# Whisper on the Snapdragon NPU (QIDK board)

Runs Whisper-Small on the Hexagon NPU and lets the keyboard use it instead of the CPU model.
The app falls back to the CPU model automatically when the NPU service is not running.

```
keyboard (app) --- audio over 127.0.0.1:5051 ---> whisper_npud --- QNN / HTP ---> Hexagon NPU
```

## Why a separate process

On this board image regular apps cannot reach the NPU: the system's FastRPC library
(`libcdsprpc.so`) is not on the public-library list and drags in a chain of private libraries, so
it cannot be shipped inside the APK either. `whisper_npud` runs as the `shell` user (no root needed),
which is how the board's other NPU demos (the Llama server) are run too.

## What it needs on the board

| Item | Where it is on the team's board |
|---|---|
| Qualcomm AI Runtime 2.39 (arm64 libs + Hexagon v75 skeleton) | `/data/local/tmp/qairt-whisper` |
| `HfWhisperEncoder.bin`, `HfWhisperDecoder.bin` (AI Hub Whisper-Small, compiled for SM8650) | `/data/local/tmp/whisper_bundle` |

The binaries are tied to the chip (SM8650) and to the QAIRT version they were built with (2.39.0).
`start_npud.sh` documents the environment variables that override these paths.

## Build

The service links against Qualcomm's headers, which are not redistributable and not in this
repository. Copy `include/QNN` from the SDK (or from the board: `qairt-whisper/include/QNN`) into
`whisper/third_party/qairt/include/QNN` (git-ignored), then:

```
gradlew :whisper:assembleDebug
```

Without the headers the build skips the service and everything else still builds (CPU only).

## Run

```
tools/npu/deploy.sh          # pushes whisper_npud + vocabulary and starts it
```

Check it from the board: `adb shell cat /data/local/tmp/whisper_npu/npud.log` should say
`listening on 127.0.0.1:5051`. Test on a file (16 kHz mono 16-bit WAV):

```
adb shell "cd /data/local/tmp/whisper_npu && LD_LIBRARY_PATH=/data/local/tmp/qairt-whisper/lib/aarch64-android \
  ./whisper_npud --enc /data/local/tmp/whisper_bundle/HfWhisperEncoder.bin \
  --dec /data/local/tmp/whisper_bundle/HfWhisperDecoder.bin --vocab multilingual.tiktoken \
  --lib /data/local/tmp/qairt-whisper/lib/aarch64-android \
  --adsp /data/local/tmp/qairt-whisper/lib/hexagon-v75/unsigned --wav clip.wav --runs 3"
```

The service stops when the board reboots; run `deploy.sh` (or `start_npud.sh` on the board) again.

## How it works

1. **Log-mel** (`npu/mel.cpp`): Whisper's front end, 80 bins x 3000 frames for a fixed 30 s window.
2. **Encoder** (one NPU run) produces the cross-attention caches.
3. **Decoder** (one NPU run per token) with a rolling 199-entry self-attention cache; greedy choice,
   English forced, no timestamps, Whisper's non-speech tokens suppressed.
4. Tokens are turned back into text with Whisper's vocabulary (`multilingual.tiktoken`, MIT, from
   github.com/openai/whisper).

Differences from the CPU path to keep in mind:

- The encoder always processes 30 s, so a short clip costs the same encoder time as a long one.
- There is no previous-text prompt: every prompt token would cost a full decoder step.
- The model is multilingual Whisper-Small; the CPU path uses the English-only `small.en`.

## Protocol

Little-endian, repeated per request on one connection.
Request: `u32 sampleCount` then that many `float32` samples (16 kHz mono, -1..1, at most 480000).
Response: `u32 status` (0 = ok), `u32 textLen`, text (UTF-8), `u32 statsLen`, stats (UTF-8).

The service listens on loopback only, but any app on the device can connect to it.

## Licensing

Qualcomm's SDK files (headers, libraries, sample code) must not be committed; they are git-ignored.
Whisper's vocabulary file is MIT licensed (OpenAI).
