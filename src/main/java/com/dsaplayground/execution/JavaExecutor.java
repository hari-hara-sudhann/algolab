package com.dsaplayground.execution;

import java.util.List;
import java.util.function.Consumer;

/**
 * A mechanism that can run a Java notebook against its test cases and hand
 * back structured {@link TestCaseResult}s. Local JDK execution and Judge0
 * execution are two interchangeable implementations of this interface — the
 * rest of AlgoLab only depends on {@link JavaExecutionService} and never on a
 * concrete executor.
 */
public interface JavaExecutor {

    /** Stable machine id: {@code "local"} or {@code "judge0"}. */
    String id();

    /** Human label for status displays, e.g. {@code "Local JDK"}. */
    String label();

    /**
     * Whether this executor can actually run code right now (a local JDK
     * exists / Judge0 is configured).
     */
    boolean isAvailable();

    /**
     * Run every test case against {@code code}, handing each result to
     * {@code sink} as soon as that case finishes (streaming contract — the
     * HTTP layer flushes one NDJSON line per case).
     */
    void runToSink(String code, List<TestCase> testCases, ExecOptions options,
                   Consumer<TestCaseResult> sink);

    /**
     * Compile {@code code} and return editor markers. Only the local executor
     * implements this (Judge0 is an execution fallback, not a linting
     * service); callers treat the empty list as "no diagnostics available".
     */
    default List<Marker> lint(String code, ExecOptions options) {
        return List.of();
    }
}