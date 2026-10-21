#!/bin/bash
# Updates Busbridge for HDL Buspro to the latest release on GitHub, but only if the new version can run on the Java that
# runs the server. The server keeps running the current version until it's restarted, and isn't touched at all if the
# check fails.
set -euo pipefail
cd "$(dirname "$0")"

JAR=releases/hdlserver-LATEST.jar
MAIN_CLASS=com.roysolberg.java.hdlserver.Application
BRANCH=$(git rev-parse --abbrev-ref HEAD)

# The systemd service from the README, if it runs from this folder. Only that one is restarted, and its own Java is the
# one the new version is checked with.
SERVICE=hdlserver
if [ "$(systemctl show "$SERVICE" -p WorkingDirectory --value 2>/dev/null)" = "$PWD/releases" ]; then
    HAS_SERVICE=true
    JAVA=$(systemctl show "$SERVICE" -p ExecStart --value | sed -n 's/^{ path=\([^ ;]*\).*/\1/p')
else
    HAS_SERVICE=false
fi
JAVA=${JAVA:-$(command -v java || true)}
if [ -z "$JAVA" ]; then
    echo "Can't find Java. Install Java 17 or later first (see README.md)." >&2
    exit 1
fi

restart() {
    if [ "$HAS_SERVICE" = true ]; then
        echo "Restarting the server..."
        sudo systemctl restart "$SERVICE"
        echo "Done. Follow it with: journalctl -u $SERVICE -f"
    else
        echo "Restart the server to run the new version (e.g. with 'sudo reboot' if it starts on boot)."
    fi
}

echo "Fetching the latest version..."
git fetch --quiet origin "$BRANCH"
TARGET=$(git rev-parse "origin/$BRANCH")
if [ "$(git rev-parse HEAD)" = "$TARGET" ]; then
    echo "Already up to date."
    # An earlier run may have updated but failed to restart
    if [ "$HAS_SERVICE" = true ] && ! systemctl is-active --quiet "$SERVICE"; then
        echo "The server isn't running."
        restart
    fi
    exit 0
fi
if ! git diff --quiet HEAD -- "$JAR"; then
    echo "$JAR has been changed by hand (e.g. to go back to an earlier version)." >&2
    echo "Run 'git checkout -- $JAR' first if you want to update anyway." >&2
    exit 1
fi

CHECK_DIR=$(mktemp -d)
trap 'rm -rf "$CHECK_DIR"' EXIT
git show "$TARGET:$JAR" > "$CHECK_DIR/hdlserver.jar"
echo "Checking that the new version runs on $JAVA..."
# A time limit, since a JAR from before 2.0 ignores --version and starts a server instead
if ! (cd "$CHECK_DIR" && timeout 60 "$JAVA" -cp hdlserver.jar "$MAIN_CLASS" --version); then
    echo "Not updating: the new version can't run here (see above). The server is unchanged." >&2
    exit 1
fi

# Exactly the version that was checked, even if another has been released since
git merge --quiet --ff-only "$TARGET"
echo "Updated."
restart
