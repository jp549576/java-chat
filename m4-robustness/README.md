# M4 — Robustness and tests

**Goal:** idle timeout, clean handling of abrupt disconnects and bad JSON, Ctrl+C
shutdown that closes sockets, logging, automated tests with pytest.

## What changed since M3  (full diff: `CHANGES.diff`)

| File | Status | Why |
|---|---|---|
| `src/ClientHandler.java` | rewritten | reader thread + **writer thread with a bounded send queue**; idle timeout; graceful close; ERROR 500 instead of a dead thread |
| `src/ChatServer.java` | rewritten | `Config`; `start()`/`stop()`; shutdown hook; `java.util.logging` to console + `server.log` |
| `src/LineFramer.java` | + size limit | `LineTooLongException` above `--max-line` (default 64 KiB) |
| `src/Protocol.java` | + codes | 408 idle timeout, 413 too long, 500 internal error |
| `src/ChatClient.java` | small | Ctrl+C sends QUIT before exiting |
| `src/ChatTests.java` | **new** | 20 automated tests, no external library |
| `tests/test_protocol.py` | **new** | pytest black-box tests, usable against any group's server |
| `test.sh` | **new** | runs the Java tests |
| `PROTOCOL.md` | updated | codes 408/413/500, section 7 "Limits and connection end" |

## Run it

```bash
./server.sh                         # options: --idle 300 --max-line 65536 --outbox 1000 --no-log-file
./client.sh --nick ana
./test.sh                           # Java tests: 20 passed, 0 failed (about 8 s)
```

pytest (once):

```bash
python3 -m venv .venv && source .venv/bin/activate && pip install pytest
python3 -m pytest -v tests/                       # builds and starts our server itself
CHAT_HOST=192.168.1.42 CHAT_PORT=12345 python3 -m pytest -v tests/   # another group's server
```

On macOS `python3` comes with the Xcode command line tools (`xcode-select --install`)
or Homebrew. The virtual environment avoids "externally-managed-environment" errors.

## Showcase

- `./test.sh` — every robustness feature has a test with a readable name.
  Explain *"a client that never reads cannot block the others"*: with the M3
  design, the flooding client's thread would get stuck inside `write()` to the
  frozen client and stop serving its own user; here it receives every echo.
- `./server.sh --idle 10`, connect, wait 10 s → `!!! error 408: idle timeout`, then
  the server closes the connection.
- Ctrl+C on the server → every client prints `*** server shutting down` and
  `*** server closed the connection`; `server.log` keeps the history.
- Run the pytest suite against another group's server on interop day.

## Design choices (and why)

- **A send queue + writer thread per client.** The slide says: "a slow or stuck
  client blocks everyone — use timeouts, or a send queue per client". Java's
  blocking sockets have a *read* timeout (`setSoTimeout`) but **no write timeout**, so
  the timeout option does not exist for sending. Now `send()` only does
  `queue.offer()` and returns at once; the client's own writer thread does the slow
  `write()`. If a client's queue reaches `--outbox` (1000) messages, it is too slow
  and is disconnected. Nobody else ever waits for it.
- **Bounded queue (`ArrayBlockingQueue`)**, not unbounded: an unbounded queue just
  moves the problem from "blocked thread" to "server runs out of memory".
- **"Poison pill" to stop the writer.** `sendAndClose()` queues the last message
  and then a special object; when the writer takes it, it stops. That guarantees the
  last message (OK to QUIT, ERROR 408, "server shutting down") goes out *before*
  the close, in order.
- **Graceful close: last message → FIN (`shutdownOutput`) → read and discard for up
  to 1 s → close.** If you close a socket while unread data is still arriving
  (e.g. the rest of an oversized message), the kernel sends a TCP RST, and the RST
  can destroy our ERROR before the client reads it. The `lineTooLong` test caught
  exactly this.
- **Reader thread owns the input and the final close; writer owns the output.**
  Only one thread ever reads a socket, only one ever writes it. Simple ownership,
  no locks around the socket.
- **Idle timeout = `setSoTimeout` on the reader.** A `read()` that waits longer
  throws `SocketTimeoutException` → ERROR 408 → close. 300 s by default: long enough
  for a human thinking, short enough to clear half-open connections (a laptop that
  went to sleep never sends FIN). Configurable for tests.
- **Size limit in the framer (64 KiB).** Without it a client that never sends `\n`
  grows our buffer until the JVM dies. After 413 we close, because we cannot know
  where the next message starts.
- **Abrupt disconnects are normal, not errors.** "Connection reset" / "Broken
  pipe" give one INFO line, no stack trace. Cleanup is idempotent (`AtomicBoolean`),
  because the reader, the writer, a broadcaster and the shutdown hook may all try
  to close the same connection.
- **A bug while handling one message → ERROR 500**, the connection and server live on.
- **Server is an object with `start()`/`stop()`/`port()`.** Tests start a fresh
  server per test on port 0 (any free port) inside the test JVM: fast, isolated,
  no fixed ports that might be busy.
- **Ctrl+C = shutdown hook.** On SIGINT/SIGTERM the JVM runs hooks: we close the
  server socket (unblocks `accept()`), send "server shutting down" to every
  connection (also those that never sent NICK — we keep a separate set of all
  connections for that) and give writers 1.5 s to flush.
- **Race found by the tests:** a client accepted at the exact moment of `stop()` was
  never closed. Fix: add to the set first, then check `running`.
- **`java.util.logging`**, the standard-library equivalent of Python's `logging`,
  with a one-line format (time, level, thread, message) to the terminal and appended
  to `server.log` (UTF-8). Lines written during Ctrl+C go to `System.err` directly:
  the logging framework has its own shutdown hook that may close its handlers first
  (we saw those lines vanish from `server.log` in testing).
- **Java tests without JUnit.** JUnit is not part of the JDK; a 40-line mini test
  runner keeps the project "standard library only" and runs with `./test.sh` and
  nothing to install. Exit code 0/1 so it works in scripts.
- **pytest tests as a black box over TCP.** The assignment names pytest, and a test
  that only uses sockets works for *any* server. That turns the test suite into an
  interop tool: point it at another group's server with `CHAT_HOST`. Tests that
  depend on our own limits are marked and skipped there.
- **Client Ctrl+C sends QUIT** (shutdown hook) so the server logs a clean goodbye
  instead of a reset.
