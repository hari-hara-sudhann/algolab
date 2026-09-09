package com.dsaplayground.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TestCaseRunner {

    private static final Logger log = LoggerFactory.getLogger(TestCaseRunner.class);

    private static final int MAX_OUTPUT_BYTES = 512 * 1024; // 512 KB

    public record TestCase(String id, String name, String stdin, String expected) {}
    public record TestCaseResult(String id, String status, String stdout, String stderr, Long durationMs, String error) {}

    public TestCaseResult runCase(Path workingDir, String mainClassName, TestCase tc, Path javaExec, long timeoutMs) {
        long start = System.currentTimeMillis();
        log.trace("TestCaseRunner.runCase(id='{}', className='{}', timeout={}ms) ENTRY",
                tc.id(), mainClassName, timeoutMs);
        log.trace("TestCaseRunner.runCase(): workingDir={} javaExec={} mainClassName={}",
                workingDir, javaExec, mainClassName);
        log.trace("TestCaseRunner.runCase(): stdinLen={} expected={}",
                tc.stdin() == null ? "null" : tc.stdin().length(),
                tc.expected() == null ? "null" : tc.expected());
        Process process = null;
        try {
            log.trace("TestCaseRunner.runCase(): building ProcessBuilder");
            ProcessBuilder pb = new ProcessBuilder(
                    javaExec.toString(),
                    "-cp",
                    workingDir.toAbsolutePath().toString(),
                    mainClassName
            );
            log.trace("TestCaseRunner.runCase(): ProcessBuilder command: {}", pb.command());
            pb.directory(workingDir.toFile());
            log.trace("TestCaseRunner.runCase(): working directory set to: {}", workingDir.toFile().getAbsolutePath());

            log.trace("TestCaseRunner.runCase(): starting process...");
            process = pb.start();
            log.trace("TestCaseRunner.runCase(): process started pid={}", process.pid());

            // Write stdin asynchronously
            log.trace("TestCaseRunner.runCase(): handling stdin");
            OutputStream os = process.getOutputStream();
            if (tc.stdin() != null && !tc.stdin().isEmpty()) {
                byte[] stdinBytes = tc.stdin().getBytes(StandardCharsets.UTF_8);
                log.trace("TestCaseRunner.runCase(): writing {} bytes to stdin", stdinBytes.length);
                os.write(stdinBytes);
            } else {
                log.trace("TestCaseRunner.runCase(): stdin is empty/null");
            }
            os.flush();
            log.trace("TestCaseRunner.runCase(): stdin flushed");
            os.close();
            log.trace("TestCaseRunner.runCase(): stdin stream closed");

            // Read stdout and stderr asynchronously to avoid process deadlocks on large buffers
            log.trace("TestCaseRunner.runCase(): starting async stdout reader");
            CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
            log.trace("TestCaseRunner.runCase(): starting async stderr reader");
            CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

            log.trace("TestCaseRunner.runCase(): waiting for process with timeout {} ms", timeoutMs);
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - start;
            log.trace("TestCaseRunner.runCase(): waitFor finished={} durationMs={}", finished, durationMs);

            if (!finished) {
                log.warn("TestCaseRunner.runCase(): TIMEOUT after {} ms; destroying process pid={}", timeoutMs, process.pid());
                process.destroyForcibly();
                log.trace("TestCaseRunner.runCase(): process destroyed");
                String stdoutNow = stdoutFuture.getNow("");
                String stderrNow = stderrFuture.getNow("");
                log.trace("TestCaseRunner.runCase(): partial stdoutLen={} stderrLen={}",
                        stdoutNow.length(), stderrNow.length());
                TestCaseResult timeoutResult = new TestCaseResult(
                        tc.id(),
                        "TIMEOUT",
                        stdoutNow,
                        stderrNow,
                        durationMs,
                        "Time limit exceeded (" + timeoutMs + " ms)"
                );
                log.trace("TestCaseRunner.runCase() -> TIMEOUT: {}", timeoutResult.error());
                return timeoutResult;
            }

            log.trace("TestCaseRunner.runCase(): reading stdout and stderr");
            String stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(1, TimeUnit.SECONDS);
            log.trace("TestCaseRunner.runCase(): stdoutLen={} stderrLen={}", stdout.length(), stderr.length());
            int exitCode = process.exitValue();
            log.trace("TestCaseRunner.runCase(): exitCode={}", exitCode);

            if (exitCode != 0) {
                String errorMsg = stderr.isBlank() ? "Process exited with code " + exitCode : stderr.trim();
                log.trace("TestCaseRunner.runCase(): non-zero exit; errorMsg='{}'", errorMsg);
                TestCaseResult errorResult = new TestCaseResult(
                        tc.id(),
                        "ERROR",
                        stdout,
                        stderr,
                        durationMs,
                        errorMsg
                );
                log.trace("TestCaseRunner.runCase() -> ERROR: {}", errorResult.error());
                return errorResult;
            }

            // Exit code 0
            log.trace("TestCaseRunner.runCase(): process exited 0; checking expected");
            if (tc.expected() == null) {
                TestCaseResult passNoExpected = new TestCaseResult(
                        tc.id(),
                        "PASS",
                        stdout,
                        stderr,
                        durationMs,
                        null
                );
                log.trace("TestCaseRunner.runCase() -> PASS (no expected to match)");
                return passNoExpected;
            }

            log.trace("TestCaseRunner.runCase(): expected='{}'", tc.expected());
            boolean matches = matches(stdout, tc.expected());
            log.trace("TestCaseRunner.runCase(): matches={}", matches);
            TestCaseResult result = new TestCaseResult(
                    tc.id(),
                    matches ? "PASS" : "FAIL",
                    stdout,
                    stderr,
                    durationMs,
                    null
            );
            log.trace("TestCaseRunner.runCase() -> {}: stdoutLen={} stderrLen={}",
                    result.status(), stdout.length(), stderr.length());
            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("TestCaseRunner.runCase(id='{}'): unhandled exception after {} ms: {}", tc.id(), durationMs, e.getMessage(), e);
            if (process != null && process.isAlive()) {
                log.trace("TestCaseRunner.runCase(): destroying alive process pid={}", process.pid());
                process.destroyForcibly();
            }
            TestCaseResult errorResult = new TestCaseResult(
                    tc.id(),
                    "ERROR",
                    "",
                    "",
                    durationMs,
                    "Execution error: " + e.getMessage()
            );
            log.trace("TestCaseRunner.runCase() -> ERROR (exception): {}", errorResult.error());
            return errorResult;
        }
    }

    private CompletableFuture<String> readStreamAsync(InputStream is) {
        log.trace("TestCaseRunner.readStreamAsync() ENTRY");
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            log.trace("TestCaseRunner.readStreamAsync(): reader task started");
            try (is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                int iterations = 0;
                while ((read = is.read(buffer)) != -1) {
                    iterations++;
                    log.trace("TestCaseRunner.readStreamAsync(): read iteration {}: {} bytes (total so far: {})",
                            iterations, read, total);
                    if (total < MAX_OUTPUT_BYTES) {
                        int toWrite = Math.min(read, MAX_OUTPUT_BYTES - total);
                        log.trace("TestCaseRunner.readStreamAsync(): writing {} of {} bytes (cap at {})", toWrite, read, MAX_OUTPUT_BYTES);
                        baos.write(buffer, 0, toWrite);
                        total += toWrite;
                    } else {
                        log.trace("TestCaseRunner.readStreamAsync(): output cap reached ({}); truncating remaining {} bytes", MAX_OUTPUT_BYTES, read);
                    }
                }
                log.trace("TestCaseRunner.readStreamAsync(): stream exhausted; total={} iterations={} cap={}", total, iterations, MAX_OUTPUT_BYTES);
                String result = baos.toString(StandardCharsets.UTF_8);
                log.trace("TestCaseRunner.readStreamAsync(): result string len={} (elapsed {} ms)",
                        result.length(), (System.nanoTime() - start) / 1_000_000);
                return result;
            } catch (Exception e) {
                log.trace("TestCaseRunner.readStreamAsync(): exception: {}", e.getMessage());
                return "";
            }
        });
    }

    public static boolean matches(String actual, String expected) {
        log.trace("TestCaseRunner.matches() ENTRY");
        if (actual == null && expected == null) {
            log.trace("TestCaseRunner.matches(): both null -> true");
            return true;
        }
        if (actual == null || expected == null) {
            log.trace("TestCaseRunner.matches(): one null -> false");
            return false;
        }
        log.trace("TestCaseRunner.matches(): actualLen={} expectedLen={}", actual.length(), expected.length());
        String normActual = actual.replace("\r\n", "\n").stripTrailing();
        String normExpected = expected.replace("\r\n", "\n").stripTrailing();
        log.trace("TestCaseRunner.matches(): normActualLen={} normExpectedLen={}", normActual.length(), normExpected.length());
        boolean result = normActual.equals(normExpected);
        log.trace("TestCaseRunner.matches() -> {}", result);
        return result;
    }
}
