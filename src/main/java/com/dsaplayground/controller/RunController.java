package com.dsaplayground.controller;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.dsaplayground.service.FsService;
import com.dsaplayground.service.JavaRunner;
import com.dsaplayground.service.TestCaseRunner.TestCase;
import com.dsaplayground.service.TestCaseRunner.TestCaseResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs Java code against test cases using the JDK and language level chosen in
 * the UI (both fall back to sensible defaults when absent). The response is a
 * stream of newline-delimited JSON (one {@link TestCaseResult} per test case),
 * flushed as each case finishes, so the editor can paint verdicts live.
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final JavaRunner javaRunner;
    private final FsService fs;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunController(JavaRunner javaRunner, FsService fs) {
        this.javaRunner = javaRunner;
        this.fs = fs;
    }

    /**
     * Streams one newline-delimited JSON verdict per test case, flushed the
     * moment each case finishes. Writes straight to the servlet response (the
     * request thread already blocks for the whole run, so no async machinery).
     * A client disconnect is detected on write and simply stops further output.
     * NOTE: each verdict is serialized to a String first — handing the Writer
     * to Jackson would let its generator auto-close our response stream.
     */
    @PostMapping("/run")
    public void run(@RequestBody RunRequest request, HttpServletResponse response) throws IOException {
        long start = System.nanoTime();
        log.trace("RunController.run(path='{}', codeLen={} jdkHome={} javaVersion={} testCases={}) ENTRY",
                request.path() == null ? "null" : request.path(),
                request.code() == null ? "null" : request.code().length(),
                request.jdk() == null ? "null" : request.jdk(),
                request.javaVersion(),
                request.testCases() == null ? "null" : request.testCases().size());
        if (request.code() != null && request.code().length() <= 1000) {
            log.trace("RunController.run(): code preview: '{}'", request.code());
        }
        if (request.testCases() != null) {
            for (int i = 0; i < request.testCases().size(); i++) {
                TestCaseDto tc = request.testCases().get(i);
                log.trace("RunController.run(): testCase [{}]: id='{}' name='{}' stdinLen={} expected='{}'",
                        i, tc.id(), tc.name(), tc.stdin() == null ? "null" : tc.stdin().length(),
                        tc.expected() == null ? "null" : tc.expected());
            }
        }
        saveNotebook(request);

        List<TestCase> cases = request.testCases() == null ? List.of() :
                request.testCases().stream()
                        .map(tc -> new TestCase(tc.id(), tc.name(), tc.stdin(), tc.expected()))
                        .toList();
        log.trace("RunController.run(): testCases from request: {} cases (RunRequest.TestCaseDto)",
                request.testCases() == null ? 0 : request.testCases().size());

        log.trace("RunController.run(): resolved {} test cases for execution", cases.size());
        response.setStatus(200);
        response.setContentType(NDJSON.toString());
        log.trace("RunController.run(): response set: status=200 contentType={}", NDJSON);
        OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        log.trace("RunController.run(): OutputStreamWriter created (UTF-8)");
        try {
            log.trace("RunController.run(): starting NDJSON stream for {} case(s)", cases.size());
            javaRunner.runToSink(request.code(), cases, request.jdk(), request.javaVersion(), result -> {
                try {
                    String json = mapper.writeValueAsString(result);
                    log.trace("RunController.run(): verdict for {}: {} (jsonLen={}) -> writing to stream",
                            result.id(), result.status(), json.length());
                    log.trace("RunController.run():   result detail: stdoutLen={} stderrLen={} durationMs={} error={}",
                            result.stdout().length(), result.stderr().length(), result.durationMs(),
                            result.error() == null ? "none" : result.error());
                    writer.write(json);
                    writer.write('\n');
                    writer.flush();
                    log.trace("RunController.run(): flushed verdict for {}", result.id());
                } catch (IOException e) {
                    // Client went away — stop writing but let the run wind down.
                    log.debug("RunController.run(): write failed for {}: {}", result.id(), e.toString());
                }
            });
            writer.flush();
            log.trace("RunController.run(): NDJSON stream finished");
        } finally {
            try {
                writer.close();
                log.trace("RunController.run(): response writer closed");
            } catch (IOException ignored) {
                // already closed / broken
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.trace("RunController.run() EXIT total: {} ms", elapsedMs);
    }

    /**
     * Auto-save: persist the request as a notebook (.algolab or legacy .dsa)
     * before running, so the file on disk always matches what was run. A
     * failed save never blocks the run itself.
     */
    private void saveNotebook(RunRequest request) {
        String path = request.path();
        if (path == null || !FsService.isNotebookFile(path)) {
            return; // nothing to persist to
        }
        try {
            fs.write(path, buildNotebookDoc(request, path));
        } catch (Exception e) {
            log.warn("Auto-save before run failed for {}: {}", path, e.toString());
        }
    }

    /** Serialize the request the same way the UI saves a notebook (.algolab / legacy .dsa). */
    private static String buildNotebookDoc(RunRequest request, String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;

        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"name\": ").append(FsService.jsonString(name)).append(",\n");
        sb.append("  \"language\": \"java\",\n");
        sb.append("  \"javaVersion\": ")
                .append(request.javaVersion() == null ? "null" : request.javaVersion()).append(",\n");
        sb.append("  \"code\": ")
                .append(FsService.jsonString(request.code() == null ? "" : request.code())).append(",\n");
        sb.append("  \"testCases\": [");
        List<TestCaseDto> testCases = request.testCases() == null ? List.of() : request.testCases();
        if (!testCases.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < testCases.size(); i++) {
                TestCaseDto tc = testCases.get(i);
                if (i > 0) {
                    sb.append(",\n");
                }
                sb.append("    {\n");
                sb.append("      \"id\": ").append(json(tc.id())).append(",\n");
                sb.append("      \"name\": ").append(json(tc.name())).append(",\n");
                sb.append("      \"stdin\": ").append(json(tc.stdin())).append(",\n");
                sb.append("      \"expected\": ").append(json(tc.expected())).append("\n");
                sb.append("    }");
            }
            sb.append('\n');
        }
        sb.append("  ]\n");
        return sb.append("}\n").toString();
    }

    private static String json(String s) {
        return s == null ? "null" : FsService.jsonString(s);
    }

    public static record TestCaseDto(String id, String name, String stdin, String expected) {}
    public record RunRequest(String path, String code, List<TestCaseDto> testCases, String jdk, Integer javaVersion) {}
}
