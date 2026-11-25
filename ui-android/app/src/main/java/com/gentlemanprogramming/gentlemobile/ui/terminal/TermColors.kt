package com.gentlemanprogramming.gentlemobile.ui.terminal

/** The terminal's 16 colours plus its default foreground/background — always
 *  from the active theme; the chrome never repaints it. */
class TermColors(
    val colors: List<Int>,
    val background: Int,
    val foreground: Int,
)
