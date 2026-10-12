#!/usr/bin/env python3
"""Release smoke verdict over a captured log (plans/RELEASE-HARDENING.md, Task R1).

Exit 0 = PASS, 1 = FAIL, 2 = not yet (keep waiting).
PASS needs "upcalls ready 3/3" and an id-matched sequence
"KogglesUp loaded N" -> "KogglesUp switch N" -> "load-begin N+1" -> "KogglesUp loaded N+1",
and no Java crash, native fatal signal, tombstone, process death or unresolved upcall.
"""
import re
import sys

FATAL = re.compile(
    r"FATAL EXCEPTION|NoSuchMethodError|ClassNotFoundException|UnsatisfiedLinkError"
    r"|upcalls: |Fatal signal \d+|F DEBUG +: \*\*\*|Process dev\.koggles .*has died"
)
EVENT = re.compile(r"(KogglesUp: loaded|KogglesUp: switch|KogglesG1: load-begin) (\d+)")


def verdict(text):
    bad = FATAL.search(text)
    if bad:
        return 1, "FAIL: " + text[max(0, bad.start() - 200): bad.end() + 400]
    ready = re.search(r"upcalls ready (\d)/3", text)
    if ready and ready.group(1) != "3":
        return 1, "FAIL: upcalls ready " + ready.group(1) + "/3"
    if not ready:
        return 2, "waiting for upcalls ready"
    seq = [(kind.split(": ")[1], int(n)) for kind, n in EVENT.findall(text)]
    for i, (kind, n) in enumerate(seq):
        if kind != "loaded":
            continue
        rest = iter(seq[i + 1:])
        want = [("switch", n), ("load-begin", n + 1), ("loaded", n + 1)]
        if all(any(e == w for e in rest) for w in want):
            return 0, f"PASS: upcalls ready 3/3; loaded {n} -> switch {n} -> load-begin {n + 1} -> loaded {n + 1}"
    return 2, "waiting for an upcall-driven auto-advance"


if __name__ == "__main__":
    rc, msg = verdict(open(sys.argv[1], errors="replace").read())
    print(msg)
    sys.exit(rc)
