#!/usr/bin/env bash
# Prune stale gradle/actions caches via the GitHub REST API.
# Keeps: the fixed Gradle distributions, the two newest gradle-home
# entries, and the newest of every content-hashed cache family
# (dependencies, transforms, instrumented-jars, groovy-dsl, kotlin-dsl,
# generated-gradle-jars).
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?}"
API="/repos/$REPO/actions/caches"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

list_page() {
  gh api "$API?per_page=100&page=$1" \
    -q '.actions_caches[] | [.id, .key, .created_at] | @tsv'
}

: > "$TMP/all.tsv"
page=1
while :; do
  out="$(list_page "$page" || true)"
  [ -z "$out" ] && break
  printf '%s\n' "$out" >> "$TMP/all.tsv"
  page=$((page + 1))
done

entries=$(wc -l < "$TMP/all.tsv")
echo "caches inspected: $entries"

family() {
  local key="$1"
  case "$key" in
    "gradle-home-v1|"*) echo "gradle-home" ;;
    "gradle-dependencies-v1-"*) echo "dependencies" ;;
    "gradle-transforms-v1-"*) echo "transforms" ;;
    "gradle-instrumented-jars-v1-"*) echo "instrumented-jars" ;;
    "gradle-groovy-dsl-v1-"*) echo "groovy-dsl" ;;
    "gradle-kotlin-dsl-v1-"*) echo "kotlin-dsl" ;;
    "gradle-generated-gradle-jars-v1-"*) echo "generated-gradle-jars" ;;
    *) echo "" ;;
  esac
}

keep_all="$TMP/keep-all.ids"
latest_ids="$TMP/latest.ids"
: > "$keep_all"
: > "$latest_ids"

while IFS=$'\t' read -r id key created; do
  f="$(family "$key")"
  if [ -z "$f" ]; then
    printf '%s\n' "$id" >> "$keep_all"
  elif [ "$f" = "gradle-home" ]; then
    printf '%s\t%s\n' "$created" "$id" >> "$TMP/home.tsv"
  else
    if ! grep -q "$f$" "$TMP/fams" 2>/dev/null; then
      : > "$TMP/$f.id"; : > "$TMP/$f.ts"; printf '%s\n' "$f" >> "$TMP/fams"
    fi
    cur="$(cat "$TMP/$f.ts" 2>/dev/null || true)"
    if [ -z "$cur" ] || [ "$created" \> "$cur" ]; then
      printf '%s\n' "$created" > "$TMP/$f.ts"
      printf '%s\n' "$id" > "$TMP/$f.id"
    fi
  fi
done < "$TMP/all.tsv"

if [ -s "$TMP/home.tsv" ]; then
  sort -r -t$'\t' -k1,1 "$TMP/home.tsv" | head -2 | cut -f2 >> "$latest_ids"
fi
if [ -s "$TMP/fams" ]; then
  while read -r f; do
    [ -s "$TMP/$f.id" ] && cat "$TMP/$f.id" >> "$latest_ids"
  done < "$TMP/fams"
fi

cat "$latest_ids" > "$TMP/keep.ids"
cat "$keep_all" >> "$TMP/keep.ids"

deleted=0
kept=0
while IFS=$'\t' read -r id key created; do
  if grep -qx "$id" "$TMP/keep.ids" 2>/dev/null; then
    kept=$((kept + 1))
    continue
  fi
  gh api -X DELETE "$API/$id" -q . >/dev/null 2>&1 || true
  echo "deleted $key"
  deleted=$((deleted + 1))
done < "$TMP/all.tsv"

echo "kept: $kept, deleted: $deleted"