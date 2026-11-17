//! Spike T4: prove Herdr's rendered cell surface (`ClientEvent::Surface`) can
//! be turned into something the phone can draw, and measure what it costs.
//!
//! Connects locally (same pattern as `spikes/herdr-local`), waits for the
//! first `Surface`, then:
//! - decodes the cell grid (symbol, fg, bg, modifier bits, skip, hyperlink);
//! - prints a compact `char|fg|bg` dump of the first 8 rows;
//! - writes one PNG of the full surface to
//!   `/mnt/WD-Black/test_tmp/gentle-companion/frames/frame-01.png` (each cell
//!   is a filled background rectangle with the glyph approximated by a solid
//!   foreground block — geometry, colour fidelity and wide characters, not
//!   font rendering);
//! - dumps the same frame as truecolor ANSI to stdout;
//! - reports grid size, cell count, wide/ambiguous cells, distinct colours,
//!   serialized frame size, and then measures bytes/frame and bytes/s over a
//!   window of live surfaces (full-frame re-encode and a delta-encoding
//!   estimate, since the client crate does not expose raw wire bytes).
//!
//! Palette: the daemon's terminal palette follows the host Ghostty config
//! (`~/.config/ghostty/config`, mostaccin theme); indices 16–255 use the
//! standard xterm 256 cube. Modifier bits are ratatui `Modifier` bits, with
//! Herdr's underline style in bits 12–15 (upstream `src/protocol/wire.rs`).

use anyhow::{Context, Result, bail};
use herdr_client::{
    ClientEvent, ConnectOptions, ConnectTarget, connect,
    protocol::{ClientKeyCode, ClientKeyKind, ClientPaneInputEvent},
};
use herdr_protocol::{
    FrameData, PaneSurfaceFrame, PaneSurfacePatchRow, ServerMessage,
    surface_delta::{GridUpdate, MESSAGE_KIND, SurfaceDelta},
    {CellData, CursorState, MAX_GRAPHICS_FRAME_SIZE, encode_message},
};
use png::{ColorType, Encoder};
use std::{
    collections::HashSet,
    fs::{File, create_dir_all},
    path::Path,
    time::{Duration, Instant},
};
use unicode_width::UnicodeWidthStr;

/// Colour comments in this table quote the host Ghostty config verbatim.
const BG_DEFAULT: [u8; 3] = [0x0a, 0x06, 0x09];
const FG_DEFAULT: [u8; 3] = [0xf6, 0xef, 0xf3];
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

const DUMP_ROWS: usize = 8;
const SETUP_TIMEOUT: Duration = Duration::from_secs(30);
const PNG_PATH: &str = "/mnt/WD-Black/test_tmp/gentle-companion/frames/frame-01.png";

fn measure_window() -> Duration {
    Duration::from_secs(
        std::env::var("SPIKE_EVENT_SECS")
            .ok()
            .and_then(|secs| secs.parse().ok())
            .unwrap_or(10),
    )
}

// ratatui Modifier bits (upstream `modifier_to_u16` passes them through).
const MOD_BOLD: u16 = 1 << 0;
const MOD_DIM: u16 = 1 << 1;
const MOD_ITALIC: u16 = 1 << 2;
const MOD_UNDERLINED: u16 = 1 << 3;
const MOD_SLOW_BLINK: u16 = 1 << 4;
const MOD_RAPID_BLINK: u16 = 1 << 5;
const MOD_REVERSED: u16 = 1 << 6;
const MOD_HIDDEN: u16 = 1 << 7;
const MOD_CROSSED_OUT: u16 = 1 << 8;
/// Herdr extension: underline style nibble, masked out by `u16_to_modifier`.
const UNDERLINE_STYLE_MASK: u16 = 0xF000;

fn main() -> Result<()> {
    let client = connect(ConnectTarget::Local, ConnectOptions::default())
        .context("connecting to the local herdr daemon (session default)")?;
    println!("connected; waiting for the first Surface");

    let mut cell_w: u32 = 8;
    let mut cell_h: u32 = 16;
    let mut reported_geometry = false;
    let first: std::sync::Arc<PaneSurfaceFrame> = loop {
        match client.events.recv_timeout(SETUP_TIMEOUT) {
            Ok(ClientEvent::Connected(_welcome)) => {}
            Ok(ClientEvent::Surface(surface)) => {
                // The client declares cell px in its hello (the phone picks its
                // own cell size); the daemon reports per-pane pixels, so derive
                // a fallback geometry from the largest pane when present.
                for pane in &surface.panes {
                    if pane.pixel_width > 0 && pane.pixel_height > 0 && pane.rect.width > 0 {
                        cell_w = (pane.pixel_width / u32::from(pane.rect.width)).max(1);
                        cell_h = (pane.pixel_height / u32::from(pane.rect.height)).max(1);
                        reported_geometry = true;
                    }
                }
                if reported_geometry {
                    println!("derived cell geometry from pane pixels: {}x{} px", cell_w, cell_h);
                }
                break surface;
            }
            Ok(ClientEvent::Disconnected { reason, .. }) => {
                bail!("daemon disconnected before the first surface: {reason}")
            }
            Ok(_) => {}
            Err(timeout) => bail!("no surface within {SETUP_TIMEOUT:?}: {timeout}"),
        }
    };
    if !reported_geometry {
        println!("no pane pixels reported; assuming {}x{} px cells", cell_w, cell_h);
    }

    let frame = &first.frame;
    let (cols, rows) = (usize::from(frame.width), usize::from(frame.height));
    let cells = &frame.cells;
    println!("\n=== frame surface_revision {} ===", first.surface_revision);
    println!(
        "grid: {cols} cols x {rows} rows = {} cells (popup: {})",
        cells.len(),
        if first.popup.is_some() { "present, excluded from this dump" } else { "none" }
    );

    let mut distinct: HashSet<u32> = HashSet::new();
    let mut wide = 0usize;
    let mut ambiguous = 0usize;
    let mut hyperlinked = 0usize;
    let mut skipped = 0usize;
    let mut attribute_bits = 0u16;
    for cell in cells {
        let fg = resolve_color(cell.fg, true);
        let bg = resolve_color(cell.bg, false);
        distinct.insert(pack(fg));
        distinct.insert(pack(bg));
        let (w, cjk) = (cell.symbol.width(), cell.symbol.width_cjk());
        if w >= 2 {
            wide += 1;
        }
        if cjk != w {
            ambiguous += 1;
        }
        if cell.hyperlink.is_some() {
            hyperlinked += 1;
        }
        if cell.skip {
            skipped += 1;
        }
        attribute_bits |= cell.modifier;
    }
    println!("wide cells: {wide}, ambiguous-width cells: {ambiguous}");
    println!("distinct colours (resolved fg+bg RGB): {}", distinct.len());
    println!("cells with hyperlinks: {hyperlinked}, skip cells: {skipped}");
    println!("modifier bits seen in the frame: {:#06b}", attribute_bits);
    match &frame.cursor {
        Some(CursorState { x, y, visible, .. }) => {
            println!("cursor: ({x},{y}) visible={visible} shape={}", cursor_shape(frame.cursor.as_ref()));
        }
        None => println!("cursor: absent in this frame"),
    }

    let payload = encode_message(
        &ServerMessage::PaneSurface((*first).clone()),
        MAX_GRAPHICS_FRAME_SIZE,
    )
    .context("re-encoding the first frame for its wire size")?;
    println!(
        "serialized size of this full frame (bincode payload, re-encoded with the crate's own codec): {} bytes",
        payload.len() - 4
    );

    print_text_dump(frame, DUMP_ROWS);
    print_ansi_dump(frame);
    write_png(PNG_PATH, cell_w, cell_h, frame)
        .with_context(|| format!("writing {}", PNG_PATH))?;
    println!("PNG written to {PNG_PATH}");

    measure(&client, &first, measure_window())
}

/// Types a self-terminating streaming command into the focused pane so the
/// surface stream carries live updates; without it an idle session sends
/// nothing and there is nothing to measure.
fn drive(client: &herdr_client::Client, first: &PaneSurfaceFrame) -> Result<()> {
    let pane = first
        .panes
        .iter()
        .find(|pane| pane.focused)
        .or_else(|| first.panes.first())
        .context("no pane to drive")?;
    let command = "for i in $(seq 1 60); do seq 1 5; sleep 0.1; done";
    client
        .handle
        .send_input(
            &first.boot_id,
            &pane.pane_id,
            [
                ClientPaneInputEvent::Paste(format!("{command}")),
                ClientPaneInputEvent::Key {
                    code: ClientKeyCode::Enter,
                    modifiers: 0,
                    kind: ClientKeyKind::Press,
                    repeat_count: 1,
                    shifted_codepoint: None,
                    generated_text: None,
                    tracks_release: false,
                    physical_key_id: None,
                    windows_record: None,
                },
            ],
        )
        .context("queuing the streaming command")?;
    println!("drove pane {} with: {command}", pane.pane_id);
    Ok(())
}

/// Live measurement: full-frame re-encoded bytes and a delta-encoding estimate
/// for every consecutive surface pair, over one window at the observed rate.
fn measure(
    client: &herdr_client::Client,
    first: &std::sync::Arc<PaneSurfaceFrame>,
    window: Duration,
) -> Result<()> {
    // Opt-in: the focused pane is the user's live agent session, so typing
    // into it injects a prompt. SPIKE_DRIVE=1 authorizes that explicitly.
    if std::env::var("SPIKE_DRIVE").as_deref() == Ok("1") {
        drive(client, first)?;
    }
    let mut previous = Some(first.clone());
    let mut surfaces = 0u64;
    let mut full_bytes = 0u64;
    let mut delta_sizes: Vec<u64> = Vec::new();
    let start = Instant::now();
    while start.elapsed() < window {
        match client.events.recv_timeout(window.saturating_sub(start.elapsed())) {
            Ok(ClientEvent::Surface(surface)) => {
                surfaces += 1;
                full_bytes += (encode_message(
                    &ServerMessage::PaneSurface((*surface).clone()),
                    MAX_GRAPHICS_FRAME_SIZE,
                )
                .context("re-encoding a live frame")?
                    .len() - 4) as u64;
                if let Some(base) = previous.as_ref() {
                    if base.surface_revision.checked_add(1) == Some(surface.surface_revision) {
                        if let Some(wire) = delta_wire_size(base, surface.as_ref()) {
                            delta_sizes.push(wire);
                        }
                    }
                }
                previous = Some(surface);
            }
            Ok(ClientEvent::Disconnected { reason, .. }) => {
                println!("daemon disconnected during measurement: {reason}");
                break;
            }
            Ok(_) => {}
            Err(_) => break,
        }
    }
    let elapsed = start.elapsed().as_secs_f64().max(0.001);
    let rate = surfaces as f64 / elapsed;
    println!("\n=== measurement over {:.1}s ===", elapsed);
    println!("surfaces observed: {surfaces} ({rate:.1}/s)");
    if surfaces > 0 {
        println!(
            "full-frame wire bytes: avg {:.0}/frame → {:.0} bytes/s at the observed rate",
            full_bytes as f64 / surfaces as f64,
            full_bytes as f64 / elapsed,
        );
    }
    if !delta_sizes.is_empty() {
        delta_sizes.sort_unstable();
        let total: u64 = delta_sizes.iter().sum();
        println!(
            "delta wire bytes (SurfaceDelta → base64 → EndpointControl): {} consecutive pairs, median {}, p95 {}, avg {:.0} → {:.0} bytes/s at the observed rate",
            delta_sizes.len(),
            percentile(&delta_sizes, 0.5),
            percentile(&delta_sizes, 0.95),
            total as f64 / delta_sizes.len() as f64,
            total as f64 / elapsed,
        );
    }
    Ok(())
}

/// Nearest-rank percentile over a sorted slice.
fn percentile(sorted: &[u64], fraction: f64) -> u64 {
    let index = ((sorted.len() as f64 * fraction).ceil() as usize)
        .saturating_sub(1)
        .min(sorted.len() - 1);
    sorted[index]
}

/// Builds the delta a sender would emit between two consecutive grids and
/// returns the full EndpointControl wire payload. `None` on geometry mismatch.
fn delta_wire_size(base: &PaneSurfaceFrame, next: &PaneSurfaceFrame) -> Option<u64> {
    if (base.frame.width, base.frame.height) != (next.frame.width, next.frame.height) {
        return None;
    }
    let rows = diff_rows(&base.frame, &next.frame);
    let mut surface = next.clone();
    surface.frame.cells.clear();
    let popup_cells = surface.popup.as_mut().map(|popup| {
        let cells = std::mem::take(&mut popup.frame.cells);
        GridUpdate::Replace(cells)
    });
    let delta = SurfaceDelta {
        base_projection_revision: base.projection_revision,
        base_surface_revision: base.surface_revision,
        surface,
        rows,
        popup_cells,
    };
    let data = delta.encode().ok()?;
    let wire = encode_message(
        &ServerMessage::EndpointControl { kind: MESSAGE_KIND.into(), data },
        MAX_GRAPHICS_FRAME_SIZE,
    )
    .ok()?;
    Some((wire.len() - 4) as u64)
}

/// Changed spans, one row per span, strictly ordered, as `PaneSurfacePatchRow`.
fn diff_rows(base: &FrameData, next: &FrameData) -> Vec<PaneSurfacePatchRow> {
    let width = usize::from(base.width);
    let mut rows = Vec::new();
    let mut run: Option<(usize, usize, Vec<CellData>)> = None; // (y, x, cells)
    for y in 0..usize::from(base.height) {
        for x in 0..width {
            let index = y * width + x;
            let changed = base.cells.get(index) != next.cells.get(index);
            match (&mut run, changed) {
                (None, true) => {
                    let cells = next.cells.get(index).cloned().into_iter().collect();
                    run = Some((y, x, cells));
                }
                (Some((_, _, cells)), true) => {
                    if let Some(cell) = next.cells.get(index) {
                        cells.push(cell.clone());
                    }
                }
                (Some((_, _, _)), false) => {
                    if let Some(span) = run.take() {
                        rows.push(PaneSurfacePatchRow { x: span.1 as u16, y: span.0 as u16, cells: span.2 });
                    }
                }
                (None, false) => {}
            }
        }
        if let Some(span) = run.take() {
            rows.push(PaneSurfacePatchRow { x: span.1 as u16, y: span.0 as u16, cells: span.2 });
        }
    }
    rows
}

fn cursor_shape(cursor: Option<&CursorState>) -> &'static str {
    match cursor.map(|c| c.shape).unwrap_or(0) {
        1 => "blinking block",
        2 => "steady block",
        3 => "blinking underline",
        4 => "steady underline",
        5 => "blinking bar",
        6 => "steady bar",
        _ => "terminal default",
    }
}

/// Resolves one packed wire colour to RGB. See the module comment for the
/// palette source; named 0 = Reset resolves to the default fg or bg.
fn resolve_color(color: u32, is_fg: bool) -> [u8; 3] {
    match color >> 24 {
        0x00 => match color & 0xFF {
            0 => if is_fg { FG_DEFAULT } else { BG_DEFAULT },
            value if (1..=16).contains(&value) => NAMED[(value - 1) as usize],
            _ => if is_fg { FG_DEFAULT } else { BG_DEFAULT },
        },
        0x01 => {
            let index = (color & 0xFF) as usize;
            if index < 16 { NAMED[index] } else { xterm256(index) }
        }
        0x02 => {
            [(color >> 16) as u8, (color >> 8) as u8, color as u8]
        }
        _ => if is_fg { FG_DEFAULT } else { BG_DEFAULT },
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

fn pack(rgb: [u8; 3]) -> u32 {
    u32::from(rgb[0]) << 16 | u32::from(rgb[1]) << 8 | u32::from(rgb[2])
}

fn kind_prefix(color: u32) -> char {
    match color >> 24 {
        0x00 => '*',
        0x01 => 'i',
        0x02 => 'r',
        _ => '?',
    }
}

fn hex(rgb: [u8; 3]) -> String {
    format!("{:02X}{:02X}{:02X}", rgb[0], rgb[1], rgb[2])
}

/// Attribute letters shown in the text dump; only set bits are printed.
fn flag_letters(modifier: u16) -> String {
    let mut flags = String::new();
    for (bit, letter) in [
        (MOD_BOLD, 'b'),
        (MOD_DIM, 'd'),
        (MOD_ITALIC, 'i'),
        (MOD_UNDERLINED, 'u'),
        (MOD_SLOW_BLINK, 'l'),
        (MOD_RAPID_BLINK, 'p'),
        (MOD_REVERSED, 'v'),
        (MOD_HIDDEN, 'h'),
        (MOD_CROSSED_OUT, 'x'),
    ] {
        if modifier & bit != 0 {
            flags.push(letter);
        }
    }
    let style = (modifier & UNDERLINE_STYLE_MASK) >> 12;
    if style != 0 {
        flags.push_str(&format!("w{style}"));
    }
    flags
}

fn print_text_dump(frame: &FrameData, max_rows: usize) {
    let width = usize::from(frame.width);
    println!("\n=== text dump, first {max_rows} rows (cell = sym|kind+hexfg|kind+hexbg[+flags], '·' = empty symbol, '*' = default/reset) ===");
    for row in frame.cells.chunks(width).take(max_rows) {
        let rendered: Vec<String> = row
            .iter()
            .map(|cell| {
                let symbol = if cell.symbol.is_empty() { "·" } else { cell.symbol.as_str() };
                let mut entry = format!(
                    "{symbol}|{}{}|{}{}",
                    kind_prefix(cell.fg),
                    hex(resolve_color(cell.fg, true)),
                    kind_prefix(cell.bg),
                    hex(resolve_color(cell.bg, false)),
                );
                let flags = flag_letters(cell.modifier);
                if !flags.is_empty() {
                    entry.push('+');
                    entry.push_str(&flags);
                }
                if let Some(index) = cell.hyperlink {
                    entry.push_str(&format!("%L{index}"));
                }
                if cell.skip {
                    entry.push_str("%s");
                }
                entry
            })
            .collect();
        println!("{}", rendered.join(" "));
    }
}

/// Truecolor ANSI dump of the whole frame: one line per row, SGR emitted only
/// when fg/bg/reverse change. A human compares this with the PNG.
fn print_ansi_dump(frame: &FrameData) {
    let width = usize::from(frame.width);
    println!("\n=== ANSI dump of the full frame ===");
    for row in frame.cells.chunks(width) {
        let mut line = String::new();
        let mut active: Option<([u8; 3], [u8; 3], bool)> = None;
        for cell in row {
            let (mut fg, mut bg) = (resolve_color(cell.fg, true), resolve_color(cell.bg, false));
            if cell.modifier & MOD_REVERSED != 0 {
                std::mem::swap(&mut fg, &mut bg);
            }
            let reversed = cell.modifier & MOD_REVERSED != 0;
            if active != Some((fg, bg, reversed)) {
                line.push_str(&format!("\x1b[38;2;{};{};{}m", fg[0], fg[1], fg[2]));
                line.push_str(&format!("\x1b[48;2;{};{};{}m", bg[0], bg[1], bg[2]));
                active = Some((fg, bg, reversed));
            }
            line.push_str(if cell.symbol.is_empty() { " " } else { &cell.symbol });
        }
        line.push_str("\x1b[0m");
        println!("{line}");
    }
}

/// Draws each cell as a filled rectangle in its background with the glyph
/// approximated by a solid block in the foreground colour (swapped when
/// reversed). Wide glyphs span two cells; empty symbols draw background only.
fn write_png(path: &str, cell_w: u32, cell_h: u32, frame: &FrameData) -> Result<()> {
    let (cols, rows) = (usize::from(frame.width), usize::from(frame.height));
    let (width, height) = (cols * cell_w as usize, rows * cell_h as usize);
    let mut pixels = vec![0u8; width * height * 4];
    let mut put = |x: usize, y: usize, rgb: [u8; 3]| {
        let index = (y * width + x) * 4;
        pixels[index..index + 3].copy_from_slice(&rgb);
        pixels[index + 3] = 0xFF;
    };
    for (index, cell) in frame.cells.iter().enumerate() {
        let (col, row) = (index % cols, index / cols);
        let (mut fg, mut bg) = (resolve_color(cell.fg, true), resolve_color(cell.bg, false));
        if cell.modifier & MOD_REVERSED != 0 {
            std::mem::swap(&mut fg, &mut bg);
        }
        let span = cell.symbol.width().max(1) as usize;
        for dy in 0..cell_h as usize {
            for dx in 0..span * cell_w as usize {
                let (x, y) = (col * cell_w as usize + dx, row * cell_h as usize + dy);
                if x < width && y < height {
                    put(x, y, bg);
                }
            }
        }
        if cell.symbol.is_empty() {
            continue;
        }
        let margin_w = (cell_w as usize / 4).max(1);
        let margin_h = (cell_h as usize / 4).max(1);
        let (block_w, block_h) = (span * cell_w as usize - margin_w, cell_h as usize - margin_h);
        for dy in 0..block_h {
            for dx in 0..block_w {
                let (x, y) = (col * cell_w as usize + margin_w + dx, row * cell_h as usize + margin_h + dy);
                if x < width && y < height {
                    put(x, y, fg);
                }
            }
        }
    }
    if let Some(parent) = Path::new(path).parent() {
        create_dir_all(parent).context("creating the PNG output directory")?;
    }
    // Numeric check, not "looks fine": count pixels equal to the most common
    // cell background and any non-opaque pixels (must be zero).
    let mut bg_counts: std::collections::HashMap<[u8; 3], usize> = std::collections::HashMap::new();
    for cell in &frame.cells {
        let bg = if cell.modifier & MOD_REVERSED != 0 {
            resolve_color(cell.fg, true)
        } else {
            resolve_color(cell.bg, false)
        };
        *bg_counts.entry(bg).or_default() += 1;
    }
    let common_bg = bg_counts
        .iter()
        .max_by_key(|(_, count)| **count)
        .map(|(bg, _)| *bg);
    let (mut bg_pixels, mut transparent) = (0usize, 0usize);
    for pixel in pixels.chunks_exact(4) {
        if pixel[3] != 0xFF {
            transparent += 1;
        }
        if Some([pixel[0], pixel[1], pixel[2]]) == common_bg {
            bg_pixels += 1;
        }
    }
    match common_bg {
        Some(bg) => println!(
            "PNG check: {} px match the most common cell bg #{:02X}{:02X}{:02X} of {} total ({:.1}%), {} non-opaque px (must be 0)",
            bg_pixels,
            bg[0], bg[1], bg[2],
            pixels.len() / 4,
            100.0 * bg_pixels as f64 / (pixels.len() / 4) as f64,
            transparent,
        ),
        None => println!("PNG check: frame has no cells"),
    }
    let file = File::create(path).context("creating the PNG file")?;
    let mut encoder = Encoder::new(file, width as u32, height as u32);
    encoder.set_color(ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().context("writing the PNG header")?;
    writer.write_image_data(&pixels).context("writing the PNG pixels")?;
    Ok(())
}
