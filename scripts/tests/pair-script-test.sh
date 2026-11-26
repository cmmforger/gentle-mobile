#!/usr/bin/env bash
# Pins the pairing helper's key handling, including what the rename gave up:
# a key written under the old marker is no longer listed or revoked.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/gentle-mobile-pair"
SANDBOX="$(mktemp -d)"
trap 'rm -f "$SANDBOX"/*; rmdir "$SANDBOX"' EXIT

KEY='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl'

# An authorized_keys the tool has never touched.
: > "$SANDBOX/empty"
out=$(AUTHORIZED_KEYS="$SANDBOX/empty" bash "$SCRIPT" --list)
[ "$out" = 'no phone keys yet' ] || { printf 'empty list said: %s\n' "$out" >&2; exit 1; }

# A key it adds is listed and then revoked, leaving the file empty again.
AUTHORIZED_KEYS="$SANDBOX/empty" bash "$SCRIPT" --add-key "$KEY phone" >/dev/null
AUTHORIZED_KEYS="$SANDBOX/empty" bash "$SCRIPT" --list | grep -q 'gentle-mobile-' \
    || { printf 'added key is not listed\n' >&2; exit 1; }
AUTHORIZED_KEYS="$SANDBOX/empty" bash "$SCRIPT" --revoke >/dev/null
[ ! -s "$SANDBOX/empty" ] || { printf 'revoke left bytes behind\n' >&2; exit 1; }

# A key carrying the pre-rename marker survives both --list and --revoke.
# This is the loss S36 accepted: the tool stopped matching the old marker.
printf '%s phone gentle-companion-20240101-000000\n' "$KEY" > "$SANDBOX/legacy"
out=$(AUTHORIZED_KEYS="$SANDBOX/legacy" bash "$SCRIPT" --list)
[ "$out" = 'no phone keys yet' ] || { printf 'legacy key listed: %s\n' "$out" >&2; exit 1; }
AUTHORIZED_KEYS="$SANDBOX/legacy" bash "$SCRIPT" --revoke >/dev/null
grep -q 'gentle-companion-' "$SANDBOX/legacy" \
    || { printf 'legacy key vanished — the documented loss changed\n' >&2; exit 1; }

# A key the tool did not add is never touched.
printf '%s other\n' "$KEY" > "$SANDBOX/foreign"
AUTHORIZED_KEYS="$SANDBOX/foreign" bash "$SCRIPT" --revoke >/dev/null
grep -q ' other$' "$SANDBOX/foreign" || { printf 'revoke ate a foreign key\n' >&2; exit 1; }

printf 'pair script: ok\n'
