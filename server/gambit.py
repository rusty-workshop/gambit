#!/usr/bin/env python3
"""
gambit: the Latitude side of the Gambit Wear OS app.

Holds the Lichess token (the watch never sees it), watches your correspondence
games, and gives the watch a small JSON API plus a long-poll event feed for
notifications. Stockfish reviews *finished* games only.

  * Bearer token (WATCH_KEY, >=32 chars), compared in constant time
  * Lichess event stream for challenges / game start / game finish
  * /api/account/playing polled every POLL_SECONDS to spot opponent moves
  * python-chess for legal moves, SAN, and driving Stockfish
"""
import hmac
import json
import math
import os
import re
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import chess
import chess.engine

TOKEN = os.environ["LICHESS_TOKEN"]
WATCH_KEY = os.environ["WATCH_KEY"]
PORT = int(os.environ.get("PORT", "8460"))
BIND = os.environ.get("BIND", "0.0.0.0")
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "20"))
STOCKFISH = os.environ.get("STOCKFISH", str(Path(__file__).with_name("stockfish")))
DATA = Path(os.environ.get("DATA_DIR", Path(__file__).with_name("data")))
LICHESS = "https://lichess.org"
UA = "gambit-wearos (personal watch client; lichess user fllocalhostedcat)"

assert len(WATCH_KEY) >= 32, "WATCH_KEY must be at least 32 characters"
DATA.mkdir(parents=True, exist_ok=True)
(DATA / "analysis").mkdir(exist_ok=True)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------ lichess

class LichessError(Exception):
    def __init__(self, code: int, body: str):
        super().__init__(f"lichess HTTP {code}: {body[:200]}")
        self.code, self.body = code, body


def li(method: str, path: str, form: dict | None = None, js: dict | None = None,
       accept: str = "application/json", timeout: float = 15, auth: bool = True):
    """One Lichess request. Returns parsed JSON (or a list for ndjson, or text)."""
    data, headers = None, {"User-Agent": UA, "Accept": accept}
    if auth:
        headers["Authorization"] = f"Bearer {TOKEN}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif js is not None:
        data = json.dumps(js).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(LICHESS + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode()
    except urllib.error.HTTPError as e:
        raise LichessError(e.code, e.read().decode(errors="replace")) from None
    if accept == "application/x-ndjson":
        return [json.loads(l) for l in body.splitlines() if l.strip()]
    try:
        return json.loads(body) if body.strip() else {}
    except json.JSONDecodeError:
        return body


def li_first_line(path: str, timeout: float = 15) -> dict:
    """Read just the first ndjson line of a stream (e.g. gameFull) and hang up."""
    req = urllib.request.Request(LICHESS + path, headers={
        "User-Agent": UA, "Authorization": f"Bearer {TOKEN}", "Accept": "application/x-ndjson"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            for raw in r:
                if raw.strip():
                    return json.loads(raw)
    except urllib.error.HTTPError as e:
        raise LichessError(e.code, e.read().decode(errors="replace")) from None
    raise LichessError(502, "empty stream")


ME = li("GET", "/api/account")
MY_ID = ME["id"]
log("signed in as", ME["username"])


# ------------------------------------------------------------------ event feed (watch long-poll)

_events: list[dict] = []
_seq = 0
_cv = threading.Condition()


def push(kind: str, **fields):
    global _seq
    with _cv:
        _seq += 1
        _events.append({"seq": _seq, "kind": kind, "at": int(time.time()), **fields})
        del _events[:-200]
        _cv.notify_all()
    log("event", kind, fields)


def events_since(after: int, wait: float) -> dict:
    deadline = time.time() + wait
    with _cv:
        # after=-1 (or a seq from before a restart) just syncs the watch to "now".
        while after >= 0 and after <= _seq and not any(e["seq"] > after for e in _events):
            left = deadline - time.time()
            if left <= 0:
                break
            _cv.wait(left)
        reset = after > _seq
        items = [] if after < 0 or reset else [e for e in _events if e["seq"] > after]
        return {"latest": _seq, "items": items, "reset": reset}


# ------------------------------------------------------------------ game tracking

_games: dict[str, dict] = {}     # gameId -> nowPlaying entry (correspondence only)
_glock = threading.Lock()
_refresh = threading.Event()


def _is_corr(g: dict) -> bool:
    return g.get("speed") == "correspondence"


def _san_of(prev_fen: str | None, uci: str | None) -> str | None:
    if not prev_fen or not uci:
        return None
    try:
        b = chess.Board(prev_fen)
        m = chess.Move.from_uci(uci)
        return b.san(m) if m in b.legal_moves else None
    except ValueError:
        return None


def poll_playing():
    data = li("GET", "/api/account/playing?nb=50")
    now = {g["gameId"]: g for g in data.get("nowPlaying", []) if _is_corr(g)}
    with _glock:
        old = dict(_games)
        _games.clear()
        _games.update(now)
    for gid, g in now.items():
        o = old.get(gid)
        if o is None:
            continue  # new games are announced by the event stream
        if g.get("lastMove") != o.get("lastMove") and g.get("isMyTurn"):
            san = _san_of(o.get("fen"), g.get("lastMove")) or g.get("lastMove")
            push("opponent_moved", game=gid, opponent=g["opponent"].get("username", "?"), san=san)


def poll_loop():
    while True:
        try:
            poll_playing()
        except Exception as e:
            log("poll failed:", e)
        _refresh.wait(POLL_SECONDS)
        _refresh.clear()


def _result_for_me(gid: str) -> tuple[str, str]:
    try:
        g = li("GET", f"/game/export/{gid}?moves=false")
    except Exception:
        return "over", "?"
    color = "white" if g["players"]["white"].get("user", {}).get("id") == MY_ID else "black"
    opp = g["players"]["black" if color == "white" else "white"].get("user", {}).get("name", "?")
    w = g.get("winner")
    return ("draw" if not w else "win" if w == color else "loss"), opp


def stream_loop():
    backoff = 5
    while True:
        try:
            req = urllib.request.Request(LICHESS + "/api/stream/event", headers={
                "User-Agent": UA, "Authorization": f"Bearer {TOKEN}"})
            with urllib.request.urlopen(req, timeout=90) as r:  # Lichess pings every ~7s
                backoff = 5
                log("event stream connected")
                for raw in r:
                    if not raw.strip():
                        continue
                    ev = json.loads(raw)
                    handle_stream_event(ev)
        except Exception as e:
            log("event stream dropped:", e)
        time.sleep(backoff)
        backoff = min(backoff * 2, 300)


def handle_stream_event(ev: dict):
    t = ev.get("type")
    if t == "challenge":
        c = ev["challenge"]
        if (c.get("destUser") or {}).get("id") == MY_ID and c.get("speed") == "correspondence":
            push("challenge", id=c["id"], user=(c.get("challenger") or {}).get("name", "?"),
                 days=(c.get("timeControl") or {}).get("daysPerTurn"))
    elif t == "challengeDeclined":
        c = ev["challenge"]
        if (c.get("challenger") or {}).get("id") == MY_ID:
            push("challenge_declined", user=(c.get("destUser") or {}).get("name", "?"))
    elif t == "gameStart":
        g = ev["game"]
        if _is_corr(g):
            push("game_started", game=g["gameId"], opponent=g.get("opponent", {}).get("username", "?"),
                 myTurn=bool(g.get("isMyTurn")))
        _refresh.set()
    elif t == "gameFinish":
        g = ev["game"]
        if _is_corr(g):
            result, opp = _result_for_me(g["gameId"])
            push("game_over", game=g["gameId"], opponent=opp, result=result)
        _refresh.set()


# ------------------------------------------------------------------ chess helpers

def legal_map(board: chess.Board) -> dict[str, list[str]]:
    """{from_square: [to_square or to+promo, ...]} for the side to move."""
    out: dict[str, list[str]] = {}
    for m in board.legal_moves:
        out.setdefault(chess.square_name(m.from_square), []).append(m.uci()[2:])
    return out


def check_square(board: chess.Board) -> str | None:
    if board.is_check():
        k = board.king(board.turn)
        return chess.square_name(k) if k is not None else None
    return None


def game_detail(gid: str) -> dict:
    full = li_first_line(f"/api/board/game/stream/{gid}")
    if full.get("type") != "gameFull":
        raise LichessError(502, "unexpected stream start")
    init = full.get("initialFen", "startpos")
    board = chess.Board() if init in ("startpos", "", None) else chess.Board(init)
    sans = []
    for u in full["state"]["moves"].split():
        m = chess.Move.from_uci(u)
        sans.append(board.san(m))
        board.push(m)
    white, black = full["white"], full["black"]
    color = "white" if white.get("id") == MY_ID else "black"
    opp = black if color == "white" else white
    st = full["state"]
    my_turn = (board.turn == chess.WHITE) == (color == "white") and st["status"] == "started"
    with _glock:
        np = _games.get(gid, {})
    return {
        "id": gid,
        "color": color,
        "fen": board.fen(),
        "myTurn": my_turn,
        "status": st["status"],
        "opponent": {"name": opp.get("name", "?"), "id": opp.get("id"), "rating": opp.get("rating")},
        "lastMove": board.peek().uci() if board.move_stack else None,
        "check": check_square(board),
        "legal": legal_map(board) if my_turn else {},
        "sans": sans,
        "secondsLeft": np.get("secondsLeft"),
        "daysPerTurn": full.get("daysPerTurn"),
        "drawOffered": bool(st.get("bdraw") if color == "white" else st.get("wdraw")),
        "iOfferedDraw": bool(st.get("wdraw") if color == "white" else st.get("bdraw")),
        "rated": full.get("rated"),
    }


def game_summary(g: dict) -> dict:
    return {
        "id": g["gameId"],
        "color": g["color"],
        "fen": g["fen"],
        "myTurn": g.get("isMyTurn", False),
        "lastMove": g.get("lastMove"),
        "opponent": {"name": g["opponent"].get("username", "?"), "id": g["opponent"].get("id"),
                     "rating": g["opponent"].get("rating")},
        "secondsLeft": g.get("secondsLeft"),
    }


# ------------------------------------------------------------------ Stockfish review (finished games only)

_jobs: dict[str, dict] = {}
_jlock = threading.Lock()
_engine_lock = threading.Lock()  # one analysis at a time; the Latitude has 4 cores and other work


def _win_pct(cp: int) -> float:
    return 50 + 50 * (2 / (1 + math.exp(-0.00368208 * cp)) - 1)


def _cp(score: chess.engine.PovScore) -> int:
    return max(-1000, min(1000, score.white().score(mate_score=10000)))


def analyze_game(gid: str):
    path = DATA / "analysis" / f"{gid}.json"
    try:
        g = li("GET", f"/game/export/{gid}?moves=true&clocks=false&evals=false")
        if g.get("status") in ("created", "started"):
            raise ValueError("game is still in progress")
        color = "white" if g["players"]["white"].get("user", {}).get("id") == MY_ID else "black"
        board = chess.Board(g["initialFen"]) if g.get("initialFen") else chess.Board()
        moves = []
        for san in g.get("moves", "").split():
            m = board.parse_san(san)
            moves.append(m)
            board.push(m)
        board = chess.Board(g["initialFen"]) if g.get("initialFen") else chess.Board()
        with _engine_lock, chess.engine.SimpleEngine.popen_uci(STOCKFISH) as eng:
            eng.configure({"Threads": 2, "Hash": 128})
            limit = chess.engine.Limit(time=0.25)
            evals = []  # cp (white POV) of each position, plus best move from it
            info = eng.analyse(board, limit)
            evals.append((_cp(info["score"]), info.get("pv", [None])[0]))
            for i, m in enumerate(moves):
                board.push(m)
                if board.is_game_over():
                    out = board.outcome()
                    cp = 0 if out.winner is None else (1000 if out.winner == chess.WHITE else -1000)
                    evals.append((cp, None))
                else:
                    info = eng.analyse(board, limit)
                    evals.append((_cp(info["score"]), info.get("pv", [None])[0]))
                with _jlock:
                    _jobs[gid]["progress"] = round((i + 1) / max(1, len(moves)), 2)
        board = chess.Board(g["initialFen"]) if g.get("initialFen") else chess.Board()
        moments, accs, cpl = [], [], []
        counts = {"inaccuracy": 0, "mistake": 0, "blunder": 0}
        for i, m in enumerate(moves):
            mover_white = board.turn == chess.WHITE
            mine = mover_white == (color == "white")
            before, best = evals[i]
            after = evals[i + 1][0]
            sign = 1 if mover_white else -1
            wb, wa = _win_pct(sign * before), _win_pct(sign * after)
            drop = wb - wa
            san = board.san(m)
            fen_before = board.fen()
            best_san = board.san(best) if best and best in board.legal_moves else None
            if mine:
                accs.append(max(0.0, min(100.0, 103.1668 * math.exp(-0.04354 * max(0.0, drop)) - 3.1669)))
                cpl.append(max(0, sign * (before - after)))
                kind = "blunder" if drop >= 30 else "mistake" if drop >= 20 else "inaccuracy" if drop >= 10 else None
                if kind and best and best != m:
                    counts[kind] += 1
                    moments.append({
                        "ply": i + 1, "moveNo": i // 2 + 1, "kind": kind, "san": san,
                        "best": best_san, "bestUci": best.uci(), "played": m.uci(),
                        "fen": fen_before, "evalBefore": sign * before, "evalAfter": sign * after,
                    })
            board.push(m)
        w = g.get("winner")
        result = {
            "id": gid, "state": "done", "color": color,
            "opponent": g["players"]["black" if color == "white" else "white"].get("user", {}).get("name", "?"),
            "result": "draw" if not w else "win" if w == color else "loss",
            "accuracy": round(sum(accs) / len(accs)) if accs else None,
            "acpl": round(sum(cpl) / len(cpl)) if cpl else None,
            **counts, "moments": moments,
        }
        path.write_text(json.dumps(result))
        with _jlock:
            _jobs[gid] = result
        log("analysed", gid, counts)
    except Exception as e:
        traceback.print_exc()
        with _jlock:
            _jobs[gid] = {"id": gid, "state": "error", "error": str(e)}


def analysis_status(gid: str) -> dict:
    with _jlock:
        if gid in _jobs:
            return _jobs[gid]
    p = DATA / "analysis" / f"{gid}.json"
    if p.exists():
        return json.loads(p.read_text())
    return {"id": gid, "state": "none"}


def start_analysis(gid: str) -> dict:
    cur = analysis_status(gid)
    if cur["state"] in ("running", "done"):
        return cur
    with _jlock:
        _jobs[gid] = {"id": gid, "state": "running", "progress": 0}
    threading.Thread(target=analyze_game, args=(gid,), daemon=True).start()
    return _jobs[gid]


# ------------------------------------------------------------------ puzzle

def daily_puzzle() -> dict:
    d = li("GET", "/api/puzzle/daily", auth=False)
    pz, game = d["puzzle"], d["game"]
    sol = pz["solution"]
    board = chess.Board()
    sans = game["pgn"].split()
    for san in sans:
        board.push_san(san)
    if chess.Move.from_uci(sol[0]) not in board.legal_moves:  # fall back to initialPly if the PGN runs long
        board = chess.Board()
        for san in sans[: pz["initialPly"] + 1]:
            board.push_san(san)
    start = board.fen()
    steps = []
    for i, u in enumerate(sol):
        if i % 2 == 0:
            mates = []
            for m in board.legal_moves:
                board.push(m)
                if board.is_checkmate():
                    mates.append(m.uci())
                board.pop()
            steps.append({"fen": board.fen(), "expect": u, "reply": sol[i + 1] if i + 1 < len(sol) else None,
                          "legal": legal_map(board), "mates": mates, "check": check_square(board)})
        board.push(chess.Move.from_uci(u))
    return {"id": pz["id"], "rating": pz["rating"], "themes": pz.get("themes", []),
            "color": "white" if chess.Board(start).turn == chess.WHITE else "black",
            "lastMove": None, "start": start, "steps": steps}


# ------------------------------------------------------------------ preferences & piece sets

_prefs = {"theme": "brown", "pieceSet": "cburnett", "at": 0}

PIECE_SETS = (
    "cburnett merida alpha pirouetti chessnut chess7 reillycraig companion riohacha kosal leipzig fantasy "
    "spatial celtic california caliente pixel firi rhosgfx maestro fresca cardinal gioco tatiana staunty cooke "
    "governor dubrovny shahi-ivory-brown icpieces mpchess kiwen-suwi horsey anarcandy xkcd letter disguised shapes"
).split()
PIECE_RE = re.compile(r"^[wb][KQRBNP]$")
_render_lock = threading.Lock()


def lichess_prefs() -> dict:
    if time.time() - _prefs["at"] > 3600:
        try:
            pr = li("GET", "/api/account/preferences")["prefs"]
            _prefs["theme"] = pr.get("theme", "brown")
            _prefs["pieceSet"] = pr.get("pieceSet", "cburnett")
        except Exception:
            pass
        _prefs["at"] = time.time()
    return _prefs


def board_theme() -> str:
    return lichess_prefs()["theme"]


def piece_png(piece_set: str, code: str, size: int) -> bytes:
    """Lichess's SVG for one piece, rendered to a PNG for the watch (Android has no SVG renderer). Cached on disk."""
    if piece_set not in PIECE_SETS or not PIECE_RE.match(code):
        raise ValueError("unknown piece set or piece")
    size = max(32, min(192, size))
    path = DATA / "pieces" / piece_set / f"{code}_{size}.png"
    if path.exists():
        return path.read_bytes()
    import cairosvg  # only needed for non-cached pieces
    with _render_lock:
        req = urllib.request.Request(f"https://lichess1.org/assets/piece/{piece_set}/{code}.svg", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=15) as r:
            svg = r.read()
        png = cairosvg.svg2png(bytestring=svg, output_width=size, output_height=size)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(png)
    return png


# ------------------------------------------------------------------ HTTP API for the watch

USER_RE = re.compile(r"^[A-Za-z0-9_-]{2,30}$")
ID_RE = re.compile(r"^[A-Za-z0-9]{8,12}$")


def home() -> dict:
    with _glock:
        games = [game_summary(g) for g in _games.values()]
    games.sort(key=lambda g: (not g["myTurn"], g["secondsLeft"] or 10**9))
    ch = li("GET", "/api/challenge")
    def c(x, who):
        return {"id": x["id"], "user": (x.get(who) or {}).get("name", "open seek"),
                "rating": (x.get(who) or {}).get("rating"), "days": (x.get("timeControl") or {}).get("daysPerTurn"),
                "rated": x.get("rated"), "color": x.get("color")}
    me = li("GET", "/api/account")
    perfs = me.get("perfs", {})
    return {
        "me": {"name": me["username"], "corr": perfs.get("correspondence", {}).get("rating"),
               "puzzle": perfs.get("puzzle", {}).get("rating")},
        "games": games,
        "challengesIn": [c(x, "challenger") for x in ch.get("in", []) if x.get("speed") == "correspondence"],
        "challengesOut": [c(x, "destUser") for x in ch.get("out", []) if x.get("speed") == "correspondence"],
        "theme": board_theme(),
        "pieceSet": lichess_prefs()["pieceSet"],
    }


_days_per_turn: dict[str, int | None] = {}
_daily = {"id": None, "at": 0}


def days_per_turn(gid: str) -> int | None:
    if gid not in _days_per_turn:
        try:
            _days_per_turn[gid] = li_first_line(f"/api/board/game/stream/{gid}").get("daysPerTurn")
        except Exception:
            return None
    return _days_per_turn[gid]


def puzzle_solved_today() -> bool:
    if time.time() - _daily["at"] > 1800:  # the daily puzzle changes at midnight UTC
        _daily["id"] = li("GET", "/api/puzzle/daily", auth=False)["puzzle"]["id"]
        _daily["at"] = time.time()
    for a in li("GET", "/api/puzzle/activity?max=30", accept="application/x-ndjson"):
        if (a.get("puzzle") or {}).get("id", a.get("id")) == _daily["id"]:
            return True
    return False


def summary() -> dict:
    """Everything the watch complications show, in one small request."""
    with _glock:
        mine = [g for g in _games.values() if g.get("isMyTurn")]
        theirs = [g for g in _games.values() if not g.get("isMyTurn")]
    urgent = min(mine, key=lambda g: g.get("secondsLeft") or 10**9, default=None)
    me = li("GET", "/api/account")
    out = {
        "myTurn": len(mine),
        "urgent": None,
        "corr": me.get("perfs", {}).get("correspondence", {}).get("rating"),
        "corrProv": bool(me.get("perfs", {}).get("correspondence", {}).get("prov")),
        "puzzleSolved": None,
        "waiting": None,  # a game where it's their move (shown when nothing is waiting on you)
    }
    if urgent:
        out["urgent"] = {"id": urgent["gameId"], "opponent": urgent["opponent"].get("username", "?"),
                         "secondsLeft": urgent.get("secondsLeft"), "daysPerTurn": days_per_turn(urgent["gameId"])}
    elif theirs:
        w = theirs[0]
        out["waiting"] = {"id": w["gameId"], "opponent": w["opponent"].get("username", "?"),
                          "daysPerTurn": days_per_turn(w["gameId"])}
    try:
        out["puzzleSolved"] = puzzle_solved_today()
    except Exception as e:
        log("puzzle activity failed:", e)
    return out


def friends() -> list[dict]:
    rel = li("GET", "/api/rel/following", accept="application/x-ndjson")
    ids = [u["id"] for u in rel][:100]
    status = {}
    if ids:
        for s in li("GET", "/api/users/status?ids=" + ",".join(ids)):
            status[s["id"]] = s
    out = [{"id": u["id"], "name": u.get("username", u["id"]),
            "online": bool(status.get(u["id"], {}).get("online")),
            "playing": bool(status.get(u["id"], {}).get("playing")),
            "corr": u.get("perfs", {}).get("correspondence", {}).get("rating")} for u in rel]
    out.sort(key=lambda u: (not u["online"], u["name"].lower()))
    return out


def player(user: str) -> dict:
    u = li("GET", f"/api/user/{user}")
    note = ""
    try:
        notes = li("GET", f"/api/user/{user}/note")
        if isinstance(notes, list) and notes:
            note = notes[0].get("text", "")
    except LichessError:
        pass
    return {"id": u["id"], "name": u["username"], "following": bool(u.get("following")),
            "online": bool(u.get("online")), "corr": u.get("perfs", {}).get("correspondence", {}).get("rating"),
            "note": note}


def finished() -> list[dict]:
    gs = li("GET", f"/api/games/user/{MY_ID}?max=15&finished=true&ongoing=false&moves=false",
            accept="application/x-ndjson", timeout=30)
    out = []
    for g in gs:
        color = "white" if g["players"]["white"].get("user", {}).get("id") == MY_ID else "black"
        opp = g["players"]["black" if color == "white" else "white"]
        w = g.get("winner")
        out.append({"id": g["id"], "speed": g.get("speed"), "at": g.get("lastMoveAt", 0) // 1000,
                    "opponent": (opp.get("user") or {}).get("name") or ("AI level %s" % opp.get("aiLevel", "?")),
                    "result": "draw" if not w else "win" if w == color else "loss",
                    "analysed": analysis_status(g["id"]).get("state") == "done"})
    return out


class H(BaseHTTPRequestHandler):
    server_version = "gambit"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, obj):
        body = json.dumps(obj).encode()
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the watch dropped Wi-Fi mid-request; it will ask again

    def _send_png(self, body: bytes):
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=31536000")
        self.end_headers()
        self.wfile.write(body)

    def _authed(self) -> bool:
        given = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if hmac.compare_digest(given.encode(), WATCH_KEY.encode()):
            return True
        self._send(401, {"error": "bad key"})
        return False

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        if n > 8192:
            raise ValueError("body too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def _route(self, method: str):
        url = urllib.parse.urlparse(self.path)
        parts = [p for p in url.path.split("/") if p]
        q = urllib.parse.parse_qs(url.query)
        if parts == ["health"]:
            return self._send(200, {"ok": True})
        if not self._authed():
            return
        try:
            if method == "GET" and len(parts) == 3 and parts[0] == "pieces":
                return self._send_png(piece_png(parts[1], parts[2], int(q.get("size", ["128"])[0])))
            out = self._dispatch(method, parts, q)
            if out is None:
                return self._send(404, {"error": "not found"})
            self._send(200, out)
        except LichessError as e:
            msg = e.body
            try:
                msg = json.loads(e.body).get("error", e.body)
            except Exception:
                pass
            self._send(502 if e.code >= 500 else 400, {"error": str(msg)[:300]})
        except (ValueError, KeyError) as e:
            self._send(400, {"error": str(e)[:300]})
        except Exception as e:
            traceback.print_exc()
            self._send(500, {"error": str(e)[:300]})

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def _dispatch(self, method: str, p: list[str], q: dict):
        def need_id(x):
            if not ID_RE.match(x):
                raise ValueError("bad id")
            return x

        def need_user(x):
            if not USER_RE.match(x):
                raise ValueError("bad username")
            return x

        if method == "GET":
            if p == ["events"]:
                after = int(q.get("after", ["-1"])[0])
                wait = min(float(q.get("wait", ["25"])[0]), 50)
                return events_since(after, wait)
            if p == ["home"]:
                return home()
            if p == ["summary"]:
                return summary()
            if p == ["piecesets"]:
                return {"sets": PIECE_SETS, "lichess": lichess_prefs()["pieceSet"]}
            if len(p) == 2 and p[0] == "game":
                return game_detail(need_id(p[1]))
            if len(p) == 3 and p[0] == "game" and p[2] == "chat":
                lines = li("GET", f"/api/board/game/{need_id(p[1])}/chat")
                return {"lines": [{"user": l.get("user"), "text": l.get("text"),
                                   "mine": (l.get("user") or "").lower() == MY_ID} for l in lines][-30:]}
            if p == ["friends"]:
                return {"friends": friends()}
            if len(p) == 2 and p[0] == "player":
                return player(need_user(p[1]))
            if p == ["puzzle", "daily"]:
                return daily_puzzle()
            if p == ["finished"]:
                return {"games": finished()}
            if len(p) == 2 and p[0] == "analysis":
                return analysis_status(need_id(p[1]))
            return None

        b = self._body()
        if len(p) == 3 and p[0] == "game":
            gid, action = need_id(p[1]), p[2]
            if action == "move":
                uci = str(b["uci"])
                d = game_detail(gid)
                if not d["myTurn"]:
                    raise ValueError("not your turn")
                if uci[2:] not in d["legal"].get(uci[:2], []):
                    raise ValueError("illegal move")
                li("POST", f"/api/board/game/{gid}/move/{uci}")
                _refresh.set()
                return game_detail(gid)
            if action == "resign":
                li("POST", f"/api/board/game/{gid}/resign")
                _refresh.set()
                return {"ok": True}
            if action == "draw":  # offer, or accept/decline an offer
                li("POST", f"/api/board/game/{gid}/draw/{'yes' if b.get('accept', True) else 'no'}")
                return game_detail(gid)
            if action == "chat":
                text = str(b["text"]).strip()[:140]
                if not text:
                    raise ValueError("empty message")
                li("POST", f"/api/board/game/{gid}/chat", form={"room": "player", "text": text})
                return {"ok": True}
            return None
        if len(p) == 3 and p[0] == "challenge" and p[2] in ("accept", "decline"):
            li("POST", f"/api/challenge/{need_id(p[1])}/{p[2]}")
            _refresh.set()
            return {"ok": True}
        if len(p) == 3 and p[0] == "challenge" and p[2] == "cancel":
            li("POST", f"/api/challenge/{need_id(p[1])}/cancel")
            return {"ok": True}
        if p == ["new"]:
            days = int(b.get("days", 3))
            if days not in (1, 2, 3, 5, 7, 10, 14):
                raise ValueError("days must be 1, 2, 3, 5, 7, 10 or 14")
            color = b.get("color", "random")
            if color not in ("random", "white", "black"):
                raise ValueError("bad color")
            rated = "true" if b.get("rated", True) else "false"
            if b.get("user"):
                user = need_user(str(b["user"]))
                c = li("POST", f"/api/challenge/{user}", form={"days": days, "color": color, "rated": rated})
                return {"ok": True, "challenge": c.get("id") or (c.get("challenge") or {}).get("id")}
            li("POST", "/api/board/seek", form={"days": days, "color": color, "rated": rated})
            return {"ok": True}
        if len(p) == 2 and p[0] == "follow":
            user = need_user(p[1])
            li("POST", f"/api/rel/{'follow' if b.get('follow', True) else 'unfollow'}/{user}")
            return player(user)
        if len(p) == 2 and p[0] == "message":
            text = str(b["text"]).strip()[:400]
            if not text:
                raise ValueError("empty message")
            li("POST", f"/inbox/{need_user(p[1])}", form={"text": text})
            return {"ok": True}
        if len(p) == 2 and p[0] == "note":
            li("POST", f"/api/user/{need_user(p[1])}/note", form={"text": str(b.get("text", ""))[:500]})
            return player(p[1])
        if len(p) == 3 and p[0] == "puzzle" and p[2] == "result":
            pid = p[1]
            if not re.match(r"^[A-Za-z0-9]{5,8}$", pid):
                raise ValueError("bad puzzle id")
            li("POST", "/api/puzzle/batch/mix",
               js={"solutions": [{"id": pid, "win": bool(b.get("win")), "rated": True}]})
            return {"ok": True}
        if len(p) == 2 and p[0] == "analyze":
            return start_analysis(need_id(p[1]))
        return None


if __name__ == "__main__":
    try:
        poll_playing()
    except Exception as e:
        log("initial poll failed:", e)
    threading.Thread(target=poll_loop, daemon=True).start()
    threading.Thread(target=stream_loop, daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), H)
    srv.daemon_threads = True
    log(f"listening on {BIND}:{PORT}")
    srv.serve_forever()
