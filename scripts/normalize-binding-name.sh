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
[ -d "$dir" ] || { printf 'not a directory: %s\n' "$dir" >&2; exit 1; }

mapfile -t generated < <(find "$dir" -maxdepth 1 -name '*.kt' -print)
case "${#generated[@]}" in
    0) printf 'no Kotlin file was generated in %s\n' "$dir" >&2; exit 1 ;;
    1) ;;
    *) printf 'expected exactly one generated Kotlin file in %s, found %s: %s\n' \
           "$dir" "${#generated[@]}" "${generated[*]}" >&2
       exit 1 ;;
esac

target="$dir/Core.kt"
# mv onto itself fails, and a generator that already picked the stable name is
# a success, not an error.
[ "${generated[0]}" = "$target" ] || mv -- "${generated[0]}" "$target"

[ -f "$target" ] || { printf 'the binding is not named Core.kt in %s\n' "$dir" >&2; exit 1; }
