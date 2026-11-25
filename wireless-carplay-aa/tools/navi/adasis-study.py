#!/usr/bin/env python3
"""adasis-study.py - do the car navigation's ADASIS side branches cover Android Auto's turns?

usage: adasis-study.py <logcat.txt> [--window M] [--stubs]

While a phone guides, the car navigation has no route but keeps sending the ADAS ECU an
ADASIS v2 horizon along the road ahead, and logs it (tag CoNA): [ENGINE][POS] the car's
offset on the path, [ENGINE][STUB] a branch leaving the path at an offset, with its turn
angle. The Wireless mod's Android Auto library logs Android Auto's navigation messages
(tag NAVDUMP): "S" NavigationState (the steps: the next maneuver's type), "P"
NavigationCurrentPosition (meters to it). This lines them up:

  - each Android Auto maneuver gets a path offset: the car's offset + the meters to it,
    from the last few P messages before it was passed;
  - the stubs within --window meters (default 25) of that offset are its junction's
    branches, angles in degrees (ADASIS v2: 0-255 for 0-360, right of the path positive
    up to 180).

What it prints: one line per maneuver (type, how many branches, their angles), then how
many maneuvers had any. No coordinates and no road names; the log's own lines hold both,
so share this output, not the log. A study tool for one drive, not a matcher: offsets are
ADASIS's path offsets (meters, wrapping at 8191), and a reroute or a new path id between
P and STUB lines can mismatch a maneuver.
"""
import argparse, re, sys

TS = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)")
STUB = re.compile(r"\[ENGINE\]\[STUB\].*?Off : (\d+).*?Cyc : (\d+).*?pathid : (\d+).*?subid : (\d+).*?repro : (\d+)"
                  r".*?Lane : (\d+).*?OnRoute : (\d+).*?RoadClass: (\d+).*?LinkClass: (\d+).*?TurnAngle: (\d+)")
POS = re.compile(r"\[ENGINE\]\[POS\].*?Off : (\d+)")
DUMP = re.compile(r"NAVDUMP[^:]*: ([SP]) (\d+) ([0-9a-fA-F]*)")
WRAP = 8192
APPROACH = 60    # s before a maneuver's last distance reports that its stubs may come
SIDE = 20        # degrees: a stub turning more than this off the path is a side branch
# NavigationManeuver.NavigationType (aasdk)
TYPES = ["UNKNOWN", "DEPART", "NAME_CHANGE", "KEEP_LEFT", "KEEP_RIGHT", "TURN_SLIGHT_LEFT", "TURN_SLIGHT_RIGHT",
         "TURN_NORMAL_LEFT", "TURN_NORMAL_RIGHT", "TURN_SHARP_LEFT", "TURN_SHARP_RIGHT", "U_TURN_LEFT", "U_TURN_RIGHT",
         "ON_RAMP_SLIGHT_LEFT", "ON_RAMP_SLIGHT_RIGHT", "ON_RAMP_NORMAL_LEFT", "ON_RAMP_NORMAL_RIGHT",
         "ON_RAMP_SHARP_LEFT", "ON_RAMP_SHARP_RIGHT", "ON_RAMP_U_TURN_LEFT", "ON_RAMP_U_TURN_RIGHT",
         "OFF_RAMP_SLIGHT_LEFT", "OFF_RAMP_SLIGHT_RIGHT", "OFF_RAMP_NORMAL_LEFT", "OFF_RAMP_NORMAL_RIGHT",
         "FORK_LEFT", "FORK_RIGHT", "MERGE_LEFT", "MERGE_RIGHT", "MERGE_SIDE_UNSPECIFIED", "ROUNDABOUT_ENTER",
         "ROUNDABOUT_EXIT", "ROUNDABOUT_CW", "ROUNDABOUT_CW_ANGLE", "ROUNDABOUT_CCW", "ROUNDABOUT_CCW_ANGLE",
         "STRAIGHT", "FERRY_BOAT", "FERRY_TRAIN", "DESTINATION", "DESTINATION_STRAIGHT", "DESTINATION_LEFT",
         "DESTINATION_RIGHT"]


def seconds(line):
    m = TS.match(line)
    if not m:
        return None
    mo, d, h, mi, s, ms = map(int, m.groups())
    return ((mo * 31 + d) * 24 + h) * 3600 + mi * 60 + s + ms / 1000.0


def fields(b):
    """protobuf fields: [(number, value)], value an int (varint) or bytes"""
    out, p = [], 0
    while p < len(b):
        tag, p = varint(b, p)
        n, w = tag >> 3, tag & 7
        if w == 0:
            v, p = varint(b, p)
        elif w == 2:
            ln, p = varint(b, p)
            v, p = b[p:p + ln], p + ln
        elif w == 1:
            v, p = b[p:p + 8], p + 8
        elif w == 5:
            v, p = b[p:p + 4], p + 4
        else:
            raise ValueError("wire type %d" % w)
        out.append((n, v))
    return out


def varint(b, p):
    n, s = 0, 0
    while True:
        if p >= len(b):
            raise ValueError("short varint")
        c = b[p]
        p += 1
        n |= (c & 0x7f) << s
        if not c & 0x80:
            return n, p
        s += 7


def first(fs, n):
    for k, v in fs:
        if k == n:
            return v
    return None


def maneuver_type(state):
    """the first step's maneuver type in a NavigationState, or None"""
    step = first(fields(state), 1)
    man = first(fields(step), 1) if isinstance(step, bytes) else None
    t = first(fields(man), 1) if isinstance(man, bytes) else None
    return t if isinstance(t, int) else None


def step_meters(position):
    """meters to the next step in a NavigationCurrentPosition (0 when it has none), or None"""
    sd = first(fields(position), 1)
    dist = first(fields(sd), 1) if isinstance(sd, bytes) else None
    if not isinstance(dist, bytes):
        return None
    m = first(fields(dist), 1)
    return m if isinstance(m, int) else 0


def angle(raw):
    """ADASIS v2 turn angle (0-255) in degrees, -180..180, right positive"""
    d = raw * 360.0 / 256
    return d - 360 if d > 180 else d


def study(lines, window):
    pos = None              # the car's last path offset
    mtype, last = None, []  # the current maneuver, its (time, offset) estimates
    maneuvers, stubs = [], []
    for line in lines:
        t = seconds(line)
        m = POS.search(line)
        if m:
            pos = int(m.group(1))
            continue
        m = STUB.search(line)
        if m:
            off, cyc, path, sub, repro, lanes, onroute, rc, lc, ta = map(int, m.groups())
            stubs.append({"t": t, "off": off, "path": path, "sub": sub, "angle": angle(ta), "raw": ta,
                          "lanes": lanes, "class": rc, "onroute": onroute})
            continue
        m = DUMP.search(line)
        if not m:
            continue
        try:
            b = bytes.fromhex(m.group(3))
        except ValueError:
            continue
        if len(b) != int(m.group(2)):
            continue
        try:
            if m.group(1) == "S":
                nt = maneuver_type(b)
                if nt is not None and nt != mtype:
                    if mtype is not None and last:
                        maneuvers.append((mtype, last[-3:]))
                    mtype, last = nt, []
            else:
                d = step_meters(b)
                if d is not None and d > 0 and pos is not None and mtype is not None:
                    last.append((t, (pos + d) % WRAP))
        except ValueError:
            continue
    if mtype is not None and last:
        maneuvers.append((mtype, last[-3:]))
    rows = []
    for mt, est in maneuvers:
        at = sorted(o for _, o in est)[len(est) // 2]
        t0, t1 = est[0][0] - APPROACH, est[-1][0] + 5
        near, seen = [], set()
        for s in stubs:
            if s["t"] is None or not t0 <= s["t"] <= t1:
                continue
            d = (s["off"] - at + WRAP // 2) % WRAP - WRAP // 2
            key = (s["off"], s["path"], s["sub"], s["raw"])
            if abs(d) <= window and key not in seen:
                seen.add(key)
                near.append(s)
        rows.append((mt, at, near))
    return rows, stubs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--window", type=int, default=25)
    ap.add_argument("--stubs", action="store_true", help="also list every stub (offset, angle, class)")
    a = ap.parse_args()
    with open(a.log, errors="replace") as f:
        rows, stubs = study(f, a.window)
    hit = turns = 0
    for mt, at, near in rows:
        name = TYPES[mt] if 0 <= mt < len(TYPES) else str(mt)
        side = sorted(set(round(s["angle"]) for s in near if abs(s["angle"]) > SIDE))
        cont = sum(1 for s in near if abs(s["angle"]) <= SIDE)
        print("%-24s at %4d m  side branches %s  path stubs %d" % (name, at, side or "none", cont))
        if 5 <= mt <= 10:
            turns += 1
            hit += 1 if side else 0
    sides = [s for s in stubs if abs(s["angle"]) > SIDE]
    print("maneuvers %d; turns %d, with a side branch %d; stubs %d (side branches %d: right %d, left %d)"
          % (len(rows), turns, hit, len(stubs), len(sides), sum(1 for s in sides if s["angle"] > 0),
             sum(1 for s in sides if s["angle"] < 0)))
    if a.stubs:
        for s in stubs:
            print("stub off %4d path %d/%d angle %6.1f (raw %3d) lanes %d class %d onroute %d"
                  % (s["off"], s["path"], s["sub"], s["angle"], s["raw"], s["lanes"], s["class"], s["onroute"]))


if __name__ == "__main__":
    main()
