#!/usr/bin/env python3
"""road-graph-study.py - how did the map's side streets do on a drive?

usage: road-graph-study.py <HMG-API log>

Reads the Wireless mod's "road graph:" lines from HMG-API's log
(/data/hmgapi/logs/hmgapi/log.log, or `adb shell hmgapid log`), written in
com.mnsoft.navi with navi_road_graph and tbt_map_side_streets on:

  - once a second, the car's link (length, meters along it) and the node ahead (its
    roads' angles, or "not read");
  - each of Android Auto's junction questions (meters to the turn, the turn's angle) and
    the answer ("matched ..." or why not).

It groups the questions into approaches (one maneuver each) and prints, per approach,
what the matcher answered then and what today's rules give for the node ahead of the car
alone: the same window (the phone's distance less PHONE_LONG, within window()) and the
same fit of the turn to the node's roads as NaviGraph.find() and match(). It can't replay
the walk past that node, since the log has no link ids, so "beyond" answers (the junction
is farther than the node ahead) are counted, not judged. Then the phone's distance less
the map's where the node ahead fits the turn, by turn side, speed and distance, and a
least-squares line against the car's speed: a slope near 1 s would be delay, a constant
is where the two maps put the turn.

Prints numbers and angles only: no coordinates, link ids or road names (the lines it
reads hold none either). Keep the constants in step with NaviGraph.java.
"""
import re, statistics, sys

PHONE_LONG = 8


def window(meters):
    return max(25, min(45, meters // 8))


PROBE = re.compile(r"^\S+ (\S+) .*road graph: on a link of (\d+) m, (\d+) m along, heading (\w+), node ahead in (\d+) m: (.*)$")
ASKED = re.compile(r"^\S+ (\S+) .*road graph: junction (\d+) m, turn (-?\d+): (.*)$")


def seconds(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def fits(angles, angle):
    """NaviGraph.match() on the node's angles alone (no sector overlap check)"""
    if not angles or len(angles) < 2:
        return False
    by = sorted((abs(a - angle), a) for a in angles)
    if by[0][0] > (22 if angle == 0 else 60):
        return False
    if len(by) > 1 and by[1][0] - by[0][0] < 15:
        return False
    if angle == 0:
        return True
    pick = by[0][1]
    if (pick <= 12) if angle > 0 else (pick >= -12):
        return False
    if abs(angle) == 90:
        side = [a for a in angles if (30 < a <= 157 if angle > 0 else -157 <= a < -30)]
        if len(side) != 1:
            return False
    return True


def read(path):
    probes, questions = [], []
    for line in open(path, errors="replace"):
        m = PROBE.match(line)
        if m:
            t, length, pos, heading, _, node = m.groups()
            r = re.match(r"(\d+) roads, angles \[([^\]]*)\]", node)
            angles = [int(x) for x in r.group(2).split(",")] if r and r.group(2).strip() else None
            probes.append({"t": seconds(t), "len": int(length), "pos": int(pos), "heading": heading,
                           "angles": angles, "unread": "not read" in node, "v": None})
            continue
        m = ASKED.match(line)
        if m:
            t, meters, angle, answer = m.groups()
            questions.append({"t": seconds(t), "asked": int(meters), "angle": int(angle), "answer": answer})
    return probes, questions


def speeds(probes):
    """meters a second along the map over the last 1.5 to 2.6 s"""
    for i, p in enumerate(probes):
        j, k, dist = i - 1, i, 0.0
        while j >= 0 and p["t"] - probes[j]["t"] < 2.6:
            a, b = probes[j], probes[k]
            if a["len"] == b["len"] and a["heading"] == b["heading"] and b["pos"] >= a["pos"]:
                dist += b["pos"] - a["pos"]
            else:
                dist += (a["len"] - a["pos"]) + b["pos"]
            k, j = j, j - 1
        dt = p["t"] - probes[k]["t"]
        if dt >= 1.5 and dist <= dt * 45:
            p["v"] = dist / dt


def probe_at(probes, t):
    lo, hi, best = 0, len(probes) - 1, None
    while lo <= hi:
        mid = (lo + hi) // 2
        if probes[mid]["t"] <= t + 0.05:
            best, lo = mid, mid + 1
        else:
            hi = mid - 1
    if best is None or t - probes[best]["t"] > 1.6:
        return None
    return probes[best]


def approaches(questions):
    out, cur = [], []
    for q in questions:
        if cur and (q["angle"] != cur[-1]["angle"] or q["asked"] > cur[-1]["asked"] + 40 or q["t"] - cur[-1]["t"] > 6):
            out.append(cur)
            cur = []
        cur.append(q)
    if cur:
        out.append(cur)
    return [a for a in out if len(a) >= 5]


def study(probes, questions):
    """per approach: (angle, answers, from m, to m, matched then, node-ahead matches now, beyond, unread, no fit);
    and the offsets (phone - map, speed, angle, asked)"""
    rows, offsets = [], []
    for a in approaches(questions):
        angle = a[0]["angle"]
        then = sum(1 for q in a if q["answer"].startswith("matched"))
        now = beyond = unread = nofit = 0
        for q in a:
            p = probe_at(probes, q["t"])
            if p is None:
                continue
            if p["unread"]:
                unread += 1
                continue
            left, want, w = p["len"] - p["pos"], q["asked"] - PHONE_LONG, window(q["asked"])
            if abs(left - want) <= w:
                if fits(p["angles"], angle):
                    now += 1
                    offsets.append((q["asked"] - left, p["v"], angle, q["asked"]))
                else:
                    nofit += 1
            elif left < want - w:
                beyond += 1
            else:
                nofit += 1
        rows.append((angle, len(a), a[0]["asked"], a[-1]["asked"], then, now, beyond, unread, nofit))
    return rows, offsets


def spread(x):
    x = sorted(x)
    return "n=%d median %.0f, p10 %d, p90 %d" % (len(x), statistics.median(x), x[len(x) // 10], x[len(x) * 9 // 10])


def fit(samples):
    """offset = a + b * speed, and r; None with too few samples or no spread"""
    n = len(samples)
    if n < 6:
        return None
    mx, my = sum(v for _, v in samples) / n, sum(o for o, _ in samples) / n
    sxx = sum((v - mx) ** 2 for _, v in samples)
    syy = sum((o - my) ** 2 for o, _ in samples)
    sxy = sum((v - mx) * (o - my) for o, v in samples)
    if sxx == 0 or syy == 0:
        return None
    b = sxy / sxx
    return my - b * mx, b, sxy / (sxx * syy) ** 0.5


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    probes, questions = read(sys.argv[1])
    speeds(probes)
    runs = changes = 0
    i = 0
    while i < len(probes):
        j = i
        while j + 1 < len(probes) and probes[j + 1]["len"] == probes[i]["len"] and probes[j + 1]["heading"] == probes[i]["heading"]:
            j += 1
        if j > i + 1:
            pos = [p["pos"] for p in probes[i:j + 1]]
            runs += 1
            changes += sum(1 for a, b in zip(pos, pos[1:]) if b > a) > 3 * sum(1 for a, b in zip(pos, pos[1:]) if b < a)
        i = j + 1
    unread = sum(1 for p in probes if p["unread"])
    print("%d position lines, %d questions" % (len(probes), len(questions)))
    if probes:
        print("position along the link counts up on %d of %d links; node ahead not read on %d%% of lines"
              % (changes, runs, 100 * unread // len(probes)))
    rows, offsets = study(probes, questions)
    print("\napproaches (5 answers or more): %d" % len(rows))
    for r in rows:
        print("  turn %4d: %3d answers, %3d -> %3d m; matched then %2d; node ahead now: matched %2d, beyond %2d, not read %2d, no fit %2d" % r)
    if rows:
        print("  all: %d answers; matched then %d; node ahead now: matched %d, beyond %d, not read %d, no fit %d"
              % tuple(sum(r[k] for r in rows) for k in (1, 4, 5, 6, 7, 8)))
    if not offsets:
        return
    print("\nthe phone's distance less the map's, where the node ahead fits the turn:")
    print("  all: " + spread([o for o, _, _, _ in offsets]))
    for sign, name in ((1, "right"), (-1, "left"), (0, "straight")):
        x = [o for o, _, a, _ in offsets if (a > 0) - (a < 0) == sign]
        if x:
            print("  %s: %s" % (name, spread(x)))
    for lo, hi in ((0, 3), (3, 7), (7, 11), (11, 16), (16, 45)):
        x = [o for o, v, _, _ in offsets if v is not None and lo <= v < hi]
        if x:
            print("  at %d to %d m/s: %s" % (lo, hi, spread(x)))
    for lo, hi in ((0, 40), (40, 100), (100, 250), (250, 700)):
        x = [o for o, _, _, m in offsets if lo <= m < hi]
        if x:
            print("  asked %d to %d m: %s" % (lo, hi, spread(x)))
    line = fit([(o, v) for o, v, _, _ in offsets if v is not None])
    if line:
        print("  least squares: %.1f m + %.2f s x speed (r %.2f)" % line)


if __name__ == "__main__":
    main()
