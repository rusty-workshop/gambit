# Gambit

Lichess correspondence chess on a Wear OS watch (built for a Galaxy Watch 8).
Your games, a notification when your opponent moves, and tap a piece → tap a
square → confirm to move. Works standalone over Wi-Fi, no phone needed.

Two parts:

- **`app/`**: the Wear OS app (Kotlin, Compose for Wear OS).
- **`server/gambit.py`**: a small server on the Latitude that holds the Lichess
  token, watches your games, and runs Stockfish. The watch only ever talks to
  this server, using its own key, and never sees the Lichess token.

## Features

- Correspondence games list (your move first, time left), board in your Lichess theme
- Any Lichess piece set (picker on the watch, defaults to your Lichess preference). cburnett is built in; other sets are rendered to PNGs on the server with cairosvg (`pip install cairosvg`) and cached on the watch
- Notifications: opponent moved, new game started, game over, challenge received/declined
- Moving: taps snap to the nearest movable piece or legal square; the bezel zooms, drag pans; every move is confirmed
- Promotion picker, offer/accept/decline draw, resign (confirmed), move list
- In-game chat: quick replies or voice
- Challenges: accept/decline/cancel, challenge a friend, or post an open seek (1–14 days per move)
- Friends you follow with online status; profile with follow/unfollow, DM (send-only: the API can't read the inbox), private notes
- Daily puzzle, with the result recorded on your Lichess puzzle rating
- Five complications: your-move count, most urgent game (opponent + live countdown), clock ring, daily puzzle (✓ once solved), correspondence rating. They refresh as soon as something happens, plus a slow periodic refresh
- **Stockfish review of finished games** on the Latitude: accuracy, inaccuracies/mistakes/blunders, and each mistake on a board with the played (red) and best (green) moves. The server refuses to analyse games still in progress.

## Server (Latitude)

```bash
# one-time
ssh latitude 'mkdir -p ~/lichess-watch && python3 -m venv ~/lichess-watch/.venv && ~/lichess-watch/.venv/bin/pip install chess'
# Stockfish: official build from github.com/official-stockfish/Stockfish/releases (x86-64 universal) at ~/lichess-watch/stockfish
# server/gambit.env (gitignored, mode 600): LICHESS_TOKEN=..., WATCH_KEY=<random 32+ chars>, PORT=8460
scp server/gambit.py server/gambit.env latitude:~/lichess-watch/
scp server/systemd/gambit.service latitude:~/.config/systemd/user/
ssh latitude 'systemctl --user daemon-reload && systemctl --user enable --now gambit'
ssh latitude 'sudo ufw allow from 192.168.1.0/24 to any port 8460 proto tcp'
```

Check it with the read-only self-test (it never moves, challenges or messages anyone):

```bash
ssh latitude "~/lichess-watch/.venv/bin/python ~/lichess-watch/selftest.py http://127.0.0.1:8460 <WATCH_KEY>"
```

Optional, for use away from home Wi-Fi (the watch isn't on the tailnet):

```bash
sudo tailscale funnel --bg --https=8443 --set-path /gambit http://127.0.0.1:8460
```

then set `server.funnel=https://latitude.<tailnet>.ts.net:8443/gambit` in `gambit.properties` and rebuild.

## Watch app

```bash
cp gambit.properties.example gambit.properties   # fill in server.key = WATCH_KEY
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.rusty.gambit
```

Always install the release build: the debug build is far too slow on a watch.

## Licence

AGPL-3.0-or-later. The piece artwork is Lichess's *cburnett* set by Colin M.L.
Burnett (GPLv2+), converted to Kotlin paths by `tools/gen_pieces.py`.
