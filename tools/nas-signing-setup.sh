#!/usr/bin/env bash
# One-time stable signing setup on Synology NAS (Docker required).
# No keystore, password, or GitHub credentials are stored inside the public repository.
set -euo pipefail
umask 077

REPOSITORY="automationseDev/PokeSleepRater"
NAS_BASE="${PSR_NAS_BASE:-/volume2/docker2}"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRET_DIR="$NAS_BASE/.secrets/pokesleeprater"
KEY_FILE="$SECRET_DIR/release.jks"
PASSWORD_FILE="$SECRET_DIR/password"
ENV_FILE="$SECRET_DIR/keytool.env"
GH_CONFIG_DIR="$SECRET_DIR/gh-config"
GH_IMAGE="pokesleeprater-gh:alpine3.23"
JDK_IMAGE="eclipse-temurin:17-jdk"

command -v docker >/dev/null 2>&1 || { echo "Docker / Container Manager is required." >&2; exit 1; }
command -v base64 >/dev/null 2>&1 || { echo "base64 command is required." >&2; exit 1; }
mkdir -p "$SECRET_DIR" "$GH_CONFIG_DIR"
chmod 700 "$SECRET_DIR" "$GH_CONFIG_DIR"
trap 'rm -f "$ENV_FILE"' EXIT

if [[ -e "$KEY_FILE" && ! -e "$PASSWORD_FILE" ]] || [[ ! -e "$KEY_FILE" && -e "$PASSWORD_FILE" ]]; then
  echo "Signing key/password pair is incomplete. Restore both files from backup; never create a replacement key." >&2
  exit 1
fi

echo "Preparing GitHub CLI container for NAS..."
docker build -q -t "$GH_IMAGE" -f "$SOURCE_DIR/tools/nas-gh.Dockerfile" "$SOURCE_DIR/tools"
gh() {
  docker run --rm -i --user "$(id -u):$(id -g)" \
    -e GH_CONFIG_DIR=/gh-config -e HOME=/tmp \
    -v "$GH_CONFIG_DIR:/gh-config" "$GH_IMAGE" "$@"
}
gh_interactive() {
  docker run --rm -it --user "$(id -u):$(id -g)" \
    -e GH_CONFIG_DIR=/gh-config -e HOME=/tmp \
    -v "$GH_CONFIG_DIR:/gh-config" "$GH_IMAGE" "$@"
}

if ! gh auth status --hostname github.com >/dev/null 2>&1; then
  echo "GitHub login is required. Follow the device-code sign-in instructions."
  gh_interactive auth login --hostname github.com --web --git-protocol https
fi

existing_secrets="$(gh secret list --repo "$REPOSITORY" --json name --jq '.[].name')"
if [[ ! -f "$KEY_FILE" ]] && grep -Eq '^(PSR_KEYSTORE_BASE64|PSR_KEYSTORE_PASSWORD)$' <<< "$existing_secrets"; then
  echo "GitHub Secrets exist but NAS signing key is missing. Restore the original key before continuing." >&2
  exit 1
fi

if [[ ! -f "$KEY_FILE" ]]; then
  echo "Creating a single long-lived Android signing key on the NAS..."
  password="$(head -c 48 /dev/urandom | base64 | tr -d '\r\n/+=')"
  [[ "${#password}" -ge 40 ]] || { echo "Could not generate a secure password." >&2; exit 1; }
  printf '%s' "$password" > "$PASSWORD_FILE"
  printf 'KEYTOOL_PASSWORD=%s\n' "$password" > "$ENV_FILE"
  docker run --rm --user "$(id -u):$(id -g)" \
    --env-file "$ENV_FILE" -v "$SECRET_DIR:/keys" "$JDK_IMAGE" \
    keytool -genkeypair -noprompt -storetype PKCS12 \
    -keystore /keys/release.jks -alias pokesleeprater \
    -keyalg RSA -keysize 3072 -validity 11000 \
    -dname 'CN=PokeSleepRater, OU=Android, O=automationseDev, C=JP' \
    -storepass:env KEYTOOL_PASSWORD -keypass:env KEYTOOL_PASSWORD
  chmod 600 "$KEY_FILE" "$PASSWORD_FILE"
else
  echo "Existing signing key found. Reusing it without rotation."
  password="$(cat "$PASSWORD_FILE")"
  printf 'KEYTOOL_PASSWORD=%s\n' "$password" > "$ENV_FILE"
fi

key_details="$(docker run --rm --user "$(id -u):$(id -g)" \
  --env-file "$ENV_FILE" -v "$SECRET_DIR:/keys:ro" "$JDK_IMAGE" \
  keytool -J-Duser.language=en -list -v -storetype PKCS12 \
  -keystore /keys/release.jks -alias pokesleeprater \
  -storepass:env KEYTOOL_PASSWORD)"
fingerprint="$(printf '%s\n' "$key_details" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n1 | tr -d ': \r' | tr 'A-F' 'a-f')"
if [[ ! "$fingerprint" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Could not read signing certificate SHA256. No secrets were changed." >&2
  exit 1
fi

remote_fingerprint="$(gh variable list --repo "$REPOSITORY" --json name,value \
  --jq '.[] | select(.name == "PSR_SIGNING_CERT_SHA256") | .value' | tr -d ':\r' | tr 'A-F' 'a-f')"
if [[ -n "$remote_fingerprint" && "$remote_fingerprint" != "$fingerprint" ]]; then
  echo "Existing GitHub signing fingerprint differs. Refusing to rotate signing key." >&2
  exit 1
fi

echo "Uploading encrypted repository secrets (never printing their values)..."
base64 "$KEY_FILE" | tr -d '\r\n' | gh secret set PSR_KEYSTORE_BASE64 --repo "$REPOSITORY"
cat "$PASSWORD_FILE" | gh secret set PSR_KEYSTORE_PASSWORD --repo "$REPOSITORY"
gh variable set PSR_SIGNING_CERT_SHA256 --repo "$REPOSITORY" --body "$fingerprint"

echo "Starting signed Android build on GitHub Actions..."
gh workflow run android-build.yml --repo "$REPOSITORY" --ref main

echo
echo "Ready. Signing key: $KEY_FILE"
echo "Password file: $PASSWORD_FILE"
echo "Backup BOTH files securely and separately from this NAS."
echo "Do not copy either into the Git repository. Never delete them to regenerate a key."
echo "Actions: https://github.com/$REPOSITORY/actions"
