#!/usr/bin/env python3
"""Fail an Android App Bundle or APK in which R8 inlined away a method that counts stack frames.

Some libraries find their caller by walking `new Throwable().getStackTrace()` and skipping a fixed
number of frames. Inlining such a method into its caller removes a frame R8 cannot know was being
counted: the build succeeds and the library fails at run time. On 2026-11-11, Flogger's
`StackBasedCallerFinder.findLoggingClass` was inlined into `FluentLogger.forEnclosingClass`,
MediaPipe's `Graph` failed its static initialiser ("no caller found on the stack for:
…FluentLogger"), and face detection never ran in the shipped Control.

The check is structural and one-directional: a method missing from the dex means it was inlined
or removed, so the build is refused. A method still present does NOT prove no call site inlined a
copy of it; that guarantee comes from the `-keep` rule in gradle/android-release-rules.pro (kept
methods are never inlined). ⚠ When a library's marker class is absent the library is skipped, so the
check goes quiet for a library whose marker R8 merged away.

usage: check_stack_frames.py <bundle.aab | app.apk>     exit 0 = clean, 1 = a frame-counting method is gone
"""
import os, re, subprocess, sys, tempfile, zipfile

sys.dont_write_bytecode = True  # no __pycache__ in the checkout
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_jni_classes import dexdump_path  # noqa: E402

# library marker class → (class, method) pairs that must survive as real methods.
FRAME_COUNTED = {
    "com/google/common/flogger/FluentLogger": [
        # `forEnclosingClass()` names the logger after the frame one below this method's own.
        ("com/google/common/flogger/backend/system/StackBasedCallerFinder", "findLoggingClass"),
        # Every log statement's call site: skips this method's frames by count.
        ("com/google/common/flogger/backend/system/StackBasedCallerFinder", "findLogSite"),
    ],
}


def main(path):
    z = zipfile.ZipFile(path)
    with tempfile.TemporaryDirectory() as tmp:
        dexes = []
        for name in z.namelist():
            if re.match(r"(?:base/dex/)?classes\d*\.dex$", name):
                out = os.path.join(tmp, os.path.basename(name))
                with open(out, "wb") as f:
                    f.write(z.read(name))
                dexes.append(out)
        dump = subprocess.run([dexdump_path(), *dexes], capture_output=True, text=True, check=True).stdout

    methods, cur, in_methods = {}, None, False
    for line in dump.splitlines():
        m = re.match(r"\s*Class descriptor\s*:\s*'L(.+);'", line)
        if m:
            cur, in_methods = m.group(1), False
            methods[cur] = set()
        elif re.match(r"\s*(Direct|Virtual) methods\s*-", line):
            in_methods = True
        elif re.match(r"\s*(Static|Instance) fields\s*-", line):
            in_methods = False
        elif cur and in_methods:
            n = re.match(r"\s*name\s*:\s*'(.+)'", line)
            if n:
                methods[cur].add(n.group(1))

    problems = []
    for marker, required in FRAME_COUNTED.items():
        if marker not in methods:
            continue
        for cls, method in required:
            if method not in methods.get(cls, set()):
                problems.append(f"{cls.replace('/', '.')}.{method} is gone from the dex (inlined or removed)")

    for p in problems:
        print(f"✗ {os.path.basename(path)}: {p}", file=sys.stderr)
    if problems:
        print("  → R8 removed a frame a stack-walking library counts on: add a -keep rule to "
              "gradle/android-release-rules.pro", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
