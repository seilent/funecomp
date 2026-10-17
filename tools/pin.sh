#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
dst=$root/third_party/funeos
funeos=${FUNEOS:-$HOME/pers/funeos}

usage() {
    echo "usage: tools/pin.sh <funeos rev> | tools/pin.sh --spec <funeos rev> | tools/pin.sh draft" >&2
    exit 2
}

old() { sed -n "s/^commit\.$1 //p" "$dst/PIN"; }

spec=$(old spec)
tokens=$(old tokens)

show() { git -C "$funeos" show "$1:$2" > "$dst/$3"; }

case "${1:-}" in
    draft)
        [ $# -eq 1 ] || usage
        spec=draft
        ;;
    --spec)
        [ $# -eq 2 ] || usage
        spec=$(git -C "$funeos" rev-parse --verify "$2^{commit}")
        show "$spec" player/link/proto.txt proto.txt
        show "$spec" player/link/vectors.txt vectors.txt
        ;;
    "")
        usage
        ;;
    *)
        [ $# -eq 1 ] || usage
        spec=$(git -C "$funeos" rev-parse --verify "$1^{commit}")
        tokens=$spec
        show "$spec" player/link/proto.txt proto.txt
        show "$spec" player/link/vectors.txt vectors.txt
        show "$tokens" player/ui/tokens.txt tokens.txt
        show "$tokens" player/ui/tokens-v1.txt tokens-v1.txt
        ;;
esac

{
    echo "commit.spec $spec"
    echo "commit.tokens $tokens"
    for f in proto.txt vectors.txt tokens.txt tokens-v1.txt; do
        echo "$f $(sha256sum "$dst/$f" | cut -d' ' -f1)"
    done
} > "$dst/PIN.tmp"
mv "$dst/PIN.tmp" "$dst/PIN"
cat "$dst/PIN"
echo "pin.sh: now run ./gradlew :proto:gen"
