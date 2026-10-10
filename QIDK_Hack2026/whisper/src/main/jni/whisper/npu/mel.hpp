// Whisper's log-mel front end: 16 kHz mono PCM -> [80 mel bins x 3000 frames] for a 30 s window.
//
// Matches OpenAI's reference (log_mel_spectrogram): zero-pad to 30 s, reflect-padded STFT with a
// 400-sample periodic Hann window and a 160-sample hop, Slaney-scale mel filters, log10, clamp to
// 8 dB below the loudest bin, then (x + 4) / 4.
#pragma once

#include <complex>
#include <vector>

namespace npu {

class MelFrontend {
public:
    static constexpr int kSampleRate = 16000;
    static constexpr int kFft = 400;
    static constexpr int kHop = 160;
    static constexpr int kMels = 80;
    static constexpr int kFrames = 3000;                      // 30 s
    static constexpr int kSamples = kFrames * kHop;           // 480000
    static constexpr int kBins = kFft / 2 + 1;                // 201

    MelFrontend();

    /** Writes kMels * kFrames floats, mel-major: out[m * kFrames + t]. Audio beyond 30 s is ignored. */
    void compute(const float* pcm, int n, float* out) const;

private:
    using Cpx = std::complex<float>;

    // Transforms two real frames at once (packed as real and imaginary parts of one complex FFT).
    void powerSpectra(const float* a, const float* b, float* powA, float* powB) const;
    void fft(const Cpx* in, Cpx* out, int n, int stride) const;

    std::vector<float> window_;
    std::vector<Cpx> twiddle_;                // exp(-2*pi*i*k/kFft)
    std::vector<float> filters_;              // kMels x kBins
    std::vector<int> filterStart_, filterEnd_;
};

}  // namespace npu
