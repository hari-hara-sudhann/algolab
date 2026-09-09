package com.dsaplayground.execution;

/**
 * A single test case for a notebook: an id, a display name, the stdin fed to
 * the program, and an optional expected stdout. {@code expected == null}
 * means the case has no expected output (the PASS/FAIL verdict is skipped).
 *
 * <p>This is the notebook's test-case shape at the execution boundary; both
 * executors (local JDK and Judge0) consume and produce it.
 */
public record TestCase(String id, String name, String stdin, String expected) {
}