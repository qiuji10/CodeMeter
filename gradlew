#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"

bootstrap_wrapper() {
  mkdir -p "$(dirname "$WRAPPER_JAR")"
  tmp="$WRAPPER_JAR.tmp"
  rm -f "$tmp"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$WRAPPER_URL" --output "$tmp"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$WRAPPER_URL" -O "$tmp"
  else
    echo "Neither curl nor wget is available to bootstrap Gradle." >&2
    exit 1
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$tmp" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$tmp" | awk '{print $1}')
  else
    echo "No SHA-256 utility found; refusing to execute an unverified wrapper JAR." >&2
    rm -f "$tmp"
    exit 1
  fi

  if [ "$actual" != "$WRAPPER_SHA256" ]; then
    echo "Gradle wrapper checksum mismatch." >&2
    echo "Expected: $WRAPPER_SHA256" >&2
    echo "Actual:   $actual" >&2
    rm -f "$tmp"
    exit 1
  fi
  mv "$tmp" "$WRAPPER_JAR"
}

[ -f "$WRAPPER_JAR" ] || bootstrap_wrapper
exec java -jar "$WRAPPER_JAR" "$@"
