#!/bin/bash
# Runs the client headlessly under Xvfb and joins localhost:25565.
#
# The graphical session is never used: WAYLAND_DISPLAY is removed and SDL/GLFW are forced
# onto the virtual X11 display, so a missing/broken Xvfb fails the run instead of opening a
# window on the desktop.
#
# Environment:
#   VFP_VERSION     ViaFabricPlus target version (default 1.21.1)
#   CLIENT_SECONDS  how long to let the client run before stopping it (default 60)
#   QUICK_PLAY      server address to join (default localhost:25565)
#   CLIENT_LOG      log file (default test/client.log)
set -u
cd "$(dirname "$0")/.."

VFP_VERSION="${VFP_VERSION:-1.21.1}"
CLIENT_SECONDS="${CLIENT_SECONDS:-60}"
QUICK_PLAY="${QUICK_PLAY:-localhost:25565}"
CLIENT_LOG="${CLIENT_LOG:-test/client.log}"
DISPLAY_NUM="${DISPLAY_NUM:-:99}"
XVFB_LOG="${XVFB_LOG:-test/xvfb.log}"

# Never let SDL/GLFW fall back to the user's Wayland session.
unset WAYLAND_DISPLAY
export DISPLAY="$DISPLAY_NUM"
export XDG_SESSION_TYPE=x11
export SDL_VIDEODRIVER=x11
export SDL_AUDIODRIVER=dummy
export ALSOFT_DRIVERS=null
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
# GLFW (used by older Minecraft versions) ignores DISPLAY if it can pick Wayland.
export GLFW_PLATFORM=x11

# Kill leftovers from a previous run.
pkill -f "Xvfb $DISPLAY_NUM " 2>/dev/null || true
pkill -f '[d]evlaunchinjector.Main' 2>/dev/null || true
rm -f "/tmp/.X${DISPLAY_NUM#:}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM#:}" 2>/dev/null || true
sleep 1

if [ "$VFP_VERSION" != auto ]; then
  jq --arg version "$VFP_VERSION" '.general.save_selected_protocol_version=false | .selected_protocol_version=$version' \
    run/config/viafabricplus/settings.json > /tmp/vfp-settings.json
  mv /tmp/vfp-settings.json run/config/viafabricplus/settings.json
fi

Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 -nolisten tcp > "$XVFB_LOG" 2>&1 &
XVFB_PID=$!

# Wait for the virtual display to actually accept connections.
ready=0
for _ in $(seq 1 50); do
  if xdpyinfo -display "$DISPLAY_NUM" >/dev/null 2>&1; then
    ready=1
    break
  fi
  kill -0 "$XVFB_PID" 2>/dev/null || break
  sleep 0.1
done
if [ "$ready" != 1 ]; then
  echo "Xvfb failed to start on $DISPLAY_NUM; see $XVFB_LOG" >&2
  kill "$XVFB_PID" 2>/dev/null || true
  exit 1
fi
echo "Xvfb $DISPLAY_NUM ready (xvfb=$XVFB_PID, target=$QUICK_PLAY, vfp=$VFP_VERSION)"

# --no-daemon keeps the client in this process group, so timing out can kill it reliably.
if [ "$VFP_VERSION" = auto ]; then
  setsid bash gradlew runClient --no-daemon --console=plain \
    -PquickPlay="$QUICK_PLAY" > "$CLIENT_LOG" 2>&1 &
else
  setsid bash gradlew runClient --no-daemon --console=plain \
    -PquickPlay="$QUICK_PLAY" -PvfpVersion="$VFP_VERSION" > "$CLIENT_LOG" 2>&1 &
fi
GRADLE_PID=$!

deadline=$((SECONDS + CLIENT_SECONDS))
while kill -0 "$GRADLE_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
  sleep 0.5
done

if kill -0 "$GRADLE_PID" 2>/dev/null; then
  echo "Client still running after ${CLIENT_SECONDS}s; stopping it"
  kill -TERM -- "-$GRADLE_PID" 2>/dev/null || kill -TERM "$GRADLE_PID" 2>/dev/null || true
  sleep 3
  kill -KILL -- "-$GRADLE_PID" 2>/dev/null || true
  pkill -f '[d]evlaunchinjector.Main' 2>/dev/null || true
fi

wait "$GRADLE_PID" 2>/dev/null || true
kill "$XVFB_PID" 2>/dev/null || true
wait "$XVFB_PID" 2>/dev/null || true
exit 0
