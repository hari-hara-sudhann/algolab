package com.dsaplayground.execution;

/**
 * The execution-relevant options for a run: which JDK home the user picked in
 * the UI (may be {@code null} for "use the default") and the Java language
 * level to target (may be {@code null} for "the JDK's own"). Judge0 ignores
 * these details — it runs whatever Java image the server has — but the
 * interface keeps one option bag so callers don't branch per executor.
 */
public record ExecOptions(String jdkHome, Integer release) {

    public static ExecOptions of(String jdkHome, Integer release) {
        return new ExecOptions(jdkHome, release);
    }
}