# M1 — Framing and a protocol

**Goal:** newline-delimited JSON messages; a receive buffer that handles partial
and merged messages; the messages `NICK` and `MSG`.

## What changed since M0  (full diff: `CHANGES.diff`)

| File | Status | Why |
|---|---|---|
| `src/LineFramer.java` | **new** | the recv buffer: bytes in, complete lines out |
| `src/Json.java` | **new** | JSON reader/writer (Java has no JSON in its standard library) |
| `src/Protocol.java` | **new** | builds every message in one place |
| `src/Cli.java` | **new** | `--port`, `--host`, `--nick` options with defaults |
| `src/ChatServer.java` | **new** (replaces EchoServer) | understands NICK and MSG |
| `src/ChatClient.java` | **new** (replaces EchoClient) | keyboard thread + receiver thread |
| `src/FramingDemo.java` | **new** | showcase tool: sends partial / merged / cut-UTF-8 data on purpose |

Still **one client at a time**: this milestone is only about framing.

## Run it

```bash
./server.sh                         # terminal 1
./client.sh --nick ana              # terminal 2: type messages
./demo.sh                           # terminal 3 (after closing the client): the framing demo
```

## Showcase

1. Run `./demo.sh` and put its output next to the **server log**:

   ```
   read 11 byte(s) -> 0 complete message(s), 11 byte(s) waiting for the rest
   read 15 byte(s) -> 0 complete message(s), 26 byte(s) waiting for the rest
   read 6 byte(s)  -> 1 complete message(s), 0 byte(s) waiting for the rest   <- NICK in 3 pieces
   read 61 byte(s) -> 2 complete message(s), 0 byte(s) waiting for the rest   <- 2 MSG in 1 read
   read 29 byte(s) -> 0 complete message(s), 29 byte(s) waiting ...
   read 5 byte(s)  -> 1 complete message(s) ...   MSG from framer: olá 😀       <- emoji cut in half
   ```
   In Wireshark (`lo0`, `tcp.port == 12345`) the same pieces are separate TCP segments.
2. Speak the protocol by hand, to show it is just text:
   `nc 127.0.0.1 12345`, then type `{"type":"NICK","nick":"ana"}` and
   `{"type":"MSG","text":"hi"}`.
3. Point `./demo.sh --host <ip>` at **another group's server**: a quick way to check
   that *their* receive buffer handles fragments too.

## Design choices (and why)

- **Framing = one JSON object per line, ending with `\n`.** The slides list three
  options (fixed size, delimiter, length prefix). A delimiter is the only one you can
  type and read by hand (`nc`, Wireshark's Follow TCP Stream), and JSON already
  escapes newlines inside strings as `\n`, so no extra escaping scheme is needed.
  A length prefix would need binary handling in every language of every group.
- **Split on the byte `0x0A` first, decode UTF-8 afterwards.** TCP can cut a
  multi-byte character (`ã` is 2 bytes, an emoji 4) between two reads. Decoding
  each chunk separately corrupts it; splitting first is safe because in UTF-8
  the byte `0x0A` never appears inside a multi-byte character. The demo proves it.
- **Our own `LineFramer` instead of `BufferedReader.readLine()`.** `readLine()`
  would hide the very thing this milestone is about, has no size limit (memory
  attack, fixed in M4), and also treats a lone `\r` as end of line.
- **Be liberal in what you accept:** `\r\n` (Windows clients), empty lines, any
  JSON whitespace, `\u00e1` escapes (Python's default `json.dumps` output), fields in
  any order, unknown extra fields, and `"nick"` in lower case. Seven groups will
  produce seven slightly different JSON styles.
- **Be strict in what you send:** compact JSON, `"type"` first (readable in
  Wireshark), raw UTF-8, exactly one `\n`, the whole message in **one** `write()`.
- **Our own JSON class** (`Json.java`, ~250 lines). The rule is "standard library
  only" and Java has no JSON API. It maps JSON to plain `Map`/`List`/`String`/`Long`/
  `Double`/`Boolean`, rejects invalid JSON with a position, and limits nesting depth
  (so `[[[[...` cannot overflow the stack). The file is identical in M1–M6.
- **`Protocol.java`**: every message is built in one place, so the wire format is
  defined once for client and server; later milestones add to this file.
- **MSG gets `OK`, then a `CHAT` that goes to "everyone"** — which, with one client,
  is the sender itself. That way M2 only has to change who "everyone" is.
- **Client = two threads.** Reading the keyboard blocks; messages must still appear
  while you type, so a second thread reads the socket. Only the main thread writes
  to the socket, so the client needs no locks.
- **Invalid input is only logged** in M1 (and M2). Answering it properly with error
  codes is M3's job, and keeping M1 small keeps the framing code easy to see.
