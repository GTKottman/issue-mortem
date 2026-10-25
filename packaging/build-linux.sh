#!/usr/bin/env bash
# Builds the helper's Linux packages into dist/: .deb, .rpm and Arch .pkg.tar.zst, for amd64 and arm64.
# Needs Go and nfpm (go install github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.43.0).
set -euo pipefail
cd "$(dirname "$0")/.."
export VERSION=${VERSION:-0.0.1} HOMEPAGE=${HOMEPAGE:-https://github.com/GTKottman/issue-mortem} LICENSE=${LICENSE:-GPL-3.0-or-later}
mkdir -p dist
for arch in amd64 arm64; do
  export ARCH=$arch BIN=dist/bin/issue-mortem-helper
  (cd helper && CGO_ENABLED=0 GOOS=linux GOARCH=$arch go build -trimpath -ldflags "-s -w" -o "../$BIN" .)
  for p in deb rpm archlinux; do nfpm package -f packaging/linux/nfpm.yaml -p $p -t dist/; done
done
ls -1 dist/*.deb dist/*.rpm dist/*.pkg.tar.zst
