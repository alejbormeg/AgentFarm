"""Prints the percentage of small faults in shop.py that the unit tests detect."""
import pathlib
import re
import subprocess
import sys
import tempfile

SWAPS = [(" - ", " + "), (" + ", " - "), (" * ", " / "), (" / ", " * "), (" += ", " -= "), ("100", "10"), ("total = 0", "total = 1"), (" > ", " >= "), (" < ", " <= "), (" == ", " != ")]
root = pathlib.Path(".").resolve()
files = sorted(p.name for p in root.glob("*.py"))
caught = total = 0
for name in files:
    source = (root / name).read_text()
    for old, new in SWAPS:
        for match in re.finditer(re.escape(old), source):
            with tempfile.TemporaryDirectory() as tmp:
                tmp = pathlib.Path(tmp)
                for f in files:
                    (tmp / f).write_text((root / f).read_text())
                (tmp / "tests").mkdir()
                for f in (root / "tests").glob("*.py"):
                    (tmp / "tests" / f.name).write_text(f.read_text())
                (tmp / name).write_text(source[: match.start()] + new + source[match.end():])
                run = subprocess.run(
                    [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."],
                    cwd=tmp,
                    capture_output=True,
                    timeout=60,
                )
            total += 1
            caught += run.returncode != 0
print(round(100 * caught / total, 1) if total else 0)
