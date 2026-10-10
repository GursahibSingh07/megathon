// whisper_npud: Whisper-Small on the NPU as a small local service.
//
//   whisper_npud --enc E.bin --dec D.bin --vocab multilingual.tiktoken --lib <QAIRT lib dir>
//                [--adsp <hexagon skel dir>] [--port 5051]      serve over 127.0.0.1
//   whisper_npud ... --wav clip.wav [--runs 5]                  transcribe a file and print timings
//
// Wire protocol (little-endian), repeated for as many requests as the client sends on a connection:
//   request : u32 sampleCount, then sampleCount float32 samples (16 kHz mono, -1..1; at most 30 s)
//   response: u32 status (0 = ok), u32 textLen, text (UTF-8), u32 statsLen, stats (UTF-8)
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "whisper_npu.hpp"

namespace {

constexpr uint32_t kMaxSamples = npu::MelFrontend::kSamples;

bool readAll(int fd, void* buf, size_t n) {
    auto* p = static_cast<uint8_t*>(buf);
    while (n > 0) {
        ssize_t r = ::read(fd, p, n);
        if (r <= 0) return false;
        p += r;
        n -= static_cast<size_t>(r);
    }
    return true;
}

bool writeAll(int fd, const void* buf, size_t n) {
    auto* p = static_cast<const uint8_t*>(buf);
    while (n > 0) {
        ssize_t w = ::write(fd, p, n);
        if (w <= 0) return false;
        p += w;
        n -= static_cast<size_t>(w);
    }
    return true;
}

bool sendResponse(int fd, uint32_t status, const std::string& text, const std::string& stats) {
    const uint32_t tl = static_cast<uint32_t>(text.size()), sl = static_cast<uint32_t>(stats.size());
    return writeAll(fd, &status, 4) && writeAll(fd, &tl, 4) && writeAll(fd, text.data(), tl) &&
           writeAll(fd, &sl, 4) && writeAll(fd, stats.data(), sl);
}

std::string describe(const npu::Stats& st) {
    char buf[200];
    std::snprintf(buf, sizeof(buf),
                  "mel %.0f ms, encoder %.0f ms, decoder %.0f ms (%d prompt steps + %d tokens)",
                  st.melMs, st.encoderMs, st.decoderMs, st.promptSteps, st.newTokens);
    return buf;
}

void serve(npu::WhisperNpu& engine, int port) {
    signal(SIGPIPE, SIG_IGN);
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // this device only
    if (bind(srv, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 || listen(srv, 4) != 0) {
        std::perror("bind/listen");
        std::exit(1);
    }
    std::printf("listening on 127.0.0.1:%d\n", port);
    std::fflush(stdout);

    for (;;) {
        int fd = accept(srv, nullptr, nullptr);
        if (fd < 0) continue;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

        uint32_t n;
        while (readAll(fd, &n, 4)) {
            if (n == 0 || n > kMaxSamples) {
                sendResponse(fd, 2, "", "bad sample count");
                break;
            }
            std::vector<float> pcm(n);
            if (!readAll(fd, pcm.data(), static_cast<size_t>(n) * 4)) break;

            npu::Stats st;
            std::string err;
            const std::string text = engine.transcribe(pcm.data(), static_cast<int>(n), &st, &err);
            if (!err.empty()) {
                if (!sendResponse(fd, 1, "", err)) break;
                continue;
            }
            std::printf("%u samples -> \"%s\" [%s]\n", n, text.c_str(), describe(st).c_str());
            std::fflush(stdout);
            if (!sendResponse(fd, 0, text, describe(st))) break;
        }
        close(fd);
    }
}

// 16-bit PCM WAV with a plain 44-byte header, 16 kHz mono (what ffmpeg writes with -ar 16000 -ac 1).
bool readWav(const std::string& path, std::vector<float>* out) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    const std::streamsize size = f.tellg();
    if (size <= 44) return false;
    f.seekg(44);
    std::vector<int16_t> pcm(static_cast<size_t>(size - 44) / 2);
    f.read(reinterpret_cast<char*>(pcm.data()), static_cast<std::streamsize>(pcm.size() * 2));
    out->resize(pcm.size());
    for (size_t i = 0; i < pcm.size(); i++) (*out)[i] = pcm[i] / 32768.0f;
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    npu::Config cfg;
    std::string libDir, adsp, wav;
    int port = 5051, runs = 3;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string k = argv[i], v = argv[i + 1];
        if (k == "--enc") cfg.encoderBin = v;
        else if (k == "--dec") cfg.decoderBin = v;
        else if (k == "--vocab") cfg.vocabPath = v;
        else if (k == "--lib") libDir = v;
        else if (k == "--adsp") adsp = v;
        else if (k == "--port") port = std::atoi(v.c_str());
        else if (k == "--wav") wav = v;
        else if (k == "--runs") runs = std::atoi(v.c_str());
    }
    if (cfg.encoderBin.empty() || cfg.decoderBin.empty() || cfg.vocabPath.empty() || libDir.empty()) {
        std::fprintf(stderr, "usage: whisper_npud --enc E --dec D --vocab V --lib DIR [--adsp DIR] [--port N | --wav F]\n");
        return 2;
    }
    cfg.backendLib = libDir + "/libQnnHtp.so";
    cfg.systemLib = libDir + "/libQnnSystem.so";
    // The DSP finds its half of the runtime (libQnnHtpV75Skel.so) through this path.
    if (!adsp.empty()) setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    npu::WhisperNpu engine;
    std::string err;
    const auto t0 = std::chrono::steady_clock::now();
    if (!engine.init(cfg, &err)) {
        std::fprintf(stderr, "init failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("models loaded in %.0f ms\n",
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());

    if (!wav.empty()) {
        std::vector<float> pcm;
        if (!readWav(wav, &pcm)) {
            std::fprintf(stderr, "cannot read %s\n", wav.c_str());
            return 1;
        }
        std::printf("audio: %.2f s\n", pcm.size() / 16000.0);
        for (int r = 0; r < runs; r++) {
            npu::Stats st;
            const auto s = std::chrono::steady_clock::now();
            const std::string text = engine.transcribe(pcm.data(), static_cast<int>(pcm.size()), &st, &err);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s).count();
            std::printf("run %d: %.0f ms -> \"%s\"  [%s]\n", r + 1, ms, text.c_str(), describe(st).c_str());
            if (!err.empty()) std::printf("  error: %s\n", err.c_str());
        }
        return 0;
    }
    serve(engine, port);
    return 0;
}
