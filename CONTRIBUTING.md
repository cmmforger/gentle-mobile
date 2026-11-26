# Contributing to Gentle Mobile

Gentle Mobile is an Android app over a Rust core that drives workspaces, terminals and agents on your own machine.
This file is the contract for anyone working in this repository — human or agent. It is short on purpose: read it fully
before your first change.

## Where the work lives

The **epic is the source of truth**: **#6**. It holds the architecture, the acceptance criteria, and the ordered
checklist of child issues. If this file and the epic ever disagree, the epic wins.

Open work is whatever the epic lists. Do not invent scope: if the change you want is not in an issue, open one first (or
ask), so the reason is recorded.

## The designated order

Take the child issues in this sequence. Each issue states its own position and what it waits for.

| # | Issue | Waits for |
| --- | --- | --- |
| 1 | #9 Secure pairing and connectivity | nothing — start here |
| 2 | #10 Security hardening | nothing; two findings are manifest configuration |
| 3 | #7 Workspace and panel management | nothing; produces the contract #8 and #12 consume |
| 4 | #8 Multi-agent runtime integration | #7 |
| 5 | #12 Settings ownership model | #7 and #9 |
| 6 | #11 Desktop setup and onboarding | #9 |

The order is **designated, not enforced**. GitHub does not block anything. Respect the sequence because it is the one
that avoids rework; if you believe a later step must come first, say so in that issue and wait for an answer before
starting — do not silently reorder.

## Taking one issue

1. **Comment on the issue** that you are taking it. This is how collisions are avoided.
2. **Branch from `main`**, one branch per issue: `issue-9-pairing-hardening`. Never work directly on `main`.
3. **One reviewable pull request.** If the issue turns out larger than one sitting (say #7), split it into chained PRs
   against the same issue and explain the split in the description. A 2,000-line PR will not be reviewed.
4. **Reference the issue**: `Closes #9` in the last PR of that issue, `Refs #9` in the ones before it.
5. **Do not merge.** Merging is the maintainer's call.
6. **Do not close, retitle or relabel existing issues** unless the issue itself asks for it.

## Before you push

There is no CI in this repository. The PR will show *no checks*; the results you paste below are the evidence.

```bash
. scripts/android-env.sh
cargo test -p gentle-mobile-core --lib          # expect ~206 passing, 3 ignored
cd ui-android && . ../scripts/android-env.sh && ./gradlew :app:testDebugUnitTest
```

Four traps, all pre-existing and none of them yours:

- **`cargo test --workspace` currently fails.** `crates/herdr-client/tests/live.rs` imports
  `../../test-support/sandbox.rs`, a file this repository never contained. Do not invent the file to make it green
  unless the issue asks for it.
- **If you touch `core/`, run `scripts/build-core-android.sh` before the Gradle build.** Otherwise the APK packages the
  old `.so` and the app misbehaves in ways unit tests will not show.
- **`jniLibs/` and the committed UniFFI binding are generated.** `ui-android/.../core/gentle_mobile_core.kt` is output of
  the script above; never hand-edit it.
- The Gradle warning about `LocalLifecycleOwner` being deprecated is known and unrelated.

## Conventions

- **Code, commits, PR titles and PR descriptions are English.** The app's UI strings are Spanish
  (`res/values-es/strings.xml`) with English in `values/`; keep both in sync.
- **Conventional Commits**, imperative mood, lowercase after the colon:
  `fix(pair): report a clear error when 'ip' is missing`.
- **Behaviour and its tests ship in the same commit.** Never a commit with tests for a later one.
- **Docs travel with behaviour.** If you add or change a command, an option or a user-visible message, update the text
  that describes it in the same change.
- **Comment discipline:** the default is no comment. Never put issue, ticket or PR ids in code. If a line needs a
  comment to be understood, rename or restructure it first.
- `design/gentle-mobile/*.html` are the **UI contract**. If a screen changes, the board changes with it.
- `odd/tasks/gentle-mobile.md` is the planning document: specs `S<n>`, tasks `T<n>`, and an append-only Log `L<n>`.
  **Append** to the Log; never rewrite an existing entry.

## Security rules for this project

These are not preferences. A PR that violates one will be rejected regardless of how well it works.

- **Never commit a secret.** No private keys, no tokens, no `.env`, no credentials of any kind.
- **Never disable host-key verification.** Not behind a flag, not "temporarily", not for a local test. A changed host key
  is refused with a typed message.
- **Never add app-wide cleartext HTTP.** `usesCleartextTraffic` is scoped to the pairing flow, and stays that way.
- **Never widen what leaves the device.** The device private key lives in `filesDir` with mode 600 and must not be
  copied, logged, or included in a backup.
- **Never bind a service to `0.0.0.0`** beyond the single-use pairing listener and its TTL.

## Reporting a defect

Open an issue with: what you did, what you saw, what you expected, and the device or build where it happened. A screen
recording or a screenshot beats a paragraph. If it is a security problem, say so in the first line and do not include
exploit details in a public issue.
