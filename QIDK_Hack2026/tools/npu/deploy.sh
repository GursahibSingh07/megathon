#!/usr/bin/env bash
# Builds nothing; pushes an already built whisper_npud to the board and starts it.
#   tools/npu/deploy.sh [path/to/whisper_npud]
# Build it first with:  gradlew :whisper:assembleDebug   (needs the QAIRT headers, see README.md)
set -euo pipefail

ADB=${ADB:-adb}
# Git Bash on Windows rewrites "/data/..." into a Windows path; keep device paths as they are and
# hand adb native paths for the files on this computer.
export MSYS_NO_PATHCONV=1
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

HERE=$(cd "$(dirname "$0")" && pwd)
BIN=${1:-$(find "$HERE/../../whisper/build" -name whisper_npud -type f -path '*arm64-v8a*' | head -1)}
DEST=/data/local/tmp/whisper_npu

if [ -z "$BIN" ] || [ ! -f "$BIN" ]; then
    echo "whisper_npud not found. Build it first: gradlew :whisper:assembleDebug" >&2
    exit 1
fi

$ADB shell "mkdir -p $DEST"
$ADB push "$(native "$BIN")" "$DEST/whisper_npud"
$ADB push "$(native "$HERE/multilingual.tiktoken")" "$DEST/multilingual.tiktoken"
$ADB push "$(native "$HERE/start_npud.sh")" "$DEST/start_npud.sh"
$ADB shell "chmod 755 $DEST/whisper_npud $DEST/start_npud.sh"
$ADB shell "sh $DEST/start_npud.sh"
