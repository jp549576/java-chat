#!/usr/bin/env bash
# Compiles every .java file in src/ into out/ (bytecode that runs on Java 17 or newer).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac --release 17 -encoding UTF-8 -d out src/*.java
touch out/.built
echo "Compiled $(ls src/*.java | wc -l | tr -d ' ') source file(s) into $(pwd)/out"
