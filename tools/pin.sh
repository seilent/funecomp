#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
dst=$root/third_party/funeos
funeos=${FUNEOS:-$HOME/pers/funeos}

usage() {
    echo "usage: tools/pin.sh <funeos rev> | tools/pin.sh --spec <funeos rev> | tools/pin.sh --tokens <funeos rev> | tools/pin.sh draft" >&2
    exit 2
}

old() { sed -n "s/^commit\.$1 //p" "$dst/PIN"; }

spec=$(old spec)
tokens=$(old tokens)

show() { git -C "$funeos" show "$1:$2" > "$dst/$3"; }

lrc=$root/state/src/test/resources/lrc/SOURCE

lrc_check() {
    local bad=0 path want got
    while read -r path want; do
        [ "$path" = commit ] && continue
        if ! git -C "$funeos" cat-file -e "$1:$path" 2>/dev/null; then
            echo "pin.sh: $path is missing at $1, the Lrc port source ($(sed -n 's/^commit //p' "$lrc")) has it" >&2
            bad=1
            continue
        fi
        got=$(git -C "$funeos" show "$1:$path" | sha256sum | cut -d' ' -f1)
        if [ "$got" != "$want" ]; then
            echo "pin.sh: $path at $1 differs from the Lrc port source ($(sed -n 's/^commit //p' "$lrc"))" >&2
            bad=1
        fi
    done < "$lrc"
    if [ $bad -ne 0 ]; then
        echo "pin.sh: review state/src/main/kotlin/net/seilent/funecomp/state/Lrc.kt against it, then update $lrc" >&2
        exit 1
    fi
}

case "${1:-}" in
    draft)
        [ $# -eq 1 ] || usage
        spec=draft
        ;;
    --spec)
        [ $# -eq 2 ] || usage
        spec=$(git -C "$funeos" rev-parse --verify "$2^{commit}")
        lrc_check "$spec"
        show "$spec" player/link/proto.txt proto.txt
        show "$spec" player/link/vectors.txt vectors.txt
        ;;
    --tokens)
        [ $# -eq 2 ] || usage
        tokens=$(git -C "$funeos" rev-parse --verify "$2^{commit}")
        show "$tokens" player/ui/tokens.txt tokens.txt
        show "$tokens" player/ui/tokens-v1.txt tokens-v1.txt
        show "$tokens" player/ui/tokens-r1.txt tokens-r1.txt
        ;;
    "")
        usage
        ;;
    *)
        [ $# -eq 1 ] || usage
        spec=$(git -C "$funeos" rev-parse --verify "$1^{commit}")
        lrc_check "$spec"
        tokens=$spec
        show "$spec" player/link/proto.txt proto.txt
        show "$spec" player/link/vectors.txt vectors.txt
        show "$tokens" player/ui/tokens.txt tokens.txt
        show "$tokens" player/ui/tokens-v1.txt tokens-v1.txt
        show "$tokens" player/ui/tokens-r1.txt tokens-r1.txt
        ;;
esac

{
    echo "commit.spec $spec"
    echo "commit.tokens $tokens"
    for f in proto.txt vectors.txt tokens.txt tokens-v1.txt tokens-r1.txt; do
        echo "$f $(sha256sum "$dst/$f" | cut -d' ' -f1)"
    done
} > "$dst/PIN.tmp"
mv "$dst/PIN.tmp" "$dst/PIN"
cat "$dst/PIN"
echo "pin.sh: now run ./gradlew :proto:gen"
