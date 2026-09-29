#!/usr/bin/env bash
# Builds and tests SigmaModern with the right JDK, proxy and output directory.
#
#   scripts/build.sh compile              incremental build into $SIGMA_BUILD (not target/)
#   scripts/build.sh test [-Dtest=Foo]    JUnit tests; prints totals and the failures
#   scripts/build.sh test-only Foo,Bar    shorthand for test -Dtest=Foo,Bar
#   scripts/build.sh javac File.java...   compile single files with plain javac, for the real error (see below)
#   scripts/build.sh clean                delete $SIGMA_BUILD
#
# Extra arguments after the mode go to Maven. The build goes to $SIGMA_BUILD, not target/, so it never pulls classes
# out from under a game running from target/ (pom.xml explains); it does still replace the classes a game started with
# game.sh runs from, so stop that game first. Full Maven output is in $SIGMA_LOGS/mvn.log.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

mode="${1:-compile}"; [ $# -gt 0 ] && shift

surefire_summary() {
    python3 - "$SIGMA_BUILD/surefire-reports" <<'PY'
import glob, re, sys
tests = errors = skipped = failures = 0
bad = []
for path in glob.glob(sys.argv[1] + "/TEST-*.xml"):
    head = open(path, encoding="utf-8", errors="replace").read(4000)
    m = re.search(r'tests="(\d+)" errors="(\d+)" skipped="(\d+)" failures="(\d+)"', head)
    if not m:
        continue
    t, e, s, f = map(int, m.groups())
    tests += t; errors += e; skipped += s; failures += f
    if e or f:
        bad.append(re.search(r'name="([^"]+)"', head).group(1))
print("tests %d, failures %d, errors %d, skipped %d" % (tests, failures, errors, skipped))
for name in sorted(bad):
    print("  FAILED:", name)
sys.exit(1 if bad else 0)
PY
}

case "$mode" in
    compile)
        if sigma_mvn -q -DskipTests compile "$@"; then
            say "compiled into $SIGMA_BUILD/classes"
        else
            noise <"$SIGMA_LOGS/mvn.log" | grep -E 'ERROR|error:' | head -40
            say "compile failed (full log: $SIGMA_LOGS/mvn.log)."
            say "A bare BUILD FAILURE with no file:line means an implicit-compile error: run scripts/build.sh javac <suspect file>."
            exit 1
        fi
        ;;
    test|test-only)
        if [ "$mode" = test-only ]; then
            [ $# -ge 1 ] || die "test-only needs test class names, e.g. PotionStatusTest,ModuleArrayListTest"
            filter="$1"; shift
            set -- "-Dtest=$filter" -Dsurefire.failIfNoSpecifiedTests=false "$@"
        fi
        skija="$(ls "$SIGMA_NATIVES"/skija-linux-x64-*.jar 2>/dev/null | head -1)"
        [ -n "$skija" ] || die "no Skija native jar. Run scripts/setup.sh"
        rm -rf "$SIGMA_BUILD/surefire-reports"
        # Skija's native library is what the rendering tests need; the pom only ships the Windows one.
        sigma_mvn -q "-Dmaven.test.additionalClasspath=$skija" test "$@" || true
        if ! ls "$SIGMA_BUILD"/surefire-reports/TEST-*.xml >/dev/null 2>&1; then
            noise <"$SIGMA_LOGS/mvn.log" | grep -E 'ERROR|error:' | head -40
            die "no test reports: the build failed before any test ran (log: $SIGMA_LOGS/mvn.log)"
        fi
        surefire_summary
        ;;
    javac)
        [ $# -ge 1 ] || die "javac needs source files"
        sigma_require_jdk
        out="$SIGMA_WORK/javac-out"; rm -rf "$out"; mkdir -p "$out"
        # Against the compiled classes, so a single file compiles without the whole module.
        javac --release "$SIGMA_JDK_MIN" -proc:none -d "$out" -cp "$(sigma_classpath)" "$@" 2>&1 | noise
        ;;
    clean)
        rm -rf "$SIGMA_BUILD" && say "removed $SIGMA_BUILD"
        ;;
    *)
        sed -n '2,13p' "$0"; exit 2
        ;;
esac
