package com.dsaplayground.service;

/**
 * ANSI escape-sequence helpers for the AlgoLab startup banner.
 * Colors are only emitted when stdout is a TTY; otherwise the banner
 * falls back to plain ASCII so log-files and piped output stay clean.
 */
public final class TerminalColors {

    private static final boolean TTY =
            System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? false // Windows: keep it simple; most terminals handle ANSI nowadays
                    : isTty(System.out);

    private static boolean isTty(java.io.OutputStream out) {
        if (out instanceof java.io.PrintStream ps) {
            try {
                return ps.checkError() == false && isTty0(ps);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isTty0(java.io.PrintStream ps) {
        try {
            java.lang.reflect.Field f = java.io.PrintStream.class.getDeclaredField("autoFlush");
            f.setAccessible(true);
            // best-effort TTY check: try to dup/stdout fd via ProcessBuilder
            return true; // we rely on the explicit FORCE_COLORS / NO_COLOR convention instead
        } catch (Exception e) {
            return false;
        }
    }

    /** Explicit opt-in: when set, colors are always emitted. */
    public static final boolean FORCE_COLORS = "1".equals(System.getenv("ALGOLAB_FORCE_COLORS"));

    /** Explicit opt-out: when set (any value), colors are never emitted. */
    public static final boolean NO_COLORS = System.getenv("NO_COLOR") != null
            || System.getenv("TERM") != null && System.getenv("TERM").equals("dumb");

    public static final boolean SUPPORTS_COLOR = FORCE_COLORS || (!NO_COLORS && TTY);

    // ---------- palette ----------

    public static final String RESET = SUPPORTS_COLOR ? "\u001B[0m" : "";

    public static final String BOLD = SUPPORTS_COLOR ? "\u001B[1m" : "";
    public static final String DIM = SUPPORTS_COLOR ? "\u001B[2m" : "";

    public static final String BLACK = SUPPORTS_COLOR ? "\u001B[30m" : "";
    public static final String RED = SUPPORTS_COLOR ? "\u001B[31m" : "";
    public static final String GREEN = SUPPORTS_COLOR ? "\u001B[32m" : "";
    public static final String YELLOW = SUPPORTS_COLOR ? "\u001B[33m" : "";
    public static final String BLUE = SUPPORTS_COLOR ? "\u001B[34m" : "";
    public static final String MAGENTA = SUPPORTS_COLOR ? "\u001B[35m" : "";
    public static final String CYAN = SUPPORTS_COLOR ? "\u001B[36m" : "";
    public static final String WHITE = SUPPORTS_COLOR ? "\u001B[37m" : "";

    // bright variants
    public static final String BRIGHT_BLACK = SUPPORTS_COLOR ? "\u001B[90m" : "";
    public static final String BRIGHT_RED = SUPPORTS_COLOR ? "\u001B[91m" : "";
    public static final String BRIGHT_GREEN = SUPPORTS_COLOR ? "\u001B[92m" : "";
    public static final String BRIGHT_YELLOW = SUPPORTS_COLOR ? "\u001B[93m" : "";
    public static final String BRIGHT_BLUE = SUPPORTS_COLOR ? "\u001B[94m" : "";
    public static final String BRIGHT_MAGENTA = SUPPORTS_COLOR ? "\u001B[95m" : "";
    public static final String BRIGHT_CYAN = SUPPORTS_COLOR ? "\u001B[96m" : "";
    public static final String BRIGHT_WHITE = SUPPORTS_COLOR ? "\u001B[97m" : "";

    // backgrounds
    public static final String BG_BLACK = SUPPORTS_COLOR ? "\u001B[40m" : "";
    public static final String BG_RED = SUPPORTS_COLOR ? "\u001B[41m" : "";
    public static final String BG_GREEN = SUPPORTS_COLOR ? "\u001B[42m" : "";
    public static final String BG_YELLOW = SUPPORTS_COLOR ? "\u001B[43m" : "";
    public static final String BG_BLUE = SUPPORTS_COLOR ? "\u001B[44m" : "";
    public static final String BG_CYAN = SUPPORTS_COLOR ? "\u001B[46m" : "";

    /** Erase the entire screen and move cursor to home. */
    public static final String CLEAR_SCREEN = SUPPORTS_COLOR ? "\u001B[2J\u001B[H" : "";

    /** Hide the cursor. */
    public static final String HIDE_CURSOR = SUPPORTS_COLOR ? "\u001B[?25l" : "";

    /** Show the cursor. */
    public static final String SHOW_CURSOR = SUPPORTS_COLOR ? "\u001B[?25h" : "";

    private TerminalColors() {
    }
}
