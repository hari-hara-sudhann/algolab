package com.dsaplayground.execution;

/**
 * The structured outcome of running one {@link TestCase}. Both the local JDK
 * executor and the Judge0 executor produce this exact shape, so the rest of
 * AlgoLab (controllers, frontend) never needs to know where the code ran.
 *
 * @param id         the test case id this result belongs to
 * @param status     one of {@code PASS}, {@code FAIL}, {@code COMPILE_ERROR},
 *                   {@code TIMEOUT}, {@code ERROR} (plus {@code RUNNING} for
 *                   placeholders the UI paints while a case is in flight)
 * @param stdout     the program's standard output
 * @param stderr     the program's standard error (or runtime-error detail)
 * @param durationMs wall-clock execution time in milliseconds
 * @param error      a human-readable error message, or {@code null} when the
 *                   case ran cleanly
 */
public record TestCaseResult(
        String id,
        String status,
        String stdout,
        String stderr,
        Long durationMs,
        String error) {

    /**
     * Compare actual stdout against expected, normalising the trailing
     * newline/CRLF differences that trip up handwritten expected output.
     * Both executors use this exact comparison so a PASS/FAIL verdict means
     * the same thing locally and on Judge0.
     */
    public static boolean matches(String actual, String expected) {
        if (actual == null && expected == null) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        String normActual = actual.replace("\r\n", "\n").stripTrailing();
        String normExpected = expected.replace("\r\n", "\n").stripTrailing();
        return normActual.equals(normExpected);
    }
}