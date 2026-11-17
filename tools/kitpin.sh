#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

cd "$(dirname "$0")/.."
[ $# -eq 0 ] || [ "$*" = --check ] || { echo "usage: tools/kitpin.sh [--check]" >&2; exit 2; }
[ -f kit/tools/kitset.sh ] || { echo "kitpin: kit/ is empty, run git submodule update --init" >&2; exit 1; }

dirty=$(git -C kit status --porcelain --ignored)
[ -z "$dirty" ] || { printf 'kitpin: kit/ is not clean:\n%s\n' "$dirty" >&2; exit 1; }
set=$(bash kit/tools/kitset.sh --set kit)

if [ $# -eq 0 ]; then
    head=$(git -C kit rev-parse HEAD)
    git -C kit merge-base --is-ancestor "$head" origin/main || { echo "kitpin: kit/ HEAD $head is not on funekit main" >&2; exit 1; }
    printf 'commit %s\nset %s\n' "$head" "$set" > kit.pin
    echo "kitpin: kit.pin $head"
    exit 0
fi

pin=$(git show :kit.pin) || { echo "kitpin: kit.pin is not staged" >&2; exit 1; }
want=$(printf '%s\n' "$pin" | awk '$1 == "commit" { print $2 }')
wantset=$(printf '%s\n' "$pin" | awk '$1 == "set" { print $2 }')
link=$(git ls-files -s kit | awk '$1 == "160000" { print $2 }')
[ -n "$link" ] || { echo "kitpin: kit is not a gitlink in the index" >&2; exit 1; }
[ "$link" = "$want" ] || { echo "kitpin: the index pins kit at $link, kit.pin names $want" >&2; exit 1; }
[ "$(git -C kit rev-parse HEAD)" = "$link" ] || { echo "kitpin: kit/ is checked out at $(git -C kit rev-parse HEAD), the index pins $link" >&2; exit 1; }
[ "$set" = "$wantset" ] || { echo "kitpin: kit/ hashes to $set, kit.pin pins $wantset" >&2; exit 1; }
echo "kitpin: ok $link"
