#!/bin/bash
# Выпуск обновления: ./scripts/release.sh 1.0.1 "Что нового"
# Поднимает версию, собирает подписанный APK и публикует релиз v<версия> на GitHub.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="Tolib-N8/andro6tube"
VERSION="${1:-}"
NOTES="${2:-}"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || { echo "Использование: $0 <версия, напр. 1.0.1> [\"описание\"]"; exit 1; }
[ -f keystore.properties ] || { echo "Нет keystore.properties — без ключа подписи обновления не встанут"; exit 1; }

CUR_NAME=$(grep '^appVersionName=' gradle.properties | cut -d= -f2)
CUR_CODE=$(grep '^appVersionCode=' gradle.properties | cut -d= -f2)
NEW_CODE=$((CUR_CODE + 1))
echo "Версия: $CUR_NAME ($CUR_CODE) → $VERSION ($NEW_CODE)"

sed -i '' "s/^appVersionName=.*/appVersionName=$VERSION/; s/^appVersionCode=.*/appVersionCode=$NEW_CODE/" gradle.properties

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew -q :app:assembleRelease

mkdir -p dist
APK="dist/Andro6Tube-v$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
echo "Собран $APK"

if git rev-parse --git-dir >/dev/null 2>&1; then
  git add -A && git commit -qm "Release v$VERSION" && git tag "v$VERSION" && git push -q --follow-tags || true
fi

gh release create "v$VERSION" "$APK" --repo "$REPO" --title "v$VERSION" --notes "${NOTES:-Обновление $VERSION}"
echo "Готово: в машине «Настройки → Проверить обновления»"
