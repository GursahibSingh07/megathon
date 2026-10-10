#include "whisper_npu.hpp"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <unordered_map>

#define TAG "WhisperNpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace npu {

namespace {

// Token ids of the multilingual Whisper vocabulary.
constexpr int kEot = 50257;
constexpr int kStartOfTranscript = 50258;
constexpr int kEnglish = 50259;
constexpr int kTranscribe = 50359;
constexpr int kNoTimestamps = 50363;
constexpr int kFirstSpecial = 50258;   // everything from here up is a control token, never text
constexpr int kSpace = 220;            // " "
constexpr int kMaxNewTokens = 190;

double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

std::string base64Decode(const std::string& s) {
    static int8_t table[256];
    static bool ready = false;
    if (!ready) {
        std::fill(table, table + 256, static_cast<int8_t>(-1));
        const char* chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 64; i++) table[static_cast<unsigned char>(chars[i])] = static_cast<int8_t>(i);
        ready = true;
    }
    std::string out;
    int val = 0, bits = -8;
    for (unsigned char c : s) {
        if (table[c] < 0) break;  // '=' padding
        val = (val << 6) + table[c];
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<char>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

}  // namespace

bool WhisperNpu::loadVocab(const std::string& path, std::string* err) {
    std::ifstream f(path);
    if (!f) {
        if (err) *err = "cannot open vocab " + path;
        return false;
    }
    vocab_.assign(kVocab, std::string());
    std::string line;
    int count = 0;
    while (std::getline(f, line)) {
        const size_t sp = line.find(' ');
        if (sp == std::string::npos) continue;
        const int rank = std::atoi(line.c_str() + sp + 1);
        if (rank < 0 || rank >= kEot) continue;
        vocab_[rank] = base64Decode(line.substr(0, sp));
        count++;
    }
    if (count != kEot) {
        if (err) *err = "vocab has " + std::to_string(count) + " tokens, expected " + std::to_string(kEot);
        return false;
    }
    return true;
}

// Whisper's own list of "non-speech" tokens (symbols, brackets, music notes) that it never emits when
// transcribing. Whisper builds it by tokenizing each symbol; here a symbol counts when it is a single
// vocabulary entry, with or without a leading space.
void WhisperNpu::buildSuppressList() {
    std::unordered_map<std::string, int> idOf;
    for (int i = 0; i < kEot; i++) idOf.emplace(vocab_[i], i);

    std::vector<std::string> symbols;
    for (const char* c : {"\"", "#", "(", ")", "*", "+", "/", ":", ";", "<", "=", ">", "@", "[", "\\", "]",
                          "^", "_", "`", "{", "|", "}", "~", "「", "」", "『", "』"}) {
        symbols.push_back(c);
    }
    for (const char* c : {"<<", ">>", "<<<", ">>>", "--", "---", "-(", "-[", "('", "(\"", "((", "))", "(((",
                          ")))", "[[", "]]", "{{", "}}", "♪♪", "♪♪♪", "♩", "♪", "♫", "♬", "♭", "♮", "♯"}) {
        symbols.push_back(c);
    }
    suppress_.clear();
    for (const char* extra : {" -", " '"}) {
        auto it = idOf.find(extra);
        if (it != idOf.end()) suppress_.push_back(it->second);
    }
    for (const auto& s : symbols) {
        for (const auto& key : {s, " " + s}) {
            auto it = idOf.find(key);
            if (it != idOf.end()) suppress_.push_back(it->second);
        }
    }
    std::sort(suppress_.begin(), suppress_.end());
    suppress_.erase(std::unique(suppress_.begin(), suppress_.end()), suppress_.end());
}

bool WhisperNpu::bindTensors(std::string* err) {
    auto in = [&](Graph& g, const std::string& n) { return g.inputIndex(n); };
    auto out = [&](Graph& g, const std::string& n) { return g.outputIndex(n); };

    encIn_ = in(*encoder_, "input_features");
    decIds_ = in(*decoder_, "input_ids");
    decPos_ = in(*decoder_, "position_ids");
    decMask_ = in(*decoder_, "attention_mask");
    decLogits_ = out(*decoder_, "logits");
    if (encIn_ < 0 || decIds_ < 0 || decPos_ < 0 || decMask_ < 0 || decLogits_ < 0) {
        if (err) *err = "unexpected encoder/decoder inputs (is this the AI Hub Whisper export?)";
        return false;
    }

    for (int l = 0; l < kLayers; l++) {
        const std::string i = std::to_string(l);
        encOutK_.push_back(out(*encoder_, "k_cache_cross_" + i));
        encOutV_.push_back(out(*encoder_, "v_cache_cross_" + i));
        decCrossK_.push_back(in(*decoder_, "k_cache_cross_" + i));
        decCrossV_.push_back(in(*decoder_, "v_cache_cross_" + i));
        decSelfKIn_.push_back(in(*decoder_, "k_cache_self_" + i + "_in"));
        decSelfVIn_.push_back(in(*decoder_, "v_cache_self_" + i + "_in"));
        decSelfKOut_.push_back(out(*decoder_, "k_cache_self_" + i + "_out"));
        decSelfVOut_.push_back(out(*decoder_, "v_cache_self_" + i + "_out"));
    }
    for (const auto* v : {&encOutK_, &encOutV_, &decCrossK_, &decCrossV_, &decSelfKIn_, &decSelfVIn_,
                          &decSelfKOut_, &decSelfVOut_}) {
        if (std::find(v->begin(), v->end(), -1) != v->end()) {
            if (err) *err = "missing cache tensors in the context binaries";
            return false;
        }
    }

    // The buffers must be exactly as large as the tensors they stand in for.
    const size_t melBytes = sizeof(float) * MelFrontend::kMels * MelFrontend::kFrames;
    if (encoder_->inputBytes(encIn_) != melBytes ||
        encoder_->outputBytes(encOutK_[0]) != sizeof(float) * kCrossElems ||
        decoder_->inputBytes(decSelfKIn_[0]) != sizeof(float) * kSelfElems ||
        decoder_->inputBytes(decMask_) != sizeof(float) * kMaskLen ||
        decoder_->outputBytes(decLogits_) != sizeof(float) * kVocab) {
        if (err) *err = "tensor sizes differ from Whisper-Small; this build only supports that model";
        return false;
    }

    melBuf_.assign(static_cast<size_t>(MelFrontend::kMels) * MelFrontend::kFrames, 0.0f);
    logits_.assign(kVocab, 0.0f);
    mask_.assign(kMaskLen, 0.0f);
    crossK_.assign(kLayers, std::vector<float>(kCrossElems));
    crossV_.assign(kLayers, std::vector<float>(kCrossElems));
    for (int s = 0; s < 2; s++) {
        selfK_[s].assign(kLayers, std::vector<float>(kSelfElems));
        selfV_[s].assign(kLayers, std::vector<float>(kSelfElems));
    }

    // Bindings that never change: the encoder writes the cross-attention caches the decoder reads.
    encoder_->bindInput(encIn_, melBuf_.data());
    for (int l = 0; l < kLayers; l++) {
        encoder_->bindOutput(encOutK_[l], crossK_[l].data());
        encoder_->bindOutput(encOutV_[l], crossV_[l].data());
        decoder_->bindInput(decCrossK_[l], crossK_[l].data());
        decoder_->bindInput(decCrossV_[l], crossV_[l].data());
    }
    decoder_->bindInput(decMask_, mask_.data());
    decoder_->bindOutput(decLogits_, logits_.data());
    return true;
}

bool WhisperNpu::init(const Config& cfg, std::string* err) {
    if (!loadVocab(cfg.vocabPath, err)) return false;
    buildSuppressList();
    if (!runtime_.init(cfg.backendLib, cfg.systemLib, err)) return false;

    const double t0 = nowMs();
    encoder_ = runtime_.loadContext(cfg.encoderBin, err);
    if (!encoder_) return false;
    decoder_ = runtime_.loadContext(cfg.decoderBin, err);
    if (!decoder_) return false;
    LOGI("contexts loaded in %.0f ms", nowMs() - t0);
    return bindTensors(err);
}

int WhisperNpu::pickToken(float* logits, bool firstStep) const {
    const float ninf = -std::numeric_limits<float>::infinity();
    for (int id : suppress_) logits[id] = ninf;
    for (int id = kFirstSpecial; id < kVocab; id++) logits[id] = ninf;  // control tokens are never text
    if (firstStep) {                                                    // never start with silence
        logits[kEot] = ninf;
        logits[kSpace] = ninf;
    }
    return static_cast<int>(std::max_element(logits, logits + kVocab) - logits);
}

std::string WhisperNpu::detokenize(const std::vector<int>& tokens) const {
    std::string s;
    for (int t : tokens) {
        if (t >= 0 && t < kEot) s += vocab_[t];
    }
    const size_t first = s.find_first_not_of(' ');
    return first == std::string::npos ? std::string() : s.substr(first);
}

std::string WhisperNpu::transcribe(const float* pcm, int n, Stats* stats, std::string* err) {
    Stats local;
    Stats& st = stats ? *stats : local;
    st = Stats();

    double t = nowMs();
    mel_.compute(pcm, n, melBuf_.data());
    st.melMs = nowMs() - t;

    t = nowMs();
    if (!encoder_->execute(err)) return std::string();
    st.encoderMs = nowMs() - t;

    // Decoder: feed the fixed prompt one token at a time, then keep feeding what it predicts.
    const int prompt[] = {kStartOfTranscript, kEnglish, kTranscribe, kNoTimestamps};
    const int promptLen = 4;

    std::fill(mask_.begin(), mask_.end(), -100.0f);
    for (int s = 0; s < 2; s++) {
        for (int l = 0; l < kLayers; l++) {
            std::fill(selfK_[s][l].begin(), selfK_[s][l].end(), 0.0f);
            std::fill(selfV_[s][l].begin(), selfV_[s][l].end(), 0.0f);
        }
    }

    std::vector<int> generated;
    int32_t idBuf = 0, posBuf = 0;
    int cur = 0;
    int token = prompt[0];
    t = nowMs();
    for (int step = 0; step < kMaskLen - 1; step++) {
        if (step < promptLen) token = prompt[step];
        idBuf = token;
        posBuf = step;
        mask_[kMaskLen - 1 - step] = 0.0f;  // the cache fills from the right; unmask one more slot

        decoder_->bindInput(decIds_, &idBuf);
        decoder_->bindInput(decPos_, &posBuf);
        for (int l = 0; l < kLayers; l++) {
            decoder_->bindInput(decSelfKIn_[l], selfK_[cur][l].data());
            decoder_->bindInput(decSelfVIn_[l], selfV_[cur][l].data());
            decoder_->bindOutput(decSelfKOut_[l], selfK_[1 - cur][l].data());
            decoder_->bindOutput(decSelfVOut_[l], selfV_[1 - cur][l].data());
        }
        if (!decoder_->execute(err)) return std::string();
        cur = 1 - cur;

        if (step < promptLen - 1) {
            st.promptSteps++;
            continue;  // still reading the prompt; its predictions are not used
        }
        if (step == promptLen - 1) st.promptSteps++;

        const int next = pickToken(logits_.data(), generated.empty());
        if (next == kEot || static_cast<int>(generated.size()) >= kMaxNewTokens) break;
        generated.push_back(next);
        token = next;
    }
    st.decoderMs = nowMs() - t;
    st.newTokens = static_cast<int>(generated.size());
    return detokenize(generated);
}

}  // namespace npu
