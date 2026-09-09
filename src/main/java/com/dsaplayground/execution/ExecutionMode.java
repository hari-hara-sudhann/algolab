package com.dsaplayground.execution;

/**
 * Which mechanism is executing Java right now. The UI shows this as a small
 * status badge — it is informational, never a feature the user has to care
 * about.
 */
public enum ExecutionMode {

    /** Running with a JDK discovered on this machine. */
    LOCAL("local"),

    /** No usable local JDK — running through the hosted Judge0 API. */
    JUDGE0("judge0"),

    /** No local JDK and Judge0 is not configured: nothing can run. */
    NONE("none");

    private final String id;

    ExecutionMode(String id) {
        this.id = id;
    }

    /** Machine-readable id used in the API response ({@code "local" | "judge0" | "none"}). */
    public String id() {
        return id;
    }
}