#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(
  cd "$(dirname "${BASH_SOURCE[0]}")" && pwd
)"
ROOT_DIR="$(
  cd "$SCRIPT_DIR/../.." && pwd
)"
XML_DIR="$ROOT_DIR/app/res"
REF_XML_PATH="$XML_DIR/values/strings.xml"
REORDER_XSL_PATH="$SCRIPT_DIR/reorder.xsl"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xml-reorder.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

NEW_XML_PATH="$TMP_DIR/transformed.xml"
COUNTER=0

while IFS= read -r values_dir; do
  file_path="$values_dir/strings.xml"

  if [[ ! -f "$file_path" ]]; then
    continue
  fi

  xsltproc --stringparam reference "$REF_XML_PATH" "$REORDER_XSL_PATH" "$file_path" >"$NEW_XML_PATH"

  if cmp -s "$file_path" "$NEW_XML_PATH"; then
    continue
  fi

  COUNTER=$((COUNTER + 1))
  cp "$NEW_XML_PATH" "$file_path"
  echo "rewrote ${file_path#$ROOT_DIR/}"
done < <(
  find "$XML_DIR" -mindepth 1 -maxdepth 1 -type d -name 'values-*' | LC_ALL=C sort
)

if [[ "$COUNTER" -eq 0 ]]; then
  echo "all files already match the reordered XML"
  exit 0
fi

echo "files rewritten: $COUNTER"
