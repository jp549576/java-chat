#!/usr/bin/env bash
# Runs the Java test suite (no external libraries needed). Exit code 0 = all passed.
cd "$(dirname "$0")"
if [ ! -f out/.built ] || [ -n "$(find src -newer out/.built)" ]; then bash build.sh || exit 1; fi
exec java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp out ChatTests "$@"
