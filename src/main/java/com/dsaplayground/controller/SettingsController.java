package com.dsaplayground.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * User settings persisted as a JSON file at {@code ~/.algolab-settings}
 * (path overridable via the {@code ALGOLAB_SETTINGS} env var). The frontend
 * treats the file as the durable store and localStorage as an instant cache.
 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static Path settingsPath() {
        String override = System.getenv("ALGOLAB_SETTINGS");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home", "."), ".algolab-settings");
    }

    /** Read the settings file; a missing or corrupt file yields an empty object. */
    @GetMapping("/settings")
    public Map<String, Object> read() {
        long start = System.nanoTime();
        log.trace("SettingsController.read() ENTRY");
        Path file = settingsPath();
        log.trace("SettingsController.read(): settingsPath()={}", file);
        log.trace("SettingsController.read(): isRegularFile={}", Files.isRegularFile(file));
        if (!Files.isRegularFile(file)) {
            log.trace("SettingsController.read(): file missing -> empty map");
            Map<String, Object> result = new LinkedHashMap<>();
            log.trace("SettingsController.read() EXIT in {} ms -> {} (empty, file missing)",
                    (System.nanoTime() - start) / 1_000_000, result);
            return result;
        }
        try {
            log.trace("SettingsController.read(): reading file (UTF-8)");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            log.trace("SettingsController.read(): read {} chars");
            if (json.isBlank()) {
                log.trace("SettingsController.read(): empty file -> empty map");
                Map<String, Object> result = new LinkedHashMap<>();
                log.trace("SettingsController.read() EXIT in {} ms -> {} (empty, blank)",
                        (System.nanoTime() - start) / 1_000_000, result);
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(json, Map.class);
            log.trace("SettingsController.read(): parsed {} keys");
            Map<String, Object> result = parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
            log.trace("SettingsController.read() EXIT in {} ms -> {} keys",
                    (System.nanoTime() - start) / 1_000_000, result.size());
            return result;
        } catch (IOException e) {
            // Partial/corrupt write: start fresh instead of crashing the UI.
            log.warn("SettingsController.read(): IOException reading settings: {}", e.getMessage());
            log.trace("SettingsController.read(): returning empty map (corrupt file)");
            Map<String, Object> result = new LinkedHashMap<>();
            log.trace("SettingsController.read() EXIT in {} ms -> {} ( IOException)",
                    (System.nanoTime() - start) / 1_000_000, result);
            return result;
        }
    }

    /** Atomically replace the settings file with the submitted JSON object. */
    @PutMapping("/settings")
    public synchronized Map<String, Object> write(@RequestBody Map<String, Object> settings) throws IOException {
        long start = System.nanoTime();
        log.trace("SettingsController.write(settings={} entries) ENTRY", settings == null ? "null" : settings.size());
        if (settings == null) {
            log.trace("SettingsController.write(): null settings -> treating as empty map");
        } else {
            log.trace("SettingsController.write(): settings keys: {}", settings.keySet());
        }
        Path file = settingsPath();
        log.trace("SettingsController.write(): settingsPath()={}", file);
        Path dir = file.getParent();
        log.trace("SettingsController.write(): parent dir={}", dir);
        if (dir != null) {
            log.trace("SettingsController.write(): ensuring parent dir exists: {}", dir);
            Files.createDirectories(dir);
            log.trace("SettingsController.write(): parent dir ready");
        }
        Map<String, Object> effectiveSettings = settings == null ? Map.of() : settings;
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(effectiveSettings);
        log.trace("SettingsController.write(): serialized to {} bytes (pretty-printed)", json.length);
        Path tmp = Files.createTempFile(dir, ".algolab-settings", ".tmp");
        log.trace("SettingsController.write(): temp file: {}", tmp);
        try {
            log.trace("SettingsController.write(): writing {} bytes to temp file", json.length);
            Files.write(tmp, json);
            log.trace("SettingsController.write(): temp file written; size={}", Files.size(tmp));
            try {
                log.trace("SettingsController.write(): attempting ATOMIC_MOVE {} -> {}", tmp, file);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.trace("SettingsController.write(): ATOMIC_MOVE succeeded");
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("SettingsController.write(): ATOMIC_MOVE not supported; falling back to regular move: {}", e.getMessage());
                log.trace("SettingsController.write(): performing regular REPLACE_EXISTING move");
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                log.trace("SettingsController.write(): regular move succeeded");
            }
        } finally {
            boolean tmpExists = Files.exists(tmp);
            log.trace("SettingsController.write(): temp file still exists? {}", tmpExists);
            if (tmpExists) {
                Files.deleteIfExists(tmp);
                log.trace("SettingsController.write(): deleted leftover temp file");
            }
        }
        Map<String, Object> result = Map.of("ok", true, "path", file.toString());
        log.trace("SettingsController.write() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, result);
        return result;
    }
}
