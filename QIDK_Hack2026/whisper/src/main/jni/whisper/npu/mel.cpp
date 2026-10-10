#include "mel.hpp"

#include <algorithm>
#include <cmath>

namespace npu {

namespace {

// Slaney mel scale, as in librosa's default (htk=False).
double hzToMel(double hz) {
    const double fSp = 200.0 / 3.0, minLogHz = 1000.0, minLogMel = minLogHz / fSp;
    const double logStep = std::log(6.4) / 27.0;
    return hz >= minLogHz ? minLogMel + std::log(hz / minLogHz) / logStep : hz / fSp;
}

double melToHz(double mel) {
    const double fSp = 200.0 / 3.0, minLogHz = 1000.0, minLogMel = minLogHz / fSp;
    const double logStep = std::log(6.4) / 27.0;
    return mel >= minLogMel ? minLogHz * std::exp(logStep * (mel - minLogMel)) : fSp * mel;
}

}  // namespace

MelFrontend::MelFrontend() {
    // Periodic Hann window (what torch.hann_window uses), not the symmetric one.
    window_.resize(kFft);
    for (int i = 0; i < kFft; i++) {
        window_[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * M_PI * i / kFft));
    }

    twiddle_.resize(kFft);
    for (int k = 0; k < kFft; k++) {
        double a = -2.0 * M_PI * k / kFft;
        twiddle_[k] = Cpx(static_cast<float>(std::cos(a)), static_cast<float>(std::sin(a)));
    }

    // Mel filter bank (librosa.filters.mel, sr=16000, n_fft=400, n_mels=80, slaney norm).
    const double fMax = kSampleRate / 2.0;
    std::vector<double> melPoints(kMels + 2);
    const double melMin = hzToMel(0.0), melMax = hzToMel(fMax);
    for (int i = 0; i < kMels + 2; i++) {
        melPoints[i] = melToHz(melMin + (melMax - melMin) * i / (kMels + 1));
    }
    filters_.assign(static_cast<size_t>(kMels) * kBins, 0.0f);
    filterStart_.assign(kMels, kBins);
    filterEnd_.assign(kMels, 0);
    for (int m = 0; m < kMels; m++) {
        const double lo = melPoints[m], mid = melPoints[m + 1], hi = melPoints[m + 2];
        const double norm = 2.0 / (hi - lo);
        for (int k = 0; k < kBins; k++) {
            const double f = fMax * k / (kBins - 1);
            const double w = std::max(0.0, std::min((f - lo) / (mid - lo), (hi - f) / (hi - mid)));
            if (w > 0.0) {
                filters_[static_cast<size_t>(m) * kBins + k] = static_cast<float>(w * norm);
                filterStart_[m] = std::min(filterStart_[m], k);
                filterEnd_[m] = std::max(filterEnd_[m], k + 1);
            }
        }
    }
}

// Mixed-radix decimation-in-time FFT for n dividing kFft (400 = 2^4 * 5^2).
void MelFrontend::fft(const Cpx* in, Cpx* out, int n, int stride) const {
    if (n == 1) {
        out[0] = in[0];
        return;
    }
    const int p = (n % 2 == 0) ? 2 : (n % 5 == 0 ? 5 : n);
    const int m = n / p;
    for (int r = 0; r < p; r++) fft(in + r * stride, out + r * m, m, stride * p);

    const int tStep = kFft / n;  // W_n^j = twiddle[(j * kFft / n) mod kFft]
    Cpx y[5];
    for (int k = 0; k < m; k++) {
        for (int r = 0; r < p; r++) y[r] = out[r * m + k];
        for (int q = 0; q < p; q++) {
            Cpx sum = y[0];
            for (int r = 1; r < p; r++) {
                sum += y[r] * twiddle_[(static_cast<long>(r) * (k + q * m) * tStep) % kFft];
            }
            out[k + q * m] = sum;
        }
    }
}

void MelFrontend::powerSpectra(const float* a, const float* b, float* powA, float* powB) const {
    Cpx in[kFft], out[kFft];
    for (int i = 0; i < kFft; i++) in[i] = Cpx(a[i] * window_[i], b[i] * window_[i]);
    fft(in, out, kFft, 1);
    for (int k = 0; k < kBins; k++) {
        const Cpx z = out[k];
        const Cpx zc = std::conj(out[(kFft - k) % kFft]);
        const Cpx xa = 0.5f * (z + zc);                    // spectrum of frame a
        const Cpx xb = Cpx(0.0f, -0.5f) * (z - zc);        // spectrum of frame b
        powA[k] = std::norm(xa);
        powB[k] = std::norm(xb);
    }
}

void MelFrontend::compute(const float* pcm, int n, float* out) const {
    n = std::min(n, kSamples);

    // 30 s of audio, then reflect-padded by half a window on each side (center=True).
    std::vector<float> padded(static_cast<size_t>(kSamples) + kFft, 0.0f);
    const int half = kFft / 2;
    std::copy(pcm, pcm + n, padded.begin() + half);
    for (int i = 1; i <= half; i++) {
        padded[half - i] = (i < kSamples) ? padded[half + i] : 0.0f;
        padded[half + kSamples - 1 + i] = padded[half + kSamples - 1 - i];
    }

    std::vector<float> logMel(static_cast<size_t>(kMels) * kFrames);
    float pa[kBins], pb[kBins];
    float maxLog = -1e30f;
    for (int t = 0; t < kFrames; t += 2) {
        const bool pair = t + 1 < kFrames;
        const float* fa = padded.data() + static_cast<size_t>(t) * kHop;
        const float* fb = pair ? fa + kHop : fa;
        powerSpectra(fa, fb, pa, pb);
        for (int m = 0; m < kMels; m++) {
            const float* f = &filters_[static_cast<size_t>(m) * kBins];
            float sa = 0.0f, sb = 0.0f;
            for (int k = filterStart_[m]; k < filterEnd_[m]; k++) {
                sa += f[k] * pa[k];
                sb += f[k] * pb[k];
            }
            const float la = std::log10(std::max(sa, 1e-10f));
            logMel[static_cast<size_t>(m) * kFrames + t] = la;
            maxLog = std::max(maxLog, la);
            if (pair) {
                const float lb = std::log10(std::max(sb, 1e-10f));
                logMel[static_cast<size_t>(m) * kFrames + t + 1] = lb;
                maxLog = std::max(maxLog, lb);
            }
        }
    }

    const float floorLog = maxLog - 8.0f;
    for (size_t i = 0; i < logMel.size(); i++) {
        out[i] = (std::max(logMel[i], floorLog) + 4.0f) / 4.0f;
    }
}

}  // namespace npu
