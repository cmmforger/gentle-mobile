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

- **S19 — Two lanes on the host, not one (measured in T11).**
  The client-shell bridge (`herdr remote-client-bridge`) does **not** serve `pane.read` or `agent.prompt`;
  asking for them is refused, and that refusal is now unit-tested. Read and prompt are operations of the
  daemon's **API socket** (protocol 22, one JSON line per request, **one request per connection**), which the
  phone reaches with SSH `direct-streamlocal` channels under the same pins, keys and keepalive.
  Consequences: the transport keeps **both** channels — bridge for surfaces/events, streamlocal for discrete
  requests — and every request opens a fresh channel. Envelope shapes (`agent_prompted`, `result.read`
  nesting, `pane_not_found`/`agent_not_found`) were measured live, never assumed.

- **S20 — Name and identity: `gentle-mobile`.**
  "1. el nombre se llama gentle-mobile como te dije". The Android app is **Gentle Mobile**:
  applicationId and namespace `com.gentlemanprogramming.gentlemobile`, label `Gentle Mobile`. "Gentle
  Companion" was a default taken from the first message and never confirmed; the repository name was always
  the decision. The icon and splash come from the human's own logo asset, never from a generated mark.
  Also stated in this decision: the Pair screen must match `design/gentle-companion/01-pair.html`, not a
  developer form — the design is the contract for what gets built.

- **S21 — Design fidelity checklist (what "as designed" means, board by board).**
  The human's rule: nothing gets installed until the app matches the boards we made. Tracked as:
  | Board | State |
  |---|---|
  | `01-pair` | done — identity, host command, `gc://` link, camera scanner |
  | splash (in `01`) | done — mark, wordmark, accent line, hides when the app is ready |
  | `07-settings` | done — every unsupported row carries its reason on screen |
  | `06-themes` | done — three palettes, immediate application, persisted choice |
  | `03-inbox` | done — grouped `needs you` → `done` → `working` → `idle`, word and count in the group label |
  | `02-nodes` | done — the connected host with group and state before the agent list |
  | `04-session` | done — terminal, composer and the extra-key row sending real events |
  | `05-compose` | missing — attachments and dictation are later features (T13/T14) |
  An element the core cannot support yet is rendered in its honest unavailable state, never faked.

- **S22 — Pairing must be one step, because the user is an average user.**
  "prueba que debe ser con facil instalacion o mejor dicho conexion remota que haga a la app … ya que para
  un usuario promedio, es mas dificil conectarse con tantos pasos."
  The current flow (copy the key, run a command with the key, paste a link) is too many steps. Target flow:
  **the machine prints a QR, the phone scans it, done.**
  Design: a host-side helper (same script, new subcommand) starts a **single-use** pairing listener with a
  short TTL, prints a QR containing the address plus a one-time token, and accepts exactly one phone key,
  appending it to `authorized_keys` and exiting. No password crosses the app, no key is copied by hand, no
  link is pasted. The existing `--add-key` paste path stays as the manual fallback, and `--revoke` keeps
  working for both.
  Bind: loopback forbidden, tailnet or LAN only, one attempt per token, token burned on use, and the helper
  prints what it wrote so the user can see it.
  This replaces the "many steps" complaint as the last blocker before the human tests the app.

- **S23 — No release APK until the human approves the build.**
  "la ultima fase sera generar el apk y cuando se apruebe recien ahi se puede hacer un apk release, pero antes no."
  Until that approval, verification uses **debug** builds installed on the emulator or a test device. Do not
  produce, copy or hand over a release APK, and do not touch the keystore, in any intermediate step. The
  release build is the last phase and it happens once, after the human says the app is right.
  **Amended:** "con eso crearemos un apk en Downloads como lo hicimos antes y hare una prueba" — once T17a and
  T17b close, the release APK is produced for that test; the human's test is the approval step, not a
  substitute for it. No release builds before T17 closes.

- **S24 — Corrections from the first real test on the phone (verbatim list).**
  1. Settings needs a **language selector** (Spanish/English/French/Italian and the usual set).
  2. Settings needs **sections/tabs** so it stops being one long list (Security, About, …).
  3. "GENTLE SHELL SESSIONS" is not understood, and entering it shows `listing sessions…` forever with no explanation.
  4. Hosts need **names** (100.96.18.28 → "Desktop") and **more than one host** (Desktop + Notebook).
  5. From the phone one should be able to **open a new session in a repo** when none is running, not only resume existing ones.
  6. `IDLE · 2` reads badly; use a human word, and drop the redundant repetition.
  7. Chrome noise: "Gentle Mobile" and "Settings" in the header are unnecessary, and `Hosts` with the back chevron explains itself.
  8. Show the **project name**, not the full path, and stop repeating `pi` beside it.
  9. **Dictation does not work** on the phone.
  Rounds: the labelling/visual items (3, 6, 7, 8) and the Settings structure (2) are immediate; naming hosts and
  multi-host (4), starting a session (5), languages (1) and dictation (9) need their own decisions and work.
  **Decisions taken:** (1) only **Spanish and English**, **English by default**, switchable in the app;
  (4) host names are **written by the user** (any label they want, so they know which machine is which);
  (5) start a session from the **list of repositories that already have history**;
  (9) on the phone **the permission dialog never appeared** and the message read like a `send` hint — the fix
  must ask for `RECORD_AUDIO` before creating the recogniser and say "permission was refused" in plain words,
  localised.

- **S25 — What we take from `Reaan06/Gentle-Movil`, and what we do not.**
  Reviewed the repository: it is the **pi-extension lineage** (Expo + Rust core, QR with host/port/user/password,
  SSH tunnel to the Pi bridge on 43721/43722, plus an on-device agent for a "local mode"). Our transport is the
  herdr daemon socket and the client-shell bridge, so the backend and almost all code are not transferable.
  What we adopt:
  1. **A real CLI name with subcommands**: `gentle-mobile pair | status | doctor`, installed on PATH, instead of
     running a script by path. `doctor` checks sshd, the host key, the LAN/tailnet address and whether herdr is
     running; `status` reports whether a pairing listener is up and what it trusted.
  2. **Connection form in the app** like Termius/Moshi: host, port, user and key, as the primary manual path.
  3. Idea worth keeping from their QR: carry the **host key fingerprint** in the link so the phone can show it
     before connecting instead of only after the handshake.
  4. **Typed QR parse errors, answered locally**: their `qr-parse` separates "not our code", "broken code" and
     "newer protocol", with one message per case, and validates **before** any native call so a foreign QR never
     stalls the camera preview. Our parser must do the same, and the link gains both `fp=` (host fingerprint,
     shown before connecting) and a version field.
  5. **A pure, framework-free validation module** for the Connection form (host as IPv4/IPv6/hostname, port
     1–65535, user without spaces or `@`), with one exact message per rejection, tested without a UI runtime.
     Their copy is rioplatense Spanish, which is what our `values-es` should read like.
  What we do **not** adopt: password inside the QR (a one-time token is strictly better), the 43721 tunnel
  (wrong backend for us), and the on-device agent local mode (a different product).
  Pair screen consequence: the primary surface becomes **Connection form + Scan QR**, and the phone key and the
  `--add-key` command move into a collapsed **Manual setup** block, since with one-scan pairing they are a
  fallback, not the default.

- **S26 — After the second phone test: the app must show the herdr workspace, not only the agents.**
  1. **Connect error without Tailscale** only said "could not …". Verbatim from the phone: "Could not reach the pairing at
     100.96.18.28:40681 — run gentle-mobile pair on the host and start again. failed to connect to /100.96.18.28 from
     /192.168.18.97 port 54068 after 10000 ms". Two defects: the advice names the wrong cause (it assumes a stale pairing when
     the **socket never left the phone**), and the raw Android networking text is shown as user copy. Required: classify the
     failure — **timeout / network unreachable** → "Can't reach 100.96.18.28 — check that both devices are on the same network
     or that Tailscale is connected"; **connection refused** → "Reached the host but nothing is listening on that port — run
     gentle-mobile pair again"; auth or token problems keep their current wording. The technical detail goes to the log, or at
     most a small secondary line, never as the sentence the user reads first. Same classification for plain SSH connect.
  2. **Workspaces**: the app has no view of the herdr workspace list the human uses on the desk (repos with their branch and
     status). It must list workspaces (name, branch, ahead/behind, state, agents) and let the human move between them.
  3. **Add a project**: a `+` that starts a workspace in a chosen path on the host, so a repo without history can be opened.
  4. **Session header noise**: `Agents` and `Refresh` must be icons, and the cwd under the agent name must be the project name only.
  5. **Rename a saved host** from the hosts list, not only when creating it: several machines with names the human chooses.

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

- **S27 — Third phone test: the terminal must fit the phone, and the session chrome must disappear.**
  1. **Fit instead of scroll**: the whole `80xN` frame must be visible on the phone — scale the cells down until the full
     frame fits the card width (and the visible height), like Moshi. Today 10 sp with horizontal scroll hides most of the
     information. Nothing may be cropped; no sideways scrolling to see the rest of a line.
  2. **Wider card, smaller margins**: the terminal card loses side padding so the frame uses the phone's width.
  3. **No measurement line**: `80x24 · 1065 drawn cells · 10 colours · wide "…"` goes away entirely.
  4. **Quiet header buttons**: the back and refresh glyphs are small and muted, not two big pink arrows. Touch targets stay
     44 dp even when the glyph is 18–20 dp.
  5. **Never the word `pi`**: the session title is the project name the human recognises, never the agent kind.
  6. **No explanatory line**: `live · what the agent shows right now` goes away; the space belongs to the terminal.
  7. **The workspace's tabs**: the card's top strip shows the tabs of the current workspace so the human can switch between
     them, instead of a numbered bar that does nothing.
  8. **The other projects**: the workspaces surface lists the projects the human is *not* in too, and offers the `+` to
     create one, so switching or starting is one tap. The word `Idle` is removed.

- **S28 — Fourth phone test: fill the phone, real tabs, and one place per thing.**
  1. **Tabs do nothing.** Pressing a tab must switch the visible pane; today the chips are inert because the core has no
     `tab.focus` binding. Wire it.
  2. **Header glyphs.** Back and refresh are grey and the arrow shape does not read: both in the theme's accent colour, and a
     chevron for back.
  3. **The frame must fill the phone and be readable.** The card leaves a large empty area below and the text is small; on a
     big desktop screen it is small on the phone, and shrinking the desktop does not help. The surface must be derived from the
     card: the largest cell size that still fills the card's height, so text is as big as possible and no empty area remains.
  4. **One place per thing.** Workspaces belong inside each host (a second host brings its own), and the connected session's
     card must not list projects again — that duplicate is what the rename row was opening.
  5. **Workspace detail.** Its title collides with the status bar clock, so it needs the same back-button header as the rest;
     and tapping a project must go straight into its session, not through an intermediate screen that repeats its name.
- **S29 — The desktop composer is redundant on the phone, and the phone must stop painting it.**
  The frame includes pi's own input box (`type, or / for commands` with its `waiting for input` bar). The phone writes through
  its own composer, so that box is chrome the human cannot use. Decision: **hide the composer rows only**, keep the metrics
  lines below them (`TPS … tok/s`, `N files · +x −y`), and never hide a row when the marker is not clearly the composer.
- **S30 — Fifth phone test: spacing, the wire width again, the wrong pane, and attachment honesty.**
  1. **Too much top padding**: the gap above the card/tabs is dead space; reduce it.
  2. **Lines still cut** on the phone while the desktop shows them whole: the frame paints fewer columns than the pane holds
     (the reflow is not applied on a warm rebind — the residual the T24 worker reported). Lines must read exactly as they read
     on the desktop.
  3. **Writing goes to the wrong pane**: after switching to tab 2, a prompt sent from the phone landed in tab 1. The prompt must
     go to the pane the human is looking at. Also remove the `Sent — the host confirmed the submission.` note entirely.
  4. **The card must re-fit when the keyboard opens**: today it keeps the old size, so the terminal shrinks into a strip.
  5. **Attachments**: some images render inline and some come out as an empty block with only the `[Image#N] png · …` label. A
     format or size the phone cannot decode must say so instead of drawing nothing. And the upload error must read `Upload failed`
     — short, no sentence cut with an ellipsis.
- **S31a — The control phrase.** The human's own acceptance test, verbatim:
  `LA-FRASE-DE-CONTROL-CRUZA-VARIAS-LINEAS-EN-UN-PANE-DE-38-COLUMNAS-Y-CADA-PALABRA-DEBE-VERSE-COMPLETA-SIN-UN-SOLO-CARACTER-CORTADO-EN-EL-BORDE-DERECHO-DE-LA-GRILLA`
  It is one long token with hyphens and no spaces, so the pane must break **inside** the word: if the width is
  wrong it clips there and nowhere else is credible. Every width fix is verified with this exact string.

- **S31 — The phone reshapes the terminal to itself, the way Moshi does.** Corrected on the fifth test by the human, who pointed out that
  Moshi does NOT leave the desktop screen whole and scaled: it **resizes the terminal** so it fits the phone and renders 1:1 — that is why
  it looks sharp. Evidence checked in the daemon: herdr has no scaling of its own; a client that claims its surface makes the tab reflow
  its PTY to the client's size. So the phone **claims** the surface and reflows the pane to the phone grid (the "adapted" mode of T23–T25),
  renders at native size, and never scales the picture. The work is making that reflow **reliable**: it must survive a bind, a rebind, a
  tab switch and a keyboard change, and never paint a frame with the previous width. The desktop reflows while the phone is connected
  and returns to its own width on exit — accepted by the human.

- **S31b — Width *and* height, as complete as Moshi.** "arrancamos con lo de ancho y largo que debe ser lo mas completo posible como
  moshi". The grid the phone claims is derived in **both** axes from the card it actually paints: number of columns from the card's
  width and the cell size, number of rows from the card's height. The frame must fill the card in both directions — no empty band at
  the bottom, no letterboxing — and it must be the *complete* terminal the pane runs, not a window onto it. Moshi is the reference
  for what "complete" means: the whole terminal, phone-shaped, rendered 1:1.

- **S31c — When the host will not give the geometry, the phone says so instead of cropping silently.** Chosen by the human
  after the T26 measurement (L22): the daemon confirms the phone's claim but keeps the pane's PTY at the desktop width while
  the desktop client is attached, so the phone receives frames of its own grid whose content was cut. The phone must **detect**
  that state (the pane's visible lines are wider than the grid it was granted), **say it in one line** on the session screen,
  and offer the explicit fallback of showing the **whole pane** scaled down — the old mirror mode as a deliberate choice, never
  as the default. When the desktop lets go, the session returns to 1:1 with no typing. Any notice that changes the card's
  height must not change the derived grid, or a failed claim re-fires itself forever.

- **S32 — Borrowed from `tuan3w/herdr-mobile`, not its code.** The human weighed replacing this app with that
  Flutter client and chose to keep ours: same stack, same Apache-2.0 licence, and take the two ideas it does better.
  Its own README states the same limit we measured — "herdr can't resize a pane to the phone's width over its API",
  answered there with a client-side **wrap mode** and pinch-zoom.
  1. **Wrap mode (S32a).** When the granted grid crops wider pane content, the phone must be able to **re-wrap the
     pane's text to its own columns** at a readable size, instead of only cropping or shrinking the whole pane. This
     is the honest answer to the daemon limit and the closest thing to "like Moshi" that upstream allows. The sharp
     1:1 grid stays the default when nothing is cropped; the whole-pane view stays available as the exact-layout
     fallback.
  2. **Approval board (S32b).** A board grouped by what each agent needs (needs you, working, done, idle), where a
     waiting agent shows the command it wants to run and its options become one-tap answers, and a risky answer is
     held rather than tapped. One "needs you" count everywhere.


- **S33 — The look of `tuan3w/herdr-mobile`, taken as direction, not as theme replacement.** The human's words: *"incluso
  las imágenes que presenta de la app son súper buenas, el cómo se visualiza, a eso quiero llegar"*. What those screens
  actually show, checked on the shipped captures: light airy surfaces with soft shadows and large titles; status chips with
  counts; an **approval block** that is content, not a dialog — the question, the exact command in mono, and full-width
  option rows with a warning glyph on the risky ones; and agent activity **drawn** rather than dumped (tool calls with
  tappable paths, diffs with coloured lines and numbers, test results). The light theme arrives as a **fourth choice**
  ("Paper"), `cute` stays the default, and the air/typography pass plus the approval block apply to every theme. The

  **The centre of this is the terminal inside the phone, not the palette** — the human corrected the emphasis: *"no es solo el
  theme si no el cómo se visualiza, cómo se muestra la terminal dentro del teléfono"*. So S33 covers (a) the **fitting**: the
  same pane in its three honest states — sharp 1:1 when it fits, cropped with the one-line truth today, and **wrapped** to the
  phone's columns (T30) — with nothing ever clipped; and (b) the **dressing**: no chrome, the key row, the rounded composer,
  and agent activity drawn rather than dumped (tool calls with tappable paths, diffs with coloured line numbers, test
  results). The reference app's pane is read-only text; ours stays a live grid the human can type into, so the wrapped state
  must still read as a terminal, not as a log.
  structured activity view is the biggest piece and is built on the lane this app already has (`gentle-shell --mode rpc`
  gives structured transcript entries and dialogs), not on a new one.


- **S34 — The hosts screen is a clean list; each host keeps its own things inside.** The human, testing on the phone: the connected
  host's row was carrying *Workspaces* and *Session history* inline, which made the hosts screen heavy. Those two cards move **inside
  the host's own screen** — the one you reach by tapping the host — so the list stays a list (name, `user@host:port`, state, Rename)
  and more hosts fit. Hosts are already renameable and that does not change.


- **S35 — The composer stays visible; the crop goes (supersedes S29).** The human: *"se puede dejar la terminal con el waiting for input, como
  lo tenía antes, ya que termux lo muestra y no hay problema, no es necesario ocultarlo. Quizás es uno de los problemas que también ha dado
  en la app móvil"*. So the pane is shown **complete**, composer bar included: no detection by the bar's label, no prompt-row heuristic, no
  extra rows requested, no blank padding. The whole `ComposerCrop` machinery and its tests are deleted rather than disabled, and the session
  screen renders the frame exactly as the host drew it.

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
| T22 | Third phone test corrections: fit the frame to the phone, wider card, no measurement or `live` lines, quiet header glyphs, project name only, the workspace's tabs, and the full project list with `+` | S27 | worker | — |
| T23 | Fourth phone test: working tabs via `tab.focus`, accent header glyphs, a surface derived from the card so the frame fills the phone, workspaces nested under their host without duplication, and a workspace screen that enters the session directly | S28 | worker | — |
| T24 | Hide the desktop composer rows from the phone's frame: detect the composer, drop those rows, shift the rest up, keep the metrics, and take the freed rows as extra conversation (request that many more rows) | S29 | worker | — |
| T25 | Fifth phone test: reduce the top padding, fix the pane/painted width mismatch on a warm rebind, route prompts to the visible pane, drop the Sent note, re-fit the card when the keyboard opens, and make attachment failures honest and short | S30 | worker | — |
| T26 | Robust reflow (survives bind, rebind, tab switch and keyboard change, never painting a stale width), the grid derived in both axes so the frame fills the card, write to the split the human is looking at, and a `+` that picks a disk (all the machine's disks) and browses to a folder to create the workspace | S31 S31a S31b | worker | — |
| T27 | Disk and directory browsing for `+`: over SFTP, list the machine's real disks (mount points with their labels) and a directory's folders, so a workspace can be created by tapping through disks and folders instead of typing an absolute path | S30 S31 | worker | core `56b2401`, UI pending |
| T28 | **Deferred, after T26 is green:** evaluate the `kitknox/herdr` fork as the host for a terminal control stream (raw output + snapshots + `tab.set_geometry` claim semantics) so the phone renders locally with native scrollback. Gated on its license, on the cost of a second modified herdr on a host that already runs `0.9.5-mustaccin`, and on an explicit human decision to install it. Until then the app stays on upstream `remote-client-bridge` surfaces. | S31 S31b | inline | — |
| T30 | Wrap mode: re-wrap the pane's text to the phone's columns when the granted grid crops it, readable and colour-preserving where the read carries it, with the sharp 1:1 grid as the default and the whole pane kept as the exact-layout fallback | S32a | worker | — |
| T32 | Design pass: artboards for the light "Paper" theme as a fourth choice, the airy type and spacing rules, and the approval block as content (question, exact command in mono, full-width option rows, risky answers marked) | S33 | design | — |
| T33 | Implement the Paper theme as a fourth option with its tokens, and the air/typography pass across the existing screens | S33 | worker | — |
| T35 | Move Workspaces and Session history out of the hosts list into each host's own screen, leaving the list to hosts only, Rename and Add | S34 | worker | — |
| T37 | PTY lane in the core: open an SSH session with `request_pty(cols, rows)`, send `window_change` on resize, and expose one bidirectional byte stream — the mechanism a real terminal needs, proven with tests before any UI | S33 S34 | worker | — |

  **Images decide the shape of this**: herdr can draw images inside a pane, and this app renders them because it draws herdr's
  surface — a PTY terminal (Termux engine) draws text and colour only, so inline images do not travel there. Therefore T38 adds the
  terminal as a **view chosen per pane**, never as a replacement: agent panes keep the rich herdr surface (images, diffs, approvals,
  agent state) with wrap for reading, and the terminal tab carries real terminal work with `herdr` inside. Sending images from the
  phone (SFTP upload plus the `[attached image: path]` line) is a separate path and stays.
| T38 | Terminal on screen: render that PTY stream with the Termux `terminal-emulator` engine (Apache-2.0, credited) inside our session screen, with our theme's terminal palette, running `herdr` inside it | S33 | worker | — |
| T39 | Session screen: back to the edge, panels and refresh as header icons with the PANELES row deleted, key chips that never truncate (`↵` instead of a cut `E`), momentary keys with a pressed state, and no line cut at the card's bottom | S35 | worker | — |
| T40 | Bundle three OFL typefaces (Space Grotesk, IBM Plex Sans, Aldrich) with a real picker in Settings that applies app-wide, persists, and is credited in NOTICE | S33 | worker | — |
| T41 | No false disconnect: while the app is in the background it never declares the link dead, and on return it probes liveness before painting any state; the terminal view is never disturbed by the check | S31 | worker | — |
| T42 | The real terminal: our PTY (T37) drawn by the Termux engine full-bleed with the app's palette, a bare shell in the workspace folder, the IME resizing it, and the key row | S33 | worker | — |
| T43 | The launcher: one header button opening the tools the host actually has (multiplexer and agents apart), each starting in the workspace folder at the measured size, last used first | S33 | worker | — |
| T34 | Approval block inside the session (board `09` P2/P4): the same question, command and held answers while looking at the agent's pane, instead of only from the board | S32b | worker | — |
| T31 | Approval board: agents grouped by what they need, the exact command before an approval, one-tap answers with a re-read before sending, risky answers held, one needs-you count everywhere | S32b | worker | — |
| T29 | Honest crop: detect that the granted grid crops wider pane content, say it in one line, and offer the whole pane scaled as an explicit fallback; anchor every notice so it cannot change the derived grid and re-fire the claim | S31c | worker | — |
| T19b | **Auto-update of the APK (deferred):** the app checks a URL we control, downloads the signed APK and updates itself. The cheap 80% of OTA without changing stack; requires the release certificate to stay stable, a version endpoint and an explicit user confirmation. | S10 | inline | — |

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

**L12 (T11 — read and prompt verified end to end):** The app opens a real agent, shows its visible pane text
and sends a prompt. Line match against `herdr pane read w1F:p1 --source visible`: **42/43 lines identical**,
the single difference being a running timer that advanced between captures. A throwaway `pi` agent
(`t11-verify`, scratch cwd) received `echo t11-ok` from the emulator; `diff` between what the app sent and what
the host read was clean. The agent then failed on **host credentials** (`OAuth refresh failed for
openai-codex … invalid_refresh_token (401)`), which is a machine issue, not an app one; the throwaway agent and
pane were closed and herdr is back to 21 panes / 2 agents. The `agent_not_found` refusal for non-agent panes is
surfaced inline, verbatim. Recorded as S19: read and prompt live on the daemon API socket via SSH
`direct-streamlocal`, not on the client-shell bridge.

**L13 (name, icon and design fidelity):** The human rejected three things at once and was right on all
three: the product was called "Gentle Companion" — a default I took from L1 and never confirmed, while the
repository has always been `gentle-mobile`; the launcher icon was a generated placeholder instead of their
own logo, which existed only inside the design artboard; and the first screen was a developer form
(host/port/user/session) instead of the Pair screen the design specifies. They also clarified what the design's
two options mean: **Herdr TUI vs Gentle Shell, both over SSH** — Tailscale is the network, not a second
transport (their `ts status` shows the same host reachable as a tailnet address, and Termius connects that
way). The device-key error they hit on the phone ("No client key at …/client_ed25519") was exactly the gap
this task closes: the app now generates its own key. Recorded as S20; the alignment work is in flight.

**L14 (design fidelity reached, APK delivered):** Every board is implemented except the `05-compose`
features (attachments and dictation, which are T13/T14, not design work). The last gap closed here was the
extra-key row: the daemon's `pane.send_keys` accepts `esc/tab/enter/arrows/backspace/space/f*` and composes
modifiers with `+`, so the core gained `pane_send_keys` over the same streamlocal request path and UniFFI
exposes `sendKeys`; the row sends real events with one-shot sticky Ctrl/Alt, verified on a throwaway pane
(`clea` + Tab completing to `clear`, Ctrl+C clearing the line). Bindings now generate directly into the app's
package instead of being rewritten by hand, and the stale pre-rename source tree is gone. 56 core tests.
Release APK 57,953,496 bytes signed and copied to `~/Downloads/gentle-mobile-0.1.0.apk`; the emulator's test key
was revoked from `authorized_keys` with `gentle-companion-pair --revoke`, leaving other keys untouched.

**L15 (auto-update noted, stack unchanged):** A reader proposed Expo + Rust core + EAS Update with a host daemon and an on-device local mode. Checked against what exists: their security model (phone-generated ed25519, one-time token, host fingerprint against MITM) is already implemented and verified here, so it validates S22 rather than replacing it. Recorded decision: **stack unchanged** — React Native would still need the Rust module for SSH, adds a JS hop to the most latency-sensitive screen, and does not unblock iOS, which needs macOS and Xcode regardless. Two ideas taken: the single-use token (already S22) and **auto-update of the APK**, logged as T19b, the cheap part of OTA without re-platforming. The host daemon and the on-device agent are product changes, not implementation choices, and stay out of scope.

**L16 (S22 done — pairing is one scan):** `gentle-mobile-pair` now runs a single-use listener bound to the
LAN or tailnet address (loopback refused), TTL 180 s, ephemeral port, and accepts exactly one phone key before
exiting. The QR/link carries `gc://<host>:<sshPort>?user=…&pair=<host>:<port>&t=<token>`, never a private key.
Verified two ways: hand-run `curl` proved the happy path (200, key written and listed) and every refusal —
reuse 409, wrong token 401, malformed key 400, expiry 403 — and the app pasted the same link, posted its key,
pinned the host fingerprint and reached the real agent list. 42 JVM tests green; release APK signed with
`CN=Gentle Mobile`. Accepted risk to revisit: `usesCleartextTraffic="true"` is app-wide because the pairing POST
is plain HTTP to a host unknown in advance; the token is single-use, short-lived and reachable only on the local
network, and the flag should be narrowed once pairing can use HTTPS.

**L17 (handoff — dictation fixed, work paused for the day):** The dictation path is corrected: the mic tap runs a real
`RequestPermission` launcher **before** any recogniser is constructed, which is why the dialog never appeared on
the phone — the recogniser was being built without the grant and its raw error was shown instead. Now: refused,
permanently refused (with a settings action) and no speech service each have their own sentence, and a result
message clears itself after four seconds, so nothing sits above the composer. The idle explanatory paragraph is
deleted from both languages, and the fixture geometry moved to logcat behind `fixture_debug`.
State: 150+ JVM tests green, build green, commits up to `460b1cd`. Verified so far by screenshot: the system
permission dialog appears. **Not yet verified after this fix:** the denied / permanently-denied / no-speech
screens, and a real transcript (needs a phone with a microphone).
Next session order: multi-host with the Name field already stored (S24-4) → start a session from repositories with
history (S24-5) → the agent row showing only the project name (S24-8) → cache the sessions index so 200 sessions do
not take two minutes → then the release APK, which the human releases only on approval (S23).

**L18 (T27 first, then width and height like Moshi):** Between L17 and now the app went through the third to sixth phone
reviews, all closed in commits `292c471`…`09d27eb`: the frame fills the card and is derived from it, `tab.focus` is real,
workspaces nest under their host, loads are branch-aware, network failures are classified, sessions carry a lease, and the
desktop composer rows stop being painted while its metrics survive. T27 (disks and folders for `+`) landed as core-only in an
isolated worktree (`129fde7`, 172 core tests) and is now cherry-picked onto `main` as `56b2401`. The human then set the order,
verbatim: "dejemos T27 cableado primero y arrancamos con lo de ancho y largo que debe ser lo mas completo posible como moshi".
Recorded as **S31b**: the grid is derived in both axes from the painted card, the frame fills it in both directions and shows
the complete terminal, and Moshi is the reference for "complete". Sequence: T27 wiring (UniFFI + UI), then T26 — the reflow
contradiction (PTY reports 184×41 while the phone paints 57 columns; hypothesis: claim+resize at connect works, a warm resize
kills the frame stream), the 38–48 column target, and the control phrase in the four moments: open, reopen, tab switch,
keyboard.

**L19 (the reflow defect is in our client, and the map says exactly where):** A read-only pass over the claim path found the
contradiction is reproducible from the code alone, no daemon needed. Ranked by evidence strength:

1. **A released pane is never re-claimed.** `MainActivity.kt:2958-2971` releases the surface on back but does not clear
   `askedCols/askedRows`; those are only cleared per *new connection* (`resetBaseline`, `598-599`, called at `1724`). Reopening the same
   agent reuses its `SessionUi` with the old size, `PanePresentation.reflow` returns null (`PanePresentation.kt:39-46`) and no activation or
   resize is ever sent again. That is the 184×41-forever state: the host went back to the desktop geometry and nothing claims it back.
2. **Activation is unconditional and "already asked" outlives a release.** Every reflow sends `setSurfaceActive(true)` again
   (`PanePresentation.kt:38-47`, `MainActivity.kt:3819`), even when the surface is already active, and `PanePresentationTest.kt:111`
   bakes the reopen hole in as expected behaviour.
3. **Bookkeeping records "queued", not "confirmed".** `resizeSurface` and `setSurfaceActive` are enqueued without waiting
   (`crates/herdr-client/src/handle.rs:243-249` documents that the matching response *should* be awaited; `core/src/connection.rs:574-586`
   discards the request id). One lost activation poisons the session and the UI only logs it (`MainActivity.kt:3812-3817`).
4. **Refusals are invisible.** `core/src/connection.rs:681-684` swallows `CommandRejected` and `Response` in a `_ => {}` arm, so a host
   that does not support surface interest looks identical to a host that accepted it.
5. **Two calls, two threads, no ordering.** The reflow sends activation and resize as separate calls on a 2-thread pool
   (`MainActivity.kt:358`, `3819-3820`), so two effect firings (keyboard open then close) can land the older size last.
6. **A geometry change is connection-fatal.** `crates/herdr-client/src/session.rs:575-589` refuses a frame whose revision does not
   advance, and `crates/herdr-protocol/src/surface_delta.rs:145-153` refuses a delta whose geometry changed; either error propagates out of
   `run_connection` (`session.rs:365` → `connect.rs:167` `Disconnected`) and the UI reports the host closed. The baseline is never replaced
   after a resize, and no fixture pins what arrives after one. This is the strongest candidate for "a warm resize kills the stream" and it
   also explains a stale width surviving until the connection dies.

The grid arithmetic today: `CardSurface.kt:84-118` derives **both** axes from the card (`cols = usableW/cellW`, `rows = usableH/cellH`, with
`MIN_COLS=56`, `MAX_COLS=96`, font clamped to 10–28 sp), so height is already phone-derived; but the ask is `rows + 4`
(`ComposerCrop.requestRows`, `MainActivity.kt:3797`) and the crop pads blank rows at the top (`ComposerCrop.kt:169-186`), so blank card
height is spent on padding. Painted geometry comes from the **frame**, not the card (`MainActivity.kt:3731-3732`), and nothing compares
`cols` with `askedCols`: on a cold connect the claim can only happen *after* the first hello frame, which is still the 110×40 desktop size
(`DESKTOP_SURFACE`, `375`), so the first thing painted is a scaled desktop frame — exactly what S31 forbids. Every width fix is verified
with S31a.


**L20 (rootshell is the reference, not the codebase):** The human pointed at
`https://github.com/kitknox/rootshell` and asked whether we should apply "más o menos lo que tiene". Read the repo and its
`docs/herdr-control-mode.md`. Decision, confirmed by the human: **adopt the model on upstream herdr now, evaluate the fork
later** (T28). Findings worth keeping:

- rootshell is Swift + Metal + libghostty on Apple platforms (MIT, 844 stars, one main author, ~5.5k files). **Not portable**:
  libghostty has no Android renderer (Zig + Metal, macOS/Linux-GTK targets), and almost every subsystem leans on Apple
  frameworks. Porting it is rewriting this app to gain features nobody asked for.
- Its herdr document solves *our* defect list by design: **geometry ownership** (`tab.set_geometry` with `claim:false` for
  routine updates, one *claiming* request per activation, so a delayed resize cannot undo a newer handoff), **activation that
  re-claims on return and ends when the app is backgrounded**, **layout changes that hold output until the native surfaces use
  the new grid and recover superseded layouts through snapshots instead of failing**, and a host-side extra `SIGWINCH` at
  250 ms for programs that missed the resize. Our client does the opposite on every one of those points (L19).
- The `claim`/`set_geometry`/raw-stream half lives in the **experimental `kitknox/herdr` fork**, not upstream. Against upstream,
  rootshell falls back to the same server-rendered endpoint surfaces we already use. So the model transfers; the code does not.
- Open before T28: the fork's license (not yet checked), the risk of a second modified herdr on a host already running
  `0.9.5-mustaccin`, and the fact that their document states **mosh cannot carry this control mode** — which is what our P1
  excluded and what our rotation-drops-the-connection pain would want.

**L21 (T27 closed on the phone-shaped flow):** The interrupted worker's work survived the power cut intact (9 files,
+1306/−370) and passed 174 core and 346 JVM tests; the app received a host over SSH to the real machine and walked the
browser end to end: disks (`System` btrfs, `D-SSD`, `WD-Black` ext4), folders, a `git` badge on every real repository
(`gs-lab`, `projects`, `bellterm`, `ew1429-rebuild`, all twelve in `personal/projects`), symlinks rendered with their target
and never followed (`bin → usr/bin`, `lib → lib64 → usr/lib`), an honest `No folders here`, and a workspace created through
the daemon (`t27-workspace`, closed again with `herdr workspace close` — the session is back to its eight workspaces).
Walking it, not reading it, surfaced two defects the tests could not: **back from a nested folder jumped straight to the disk
list**, and **the jump changed the title and the path without re-reading the listing**, so the previous folder's rows stayed on
screen (the load was wired to the tap handlers only). Both fixed: back climbs to the parent, stops at a disk's mount point or
the root, and re-reads the folder it lands in (348 JVM tests). Commits `9cac0ea`, `da2df63`.
Machine note: `sshd` was down after the power cut and is **disabled** at boot — the human started it for this verification and
it stops again afterwards. The emulator's key was registered with `gentle-mobile-pair --add-key` (marker
`gentle-mobile-20261120-115353`), left in place as before.

**L22 (T26 in — the client obeys, the daemon arbitrates):** The whole geometry lifecycle is now the rootshell model, one
RED test per rule: a released pane re-claims on return, one claiming activation per session with routine size-only updates,
`set_surface_active` **awaits the daemon's answer** (`AckRouter`, 10 s) and the UI shows a refusal instead of logging it,
`Response`/`CommandRejected` reach the caller instead of the old `_ => {}`, activation and resize run on one flight so wire
order is decision order, a superseded or re-activated surface **replaces the baseline instead of killing the connection**
(boot mismatch stays fatal; wire order untouched), and no frame whose dims differ from the asked grid is ever painted. The
grid now comes from the card in both axes with `MIN_COLS=38`/`MAX_COLS=48`, `requestRows`'s `+4` is gone and the composer
crop blanks rows in place: live measure **card 974×1475 → grid 38×26, cell 25.2×55.4 px, fill 0.98×0.98, font 42 px, painted
1:1**. 179 core (+1 ignored live probe), 272 vendored, 353 JVM.

**The blocker, measured not guessed:** with the human's desktop TUI attached, the daemon **confirms** the phone's claim
(`SurfaceAck::Confirmed`) and renders 38-column frames, but never reflows the pane's PTY — `herdr pane read` stays 184 cols,
and S31a clips (`LA-FRASE-DE-CONTROL-CRUZA-VARIAS-LINE…` in `t26-m1-open.png`). In an isolated daemon session the same wire
path reflows the pane to 38 (probe test `t26_claim_reflow_probe`). After a phone release the daemon also stops answering
re-activations. Upstream has **no geometry takeover**: `pane.resize` is a split-divider amount, `client_shell.surface.set
{active}` is the only presentation control, and nothing claims ownership. So this is daemon arbitration between co-attached
clients, not a client defect — the human's call: accept "the phone claims geometry only while it is the only client", or take
T28's fork semantics (`tab.set_geometry` with `claim`). Open client-side defect to anchor regardless: a failed claim prints a
line that changes the card height, which changes the derived grid, which re-fires the claim every 10 s (seq 38x24↔38x26).

**L23 (incident, owned):** while probing the live host the worker briefly opened the human's `.gentle-shell` session (one back,
~40 s, their agent was mid-turn) and the `ERP-WEB-FRONT` session, backing out immediately. No typing and no resizing into
their panes; the release restored the desktop geometry. Recorded because a phone client that claims geometry can disturb a
live desktop session, and that is exactly the risk the blocker above is about.

**L24 (S31c, the human's call):** Faced with the daemon arbitration from L22, the human chose **detect and tell the truth, no
fork**: when the granted grid crops wider pane content the phone says so in one line and offers the whole pane scaled as an
explicit fallback, never as the default; when the desktop lets go, the session returns to 1:1 with no typing. T28 stays
deferred. This is T29.

**L25 (T29 closed — the phone stops lying about what it shows):** S31c is implemented and verified. Once per confirmed claim the
phone measures the pane's widest visible line in cells (wide characters twice) and compares it with the grid it was granted; when
the pane is wider it says so in one line and offers the whole pane scaled — the session asks the host for the pane's own width,
fits the full grid into the card and letterboxes it. The four sentences ship (EN cut "The desktop is using this pane, so long
lines arrive cut." / EN whole "… — showing it whole." / ES cut "El escritorio usa este panel: las líneas llegan cortadas." / ES
whole "El escritorio usa este panel: se muestra entero."), each pinned by a length rule so none ellipsizes, and each true for its
state. Every notice now lives in an always-reserved slot, so the derived grid no longer depends on whether one is showing: the
`38x24↔38x26` claim oscillation is gone (flat 38×23 → whole 184×23 → flat 38×23 with the grid constant). Number seen on screen:
whole pane at font 8 px, grid 883×243 px, control phrase complete in one line, `t29b-5-whole-en.png`; the ES cut sentence whole in
`t29b-1-notice-zoom.png`. 372 JVM + 179 core tests. Commits `06edaea` (T26), `2ab79e6` (T29). Also fixed there: HEAD carried
**stale Kotlin bindings** (`resizeSurface` with two arguments against a core asking four, so no resize could carry cell
dimensions); regenerated from the core.
The daemon fact stands and is the honest limit of "like Moshi" on upstream: while the human's desktop TUI is attached, every
pane's PTY stays at the TUI width — even a workspace the TUI is not showing — so the phone can be sharp and cropped, or complete
and small, but not reflowed and large. That needs the desktop detached, or T28.

**L26 (the fork question, answered: no fork, take the ideas):** The human proposed replacing this app with
`tuan3w/herdr-mobile` (Flutter, Android, ~37 stars, one author, v0.1.67, **GPL-3.0-only**), connecting it to their own
herdr, with our QR/SSH pairing and the Gentleman Programming themes. Checked before answering: that app drives herdr over
SSH with a persistent multiplexed channel that runs a **python3 script on the host** (falling back to
`herdr remote-api-bridge`, then `socat`), pins host keys, keeps keys in the platform keychain, and generates an Ed25519 key
on the phone with a one-line `authorized_keys` command — the same security model we already ship. It adds an approval
board and ACP chat, and its own README records the limitation this repo measured today: *"herdr can't resize a pane to the
phone's width over its API, so wide panes are pinch-zoomed or re-flowed by the app's wrap mode"*. Its `pi` support is
declared unverified ("starts but has not completed a turn yet"), which matters here because pi is what the human runs.
Decision: **keep our app** (Kotlin/Compose + Rust, Apache-2.0, 372 JVM + 179 core tests, QR pairing, themes, dictation,
lease, workspace creation) and **take the two ideas it does better** — the wrap mode (S32a) and the approval board
(S32b). Rejected for now: forking it (GPL-3.0 would relicense this project and force source on any distribution, and
Flutter would discard every fix and test here).

**L27 (light as a fourth choice, designed before coded):** The human wants the app to *look* like `tuan3w/herdr-mobile`'s
captures. Reviewed the shipped images: the look is light and airy with soft shadows and large type, status chips with counts,
an approval block that reads as content (question, exact command in mono, full-width answers, risky ones marked) and agent
activity drawn rather than dumped (tool calls, diffs, test results). Decision taken with the human: the light theme arrives
as a **fourth** choice ("Paper"), `cute` stays the default, and the air and approval rules apply to every theme. Design first
(T32), then implementation (T33), because the human should see it on the canvas before any screen changes.

**L28 (the emphasis: the terminal in the phone, not the theme):** The human clarified S33 — the goal is not a recolour but
*how the terminal is displayed inside the phone*, which they connected to the wrap work. Recorded in S33: the fitting (three
honest states, nothing clipped) and the dressing (no chrome, key row, composer, activity drawn rather than dumped). The design
brief was corrected mid-flight: a new board `11-terminal-in-the-phone.html` carries the three states, and the approval board
also shows a tool call with a tappable path, a diff and a test result. Not a read-only terminal: the wrapped state must still
look typeable, because ours is a live grid and theirs is text.

**L29 (the wrap wording, approved):** The human approved the copy the design proposed for the wrapped state: EN "Wrapped to
this phone's width — nothing is cut." / ES "Ajustado al ancho de este teléfono — no se corta nada." It lives in the same
reserved slot the crop notice uses, replaces it rather than stacking, and is pinned by the same length rule so it cannot
ellipsize. With this, T32 is accepted as designed: Paper as the fourth theme, the air rules with their numbers, the approval
block as content, and the three terminal states in `11-terminal-in-the-phone.html`. Next in order: T33 (Paper tokens and the
air pass, after T30 releases the same files), then T31 (the approval board).

**L30 (test agents: the host has no `pi` login, and that is environment):** The human stated it plainly: `pi` here has no
login, so a pi agent fails with a connection, OAuth or quota message. That is the machine, never this app — the same failure
was already seen in L12 and again inside a verification pane in `t26-m1-open.png`, and both times it cost time. From now on
every verification drives text through a **plain shell pane** (`echo`/`printf`) of the worker's own, never through an agent
that needs a provider. Note the distinction this repo already recorded: `gentle-shell` authenticates against
`~/.gentle-shell/agent/`, while bare `pi` reads `~/.pi/agent/`, which is stale here. Consequence for T31: the approval board's
real check needs an agent that actually asks for permission; until one is logged in, the board is verified against a shell
pane that prints a prompt and its command, and that limitation is reported instead of claimed as a real-agent proof.

**L31 (two design corrections from the canvas):** `07-settings.html` lost its **Connection** section (the `Herdr TUI /
Gentle Shell — Start a shell session` choice): there is nothing to choose, the app talks to herdr. In its place the human
asked for the settings shape of a reference app — Appearance, Language, Typeface, Terminal font. Applied with the values this
app really has (Follow the phone + a pinned theme out of Cute/Paper/Sexy/Gentle; English/Español with "the terminal is never
translated"; the platform UI face; the bundled JetBrainsMono Nerd Font Mono), deliberately **without** drawing font pickers the
app cannot back. `04-session.html` had its send triangle replaced by the **Iconoir** solid `send` paper-plane (MIT, credited in
the file) inside the same 44 dp accent circle. Both are board changes: T33 must carry them into the app (the drawable
`ic_send` is still a plain triangle) and the settings screen must lose the Connection choice.

**L32 (wrap closed, and a correction to what we believed about the daemon):** T30 is in (`1ae1358`): when the granted grid cuts
wider pane content the phone can re-flow the pane's own text — 12 dp gutter per side, 32 columns of reading width, 16 sp floor,
hard break inside over-long tokens — with the sharp 1:1 grid still the default and the whole pane still the exact-layout
fallback. The first attempt shipped at 21 columns and a 73.5 px font with every glyph flush to the card edge: complete content
that *looked* clipped, which is the worst of both. Numbers now: 32 columns, 47.4 px (≈18.1 sp), the phrase in six pieces that
concatenate to the exact 162 characters, verified on the **real** cropped flow (widest 182 against a granted 38) with the
preview switch built but not needed. 396 JVM tests. The debug preview is gated on `FLAG_DEBUGGABLE` and only touches the view
layer; the decision tests pass unmodified.
**Correction to L22 and to memory:** between 16:56 and 17:05 the daemon **did** reflow fresh panes' PTYs to the phone's claim
(37–38 columns, nothing cropped, the app correctly showed no notice), and by 17:19 it was holding panes wide again — with the
same desktop TUI attached throughout and no change on our side. So "never reflows while the desktop is attached" was too
strong: the arbitration flips with daemon state we do not control. The honest consequence is that wrap is a fallback for a
state that comes and goes, not a permanent answer, and that the phone must keep detecting rather than assuming.

**L33 (T33 closed — and the eyes earned their keep again):** The implementation passed 407 tests and built, but nobody had looked at a
screen; the verification pass found a defect no test could: **`WorkspacesScreen` never painted its own background**, so in Paper it
drew the hardcoded dark window colour behind dark-on-dark text and the title was nearly invisible — the classic "looks fine in the
theme you designed it in". Fixed at the root column, and the screen is now correct in all four themes. Three smaller corrections the
same pass caught: the theme picker's caption claimed the terminal follows the chrome (board `08` says the opposite — the terminal
stays dark), the Gentleman-Paper card was missing its `NEW` tag, and Settings showed the *resolved* theme in the pinned row while
Follow the phone was on instead of the stored pin (rendering keeps using the resolved theme; only the pin travels to the rows).
Confirmed live: four themes listed with Cute as default, Paper persists across a force-stop, Follow the phone does dark→Cute and
light→Paper and stays pinned when off, settings scrolls to the last card and both actions, and the session's send reaches the pane
(send glyph, composer clears). Environment, reported not papered over: `sshd` on port 22 was not listening during that pass (the
human had stopped it), so the worker used the task's own sshd on 2222 and stopped it after; `pi` failed OAuth as L30 predicts, so the
approval grouping was verified structurally only. Commits `ec65dc5` (implementation) and `7b2822f` (the fixes). Next: T31.

**L35 (one correction from the phone test):** The human, using the 0.8.0 build, asked for one IA correction: the connected host's row carried
*Workspaces* and *Session history* inline, which loaded the hosts screen and limited how many hosts fit. Those two cards move inside the host's
own screen; the list keeps name, address, state, Rename and Add. Host renaming already works and stays as is. Recorded as S34/T35.

**L36 (the terminal verdict: mirroring is the ceiling, and collie is the honest benchmark):** The human's judgement on the 0.8.0 build is
that the terminal is unusable next to Termux, herdr and collie: small, cramped, not made for a phone. The cause is architectural, not
cosmetic — every one of those either owns the PTY at phone size (Termux, herdr on a desktop) or refuses to draw a terminal at all
(collie, ADR 0008: *"collie does not run a terminal emulator"*), while this app mirrors a 184-column grid rendered for the desktop and
can only shrink, crop or re-flow it. Decision taken with the human: **stop mirroring for the terminal** and become the terminal — our own
PTY over SSH sized to the phone, parsing VT locally, running **`herdr`'s TUI inside it** (the human chose herdr over a bare shell),
keeping the herdr API lane for what it does well (board, approvals, agent state). First slice: PTY + local terminal with a shell, then
herdr inside, then the terminal as a tab beside the board with today's wrap as the fallback. Also recorded: collie (MIT, PWA over the
tailnet, no app store) is the honest benchmark for phone UX here and should be tried side by side before more of our own money goes into
a terminal nobody can read.

**L37 (T37 closed — the PTY lane exists and the size really travels):** `core/src/pty.rs` opens a session channel, requests a PTY with
`xterm-256color` and the phone's `cols`/`rows`, sends `window-change` in place on resize, and exposes one bidirectional byte stream with
the rules a terminal needs: a partial read reaches the caller at once, an oversized chunk is carried across reads, a closed channel
reports end-of-stream once with the host's exit status, and a quiet stream can be cancelled without leaking the channel. Proven live over
a real sshd: **`stty size: 31 rows x 97 cols (asked 31 x 97)`** — the geometry the phone asks for is the geometry the host's PTY has, which
is the whole reason Termux reads well and mirroring a 184-column grid never can. 8 new tests over a fake, 187 core tests. Commit for this
slice; T38 (the Termux engine drawing it with herdr inside) waits for the UI pass to release the phone files.
**Two self-inflicted incidents, owned:** deleting scratch mid-flight removed the probe key and the session `TMPDIR` while this worker was
using them — it fell back to the throwaway sshd on 2222 for the live proof and one test run failed transiently until the directory came back.
Cleanup happens when nobody is working, and it is reported before, not after.

**L38 (stopped for the day, nothing lost):** The owner had to shut the machine down, so the pass on the seven reported fixes was stopped
mid-verification: its work is committed as `5215c7b` on branch **`wip/t36-reported-bugs`** (1298 authored lines, one slice — above the
~400-line review size, so when it is published it should be split or reviewed as a WIP). `main` stays clean at `5d1d330`, with T37's PTY
lane committed and independent of that branch. **Nothing in that WIP is proven**: no build, no screenshots, no report — the worker was
cancelled before any of it. Tonight's state: emulator dead, daemon back to the human's eight workspaces, no listener of ours, the scratch
directory and the session's temp dirs deleted. Resume by building the branch first and looking at the seven screens before trusting any of it.

**L39 (S29 reversed by the human — the pane is shown whole):** While testing, the human asked to bring back the composer bar ("waiting for input")
because Termux shows it and hiding it never helped; they suspect it caused some of the app's odd behaviour. Agreed and done: the `ComposerCrop`
module and its 19 tests are deleted, `pollSurface` stores the frame as it arrives, and nothing pads rows any more. This removes the heuristic that
changed meaning when the bar's label flipped between idle and working, the four extra rows requested from the pane, and the blank band that the
padding could leave at the top of the card.

**L40 (0.9.0 shipped — the phone fixes and the pane shown whole):** The parked branch was completed and merged (`wip/t36-reported-bugs` → `main`,
one delivery of ~2.2k authored lines, above the review slice — recorded here as such). What landed: the hosts list is a list and each host keeps
Workspaces and Session history inside; session history has the back chevron, lost "one device at a time", and states that are true (running/closed
instead of a blanket `libre`); the inert `1 2 3` chips are a real pane switcher backed by a new `pane.focus` in the core; wrap is the default for
wide panes; the settings security section is trimmed; colours come from the active theme on every screen including session history; and the image
attach error is fixed at the upload path. Then **S35**: the composer crop is deleted (not disabled) so the pane shows whole the way Termux shows it,
and the notice slot is reserved only while a notice shows, which removes the dead band above the card without bringing back the claim
oscillation it was protecting. Verified by the parent on the emulator, not by a report: clean hosts list, both cards inside the host, no dead band,
the pane visible down to its composer rows and metrics. 439 JVM tests. Signed **0.9.0** (versionCode 9) in `~/Downloads`. Machine left clean:
emulator killed, daemon back to the owner's eight workspaces (the cancelled worker's `t36b-ws` closed), screenshots and scratch deleted.
Still open: **T38** (the Termux engine drawing our own PTY with herdr inside) and the collie comparison, which the owner wants to decide with both
in hand.

**L42 (two colour fixes and fonts):** The workspaces rows drew their names with the Material ink (near white in Cute) and the settings back chevron was ink where every other screen draws it in the accent — both now take the theme (`1930b9c`, 0.9.5). The human then chose, out of three options for typefaces, the small one: **bundle two or three OFL faces and give the Typeface row a real picker** — Space Grotesk, IBM Plex Sans and Aldrich, applied app-wide through the Material `Typography` so no screen needs touching, credited in `NOTICE` like Iconoir. T40 carries it.
