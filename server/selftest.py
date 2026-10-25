#!/usr/bin/env python3
"""Read-only checks against a running gambit server (never moves, challenges or messages anyone)."""
import json, sys, time, urllib.request
base, key = sys.argv[1].rstrip("/"), sys.argv[2]
ok = fail = 0
def get(path, auth=True):
    h = {"Authorization": f"Bearer {key}"} if auth else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, headers=h), timeout=60) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:  # a proxy in front (e.g. Funnel) answered with plain text
            return e.code, {"raw": raw[:80].decode(errors="replace")}
def check(name, cond, detail=""):
    global ok, fail
    ok, fail = (ok + 1, fail) if cond else (ok, fail + 1)
    print(("PASS " if cond else "FAIL ") + name, detail if not cond else "")
c, b = get("/health", auth=False); check("health", c == 200)
c, b = get("/home", auth=False); check("home needs key", c == 401)
c, b = get("/home"); check("home", c == 200 and "games" in b and "me" in b, b); print("   ", b.get("me"), "theme", b.get("theme"), len(b.get("games", [])), "games")
c, b = get("/events?after=-1&wait=1"); check("events sync", c == 200 and "latest" in b, b)
t = time.time(); c, b2 = get(f"/events?after={b.get('latest', 0)}&wait=2"); check("events long-poll waits", c == 200 and time.time() - t >= 1.8, b2)
c, b = get("/summary"); check("summary", c == 200 and "myTurn" in b and "corr" in b, b); print("   ", b)
c, b = get("/piecesets"); check("piece sets", c == 200 and "kiwen-suwi" in b.get("sets", []), b)
try:
    with urllib.request.urlopen(urllib.request.Request(base + "/pieces/kiwen-suwi/wN?size=64", headers={"Authorization": f"Bearer {key}"}), timeout=60) as r:
        check("piece png", r.status == 200 and r.read(8) == b"\x89PNG\r\n\x1a\n")
except Exception as e:
    check("piece png", False, e)
c, b = get("/pieces/../wN"); check("bad piece set rejected", c in (400, 404), (c, b))
c, b = get("/friends"); check("friends", c == 200 and "friends" in b, b)
c, b = get("/puzzle/daily"); check("daily puzzle", c == 200 and b.get("steps") and b["steps"][0]["expect"][2:] in b["steps"][0]["legal"].get(b["steps"][0]["expect"][:2], []), b)
c, b = get("/finished"); check("finished games", c == 200 and "games" in b, b)
if b.get("games"):
    gid = b["games"][0]["id"]
    c, a = get(f"/analysis/{gid}"); check("analysis status", c == 200 and "state" in a, a)
c, b = get("/player/lichess"); check("player", c == 200 and b.get("name"), b)
c, b = get("/game/zzzzzzzz"); check("unknown game is an error, not a crash", c in (400, 404, 502), (c, b))
c, b = get("/game/../../etc"); check("bad id rejected", c in (400, 404), (c, b))
print(f"\n{ok} passed, {fail} failed"); sys.exit(1 if fail else 0)
