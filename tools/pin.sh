#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
dst=$root/third_party/funeos
funeos=${FUNEOS:-$HOME/pers/funeos}

if [ $# -ne 1 ]; then
    echo "usage: tools/pin.sh <funeos rev> | tools/pin.sh draft" >&2
    exit 2
fi

if [ "$1" = draft ]; then
    commit=draft
else
    commit=$(git -C "$funeos" rev-parse --verify "$1^{commit}")
    git -C "$funeos" show "$commit:player/link/proto.txt" > "$dst/proto.txt"
    git -C "$funeos" show "$commit:player/link/vectors.txt" > "$dst/vectors.txt"
    git -C "$funeos" show "$commit:player/ui/tokens.txt" > "$dst/tokens.txt"
    git -C "$funeos" show "$commit:player/ui/tokens-v1.txt" > "$dst/tokens-v1.txt"
fi

{
    echo "commit $commit"
    for f in proto.txt vectors.txt tokens.txt tokens-v1.txt; do
        echo "$f $(sha256sum "$dst/$f" | cut -d' ' -f1)"
    done
} > "$dst/PIN.tmp"
mv "$dst/PIN.tmp" "$dst/PIN"
cat "$dst/PIN"
echo "pin.sh: now run ./gradlew :proto:gen"
