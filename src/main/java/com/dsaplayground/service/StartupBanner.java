package com.dsaplayground.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Draws the AlgoLab terminal startup experience:
 *   1. Clears the screen (if stdout is a TTY).
 *   2. Hides the cursor during the draw.
 *   3. Prints a big ANSI logo, version line, and a status panel with workspace,
 *      JDK, and server URL.
 *   4. Shows a graceful plain-ASCII fallback when colors/TTY are unavailable.
 *
 * This is invoked early via a {@link org.springframework.boot.CommandLineRunner}
 * so the user sees something polished before Spring's own logs (which go to
 * ~/.algolab.log) would clutter the terminal.
 */
public final class StartupBanner {

    private final boolean tty;

    public StartupBanner() {
        // Re-check TTY ourselves rather than relying on TerminalColors (which is static at class-load).
        this.tty = TerminalColors.SUPPORTS_COLOR;
    }

    /**
     * Draw the full startup screen. Safe to call even before the Spring context
     * is fully ready; any {@code IOException} from stdout is swallowed (the banner
     * is decorative, not critical).
     */
    public void draw(String workspaceRoot, String jdkDescription, String serverUrl,
                     String statusText, long startupMs) {
        try {
            clear();
            hideCursor();
            printAsciiLogo();
            println(); // blank line
            printStatusPanel(workspaceRoot, jdkDescription, serverUrl, statusText, startupMs);
            println();
            printStatusFooter(statusText);
            println();
            showCursor();
            println();
        } catch (IOException e) {
            // If stdout dies while we're painting, just stop.
            System.err.println("[algolab] Warning: could not draw startup banner: "
                    + e.getMessage());
        }
    }

    private void clear() throws IOException {
        if (tty) {
            System.out.print(TerminalColors.CLEAR_SCREEN);
        }
    }

    private void hideCursor() throws IOException {
        if (tty) {
            System.out.print(TerminalColors.HIDE_CURSOR);
        }
    }

    private void showCursor() throws IOException {
        if (tty) {
            System.out.print(TerminalColors.SHOW_CURSOR);
        }
    }

    private void printAsciiLogo() throws IOException {
        if (tty) {
            // Colored "AlgoLab" wordmark + tagline.
            String ansi = terminal(
                    TerminalColors.BRIGHT_CYAN,
                    TerminalColors.BRIGHT_BLUE,
                    TerminalColors.RESET);
            System.out.print(ansi);
        } else {
            System.out.print(asciiFallback());
        }
    }

    private String terminal(String... colors) {
        StringBuilder sb = new StringBuilder();
        for (String c : colors) {
            sb.append(c);
        }
        return sb.toString();
    }

    private void println() throws IOException {
        System.out.println();
    }

    private void printStatusPanel(String workspaceRoot, String jdkDescription,
                                  String serverUrl, String statusText, long startupMs)
            throws IOException {
        if (tty) {
            panelColorful(workspaceRoot, jdkDescription, serverUrl, statusText, startupMs);
        } else {
            panelPlain(workspaceRoot, jdkDescription, serverUrl, statusText, startupMs);
        }
    }

    private void panelColorful(String workspaceRoot, String jdkDescription,
                               String serverUrl, String statusText, long startupMs)
            throws IOException {
        // Box-drawing panel with labeled rows.
        String top = TerminalColors.BRIGHT_BLACK + "╭" + dimLine(48) + "╮" + TerminalColors.RESET;
        String midTop = TerminalColors.BRIGHT_BLACK + "│" + TerminalColors.RESET;
        String midBot = TerminalColors.BRIGHT_BLACK + "│" + TerminalColors.RESET;
        String bot = TerminalColors.BRIGHT_BLACK + "╰" + dimLine(48) + "╯" + TerminalColors.RESET;

        System.out.println(top);
        System.out.println(midTop + " " + pad(label(" WORKSPACE", TerminalColors.BRIGHT_CYAN),
                workspaceRoot, TerminalColors.WHITE) + " " + midBot);
        System.out.println(midTop + " " + pad(label(" JDK", TerminalColors.BRIGHT_GREEN),
                jdkDescription, TerminalColors.WHITE) + " " + midBot);
        System.out.println(midTop + " " + pad(label(" SERVER", TerminalColors.BRIGHT_MAGENTA),
                serverUrl, TerminalColors.WHITE) + " " + midBot);
        System.out.println(midTop + " " + pad(label(" STARTUP", TerminalColors.BRIGHT_YELLOW),
                statusText + "  (" + startupMs + "ms)", statusColor(statusText)) + " "
                + midBot);
        System.out.println(bot);
    }

    private void panelPlain(String workspaceRoot, String jdkDescription,
                            String serverUrl, String statusText, long startupMs)
            throws IOException {
        System.out.println("┌" + dimLine(48) + "┐");
        System.out.println("│ " + pad(labelPlain("WORKSPACE"), workspaceRoot) + " │");
        System.out.println("│ " + pad(labelPlain("JDK"), jdkDescription) + " │");
        System.out.println("│ " + pad(labelPlain("SERVER"), serverUrl) + " │");
        System.out.println("│ " + pad(labelPlain("STARTUP"),
                statusText + "  (" + startupMs + "ms)") + " │");
        System.out.println("└" + dimLine(48) + "┘");
    }

    private String dimLine(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('─');
        }
        return sb.toString();
    }

    private String pad(String label, String value, String valueColor) {
        String paddedValue = padTo(value, 42);
        return label + " " + valueColor + paddedValue + TerminalColors.RESET;
    }

    private String pad(String label, String value) {
        return label + " " + padTo(value, 42);
    }

    private String padTo(String s, int width) {
        if (s == null) {
            s = "";
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private String label(String text, String color) {
        return color + text + TerminalColors.RESET;
    }

    private String labelPlain(String text) {
        return text;
    }

    private String statusColor(String statusText) {
        if (statusText == null) {
            return TerminalColors.RESET;
        }
        String lower = statusText.toLowerCase();
        if (lower.contains("started") || lower.contains("ready") || lower.contains("listening")
                || lower.contains("up") || lower.contains("ok")) {
            return TerminalColors.BRIGHT_GREEN;
        }
        if (lower.contains("fail") || lower.contains("error") || lower.contains("crash")
                || lower.contains("exception")) {
            return TerminalColors.BRIGHT_RED;
        }
        if (lower.contains("warn") || lower.contains("degrad") || lower.contains("fallback")) {
            return TerminalColors.BRIGHT_YELLOW;
        }
        return TerminalColors.BRIGHT_CYAN;
    }

    private void printStatusFooter(String statusText) throws IOException {
        if (tty) {
            String hint = TerminalColors.DIM
                    + "→ point your browser at the SERVER URL above  ·  logs: ~/.algolab.log"
                    + TerminalColors.RESET;
            System.out.println(hint);
        } else {
            System.out.println("→ point your browser at the SERVER URL above  ·  logs: ~/.algolab.log"
                    + TerminalColors.RESET);
        }
    }

    private String asciiFallback() {
        return
                "  ___ _      _   _       _     _       _      _    _      _ \n"
                        + " / __| |    / \\ | |     / \\   / \\   / \\    / \\  / \\ | |\n"
                        + "| (_| |__ / _ \\| |__  / _ \\ / _ \\ / _ \\  / _ \\/ _ \\| |\n"
                        + " \\___|____/_/ \\_\\____/_/ \\_\\/_/ \\_\\/_/ \\_\\/_/ \\/_/ \\_\\_|\n"
                        + "\n"
                        + "  Local-first Java playground for DSA practice\n";
    }

    /** Eagerly print the ASCII fallback (used when TTY/color is unavailable). */
    public void printFallback() {
        try {
            System.out.print(asciiFallback());
        } catch (Exception ignored) {
        }
    }
}
