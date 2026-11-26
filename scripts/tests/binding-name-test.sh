#!/usr/bin/env bash
# Pins the invariant behind the stable binding name: exactly one generated
# Kotlin file, committed as Core.kt. Every case below is a real outcome the
# build script can hit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT/scripts/normalize-binding-name.sh"
SANDBOX="$(mktemp -d)"
trap 'rm -rf -- "$SANDBOX"' EXIT

# 1. Any generated name becomes Core.kt.
mkdir -p "$SANDBOX/any-name"
: > "$SANDBOX/any-name/gentle_mobile_core.kt"
bash "$HELPER" "$SANDBOX/any-name"
[ -f "$SANDBOX/any-name/Core.kt" ] || { printf 'generated file was not renamed\n' >&2; exit 1; }
[ ! -e "$SANDBOX/any-name/gentle_mobile_core.kt" ] || { printf 'the old name survived\n' >&2; exit 1; }

# 2. A generator that already picked Core.kt is a success, not a same-file mv.
mkdir -p "$SANDBOX/already"
: > "$SANDBOX/already/Core.kt"
bash "$HELPER" "$SANDBOX/already" || { printf 'an already-correct name failed the script\n' >&2; exit 1; }
[ -f "$SANDBOX/already/Core.kt" ] || { printf 'the file disappeared\n' >&2; exit 1; }

# 3. More than one file is refused, and nothing is renamed on the way out.
mkdir -p "$SANDBOX/two-files"
: > "$SANDBOX/two-files/one.kt"
: > "$SANDBOX/two-files/two.kt"
if bash "$HELPER" "$SANDBOX/two-files" 2>/dev/null; then
    printf 'two generated files should be refused\n' >&2; exit 1
fi
[ -f "$SANDBOX/two-files/one.kt" ] && [ -f "$SANDBOX/two-files/two.kt" ] \
    || { printf 'the refused run still moved files\n' >&2; exit 1; }

# 4. No output at all is refused.
mkdir -p "$SANDBOX/empty"
if bash "$HELPER" "$SANDBOX/empty" 2>/dev/null; then
    printf 'an empty directory should be refused\n' >&2; exit 1
fi

# 5. A path that is not a directory is refused.
if bash "$HELPER" "$SANDBOX/nope" 2>/dev/null; then
    printf 'a missing directory should be refused\n' >&2; exit 1
fi

printf 'binding name: ok\n'
