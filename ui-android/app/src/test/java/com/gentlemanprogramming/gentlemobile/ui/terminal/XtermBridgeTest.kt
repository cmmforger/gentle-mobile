package com.gentlemanprogramming.gentlemobile.ui.terminal

import com.gentlemanprogramming.gentlemobile.ui.theme.AppTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtermBridgeTest {

    private fun hex(s: String): Int = TerminalPalette.hex(s)

    /** A synthetic palette with distinct, recognisable values so the mapping
     *  is asserted on exact strings, never on the lighten() math. */
    private fun synthetic(): TermColors = TermColors(
        colors = listOf(
            "#000000", "#aa0000", "#00aa00", "#aa5500", "#0000aa",
            "#aa00aa", "#00aaaa", "#aaaaaa", "#555555", "#ff5555",
            "#55ff55", "#ffff55", "#5555ff", "#ff55ff", "#55ffff", "#ffffff",
        ).map(::hex),
        background = hex("#101010"),
        foreground = hex("#f0f0f0"),
    )

    // --- theme -> xterm JSON mapping -------------------------------------

    @Test
    fun theme_maps_background_foreground_and_cursor_surfaces() {
        val t = XtermTheme.from(synthetic())
        assertEquals("#101010", t.background)
        assertEquals("#f0f0f0", t.foreground)
        assertEquals("#f0f0f0", t.cursor)
        assertEquals("#101010", t.cursorAccent)
        assertEquals("rgba(240,240,240,0.4)", t.selectionBackground)
    }

    @Test
    fun theme_maps_the_sixteen_ansi_colours_to_hex() {
        val t = XtermTheme.from(synthetic())
        assertEquals("#000000", t.black)
        assertEquals("#aa0000", t.red)
        assertEquals("#00aa00", t.green)
        assertEquals("#aa5500", t.yellow)
        assertEquals("#0000aa", t.blue)
        assertEquals("#aa00aa", t.magenta)
        assertEquals("#00aaaa", t.cyan)
        assertEquals("#aaaaaa", t.white)
        assertEquals("#555555", t.brightBlack)
        assertEquals("#ff5555", t.brightRed)
        assertEquals("#55ff55", t.brightGreen)
        assertEquals("#ffff55", t.brightYellow)
        assertEquals("#5555ff", t.brightBlue)
        assertEquals("#ff55ff", t.brightMagenta)
        assertEquals("#55ffff", t.brightCyan)
        assertEquals("#ffffff", t.brightWhite)
    }

    @Test
    fun theme_json_is_exactly_the_shape_xterm_reads() {
        val json = XtermTheme.from(synthetic()).toJson()
        assertEquals(
            "{\"background\":\"#101010\",\"foreground\":\"#f0f0f0\"," +
                "\"cursor\":\"#f0f0f0\",\"cursorAccent\":\"#101010\"," +
                "\"selectionBackground\":\"rgba(240,240,240,0.4)\"," +
                "\"black\":\"#000000\",\"red\":\"#aa0000\",\"green\":\"#00aa00\"," +
                "\"yellow\":\"#aa5500\",\"blue\":\"#0000aa\",\"magenta\":\"#aa00aa\"," +
                "\"cyan\":\"#00aaaa\",\"white\":\"#aaaaaa\",\"brightBlack\":\"#555555\"," +
                "\"brightRed\":\"#ff5555\",\"brightGreen\":\"#55ff55\"," +
                "\"brightYellow\":\"#ffff55\",\"brightBlue\":\"#5555ff\"," +
                "\"brightMagenta\":\"#ff55ff\",\"brightCyan\":\"#55ffff\"," +
                "\"brightWhite\":\"#ffffff\"}",
            json,
        )
    }

    @Test
    fun paper_is_a_light_terminal_through_the_mapping() {
        val t = XtermTheme.from(TerminalPalette.forTheme(AppTheme.PAPER))
        assertEquals("#ffffff", t.background)
        assertEquals("#36242c", t.foreground)
    }

    @Test
    fun every_theme_maps_to_a_full_hex_palette() {
        for (theme in AppTheme.entries) {
            val t = XtermTheme.from(TerminalPalette.forTheme(theme))
            val values = listOf(
                t.background, t.foreground, t.black, t.red, t.green, t.yellow,
                t.blue, t.magenta, t.cyan, t.white, t.brightBlack, t.brightRed,
                t.brightGreen, t.brightYellow, t.brightBlue, t.brightMagenta,
                t.brightCyan, t.brightWhite,
            )
            for (v in values) {
                assertEquals("$theme value $v must be #rrggbb", 7, v.length)
                assertEquals("$theme value $v must start with #", '#', v[0])
            }
        }
    }

    // --- byte encoding ----------------------------------------------------

    @Test
    fun base64_round_trips_utf8_and_binary() {
        val utf8 = "héllo 界 💥\r\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(utf8, XtermCodec.decode(XtermCodec.encode(utf8)))

        val binary = ByteArray(256) { it.toByte() }
        assertArrayEquals(binary, XtermCodec.decode(XtermCodec.encode(binary)))

        assertArrayEquals(ByteArray(0), XtermCodec.decode(XtermCodec.encode(ByteArray(0))))
    }

    @Test
    fun base64_never_embeds_a_raw_quote_or_newline() {
        val encoded = XtermCodec.encode("a\"b\nc".toByteArray(Charsets.UTF_8))
        assertEquals(-1, encoded.indexOf('"'))
        assertEquals(-1, encoded.indexOf('\n'))
    }

    // --- message framing --------------------------------------------------

    @Test
    fun write_message_carries_the_base64_body() {
        assertEquals(
            """{"type":"write","data":"aGVsbG8="}""",
            XtermMessages.write(XtermCodec.encode("hello".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun theme_and_cursor_messages_are_exact() {
        assertEquals(
            """{"type":"theme","theme":{"background":"#101010"}}""",
            XtermMessages.theme("""{"background":"#101010"}"""),
        )
        assertEquals(
            """{"type":"cursor","cursor":"bar","blink":true}""",
            XtermMessages.cursor("bar", true),
        )
        assertEquals("""{"type":"focus"}""", XtermMessages.focus())
    }

    @Test
    fun json_escape_covers_quote_backslash_and_control_chars() {
        assertEquals("a\\\"b\\\\c\\nd", XtermMessages.jsonEscape("a\"b\\c\nd"))
        assertEquals("\"a\\\"b\\\\c\\nd\"", XtermMessages.jsonQuote("a\"b\\c\nd"))
        assertEquals("plain", XtermMessages.jsonEscape("plain"))
    }

    @Test
    fun init_message_embeds_theme_font_and_cursor() {
        val json = XtermMessages.init(
            themeJson = """{"background":"#101010"}""",
            fontCss = "@font-face { font-family: 'x'; }",
            mode = "block",
            blink = false,
        )
        assertEquals(
            """{"type":"init","theme":{"background":"#101010"},""" +
                """"font":"@font-face { font-family: 'x'; }","cursor":"block","blink":false}""",
            json,
        )
    }

    // --- resize decision --------------------------------------------------

    @Test
    fun resize_is_decided_only_when_the_grid_changed() {
        assertNull(XtermResize.decide(80, 24, 80, 24))
        assertEquals(XtermGrid(80, 12), XtermResize.decide(80, 24, 80, 12))
        assertEquals(XtermGrid(40, 24), XtermResize.decide(80, 24, 40, 24))
        assertEquals(XtermGrid(40, 12), XtermResize.decide(80, 24, 40, 12))
    }

    @Test
    fun resize_clamps_a_broken_report_to_a_real_terminal() {
        assertEquals(XtermGrid(2, 1), XtermResize.decide(80, 24, 0, 0))
        assertEquals(XtermGrid(2, 1), XtermResize.decide(80, 24, -5, -5))
    }

    // --- cursor mapping ---------------------------------------------------

    @Test
    fun cursor_modes_map_to_xterm_styles() {
        assertEquals("block", CursorMode.BLOCK.xtermStyle())
        assertEquals("underline", CursorMode.UNDERLINE.xtermStyle())
        assertEquals("bar", CursorMode.BAR.xtermStyle())
    }

    // --- font face --------------------------------------------------------

    @Test
    fun font_face_registers_regular_and_bold_weights() {
        val css = XtermFont.faceCss("cmVndWxhcg==", "Ym9sZA==")
        assertEquals(
            "@font-face { font-family: 'gentle-terminal'; " +
                "src: url('data:font/ttf;base64,cmVndWxhcg==') format('truetype'); " +
                "font-weight: normal; font-style: normal; } " +
                "@font-face { font-family: 'gentle-terminal'; " +
                "src: url('data:font/ttf;base64,Ym9sZA==') format('truetype'); " +
                "font-weight: bold; font-style: normal; }",
            css,
        )
    }
}
