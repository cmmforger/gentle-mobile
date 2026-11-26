#!/usr/bin/env bash
# Ensure a generated-bindings directory holds exactly one Kotlin file, named
# Core.kt.
#
# uniffi names its output after the crate, so a crate rename turns the committed
# binding into a multi-thousand-line file move that a reviewer cannot see past.
# The committed name stays put instead; the symbols inside still follow the
# crate, which is what the .so must match.
#
# Usage: normalize-binding-name.sh <directory>
set -euo pipefail

dir="${1:?usage: normalize-binding-name.sh <directory>}"
# Normalize the caller's spelling once: a trailing slash would make the
# same-file comparison below see two different strings for one file, and the
# skip would turn into mv's same-file failure.
dir="${dir%"${dir##*[!/]}"}"
[ -n "$dir" ] || dir="/"

[ -d "$dir" ] || { printf 'not a directory: %s\n' "$dir" >&2; exit 1; }

# Read the listing through a command whose status survives, and keep find's
# diagnostics out of it: a failing find must report itself, and a find that ever
# printed while succeeding must not contribute a line that looks like a filename.
# 2>&1 would make those two streams indistinguishable.
errfile=$(mktemp)
trap 'rm -f -- "$errfile"' EXIT
if ! listing=$(find "$dir" -maxdepth 1 -name '*.kt' -print 2>"$errfile"); then
    printf 'cannot list %s: %s\n' "$dir" "$(cat "$errfile")" >&2
    exit 1
fi

generated=()
while IFS= read -r entry; do
    if [ -n "$entry" ]; then
        generated+=("$entry")
    fi
done <<< "$listing"

case "${#generated[@]}" in
    0) printf 'no Kotlin file was generated in %s\n' "$dir" >&2; exit 1 ;;
    1) ;;
    *) printf 'expected exactly one generated Kotlin file in %s, found %s: %s\n' \
           "$dir" "${#generated[@]}" "${generated[*]}" >&2
       exit 1 ;;
esac

target="$dir/Core.kt"
# A generator that already picked the stable name is a success, not an error.
[ "${generated[0]}" = "$target" ] || mv -- "${generated[0]}" "$target"

[ -f "$target" ] || { printf 'the binding is not named Core.kt in %s\n' "$dir" >&2; exit 1; }
