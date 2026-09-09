package com.dsaplayground.service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dsaplayground.service.JavaRunner.Marker;
import com.dsaplayground.service.JdkManager.JdkInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Fast, in-process linting. The editor lints on every typing pause; spawning a
 * fresh {@code javac} JVM per request costs ~300 ms of pure startup, which is
 * what made live linting feel slow. When the JDK chosen in the UI is the JDK
 * this app itself runs on, we compile with the in-process compiler API
 * (javax.tools) — ~20-30 ms once warm — and produce markers identical to the
 * subprocess path. For any other chosen JDK we fall back to the spawn-based
 * {@link JavaRunner#lint} so the compile always honours the user's JDK pick.
 */
@Service
public class CompileService {

    private static final Logger log = LoggerFactory.getLogger(CompileService.class);

    private static final int MAX_MARKERS = 300;

    private static final Pattern PUBLIC_CLASS_PATTERN =
            Pattern.compile("public\\s+(?:final\\s+)?class\\s+([A-Za-z0-9_]+)");
    private static final Pattern ANY_CLASS_PATTERN =
            Pattern.compile("class\\s+([A-Za-z0-9_]+)");

    private final JdkManager jdkManager;
    private final JavaRunner javaRunner;

    /** Serialises in-process compiles — ToolProvider returns one shared compiler. */
    private final Object compileLock = new Object();

    public CompileService(JdkManager jdkManager, JavaRunner javaRunner) {
        this.jdkManager = jdkManager;
        this.javaRunner = javaRunner;
    }

    /**
     * Lint {@code code} against the given JDK/language level, returning javac
     * markers for the editor. Uses the in-process compiler when possible and
     * the {@code javac} subprocess otherwise; both paths return the same
     * {@link Marker} shape.
     */
    public List<Marker> lint(String code, String jdkHome, Integer release) {
        long start = System.nanoTime();
        log.trace("CompileService.lint(codeLen={} jdkHome={} release={}) ENTRY",
                code == null ? "null" : code.length(),
                jdkHome == null ? "default" : jdkHome, release);
        log.trace("CompileService.lint(): code preview: '{}'", code == null ? "null" : code.substring(0, Math.min(200, code.length())) + (code.length() > 200 ? "..." : ""));
        if (code == null || code.isBlank()) {
            log.trace("CompileService.lint(): code is empty/blank -> empty markers");
            return List.of();
        }
        JdkInstallation jdk = jdkManager.requireInstallation(jdkHome);
        log.trace("CompileService.lint(): resolved JDK: name='{}' version={} home={} javaBin={} javacBin={}",
                jdk.name(), jdk.version(), jdk.home(), jdk.javaBinary(), jdk.javacBinary());
        boolean inProcessPossible = canCompileInProcess(jdk);
        log.trace("CompileService.lint(): canCompileInProcess()={}/jdk.home={} runtime.java.home={}",
                inProcessPossible, jdk.home(), System.getProperty("java.home"));
        if (inProcessPossible) {
            log.trace("CompileService.lint(): attempting IN-PROCESS compile");
            try {
                List<Marker> markers = lintInProcess(code, release);
                log.trace("CompileService.lint(): in-process lint done: {} markers (elapsed {} ms)",
                        markers.size(), (System.nanoTime() - start) / 1_000_000);
                for (Marker m : markers) {
                    log.trace("CompileService.lint():   marker: line={} col={} severity={} msg='{}'",
                            m.line(), m.column(), m.severity(), m.message());
                }
                return markers;
            } catch (Exception e) {
                // Exotic in-process failure (file manager, memory…) — degrade to the
                // subprocess path rather than returning no markers.
                log.debug("CompileService.lint(): in-process lint failed, falling back to subprocess: {}", e.getMessage(), e);
                log.trace("CompileService.lint(): falling back to javac subprocess");
            }
        } else {
            log.trace("CompileService.lint(): in-process not possible; using javac subprocess");
        }
        log.trace("CompileService.lint(): delegating to JavaRunner.lint");
        List<Marker> markers = javaRunner.lint(code, jdkHome, release);
        log.trace("CompileService.lint() EXIT: {} markers (elapsed {} ms)",
                markers.size(), (System.nanoTime() - start) / 1_000_000);
        return markers;
    }

    /** In-process linting only makes sense when the chosen JDK is our own runtime. */
    private boolean canCompileInProcess(JdkInstallation jdk) {
        log.trace("CompileService.canCompileInProcess(jdk.home={} jdk.version={}) ENTRY", jdk.home(), jdk.version());
        String runtimeHome = System.getProperty("java.home");
        log.trace("CompileService.canCompileInProcess(): runtime java.home='{}'", runtimeHome == null ? "null" : runtimeHome);
        if (runtimeHome == null || runtimeHome.isBlank()) {
            log.trace("CompileService.canCompileInProcess() -> false (no runtime java.home)");
            return false;
        }
        Path requestedHome = Path.of(jdk.home()).toAbsolutePath().normalize();
        Path runtimeHomePath = Path.of(runtimeHome).toAbsolutePath().normalize();
        log.trace("CompileService.canCompileInProcess(): requestedHome={} runtimeHomePath={}", requestedHome, runtimeHomePath);
        boolean sameJdk = requestedHome.equals(runtimeHomePath);
        log.trace("CompileService.canCompileInProcess(): sameJdk={}", sameJdk);
        boolean compilerAvailable = ToolProvider.getSystemJavaCompiler() != null;
        log.trace("CompileService.canCompileInProcess(): ToolProvider.getSystemJavaCompiler()={}", compilerAvailable);
        boolean result = sameJdk && compilerAvailable;
        log.trace("CompileService.canCompileInProcess() -> {}", result);
        return result;
    }

    private List<Marker> lintInProcess(String code, Integer release) throws Exception {
        long start = System.nanoTime();
        log.trace("CompileService.lintInProcess(codeLen={} release={}) ENTRY", code.length(), release);
        synchronized (compileLock) {
            log.trace("CompileService.lintInProcess(): acquired compileLock");
            String className = detectClassName(code);
            log.trace("CompileService.lintInProcess(): detected class name='{}'", className);
            Path outDir = null;
            try {
                log.trace("CompileService.lintInProcess(): creating temp output dir");
                outDir = Files.createTempDirectory("dsa-lint-io-");
                log.trace("CompileService.lintInProcess(): temp out dir: {}", outDir);
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                log.trace("CompileService.lintInProcess(): compiler instance: {}", compiler);
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                log.trace("CompileService.lintInProcess(): diagnostic collector created");
                try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                    log.trace("CompileService.lintInProcess(): file manager opened");
                    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
                    log.trace("CompileService.lintInProcess(): CLASS_OUTPUT set to: {}", outDir.toFile());

                    SourceFile source = new SourceFile(className, code);
                    log.trace("CompileService.lintInProcess(): source file object created: name='{}'", className);
                    List<String> options = optionsFor(release);
                    log.trace("CompileService.lintInProcess(): compile options ({} args): {}", options.size(), options);
                    log.trace("CompileService.lintInProcess(): invoking compiler.getTask...");
                    Boolean ok = compiler.getTask(null, fm, diagnostics, options, null, List.of(source)).call();
                    log.trace("CompileService.lintInProcess(): compiler.call() returned ok={}", ok);
                    if (Boolean.TRUE.equals(ok)) {
                        log.trace("CompileService.lintInProcess(): compilation succeeded -> empty markers");
                        return List.of();
                    }
                    List<Diagnostic<? extends JavaFileObject>> diagList = diagnostics.getDiagnostics();
                    log.trace("CompileService.lintInProcess(): diagnostics count={}", diagList.size());
                    for (Diagnostic<? extends JavaFileObject> d : diagList) {
                        log.trace("CompileService.lintInProcess():   diag: kind={} line={} col={} msg='{}'",
                                d.getKind(), d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ROOT));
                    }
                    List<Marker> markers = parseDiagnostics(diagList);
                    log.trace("CompileService.lintInProcess(): parsed {} markers (elapsed {} ms)",
                            markers.size(), (System.nanoTime() - start) / 1_000_000);
                    return markers;
                }
            } finally {
                if (outDir != null) {
                    log.trace("CompileService.lintInProcess(): cleaning up outDir: {}", outDir);
                    deleteDirQuietly(outDir);
                }
            }
        }
    }

    /** javac command-line options, mirroring {@link JavaRunner#compileArgs} minus the binary. */
    private List<String> optionsFor(Integer release) {
        log.trace("CompileService.optionsFor(release={}) ENTRY", release);
        List<String> options = new ArrayList<>();
        log.trace("CompileService.optionsFor(): adding -Xlint:all");
        options.add("-Xlint:all");
        log.trace("CompileService.optionsFor(): adding -encoding UTF-8");
        options.add("-encoding");
        options.add("UTF-8");
        int runtimeVersion = Runtime.version().feature();
        log.trace("CompileService.optionsFor(): runtime feature version={}", runtimeVersion);
        if (release != null && release > 0 && release < runtimeVersion) {
            log.trace("CompileService.optionsFor(): adding --release {}", release);
            options.add("--release");
            options.add(String.valueOf(release));
        } else {
            log.trace("CompileService.optionsFor(): no --release (release={} runtime={})", release, runtimeVersion);
        }
        log.trace("CompileService.optionsFor() -> {} options: {}", options.size(), options);
        return options;
    }

    private List<Marker> parseDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        log.trace("CompileService.parseDiagnostics(diagCount={}) ENTRY", diagnostics.size());
        List<Marker> markers = new ArrayList<>();
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic<? extends JavaFileObject> d = diagnostics.get(i);
            log.trace("CompileService.parseDiagnostics(): diag [{}]: kind={} line={} col={}",
                    i, d.getKind(), d.getLineNumber(), d.getColumnNumber());
            if (markers.size() >= MAX_MARKERS) {
                log.trace("CompileService.parseDiagnostics(): hit MAX_MARKERS={}; stopping", MAX_MARKERS);
                break;
            }
            if (d.getKind() == Diagnostic.Kind.NOTE) {
                log.trace("CompileService.parseDiagnostics(): skipping NOTE diagnostic");
                continue;
            }
            int line = (int) d.getLineNumber();
            int column = (int) d.getColumnNumber();
            String message = d.getMessage(Locale.ROOT);
            log.trace("CompileService.parseDiagnostics(): message='{}'", message);
            // Keep the same single-line markers the subprocess path produces.
            String firstLine = message == null ? "" : message.lines().findFirst().orElse("").trim();
            log.trace("CompileService.parseDiagnostics(): firstLine='{}'", firstLine);
            if (firstLine.isEmpty()) {
                log.trace("CompileService.parseDiagnostics(): skipping empty firstLine");
                continue;
            }
            String severity = d.getKind() == Diagnostic.Kind.ERROR ? "error" : "warning";
            markers.add(new Marker(
                    line < 1 ? 1 : line,
                    column < 1 ? 1 : column,
                    severity,
                    firstLine));
            log.trace("CompileService.parseDiagnostics(): added marker: line={} col={} severity={} msg='{}'",
                    markers.get(markers.size() - 1).line(), markers.get(markers.size() - 1).column(), severity, firstLine);
        }
        log.trace("CompileService.parseDiagnostics() -> {} markers", markers.size());
        return markers;
    }

    /** The source file to compile, fed to javac straight from memory. */
    private static final class SourceFile extends SimpleJavaFileObject {
        private final String code;

        SourceFile(String className, String code) {
            super(URI.create("string:///" + className + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private String detectClassName(String code) {
        log.trace("CompileService.detectClassName(codeLen={}) ENTRY", code.length());
        Matcher m = PUBLIC_CLASS_PATTERN.matcher(code);
        boolean pubMatch = m.find();
        log.trace("CompileService.detectClassName(): PUBLIC_CLASS_PATTERN matches={}", pubMatch);
        if (pubMatch) {
            String cn = m.group(1);
            log.trace("CompileService.detectClassName(): PUBLIC match -> '{}'", cn);
            return cn;
        }
        m = ANY_CLASS_PATTERN.matcher(code);
        boolean anyMatch = m.find();
        log.trace("CompileService.detectClassName(): ANY_CLASS_PATTERN matches={}", anyMatch);
        if (anyMatch) {
            String cn = m.group(1);
            log.trace("CompileService.detectClassName(): ANY match -> '{}'", cn);
            return cn;
        }
        log.trace("CompileService.detectClassName(): no class found -> 'Main'");
        return "Main";
    }

    private void deleteDirQuietly(Path dir) {
        log.trace("CompileService.deleteDirQuietly(dir={}) ENTRY", dir);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    log.trace("CompileService.deleteDirQuietly(): deleting file: {}", file);
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    log.trace("CompileService.deleteDirQuietly(): deleting dir: {}", d);
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.trace("CompileService.deleteDirQuietly(dir={}): cleanup complete", dir);
        } catch (Exception e) {
            log.trace("CompileService.deleteDirQuietly(dir={}): exception during cleanup: {}", dir, e.getMessage());
        }
    }
}
