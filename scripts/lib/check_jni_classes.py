#!/usr/bin/env python3
"""Fail an Android App Bundle or APK whose native code names a Java class that R8 removed or emptied.

Native code reaches Java by NAME (JNI FindClass / GetMethodID), which R8 cannot see. A library that
ships no R8 rules of its own then loses exactly the classes its `.so` needs, Gradle reports success,
and the app aborts the first time the feature runs — on 2026-10-22, *Index now* in Control
(`NoSuchMethodError: ai.onnxruntime.NodeInfo.<init>`).

The check reads every class-shaped string out of the bundle's `.so` files and looks each one up in
the bundle's dex:
  - present with no members  → R8 emptied it (unless it is a known marker interface);
  - absent, but in a package the dex does contain → R8 removed it.
A string in a package the dex does not contain at all, or whose last segment is not capitalised,
is a URL or a build path, not a class.

usage: check_jni_classes.py <bundle.aab | app.apk>     exit 0 = clean, 1 = a JNI-reached class is missing
"""
import glob, os, re, subprocess, sys, tempfile, zipfile

# Classes the native side names that are legitimately member-less or absent on Android.
KNOWN_EMPTY = {
    "ai/onnxruntime/ValueInfo",  # a marker interface: no members in the source either
}
KNOWN_ABSENT = {
    # androidx.sqlite's KMP library: on Android `SQLiteException` is a typealias for
    # android.database.SQLException, so the class never exists there; the name in libsqliteJni
    # serves the JVM target.
    "androidx/sqlite/SQLiteException",
}

CLASS_RE = re.compile(rb"L?((?:ai|com|org|androidx|app|io|net)/[A-Za-z0-9_/$]+);?")


def dexdump_path():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump")),
                   key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", p.split("/")[-2])])
    if not tools:
        sys.exit("check_jni_classes: no dexdump under $ANDROID_HOME/build-tools")
    return tools[-1]


def main(aab):
    z = zipfile.ZipFile(aab)
    refs = {}
    for name in z.namelist():
        # A bundle keeps its base module under `base/`; an APK is that module at the root.
        if re.match(r"(?:base/)?lib/[^/]+/[^/]+\.so$", name):
            for m in CLASS_RE.finditer(z.read(name)):
                ref = m.group(1).decode()
                # A class name's last segment starts upper-case; the vendors' build paths
                # (MediaPipe's `…/tasks/core`, say) do not.
                if ref.rsplit("/", 1)[-1][:1].isupper():
                    refs.setdefault(ref, set()).add(os.path.basename(name))

    with tempfile.TemporaryDirectory() as tmp:
        dexes = []
        for name in z.namelist():
            if re.match(r"(?:base/dex/)?classes\d*\.dex$", name):
                path = os.path.join(tmp, os.path.basename(name))
                with open(path, "wb") as f:
                    f.write(z.read(name))
                dexes.append(path)
        dump = subprocess.run([dexdump_path(), *dexes], capture_output=True, text=True, check=True).stdout

    members, cur = {}, None
    for line in dump.splitlines():
        m = re.match(r"\s*Class descriptor\s*:\s*'L(.+);'", line)
        if m:
            cur = m.group(1)
            members[cur] = 0
        elif cur and re.match(r"\s*name\s*:", line):
            members[cur] += 1
    packages = {c.rsplit("/", 1)[0] for c in members}

    problems = []
    for ref in sorted(refs):
        libs = ", ".join(sorted(refs[ref]))
        if ref in members:
            if members[ref] == 0 and ref not in KNOWN_EMPTY:
                problems.append(f"{ref} is EMPTY in the dex (named by {libs})")
        elif ref.rsplit("/", 1)[0] in packages and ref not in KNOWN_ABSENT:
            problems.append(f"{ref} is MISSING from the dex (named by {libs})")

    for p in problems:
        print(f"✗ {os.path.basename(aab)}: {p}", file=sys.stderr)
    if problems:
        print("  → R8 stripped a class native code reaches by name: add a -keep rule to "
              "gradle/android-release-rules.pro", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
