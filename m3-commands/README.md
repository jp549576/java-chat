# M3 — Commands

**Goal:** `/list`, `/msg <nick>` private messages, unique nicknames, ERROR replies
with codes, and a written protocol spec.

## What changed since M2  (full diff: `CHANGES.diff`)

| File | Status | Why |
|---|---|---|
| `PROTOCOL.md` | **new** | the written specification — share it with the other groups |
| `src/Protocol.java` | extended | PRIV, LIST, QUIT, USERS, ERROR; error-code constants; nickname rule |
| `src/ChatServer.java` | list → **map** | unique nicks, `find()` for PRIV, `nicks()` for LIST |
| `src/ClientHandler.java` | extended | one handler per request type; every request gets exactly one reply |
| `src/ChatClient.java` | extended | `/list`, `/msg`, `/nick`, `/quit`, `/help`; shows USERS, ERROR, private CHAT |

## Run it

```bash
./server.sh
./client.sh --nick ana
./client.sh --nick rui
```

Client commands: `/list`, `/msg rui hello`, `/nick newname` (only before you are
registered, e.g. after a 409), `/quit`, `/help`. Anything else goes to everyone.

## Showcase

- Second client tries `--nick ANA` while `ana` is online → `!!! error 409: nick taken`,
  then `/nick ana2` works on the same connection.
- `/msg rui segredo`: only rui sees `[ana -> you] segredo`; `/msg nobody x` → 404.
- With `nc 127.0.0.1 12345` type `hello` (not JSON) → `ERROR 400`, and the
  connection is still usable: type `{"type":"NICK","nick":"nc"}` → `OK`.
- Walk through `PROTOCOL.md` section 5: this is what you agree on with other groups.

## Design choices (and why)

- **Client map keyed by lower-case nick.** Uniqueness becomes a single
  `containsKey` + `put` under the lock, so two people asking for `ana` at the same
  instant cannot both win (check-then-act must be atomic). The same map finds the
  PRIV recipient in O(1). Lower-case key: `Ana` and `ana` are the same person,
  otherwise impersonating someone is trivial. `LinkedHashMap` keeps join order for LIST.
- **`Locale.ROOT` for lower-casing.** With a Turkish locale `"I".toLowerCase()` is
  not `"i"`; ROOT gives the same result on every machine.
- **Nickname rule: 1–20 letters/digits of any alphabet, `_`, `-`.** Any alphabet,
  because `joão` and `zé` must work in Portugal. No spaces, because
  `/msg <nick> <text>` uses the first space as separator.
- **Every request gets exactly one reply** (`OK`, `USERS` or `ERROR`), in order;
  `CHAT`/`EVENT` are notifications that can arrive any time. With this single rule a
  client knows what an `ERROR` refers to without request ids, and tests can be
  written as simple request → reply checks.
- **HTTP-style codes** (400, 401, 404, 409): everybody already knows what they mean.
  Each ERROR also carries a human `reason`.
- **Errors do not close the connection.** Newline framing makes resynchronising
  free: the next line is a fresh message. Disconnecting on a typo would be harsh
  and makes debugging other groups' clients painful.
- **401 before registration, except NICK and QUIT.** A client must be allowed to
  give up (QUIT) without a name.
- **PRIV: recipient gets `CHAT private:true`, sender gets only `OK`.** The client
  prints its own private message locally. Sending a copy back would need an extra
  "to" field to make sense.
- **QUIT → OK, then close.** The client knows its goodbye was understood; anything
  sent after QUIT is ignored.
- **The client never waits for a specific reply**; it prints whatever arrives and
  shows unknown messages raw. Other groups' servers may differ (no OK after MSG,
  no echo to the sender), and our client keeps working with all of them.
- **A written spec with MUST/SHOULD.** The slide asks for it, and when seven groups
  interoperate, the spec — not anybody's code — is the reference.

## Known limitations (fixed in M4)

No idle timeout, no size limit (a client that never sends `\n` makes our buffer grow
without end), a stuck client can still freeze a broadcaster, no automated tests,
Ctrl+C just kills the server, logging is `println`.
