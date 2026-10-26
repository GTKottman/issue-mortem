#!/usr/bin/env bash
# Builds dist/issue-mortem-helper-<version>-macos.pkg (Apple chip + Intel in one). Runs on macOS only: it needs
# pkgbuild, lipo, sips and iconutil, which come with macOS. Needs Go.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=${VERSION:-0.0.2}
work=$(mktemp -d)
app="$work/root/Applications/Issue Mortem Helper.app/Contents"
mkdir -p "$app/MacOS" "$app/Resources" "$work/root/Library/LaunchAgents" dist

for arch in arm64 amd64; do
  (cd helper && CGO_ENABLED=0 GOOS=darwin GOARCH=$arch go build -trimpath -ldflags "-s -w" -o "$work/helper-$arch" .)
done
lipo -create -output "$app/MacOS/issue-mortem-helper" "$work/helper-arm64" "$work/helper-amd64"
codesign --force --sign - "$app/MacOS/issue-mortem-helper"   # ad-hoc: Apple chips refuse unsigned code

sed "s/VERSION/$VERSION/g" packaging/macos/Info.plist > "$app/Info.plist"
iconset="$work/icon.iconset"; mkdir "$iconset"
for s in 16 32 128 256 512; do
  sips -z $s $s packaging/macos/icon-1024.png --out "$iconset/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) packaging/macos/icon-1024.png --out "$iconset/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$app/Resources/icon.icns"
cp packaging/macos/app.issuemortem.helper.plist "$work/root/Library/LaunchAgents/"

pkgbuild --root "$work/root" --scripts packaging/macos/scripts --identifier app.issuemortem.helper \
  --version "$VERSION" --install-location / "dist/issue-mortem-helper-$VERSION-macos.pkg"
rm -rf "$work"
ls -l dist/*.pkg
