# Multi-user chat in Java — Internet do Futuro 2026-27

A multi-user chat built in **7 milestones (M0–M6)**, in Java, using **only the JDK
standard library**, developed and run on **macOS**, and designed to interoperate
with the other groups' implementations in any language and on any OS.

Every milestone is a **complete, runnable folder**. Nothing is shared between
folders, so you can show M2, then M3, and see exactly what changed and why.

| Folder | Milestone | What it adds | Try it |
|---|---|---|---|
| `m0-echo/` | M0 Echo warm-up | TCP echo server + client, Wireshark | `./server.sh`, `./client.sh` |
| `m1-framing/` | M1 Framing and a protocol | newline-delimited JSON, recv buffer, NICK/MSG | `./demo.sh` (partial/merged messages) |
| `m2-many-clients/` | M2 Many clients | thread per client, shared list + lock, broadcast, join/leave | several `./client.sh` |
| `m3-commands/` | M3 Commands | `/list`, `/msg`, unique nicks, ERROR codes, **PROTOCOL.md** | `/msg`, duplicate nick |
| `m4-robustness/` | M4 Robustness and tests | idle timeout, send queues, Ctrl+C shutdown, logging, tests | `./test.sh`, pytest |
| `m5-discovery/` | M5 UDP discovery | DISCOVER broadcast, no address to type | `./client.sh --scan` |
| `m6-rooms/` | M6 Extension **C: chat rooms** | `/join`, `/leave` | `/join games` |

Each folder has a **README.md** (what changed, how to demo it, every design
choice and why) and, from M1 on, a **CHANGES.diff** against the previous milestone.
Cross-cutting decisions are in **[DESIGN.md](DESIGN.md)**. The protocol to hand to
the other groups is **[m6-rooms/PROTOCOL.md](m6-rooms/PROTOCOL.md)**.

## What you need on the Mac

- **A JDK, version 17 or newer.** Easiest: the Temurin `.pkg` installer from
  adoptium.net (or `brew install --cask temurin`). Check with `javac -version`.
- **Optional:** Python 3 + pytest for the pytest suite (M4+), Wireshark for M0/M1.

No Maven, no Gradle, no libraries: each folder compiles with one `javac` command.

## Quick start

```bash
cd m6-rooms            # or any milestone folder
./server.sh            # terminal 1   (compiles automatically the first time)
./client.sh --nick ana # terminal 2   (M5+: finds the server by itself)
./client.sh --nick rui # terminal 3
./test.sh              # M4+: automated tests
```

If the scripts are not executable after unzipping: `chmod +x */*.sh`
(or run them as `bash server.sh`). Every option has a default; `--port`,
`--host`, `--nick`, `--udp-port`, `--idle`, ... change them.

Using IntelliJ / VS Code instead: open one milestone folder, mark `src` as the
source root, and run `ChatServer` / `ChatClient` (add VM option
`-Djava.net.preferIPv4Stack=true` for M5 and M6).

## How to present the progression

1. Open the milestone's `README.md`: goal, changed files, design choices.
2. Run its demo (table above; each README has a "Showcase" section).
3. Show what changed: open `CHANGES.diff`, or compare two folders live,
   e.g. `diff -ru m2-many-clients/src m3-commands/src`, or in VS Code select both
   files › "Compare Selected".

How the project grows (lines include comments, which are a large part on purpose):

| | M0 | M1 | M2 | M3 | M4 | M5 | M6 |
|---|---|---|---|---|---|---|---|
| Java files in `src/` | 2 | 7 | 8 | 8 | 9 | 11 | 11 |
| Lines of Java | 128 | 723 | 841 | 985 | 1 703 | 2 035 | 2 203 |
| Java tests (`./test.sh`) | – | – | – | – | 20 | 23 | 27 |
| pytest tests | – | – | – | – | 13 | 16 | 19 |

## Interop day checklist (with the other groups)

1. Agree on the **TCP port** (we default to 12345) and the **UDP discovery port** (12346).
2. Hand out `m6-rooms/PROTOCOL.md`; agree on its section 5 semantics. The points
   where groups typically differ:
   - is there an `OK` after `MSG`?
   - does the sender get its own `CHAT` back?
   - the exact `DISCOVER` request and reply format.

   Our client tolerates either answer for all of them.
3. Same Wi-Fi/subnet; eduroam-style networks often block broadcast and
   client-to-client traffic: a phone hotspot or a switch avoids that.
4. Allow `java` in the macOS firewall when asked.
5. Test in both directions:
   - our client → their server: `./client.sh --host <ip>`;
   - their client → our server;
   - our test suite → their server:
     `CHAT_HOST=<ip> CHAT_PORT=<port> python3 -m pytest -v tests/` (from `m4`+);
   - their server's recv buffer: `./demo.sh --host <ip>` sends deliberately
     fragmented messages.
6. Watch it in Wireshark (`tcp.port == 12345 || udp.port == 12346`).

## Troubleshooting on macOS

| Symptom | Cause / fix |
|---|---|
| `Address already in use` | another server still running (`lsof -i :12345`); restart works thanks to SO_REUSEADDR |
| port 5000 or 7000 busy | AirPlay Receiver — that's why we use 12345 |
| others can't connect | macOS firewall blocked `java`; or you gave them `127.0.0.1` instead of your IP (`ipconfig getifaddr en0`) |
| `--scan` finds nothing | different subnet, Wi-Fi blocks broadcast, firewall; use `--host` |
| accents show as `?` | run through the scripts (they force UTF-8), or set the IDE console to UTF-8 |
| Wireshark sees nothing on localhost | capture on `lo0`, not `en0` |
