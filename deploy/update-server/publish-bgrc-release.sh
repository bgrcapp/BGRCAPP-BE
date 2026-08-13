#!/usr/bin/env bash
set -euo pipefail

# 사용 예: ./deploy/update-server/publish-bgrc-release.sh 1.2.9.1
# 개인 서명 키는 개발 Mac에만 존재해야 하며, Ubuntu에는 전달하지 않는다.

VERSION="${1:?사용법: $0 <major.minor.patch[.build ...]>}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SIGNING_KEY="${BGRC_UPDATE_SIGNING_KEY:-$HOME/.config/bgrc-attendance/update-signing-private.pem}"
UPDATE_HOST="${BGRC_UPDATE_HOST:-feralshining@100.108.22.80}"
UPDATE_ROOT="${BGRC_UPDATE_ROOT:-/home/feralshining/bgrc-updates}"
UPDATE_BASE_URL="${BGRC_UPDATE_BASE_URL:-https://bgrc.howmanycals.online}"

# 개발 Mac에서 JAVA_HOME이 비어 있으면 설치된 Homebrew Java 17을 우선 사용한다.
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVA_EXECUTABLE="${JAVA_EXECUTABLE:-}"
if [[ -z "$JAVA_EXECUTABLE" ]]; then
    JAVA_EXECUTABLE=java
    if [[ -n "${JAVA_HOME:-}" ]]; then
        JAVA_EXECUTABLE="$JAVA_HOME/bin/java"
    fi
fi

if [[ ! "$VERSION" =~ ^[0-9]+(\.[0-9]+){2,}$ ]]; then
    echo "버전은 major.minor.patch 또는 그 이상의 숫자 형식이어야 합니다: $VERSION" >&2
    exit 1
fi
if [[ ! -f "$SIGNING_KEY" ]]; then
    echo "개인 서명 키를 찾을 수 없습니다: $SIGNING_KEY" >&2
    exit 1
fi

PUBLIC_KEY_BASE64="$(openssl pkey -in "$SIGNING_KEY" -pubout -outform DER | base64 | tr -d '\n')"
STAGING_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/bgrc-update-release.XXXXXX")"
trap 'rm -rf -- "$STAGING_DIRECTORY"' EXIT

cd "$PROJECT_ROOT"
bash ./gradlew clean build -PreleaseVersion="$VERSION"
bash ./gradlew packageWindowsDistribution \
    -PreleaseVersion="$VERSION" \
    -PupdatePublicKeyBase64="$PUBLIC_KEY_BASE64"

mkdir -p "$STAGING_DIRECTORY/releases" "$STAGING_DIRECTORY/stable"
"$JAVA_EXECUTABLE" -cp "launcher/build/libs/attendance-launcher-${VERSION}.jar" \
    com.bgrc.attendance.launcher.UpdateManifestTool \
    --version "$VERSION" \
    --jar "build/libs/attendance-${VERSION}.jar" \
    --private-key "$SIGNING_KEY" \
    --release-directory "$STAGING_DIRECTORY/releases" \
    --stable-directory "$STAGING_DIRECTORY/stable" \
    --base-url "$UPDATE_BASE_URL"

ssh "$UPDATE_HOST" "mkdir -p '$UPDATE_ROOT/public/releases' '$UPDATE_ROOT/public/stable'"

# JAR가 완전히 전달되기 전에는 manifest를 바꾸지 않는다.
scp "$STAGING_DIRECTORY/releases/attendance-${VERSION}.jar" \
    "$UPDATE_HOST:$UPDATE_ROOT/public/releases/attendance-${VERSION}.jar"
scp "$STAGING_DIRECTORY/stable/manifest.json" \
    "$UPDATE_HOST:$UPDATE_ROOT/public/stable/manifest.json.next"
scp "$STAGING_DIRECTORY/stable/manifest.json.sig" \
    "$UPDATE_HOST:$UPDATE_ROOT/public/stable/manifest.json.sig.next"
ssh "$UPDATE_HOST" "mv '$UPDATE_ROOT/public/stable/manifest.json.next' '$UPDATE_ROOT/public/stable/manifest.json' && mv '$UPDATE_ROOT/public/stable/manifest.json.sig.next' '$UPDATE_ROOT/public/stable/manifest.json.sig'"

echo "배포 완료: $UPDATE_BASE_URL/stable/manifest.json"
echo "Windows 최초 설치 배포본: $PROJECT_ROOT/build/distributions/bgrc-attendance-${VERSION}-windows.zip"
