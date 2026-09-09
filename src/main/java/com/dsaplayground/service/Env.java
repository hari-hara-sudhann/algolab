package com.dsaplayground.service;

/**
 * Single access point for configuration that may come from either the real
 * process environment (exported shell variables) or the project's {@code .env}
 * file (loaded at startup by {@link EnvFileLoader}).
 *
 * <p>Real environment variables always win: the loader never overrides a key
 * the shell already exported, so a user's exported value beats a value in
 * {@code .env}.
 */
public final class Env {

    private Env() {
    }

    /**
     * Look up {@code key} in the real environment first, then in the values
     * loaded from {@code .env} (stored as {@code algolab.env.<KEY>} system
     * properties because {@link System#getenv()} is immutable).
     *
     * @return the value, or {@code null} when neither source defines the key
     */
    public static String get(String key) {
        String real = System.getenv(key);
        if (real != null) {
            return real;
        }
        return System.getProperty(EnvFileLoader.PROPERTY_PREFIX + key);
    }
}