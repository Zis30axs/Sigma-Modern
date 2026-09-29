#!/usr/bin/env bash
# One-shot capture: boots the client headless, waits for its own debug screenshot, and stops it. For GUI screens and
# HUD states that -Dsigma.debug.* flags can bring up without a world (no server needed).
#
#   scripts/capture.sh out.png 240 -Dsigma.debug.openGui=SIGMA_MODERN
#   scripts/capture.sh out.png 240 -Dsigma.debug.openGui=SIGMA_MODERN '-Dsigma.debug.modernPreviewView=CATEGORY:RENDER'
#   MOUSE=640,360 scripts/capture.sh hover.png 300 -Dsigma.debug.openGui=SIGMA_MODERN     # hover capture
#
# The second argument is how many frames to render before the shot (software GL is slow: 240 is about 15-30 s).
# It uses the same game directory as game.sh, so it refuses to run while a game.sh session is up.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

[ $# -ge 2 ] || { sed -n '2,10p' "$0"; exit 2; }
OUT="$1"; FRAMES="$2"; shift 2
D="${CAPTURE_DISPLAY:-97}"
PIDFILE="$SIGMA_GAME/capture.pid"
XAUTH="$SIGMA_GAME/xauth-capture"
SHOT="$SIGMA_GAME/run/screenshots/sigma-debug.png"
LOG="$SIGMA_GAME/capture.log"

sigma_require_jdk
command -v xvfb-run >/dev/null || die "xvfb-run is missing (apt-get install -y xvfb xdotool)"
sigma_alive "$SIGMA_GAME/game.pid" && die "a game.sh session is running; stop it first (same game directory)"
cp="$(sigma_classpath)"
sigma_prepare_game_dir
rm -f "$SHOT"
cd "$SIGMA_GAME"
setsid xvfb-run -n "$D" -f "$XAUTH" -s "-screen 0 ${GAME_WIDTH:-1280}x${GAME_HEIGHT:-720}x24" \
    "$JAVA_HOME/bin/java" "-Xmx${GAME_MEM:-3g}" --enable-native-access=ALL-UNNAMED -cp "$cp" \
    -Dsigma.debug.musicOffline=true "-Dsigma.debug.setClientMode=${CLIENT_MODE:-SIGMA_MODERN}" \
    "-Dsigma.debug.screenshotAfterFrames=$FRAMES" "$@" \
    Start --width "${GAME_WIDTH:-1280}" --height "${GAME_HEIGHT:-720}" >"$LOG" 2>&1 &
echo $! >"$PIDFILE"
pid="$(cat "$PIDFILE")"

# MOUSE=x,y (window pixels): keep the pointer there for a minute, for hover states.
if [ -n "${MOUSE:-}" ]; then
    ( for _ in $(seq 1 60); do sleep 1; DISPLAY=":$D" XAUTHORITY="$XAUTH" xdotool mousemove "${MOUSE%,*}" "${MOUSE#*,}" 2>/dev/null || true; done ) &
    mouse_pid=$!
fi

for _ in $(seq 1 180); do
    [ -f "$SHOT" ] && { sleep 1; break; }
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
done
[ -n "${mouse_pid:-}" ] && kill "$mouse_pid" 2>/dev/null || true
sigma_stop_group "$PIDFILE"

if [ -f "$SHOT" ]; then
    cp "$SHOT" "$OUT"
    say "captured $OUT"
else
    say "no screenshot. Errors from the log:"
    noise <"$LOG" | grep -iE 'exception|error' | grep -viE 'narrator|flite|iris' | head -8 >&2
    exit 1
fi
