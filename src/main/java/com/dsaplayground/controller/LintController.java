package com.dsaplayground.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dsaplayground.execution.Marker;
import com.dsaplayground.service.CompileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offline “problems” for the editor: compiles the snippet with the machine's
 * own javac (optionally at the chosen --release) and returns the diagnostics.
 * Compilation runs in-process via {@link CompileService} when the chosen JDK
 * is the app's own (fast enough for per-keystroke linting) and falls back to
 * a javac subprocess for any other JDK.
 */
@RestController
@RequestMapping("/api")
public class LintController {

    private static final Logger log = LoggerFactory.getLogger(LintController.class);
    private final CompileService compileService;

    public LintController(CompileService compileService) {
        this.compileService = compileService;
    }

    @PostMapping("/lint")
    public Map<String, Object> lint(@RequestBody LintRequest request) {
        long start = System.nanoTime();
        log.trace("LintController.lint(codeLen={} jdkHome={} javaVersion={}) ENTRY",
                request.code() == null ? "null" : request.code().length(),
                request.jdk() == null ? "null" : request.jdk(),
                request.javaVersion());
        if (request.code() != null && request.code().length() <= 500) {
            log.trace("LintController.lint(): code preview: '{}'", request.code());
        }
        List<Marker> markers = compileService.lint(request.code(), request.jdk(), request.javaVersion());
        log.trace("LintController.lint(): {} markers returned (elapsed {} ms)", markers.size(), (System.nanoTime() - start) / 1_000_000);
        for (Marker m : markers) {
            log.trace("LintController.lint():   marker: line={} col={} severity={} msg='{}'",
                    m.line(), m.column(), m.severity(), m.message());
        }
        Map<String, Object> result = Map.of("markers", markers);
        log.trace("LintController.lint() EXIT in {} ms -> {} markers",
                (System.nanoTime() - start) / 1_000_000, markers.size());
        return result;
    }

    public record LintRequest(String code, String jdk, Integer javaVersion) {
    }
}
