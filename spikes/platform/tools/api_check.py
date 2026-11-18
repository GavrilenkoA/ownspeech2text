#!/usr/bin/env python3
"""Look up API members in the SDK's api-versions.xml (since / deprecated / removed).
Usage: api_check.py <platform-dir> < queries.txt
Query line: <class/with/slashes>[#<member-prefix>]   e.g.
  android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(
  android/view/WindowManager$LayoutParams#TYPE_ACCESSIBILITY_OVERLAY
Prints one line per match: STATUS class#member since=.. deprecated=.. removed=..
"""
import sys, xml.etree.ElementTree as ET
plat = sys.argv[1]
root = ET.parse(plat + "/data/api-versions.xml").getroot()
classes = {c.get("name"): c for c in root.iter("class")}
missing = 0
for line in sys.stdin:
    q = line.strip()
    if not q or q.startswith("#"):
        continue
    cls, _, mem = q.partition("#")
    c = classes.get(cls)
    if c is None:
        print(f"MISSING-CLASS {cls}"); missing += 1; continue
    csince = c.get("since", "1")
    if not mem:
        print(f"OK {cls} since={csince}" + (f" deprecated={c.get('deprecated')}" if c.get('deprecated') else ""))
        continue
    hits = [m for m in c if m.tag in ("method", "field") and m.get("name", "").startswith(mem)]
    if not hits:
        print(f"MISSING {cls}#{mem}"); missing += 1; continue
    for m in hits:
        extra = "".join(f" {k}={m.get(k)}" for k in ("deprecated", "removed") if m.get(k))
        print(f"OK {cls.split('/')[-1]}#{m.get('name')} since={m.get('since', csince)}{extra}")
sys.exit(1 if missing else 0)
