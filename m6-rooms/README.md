# M6 — One extension: **C. Chat rooms** (`/join`, `/leave`)

**Goal:** users can move between rooms; public messages stay inside a room.

## Why rooms (and not A, B or D)

| Option | What it would take | Verdict |
|---|---|---|
| A. File transfer, length-prefixed binary frames | a second framing mode on the same socket (switching between text lines and raw bytes), binary-safe file handling, partial-file errors; any group that misreads one length byte loses the whole stream | hardest to get interoperable |
| B. TLS with a self-signed certificate | little Java code, but certificates (`keytool` keystore here, PEM files for Python groups), trust settings in every client, and **all** groups must switch at once: a TLS server cannot talk to a plain-TCP client. Wireshark only shows encrypted bytes, so debugging gets harder | simple code, hard logistics |
| D. Supervisory node | the slides do not define it: a second protocol (server ↔ supervisor) to design from scratch | most open-ended |
| **C. Chat rooms** | pure application logic on top of the framing, threads and locks we already have; 2 new messages; **old clients keep working unchanged** | **easiest to build and to explain** |

## What changed since M5  (full diff: `CHANGES.diff`)

| File | Status | Why |
|---|---|---|
| `src/ClientHandler.java` | small | a `room` field ("lobby" at first); JOIN and LEAVE; MSG and LIST use the room |
| `src/ChatServer.java` | + 3 methods | `changeRoom()`, `broadcastToRoom()`, `nicksInRoom()` |
| `src/Protocol.java` | extended | `JOIN`, `LEAVE`, `roomChat()`, `users(nicks, room)`, `normalizeRoom()` |
| `src/ChatClient.java` | small | `/join <room>`, `/leave`; shows `#games [ana] hi` |
| `src/ChatTests.java` | + 4 tests | isolation, notices, LIST per room + PRIV across rooms, invalid names |
| `tests/test_protocol.py` | + 3 tests | rooms over the wire (against others only with `CHAT_ROOMS=1`) |
| `PROTOCOL.md` | + section 11 | rooms spec, including backward compatibility |

## Run it

```bash
./server.sh
./client.sh --nick ana     # then: /join games   hello   /list   /leave
./client.sh --nick rui     # stays in the lobby: does not see ana's #games messages
./test.sh                  # 27 passed
```

## Showcase

Three terminals: `ana` types `/join games` and `olá`; `eva` types `/join #Games`;
`rui` stays in the lobby and later types a message and `/list`. What each one saw
in a recorded run (connection lines removed):

```
--- ana ---                         --- rui (lobby) ---               --- eva ---
*** ana joined #games               *** ana left #lobby               *** eva joined #games
#games [ana] olá                    *** eva joined
*** eva joined                      *** eva left #lobby
*** eva joined #games               [rui] oi da sala de jogos
*** online in #games: ana, eva      *** online in #lobby: rui
```

rui never sees `olá`; ana and eva never see rui's lobby message.

Then connect an **M3 or M4 client** (or another group's client): it works
unchanged and simply lives in the lobby. `/msg` still reaches anyone, in any room.
The other direction degrades gracefully too: this M6 client on an M3 server chats
normally, and `/join` just gets `!!! error 400: unknown type 'JOIN'` (both tested).

## Design choices (and why)

- **One room per user at a time.** Then `MSG` needs no room field — it goes to "my
  room" — so the existing `MSG` message keeps working as before. (IRC-style
  multiple memberships would force every message to name its room.)
- **No rooms table.** A room is just the value of each client's `room` field; a room
  exists while someone is in it. Nothing to create, delete, clean up or keep in sync
  with the client map. `broadcastToRoom()` filters the same snapshot the old
  `broadcast()` copied, under the same lock.
- **Default room `lobby`; `LEAVE` means "back to the lobby".** Everybody always has
  a room, so there is no "in no room" special case anywhere in the code.
- **Backward compatible by construction.** New behaviour only appears after a
  `JOIN`, and new information travels in *extra* fields (`room` in CHAT and USERS)
  that old clients ignore — the "ignore unknown fields" rule from M1/M3 pays off.
- **Room names = nickname rules**, `#` optional, case-insensitive, stored in lower
  case: one validation function, and `#Games`, `games` and `GAMES` are one room.
- **Room change and its OK under the lock** (same reasoning as NICK in M2): the
  client gets OK before any message of the new room.
- **JOIN to your current room / LEAVE in the lobby → OK, no change.** Idempotent
  operations are friendlier than errors.
- **Moving between rooms is announced in both rooms**
  (`ana left #lobby`, `ana joined #games`), while **connect/disconnect notices stay
  server-wide** as in M3: everyone still knows who is online, which matters because
  **PRIV keeps working across rooms**.
- **LIST shows your room** (with `"room"` in the reply): inside a room that is the
  useful answer.
