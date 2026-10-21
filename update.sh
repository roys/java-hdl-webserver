#!/bin/bash
# Updates the HDL Buspro server to the latest release on GitHub, but only if the new version can run on this Java.
# The server keeps running the current version until it's restarted, and isn't touched at all if the check fails.
set -euo pipefail
cd "$(dirname "$0")"

JAR=releases/hdlserver-LATEST.jar
MAIN_CLASS=com.roysolberg.java.hdlserver.Application
BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "Fetching the latest version..."
git fetch --quiet origin "$BRANCH"
if [ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ]; then
    echo "Already up to date."
    exit 0
fi
if ! git diff --quiet HEAD -- "$JAR"; then
    echo "$JAR has been changed by hand (e.g. to go back to an earlier version)." >&2
    echo "Run 'git checkout -- $JAR' first if you want to update anyway." >&2
    exit 1
fi

CHECK_DIR=$(mktemp -d)
trap 'rm -rf "$CHECK_DIR"' EXIT
git show "origin/$BRANCH:$JAR" > "$CHECK_DIR/hdlserver.jar"
echo "Checking that the new version runs on this Java..."
# A time limit, since a JAR from before 2.0 ignores --version and starts a server instead
if ! (cd "$CHECK_DIR" && timeout 60 java -cp hdlserver.jar "$MAIN_CLASS" --version); then
    echo "Not updating: the new version can't run here (see above). The server is unchanged." >&2
    exit 1
fi

git pull --quiet --ff-only origin "$BRANCH"
echo "Updated."
# Only the systemd service that runs from this folder (see the setup in the README) is restarted
if [ "$(systemctl show hdlserver -p WorkingDirectory --value 2>/dev/null)" = "$PWD/releases" ]; then
    echo "Restarting the server..."
    sudo systemctl restart hdlserver
    echo "Done. Follow it with: journalctl -u hdlserver -f"
else
    echo "Restart the server to run the new version (e.g. with 'sudo reboot' if it starts on boot)."
fi
