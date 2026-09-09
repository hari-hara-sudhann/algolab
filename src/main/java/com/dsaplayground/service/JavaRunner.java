package com.dsaplayground.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dsaplayground.service.JdkManager.JdkInstallation;
import com.dsaplayground.service.TestCaseRunner.TestCase;
import com.dsaplayground.service.TestCaseRunner.TestCaseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JavaRunner {

    private static final Logger log = LoggerFactory.getLogger(JavaRunner.class);

    private static final Pattern PUBLIC_CLASS_PATTERN = Pattern.compile("public\\s+(?:final\\s+)?class\\s+([A-Za-z0-9_]+)");
    private static final Pattern ANY_CLASS_PATTERN = Pattern.compile("class\\s+([A-Za-z0-9_]+)");

    /** javac diagnostic: path:line[:col]: error|warning: message */
    private static final Pattern DIAGNOSTIC_PATTERN = Pattern.compile(
            "^([A-Za-z0-9_.-]+):(\\d+)(?::(\\d+))?:\\s*(error|warning):\\s*(.+)$");

    private static final int MAX_MARKERS = 300;

    private final JdkManager jdkManager;
    private final TestCaseRunner testCaseRunner;

    public JavaRunner(JdkManager jdkManager, TestCaseRunner testCaseRunner) {
        this.jdkManager = jdkManager;
        this.testCaseRunner = testCaseRunner;
    }

    /** A single compile diagnostic for the editor (line/column are 1-based). */
    public record Marker(int line, int column, String severity, String message) {
    }

    /**
     * Compile {@code code} with the given JDK (or the default one) targeting
     * {@code release} (e.g. 21, 17, 11, 8 — {@code null} = JDK default) and run
     * every test case against the compiled class, collecting all results.
     */
    public List<TestCaseResult> run(String code, List<TestCase> testCases, String jdkHome, Integer release) {
        log.trace("JavaRunner.run(codeLen={} testCases={} jdkHome={} release={}) ENTRY",
                code == null ? "null" : code.length(), testCases == null ? "null" : testCases.size(),
                jdkHome == null ? "default" : jdkHome, release);
        List<TestCaseResult> results = new ArrayList<>();
        runToSink(code, testCases, jdkHome, release, results::add);
        log.trace("JavaRunner.run() -> {} results", results.size());
        for (TestCaseResult r : results) {
            log.trace("JavaRunner.run():   result: id={} status={} durationMs={} error={}",
                    r.id(), r.status(), r.durationMs(), r.error() == null ? "none" : r.error());
        }
        return results;
    }

    /**
     * Same run as {@link #run(String, List, String, Integer)} but every result
     * is handed to {@code sink} the moment that test case finishes, so callers
     * (e.g. a streaming HTTP response) can show verdicts as they complete.
     */
    public void runToSink(String code, List<TestCase> testCases, String jdkHome, Integer release,
                          Consumer<TestCaseResult> sink) {
        long outerStart = System.nanoTime();
        log.trace("JavaRunner.runToSink(codeLen={} testCases={} jdkHome={} release={}) ENTRY",
                code == null ? "null" : code.length(), testCases == null ? "null" : testCases.size(),
                jdkHome == null ? "default" : jdkHome, release);
        log.trace("JavaRunner.runToSink(): code preview: '{}'", code == null ? "null" : code.substring(0, Math.min(200, code.length())) + (code.length() > 200 ? "..." : ""));
        JdkInstallation jdk = jdkManager.requireInstallation(jdkHome);
        log.trace("JavaRunner.runToSink(): resolved JDK: name='{}' version={} home={} java={} javac={}",
                jdk.name(), jdk.version(), jdk.home(), jdk.javaBinary(), jdk.javacBinary());
        List<TestCase> cases = testCases == null ? List.of() : testCases;
        log.trace("JavaRunner.runToSink(): effective test cases count={}", cases.size());
        for (int i = 0; i < cases.size(); i++) {
            TestCase tc = cases.get(i);
            log.trace("JavaRunner.runToSink():   test case [{}]: id='{}' name='{}' stdinLen={} expected='{}'",
                    i, tc.id(), tc.name(), tc.stdin() == null ? "null" : tc.stdin().length(), tc.expected() == null ? "null" : tc.expected());
        }
        if (code == null || code.isBlank()) {
            log.warn("JavaRunner.runToSink(): code is empty/blank");
            for (TestCase tc : cases) {
                TestCaseResult err = new TestCaseResult(tc.id(), "ERROR", "", "", 0L, "Code is empty");
                log.trace("JavaRunner.runToSink(): emitting ERROR for {}: {}", tc.id(), err.error());
                sink.accept(err);
            }
            return;
        }

        log.trace("JavaRunner.runToSink(): detecting class name from code");
        String className = detectClassName(code);
        log.trace("JavaRunner.runToSink(): detected class name='{}'", className);
        Path tempDir = null;
        try {
            log.trace("JavaRunner.runToSink(): creating temp directory");
            tempDir = Files.createTempDirectory("dsa-run-");
            log.trace("JavaRunner.runToSink(): temp dir created: {}", tempDir);
            Path javaFile = tempDir.resolve(className + ".java");
            log.trace("JavaRunner.runToSink(): java file path: {}", javaFile);
            log.trace("JavaRunner.runToSink(): writing {} bytes to java file", code.length());
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);
            log.trace("JavaRunner.runToSink(): java file written; exists={} size={}", Files.exists(javaFile), Files.size(javaFile));

            // Compile
            log.trace("JavaRunner.runToSink(): starting compilation");
            long compileStart = System.currentTimeMillis();
            List<String> compileCmd = compileArgs(jdk, javaFile, release, false);
            log.trace("JavaRunner.runToSink(): compile command ({} args): {}", compileCmd.size(), compileCmd);
            ProcessBuilder pb = new ProcessBuilder(compileCmd);
            pb.directory(tempDir.toFile());
            log.trace("JavaRunner.runToSink(): compile working dir: {}", pb.directory());
            pb.redirectErrorStream(true);
            log.trace("JavaRunner.runToSink(): redirectErrorStream=true (stdout+stderr merged)");

            Process javacProcess = pb.start();
            log.trace("JavaRunner.runToSink(): javac process started pid={}", javacProcess.pid());
            boolean finished = javacProcess.waitFor(15, TimeUnit.SECONDS);
            long compileDuration = System.currentTimeMillis() - compileStart;
            log.trace("JavaRunner.runToSink(): javac waitFor finished={} durationMs={}", finished, compileDuration);

            if (!finished) {
                log.warn("JavaRunner.runToSink(): compilation TIMED OUT after 15s; destroying pid={}", javacProcess.pid());
                javacProcess.destroyForcibly();
                log.trace("JavaRunner.runToSink(): javac process destroyed");
                String err = "Compilation timed out";
                for (TestCase tc : cases) {
                    TestCaseResult compileErr = new TestCaseResult(tc.id(), "COMPILE_ERROR", "", "", compileDuration, err);
                    log.trace("JavaRunner.runToSink(): emitting COMPILE_ERROR for {}: {}", tc.id(), err);
                    sink.accept(compileErr);
                }
                return;
            }

            log.trace("JavaRunner.runToSink(): reading javac output ({} bytes)", javacProcess.getInputStream().available());
            String compilerOutput = new String(javacProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.trace("JavaRunner.runToSink(): javac output ({} chars): '{}'", compilerOutput.length(), compilerOutput.replace("\n", " | "));
            int exitValue = javacProcess.exitValue();
            log.trace("JavaRunner.runToSink(): javac exitValue={}", exitValue);
            if (exitValue != 0) {
                final String finalErr = compilerOutput.isBlank() ? "Compilation failed" : compilerOutput.trim();
                log.warn("JavaRunner.runToSink(): compilation FAILED: {}", finalErr);
                for (TestCase tc : cases) {
                    TestCaseResult compileErr = new TestCaseResult(tc.id(), "COMPILE_ERROR", "", "", compileDuration, finalErr);
                    log.trace("JavaRunner.runToSink(): emitting COMPILE_ERROR for {}: {}", tc.id(), finalErr);
                    sink.accept(compileErr);
                }
                return;
            }
            log.trace("JavaRunner.runToSink(): compilation successful");

            // Run test cases
            log.trace("JavaRunner.runToSink(): running {} test case(s)", cases.size());
            for (int i = 0; i < cases.size(); i++) {
                TestCase tc = cases.get(i);
                log.trace("JavaRunner.runToSink(): [test {}/{}] running case id='{}' name='{}'", i + 1, cases.size(), tc.id(), tc.name());
                long tcStart = System.nanoTime();
                TestCaseResult res = testCaseRunner.runCase(
                        tempDir,
                        className,
                        tc,
                        jdk.javaBinary(),
                        5000L
                );
                long tcDurationMs = (System.nanoTime() - tcStart) / 1_000_000;
                log.trace("JavaRunner.runToSink(): [test {}/{}] case id='{}' result: status={} durationMs={} (total run: {}ms)",
                        i + 1, cases.size(), tc.id(), res.status(), tcDurationMs, res.durationMs());
                log.trace("JavaRunner.runToSink(): [test {}/{}] case id='{}': stdoutLen={} stderrLen={} error={}",
                        i + 1, cases.size(), tc.id(), res.stdout().length(), res.stderr().length(),
                        res.error() == null ? "none" : res.error());
                sink.accept(res);
            }

        } catch (Exception e) {
            log.error("JavaRunner.runToSink(): UNHANDLED EXCEPTION after {} ms: {}", (System.nanoTime() - outerStart) / 1_000_000, e.getMessage(), e);
            final String err = "Runner execution failed: " + e.getMessage();
            log.error("JavaRunner.runToSink(): error message: {}", err);
            for (TestCase tc : cases) {
                TestCaseResult errResult = new TestCaseResult(tc.id(), "ERROR", "", "", 0L, err);
                log.trace("JavaRunner.runToSink(): emitting ERROR for {}: {}", tc.id(), err);
                sink.accept(errResult);
            }
        } finally {
            if (tempDir != null) {
                log.trace("JavaRunner.runToSink(): cleaning up temp dir: {}", tempDir);
                deleteDirQuietly(tempDir);
                log.trace("JavaRunner.runToSink(): temp dir cleanup complete");
            }
            log.trace("JavaRunner.runToSink() EXIT total: {} ms", (System.nanoTime() - outerStart) / 1_000_000);
        }
    }

    /**
     * Lint {@code code} by compiling it in a scratch dir and turning javac's
     * errors/warnings into editor markers. Completely offline — this is the
     * machine's own javac doing the real work.
     */
    public List<Marker> lint(String code, String jdkHome, Integer release) {
        log.trace("JavaRunner.lint(codeLen={} jdkHome={} release={}) ENTRY",
                code == null ? "null" : code.length(), jdkHome == null ? "default" : jdkHome, release);
        if (code == null || code.isBlank()) {
            log.trace("JavaRunner.lint(): code is empty/blank -> empty markers");
            return List.of();
        }
        JdkInstallation jdk = jdkManager.requireInstallation(jdkHome);
        log.trace("JavaRunner.lint(): resolved JDK: name='{}' version={} home={}", jdk.name(), jdk.version(), jdk.home());
        String className = detectClassName(code);
        log.trace("JavaRunner.lint(): detected class name='{}'", className);
        Path tempDir = null;
        try {
            log.trace("JavaRunner.lint(): creating temp dir");
            tempDir = Files.createTempDirectory("dsa-lint-");
            log.trace("JavaRunner.lint(): temp dir created: {}", tempDir);
            Path javaFile = tempDir.resolve(className + ".java");
            log.trace("JavaRunner.lint(): java file: {}", javaFile);
            log.trace("JavaRunner.lint(): writing {} bytes to java file", code.length());
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);
            log.trace("JavaRunner.lint(): java file written; size={}", Files.size(javaFile));

            List<String> compileCmd = compileArgs(jdk, javaFile, release, true);
            log.trace("JavaRunner.lint(): lint compile command ({} args): {}", compileCmd.size(), compileCmd);
            ProcessBuilder pb = new ProcessBuilder(compileCmd);
            pb.directory(tempDir.toFile());
            log.trace("JavaRunner.lint(): compile working dir: {}", pb.directory());
            pb.redirectErrorStream(true);

            Process javacProcess = pb.start();
            log.trace("JavaRunner.lint(): javac process started pid={}", javacProcess.pid());
            boolean finished = javacProcess.waitFor(15, TimeUnit.SECONDS);
            log.trace("JavaRunner.lint(): javac waitFor finished={} after 15s", finished);
            if (!finished) {
                log.warn("JavaRunner.lint(): javac timed out; destroying pid={}", javacProcess.pid());
                javacProcess.destroyForcibly();
                log.trace("JavaRunner.lint(): returning empty markers (timeout)");
                return List.of();
            }
            log.trace("JavaRunner.lint(): reading javac output");
            String output = new String(javacProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.trace("JavaRunner.lint(): javac output ({} chars): '{}'", output.length(), output.replace("\n", " | "));
            int exitValue = javacProcess.exitValue();
            log.trace("JavaRunner.lint(): javac exitValue={}", exitValue);
            List<Marker> markers = parseDiagnostics(output);
            log.trace("JavaRunner.lint(): parsed {} markers", markers.size());
            for (Marker m : markers) {
                log.trace("JavaRunner.lint():   marker: line={} col={} severity={} msg='{}'", m.line(), m.column(), m.severity(), m.message());
            }
            return markers;
        } catch (Exception e) {
            log.debug("JavaRunner.lint(): exception: {}", e.toString(), e);
            return List.of();
        } finally {
            if (tempDir != null) {
                log.trace("JavaRunner.lint(): cleaning up temp dir: {}", tempDir);
                deleteDirQuietly(tempDir);
            }
        }
    }

    /**
     * Build the javac command line. A requested release is honoured with
     * {@code --release} (JDK 9+); for JDK 8 the only supported target is the
     * compiler's own, so no flag is added.
     */
    private List<String> compileArgs(JdkInstallation jdk, Path javaFile, Integer release, boolean lint) {
        log.trace("JavaRunner.compileArgs(jdk.version={} release={} lint={} javaFile={}) ENTRY",
                jdk.version(), release, lint, javaFile);
        List<String> args = new ArrayList<>();
        log.trace("JavaRunner.compileArgs(): javacBinary={}", jdk.javacBinary());
        args.add(jdk.javacBinary().toString());
        if (lint) {
            log.trace("JavaRunner.compileArgs(): adding -Xlint:all");
            args.add("-Xlint:all");
        }
        log.trace("JavaRunner.compileArgs(): adding -encoding UTF-8");
        args.add("-encoding");
        args.add("UTF-8");
        Integer level = release;
        log.trace("JavaRunner.compileArgs(): release requested={} jdk.version()={}", level, jdk.version());
        if (level != null && level > 0 && level < jdk.version()) {
            log.trace("JavaRunner.compileArgs(): need to target release {} (jdk has {})", level, jdk.version());
            if (jdk.version() >= 9) {
                log.trace("JavaRunner.compileArgs(): JDK 9+, using --release {}", level);
                args.add("--release");
                args.add(String.valueOf(level));
            } else {
                log.trace("JavaRunner.compileArgs(): JDK 8, using -source -target {}", level);
                args.add("-source");
                args.add(String.valueOf(level));
                args.add("-target");
                args.add(String.valueOf(level));
            }
        } else {
            log.trace("JavaRunner.compileArgs(): no --release flag (level={} or level>=jdk.version)", level);
        }
        log.trace("JavaRunner.compileArgs(): adding source file: {}", javaFile.getFileName());
        args.add(javaFile.getFileName().toString());
        log.trace("JavaRunner.compileArgs() -> {} args: {}", args.size(), args);
        return args;
    }

    private List<Marker> parseDiagnostics(String javacOutput) {
        log.trace("JavaRunner.parseDiagnostics(inputLen={}) ENTRY", javacOutput == null ? "null" : javacOutput.length());
        List<Marker> markers = new ArrayList<>();
        if (javacOutput == null || javacOutput.isBlank()) {
            log.trace("JavaRunner.parseDiagnostics(): empty/null input -> empty markers");
            return markers;
        }
        List<String> lines = javacOutput.lines().toList();
        log.trace("JavaRunner.parseDiagnostics(): total lines to scan: {}", lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            log.trace("JavaRunner.parseDiagnostics(): line [{}]: '{}'", i, line);
            Matcher m = DIAGNOSTIC_PATTERN.matcher(line.trim());
            boolean matches = m.matches();
            log.trace("JavaRunner.parseDiagnostics(): line [{}] matches DIAGNOSTIC_PATTERN={}", i, matches);
            if (!matches) {
                continue; // notes, caret lines, "symbol:"/"location:" continuations
            }
            int lineNum = Integer.parseInt(m.group(2));
            int column = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
            String severity = "error".equals(m.group(4)) ? "error" : "warning";
            String message = m.group(5).trim();
            log.trace("JavaRunner.parseDiagnostics(): parsed: line={} col={} severity={} message='{}'",
                    lineNum, column, severity, message);
            markers.add(new Marker(lineNum, column, severity, message));
            if (markers.size() >= MAX_MARKERS) {
                log.trace("JavaRunner.parseDiagnostics(): hit MAX_MARKERS={}, stopping", MAX_MARKERS);
                break;
            }
        }
        log.trace("JavaRunner.parseDiagnostics() -> {} markers (from {} lines)", markers.size(), lines.size());
        return markers;
    }

    private String detectClassName(String code) {
        log.trace("JavaRunner.detectClassName(codeLen={}) ENTRY", code.length());
        Matcher m = PUBLIC_CLASS_PATTERN.matcher(code);
        boolean pubMatch = m.find();
        log.trace("JavaRunner.detectClassName(): PUBLIC_CLASS_PATTERN matches={}", pubMatch);
        if (pubMatch) {
            String cn = m.group(1);
            log.trace("JavaRunner.detectClassName(): PUBLIC match -> '{}'", cn);
            return cn;
        }
        m = ANY_CLASS_PATTERN.matcher(code);
        boolean anyMatch = m.find();
        log.trace("JavaRunner.detectClassName(): ANY_CLASS_PATTERN matches={}", anyMatch);
        if (anyMatch) {
            String cn = m.group(1);
            log.trace("JavaRunner.detectClassName(): ANY match -> '{}'", cn);
            return cn;
        }
        log.trace("JavaRunner.detectClassName(): no class found -> 'Main'");
        return "Main";
    }

    private void deleteDirQuietly(Path dir) {
        log.trace("JavaRunner.deleteDirQuietly(dir={}) ENTRY", dir);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    log.trace("JavaRunner.deleteDirQuietly(): deleting file: {}", file);
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    log.trace("JavaRunner.deleteDirQuietly(): deleting dir: {}", d);
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.trace("JavaRunner.deleteDirQuietly(dir={}): cleanup complete", dir);
        } catch (Exception e) {
            log.trace("JavaRunner.deleteDirQuietly(dir={}): exception during cleanup: {}", dir, e.getMessage());
        }
    }
}
