# M2 — Many clients

**Goal:** one thread per client; a shared client list behind a lock; broadcast
to all; join and leave notices.

## What changed since M1  (full diff: `CHANGES.diff`)

| File | Status | Why |
|---|---|---|
| `src/ClientHandler.java` | **new** | one connected client; `run()` is that client's thread |
| `src/ChatServer.java` | rewritten | accept loop starts a thread per client; shared list + lock; `register`, `unregister`, `broadcast` |
| `src/Protocol.java` | + `event()` | `{"type":"EVENT","text":"rui joined"}` |
| `src/ChatClient.java` | + EVENT display | prints `*** rui joined` |

`LineFramer`, `Json`, `Cli`, `FramingDemo` are unchanged.

## Run it

```bash
./server.sh
./client.sh --nick ana        # as many terminals / laptops as you like
./client.sh --nick rui
./client.sh --host 192.168.1.7 --nick eva   # from another machine
```

## Showcase

- Three clients chatting at once; each sees `*** x joined` / `*** x left`.
- Kill a client with Ctrl+C (no goodbye): the others still get `*** x left`,
  because cleanup is in a `finally` block.
- The server log shows the thread name in brackets (`[client-/127.0.0.1:53211]`),
  so you can see that every client really runs in its own thread.

## Design choices (and why)

- **One thread per client, not `selectors`.** The slide allows both. A thread per
  client keeps the code a straight line ("read, handle, repeat") that reads like
  the protocol. With `java.nio` selectors you must keep every client's state
  yourself and handle partial *writes* by hand. For a class-sized chat (tens of
  clients) threads cost nothing noticeable.
- **The main thread only `accept()`s.** It never touches client data, so a slow
  client can never stop new clients from connecting (unlike M0/M1).
- **One `LineFramer` per client.** Each connection has its own byte stream; a shared
  buffer would mix the bytes of different clients.
- **Shared list + `synchronized (clients)`.** Several threads add, remove and
  iterate the list at the same time; without the lock, iterating while someone
  joins throws `ConcurrentModificationException` or misses clients. We use an
  explicit lock (not a `ConcurrentHashMap`) because the slide asks for it and
  because M3 needs check-then-insert to be atomic anyway.
- **Broadcast to a copy of the list, outside the lock** (the slide's advice).
  Copy = stable list while sending; outside the lock = sending (which can be slow)
  does not freeze every other thread that needs the list.
- **`send()` is `synchronized` per client.** Two clients can broadcast at the same
  moment, and both threads then write to the same third socket. Without the lock
  their bytes can interleave and break the framing of both messages.
- **OK is sent while still holding the list lock in `register()`.** From the moment
  a client is in the list, other threads may broadcast to it. Doing insert + OK under
  the same lock guarantees the reply to NICK is the first thing it receives.
- **Only registered clients are in the list.** A connection that has not sent NICK
  has no name, so it gets no broadcasts.
- **Notices go to everyone, the new client included** ("everyone means everyone"),
  which also confirms the join to the newcomer.
- **Cleanup in `finally`.** Whether the client sent nothing, closed nicely, or
  crashed with "Connection reset", `unregister()` runs, so no ghost clients stay in
  the list (and no broadcasts are sent to dead sockets forever).

## Known limitations (fixed later, on purpose)

- Two clients may use the same nick; no `/list`, no private messages, no error
  replies → **M3**.
- `broadcast()` writes directly into each socket. If one client stops reading, its
  TCP buffer fills up, `write()` blocks, and the thread that was broadcasting (some
  *other* client) freezes → **M4** (per-client send queue).
