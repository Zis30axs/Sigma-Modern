#!/usr/bin/env bash
# Prepares this machine to build, test and run SigmaModern. Idempotent: run it again any time, it only does what's missing.
#
#   scripts/setup.sh            install what's missing
#   scripts/setup.sh --check    only report what's missing (exit 1 if anything is)
#   scripts/setup.sh --build    also compile once at the end
#
# What it puts in $SIGMA_WORK (default ~/.cache/sigma-modern): a JDK 25 (Temurin) unless one is installed, Maven's
# proxy settings, the Linux native jars the pom lists only for Windows, and the game's runtime classpath.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

CHECK=0; BUILD=0
for arg in "$@"; do
    case "$arg" in
        --check) CHECK=1 ;;
        --build) BUILD=1 ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) die "unknown option $arg" ;;
    esac
done
missing=0
todo() { missing=1; if [ "$CHECK" -eq 1 ]; then say "missing: $*"; return 1; fi; say "$*"; return 0; }

# ---------------------------------------------------------------------------------------------------- JDK
if sigma_find_jdk; then
    say "JDK ok: $JAVA_HOME"
elif todo "JDK $SIGMA_JDK_MIN+"; then
    arch="$(sigma_arch)"
    # The latest GA release's name and sha256, then that exact release's archive, so the two can't disagree.
    info="$(curl -sS -f --max-time 60 "https://api.adoptium.net/v3/assets/latest/$SIGMA_JDK_MIN/hotspot?architecture=$arch&image_type=jdk&os=linux&vendor=eclipse")" \
        || die "cannot reach api.adoptium.net (see env-agent.md, 'Network')"
    read -r release sha256 < <(python3 -c 'import json,sys; d=json.load(sys.stdin)[0]; print(d["release_name"], d["binary"]["package"]["checksum"])' <<<"$info")
    mkdir -p "$SIGMA_JDK_DIR"
    archive="$SIGMA_JDK_DIR/$release.tar.gz"
    say "downloading $release"
    SIGMA_FETCH_TIMEOUT=900 sigma_fetch "https://api.adoptium.net/v3/binary/version/${release//+/%2B}/linux/$arch/jdk/hotspot/normal/eclipse" "$archive" \
        || die "JDK download failed"
    [ "$(sha256sum "$archive" | cut -d' ' -f1)" = "$sha256" ] || { rm -f "$archive"; die "JDK checksum mismatch"; }
    tar -xzf "$archive" -C "$SIGMA_JDK_DIR" && rm -f "$archive"
    sigma_find_jdk || die "unpacked the JDK but it isn't usable"
    say "JDK installed: $JAVA_HOME"
fi

# ---------------------------------------------------------------------------------------------------- Maven
command -v mvn >/dev/null || die "Maven (mvn) isn't installed"
sigma_write_settings

# ---------------------------------------------------------------------------------------------------- natives
# The pom lists LWJGL, jtracy and Skija natives for Windows (the shipped client's platform). Running or testing here
# needs the Linux twins of the same versions. They are worked out from the pom, so a version bump needs no change here.
mapfile -t wanted < <(python3 - "$SIGMA_REPO/pom.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
seen = set()
for d in ET.parse(sys.argv[1]).getroot().findall("m:dependencies/m:dependency", ns):
    text = lambda tag: (d.find("m:" + tag, ns).text if d.find("m:" + tag, ns) is not None else "")
    group, artifact, version, classifier = text("groupId"), text("artifactId"), text("version"), text("classifier")
    if classifier == "natives-windows":
        item = (group, artifact, version, "natives-linux")
    elif artifact == "skija-windows-x64":
        item = (group, "skija-linux-x64", version, "")
    else:
        continue
    if item not in seen:
        seen.add(item)
        print(*item)
PY
)
mkdir -p "$SIGMA_NATIVES"
natives_missing=0
for entry in "${wanted[@]}"; do
    read -r group artifact version classifier <<<"$entry"
    file="$artifact-$version${classifier:+-$classifier}.jar"
    [ -s "$SIGMA_NATIVES/$file" ] && continue
    natives_missing=1
    todo "native jar $file" || continue
    path="${group//.//}/$artifact/$version/$file"
    for repo in https://repo1.maven.org/maven2 https://libraries.minecraft.net; do
        sigma_fetch "$repo/$path" "$SIGMA_NATIVES/$file" 2>/dev/null && break
    done
    [ -s "$SIGMA_NATIVES/$file" ] || die "cannot download $file"
done
[ "$natives_missing" -eq 0 ] && say "natives ok: ${#wanted[@]} jars"
[ "$(sigma_arch)" = x64 ] || say "warning: the natives are x86_64 Linux; this machine is $(uname -m)"

# ---------------------------------------------------------------------------------------------------- classpath
if [ ! -f "$SIGMA_DEPS_MARK" ] || [ ! -s "$SIGMA_CP" ] || [ "$SIGMA_REPO/pom.xml" -nt "$SIGMA_CP" ]; then
    if todo "Maven dependencies and the runtime classpath"; then
        sigma_require_jdk
        raw="$SIGMA_WORK/runtime-cp.txt"
        # Online on purpose: this is what fills Maven's cache, which the offline builds after it rely on.
        (cd "$SIGMA_REPO" && mvn -B -s "$SIGMA_SETTINGS" dependency:build-classpath -Dmdep.outputFile="$raw" -Dmdep.includeScope=runtime) \
            >"$SIGMA_LOGS/deps.log" 2>&1 || { tail -20 "$SIGMA_LOGS/deps.log" | noise; die "Maven could not resolve the dependencies (log: $SIGMA_LOGS/deps.log)"; }
        # Windows natives can't load here; the Linux ones from $SIGMA_NATIVES take their place.
        { tr ':' '\n' <"$raw" | grep -v -e 'natives-windows' -e 'windows-x64' -e 'windows-aarch64' -e 'windows-x86_64' -e '^$'; ls "$SIGMA_NATIVES"/*.jar; } >"$SIGMA_CP"
        # Test-scope dependencies (JUnit) too, so the first offline `mvn test` doesn't reach for the network.
        (cd "$SIGMA_REPO" && mvn -B -s "$SIGMA_SETTINGS" dependency:resolve -DincludeScope=test) >>"$SIGMA_LOGS/deps.log" 2>&1 || true
        touch "$SIGMA_DEPS_MARK"
        say "classpath ok: $(wc -l <"$SIGMA_CP") entries"
    fi
else
    say "classpath ok: $(wc -l <"$SIGMA_CP") entries"
fi

# ---------------------------------------------------------------------------------------------------- headless display
# Skia/Mesa need libEGL even under Xvfb; a container image may not ship it, nor Xvfb and xdotool.
packages=()
# ldconfig lives in /sbin, which a bare PATH (an environment's setup script) may lack.
{ PATH="$PATH:/sbin:/usr/sbin" ldconfig -p 2>/dev/null | grep -q 'libEGL.so.1'; } || compgen -G '/usr/lib/*/libEGL.so.1' >/dev/null || packages+=(libegl1)
command -v xvfb-run >/dev/null || packages+=(xvfb)
command -v xdotool >/dev/null || packages+=(xdotool)
if [ "${#packages[@]}" -eq 0 ]; then
    say "headless display ok (libEGL, xvfb, xdotool)"
elif todo "apt packages for the headless game: ${packages[*]}"; then
    # Only game.sh and capture.sh need these; building and testing don't, so a failure here is a warning.
    if [ "$(id -u)" -eq 0 ] && command -v apt-get >/dev/null; then
        { apt-get install -y -q "${packages[@]}" || { apt-get update -q && apt-get install -y -q "${packages[@]}"; }; } >"$SIGMA_LOGS/apt.log" 2>&1 \
            && say "installed ${packages[*]}" \
            || say "could not install ${packages[*]} (log: $SIGMA_LOGS/apt.log); building and testing are fine, the headless game may not start"
    else
        say "not root with apt: install by hand for the headless game: apt-get install -y ${packages[*]}"
    fi
fi

if [ "$CHECK" -eq 1 ]; then
    [ "$missing" -eq 0 ] && say "everything is in place" || exit 1
    exit 0
fi

if [ "$BUILD" -eq 1 ]; then
    "$(dirname "${BASH_SOURCE[0]}")/build.sh" compile
fi
say "ready. Next: scripts/build.sh compile | test   (see env-agent.md)"
