# Gentle Companion — planning document

A native mobile client (Android first, iPhone prepared) that attaches over SSH/Tailscale to
Gentle Shell agents running inside Herdr TUI, and to Gentle Shell sessions started directly on
the host. Client only: the host keeps running the agents; the phone is a window and a remote
control. Status: decisions closed for planning (L1–L4); no code written yet.

## Specs

Verbatim quotes are the user's own words. Prose around them is planning, not a new requirement.

- **S1 — Native app, Android + iPhone.**
  "la intencion es llegar hacer el desarrollo tanto para android como para iphone";
  "me iria por la opcion 1. que es la nativa" (option 1 = shared Rust core + UniFFI + Compose/SwiftUI).

- **S2 — SSH or Tailscale transport with QR pairing.**
  "tu puedas conectarte mediante SSH o Tailscale (Como Moshi) Tambien con QR y facil integracion".

- **S3 — Two lanes: Herdr TUI and Gentle Desktop.**
  "la conexion deberia ser tanto para tui como gentle-desktop";
  "la app es para conectarse remotamente a gentle-shell que esta en herdr o bien directamente
  a gentle-shell como lo hace moshi".
  Lane A = Herdr TUI server over SSH. Lane B-i (B-i) = the app opens its own `gentle-shell`
  session over SSH; it does not attach to the desktop window's live session.

- **S15 — Continuity is the second step, not the first.**
  Decision: the app owns its own Gentle Shell session in v1; **v1.5 adds continuity** — list the host's
  sessions and resume one that is *closed*. Never two writers on one session file in v1.
  Rationale: concurrent attach to a session file is a consensus problem, and a lost conversation is
  unrecoverable trust. "Own session + resume closed" delivers most of the value at a fraction of the risk.

- **S16 — Concurrent mirroring of the live desktop session is deferred, not cancelled.**
  "v2 todavia no podemos tomar una decision porque todavia estan trabajando en el desarrollo de esta app,
  por lo que considero que deberia estar al tanto pero no es momento de desarrollarla."
  It stays visible in the plan as an upstream dependency (T20). No work on it in v1 or v1.5, and no UI in
  the app promises it until Gentle Desktop exposes an attach surface.

- **S17 — Client contract for the RPC lane (measured in T5).**
  `gentle-shell --mode rpc [--no-session | --session <path>]` speaks JSONL over plain stdio, no TTY needed.
  The client must: (a) keep reading stdout until the process exits — closing the read end early makes Pi
  crash on EPIPE; (b) close stdin for an orderly shutdown and treat stdout EOF as a normal end, not an error;
  (c) tolerate a torn trailing JSONL line, because `kill -9` mid-append can leave one; (d) answer every
  `extension_ui_request` or it blocks and eventually auto-resolves.

- **S18 — Pi provides no session lock, so the guard is ours.**
  Measured: two `--mode rpc` processes on the same session file do not error and do not corrupt the file;
  both append, both exit 0, and their in-memory views diverge silently. No lock files, no flock, and a
  `kill -9` leaves valid JSONL. Therefore the "never two writers" rule cannot be delegated to Pi: the app
  writes its own advisory lease (pid + heartbeat + device id) next to the session and refuses to open a
  session a live lease claims.

- **S4 — pi-companion is out of scope.**
  "no estamos usando pi-companion".

- **S5 — Moshi is the UX bar, not Herdr Mobile.**
  "como te menciono esto es como moshi, mas que herdr mobile".

- **S6 — Fewer gestures than Moshi.**
  "la idea es reducir los gestos visuales que tiene moshi".

- **S7 — Send images from the app.**
  "poder enviar imagenes ... desde la misma app que se conecte a herdr, talcomo lo hace moshi".

- **S8 — Audio is dictation turned into text.**
  "es dictado que se transforma en texto, ya que el TUI no es capaz de recibir audio.
  esto es igual a lo que tiene moshi".

- **S9 — Selectable themes, `cute` by default.**
  "los temas son para elegir, son los que vienen por defecto en el sistema, el por defcto es
  el cute, pero se puede elegir sexy y pueden haber muchos mas temas. esa sera la tonalidad
  de la app mobile"; also "mantener los colores de https://gentlemanprogramming.com/"
  and "que se puedan implementar temas como mustaccin sexy y mustaccin cute".
  Approved canonical format: Pi theme JSON (`Gentleman-Cute`, `Gentleman-Sexy`, `Gentle`), exported
  to Herdr TOML `[theme.custom]` for the in-app terminal.

- **S10 — No store accounts, so Android-first.**
  "no tengo cuentas app developer y play console" → Android APK signed and sideloaded;
  iOS target compiles but is not published until an Apple Developer account exists.

- **S11 — Notifications: local, no relay.**
  Approval "si" to: Android foreground service + local notification; iOS shows in-app banners only
  until an Apple account exists. No APNs relay, no third-party push service, no server in between.

- **S12 — License and code provenance.**
  Project: Apache-2.0. Rust core derived from Bellterm's core with attribution in `NOTICE`
  (`herdr-client` / `herdr-protocol` are vendored Herdr crates; their bincode field and variant
  order is a wire contract and must not change). UI is original work. Answer to "cual recomendas".

- **S13 — No code imported from `gentle-shell-gpui` in v1.**
  Approval "ok". That repo declares MIT in `Cargo.toml` but ships no `LICENSE` file and GitHub
  reports `license:null`. If lane B-ii (attach to the desktop's live session) is ever pursued,
  a `LICENSE` file is requested upstream first.

- **S14 — No unilateral decisions, no code before approval.**
  "no tomes una decision sin antes indicarme"; "no programes, veamos todo lo necesario para ver
  el plan, cualquier duda me dices".

## Decisions taken (evidence-backed)

| # | Decision | Evidence |
|---|---|---|
| D1 | Lane A transport is SSH exec of `herdr --session <name> remote-client-bridge`; socket API `protocol: 22`, `schema_version: 1`, 102 methods incl. `agent.prompt/read/list`, `events.subscribe`, `session.snapshot`, `client_shell.surface.set` | local `herdr 0.9.5` (`~/.local/share/herdr-mustaccin/bin/herdr`); bridge subcommand exists; Bellterm's `crates/herdr-client/src/ssh.rs` |
| D2 | Herdr sends rendered cell grids, not a byte stream; render them (cell frame → canvas), do not parse ANSI | Bellterm README "How it works" |
| D3 | Official Herdr mobile is not a reference target: it talks to `herdr-core` and its FAQ says it does not work with Herdr TUI | herdr.dev/mobile |
| D4 | Lane B-i talks to Pi RPC mode (`gentle-shell --mode rpc`, strict JSONL over the child's stdin/stdout) | `gentle-shell-gpui` `crates/pi-rpc/src/lib.rs` |
| D5 | Gentle Desktop exposes no remote API (loopback single-instance `OpenRequest` only, token in `instance.json`, 0600) | `gentle-shell-gpui` `crates/app/src/platform/instance.rs` |
| D6 | Desktop state channel `gentle-shell.desktop-rpc/v1` has 14 structured methods but rides the child's pipe | `crates/pi-rpc/src/desktop_rpc.rs` |
| D7 | Themes live in two formats today: Pi theme JSON (desktop) and Herdr TOML `[theme.custom]` | `themes/Gentleman-Cute.json`; `~/.config/herdr/config.toml` |
| D8 | Bellterm's Android app is proven; its iOS target exists as a public beta but was not confirmed in the source tree | bellterm.app + repo listing (to verify) |

## Proposed defaults (awaiting explicit confirmation)

Never stated by the user; listed so they can be accepted or changed. No task below starts from them
until confirmed.

- P1 Persistence bar = host-side panes survive + fast bounded reconnect + keepalive. Mosh/ET excluded from v1.
- P2 Pairing = one host-side helper (`gentle-companion pair`) registers the device public key and prints QR + short code.
- P3 Minimum versions: Android 10, iOS 17 (same floor as Bellterm and Moshi).
- P4 Security: per-device ed25519 key in Keystore/Keychain, mandatory host-key pinning, biometric gate, no telemetry.
- P5 Multi-host and Herdr saved machines land in v0.5, not in the MVP.
- P6 Dictation runs on-device (Speech / SpeechRecognizer) first; whisper.cpp only if quality is insufficient.
- P7 `herdr-core` lane (Herdr Desktop) is out of v1; the core still abstracts the endpoint so it can be added.

## Tasks

Route: `design` (gentle-design), `inline` (parent), `spike`, `worker` (delegated with an allowed-surface
handoff). Commit column stays `—` until implementation is authorized.

| ID | Task | Spec | Route | Commit |
|---|---|---|---|---|
| T1 | Sync design system from real tokens: Pi theme JSON × 3 themes + Herdr TOML + gentlemanprogramming palette → `design/system.md` | S9 | design | — |
| T2 | Artboards: Pair, Nodes, Inbox, Session, Compose, Theme picker, Settings (no gesture-only actions, 44pt targets, one-thumb zone) | S6 S9 | design | — |
| T3 | Spike: `russh` + `remote-client-bridge` + `events.subscribe` + `session.snapshot` on Android, protocol generation negotiation | D1 S2 | spike | — |
| T4 | Spike: cell frame → canvas (colors, wide chars, Nerd Fonts, cursor) | D2 S6 | spike | — |
| T5 | Spike: `gentle-shell --mode rpc` over SSH — JSONL handshake, dialog events, session resume semantics, and whether a second process on the same session file conflicts | D4 S3 | spike | — |
| T6 | Spike: Rust core → iOS via UniFFI compiles and links (no UI yet) | S1 S10 | spike | — |
| T7 | Repo layout + CI (workspace `core/`, `ui-android/`, `ui-ios/`), Android build, signing, `.gitignore`, `NOTICE` | S12 | inline | — |
| T8 | Transport layer: device keys, host-key pinning, jump hosts, keepalive, reconnect with bounded backoff | S2 P1 | worker | — |
| T9 | Pairing: host helper (pubkey + QR + code) and in-app scanner/manual code | S2 P2 | worker | — |
| T10 | Lane A read path: host list, workspaces/tabs, agent list + status, session snapshot, event stream | S3 D1 | worker | — |
| T11 | Lane A write path: prompt, keys, extra-keys row, focus/split/zoom guardrails | S3 S6 | worker | — |
| T12 | Terminal renderer in the app (cell frames, pinch-to-font-size, optional drag scroll, selection/copy) | D2 | worker | — |
| T13 | Compose sheet: text + image picker → upload to host → paste path → Enter, with previews and failure states | S7 | worker | — |
| T14 | Dictation → text into the prompt, on-device, with a visible listening state | S8 P6 | worker | — |
| T15 | Android notifications: foreground service + daemon "needs input"/"finished" → local notification, tap opens the agent | S11 | worker | — |
| T16 | Theme pack: `Gentleman-Cute` default, `Gentleman-Sexy`, `Gentle`, extra palettes, app tokens + terminal tokens, Herdr TOML export | S9 D7 | worker | — |
| T17 | Lane B-i: `gentle-shell --mode rpc` client over SSH — transcript, prompt, dialogs as sheets | S3 D4 | worker | — |
| T18 | Settings/security screen: keys, known hosts, biometrics, theme choice, about | S9 P4 | worker | — |
| T19 | v1 packaging: signed APK release, what's-new page, sideload instructions, iOS target kept building | S10 | inline | — |
| T20 | **Deferred (S16):** optional upstream ask for B-ii, a minimal `--attach-stdio` bridge for `gentle-shell-gpui`, after a `LICENSE` file exists. Not scheduled. | S3 S13 S16 | inline | — |
| T21 | Continuity (v1.5): list the host's Gentle Shell sessions and resume a closed one, with a guard that refuses a session another process holds | S15 | worker | — |

## Phases and gates

| Phase | Deliverable | Gate |
|---|---|---|
| 0 | This document + design brief approved | User sign-off |
| 1 | T1, T2 (design system + artboards) | Visual review by the user |
| 2 | T3, T4, T5, T6 (four spikes) | If T3 fails to handshake, re-plan the stack before any UI work |
| 3 | T7 (repo, CI, Android build, iOS core build) | Green build on both |
| 4 | T8–T11, T12 (transport, pairing, lane A read/write, terminal) | Demo on a real device against this host |
| 5 | T13–T16 (compose, dictation, notifications, themes) | Visual + audio verification on device |
| 6 | T17–T19 (lane B-i, settings, packaging) | Feature-complete v1, signed APK |

## Design plan (`gentle-design`)

Single source of truth is `design/system.md`, generated from the real token files — never invented.
Artboards live in `design/gentle-companion/`. Required screens: Pair (QR + code + host command),
Nodes (hosts, groups, state), Inbox (needs you / done / working / idle, in that order), Session
(one-thumb header, extra-keys, tab map), Compose (text + images + dictation, a single send action),
Theme picker (sexy / cute / gentle with a live terminal preview), Settings/security.
Design rule from S6: no action reachable only by gesture; explicit buttons, 44pt targets, reduced-motion
respected.

## Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Herdr protocol is generation-scoped and bincode order is a wire contract; the local binary is a modified (mustaccin) build | Negotiate generation, pin a minimum version, refuse with a readable message instead of failing silently (Bellterm's `compat.rs` pattern) |
| R2 | `herdr remote-client-bridge` framing may differ from the vendored `herdr-protocol` crate | T3 proves it before any UI work |
| R3 | Lane B-i: Pi session resume and file-lock semantics are unverified; two processes may conflict on the same session | T5; if blocking, lane B-i is scoped to "session owned by the phone" only |
| R4 | iOS: no account, and SSH cannot stay alive in background | Android-only release; iOS built but unpublished; in-app banners only |
| R5 | Bellterm's iOS target unconfirmed (D8) | T6 proves the core links with UniFFI before iOS UI work |
| R6 | Apache-2.0 attribution and the vendored wire contract are easy to break | `NOTICE`, no fields reordered, review checklist item |
| R7 | Scope creep toward Mosh/ET or a relay server | Explicitly excluded (P1, S11) until the user asks |
| R8 | `gentle-shell-gpui` is 11 days old, single maintainer, no LICENSE file | Nothing imported (S13); B-ii is optional and gated on upstream |

## Log

**L1 (original request, verbatim):**
"https://github.com/ygrip/pi-companion Hola que tal, necesito plantear la arquitectura, definir lenguaje
y ver alternativas de desarrollo para crear una app mobile como herdr mobile o mejor dicho moshi.
un usuario quiere implementar lo siguiente:
Hola a todos, he visto que recientemente ah surgido Gentle Shell Desktop y es una nueva rama que esta
naciendo de Gentle-Ai y Gentle-Shell. Asi que pense: «¿Ahora como vamos a contrar a Gentle Shell desde
el movil con la aplicacion?»
Esta pregunta me llevo a esta propuesta. La cual se centra en la creacion de una app movil la cual tu
puedas conectarte mediante SSH o Tailscale (Como Moshi) Tambien con QR y facil integracion y puedas
controlar tus agentes nativos de Gentle Shell Desktop y Gentle Shell y tambien de Gentle Ai. Tambien es
una gran oportunidad para las personas que programan desde telefono que conozco alguno poder usar Gentle
Shell nativo desde sus dispositivos Moviles
Queria saber cual es su opinion y si les interesaria. Quedo Atento ;)
en base a lo enterior encuentro como buena base gentle-companion que te acabo de enviar repositorio como
para poder dar solucion. La diferencia esta en que este arranca como runtime, la intencion es llegar
hacer el desarrollo tanto para android como para iphone, y mantener los colores de
https://gentlemanprogramming.com/ o mejor dicho que se puedan implementar temas como mustaccin sexy y
mustaccin cute (temas que ya maneja gentle-shell y herdr en este equipo).
necesito ver planificacion del proyecto, no tomes una decision sin antes indicarme, realizaremos tambien
un planificacion de visualizacion con gentle-design.
la idea es reducir los gestos visuales que tiene moshi, poder enviar imagenes, enviar audios, desde la
misma app que se conecte a herdr, talcomo lo hace moshi.
no programes, veamos todo lo necesario para ver el plan, cualquier duda me dices"

**L2 (decisions):**
"1. seria la A pero tambien se esta creando gentle-desktop que es pertenciente a
https://github.com/ismaelosuna7824/gentle-shell-gpui por lo que la conexion deberia ser tanto para tui
como gentle-desktop
2. me iria por la opcion 1. que es la nativa.
3. como te menciono esto es como moshi, mas que herdr mobile
4. es dictado que se transforma en texto, ya que el TUI no es capaz de recibir audio. esto es igual a lo
que tiene moshi
5. los temas son para elegir, son los que vienen por defecto en el sistema, el por defcto es el cute, pero
se puede elegir sexy y pueden haber muchos mas temas. esa sera la tonalidad de la app mobile
alguna otra pregunta o decison que tenga que tomar?"

**L3 (decisions):**
"2. no tengo cuentas app developer y play console.
3. cual recomiendas.
4. no estamos usando pi-companion, la app es para conectarse remotamente a gentle-shell que esta en herdr
o bien directamente a gentle-shell como lo hace moshi.
7. si"

**L4 (decisions):**
"1. B-i por default como dices
2. si
3. ok"

**Evidence appended during planning (read-only, no code):**
- Lane A verified locally: `herdr 0.9.5` mustaccin build, socket API protocol 22, 102 methods,
  `remote-client-bridge` present.
- Official Herdr mobile targets `herdr-core` and does not work with Herdr TUI (herdr.dev/mobile FAQ).
- `gentle-shell-gpui` (Gentle Desktop): GPUI app wrapping `pi --mode rpc` JSONL, local PTY
  (`alacritty_terminal`), no daemon, no remote API, zero relation to Herdr, Pi theme JSON with
  `Gentleman-Cute` default, MIT in `Cargo.toml` but no `LICENSE` file.
- Bellterm (Apache-2.0): one Rust core (`herdr-client`, `herdr-protocol`, `native/` JNI) + Kotlin/Compose,
  `russh` in-process SSH, `libghostty-vt` for cell frames, jump hosts, Keystore, SSH config import.
- Evern (Apache-2.0): shared Rust core + UniFFI + Compose/SwiftUI, SSH+Mosh, agent output parsing, voice.
- pi-companion: Rust daemon + embedded SvelteKit PWA, HTTPS/WSS through a tunnel — out of scope (S4).

**L5 (T1 — design system sync, no code):**
Captured the real theme tokens into `design/system.md` and `design/system.html` (system board, 1440 px,
three-theme preview). Sources read: `gentle-pi/themes/Gentleman-Cute.json`, `Gentleman-Sexy.json`,
`Gentle.json` (installed package, Apache-2.0); `~/.config/herdr/config.toml` `[theme.custom]`;
`~/.local/share/herdr-mustaccin/compatible-cute.toml` and `compatible-sexy.toml`. Colour tokens are
captured; geometry (spacing 4/8/12/16/24/32, radius 8/12/16/pill, type scale 11/13/15/18/22/28,
44 pt targets) is listed as a **proposal** awaiting acceptance. The brand palette of
gentlemanprogramming.com was **not** captured (no token file inspected) and stays a pending source
for `Gentleman-Brand` in T16. Artboards for `design/gentle-companion/` follow in T2.

**L6 (T2 — artboards, no code):** `design/gentle-companion/` created with 7 artboards at 390×844 plus
`design.json` (`feature` pointing at this document): `01-pair`, `02-nodes`, `03-inbox`, `04-session`,
`05-compose`, `06-themes`, `07-settings`. All are self-contained HTML with no external URLs. Colour
audit against `design/system.md` passes for every value. The canvas reported 5 quality findings, all
fixed inline before reporting: `node-main` touch target raised to 44 px (02), the dim `#563040`
timestamp/cursor raised to `#A78E9B` (04), unlabelled `textarea` given `aria-label` (05), and Gentle's
`muted #5C6170` (3.1 : 1) replaced with `secondary #A3B5D6` in the mini terminal (06). The Gentle
contrast rule is now recorded in `design/system.md`. Canvas re-check: 0 findings, 7/7 accessible.
`design/system.md` also gained a **Tool surfaces** table (`toolPendingBg`, `toolSuccessBg`,
`toolErrorBg`, `infoBg`, diff colours), which was missing when the first artboard used `#261019`.
No artboard has been handed off for implementation, so none is marked as chosen.

**L7 (design iteration from canvas comments, no code):** The user reviewed the boards and left seven
comments, all in the same direction — too many cards, too many colours, and elements repeating what the
control already says. Every comment was applied and resolved: `kocol9x1` (composer needs room to type,
WhatsApp-shaped), `wrodwaof` (the `Ctrl+A = herdr compose` legend does not belong in the key row; bindings
move to the session menu), `r9ttm4bq` (drop `Send` / `NEEDS YOU` / `review-payments` from each theme
card: the card is the control), `504x15if` and `ysu6usat` (explain host-key pinning in plain language,
then cut it to one line: `Refuses a host whose SSH key changed.`), `ue6799ac` and `dq0vuv0u` (composer
geometry: 8 px corners, controls outside the field in the order clip · camera · accent circle, circle is
the microphone by default and the send triangle when there is text).
Two rule sets were codified in `design/system.md` so this does not depend on repetition: **Redundancy rule**
and **Refinement rules for every artboard**. T2b then brought `01-pair`, `02-nodes`, `05-compose` and
`07-settings` to the same level as the calibrated `03`, `04` and `06`: one surface, hairline rows, at most
one accent plus one error per screen, state as a word. Canvas re-check: 0 findings, 7/7 accessible.
Fixed during verification: `.btn-ghost` in `02-nodes` had no CSS rule, so *Pair a new host with QR*
rendered as a default white button and competed with the accent.
**Open finding, needs a decision:** the QR in `01-pair` is drawn as light modules on a dark plate.
Inverted QR codes fail on a noticeable share of phone cameras; the standard fix is a light plate behind
the code. It is a functional exception to the one-surface rule, so it awaits the user's call.

**L8 (appearance rule, no code):** Comment `jf295k0f` stated that every view must respect the background of
the selected appearance. `07-settings.html` became the reference: three `html[data-theme]` token sets taken
from `design/system.md` plus a quiet `Theme · Cute · Sexy · Gentle` switch outside the phone frame. The same
mechanism was applied to `01-pair`, `02-nodes`, `03-inbox`, `04-session` and `05-compose`. `06-themes` stays
out on purpose: it is the theme picker, so a global switch there would duplicate its own cards.
Default stays Gentleman-Cute with no `data-theme` on `<html>`. Gentle maps `--textSecondary` to `#A3B5D6`,
not to `muted #5C6170`, to keep AA contrast. Terminal palettes in `04`/`05` do not follow the app theme:
they are Herdr's `[theme.custom]` set, and the Gentle terminal export is still T16 work.
Verified by measurement, not by assumption: with `data-theme="gentle"` the inbox frame computes
`background: rgb(6,8,15)` and `color: rgb(243,246,249)`. Canvas re-check after the change: 0 findings, 7/7
accessible.

**L9 (Cute/Sexy separation, no code):** Comment `rurqd9vx` was applied as an **app palette extension**
(declared in `design/system.md`, not captured tokens): page `#28121E` Cute / `#2E0F1F` Sexy / `#161D2E`
Gentle, panels and fields one step lighter, and a separate `--terminal` token so the Herdr palette is never
repainted by the chrome. Verified by measurement: page `rgb(40,18,30)`, field `rgb(62,28,49)`, terminal
`rgb(16,10,15)`; hierarchy `bg < surface < surfaceRaised` holds; canvas quality stayed at 0 with 7/7
accessible. Comment `q3nw8s2m` then corrected the scope: **only Cute** was supposed to change, and Sexy was
already right. Sexy was reverted to its captured palette in all six switcher boards (measured page
`rgb(6,4,7)`), and Cute was left undecided: `08-cute-theme-options.html` now shows three candidates (A plum
page with sunk black panels, B plum page with lifted panels — the rejected one, C black page with a plum
field) for the user to pick before any further sweep. `08` is a decision board, not a screen.

**L10 (lane B-i scope, user decision):** "vamos como dices, con el orden de prioridad. v2 todavia no podemos
tomar una decision porque todavia estan trabajando en el desarrollo de esta app, por lo que considero que
deberia estar al tanto pero no es momento de desarrollarla."
Order fixed: **v1** own session → **v1.5** list + resume a closed session → **v2** mirror the live desktop
session, which stays in the plan as an upstream dependency and gets no work now. Recorded as S15 and S16,
with T21 added for continuity and T20 marked deferred. The gate for T21 is the T5 spike: resume support and a
lock that is released after a crash.

**L11 (phase 2 closed, phase 3 started — code):** Spikes closed: T3a (`7f43918`) endpoint handshake from Rust;
T3b (`1ed3330`) bridge over SSH with russh; T4 (`2b79711`) cell surfaces render and are measured — a full
frame is 28,998 B against a 130 B median delta, so the phone streams deltas and takes a full frame only as a
baseline; T5 (`7d5096c`) proves `gentle-shell --mode rpc --session <file>` resumes with the same `sessionId`,
that there is **no lock**, that two writers diverge silently while the file stays valid, and that `kill -9`
leaves a possibly torn last line. S17 and S18 record the resulting client contract and the decision that the
lease is ours to write. T6 (iOS) is blocked: it needs macOS and Xcode.
Environment: rootless JDK/SDK/NDK/rustup (`10c1305`), fixed in `9b5472f`, `701dc61` and `113653c` after the
env script bit us with `"$VAR:-default"` under `set -u`, a missing PATH for adb, and AVDs written to
`~/.config/.android` while the emulator only looks at `ANDROID_AVD_HOME`.
T7 (`e6f7be5`) builds the Compose scaffold and produces a 22 MB debug APK themed from `design/system.md`.
**Verified on a Pixel 6 / Android 14 emulator**, not just built: installed, launched, screenshot taken, and the
pixels measured — background `#1A1218`, accent `#FF76C3`. The screenshot exposed a real defect the build could
never show: the mono meta wrapped and left a stray bullet, fixed in `b4dd41a` (`maxLines = 1` + ellipsis).
Next: T8a, the transport core (pinning, keepalive, reconnect) as a tested crate.

