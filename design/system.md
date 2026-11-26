# Gentle Mobile — design system

Tokens captured from the real theme files that Gentle Shell and Herdr use on this machine.
Nothing here is invented: every value names its source. Values the sources do not contain are
listed under **Proposed starter tokens** and are not project tokens until the user accepts them.

Scope: the mobile app (Android first, iPhone prepared). The in-app terminal keeps its own
16-colour palette, exported from these same themes to Herdr's `[theme.custom]` shape.

## Theme set

| Theme | Role | Source |
|---|---|---|
| `Gentleman-Cute` | default | `gentle-pi/themes/Gentleman-Cute.json` (installed package, Apache-2.0) |
| `Gentleman-Sexy` | selectable | `gentle-pi/themes/Gentleman-Sexy.json` |
| `Gentle` | selectable, cool/neutral | `gentle-pi/themes/Gentle.json` |
| `Gentleman-Paper` | light, fourth choice (S33) | designed in T32 from the Cute/brand accent family; the theme file lands in T33 |

Source paths (installed package root):
`~/.gentle-shell/versions/4.8.0-1.5.2/node_modules/.pnpm/gentle-pi@4.8.0_*/node_modules/gentle-pi/themes/`
Herdr export shape: `~/.config/herdr/config.toml` `[theme.custom]`, and the pair
`~/.local/share/herdr-mustaccin/compatible-cute.toml` / `compatible-sexy.toml`.

Pi theme schema: `https://raw.githubusercontent.com/earendil-works/pi-mono/main/packages/coding-agent/src/modes/interactive/theme/theme-schema.json`

## Gentleman-Cute (default)

| Token | Value | Usage |
|---|---|---|
| `bg` | `#060407` | app background |
| `bgPanel` | `#100A0F` | cards, sheets, rails |
| `bgElement` | `#100A0F` | inputs, list rows |
| `userMessageBg` | `#2A1523` | user turn bubble |
| `border` | `#563040` | dividers, focus rings |
| `borderSubtle` | `#2A1720` | inactive borders |
| `text` | `#F6EFF3` | primary text |
| `muted` | `#A78E9B` | secondary text |
| `dim` | `#76616B` | tertiary text, disabled |
| `accent` | `#F095C8` | primary action, links |
| `deepPink` | `#C96AA2` | pressed accent, search match |
| `activePink` | `#FFB1DD` | accent borders, focus accent |
| `champagne` | `#E0C27A` | headings, tool titles, code |
| `pearl` | `#D2CBD0` | success |
| `softRose` | `#D7A0B8` | thinking text |
| `powderBlue` | `#A9C7EE` | functions, medium thinking |
| `mint` | `#B4E7C7` | success / diff added |
| `peach` | `#F2B86D` | warning, numbers |
| `sky` | `#C4DAF6` | operators |
| `error` | `#FF718F` | error, diff removed |
| `selection` | `#28121E` | selected row background |

Source: `gentle-pi/themes/Gentleman-Cute.json` (`vars` block).

## Gentleman-Sexy

| Token | Value | Usage |
|---|---|---|
| `bg` | `#060407` | app background |
| `bgPanel` | `#100A0F` | cards, sheets, rails |
| `userMessageBg` | `#2E0F1F` | user turn bubble |
| `border` | `#563040` | dividers, focus rings |
| `borderSubtle` | `#2A1720` | inactive borders |
| `text` | `#F6EFF3` | primary text |
| `muted` | `#A78E9B` | secondary text |
| `dim` | `#76616B` | tertiary text |
| `accent` | `#F43888` | primary action, links |
| `deepPink` | `#BF0F50` | pressed accent |
| `activePink` | `#FF4F9A` | accent borders |
| `champagne` | `#E0C27A` | headings, code |
| `violet` | `#C49BFF` | functions, high thinking |
| `pearl` | `#D2CBD0` | success |
| `softRose` | `#D7A0B8` | numbers |
| `powderBlue` | `#A9C7EE` | diff added, types |
| `warning` | `#F2B86D` | warning |
| `error` | `#FF718F` | error |

Source: `gentle-pi/themes/Gentleman-Sexy.json` (`vars` block); identical accent to
`compatible-sexy.toml` `[theme.custom] accent = "#f43888"`.

## Gentle

| Token | Value | Usage |
|---|---|---|
| `bg` | `#06080f` | app background |
| `bgSubtle` | `#0d0f14` | subtle panels |
| `border` | `#313342` | dividers |
| `borderSubtle` | `#232A40` | inactive borders, selection |
| `text` | `#F3F6F9` | primary text |
| `muted` | `#5C6170` | secondary and disabled text |
| `accent` | `#E0C15A` | primary action (gold) |
| `blue` | `#7FB4CA` | links, headings, functions |
| `secondary` | `#A3B5D6` | secondary highlight |
| `heading` | `#B5B2D0` | markdown headings |
| `green` | `#B7CC85` | success |
| `warning` | `#DEBA87` | warning, operators |
| `red` | `#CB7C94` | error |

Source: `gentle-pi/themes/Gentle.json` (`vars` block). Note this palette is deliberately cooler
and has no pink; it is the "no-brand" reading theme.

**Contrast rule for `Gentle`.** `muted` `#5C6170` measures 3.1 : 1 on the Gentle surface and therefore
fails WCAG AA for essential text. Use it only for decorative or disabled text; essential secondary
labels use `secondary` `#A3B5D6`. Cute and Sexy `muted` `#A78E9B` measures about 7 : 1 and is safe.

## Gentleman-Paper (designed in T32, L27 — enters as the fourth choice)

Paper is the S33 light pass: airy surfaces, soft shadow, large titles. It is **derived from the brand
pink family, not from the reference app's greys** — the accent stays recognisably pink in a light skin.
These values are design decisions measured for contrast at design time (artboards `08`–`11`), **not
captured from a theme source file**; T33 moves them into `themes/Gentleman-Paper.json` in the Pi theme
shape. This is the one table in this document where invention is deliberate, and it is labelled as such.

| Token | Value | Usage |
|---|---|---|
| `bg` | `#FAF5F6` | app background: faint rose-tinted paper (from the Cute page family, lifted) |
| `surface` | `#FFFFFF` | cards, sheets |
| `surfaceRaised` | `#FBEFF4` | fields, composer, switch tracks — a rose wash one step off white |
| `border` | `#E3CBD6` | strong hairlines, focus borders on fields |
| `borderSubtle` | `#F0E2E8` | inactive borders, row separators |
| `text` | `#36242C` | primary text (warm dark plum ink, the Cute text family inverted) |
| `muted` | `#8A6675` | secondary text (rose grey) |
| `dim` | `#B39CA7` | tertiary, disabled — decorative only |
| `accent` | `#C2417F` | primary action: brand rose deepened for AA on white (text and fills) |
| `accentPressed` | `#A63269` | pressed accent |
| `accentEdge` | `#E0479F` | accent borders, focus rings (the brand pink kept bright) |
| `selection` | `#FBE4EF` | selected rows, lanes, list highlight |
| `success` | `#2F6B45` | success text, test results, diff added (on `diffAddedBg`) |
| `diffAddedBg` | `#E7F4EC` | drawn diff added lines |
| `warning` | `#B26B1F` | warning glyph on risky approval answers |
| `error` | `#D6365B` | error, diff removed (on `diffRemovedBg`) |
| `diffRemovedBg` | `#FCE9ED` | drawn diff removed lines |
| `terminal` | `#100A0F` | unchanged — the terminal keeps Herdr's dark palette in every theme |

Measured contrast (WCAG, on white `#FFFFFF` unless stated): `text` 14.5 : 1 · `muted` 4.96 : 1
(4.59 on `bg`) · `accent` 4.82 : 1 as text and as fill with white text · `accentEdge` 3.77 : 1
(border/focus, ≥3 : 1 UI minimum) · `success` 6.34 : 1 · `warning` 4.19 : 1 · `error` 4.63 : 1 ·
`dim` 2.55 : 1 (decorative/disabled only). Hierarchy on light reads differently than on the dark
themes: the **page is tinted and the cards are white**, so cards are distinguished by hairline +
shadow instead of a darker surface; fields are the rose wash and never sit on a same-value surface.

## Semantic → theme mapping

The app uses semantic tokens only; a theme swap rewrites the backing values.

| Semantic token | Cute | Sexy | Gentle | Paper | Source of the mapping |
|---|---|---|---|---|---|
| `surface` | `#100A0F` | `#100A0F` | `#06080f` | `#FFFFFF` | `bgPanel` / `bg` |
| `surfaceRaised` | `#2A1720` | `#2A1720` | `#161D2E` | `#FBEFF4` | `borderSubtle` / `userMessageBg` / Paper rose wash |
| `textPrimary` | `#F6EFF3` | `#F6EFF3` | `#F3F6F9` | `#36242C` | `text` |
| `textSecondary` | `#A78E9B` | `#A78E9B` | `#5C6170` | `#8A6675` | `muted` |
| `textDisabled` | `#76616B` | `#76616B` | `#5C6170` | `#B39CA7` | `dim` |
| `accent` | `#F095C8` | `#F43888` | `#E0C15A` | `#C2417F` | `accent` |
| `accentPressed` | `#C96AA2` | `#BF0F50` | `#B5B2D0` | `#A63269` | `deepPink` / `secondary` |
| `accentEdge` | `#FFB1DD` | `#FF4F9A` | `#7FB4CA` | `#E0479F` | `activePink` / `blue` |
| `success` | `#B4E7C7` | `#D2CBD0` | `#B7CC85` | `#2F6B45` | `success` |
| `warning` | `#F2B86D` | `#F2B86D` | `#DEBA87` | `#B26B1F` | `warning` |
| `error` | `#FF718F` | `#FF718F` | `#CB7C94` | `#D6365B` | `error` |
| `selection` | `#28121E` | `#28121E` | `#232A40` | `#FBE4EF` | `selection` / Paper from `userMessageBg` lifted |

**App-level selection (`--selection`).** The mobile app paints the selected row, lane and list highlight with
the theme's `userMessageBg`, not with the terminal `selection` token, because `userMessageBg` is the value that
actually differs between Cute and Sexy: Cute `#2A1523`, Sexy `#2E0F1F`, Gentle `#161D2E`. Rationale: Cute and
Sexy share `bg`, `bgPanel` and `bgElement` in the source theme files — they differ only in the accent family,
`userMessageBg`, and syntax/status colours. A screenshot that expects different backgrounds between Cute and
Sexy is expecting something the source themes do not define.

## Terminal palette (Herdr export)

The in-app terminal keeps the 16-colour set the pane programs expect. Herdr's `[theme.custom]` keys
and their Cute/Sexy values:

| Herdr key | Cute | Sexy | Usage |
|---|---|---|---|
| `accent` | `#f095c8` | `#f43888` | prompt accent |
| `panel_bg` | `#100a0f` | `#100a0f` | panel background |
| `active_row_bg` | `#2a1523` | `#2e0f1f` | focused row |
| `selection_bg` | `#28121e` | `#28121e` | text selection |
| `surface0` | `#100a0f` | `#100a0f` | terminal background |
| `surface1` | `#2a1720` | `#2a1720` | cursor line |
| `surface_dim` | `#563040` | `#563040` | unfocused border |
| `overlay0` | `#f095c8` | `#f43888` | dim foreground |
| `overlay1` | `#d7a0b8` | `#f43888` | comment foreground |
| `text` | `#f6eff3` | `#f6eff3` | foreground |
| `subtext0` | `#f6eff3` | `#f6eff3` | secondary foreground |
| `mauve` | `#f095c8` | `#f43888` | keywords |
| `green` | `#b4e7c7` | `#d2cbd0` | strings, success |
| `yellow` | `#e0c27a` | `#e0c27a` | warnings |
| `red` | `#ff718f` | `#ff718f` | errors |
| `blue` | `#f095c8` | `#f43888` | types |
| `teal` | `#a9c7ee` | `#a9c7ee` | parameters |
| `peach` | `#f2b86d` | `#f2b86d` | numbers |
| `sidebar_bg` | `reset` | `reset` | inherit host |

Sources: `~/.local/share/herdr-mustaccin/compatible-cute.toml`, `compatible-sexy.toml`.
The `Gentle` theme has no Herdr export yet; its terminal palette is derived from its own `vars`
(`bg #06080f`, `text #F3F6F9`, `green #B7CC85`, `warning #DEBA87`, `red #CB7C94`, `blue #7FB4CA`,
`secondary #A3B5D6`) and is marked as a pending export in T16.

## Tool surfaces

Captured from the same theme `vars`; used by transcript tool blocks and diff rows.

| Token | Cute | Sexy | Usage |
|---|---|---|---|
| `toolPendingBg` | `#100A0F` | `#100A0F` | running tool block |
| `toolSuccessBg` | `#151316` | `#151316` | successful tool block |
| `toolErrorBg` | `#261019` | `#261019` | failed tool block |
| `infoBg` | `#100A0F` | `#100A0F` | info banner |
| `toolDiffAdded` | `#B4E7C7` (mint) | `#A9C7EE` (powderBlue) | added diff line |
| `toolDiffRemoved` | `#FF718F` (error) | `#FF718F` (error) | removed diff line |
| `toolTitle` | `#E0C27A` (champagne) | `#E0C27A` | tool name |

Source: `gentle-pi/themes/Gentleman-Cute.json` and `Gentleman-Sexy.json` (`vars` block).

## Typography

No font files exist in the sources: both products use system font stacks. The app follows that.

| Role | Stack | Source |
|---|---|---|
| UI text | platform system UI font | platform rule (Bellterm README: "system fonts only"; pi-companion design notes) |
| Terminal | bundled Nerd Font mono (JetBrainsMono Nerd Font Mono) | Bellterm `app/src/main/assets/fonts/` (Apache-2.0 build) |
| Code in transcripts | same terminal mono face | same |

Type scale, spacing, radius and shadows are **not** in the token sources — see below.

## Proposed starter tokens (awaiting user acceptance)

Not project tokens yet. Proposed because the sources only define colour.

| Token | Proposed value | Rationale |
|---|---|---|
| `space-1…6` | `4 / 8 / 12 / 16 / 24 / 32` px | 4-based scale, matches Material and Compose defaults |
| `radius-sm/md/lg/pill` | `8 / 12 / 16 / 999` px | soft cards, no glass/blur (S6: fewer visual effects) |
| `type-scale` | `11 / 13 / 15 / 18 / 22 / 28` px | mobile reading sizes, 15 px body |
| `line-height` | `1.35` body, `1.2` headings | dense terminal-adjacent reading |
| `touch-target` | `>= 44 pt` | platform rule (iOS HIG / Android 48 dp), stated in S6 |
| `elevation` | one flat shadow, no blur gradients | S6 reduces visual gestures |

## Redundancy rule (from canvas comments kocol9x1, wrodwaof, r9ttm4bq, 504x15if)

If an element repeats what the control, the radio state, or the surrounding context already says, it does not go
in the UI.

- No per-row status chip when the list is already grouped by that status.
- No shortcut legend next to the keys; bindings live behind the session menu.
- No mock chrome (buttons, chips, agent names) inside a theme card: the card is the control and shows a
  wordless colour strip.
- No jargon-only labels: a security row carries one plain sentence of what it does and what it prevents.
- Nothing sits in the thumb zone that is not an action or a value you act on.

## Refinement rules for every artboard (from T2b)

| Rule | Instead of | Use |
|---|---|---|
| Surfaces per screen | panel + a card per row | one surface, rows separated by a 1px `borderSubtle` hairline |
| Colour budget | 4-6 coloured elements | at most 2 accent uses and 1 error use per screen |
| Semantic colours | 8-9 per screen | 4: background, text, secondary text, accent |
| Notices | card with border and fill | left rule (2px) plus quiet text |
| State | chip with border per row | the word, once, in the group label |

**App-level chrome surfaces.** `surfaceRaised` (composer, tabs, key bar, switch tracks) takes a different
captured value per theme, because the source themes give Cute and Sexy identical panels and the app needs the
switch to be legible on a phone:

| Slot | Cute | Sexy | Gentle |
|---|---|---|---|
| `surfaceRaised` | `#2A1720` (`borderSubtle`) | `#2E0F1F` (`userMessageBg`) | `#161D2E` (`userMessageBg`) |
| `selection` | `#2A1523` (`userMessageBg`) | `#2E0F1F` (`userMessageBg`) | `#161D2E` (`userMessageBg`) |

Every value is captured in the theme's own `vars`; none is invented. This is a **mapping decision**, not a new
token: it is what makes Cute and Sexy read differently in the app while both stay recognisable. Divergence to
accept: the app's Sexy chrome is warmer than the Herdr terminal beside it, which stays on `[theme.custom]`.

## App palette — Cute follows the brand site

Cute does **not** use the theme file's near-black chrome. It uses the palette
`https://gentlemanprogramming.com/` declares in its own CSS custom properties, read from the live page
(`--st-base`, `--st-surface0/1/2`, `--st-overlay0`, `--st-text`, `--st-subtext1`, `--st-blue`, `--st-pink`,
`--st-red`). Nothing here is invented:

| App token | Cute (brand) | Source variable on the site |
|---|---|---|
| `--bg` | `#1A1218` | `--st-base` |
| `--surface` | `#20161E` | `--st-surface0` |
| `--surfaceRaised` | `#241822` | `--st-surface1` |
| `--border` / `--borderSubtle` | `#342230` | `--st-overlay0` / `--st-surface2` |
| `--selection` | `#342230` | `--st-overlay0` |
| `--accent` | `#FF76C3` | `--gentlepi-rose` |
| `--accentEdge` | `#FF8ACE` | `--gentlepi-logo-fresh` |
| `--accentPressed` | `#5F1E3C` | `--gentlepi-logo-dim` |
| `--textPrimary` | `#F6EFF3` | `--st-text` |
| `--textSecondary` | `#A78E9B` | `--st-subtext1` |
| `--error` | `#FF718F` | `--st-red` |
| `--terminal` | `#100A0F` | Herdr `[theme.custom]` — never the brand palette |

Runtime proof (measured, not assumed): page `rgb(26,18,24)`, panel `rgb(32,22,30)`, field `rgb(36,24,34)`,
terminal `rgb(16,10,15)`.

**Sexy and Gentle keep their captured palettes**, with no extension layer. Paper joins them with its
designed values (see its own section above):

| Slot | Sexy | Gentle | Paper |
|---|---|---|---|
| `--bg` | `#060407` | `#06080F` | `#FAF5F6` |
| `--surface` | `#100A0F` | `#0D0F14` | `#FFFFFF` |
| `--surfaceRaised` | `#2A1720` | `#161D2E` | `#FBEFF4` |
| `--borderSubtle` | `#2A1720` | `#232A40` | `#F0E2E8` |
| `--selection` | `#2E0F1F` | `#232A40` | `#FBE4EF` |
| `--shadow` | none | none | `0 6px 16px rgba(26,18,24,.10)` |

Gentle's page is `#06080F` (its own `bg`), never the lifted `#161D2E` that an earlier iteration used — that
correction came from canvas comment `d3vqi9m`. Every value in this table exists in `Gentleman-Sexy.json` or
`Gentle.json`.

**Sexy and Gentle keep their captured palettes.** Sexy stays on `#060407` page with `#100A0F` panels and
`#2E0F1F` selection; Gentle keeps its night-blue set and `secondary #A3B5D6` for secondary text. Only Cute was
in scope, and only Cute was changed.

Rules that come with it:

- The terminal always keeps **Herdr's** palette (`--terminal`). The app chrome never repaints it, because the
  terminal preview must tell the truth about what the pane will look like.
- Hierarchy always reads upward: `bg` < `surface` < `surfaceRaised`. A field must never sit on a same-value
  background; that is how the composer became invisible when `bg` was raised without lifting the rest.
- When a brand value is needed, read it from the site's own CSS variables and record the variable name here.
  Never approximate a brand colour by eye.

## Air rules (fixed in T32, L27 — the S33 pass, every theme)

These turn the proposed starter geometry into the numbers the boards (`10-air-rules.html` and the
S33 pass) actually use. They apply to all four themes; only the shadow differs.

**Type** (system UI stack; mono = bundled Nerd Font face):

| Role | Numbers | Where |
|---|---|---|
| Title | 28 px / 700 / 1.2 | screen h1 |
| Row name | 15 / 600 / 1.35 | hosts, agents, list rows |
| Body | 15 / 400 / **1.5** | subtitles, approval question context |
| Question | 15 / 600 / 1.4 | approval block |
| Command | 13 mono / 1.55 | exact command chip, paths |
| Section label | 11 / 500 / uppercase / .08em tracking | group labels |
| Meta | 11 mono / 1.5 | timestamps, versions, cwd |

**Rhythm:** screen side padding 16, top 12 (S30: no dead space above the card), bottom 16; between
cards 12; group labels 24 above / 4 below; rows 12–14 vertical with min-height 44; card inner
padding 16; every visible action ≥ 44 dp with 18–20 px glyphs (S27 quiet header).

**Surface:** card radius 16, terminal/composer radius 12, fields radius 8; hairlines are 1 px
`borderSubtle` everywhere; the **shadow is one flat layer** `0 6px 16px rgba(26,18,24,.10)` and only
in Paper — dark themes keep hairline-only cards (no blur stacks, S6). Colour budget unchanged:
one accent + one error per screen.

## Approval block (S32b — content, never a dialog)

- The agent's question, the **exact command in mono** (`Command: …` chip, `surfaceRaised`, radius 8),
  and **full-width option rows** at ≥ 44 dp separated by hairlines.
- Risky answers carry the warning triangle in `warning`; the safe answer carries none. Nothing else
  is marked.
- The needs-you count is **one count everywhere**: the group label (`needs you · 2`) and a single
  floating pill (`2 need you`); no per-row status chips (redundancy rule).
- Copy EN default: `May I run the migration on staging?` · `1. Yes` · `2. Yes, don't ask again` ·
  `3. No` · `2 need you`. ES: `¿Puedo ejecutar la migración en staging?` · `1. Sí` ·
  `2. Sí, no me vuelvas a preguntar` · `3. No` · `2 te necesitan`.

## Agent activity drawn, not dumped (S33)

- One card in the session flow: tool call lines (`Read(path)`, `Edit(path)`, `Bash(cmd)`) with the
  path as an underlined tappable link, a one-line result sub-line, and `+n −m` counts at the row end.
- Diff rows carry **coloured line numbers**: removed on `diffRemovedBg`/`error`, added on
  `diffAddedBg`/`success` (Paper); dark themes use `toolSuccessBg` with the theme's diff colours.
- Test results render as words with a colour, never a dump: `128 passed in 14.2s` in `success`.

## Terminal presentation states (S31b / S31c / S32a — artboard `11`)

The dressing never changes: quiet header (project name only), workspace tabs strip on the card,
reserved notice slot, key row and composer — every state stays **typeable**, never read-only.

| State | When | What the phone shows |
|---|---|---|
| Sharp 1:1 | the pane fits the granted grid | full frame, cell grid, nothing cut, notice slot **empty** |
| Cut | the desktop owns the pane (S31c) | lines clipped at the right edge, the one-line notice in `error` (`The desktop is using this pane, so long lines arrive cut.` / ES `El escritorio usa este panel: las líneas llegan cortadas.`) + `Show the whole pane` action; the notice lives in a slot reserved in every state so it never changes the derived grid |
| Whole (fallback) | explicit choice from the cut state | the entire pane scaled to fit, `… — showing it whole.` / ES `… se muestra entero.` |
| Wrap (T30) | the granted grid crops, chosen re-flow | the pane's text re-wrapped to the phone's columns at a readable size, nothing clipped, cursor + key row + composer intact; proposed notice copy: `Wrapped to this phone's width — nothing is cut.` / ES `Ajustado al ancho de este teléfono. Nada se corta.` — **new copy, flagged for T30 to confirm** |

Every state keeps the terminal palette truth: the app chrome never repaints the pane.

## Component rules (stated in the feature, not derived from tokens)

- No action may be reachable **only** by a gesture: every swipe has a visible button equivalent (S6).
- Agent status is a labelled chip, not only a colour dot: `needs you` / `working` / `done` / `idle`.
- One primary action per screen; the composer's send is the only filled accent button.
- Terminal text is never recoloured by the app chrome; the chrome yields to the terminal palette.
- Theme swap never changes layout, only tokens.

## Pending sources

- `https://gentlemanprogramming.com/` brand palette (S9). Not captured yet: the site is not a token
  file and no local build was inspected. It stays unlisted rather than guessed; it becomes a fourth
  theme (`Gentleman-Brand`) in T16 once its values are read from the site's CSS.

## Self-check

Colours used by every artboard must come from the tables above. Any artboard colour not listed here
is a deviation to fix before reporting. Fonts are limited to the system UI stack plus the bundled
mono face.
