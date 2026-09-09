package com.dsaplayground.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dsaplayground.service.JdkManager;
import com.dsaplayground.service.JdkManager.JdkInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes Java using a JDK discovered on this machine: writes the notebook
 * code to a temp workspace, compiles with the machine's own {@code javac}
 * (honouring the requested {@code --release}), then runs each test case as a
 * local subprocess with the machine's own {@code java}. Fully offline.
 *
 * <p>This is the preferred mechanism — the app only falls back to Judge0 when
 * no usable local JDK exists.
 */
@Service
public class LocalJavaExecutor implements JavaExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalJavaExecutor.class);

    private static final Pattern PUBLIC_CLASS_PATTERN =
            Pattern.compile("public\\s+(?:final\\s+)?class\\s+([A-Za-z0-9_]+)");
    private static final Pattern ANY_CLASS_PATTERN =
            Pattern.compile("class\\s+([A-Za-z0-9_]+)");

    /** javac diagnostic: path:line[:col]: error|warning: message */
    private static final Pattern DIAGNOSTIC_PATTERN = Pattern.compile(
            "^([A-Za-z0-9_.-]+):(\\d+)(?::(\\d+))?:\\s*(error|warning):\\s*(.+)$");

    private static final int MAX_MARKERS = 300;
    private static final int MAX_OUTPUT_BYTES = 512 * 1024; // 512 KB per stream
    private static final long COMPILE_TIMEOUT_MS = 15_000;
    private static final long CASE_TIMEOUT_MS = 5_000;

    private final JdkManager jdkManager;

    public LocalJavaExecutor(JdkManager jdkManager) {
        this.jdkManager = jdkManager;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String label() {
        return "Local JDK";
    }

    @Override
    public boolean isAvailable() {
        return !jdkManager.available().isEmpty();
    }

    /** The JDK used when the user hasn't picked a specific one (for status text). */
    public JdkInstallation defaultJdk() {
        return jdkManager.defaultInstallation().orElse(null);
    }

    @Override
    public void runToSink(String code, List<TestCase> testCases, ExecOptions options,
                          Consumer<TestCaseResult> sink) {
        List<TestCase> cases = testCases == null ? List.of() : testCases;
        if (code == null || code.isBlank()) {
            emitForEach(cases, sink, new TestCaseResult("", "ERROR", "", "", 0L, "Code is empty"));
            return;
        }
        JdkInstallation jdk = jdkManager.requireInstallation(options.jdkHome()).orElse(null);
        if (jdk == null) {
            emitForEach(cases, sink, new TestCaseResult("", "ERROR", "", "", 0L,
                    "No JDK found on this machine — local execution is unavailable"));
            return;
        }
        log.debug("LocalJavaExecutor: running with {} (Java {})", jdk.name(), jdk.version());

        String className = detectClassName(code);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("dsa-run-");
            Path javaFile = tempDir.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

            long compileStart = System.currentTimeMillis();
            String compileOutput = compile(jdk, tempDir, javaFile, options.release(), false);
            long compileDuration = System.currentTimeMillis() - compileStart;

            if (compileOutput != null) {
                String err = compileOutput.isBlank() ? "Compilation failed" : compileOutput.trim();
                emitForEach(cases, sink, new TestCaseResult("", "COMPILE_ERROR", "", "", compileDuration, err));
                return;
            }

            for (TestCase tc : cases) {
                sink.accept(runCase(tempDir, className, tc, jdk.javaBinary(), CASE_TIMEOUT_MS));
            }
        } catch (Exception e) {
            log.error("LocalJavaExecutor: execution failed: {}", e.toString(), e);
            emitForEach(cases, sink, new TestCaseResult("", "ERROR", "", "", 0L,
                    "Runner execution failed: " + e.getMessage()));
        } finally {
            if (tempDir != null) {
                deleteDirQuietly(tempDir);
            }
        }
    }

    @Override
    public List<Marker> lint(String code, ExecOptions options) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        JdkInstallation jdk = jdkManager.requireInstallation(options.jdkHome()).orElse(null);
        if (jdk == null) {
            log.debug("LocalJavaExecutor: lint skipped — no JDK available");
            return List.of();
        }
        String className = detectClassName(code);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("dsa-lint-");
            Path javaFile = tempDir.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

            String output = compile(jdk, tempDir, javaFile, options.release(), true);
            if (output == null) {
                return List.of(); // compiled cleanly
            }
            return parseDiagnostics(output);
        } catch (Exception e) {
            log.debug("LocalJavaExecutor: lint failed: {}", e.toString());
            return List.of();
        } finally {
            if (tempDir != null) {
                deleteDirQuietly(tempDir);
            }
        }
    }

    /* ---------------- compile ---------------- */

    /**
     * Run {@code javac} in {@code tempDir}. Returns {@code null} on success,
     * or the merged compiler output when compilation failed/timed out.
     */
    private String compile(JdkInstallation jdk, Path tempDir, Path javaFile,
                           Integer release, boolean lint) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add(jdk.javacBinary().toString());
        if (lint) {
            args.add("-Xlint:all");
        }
        args.add("-encoding");
        args.add("UTF-8");
        Integer level = release;
        if (level != null && level > 0 && level < jdk.version()) {
            if (jdk.version() >= 9) {
                args.add("--release");
                args.add(String.valueOf(level));
            } else {
                args.add("-source");
                args.add(String.valueOf(level));
                args.add("-target");
                args.add(String.valueOf(level));
            }
        }
        args.add(javaFile.getFileName().toString());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(COMPILE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("LocalJavaExecutor: javac timed out after {} ms", COMPILE_TIMEOUT_MS);
            return "Compilation timed out";
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() == 0) {
            return null;
        }
        return output;
    }

    private List<Marker> parseDiagnostics(String javacOutput) {
        List<Marker> markers = new ArrayList<>();
        if (javacOutput == null || javacOutput.isBlank()) {
            return markers;
        }
        for (String line : javacOutput.lines().toList()) {
            Matcher m = DIAGNOSTIC_PATTERN.matcher(line.trim());
            if (!m.matches()) {
                continue; // notes, caret lines, "symbol:"/"location:" continuations
            }
            int lineNum = Integer.parseInt(m.group(2));
            int column = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            String severity = "error".equals(m.group(4)) ? "error" : "warning";
            markers.add(new Marker(lineNum, column, severity, m.group(5).trim()));
            if (markers.size() >= MAX_MARKERS) {
                break;
            }
        }
        return markers;
    }

    /* ---------------- per-case subprocess run ---------------- */

    private TestCaseResult runCase(Path workingDir, String mainClassName, TestCase tc,
                                   Path javaExec, long timeoutMs) {
        long start = System.currentTimeMillis();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExec.toString(),
                    "-cp",
                    workingDir.toAbsolutePath().toString(),
                    mainClassName);
            pb.directory(workingDir.toFile());
            process = pb.start();

            OutputStream os = process.getOutputStream();
            if (tc.stdin() != null && !tc.stdin().isEmpty()) {
                os.write(tc.stdin().getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
            os.close();

            // Read streams async so a chatty program can't deadlock the pipes.
            CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - start;
            if (!finished) {
                process.destroyForcibly();
                return new TestCaseResult(tc.id(), "TIMEOUT",
                        stdoutFuture.getNow(""), stderrFuture.getNow(""), durationMs,
                        "Time limit exceeded (" + timeoutMs + " ms)");
            }

            String stdout = stdoutFuture.get(1, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(1, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errorMsg = stderr.isBlank() ? "Process exited with code " + exitCode : stderr.trim();
                return new TestCaseResult(tc.id(), "ERROR", stdout, stderr, durationMs, errorMsg);
            }
            if (tc.expected() == null) {
                return new TestCaseResult(tc.id(), "PASS", stdout, stderr, durationMs, null);
            }
            boolean matches = TestCaseResult.matches(stdout, tc.expected());
            return new TestCaseResult(tc.id(), matches ? "PASS" : "FAIL", stdout, stderr, durationMs, null);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new TestCaseResult(tc.id(), "ERROR", "", "", durationMs,
                    "Execution error: " + e.getMessage());
        }
    }

    private CompletableFuture<String> readStreamAsync(InputStream is) {
        return CompletableFuture.supplyAsync(() -> {
            try (is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                while ((read = is.read(buffer)) != -1) {
                    if (total < MAX_OUTPUT_BYTES) {
                        int toWrite = Math.min(read, MAX_OUTPUT_BYTES - total);
                        baos.write(buffer, 0, toWrite);
                        total += toWrite;
                    }
                }
                return baos.toString(StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        });
    }

    /* ---------------- helpers ---------------- */

    private static void emitForEach(List<TestCase> cases, Consumer<TestCaseResult> sink,
                                    TestCaseResult template) {
        for (TestCase tc : cases) {
            sink.accept(new TestCaseResult(tc.id(), template.status(), template.stdout(),
                    template.stderr(), template.durationMs(), template.error()));
        }
    }

    private static String detectClassName(String code) {
        Matcher m = PUBLIC_CLASS_PATTERN.matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        m = ANY_CLASS_PATTERN.matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        return "Main";
    }

    private static void deleteDirQuietly(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.trace("LocalJavaExecutor: cleanup failed for {}: {}", dir, e.toString());
        }
    }
}