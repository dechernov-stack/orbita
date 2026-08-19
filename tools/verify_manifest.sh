#!/usr/bin/env bash
# Проверка полноты комплекта по MANIFEST.sha256.
set -uo pipefail
cd "$(dirname "$0")/.."
if [ ! -f MANIFEST.sha256 ]; then echo "MANIFEST.sha256 не найден"; exit 1; fi
missing=0; changed=0
while read -r sum path; do
  if [ ! -f "$path" ]; then echo "ОТСУТСТВУЕТ: $path"; missing=$((missing+1))
  elif [ "$(sha256sum "$path" | cut -d' ' -f1)" != "$sum" ]; then echo "ИЗМЕНЁН: $path"; changed=$((changed+1)); fi
done < MANIFEST.sha256
total=$(wc -l < MANIFEST.sha256)
echo "файлов в манифесте: $total; отсутствует: $missing; изменено: $changed"
[ "$missing" -eq 0 ] || exit 1
