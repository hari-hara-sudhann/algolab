package com.dsaplayground.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dsaplayground.service.JdkManager;
import com.dsaplayground.service.JdkManager.JdkInstallation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports the JDKs found on this machine so the UI can offer a picker. */
@RestController
@RequestMapping("/api")
public class JdkController {

    private static final Logger log = LoggerFactory.getLogger(JdkController.class);
    private final JdkManager jdkManager;

    public JdkController(JdkManager jdkManager) {
        this.jdkManager = jdkManager;
    }

    @GetMapping("/jdks")
    public Map<String, Object> jdks() {
        long start = System.nanoTime();
        log.trace("JdkController.jdks() ENTRY");
        JdkInstallation def = jdkManager.defaultInstallation();
        log.trace("JdkController.jdks(): default={} (Java {})", def.name(), def.version());
        List<JdkInstallation> all = jdkManager.available();
        log.trace("JdkController.jdks(): available count={}", all.size());
        for (int i = 0; i < all.size(); i++) {
            JdkInstallation j = all.get(i);
            log.trace("JdkController.jdks():   [{}]: name='{}' version={} home={} levels={}",
                    i, j.name(), j.version(), j.home(), j.levels());
        }
        List<Map<String, Object>> jdks = all.stream()
                .map(JdkController::toJson)
                .toList();
        Map<String, Object> result = Map.of(
                "default", toJson(def),
                "jdks", jdks);
        log.trace("JdkController.jdks() EXIT in {} ms -> default + {} jdks",
                (System.nanoTime() - start) / 1_000_000, jdks.size());
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
