#!/usr/bin/env bash
# Synology NAS: one-time stable signing setup; no Docker/JDK required.
# Dependencies: git, curl, tar, openssl, bash, and access to github.com.
set -Eeuo pipefail
umask 077

REPOSITORY='automationseDev/PokeSleepRater'
NAS_BASE="${PSR_NAS_BASE:-/volume2/docker2}"
SECRET_DIR="$NAS_BASE/.secrets/pokesleeprater"
KEY_FILE="$SECRET_DIR/release.jks"
PASSWORD_FILE="$SECRET_DIR/password"
GH_CONFIG_DIR="$SECRET_DIR/gh-config"
BIN_DIR="$SECRET_DIR/bin"
GH_VERSION='2.105.0'
DOWNLOAD_DIR=''
TMP_KEY=''
TMP_CERT=''
cleanup() {
    [[ -z "$TMP_KEY" ]] || rm -f -- "$TMP_KEY"
    [[ -z "$TMP_CERT" ]] || rm -f -- "$TMP_CERT"
    [[ -z "$DOWNLOAD_DIR" ]] || rm -rf -- "$DOWNLOAD_DIR"
}
trap cleanup EXIT

log() { printf '[PokeSleepRater] %s\n' "$*"; }
fail() { printf '[PokeSleepRater] ERROR: %s\n' "$*" >&2; exit 1; }
for dependency in curl tar openssl git; do
    command -v "$dependency" >/dev/null 2>&1 || fail "Missing command: $dependency"
done

mkdir -p "$SECRET_DIR" "$GH_CONFIG_DIR" "$BIN_DIR"
chmod 700 "$SECRET_DIR" "$GH_CONFIG_DIR" "$BIN_DIR"
export GH_CONFIG_DIR

# Download a verified official gh binary instead of building a Docker image.
if command -v gh >/dev/null 2>&1; then
    GH_BIN="$(command -v gh)"
elif [[ -x "$BIN_DIR/gh" ]]; then
    GH_BIN="$BIN_DIR/gh"
else
    case "$(uname -m)" in
        x86_64|amd64) ARCH='amd64' ;;
        aarch64|arm64) ARCH='arm64' ;;
        *) fail "Unsupported NAS architecture: $(uname -m)" ;;
    esac
    NAME="gh_${GH_VERSION}_linux_${ARCH}.tar.gz"
    ROOT="https://github.com/cli/cli/releases/download/v${GH_VERSION}"
    DOWNLOAD_DIR="$(mktemp -d "$SECRET_DIR/.gh-download.XXXXXXXX")"
    log "Downloading official GitHub CLI v$GH_VERSION ($ARCH). Network requests have timeouts."
    curl -fLSs --connect-timeout 12 --max-time 180 --retry 2 --retry-delay 3 \
        -o "$DOWNLOAD_DIR/$NAME" "$ROOT/$NAME" || fail 'GitHub CLI download failed: check NAS network/DNS/TLS access.'
    curl -fLSs --connect-timeout 12 --max-time 60 --retry 2 --retry-delay 3 \
        -o "$DOWNLOAD_DIR/checksums.txt" "$ROOT/gh_${GH_VERSION}_checksums.txt" || fail 'Could not download official checksums.'
    EXPECTED="$(awk -v file="$NAME" '$2 == file {print $1; exit}' "$DOWNLOAD_DIR/checksums.txt")"
    ACTUAL="$(openssl dgst -sha256 "$DOWNLOAD_DIR/$NAME" | awk '{print $NF}')"
    [[ -n "$EXPECTED" && "$ACTUAL" == "$EXPECTED" ]] || fail 'GitHub CLI checksum verification failed.'
    log 'Verified download checksum. Extracting GitHub CLI.'
    tar -xzf "$DOWNLOAD_DIR/$NAME" -C "$DOWNLOAD_DIR" "gh_${GH_VERSION}_linux_${ARCH}/bin/gh" \
        || fail 'GitHub CLI archive extraction failed.'
    cp "$DOWNLOAD_DIR/gh_${GH_VERSION}_linux_${ARCH}/bin/gh" "$BIN_DIR/gh"
    chmod 700 "$BIN_DIR/gh"
    GH_BIN="$BIN_DIR/gh"
fi
"$GH_BIN" --version | head -n 1

if ! "$GH_BIN" auth status --hostname github.com >/dev/null 2>&1; then
    log 'GitHub authentication required. Follow the device-code instructions shown below.'
    "$GH_BIN" auth login --hostname github.com --git-protocol https --web || fail 'GitHub login failed.'
fi
"$GH_BIN" auth status --hostname github.com || fail 'GitHub authentication was not completed.'

# Never rotate a key if an existing one is registered on GitHub.
log 'Checking existing signing Secrets and local key files.'
REMOTE_SECRET_NAMES="$("$GH_BIN" secret list --repo "$REPOSITORY" --json name --jq '.[].name')" \
    || fail 'Could not read repository secret names (permissions may be insufficient).'
if [[ -f "$KEY_FILE" && ! -f "$PASSWORD_FILE" ]] || [[ ! -f "$KEY_FILE" && -f "$PASSWORD_FILE" ]]; then
    fail 'Signing key/password pair is incomplete. Restore BOTH files from backup.'
fi
if [[ ! -f "$KEY_FILE" ]] && grep -Eq '^(PSR_KEYSTORE_BASE64|PSR_KEYSTORE_PASSWORD)$' <<< "$REMOTE_SECRET_NAMES"; then
    fail 'GitHub already has signing Secrets, but NAS key is missing. Restore the original key; do NOT create a new one.'
fi

if [[ ! -f "$KEY_FILE" ]]; then
    log 'Creating new long-lived PKCS#12 signing key with local OpenSSL.'
    TMP_KEY="$(mktemp "$SECRET_DIR/.private.XXXXXXXX")"
    TMP_CERT="$(mktemp "$SECRET_DIR/.cert.XXXXXXXX")"
    openssl rand -hex 32 > "$PASSWORD_FILE" || fail 'Password generation failed.'
    openssl req -new -newkey rsa:3072 -nodes -x509 -sha256 \
        -days 10000 -subj '/CN=PokeSleepRater/OU=Android/O=automationseDev/C=JP' \
        -keyout "$TMP_KEY" -out "$TMP_CERT" >/dev/null 2>&1 \
        || fail 'OpenSSL certificate generation failed.'
    openssl pkcs12 -export -in "$TMP_CERT" -inkey "$TMP_KEY" \
        -name pokesleeprater -out "$KEY_FILE" -passout "file:$PASSWORD_FILE" \
        || fail 'PKCS#12 signing key export failed.'
    chmod 600 "$PASSWORD_FILE" "$KEY_FILE"
    cleanup
    TMP_KEY=''
    TMP_CERT=''
else
    log 'Reusing existing signing key; not generating a replacement.'
fi

# Read the public certificate fingerprint; never print private key material.
CERT_SHA256="$(openssl pkcs12 -in "$KEY_FILE" -passin "file:$PASSWORD_FILE" \
    -clcerts -nokeys 2>/dev/null | openssl x509 -noout -fingerprint -sha256 \
    | sed -n 's/^.*=//p' | tr -d ':\r' | tr 'A-F' 'a-f')" \
    || fail 'Could not validate the stored PKCS#12 keystore.'
[[ "$CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail 'Signing certificate digest is invalid.'

EXISTING_FINGERPRINT="$("$GH_BIN" variable list --repo "$REPOSITORY" --json name,value \
    --jq '.[] | select(.name == "PSR_SIGNING_CERT_SHA256") | .value' \
    | tr -d ':\r' | tr 'A-F' 'a-f')" || fail 'Could not read repository variable names.'
if [[ -n "$EXISTING_FINGERPRINT" && "$EXISTING_FINGERPRINT" != "$CERT_SHA256" ]]; then
    fail 'GitHub has a DIFFERENT pinned signing certificate; refusing to rotate the signing key.'
fi

log 'Registering encrypted signing data in GitHub Secrets.'
base64 "$KEY_FILE" | tr -d '\r\n' | "$GH_BIN" secret set PSR_KEYSTORE_BASE64 --repo "$REPOSITORY" \
    || fail 'Could not register PSR_KEYSTORE_BASE64.'
printf '%s' "$(cat "$PASSWORD_FILE")" | "$GH_BIN" secret set PSR_KEYSTORE_PASSWORD --repo "$REPOSITORY" \
    || fail 'Could not register PSR_KEYSTORE_PASSWORD.'
"$GH_BIN" variable set PSR_SIGNING_CERT_SHA256 --repo "$REPOSITORY" --body "$CERT_SHA256" \
    || fail 'Could not register PSR_SIGNING_CERT_SHA256.'

log 'Triggering GitHub Actions signed release build.'
"$GH_BIN" workflow run android-build.yml --repo "$REPOSITORY" --ref main \
    || fail 'Secrets registered, but workflow trigger failed. Start it manually from GitHub Actions.'
log 'Completed NAS configuration. Preserve BOTH local files in an offline backup:'
log "  $KEY_FILE"
log "  $PASSWORD_FILE"
log "GitHub Actions: https://github.com/$REPOSITORY/actions"
log 'The current debug-signed APK may require one final uninstall when switching to stable release signing.'
