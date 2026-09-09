package com.dsaplayground.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal dotenv-style loader: reads {@code <working directory>/.env} at
 * startup and exposes each {@code KEY=VALUE} line as a
 * {@code algolab.env.<KEY>} system property so {@link Env} can see it.
 *
 * <p>Rules mirror the file's own header: {@code #} comments and blank lines
 * are skipped, values may be quoted, and whitespace around {@code =} is
 * tolerated. A key the process environment already defines is never
 * overridden (exported shell variables win over {@code .env}).
 *
 * <p>The loader is intentionally tiny — no external dependency, and it keeps
 * working inside a GraalVM native image.
 */
public final class EnvFileLoader {

    /** System-property prefix used for values read from {@code .env}. */
    public static final String PROPERTY_PREFIX = "algolab.env.";

    private static final Logger log = LoggerFactory.getLogger(EnvFileLoader.class);

    private EnvFileLoader() {
    }

    /**
     * Load {@code .env} from the process working directory. Safe to call more
     * than once (idempotent: an existing real env var or already-set property
     * is left untouched). Never throws.
     */
    public static void loadFromWorkingDirectory() {
        String cwd = System.getProperty("user.dir", ".");
        Path envFile = Path.of(cwd, ".env");
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue; // not KEY=VALUE
                }
                String key = line.substring(0, eq).trim();
                String value = unquote(line.substring(eq + 1).trim());
                if (key.isEmpty()) {
                    continue;
                }
                if (System.getenv(key) != null) {
                    continue; // real environment wins
                }
                String property = PROPERTY_PREFIX + key;
                if (System.getProperty(property) == null) {
                    System.setProperty(property, value);
                    log.debug("EnvFileLoader: loaded {} from {}", key, envFile);
                }
            }
        } catch (IOException e) {
            log.debug("EnvFileLoader: could not read {}: {}", envFile, e.toString());
        }
    }

    /** Strip a single layer of matching quotes, e.g. {@code "8080"} → {@code 8080}. */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}