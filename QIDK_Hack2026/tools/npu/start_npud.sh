#!/system/bin/sh
# Runs on the QIDK board. (Re)starts whisper_npud in the background.
#
# The paths below are where the board image keeps things; override them with environment variables.
D=${NPUD_DIR:-/data/local/tmp/whisper_npu}                    # whisper_npud + vocabulary
Q=${QAIRT_HOME:-/data/local/tmp/qairt-whisper}                # Qualcomm AI Runtime 2.39 (must match the binaries)
B=${WHISPER_BUNDLE:-/data/local/tmp/whisper_bundle}           # HfWhisperEncoder.bin / HfWhisperDecoder.bin
PORT=${NPUD_PORT:-5051}

killall whisper_npud 2>/dev/null
sleep 1

export LD_LIBRARY_PATH="$Q/lib/aarch64-android"
cd "$D" || exit 1
setsid nohup ./whisper_npud \
    --enc "$B/HfWhisperEncoder.bin" \
    --dec "$B/HfWhisperDecoder.bin" \
    --vocab "$D/multilingual.tiktoken" \
    --lib "$Q/lib/aarch64-android" \
    --adsp "$Q/lib/hexagon-v75/unsigned" \
    --port "$PORT" > "$D/npud.log" 2>&1 < /dev/null &

# Wait for the models to load (about a second) and report.
i=0
while [ $i -lt 20 ]; do
    if grep -q "listening" "$D/npud.log" 2>/dev/null; then
        echo "whisper_npud is up: $(cat "$D/npud.log" | tr '\n' ' ')"
        exit 0
    fi
    sleep 1
    i=$((i + 1))
done
echo "whisper_npud did not start; log:"
cat "$D/npud.log"
exit 1
