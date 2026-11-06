#!/bin/bash
# Automated end-to-end check: makes sure the right local server is running, starts the client
# headlessly under Xvfb, and reports whether it actually joined.
#
# Usage: test/auto-test.sh <gtnh|atm10|atm10sky|atm11> [client-seconds]
#
# Prints PASS when the server logs a new "joined the game" line. The client may later lose
# connection because of broken modded packets; joining is what this mod promises.
set -u
cd "$(dirname "$0")/.."

PACK="${1:-gtnh}"
CLIENT_SECONDS="${2:-60}"
JAVA=/usr/lib/jvm/java-21-openjdk/bin/java
JAVA25=/home/uniq/.local/share/PrismLauncher/java/java-runtime-epsilon/bin/java

case "$PACK" in
  gtnh)
    SERVER_DIR=test/gtnh
    SERVER_LOG="$SERVER_DIR/server.log"
    START_CMD="$JAVA -Xms4G -Xmx6G -Dfml.readTimeout=180 -Duser.language=en @java9args.txt -jar lwjgl3ify-forgePatches.jar nogui"
    SERVER_PATTERN=lwjgl3ify-forgePatches.jar
    VFP_VERSION=1.7.10
    ;;
  atm10)
    SERVER_DIR=test/atm10
    SERVER_LOG="$SERVER_DIR/server.log"
    # PATH java is 27 here; NeoForge 21.1's mixins need Java 21.
    START_CMD="$JAVA @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.219/unix_args.txt nogui"
    SERVER_PATTERN='neoforge/21.1.219/'
    VFP_VERSION=1.21.1
    ;;
  atm10sky)
    SERVER_DIR=test/atm10sky
    SERVER_LOG="$SERVER_DIR/server.log"
    START_CMD="$JAVA @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.250/unix_args.txt nogui"
    SERVER_PATTERN='neoforge/21.1.250/'
    VFP_VERSION=1.21.1
    ;;
  atm11)
    SERVER_DIR=test/atm11
    SERVER_LOG="$SERVER_DIR/server.log"
    START_CMD="$JAVA25 @user_jvm_args.txt @libraries/net/neoforged/neoforge/26.1.2.126/unix_args.txt nogui"
    SERVER_PATTERN='neoforge/26.1.2.126'
    VFP_VERSION=26.1.2
    ;;
  *)
    echo "usage: $0 <gtnh|atm10|atm10sky|atm11> [client-seconds]" >&2
    exit 2
    ;;
esac

port_open() {
  (exec 3<>/dev/tcp/127.0.0.1/25565) 2>/dev/null
}

STARTED=0
SERVER_PID=""
if port_open; then
  if ! pgrep -f "$SERVER_PATTERN" >/dev/null; then
    echo "FAIL: localhost:25565 is in use, but not by the $PACK server ($SERVER_PATTERN). Stop it first." >&2
    exit 2
  fi
  echo "Using the server already listening on localhost:25565 ($PACK)"
else
  if [ ! -f "$SERVER_DIR/startserver-java9.sh" ] && [ ! -f "$SERVER_DIR/run.sh" ]; then
    echo "No server in $SERVER_DIR" >&2
    exit 2
  fi
  echo "Starting $PACK server..."
  : > "$SERVER_LOG"
  setsid bash -c "cd '$SERVER_DIR' && exec $START_CMD" > "$SERVER_LOG" 2>&1 &
  SERVER_PID=$!
  STARTED=1
fi

# Wait for the port and the server's ready line.
ready=0
for _ in $(seq 1 900); do
  if port_open && grep -aq 'Done (' "$SERVER_LOG"; then
    ready=1
    break
  fi
  if [ -n "$SERVER_PID" ] && ! kill -0 "$SERVER_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "FAIL: server did not become ready" >&2
  tail -30 "$SERVER_LOG" >&2 2>/dev/null || true
  [ -n "$SERVER_PID" ] && kill -TERM -- "-$SERVER_PID" 2>/dev/null || true
  exit 1
fi

trap '[ -n "$SERVER_PID" ] && kill -TERM -- "-$SERVER_PID" 2>/dev/null || true' EXIT

ATTEMPTS="${ATTEMPTS:-4}"
RC=1
for attempt in $(seq 1 "$ATTEMPTS"); do
  echo "=== attempt $attempt/$ATTEMPTS ($PACK, vfp=$VFP_VERSION)"
  BEFORE=$(grep -ac 'joined the game' "$SERVER_LOG" 2>/dev/null)
  BEFORE=${BEFORE:-0}
  VFP_VERSION="$VFP_VERSION" CLIENT_SECONDS="$CLIENT_SECONDS" bash test/run-client.sh
  AFTER=$(grep -ac 'joined the game' "$SERVER_LOG" 2>/dev/null)
  AFTER=${AFTER:-0}

  if [ "$AFTER" -gt "$BEFORE" ]; then
    echo "PASS: client joined $PACK"
    grep -a 'joined the game' "$SERVER_LOG" | tail -1
    RC=0
    break
  fi

  if grep -aq 'learned [0-9]* mods\|learned [0-9]* channels\|Reconnect to try again' test/client.log 2>/dev/null; then
    echo "Handshake learned new data; reconnecting"
    continue
  fi
  echo "No new handshake data learned; not retrying" >&2
  break
done

if [ "$RC" != 0 ]; then
  echo "FAIL: client did not join $PACK" >&2
  echo "--- client log (filtered)" >&2
  tr -cd '\11\12\15\40-\176' < test/client.log 2>/dev/null \
    | grep -aE 'ViaFabricPlus target|ATM10 Spoof|Connecting|disconnect|Disconnect|reject|Reject|incompatible|Incompatible|Exception|ERROR' \
    | grep -av 'Native Library\|resource path\|flite\|Realms\|narrator\|profile key\|Classpath\|classpath' \
    | tail -30 >&2 || true
  echo "--- server log (filtered)" >&2
  grep -aE 'lost connection|joined the game|Mod rejections|FML|handshake' "$SERVER_LOG" \
    | tail -20 >&2 || true
fi

if [ "$STARTED" = 1 ] && [ -n "$SERVER_PID" ]; then
  kill -TERM -- "-$SERVER_PID" 2>/dev/null || true
fi
exit "$RC"
