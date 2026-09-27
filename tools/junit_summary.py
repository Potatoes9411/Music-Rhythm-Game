#!/usr/bin/env python3
"""Summarises Gradle JUnit XML results into artifacts/test-reports/junit-summary.md."""
import glob, re, sys, os
suites = [("core (:core:test)", "core/build/test-results/test"), ("app pure logic (:app:test)", "app/build/test-results/test"),
          ("app on Robolectric (:app:roboTest)", "app/build/test-results/roboTest")]
out = ["# JUnit summary\n"]
for title, d in suites:
    rows = []
    for f in sorted(glob.glob(os.path.join(d, "*.xml"))):
        s = open(f).read()
        cls = re.search(r'<testsuite name="([^"]+)"', s).group(1).split(".")[-1]
        for m in re.finditer(r'<testcase name="([^"]+)"[^>]*time="([^"]+)"(/?)>', s):
            name, t, closed = m.groups()
            failed = not closed and "<failure" in s[m.end():s.find("</testcase>", m.end())]
            rows.append((cls, name, float(t), failed))
    ok = sum(1 for r in rows if not r[3])
    out.append(f"## {title}: {ok}/{len(rows)} passed\n\n| class | test | s | result |\n|---|---|---|---|")
    out += [f"| {c} | {n} | {t:.2f} | {'FAIL' if fl else 'pass'} |" for c, n, t, fl in rows]
    out.append("")
target = sys.argv[1] if len(sys.argv) > 1 else "artifacts/test-reports/junit-summary.md"
open(target, "w").write("\n".join(out) + "\n")
print("wrote", target)
