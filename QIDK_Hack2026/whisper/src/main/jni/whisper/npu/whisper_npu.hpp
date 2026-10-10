// Whisper-Small on the Hexagon NPU: log-mel -> encoder (one run) -> decoder (one run per token).
//
// Drives the two precompiled QNN context binaries exported by Qualcomm AI Hub
// (HfWhisperEncoder / HfWhisperDecoder). Their shapes are fixed: the encoder always sees a 30 s
// window, and the decoder takes one token per call with a rolling 199-entry self-attention cache.
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "mel.hpp"
#include "qnn_runtime.hpp"

namespace npu {

struct Config {
    std::string encoderBin;
    std::string decoderBin;
    std::string vocabPath;     // multilingual.tiktoken
    std::string backendLib;    // libQnnHtp.so
    std::string systemLib;     // libQnnSystem.so
};

struct Stats {
    double melMs = 0, encoderMs = 0, decoderMs = 0;
    int promptSteps = 0, newTokens = 0;
};

class WhisperNpu {
public:
    bool init(const Config& cfg, std::string* err);

    /** Transcribes up to 30 s of 16 kHz mono audio (values in [-1, 1]). English, no timestamps. */
    std::string transcribe(const float* pcm, int n, Stats* stats, std::string* err);

private:
    static constexpr int kLayers = 12;
    static constexpr int kVocab = 51865;
    static constexpr int kMaskLen = 200;             // 199 cached + the current token
    static constexpr int kSelfLen = kMaskLen - 1;
    static constexpr int kCrossElems = 12 * 64 * 1500;
    static constexpr int kSelfElems = 12 * 64 * kSelfLen;

    bool loadVocab(const std::string& path, std::string* err);
    bool bindTensors(std::string* err);
    void buildSuppressList();
    int pickToken(float* logits, bool firstStep) const;
    std::string detokenize(const std::vector<int>& tokens) const;

    Runtime runtime_;
    MelFrontend mel_;
    std::unique_ptr<Graph> encoder_, decoder_;

    std::vector<std::string> vocab_;                 // token id -> bytes
    std::vector<int> suppress_;                      // ids never allowed in the output text

    std::vector<float> melBuf_;
    std::vector<std::vector<float>> crossK_, crossV_;               // shared encoder output -> decoder input
    std::vector<std::vector<float>> selfK_[2], selfV_[2];           // ping-pong self-attention caches
    std::vector<float> logits_, mask_;

    // Tensor indices, looked up once.
    int encIn_ = -1;
    std::vector<int> encOutK_, encOutV_;
    int decIds_ = -1, decPos_ = -1, decMask_ = -1, decLogits_ = -1;
    std::vector<int> decCrossK_, decCrossV_, decSelfKIn_, decSelfVIn_, decSelfKOut_, decSelfVOut_;
};

}  // namespace npu
