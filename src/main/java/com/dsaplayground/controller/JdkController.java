package com.dsaplayground.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dsaplayground.execution.JavaExecutionService;
import com.dsaplayground.service.JdkManager;
import com.dsaplayground.service.JdkManager.JdkInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the JDKs found on this machine (so the UI can offer a picker) plus
 * the active execution mode — local JDK, Judge0 fallback, or none — so the UI
 * can show a small status badge without caring how execution works.
 */
@RestController
@RequestMapping("/api")
public class JdkController {

    private static final Logger log = LoggerFactory.getLogger(JdkController.class);
    private final JdkManager jdkManager;
    private final JavaExecutionService executionService;

    public JdkController(JdkManager jdkManager, JavaExecutionService executionService) {
        this.jdkManager = jdkManager;
        this.executionService = executionService;
    }

    @GetMapping("/jdks")
    public Map<String, Object> jdks() {
        long start = System.nanoTime();
        log.trace("JdkController.jdks() ENTRY");
        List<JdkInstallation> all = jdkManager.available();
        Optional<JdkInstallation> def = jdkManager.defaultInstallation();
        log.trace("JdkController.jdks(): available count={} default={}", all.size(),
                def.map(j -> j.name()).orElse("none"));

        List<Map<String, Object>> jdks = all.stream()
                .map(JdkController::toJson)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("default", def.map(JdkController::toJson).orElse(null));
        result.put("jdks", jdks);
        result.put("mode", executionService.mode().id());
        result.put("modeLabel", executionService.modeLabel());
        result.put("judge0", Map.of(
                "configured", executionService.judge0Configured(),
                "url", executionService.judge0Url()));
        log.trace("JdkController.jdks() EXIT in {} ms -> mode={} + {} jdks",
                (System.nanoTime() - start) / 1_000_000, result.get("mode"), jdks.size());
        return result;
    }

    private static Map<String, Object> toJson(JdkInstallation jdk) {
        return Map.of(
                "home", jdk.home(),
                "version", jdk.version(),
                "name", jdk.name(),
                "versionLine", jdk.versionLine(),
                "levels", jdk.levels());
    }
}