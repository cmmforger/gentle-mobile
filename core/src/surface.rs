//! The terminal surface, decoded for the UI.
//!
//! The vendored client already applies every wire encoding itself: deltas
//! (`endpoint.surface-delta.v1`), scroll patches, and reuse frames are
//! reconstructed in `herdr-client/src/session.rs` and re-emitted as a full,
//! atomic [`herdr_protocol::PaneSurfaceFrame`] on
//! `ClientEvent::Surface`. This module therefore never touches the wire: it
//! resolves packed colours to RGB, decodes cells into plain DTOs, and
//! throttles the stream with a latest-wins store — the UI can never queue a
//! stale frame because there is exactly one slot, overwritten in place.
//!
//! Palette: the daemon's terminal palette follows the host Ghostty config
//! (`~/.config/ghostty/config`, mostaccin theme), measured in spike T4.
//! Indices 16–255 use the standard xterm 256 cube; modifier bits are ratatui
//! `Modifier` bits, with Herdr's underline style in bits 12–15 (upstream
//! `src/protocol/wire.rs`).

use std::sync::{Arc, Mutex, atomic::{AtomicU64, Ordering}};

use herdr_protocol::{CellData, PaneSurfaceFrame};
use unicode_width::UnicodeWidthStr;

// --- DTOs (the UniFFI surface; see uniffi_api) ------------------------------

/// One decoded cell. `fg`/`bg` are packed `0xRRGGBB`, already resolved.
/// `symbol` is `""` for a blank or skipped cell: blank cells still paint
/// their background, skipped cells (the follower of a wide glyph) do not
/// even own their column — the wide glyph spans both.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct CellDto {
    pub symbol: String,
    pub fg: u32,
    pub bg: u32,
    pub flags: u32,
    pub wide: bool,
    pub skip: bool,
}

impl CellDto {
    /// Columns this cell advances the drawing cursor by. A wide glyph owns
    /// two columns; the follower is reached by that advance, not its own.
    pub fn column_span(&self) -> u32 {
        if self.wide { 2 } else { 1 }
    }
}

/// The terminal cursor, in grid coordinates.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct CursorDto {
    pub x: u32,
    pub y: u32,
    pub visible: bool,
    /// The wire's cursor shape code: 1/2 block, 3/4 underline, 5/6 bar,
    /// 0 terminal default (drawn as a block).
    pub shape: u8,
}

/// One full surface frame in plain DTOs. `revision` is the daemon's
/// `surface_revision`: the UI renders only when it changes, so an idle pane
/// costs no redraws.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct SurfaceDto {
    pub width: u32,
    pub height: u32,
    pub revision: u64,
    pub cells: Vec<CellDto>,
    pub cursor: Option<CursorDto>,
    /// A popup overlays the grid but is not decoded here; the UI says so
    /// rather than silently drawing a grid the daemon has covered.
    pub popup_present: bool,
}

// --- Attribute flags --------------------------------------------------------

/// Cell attribute flags, decoded for the UI. Deliberately not the wire's
/// ratatui bits: Herdr's underline style nibble (bits 12–15) is masked out
/// here, so it can never leak as a bogus flag.
pub const FLAG_BOLD: u32 = 1;
pub const FLAG_DIM: u32 = 1 << 1;
pub const FLAG_ITALIC: u32 = 1 << 2;
pub const FLAG_UNDERLINE: u32 = 1 << 3;
pub const FLAG_REVERSED: u32 = 1 << 4;
pub const FLAG_HIDDEN: u32 = 1 << 5;
pub const FLAG_STRUCK: u32 = 1 << 6;

const WIRE_BOLD: u16 = 1 << 0;
const WIRE_DIM: u16 = 1 << 1;
const WIRE_ITALIC: u16 = 1 << 2;
const WIRE_UNDERLINED: u16 = 1 << 3;
const WIRE_REVERSED: u16 = 1 << 6;
const WIRE_HIDDEN: u16 = 1 << 7;
const WIRE_CROSSED_OUT: u16 = 1 << 8;
/// Herdr extension: underline style nibble, masked out by the decoder.
const UNDERLINE_STYLE_MASK: u16 = 0xF000;

/// Map the wire's ratatui modifier bits to UI flags. Herdr's underline
/// style nibble (bits 12–15) is stripped first so it can never leak as a
/// bogus flag.
fn flags_from_modifier(modifier: u16) -> u32 {
    let modifier = modifier & !UNDERLINE_STYLE_MASK;
    let mut flags = 0u32;
    if modifier & WIRE_BOLD != 0 {
        flags |= FLAG_BOLD;
    }
    if modifier & WIRE_DIM != 0 {
        flags |= FLAG_DIM;
    }
    if modifier & WIRE_ITALIC != 0 {
        flags |= FLAG_ITALIC;
    }
    if modifier & WIRE_UNDERLINED != 0 {
        flags |= FLAG_UNDERLINE;
    }
    if modifier & WIRE_REVERSED != 0 {
        flags |= FLAG_REVERSED;
    }
    if modifier & WIRE_HIDDEN != 0 {
        flags |= FLAG_HIDDEN;
    }
    if modifier & WIRE_CROSSED_OUT != 0 {
        flags |= FLAG_STRUCK;
    }
    flags
}

// --- Colour resolution ------------------------------------------------------

/// Colour comments in this table quote the host Ghostty config verbatim.
pub const DEFAULT_BG: [u8; 3] = [0x0a, 0x06, 0x09];
pub const DEFAULT_FG: [u8; 3] = [0xf6, 0xef, 0xf3];
/// Named colours 1..=16 (ratatui order) → ANSI indices 0..=15.
const NAMED: [[u8; 3]; 16] = [
    [0x0a, 0x06, 0x09], // palette = 0
    [0xff, 0x71, 0x8f], // palette = 1
    [0xb4, 0xe7, 0xc7], // palette = 2
    [0xe0, 0xc2, 0x7a], // palette = 3
    [0xf0, 0x95, 0xc8], // palette = 4
    [0xff, 0xb1, 0xdd], // palette = 5
    [0xa9, 0xc7, 0xee], // palette = 6
    [0xf6, 0xef, 0xf3], // palette = 7
    [0x1a, 0x12, 0x18], // palette = 8
    [0xff, 0x71, 0x8f], // palette = 9
    [0xb4, 0xe7, 0xc7], // palette = 10
    [0xf2, 0xb8, 0x6d], // palette = 11
    [0xff, 0x76, 0xc3], // palette = 12
    [0xff, 0xb1, 0xdd], // palette = 13
    [0xc4, 0xda, 0xf6], // palette = 14
    [0xf6, 0xef, 0xf3], // palette = 15
];
const XTERM_CUBE_LEVELS: [u8; 6] = [0, 95, 135, 175, 215, 255];

/// Resolve one packed wire colour to RGB. Named 0 is Reset: the default
/// foreground for a glyph, the default background for a cell. An unknown
/// kind byte falls back to the default instead of inventing a colour.
pub fn resolve_color(color: u32, is_fg: bool) -> [u8; 3] {
    match color >> 24 {
        0x00 => match color & 0xFF {
            0 => if is_fg { DEFAULT_FG } else { DEFAULT_BG },
            value if (1..=16).contains(&value) => NAMED[(value - 1) as usize],
            _ => if is_fg { DEFAULT_FG } else { DEFAULT_BG },
        },
        0x01 => {
            let index = (color & 0xFF) as usize;
            if index < 16 {
                NAMED[index]
            } else {
                xterm256(index)
            }
        }
        0x02 => [(color >> 16) as u8, (color >> 8) as u8, color as u8],
        _ => if is_fg { DEFAULT_FG } else { DEFAULT_BG },
    }
}

/// Standard xterm 256 palette for indices 16..=255.
fn xterm256(index: usize) -> [u8; 3] {
    if index < 232 {
        let rest = index - 16;
        let (r, rest) = (rest / 36, rest % 36);
        let (g, b) = (rest / 6, rest % 6);
        [
            XTERM_CUBE_LEVELS[r],
            XTERM_CUBE_LEVELS[g],
            XTERM_CUBE_LEVELS[b],
        ]
    } else {
        let gray = 8 + (index - 232) * 10;
        [gray as u8, gray as u8, gray as u8]
    }
}

/// Pack resolved RGB as `0xRRGGBB` for the DTO.
pub fn pack(rgb: [u8; 3]) -> u32 {
    u32::from(rgb[0]) << 16 | u32::from(rgb[1]) << 8 | u32::from(rgb[2])
}

// --- Decoding ---------------------------------------------------------------

/// Decode one full vendored frame into UI DTOs. The frame is already the
/// reconstructed whole grid (the vendored client applied any wire deltas);
/// nothing here re-reads the wire.
pub fn decode_surface(frame: &PaneSurfaceFrame) -> SurfaceDto {
    let grid = &frame.frame;
    SurfaceDto {
        width: u32::from(grid.width),
        height: u32::from(grid.height),
        revision: frame.surface_revision,
        cells: grid.cells.iter().map(decode_cell).collect(),
        cursor: grid.cursor.as_ref().map(|cursor| CursorDto {
            x: u32::from(cursor.x),
            y: u32::from(cursor.y),
            visible: cursor.visible,
            shape: cursor.shape,
        }),
        popup_present: frame.popup.is_some(),
    }
}

fn decode_cell(cell: &CellData) -> CellDto {
    CellDto {
        symbol: cell.symbol.clone(),
        fg: pack(resolve_color(cell.fg, true)),
        bg: pack(resolve_color(cell.bg, false)),
        flags: flags_from_modifier(cell.modifier),
        wide: cell.symbol.width() >= 2,
        skip: cell.skip,
    }
}

// --- The latest-wins throttle -----------------------------------------------

/// The surface throttle, at the source. One slot, overwritten in place: a
/// burst of N frames between two consumer polls leaves exactly the last one,
/// and a frame older than the slot is refused instead of resurrected.
/// A fresh store is empty — a new connection never reuses the previous
/// connection's baseline.
pub struct SurfaceStore {
    slot: Mutex<Option<Arc<PaneSurfaceFrame>>>,
    dropped: AtomicU64,
}

impl SurfaceStore {
    pub fn new() -> Self {
        Self { slot: Mutex::new(None), dropped: AtomicU64::new(0) }
    }

    /// Offer one frame. A frame whose revision is not strictly newer than
    /// the slot's is counted as dropped, not queued — unless its grid
    /// dimensions changed: that is the re-render after the phone's claimed
    /// resize (T26 rule 6), and the reflow must replace the slot even when
    /// the daemon's revision bookkeeping did not advance.
    pub fn store(&self, frame: Arc<PaneSurfaceFrame>) {
        let mut slot = match self.slot.lock() {
            Ok(slot) => slot,
            Err(_) => {
                self.dropped.fetch_add(1, Ordering::Relaxed);
                return;
            }
        };
        if let Some(current) = slot.as_ref() {
            let advancing = frame.surface_revision > current.surface_revision;
            let reflowed = (frame.frame.width, frame.frame.height)
                != (current.frame.width, current.frame.height);
            if !advancing && !reflowed {
                drop(slot);
                self.dropped.fetch_add(1, Ordering::Relaxed);
                return;
            }
            // The frame being replaced is collapsed, not queued: it never
            // reaches the consumer.
            self.dropped.fetch_add(1, Ordering::Relaxed);
        }
        *slot = Some(frame);
    }

    /// The latest frame, if any. Reading never drains: the grid stays warm.
    pub fn latest(&self) -> Option<Arc<PaneSurfaceFrame>> {
        self.slot.lock().ok().and_then(|slot| slot.clone())
    }

    /// How many offered frames never reached the slot (stale, equal, or
    /// unreadable state): the proof that a burst was collapsed, not queued.
    pub fn dropped(&self) -> u64 {
        self.dropped.load(Ordering::Relaxed)
    }
}

impl Default for SurfaceStore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use herdr_protocol::{FrameData, surface_delta::GridUpdate};

    /// A frame with a grid of blank cells and the given revision.
    fn frame(revision: u64) -> PaneSurfaceFrame {
        PaneSurfaceFrame {
            boot_id: "boot".to_owned(),
            projection_revision: revision,
            surface_revision: revision,
            frame: FrameData {
                cells: vec![
                    CellData {
                        symbol: " ".to_owned(),
                        fg: 0,
                        bg: 0,
                        modifier: 0,
                        skip: false,
                        hyperlink: None,
                    };
                    6
                ],
                width: 3,
                height: 2,
                cursor: None,
                hyperlinks: Vec::new(),
                graphics: Vec::new(),
            },
            panes: Vec::new(),
            splits: Vec::new(),
            popup: None,
            graphics: Default::default(),
        }
    }

    // --- colour resolution --------------------------------------------------

    #[test]
    fn named_colours_resolve_to_the_theme_palette() {
        // Reset (0) is the default: foreground for a glyph, background for a cell.
        assert_eq!(resolve_color(0x00_00_0000, true), DEFAULT_FG);
        assert_eq!(resolve_color(0x00_00_0000, false), DEFAULT_BG);
        // The wire's named value is ratatui order: 2 = red, 3 = green (mint).
        assert_eq!(resolve_color(0x00_00_0002, true), [0xff, 0x71, 0x8f]);
        assert_eq!(resolve_color(0x00_00_0003, true), [0xb4, 0xe7, 0xc7]);
        assert_eq!(resolve_color(0x00_00_0005, false), [0xf0, 0x95, 0xc8]);
        // The named range ends at 16; 17 is not a colour the daemon defined.
        assert_eq!(resolve_color(0x00_00_0011, true), DEFAULT_FG);
    }

    #[test]
    fn indexed_colours_16_to_255_resolve_to_the_xterm_cube() {
        // Index 16 is the first cube cell: 0,0,0.
        assert_eq!(resolve_color(0x01_00_0010, false), [0, 0, 0]);
        // Index 52 = 16 + 36·1 → level r=1 → 95 red.
        assert_eq!(resolve_color(0x01_00_0034, false), [95, 0, 0]);
        // Index 20 = 16 + 4 → level b=4 → 215 blue.
        assert_eq!(resolve_color(0x01_00_0014, false), [0, 0, 215]);
        // Index 232 is the first grayscale step: 8.
        assert_eq!(resolve_color(0x01_00_00e8, true), [8, 8, 8]);
        // Index 255 is the last: 8 + 23*10 = 238.
        assert_eq!(resolve_color(0x01_00_00ff, true), [238, 238, 238]);
        // Indexed below 16 aliases the ANSI palette directly: 3 = yellow.
        assert_eq!(resolve_color(0x01_00_0003, true), [0xe0, 0xc2, 0x7a]);
    }

    #[test]
    fn rgb_colours_resolve_verbatim() {
        // 0x02RRGGBB is NOT ARGB: the byte order is verbatim.
        assert_eq!(resolve_color(0x02_1a_2b3c, true), [0x1a, 0x2b, 0x3c]);
        assert_eq!(resolve_color(0x02_ff_76c3, false), [0xff, 0x76, 0xc3]);
        assert_eq!(pack([0x1a, 0x2b, 0x3c]), 0x1a2b3c);
    }

    #[test]
    fn unknown_colour_kind_falls_back_to_the_default() {
        assert_eq!(resolve_color(0x03_1a_2b3c, true), DEFAULT_FG);
        assert_eq!(resolve_color(0xff_00_0000, false), DEFAULT_BG);
    }

    // --- attribute flags ------------------------------------------------------

    #[test]
    fn modifier_bits_map_to_ui_flags() {
        let flags = flags_from_modifier(WIRE_BOLD | WIRE_ITALIC | WIRE_REVERSED);
        assert_eq!(
            flags,
            FLAG_BOLD | FLAG_ITALIC | FLAG_REVERSED,
            "the three bits the session screen exercises most"
        );
        assert_eq!(flags_from_modifier(WIRE_UNDERLINED), FLAG_UNDERLINE);
        assert_eq!(flags_from_modifier(WIRE_CROSSED_OUT), FLAG_STRUCK);
        assert_eq!(flags_from_modifier(WIRE_DIM), FLAG_DIM);
        assert_eq!(flags_from_modifier(WIRE_HIDDEN), FLAG_HIDDEN);
    }

    #[test]
    fn underline_style_nibble_never_leaks_as_a_flag() {
        // Herdr's underline style lives in bits 12–15; only bit 3 means
        // "underlined". A styled underline without the bit is no flag at all.
        assert_eq!(flags_from_modifier(0x1000), 0);
        assert_eq!(flags_from_modifier(0xF000), 0);
        assert_eq!(
            flags_from_modifier(WIRE_UNDERLINED | 0x2000),
            FLAG_UNDERLINE,
            "a styled underline keeps its underline flag, nothing more"
        );
    }

    // --- decoding ---------------------------------------------------------------

    #[test]
    fn wide_cells_own_two_columns_and_their_follower_is_marked_skip() {
        let mut frame = frame(1);
        // Row "a漢b": the CJK glyph is wide; herdr marks its follower skip.
        let cells = &mut frame.frame.cells;
        cells[0].symbol = "a".to_owned();
        cells[1].symbol = "漢".to_owned();
        cells[2].symbol = String::new();
        cells[2].skip = true;
        cells[3].symbol = "b".to_owned();
        let surface = decode_surface(&frame);
        assert_eq!((surface.width, surface.height), (3, 2));
        assert_eq!(surface.cells[0].column_span(), 1);
        assert!(surface.cells[1].wide, "a double-width glyph is wide");
        assert_eq!(surface.cells[1].column_span(), 2);
        assert!(surface.cells[2].skip, "the wide glyph's follower is skipped");
        // The drawing walk a consumer needs: a glyph is drawn at the walking
        // column; the wide glyph advances two and its skipped follower
        // advances nothing (it has no glyph of its own).
        let mut column = 0u32;
        let mut previous_wide = false;
        let mut glyph_columns = Vec::new();
        for cell in &surface.cells[..4] {
            if !(cell.skip && previous_wide) && !cell.symbol.is_empty() {
                glyph_columns.push(column);
            }
            if !(cell.skip && previous_wide) {
                column += cell.column_span();
            }
            previous_wide = cell.wide;
        }
        assert_eq!(glyph_columns, vec![0, 1, 3], "a@0, 漢@1 spanning 1..2, nothing for the follower, b@3");
    }

    #[test]
    fn cursor_decodes_with_position_visibility_and_shape() {
        let mut frame = frame(7);
        frame.frame.cursor = Some(herdr_protocol::CursorState {
            x: 3,
            y: 4,
            visible: true,
            shape: 2,
        });
        let surface = decode_surface(&frame);
        let cursor = surface.cursor.expect("a frame with a cursor decodes one");
        assert_eq!((cursor.x, cursor.y), (3, 4));
        assert!(cursor.visible);
        assert_eq!(cursor.shape, 2);
        assert_eq!(surface.revision, 7);
    }

    #[test]
    fn a_frame_without_a_cursor_decodes_without_one() {
        let surface = decode_surface(&frame(1));
        assert!(surface.cursor.is_none());
        assert!(!surface.popup_present);
    }

    #[test]
    fn popup_presence_is_reported_not_hidden() {
        let mut with_popup = frame(1);
        with_popup.popup = Some(Box::new(herdr_protocol::ClientShellPopupSurface {
            terminal_id: "t".to_owned(),
            title: "popup".to_owned(),
            width: None,
            height: None,
            frame: frame(0).frame,
            mouse_reporting: false,
            sgr_pixel_mouse: false,
            pixel_width: 0,
            pixel_height: 0,
        }));
        assert!(decode_surface(&with_popup).popup_present);
    }

    #[test]
    fn cell_flags_and_colours_decode_from_the_wire() {
        let mut frame = frame(1);
        let cell = &mut frame.frame.cells[0];
        cell.symbol = "x".to_owned();
        cell.fg = 0x02_ff_76c3;
        cell.bg = 0x01_00_0010;
        cell.modifier = WIRE_BOLD | WIRE_UNDERLINED;
        let decoded = decode_surface(&frame).cells.remove(0);
        assert_eq!(decoded.symbol, "x");
        assert_eq!(decoded.fg, 0xff76c3);
        assert_eq!(decoded.bg, 0);
        assert_eq!(decoded.flags, FLAG_BOLD | FLAG_UNDERLINE);
    }

    // --- the latest-wins throttle -------------------------------------------------

    #[test]
    fn a_burst_of_frames_leaves_exactly_the_last_one() {
        let store = SurfaceStore::new();
        for revision in 1..=5u64 {
            store.store(Arc::new(frame(revision)));
        }
        let latest = store.latest().expect("a burst leaves a frame");
        assert_eq!(latest.surface_revision, 5, "the consumer sees the last frame");
        assert_eq!(store.dropped(), 4, "four stale frames were collapsed, not queued");
    }

    #[test]
    fn a_stale_or_equal_frame_never_resurrects_over_the_slot() {
        let store = SurfaceStore::new();
        store.store(Arc::new(frame(10)));
        store.store(Arc::new(frame(9)));
        store.store(Arc::new(frame(10)));
        let latest = store.latest().expect("the newest frame stays");
        assert_eq!(latest.surface_revision, 10);
        assert_eq!(store.dropped(), 2, "stale and equal frames are both refused");
    }

    #[test]
    fn a_fresh_store_is_an_empty_baseline() {
        // A new connection constructs its own store: no frame from the old
        // connection can survive a reconnect.
        let store = SurfaceStore::new();
        assert!(store.latest().is_none());
        assert_eq!(store.dropped(), 0);
    }

    // T26 rule 6/7: after the phone's claim the daemon may re-render with a
    // revision that does not advance. A frame with NEW dimensions is the
    // reflow itself — it replaces the slot; a stale frame of the SAME
    // dimensions is still collapsed.
    #[test]
    fn a_reflowed_frame_of_new_dimensions_replaces_the_slot_even_without_revision_advance() {
        let store = SurfaceStore::new();
        store.store(Arc::new(frame(10)));
        let mut reflowed = frame(10);
        reflowed.frame.cells.push(reflowed.frame.cells[0].clone());
        reflowed.frame.width = 2;
        store.store(Arc::new(reflowed));
        let latest = store.latest().expect("the reflowed frame replaces the slot");
        assert_eq!(latest.frame.width, 2, "the reflowed geometry is the slot");
        assert_eq!(latest.surface_revision, 10);
        let mut stale = frame(10);
        stale.frame.width = 2;
        store.store(Arc::new(stale));
        let latest = store.latest().unwrap();
        assert_eq!(
            latest.frame.cells.len(),
            7,
            "the same-dims stale frame never resurrects over the reflowed one"
        );
        assert_eq!(
            store.dropped(),
            2,
            "both replaced frames are collapsed, not queued"
        );
    }

    // A guard against accidental coupling to `GridUpdate`: the store works on
    // full frames only. (Keeps the import used; the delta path belongs to the
    // vendored client.)
    const _GRID_UPDATE_IS_VENDORED: fn(&GridUpdate) -> &str = |_| "vendored";
}
