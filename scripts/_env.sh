#!/usr/bin/env bash
# Shared by every script in scripts/ (source it, don't run it). See env-agent.md.
#
# Everything the scripts download or build lives in SIGMA_WORK, outside the repo and outside any session's scratch
# directory, so it survives /clear and new conversations in the same container. Nothing here touches the repo tree.

[ -n "${_SIGMA_ENV_LOADED:-}" ] && return 0
_SIGMA_ENV_LOADED=1

SIGMA_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIGMA_WORK="${SIGMA_WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/sigma-modern}"
SIGMA_BUILD="${SIGMA_BUILD:-$SIGMA_WORK/build}"          # Maven's output dir; the game runs from $SIGMA_BUILD/classes
SIGMA_NATIVES="$SIGMA_WORK/natives"                       # Linux native jars the pom only lists for Windows
SIGMA_CP="$SIGMA_WORK/runtime-cp.list"                    # one classpath entry per line
SIGMA_GAME="${SIGMA_GAME:-$SIGMA_WORK/game}"              # the game's working dir; its game directory is run/ inside
SIGMA_SERVER="${SIGMA_SERVER:-$SIGMA_WORK/server}"        # local vanilla server (server.sh)
SIGMA_LOGS="$SIGMA_WORK/logs"
SIGMA_JDK_DIR="$SIGMA_WORK/jdk"
SIGMA_SETTINGS="$SIGMA_WORK/settings.xml"                 # Maven settings, proxy regenerated from $HTTPS_PROXY
SIGMA_DEPS_MARK="$SIGMA_WORK/.deps-ready"                 # exists once Maven's cache holds everything: builds go offline
SIGMA_JDK_MIN=25

mkdir -p "$SIGMA_WORK" "$SIGMA_LOGS"

# An environment's setup script may run with a bare PATH; Maven is often installed outside it.
if ! command -v mvn >/dev/null 2>&1; then
    for _dir in "${MAVEN_HOME:-}/bin" "${M2_HOME:-}/bin" /opt/maven/bin /usr/share/maven/bin /usr/local/maven/bin; do
        [ -x "$_dir/mvn" ] && { export PATH="$_dir:$PATH"; break; }
    done
    unset _dir
fi

say() { printf '[sigma] %s\n' "$*" >&2; }
die() { printf '[sigma] error: %s\n' "$*" >&2; exit 1; }

# The JVM prints this on every start; it is noise in every log and every grep.
noise() { grep -v -e '^Picked up JAVA_TOOL_OPTIONS' -e '^WARNING: ' || true; }

sigma_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo x64 ;;
        aarch64|arm64) echo aarch64 ;;
        *) die "unsupported CPU $(uname -m)" ;;
    esac
}

# Major version of the JDK at $1, or 0 when $1 isn't one.
sigma_java_major() {
    [ -x "$1/bin/javac" ] || { echo 0; return; }
    "$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p' | head -1 | grep . || echo 0
}

# Sets JAVA_HOME to a JDK >= SIGMA_JDK_MIN: the one already in JAVA_HOME, one installed system-wide, or ours.
# CLAUDE.md: the `java` on PATH may be older, and then mvn fails with a misleading "release 25 not supported".
sigma_find_jdk() {
    local candidate
    for candidate in "${JAVA_HOME:-}" /usr/lib/jvm/*25* /usr/lib/jvm/*-2[6-9]* "$SIGMA_JDK_DIR"/jdk-25* "$SIGMA_JDK_DIR"/jdk-2[6-9]*; do
        [ -n "$candidate" ] && [ -d "$candidate" ] || continue
        if [ "$(sigma_java_major "$candidate")" -ge "$SIGMA_JDK_MIN" ] 2>/dev/null; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            return 0
        fi
    done
    return 1
}

sigma_require_jdk() {
    sigma_find_jdk || die "no JDK $SIGMA_JDK_MIN+ found. Run scripts/setup.sh"
}

# Maven settings with the agent proxy from $HTTPS_PROXY. The port differs from one session to the next, so this is
# rewritten whenever it changes rather than saved once.
sigma_write_settings() {
    python3 - "$SIGMA_SETTINGS" "${HTTPS_PROXY:-${https_proxy:-}}" <<'PY'
import sys
from urllib.parse import urlparse
path, proxy = sys.argv[1], sys.argv[2]
xml = "<settings>\n"
if proxy:
    u = urlparse(proxy if "//" in proxy else "http://" + proxy)
    xml += ("  <proxies>\n    <proxy>\n      <id>agent-proxy</id>\n      <active>true</active>\n"
            "      <protocol>https</protocol>\n      <host>%s</host>\n      <port>%s</port>\n" % (u.hostname, u.port or 80))
    if u.username:
        xml += "      <username>%s</username>\n      <password>%s</password>\n" % (u.username, u.password or "")
    xml += "      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>\n    </proxy>\n  </proxies>\n"
xml += "</settings>\n"
try:
    if open(path).read() == xml:
        sys.exit(0)
except OSError:
    pass
open(path, "w").write(xml)
PY
}

# mvn with this repo's flags. Offline once setup.sh has filled Maven's cache; if that turns out not to be enough
# (a new dependency), it retries online once. SIGMA_ONLINE=1 skips the offline attempt.
# Output goes to $SIGMA_LOGS/mvn.log and, filtered, to stdout; the exit code is Maven's.
sigma_mvn() {
    sigma_require_jdk
    sigma_write_settings
    local common=(-B -s "$SIGMA_SETTINGS" "-Dsigma.buildDirectory=$SIGMA_BUILD")
    local log="$SIGMA_LOGS/mvn.log" status
    cd "$SIGMA_REPO"
    if [ -f "$SIGMA_DEPS_MARK" ] && [ -z "${SIGMA_ONLINE:-}" ]; then
        mvn -o "${common[@]}" "$@" >"$log" 2>&1 && status=0 || status=$?
        if [ "$status" -ne 0 ] && grep -q -e 'offline mode' -e 'Cannot access .* in offline' "$log"; then
            say "offline build lacks a dependency; retrying online"
            mvn "${common[@]}" "$@" >"$log" 2>&1 && status=0 || status=$?
        fi
    else
        mvn "${common[@]}" "$@" >"$log" 2>&1 && status=0 || status=$?
    fi
    return "$status"
}

# True when the pid in file $1 is a live process.
sigma_alive() {
    [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null
}

# Stops the process group whose leader's pid is in file $1: TERM, then KILL. Never `pkill -f <pattern>`: a pattern that
# also appears in the calling command line kills the caller's own shell.
sigma_stop_group() {
    local pidfile="$1" pid
    [ -f "$pidfile" ] || return 0
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
        for _ in 1 2 3 4 5 6; do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
        kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
}

# Downloads $1 to $2 (atomically, so an interrupted download never leaves a half file that looks finished).
sigma_fetch() {
    local url="$1" out="$2" tmp="$2.part"
    curl -sSL -f --retry 3 --retry-delay 2 --max-time "${SIGMA_FETCH_TIMEOUT:-600}" -o "$tmp" "$url" || { rm -f "$tmp"; return 1; }
    mv "$tmp" "$out"
}

sigma_classpath() {
    [ -f "$SIGMA_CP" ] || die "no classpath list. Run scripts/setup.sh"
    [ -d "$SIGMA_BUILD/classes" ] || die "nothing compiled in $SIGMA_BUILD/classes. Run scripts/build.sh compile"
    echo "$SIGMA_BUILD/classes:$(paste -sd: "$SIGMA_CP")"
}

# What the client expects to find in its working directory before it starts. Iris and Sodium write ./config relative to
# the working directory, not the game directory; Start refuses to run without run/assets/indexes and downloads the
# asset index and objects into an existing, empty one on the first run (a few minutes, once).
sigma_prepare_game_dir() {
    mkdir -p "$SIGMA_GAME/config" "$SIGMA_GAME/shaderpacks" "$SIGMA_GAME/run/assets/indexes" "$SIGMA_GAME/run/screenshots"
    # A fresh game directory opens vanilla's first-run screens (the narrator / accessibility welcome, the multiplayer
    # warning), and quick play then never connects. Set once, so later edits to options.txt are left alone.
    local options="$SIGMA_GAME/run/options.txt" mark="$SIGMA_GAME/run/.sigma-options-seeded"
    [ -f "$mark" ] && return 0
    python3 - "$options" <<'PY'
import sys
path = sys.argv[1]
seed = {"onboardAccessibility": "false", "skipMultiplayerWarning": "true", "joinedFirstServer": "true",
        "tutorialStep": "none", "pauseOnLostFocus": "false", "narrator": "0", "guiScale": "2"}
lines = []
try:
    lines = open(path).read().splitlines()
except OSError:
    pass
kept = [l for l in lines if l.split(":", 1)[0] not in seed]
open(path, "w").write("\n".join(kept + ["%s:%s" % kv for kv in seed.items()]) + "\n")
PY
    touch "$mark"
}
