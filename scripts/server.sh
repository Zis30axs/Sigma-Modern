#!/usr/bin/env bash
# A local vanilla dedicated server for in-game checks (effects, commands, combat). 127.0.0.1 only, offline mode, flat.
#
#   scripts/server.sh start          download it on first use, start it, wait until it is up
#   scripts/server.sh cmd "<line>"   run a console command, e.g. cmd "effect give @a speed 300 1"
#   scripts/server.sh wait-join [n]  wait until n players (default 1) have joined since the server started
#   scripts/server.sh log [lines]    tail the server log
#   scripts/server.sh status | stop | reset (deletes the world)
#
# Environment: MC_VERSION (default 26.2), SERVER_PORT (default 25565), SERVER_MEM (default 2g).
# Connect with `SERVER=127.0.0.1:25565 scripts/game.sh start`. Only ever start and join local servers: never point
# the client at someone else's server.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_env.sh"

MC_VERSION="${MC_VERSION:-26.2}"
SERVER_PORT="${SERVER_PORT:-25565}"
SERVER_MEM="${SERVER_MEM:-2g}"
JAR="$SIGMA_SERVER/server-$MC_VERSION.jar"
PIDFILE="$SIGMA_SERVER/server.pid"
LOG="$SIGMA_SERVER/server.log"
FIFO="$SIGMA_SERVER/console"

# Mojang's own launcher manifest: the server download's URL and sha1 for $MC_VERSION.
fetch_jar() {
    mkdir -p "$SIGMA_SERVER"
    local manifest="$SIGMA_SERVER/version_manifest.json" meta url sha1
    sigma_fetch https://piston-meta.mojang.com/mc/game/version_manifest_v2.json "$manifest" || die "cannot reach piston-meta.mojang.com"
    meta="$(python3 - "$manifest" "$MC_VERSION" <<'PY'
import json, sys
manifest, wanted = json.load(open(sys.argv[1])), sys.argv[2]
for v in manifest["versions"]:
    if v["id"] == wanted:
        print(v["url"]); break
else:
    sys.exit("no Minecraft version %s in Mojang's manifest" % wanted)
PY
)"
    sigma_fetch "$meta" "$SIGMA_SERVER/version-$MC_VERSION.json"
    read -r url sha1 < <(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["downloads"]["server"]; print(s["url"], s["sha1"])' "$SIGMA_SERVER/version-$MC_VERSION.json")
    say "downloading the $MC_VERSION server"
    sigma_fetch "$url" "$JAR"
    [ "$(sha1sum "$JAR" | cut -d' ' -f1)" = "$sha1" ] || { rm -f "$JAR"; die "server jar checksum mismatch"; }
}

write_config() {
    echo "eula=true" >"$SIGMA_SERVER/eula.txt"
    [ -f "$SIGMA_SERVER/server.properties" ] && return 0
    cat >"$SIGMA_SERVER/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$SERVER_PORT
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generate-structures=false
gamemode=survival
difficulty=easy
max-tick-time=-1
spawn-protection=0
EOF
}

case "${1:-}" in
    start)
        sigma_require_jdk
        sigma_alive "$PIDFILE" && { say "already running (pid $(cat "$PIDFILE"))"; exit 0; }
        [ -s "$JAR" ] || fetch_jar
        write_config
        rm -f "$FIFO"; mkfifo "$FIFO"; : >"$LOG"
        cd "$SIGMA_SERVER"
        # The console is a fifo; `tail -f /dev/null` holds its write end open so the server never reads EOF and stops.
        setsid bash -c "tail -f /dev/null >'$FIFO' & exec '$JAVA_HOME/bin/java' -Xmx$SERVER_MEM -jar '$JAR' --nogui <'$FIFO'" >"$LOG" 2>&1 &
        echo $! >"$PIDFILE"
        for _ in $(seq 1 90); do
            grep -q 'Done (' "$LOG" && break
            sigma_alive "$PIDFILE" || { noise <"$LOG" | tail -20; die "the server exited while starting"; }
            sleep 1
        done
        grep -q 'Done (' "$LOG" || die "the server did not finish starting in 90 s (log: $LOG)"
        # A flat world is otherwise crowded with slimes, which get in the way of every fight and screenshot.
        echo "gamerule spawn_mobs false" >"$FIFO"
        say "up on 127.0.0.1:$SERVER_PORT ($MC_VERSION)"
        ;;
    cmd)
        shift; [ $# -ge 1 ] || die "cmd needs a console command"
        sigma_alive "$PIDFILE" || die "the server isn't running"
        echo "$*" >"$FIFO"
        ;;
    wait-join)
        want="${2:-1}"
        for _ in $(seq 1 180); do
            [ "$(grep -c 'joined the game' "$LOG" 2>/dev/null || true)" -ge "$want" ] && exit 0
            sleep 1
        done
        die "$want player(s) did not join in 180 s"
        ;;
    log)
        tail -n "${2:-40}" "$LOG" | noise
        ;;
    status)
        sigma_alive "$PIDFILE" && say "running, pid $(cat "$PIDFILE"), $(grep -c 'joined the game' "$LOG" || true) join(s)" || say "not running"
        ;;
    stop)
        if sigma_alive "$PIDFILE"; then
            echo stop >"$FIFO" 2>/dev/null || true
            for _ in $(seq 1 20); do sigma_alive "$PIDFILE" || break; sleep 0.5; done
        fi
        sigma_stop_group "$PIDFILE"
        rm -f "$FIFO"
        say "stopped"
        ;;
    reset)
        sigma_alive "$PIDFILE" && die "stop the server first"
        rm -rf "$SIGMA_SERVER/world" && say "world deleted"
        ;;
    *)
        sed -n '2,12p' "$0"; exit 2
        ;;
esac
