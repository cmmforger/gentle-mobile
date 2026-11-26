#!/usr/bin/env bash
# S36: the project answers to gentle-mobile and nothing else.
#
# Two documented exceptions:
#   - odd/tasks/gentle-mobile.md quotes the human verbatim, and that request
#     names the old name; rewriting the quote would falsify the record.
#   - design/ ships its own rename in a separate change; drop the exclusion
#     once that lands.
#   - scripts/tests/pair-script-test.sh must name the old marker to pin the
#     loss S36 accepted; a test that cannot spell the string cannot test it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
PATTERN='gentlecompanion|gentle-companion|gentle_companion|gentle companion'

paths=$(git ls-tree -r --name-only HEAD | grep -iE "$PATTERN" | grep -v '^design/' || true)
if [ -n "$paths" ]; then
    printf 'the old name is still in a path:\n%s\n' "$paths" >&2
    exit 1
fi

hits=$(git grep -inE "$PATTERN" -- . ':!odd/tasks/gentle-mobile.md' ':!scripts/tests/pair-script-test.sh' ':!scripts/tests/name-hygiene-test.sh' || true)
if [ -n "$hits" ]; then
    printf 'the old name is still in tracked content:\n%s\n' "$hits" >&2
    exit 1
fi

printf 'name hygiene: ok\n'
