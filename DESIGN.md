# Design decisions

This file explains the choices that affect the whole project. Choices specific to one
milestone are explained in that milestone's README ("Design choices (and why)").

## 1. Project shape

**One self-contained folder per milestone, not one evolving folder.**
The assignment asks for "7 pieces, each a working, testable program" and wants to
"understand what is changed and why". A folder per milestone can be run on its own
at any moment of a presentation, and `CHANGES.diff` (or `diff -ru`) shows the exact
change. The price is duplicated files (e.g. `Json.java` is copied into M1–M6); for
teaching, visible history is worth more than no duplication. In a real product this
would be one folder with git tags `m0`…`m6`.

**Plain `javac` + bash scripts, no Maven/Gradle.** Nothing to install beyond a JDK,
nothing hidden: `build.sh` is one `javac` line. The run scripts recompile only when
a source file is newer than the last build.

**Java 17 bytecode (`--release 17`), standard library only.** 17 is the oldest
long-term-support version still common on student Macs; anything newer runs it.
We use a few modern features (records, `switch` on strings, `String.strip`,
`Stream.toList`) but nothing that needs 21. No external libraries, as the
assignment requires — which is why we wrote our own JSON class (section 4) and our
own small test runner (section 9).

**Default package, one `src/` folder.** Every class is a few hundred lines at most;
packages would add folders and `import` lines without adding clarity.

## 2. Concurrency model

| Milestone | Model | Why it changes |
|---|---|---|
| M0, M1 | one client at a time, single thread | simplest; isolates TCP and framing |
| M2, M3 | one thread per client; writes are synchronized per socket | many clients; slide's "one thread per client" |
| M4+ | per client: reader thread + writer thread with a bounded queue | a stuck client must not block anyone |

**Why not `selectors` (java.nio)?** One thread then serves everybody, but the code
becomes a state machine: per-client buffers for reads *and* partial writes, interest
sets, and attachments. It hides the protocol behind plumbing. With threads, each
client's code reads top-to-bottom like the protocol.

**Why not virtual threads (Java 21)?** They would be a one-line change
(`Thread.ofVirtual().start(handler)`) and allow thousands of clients, but would
force Java 21 on every machine. A class chat has tens of clients; platform threads
are fine.

**Ownership rules (M4+):** only the reader thread reads a socket and performs the
final close; only the writer thread writes it; any thread may *queue* a message. The
client registry is the only shared structure and is always used under one lock.
When something must happen "atomically" (nick check + insert + OK; room change + OK)
it happens inside that one lock, and the OK is only queued, never written, under the
lock, so a lock is never held during network I/O.

## 3. Wire format: framing and encoding

- **One JSON object per line, `\n`-terminated** (delimiter framing). Readable in
  `nc` and Wireshark, trivially implemented in every language (`split("\n")`), and
  JSON already escapes newlines in strings. Length-prefix framing would be binary and
  error-prone across seven implementations; fixed-size records waste space and cap
  message length.
- **UTF-8 explicitly, everywhere.** Never the platform default: sockets, keyboard,
  terminal output (the scripts force it), log file. Lines are split on the byte
  `0x0A` *before* decoding, so characters cut by TCP survive.
- **Size limit (M4): 64 KiB per line.** Protects memory; generous for chat.

## 4. JSON

`Json.java` is a small recursive-descent parser and writer (~250 lines, identical in
M1–M6), because the JDK has no JSON API. Objects become `LinkedHashMap` (keeps
`"type"` first when writing), arrays `ArrayList`, numbers `Long` or `Double`. It
accepts everything standard JSON allows (whitespace, escapes, `\uXXXX` including
emoji surrogate pairs — Python's default output) and rejects everything else with a
position, which goes straight into `ERROR 400` reasons. Nesting is limited to 32
levels so hostile input cannot overflow the stack. Output never contains a raw
newline, which is what keeps the framing safe.

## 5. Protocol semantics (full text in `m6-rooms/PROTOCOL.md`)

- **Requests vs notifications.** Client requests always get exactly **one reply**
  (`OK`, `USERS`, `ERROR`), in order. `CHAT` and `EVENT` are notifications that may
  arrive any time. One rule, no request ids, and easy to test.
- **`MSG` is delivered to everybody including the sender**, after the sender's `OK`.
  Everyone sees the same message in the same server-defined order, and the sender
  sees that it really went through the server.
- **Nick and room names:** letters/digits of any alphabet, `_`, `-`, 1–20
  characters, case-insensitive uniqueness. Portuguese names work; spaces would
  break `/msg <nick> <text>`; case-insensitivity prevents `Ana`/`ana` impersonation.
- **Errors are HTTP-like codes** (400, 401, 404, 408, 409, 413, 500) plus a text.
  Only 408 and 413 close the connection; everything else lets the session continue.
- **Extensions add fields and message types, never change existing ones** (M6 adds
  `room` fields and `JOIN`/`LEAVE`), so older clients keep working.

## 6. Interoperability strategy

The other six groups use other languages, OSes and interpretations, so:

- **Be liberal in what we accept:** `\r\n` and `\n`, empty lines, any JSON
  whitespace and escapes, unknown fields, lower-case `type`, two `DISCOVER`
  dialects, discovery replies with the port as number, string or plain text.
- **Be strict in what we send:** compact JSON, `\n`, UTF-8, exactly the fields of
  the slide table (plus documented extras).
- **Our client never blocks waiting for a specific reply.** It prints whatever comes
  and shows unknown messages raw, so a server that omits `OK` or does not echo still
  works with it.
- **Our tests double as interop tools:** the pytest suite and `demo.sh` can be
  pointed at any group's server.
- **A written spec** with MUST/SHOULD, and a short list of the questions groups
  must settle together (in the main README).

## 7. Robustness

Covered in detail in `m4-robustness/README.md`: idle timeout (read timeout),
bounded per-client send queues (Java has no write timeout), size limit, graceful
close (last message → FIN → drain → close, to avoid an RST destroying the last
message), idempotent cleanup, `ERROR 500` instead of dying on a bug, shutdown hook
for Ctrl+C, logging with `java.util.logging`.

## 8. macOS-specific decisions

| Decision | Reason |
|---|---|
| Default port 12345, not 5000 | macOS 12+ uses 5000 and 7000 for AirPlay Receiver |
| `-Djava.net.preferIPv4Stack=true` (M5+) | broadcast is IPv4-only; Java's dual-stack sockets on macOS can fail to send to 255.255.255.255 |
| Discovery also sends to each interface's broadcast address | macOS sends 255.255.255.255 out of one interface only |
| Server name from `--name`, not `InetAddress.getLocalHost()` | that call can block ~5 s on macOS when the Mac's own name is not resolvable |
| UTF-8 forced by the run scripts | works on Java 17 and 18+ and any terminal locale |
| Wireshark on `lo0` for local tests | macOS captures loopback natively (Windows needs Npcap) |
| README notes on firewall and Local Network permission | the two reasons a working server is invisible to other laptops |

Nothing in the code is macOS-only: the same folders run on Linux and Windows
(Windows users run `javac`/`java` directly or use Git Bash for the scripts).

## 9. Testing strategy

- **Java test runner without JUnit** (`ChatTests.java`): JUnit is not in the JDK.
  Each test starts a real server in-process on port 0, uses real sockets, and stops
  it, so tests are isolated and need no fixed port. It covers the framer and JSON
  (units) and every protocol rule, every error code, partial/merged input,
  abrupt disconnects, timeouts, size limits, slow consumers, shutdown, discovery
  and rooms.
- **pytest black-box suite** (`tests/test_protocol.py`): what the assignment asks
  for, and language-independent, so it runs against other groups' servers.
- During development the tests found a real race (a connection accepted at the
  exact moment of shutdown was never closed) and showed that a graceful close needs
  the FIN-and-drain step, otherwise the final `ERROR 413` was lost to a TCP reset.

## 10. Deliberately left out

No authentication or passwords, no message history/persistence, no rate limiting,
no TLS (that was extension B), no nick change after registration. Each would be a
milestone of its own; leaving them out keeps every milestone focused on what the
assignment asks.
