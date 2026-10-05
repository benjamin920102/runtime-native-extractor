#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}" 
rm -rf "$ROOT/build/native-linux" "$ROOT/dist-linux"
cmake -S "$ROOT/native" -B "$ROOT/build/native-linux" -DCMAKE_BUILD_TYPE=Release
cmake --build "$ROOT/build/native-linux" -j
(cd "$ROOT/extractor" && ant clean build)
mkdir -p "$ROOT/dist-linux"
cp "$ROOT/build/native-linux/lib/libnative_recovery_agent.so" "$ROOT/dist-linux/"
cp "$ROOT/extractor/build/extractor.jar" "$ROOT/dist-linux/"
echo "Native agent + extractor smoke build complete. Full recovery runtime is built by Gradle (see README)."
