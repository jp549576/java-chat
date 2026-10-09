# M5 — UDP discovery

**Goal:** the server answers a UDP broadcast (`DISCOVER`) with its TCP port; the
client finds servers on the LAN with no address typed in.

## What changed since M4  (full diff: `CHANGES.diff`)

| File | Status | Why |
|---|---|---|
| `src/DiscoveryResponder.java` | **new** | server side: UDP socket on port 12346, answers DISCOVER |
| `src/ServerFinder.java` | **new** | client side: broadcasts DISCOVER, collects and parses replies |
| `src/ChatServer.java` | small | `Config` gets `udpPort` and `name`; `start()`/`stop()` start/stop the responder |
| `src/ChatClient.java` | small | no `--host` → discover and pick a server; `--scan` only lists |
| `server.sh`, `client.sh`, `test.sh` | changed | `-Djava.net.preferIPv4Stack=true` |
| `src/ChatTests.java` | + 3 tests | reply to both request formats, finder finds the server, parsing of foreign replies |
| `tests/test_protocol.py` | + 3 tests | discovery over real UDP (also against other groups: `CHAT_UDP_PORT`) |
| `PROTOCOL.md` | + section 10 | discovery spec |

The TCP chat protocol is unchanged.

## Run it

```bash
./server.sh --name ana-mac          # TCP 12345 + UDP 12346
./client.sh --scan                  # just list servers on the LAN
./client.sh --nick rui              # no --host: finds the server, asks if there are several
./client.sh --host 192.168.1.20     # still works when broadcast is blocked
./test.sh                           # 23 passed
```

## Showcase

- On interop day: `./client.sh --scan` lists every group's server that implements
  discovery (and understands either request format).
- Wireshark on `en0`, filter `udp.port == 12346`: the request goes to
  `255.255.255.255` and to e.g. `192.168.1.255`; each reply is a **unicast** packet
  whose *source address* is the server.
- `nc -u 127.0.0.1 12346`, type `DISCOVER` → `{"type":"SERVER","port":12345,...}`.

## Design choices (and why)

- **Separate UDP port (12346).** TCP and UDP ports are independent, so 12345/udp
  would also work, but a different number avoids "which 12345?" confusion in
  firewalls and Wireshark filters.
- **Accept two request dialects: `DISCOVER` and `{"type":"DISCOVER"}`.** The slide
  writes the word DISCOVER but never fixes a format, so different groups will choose
  differently. Accepting both costs four lines. Our client sends both, so it also
  finds servers that only understand one of them.
- **Reply in JSON** `{"type":"SERVER","port":12345,"name":"..."}`, consistent with
  the TCP protocol. The client still accepts other shapes (port as string, or plain
  text such as `PORT 12345`).
- **No IP address in the reply: the client uses the reply packet's source address.**
  A server often does not know which of its addresses (Wi-Fi, Ethernet, VPN,
  Docker...) the client can reach. The packet's source is, by definition, one that
  just reached it.
- **Reply by unicast to the sender**, not by broadcast: only the asker needs it.
- **Never answer anything except DISCOVER.** Otherwise two servers could answer each
  other's answers forever.
- **Send to three kinds of destination:** `255.255.255.255`, each interface's
  directed broadcast (macOS sends the limited broadcast out of one interface only),
  and `127.0.0.1` (finds a server on the same Mac even with no network).
  Duplicates are merged; a local server seen via both 127.0.0.1 and the LAN address
  is shown once, with the LAN address, because that is the one others can use too.
- **Collect for 1.5 s, then decide.** UDP is unreliable and replies arrive in any
  order; a fixed window is the simple, standard approach.
- **IPv4 only (`preferIPv4Stack`).** Broadcast does not exist in IPv6, and Java's
  default dual-stack sockets on macOS can refuse to send to `255.255.255.255`.
- **Server name from `--name`, default `java-chat-<user>`**, not
  `InetAddress.getLocalHost()`: on macOS that call can take 5 s when the Mac's own
  name is not resolvable.
- **Daemon thread** for the responder, closed by `stop()` (closing the socket
  unblocks `receive()`, same trick as for `accept()`).
- **No framing on UDP.** UDP keeps message boundaries: one `send()` is one
  `receive()`. A nice contrast with everything we had to do for TCP in M1.

## When discovery finds nothing

- All machines must be on the **same subnet**; broadcast never crosses routers.
- University / hotel Wi-Fi (e.g. eduroam) often blocks broadcast or isolates
  clients. A phone hotspot or a lab switch works.
- macOS firewall (System Settings › Network › Firewall): allow incoming connections
  for `java` when macOS asks.
- On macOS 15 or later, if you start the programs from an IDE (IntelliJ, VS Code),
  macOS may ask for **Local Network** permission for that IDE; allow it in
  System Settings › Privacy & Security › Local Network.
- `--host <ip>` always works as a fallback.
