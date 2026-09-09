package com.dsaplayground.execution;

import java.util.List;
import java.util.function.Consumer;

import com.dsaplayground.service.JdkManager.JdkInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single entry point the rest of AlgoLab uses to run Java. It decides
 * <em>where</em> execution happens — always preferring a local JDK and falling
 * back to Judge0 only when no usable JDK exists on this machine — and callers
 * never need to know which mechanism served the request.
 *
 * <p>Also exposes the active {@link ExecutionMode} (for a status badge in the
 * UI and the startup banner) and routes linting to the local javac pass
 * (Judge0 is an execution fallback, not a linting service).
 */
@Service
public class JavaExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JavaExecutionService.class);

    private final LocalJavaExecutor local;
    private final Judge0Executor judge0;

    public JavaExecutionService(LocalJavaExecutor local, Judge0Executor judge0) {
        this.local = local;
        this.judge0 = judge0;
    }

    /** Which mechanism would serve a run right now. */
    public ExecutionMode mode() {
        if (local.isAvailable()) {
            return ExecutionMode.LOCAL;
        }
        if (judge0.isAvailable()) {
            return ExecutionMode.JUDGE0;
        }
        return ExecutionMode.NONE;
    }

    /** Human description of the active mechanism for the startup banner/status. */
    public String modeLabel() {
        return switch (mode()) {
            case LOCAL -> {
                JdkInstallation jdk = local.defaultJdk();
                yield jdk == null ? "Local JDK" : "Local JDK — Java " + jdk.version() + " (" + jdk.name() + ")";
            }
            case JUDGE0 -> "Judge0 — " + judge0.config().apiUrl();
            case NONE -> "No JDK found and Judge0 not configured";
        };
    }

    /** Whether the Judge0 fallback is configured (for the UI status badge). */
    public boolean judge0Configured() {
        return judge0.isAvailable();
    }

    /** Judge0 base URL for status display. */
    public String judge0Url() {
        return judge0.config().apiUrl();
    }

    /**
     * Run every test case, streaming one {@link TestCaseResult} per case to
     * {@code sink} as it finishes. Local JDK preferred; Judge0 used only when
     * no local JDK is available; otherwise every case gets an ERROR verdict
     * explaining that nothing can run.
     */
    public void runToSink(String code, List<TestCase> testCases, ExecOptions options,
                          Consumer<TestCaseResult> sink) {
        switch (mode()) {
            case LOCAL -> local.runToSink(code, testCases, options, sink);
            case JUDGE0 -> {
                log.info("No local JDK — falling back to Judge0 ({})", judge0.config().apiUrl());
                judge0.runToSink(code, testCases, options, sink);
            }
            case NONE -> {
                log.warn("Cannot run: no local JDK and Judge0 not configured");
                List<TestCase> cases = testCases == null ? List.of() : testCases;
                for (TestCase tc : cases) {
                    sink.accept(new TestCaseResult(tc.id(), "ERROR", "", "", 0L,
                            "No JDK found on this machine and Judge0 is not configured. "
                                    + "Install a JDK or set JUDGE0_API_URL to enable remote execution."));
                }
            }
        }
    }

    /**
     * Compile {@code code} for editor markers. Only meaningful with a local
     * JDK; without one this returns no markers (linting is an offline
     * nicety, never a blocker).
     */
    public List<Marker> lint(String code, ExecOptions options) {
        if (!local.isAvailable()) {
            return List.of();
        }
        return local.lint(code, options);
    }
}