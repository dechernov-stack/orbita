#!/usr/bin/env bash
# Двоичные файлы Typst для образа api (шип 4 §4) — в контекст сборки, а не
# `COPY --from=ghcr.io/...`: сборщик в сети с MTU 1380 на VPN не дотягивался до
# ghcr.io («DeadlineExceeded», выкат 25.09). Берутся из выпуска GitHub
# (тот же статический musl-бинарник, что в официальном образе), кладутся в
# ops/typst/typst-<arch> — в git они не идут (.gitignore).
#
#   ops/typst-fetch.sh            # обе архитектуры, если ещё нет
#   TYPST_VERSION=v0.13.1 ops/typst-fetch.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${TYPST_VERSION:-v0.13.1}"
mkdir -p "$ROOT/ops/typst"
for pair in "x86_64:amd64" "aarch64:arm64"; do
  triple="${pair%%:*}"; arch="${pair##*:}"
  out="$ROOT/ops/typst/typst-$arch"
  if [ -s "$out" ]; then echo "==> typst $arch: есть ($out)"; continue; fi
  tmp="$(mktemp -d)"
  echo "==> typst $arch: скачиваю $VERSION"
  curl -sSL -m 600 -o "$tmp/typst.tar.xz" "https://github.com/typst/typst/releases/download/$VERSION/typst-$triple-unknown-linux-musl.tar.xz"
  tar -xJf "$tmp/typst.tar.xz" -C "$tmp" "typst-$triple-unknown-linux-musl/typst"
  mv "$tmp/typst-$triple-unknown-linux-musl/typst" "$out"
  chmod +x "$out"
  rm -rf "$tmp"
  echo "==> typst $arch: $(stat -f%z "$out" 2>/dev/null || stat -c%s "$out") байт"
done
