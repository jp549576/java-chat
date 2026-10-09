#!/usr/bin/env bash
# Starts the echo server (builds first if a source file changed).
# Usage: ./server.sh [port]
cd "$(dirname "$0")"
if [ ! -f out/.built ] || [ -n "$(find src -newer out/.built)" ]; then bash build.sh || exit 1; fi
# Force UTF-8 on the terminal whatever the Java version/locale (olá, ação, emoji).
UTF8="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
exec java $UTF8 -cp out EchoServer "$@"
