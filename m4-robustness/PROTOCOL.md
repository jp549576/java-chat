# Chat protocol specification — version M4

This is the written spec required by milestone M3, updated in M4
(new: section 6 codes 408/413/500, section 7 limits and shutdown). It is meant to be shared with the
other groups: if your server or client follows it, it talks to ours.

The words MUST, SHOULD and MAY are used as in RFCs: MUST is required for
interoperability, SHOULD is strongly recommended, MAY is optional.

## 1. Transport

The protocol runs over **TCP**. The default server port is **12345** (every program
accepts `--port` to change it; the class should agree on one number). The server
listens on all network interfaces, not only on `127.0.0.1`.

## 2. Framing

TCP is a byte stream with no message boundaries, so the protocol adds its own:

- Every message is **one JSON object followed by one line feed** (`\n`, byte `0x0A`).
- A sender MUST NOT put a raw line feed inside a message. This is automatic: JSON
  writes a newline inside a string as the two characters `\n`.
- A receiver MUST keep a buffer, because one `recv()` can return part of a message
  or several messages at once. It cuts a message at every `0x0A` and keeps the rest.
- A receiver MUST accept `\r\n` as well as `\n` (Windows clients) and MUST ignore
  empty lines.

## 3. Encoding

All text is **UTF-8**. Non-ASCII characters MAY be sent as raw UTF-8 (`"olá"`) or as
JSON escapes (`"ol\u00e1"`, Python's default); a receiver MUST accept both.
Our programs send raw UTF-8. A receiver SHOULD split lines on the byte `0x0A`
*before* decoding UTF-8, because TCP can cut a multi-byte character in two.

## 4. Messages

Every message is a JSON object with a string field `"type"`.

- A receiver MUST ignore fields it does not know (this lets the protocol grow).
- Field order does not matter. Whitespace between JSON tokens is allowed.
- Our server matches `type` without regard to case (`"msg"` = `"MSG"`), but
  everybody SHOULD send upper case.

### Client → server (requests)

| type | fields | meaning | reply |
|---|---|---|---|
| `NICK` | `nick`: string | register a nickname; must be the first request | `OK`, or `ERROR` 400/409 |
| `MSG` | `text`: string | send `text` to everyone | `OK` (and a `CHAT` to everyone), or `ERROR` |
| `PRIV` | `to`: string, `text`: string | send `text` only to user `to` (the `/msg` command) | `OK`, or `ERROR` 404 |
| `LIST` | — | ask who is online | `USERS` |
| `QUIT` | — | leave cleanly | `OK`, then the server closes the connection |

### Server → client

| type | fields | meaning |
|---|---|---|
| `OK` | — | the last request was accepted |
| `ERROR` | `code`: integer, `reason`: string | the last request was refused (section 6) |
| `USERS` | `nicks`: array of strings | reply to `LIST`, in join order |
| `CHAT` | `from`: string, `text`: string, `private`: boolean | a message to display |
| `EVENT` | `text`: string | a notice to display, e.g. `"rui joined"`, `"rui left"` |

## 5. Semantics

**Replies vs notifications.** `OK`, `USERS` and `ERROR` are *replies*.
`CHAT` and `EVENT` are *notifications* and can arrive at any moment, also between a
request and its reply.

**One reply per request, in order.** The server answers every request with exactly
one reply, in the order the requests were received. A client can therefore tell
which request an `ERROR` refers to without any request id.

**Registration.** Before a successful `NICK`, the server answers every request except
`NICK` and `QUIT` with `ERROR 401`. After a failed `NICK` the client may try another
nick on the same connection. A second `NICK` after success is refused (`400`).

**Nicknames.** 1 to 20 characters; letters and digits of any alphabet (`joão`, `zé`),
`_` and `-`. No spaces (the `/msg <nick> <text>` command needs the space as separator).
Leading/trailing spaces are removed. Nicks are unique **ignoring case**:
if `Ana` is online, `ana` gets `409`. The server shows the nick as it was typed.

**MSG.** The server replies `OK` to the sender and then sends
`{"type":"CHAT","from":<sender>,"text":...,"private":false}` to **every** registered
client, **including the sender**. The sender's own copy shows that the message went
through the server and in which order everyone sees it.

**PRIV.** The recipient is looked up ignoring case. The recipient gets a `CHAT` with
`"private":true`; the sender gets only `OK` (its client prints the message locally).
Sending to yourself is allowed.

**EVENT.** When a client registers, every registered client (the new one included)
gets `"<nick> joined"`. When a registered client leaves for any reason (QUIT, closed
socket, crash) the others get `"<nick> left"`.

**QUIT.** The server replies `OK`, then closes the connection. Anything sent after
`QUIT` is ignored. Closing the socket without `QUIT` is also fine.

## 6. Error codes

The codes are borrowed from HTTP, so their meaning is familiar.

| code | when |
|---|---|
| 400 | line is not JSON, not an object, no `type`, unknown `type`, missing/invalid field, invalid nick, second `NICK` |
| 401 | a request other than `NICK`/`QUIT` before registration |
| 404 | `PRIV` to a nick that is not online |
| 408 | no data received for the idle timeout — **the server then closes** (new in M4) |
| 409 | nick already taken |
| 413 | message longer than the size limit — **the server then closes** (new in M4) |
| 500 | the server failed while handling the request; connection stays open (new in M4) |

After an `ERROR` the connection normally stays open: newline framing lets both
sides continue with the next line, so one bad message never kills a session.
The exceptions are 408 and 413: after an oversized message the server cannot know
where the next message starts, so it closes the connection.

## 7. Limits and connection end (new in M4)

- **Maximum message size: 65 536 bytes** (64 KiB) per line, `\n` excluded.
  Clients SHOULD stay well below it. Our client accepts up to 1 MiB from servers.
- **Idle timeout: 300 seconds** by default (`--idle`). A connection on which the
  server receives nothing for that long gets `ERROR 408` and is closed. The protocol
  has no keep-alive message; a client that wants to stay connected while idle can
  send `LIST` from time to time.
- **Slow clients.** The server queues at most 1 000 messages per client. A client
  that does not read and lets its queue fill up is disconnected (the others see
  `"<nick> left"`), so it can never slow down the rest of the chat.
- **Graceful close.** Before closing, the server sends its last message, then shuts
  down its sending side (TCP FIN) and reads for up to 1 s more, so that the last
  message is not destroyed by a TCP reset.
- **Server shutdown** (Ctrl+C): every client receives
  `{"type":"EVENT","text":"server shutting down"}` and the connection is closed.

## 8. Example session

`C:` is what the client sends, `S:` what the server sends (each line ends with `\n`).

```
C: {"type":"NICK","nick":"ana"}
S: {"type":"OK"}
S: {"type":"EVENT","text":"ana joined"}
C: {"type":"MSG","text":"olá a todos"}
S: {"type":"OK"}
S: {"type":"CHAT","from":"ana","text":"olá a todos","private":false}
C: {"type":"PRIV","to":"nobody","text":"hi"}
S: {"type":"ERROR","code":404,"reason":"no such user: nobody"}
C: {"type":"LIST"}
S: {"type":"USERS","nicks":["ana","rui"]}
C: {"type":"QUIT"}
S: {"type":"OK"}
   (server closes the connection)
```

## 9. Interoperability notes

What we **accept** (be liberal): `\r\n`, empty lines, any whitespace inside JSON,
`\uXXXX` escapes, unknown fields, lower-case `type`.

What we **send** (be strict): compact JSON, `"type"` first, raw UTF-8, one `\n`.

The pytest suite in `tests/` checks all of this over the network and can be run
against another group's server: `CHAT_HOST=<ip> CHAT_PORT=<port> python3 -m pytest -v tests/`.

Our client never *waits* for a specific reply; it prints whatever arrives and shows
unknown message types raw. So it still works with a server that, for example,
does not send `OK` after `MSG` or does not echo the sender's own `CHAT`.
These are the points to agree on with the other groups before testing together.
