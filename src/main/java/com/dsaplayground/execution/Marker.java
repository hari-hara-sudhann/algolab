package com.dsaplayground.execution;

/**
 * A single editor marker produced by compiling a snippet (the lint path).
 * Line and column are 1-based; severity is {@code "error"} or
 * {@code "warning"}. Produced by the local javac pass; never by Judge0
 * (remote linting is not part of the fallback contract).
 */
public record Marker(int line, int column, String severity, String message) {
}