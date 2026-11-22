#!/usr/bin/env sh
# Portable Gradle 8.9 bootstrap (no gradle-wrapper.jar required).
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/pokesleep-bootstrap"
GRADLE_HOME="$DIST_ROOT/gradle-8.9"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$DIST_ROOT"
  ARCHIVE="$DIST_ROOT/gradle-8.9-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fLsS --retry 3 https://services.gradle.org/distributions/gradle-8.9-bin.zip -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -q https://services.gradle.org/distributions/gradle-8.9-bin.zip -O "$ARCHIVE"
  else
    echo "curl or wget required for first-time Gradle setup" >&2
    exit 1
  fi
  unzip -q -o "$ARCHIVE" -d "$DIST_ROOT"
  rm -f "$ARCHIVE"
fi
cd "$PROJECT_ROOT"
exec "$GRADLE_HOME/bin/gradle" "$@"
