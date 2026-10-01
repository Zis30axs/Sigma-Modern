#!/usr/bin/env bash
# A persistent headless SigmaModern client (Xvfb, software GL) you can drive from the shell and screenshot.
#
#   scripts/game.sh start [-Dsigma.debug.x=y ...]   launch (returns at once; see wait-ready)
#   scripts/game.sh wait-ready [seconds]            wait until Sigma has started (default 120)
#   scripts/game.sh shot [out.png]                  F2 screenshot; prints the file's path (and copies it to out.png)
#   scripts/game.sh key F3                          send a key (xdotool names: F3, Escape, e, ctrl+a, ...)
#   scripts/game.sh type "hello"                    type text (into chat, a text field)
#   scripts/game.sh click X Y | rclick X Y | move X Y   mouse, in window pixels (default window 1280x720)
#   scripts/game.sh scroll X Y up|down [notches]   mouse wheel at X Y
#   scripts/game.sh hold X Y | release              press the left button at X Y / let it go (drag with `move` in between)
#   scripts/game.sh log [lines] | status | stop
#
# Environment: SERVER=host:port joins that server on start (quick play; local servers only: see server.sh),
# WORLD=<save name> opens a singleplayer world. GAME_WIDTH/GAME_HEIGHT (1280x720), GAME_MEM (3g), GAME_DISPLAY (98),
# CLIENT_MODE (SIGMA_MODERN). Flags after `start` are passed to the JVM: -Dsigma.debug.enableModules=A,B,
# -Dsigma.debug.settings='Module/Setting=value;...', -Dsigma.debug.openGui=..., see env-agent.md.
#
# The game runs from the classes in $SIGMA_BUILD, so stop it before scripts/build.sh compile. Its game directory is
# $SIGMA_GAME/run; the settings it saves (run/sigma5/config.json) persist between starts, the -D overrides don't.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

W="${GAME_WIDTH:-1280}"; H="${GAME_HEIGHT:-720}"
D="${GAME_DISPLAY:-98}"
PIDFILE="$SIGMA_GAME/game.pid"
LOG="$SIGMA_GAME/session.log"
XAUTH="$SIGMA_GAME/xauth"
SHOTS="$SIGMA_GAME/run/screenshots"

x() { DISPLAY=":$D" XAUTHORITY="$XAUTH" "$@"; }
need_running() { sigma_alive "$PIDFILE" || die "no game running. scripts/game.sh start"; }

case "${1:-}" in
    start)
        shift
        sigma_require_jdk
        command -v xvfb-run >/dev/null || die "xvfb-run is missing (apt-get install -y xvfb xdotool)"
        sigma_alive "$PIDFILE" && die "a game is already running (pid $(cat "$PIDFILE")); scripts/game.sh stop first"
        cp="$(sigma_classpath)"
        sigma_prepare_game_dir
        quick=()
        [ -n "${WORLD:-}" ] && quick=(--quickPlaySingleplayer "$WORLD")
        [ -n "${SERVER:-}" ] && quick=(--quickPlayMultiplayer "$SERVER")
        cd "$SIGMA_GAME"
        # Defaults first, the caller's flags after: the last -D of a name wins.
        setsid xvfb-run -n "$D" -f "$XAUTH" -s "-screen 0 ${W}x${H}x24" \
            "$JAVA_HOME/bin/java" "-Xmx${GAME_MEM:-3g}" --enable-native-access=ALL-UNNAMED -cp "$cp" \
            -Dsigma.debug.musicOffline=true "-Dsigma.debug.setClientMode=${CLIENT_MODE:-SIGMA_MODERN}" "$@" \
            Start --width "$W" --height "$H" ${quick[@]+"${quick[@]}"} >"$LOG" 2>&1 &
        echo $! >"$PIDFILE"
        say "started (pid $(cat "$PIDFILE"), display :$D, log $LOG)"
        ;;
    wait-ready)
        need_running
        limit="${2:-120}"
        for _ in $(seq 1 "$limit"); do
            grep -q 'Started with [0-9]* modules' "$LOG" && exit 0
            sigma_alive "$PIDFILE" || { noise <"$LOG" | tail -30; die "the game exited while starting"; }
            sleep 1
        done
        die "Sigma did not start in ${limit}s (scripts/game.sh log)"
        ;;
    shot)
        need_running
        mkdir -p "$SHOTS"
        before="$(ls -t "$SHOTS"/*.png 2>/dev/null | head -1 || true)"
        x xdotool key F2
        for _ in $(seq 1 30); do
            now="$(ls -t "$SHOTS"/*.png 2>/dev/null | head -1 || true)"
            if [ -n "$now" ] && [ "$now" != "$before" ]; then
                sleep 0.5   # the file is written from another thread
                [ -n "${2:-}" ] && cp "$now" "$2"
                echo "$now"; exit 0
            fi
            sleep 0.5
        done
        die "no screenshot appeared (is the window focused? try: scripts/game.sh key Escape)"
        ;;
    key)   need_running; x xdotool key "${2:?key name}" ;;
    type)  need_running; x xdotool type --delay 40 -- "${2:?text}" ;;
    move)  need_running; x xdotool mousemove "${2:?x}" "${3:?y}" ;;
    click) need_running; x xdotool mousemove "${2:?x}" "${3:?y}"; sleep 0.3; x xdotool click 1 ;;
    rclick) need_running; x xdotool mousemove "${2:?x}" "${3:?y}"; sleep 0.3; x xdotool click 3 ;;
    hold)  need_running; x xdotool mousemove "${2:?x}" "${3:?y}"; sleep 0.3; x xdotool mousedown 1 ;;
    release) need_running; x xdotool mouseup 1 ;;
    scroll) need_running; x xdotool mousemove "${2:?x}" "${3:?y}"; sleep 0.3
            x xdotool click --repeat "${5:-1}" --delay 120 "$([ "${4:?up|down}" = up ] && echo 4 || echo 5)" ;;
    log)   noise <"$LOG" | tail -n "${2:-60}" ;;
    status) sigma_alive "$PIDFILE" && say "running, pid $(cat "$PIDFILE")" || say "not running" ;;
    stop)  sigma_stop_group "$PIDFILE"; say "stopped" ;;
    *)     sed -n '2,20p' "$0"; exit 2 ;;
esac
