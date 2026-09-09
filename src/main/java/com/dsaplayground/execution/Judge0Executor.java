package com.dsaplayground.execution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dsaplayground.service.Env;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Judge0 execution fallback: runs the notebook against the hosted (or
 * self-hosted) Judge0 API when no usable local JDK exists. All Judge0
 * specifics live here — the rest of AlgoLab only sees {@link JavaExecutor}.
 *
 * <p>Configuration comes from the environment (see {@code .env.example}):
 * <ul>
 *   <li>{@code JUDGE0_API_URL} — base URL, defaults to {@code https://ce.judge0.com}
 *       (the unauthenticated Judge0 Cloud preview)</li>
 *   <li>{@code JUDGE0_JAVA_LANGUAGE_ID} — Judge0 language id for Java, default
 *       {@code 91} (Java JDK 17 on current CE images)</li>
 * </ul>
 *
 * <p>The Judge0 Cloud preview needs no authentication, so no credential is
 * ever configured or sent.
 *
 * <p>Judge0 compiles submissions as {@code Main.java}, so source whose public
 * class has any other name is adapted (class renamed to {@code Main} with
 * references rewritten outside strings/comments) before submission.
 */
@Service
public class Judge0Executor implements JavaExecutor {

    private static final Logger log = LoggerFactory.getLogger(Judge0Executor.class);

    static final String DEFAULT_API_URL = "https://ce.judge0.com";
    static final int DEFAULT_JAVA_LANGUAGE_ID = 91; // Java (JDK 17.0.6) on Judge0 CE v1.13+

    /** Judge0 status ids ≥ 3 are final (3 Accepted … 6 Compilation Error …). */
    private static final int FINAL_STATUS_FROM = 3;
    private static final int STATUS_ACCEPTED = 3;
    private static final int STATUS_WRONG_ANSWER = 4;
    private static final int STATUS_TLE = 5;
    private static final int STATUS_COMPILE_ERROR = 6;

    private static final int CPU_TIME_LIMIT_SECONDS = 5;
    private static final int WALL_TIME_LIMIT_SECONDS = 10;
    private static final int MEMORY_LIMIT_KB = 256_000;

    private static final long POLL_INTERVAL_MS = 600;
    private static final long MAX_POLL_MS = 90_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);

    private final Judge0Config config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public Judge0Executor() {
        this.config = Judge0Config.fromEnv();
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        log.info("Judge0 executor configured: url={} languageId={}",
                config.apiUrl(), config.languageId());
    }

    /** Immutable Judge0 configuration resolved from the environment. */
    public record Judge0Config(String apiUrl, int languageId) {

        static Judge0Config fromEnv() {
            String url = Env.get("JUDGE0_API_URL");
            if (url == null || url.isBlank()) {
                url = DEFAULT_API_URL;
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            int languageId = DEFAULT_JAVA_LANGUAGE_ID;
            String rawId = Env.get("JUDGE0_JAVA_LANGUAGE_ID");
            if (rawId != null && !rawId.isBlank()) {
                try {
                    languageId = Integer.parseInt(rawId.trim());
                } catch (NumberFormatException e) {
                    log.warn("Judge0Config: ignoring non-numeric JUDGE0_JAVA_LANGUAGE_ID '{}'", rawId);
                }
            }
            return new Judge0Config(url, languageId);
        }
    }

    @Override
    public String id() {
        return "judge0";
    }

    @Override
    public String label() {
        return "Judge0";
    }

    @Override
    public boolean isAvailable() {
        return config.apiUrl() != null && !config.apiUrl().isBlank();
    }

    /** Config exposure for status text (URL only). */
    public Judge0Config config() {
        return config;
    }

    @Override
    public void runToSink(String code, List<TestCase> testCases, ExecOptions options,
                          Consumer<TestCaseResult> sink) {
        List<TestCase> cases = testCases == null ? List.of() : testCases;
        if (code == null || code.isBlank()) {
            emitForEach(cases, sink, new TestCaseResult("", "ERROR", "", "", 0L, "Code is empty"));
            return;
        }
        try {
            String source = adaptSourceForJudge0(code);
            List<String> tokens = submitBatch(source, cases);
            pollAndEmit(tokens, cases, sink);
        } catch (Exception e) {
            log.warn("Judge0Executor: run failed: {}", e.toString());
            emitForEach(cases, sink, new TestCaseResult("", "ERROR", "", "", 0L,
                    "Judge0 execution failed: " + e.getMessage()));
        }
    }

    /* ---------------- submission lifecycle ---------------- */

    /** Submit every test case in one batch call; returns one token per case. */
    private List<String> submitBatch(String source, List<TestCase> cases) throws Exception {
        ArrayNode submissions = mapper.createArrayNode();
        for (TestCase tc : cases) {
            ObjectNode sub = submissions.addObject();
            sub.put("source_code", source);
            sub.put("language_id", config.languageId());
            sub.put("stdin", tc.stdin() == null ? "" : tc.stdin());
            if (tc.expected() != null) {
                sub.put("expected_output", tc.expected());
            }
            sub.put("cpu_time_limit", CPU_TIME_LIMIT_SECONDS);
            sub.put("wall_time_limit", WALL_TIME_LIMIT_SECONDS);
            sub.put("memory_limit", MEMORY_LIMIT_KB);
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("submissions", submissions);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl() + "/submissions/batch?base64_encoded=false&wait=false"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Judge0 batch submit returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body(), 300));
        }
        JsonNode root = mapper.readTree(response.body());
        // Judge0 returns a bare array ([{token}, …]) on some versions and
        // {"submissions":[{token}, …]} on others — accept both.
        JsonNode list = root.isArray() ? root : root.path("submissions");
        List<String> tokens = new ArrayList<>();
        for (JsonNode node : list) {
            String token = node.path("token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("Judge0 batch submit returned a submission without a token");
            }
            tokens.add(token);
        }
        if (tokens.size() != cases.size()) {
            throw new IllegalStateException("Judge0 returned " + tokens.size()
                    + " tokens for " + cases.size() + " test cases");
        }
        return tokens;
    }

    /** Poll until every submission is final, emitting results as they complete. */
    private void pollAndEmit(List<String> tokens, List<TestCase> cases,
                             Consumer<TestCaseResult> sink) throws Exception {
        Map<String, TestCase> byToken = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            byToken.put(tokens.get(i), cases.get(i));
        }
        List<String> pending = new ArrayList<>(tokens);
        long deadline = System.currentTimeMillis() + MAX_POLL_MS;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
            JsonNode list = fetchSubmissions(pending);
            for (JsonNode sub : list) {
                String token = sub.path("token").asText("");
                if (!pending.contains(token)) {
                    continue;
                }
                int statusId = sub.path("status").path("id").asInt(0);
                if (statusId < FINAL_STATUS_FROM) {
                    continue; // still in queue / processing
                }
                pending.remove(token);
                sink.accept(toResult(byToken.get(token), sub, statusId));
            }
        }
        for (String token : pending) {
            log.warn("Judge0Executor: submission {} did not finish within {} ms", token, MAX_POLL_MS);
            TestCase tc = byToken.get(token);
            sink.accept(new TestCaseResult(tc.id(), "ERROR", "", "", 0L,
                    "Judge0 did not return a result within " + MAX_POLL_MS + " ms"));
        }
    }

    /** GET /submissions/batch?tokens=a,b,c — returns the current state of each. */
    private JsonNode fetchSubmissions(List<String> tokens) throws Exception {
        String joined = String.join(",", tokens);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl() + "/submissions/batch?tokens=" + joined + "&base64_encoded=false"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Judge0 poll returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body(), 300));
        }
        JsonNode root = mapper.readTree(response.body());
        return root.isArray() ? root : root.path("submissions");
    }

    /* ---------------- result mapping ---------------- */

    private TestCaseResult toResult(TestCase tc, JsonNode sub, int statusId) {
        String stdout = sub.path("stdout").asText("");
        String stderr = sub.path("stderr").asText("");
        String compileOutput = sub.path("compile_output").asText("");
        String message = sub.path("message").asText("");
        String description = sub.path("status").path("description").asText("");

        long durationMs = 0L;
        // Judge0 reports CPU seconds, sometimes as a number, sometimes as a
        // string ("0.087") depending on version — accept both.
        JsonNode time = sub.get("time");
        if (time != null) {
            String raw = time.isTextual() ? time.asText() : String.valueOf(time.doubleValue());
            try {
                durationMs = (long) Math.round(Double.parseDouble(raw.trim()) * 1000);
            } catch (NumberFormatException e) {
                // leave 0
            }
        }

        return switch (statusId) {
            case STATUS_ACCEPTED, STATUS_WRONG_ANSWER -> {
                // Judge0 compares stdout against expected_output itself, but we
                // recompute with our own normalisation so remote and local
                // PASS/FAIL mean exactly the same thing.
                boolean pass = tc.expected() == null || TestCaseResult.matches(stdout, tc.expected());
                yield new TestCaseResult(tc.id(), pass ? "PASS" : "FAIL", stdout, stderr, durationMs, null);
            }
            case STATUS_TLE, 14, 20 -> new TestCaseResult(tc.id(), "TIMEOUT", stdout, stderr, durationMs,
                    "Time limit exceeded (" + CPU_TIME_LIMIT_SECONDS + " s)");
            case STATUS_COMPILE_ERROR -> {
                String err = compileOutput.isBlank() ? "Compilation error" : compileOutput.trim();
                yield new TestCaseResult(tc.id(), "COMPILE_ERROR", "", "", durationMs, err);
            }
            case 15, 18, 19 -> new TestCaseResult(tc.id(), "ERROR", stdout, stderr, durationMs,
                    "Memory limit exceeded");
            case 7, 8, 9, 11, 12, 13, 16, 17 -> {
                String detail = !stderr.isBlank() ? stderr.trim()
                        : (!message.isBlank() ? message : "Runtime error (" + description + ")");
                yield new TestCaseResult(tc.id(), "ERROR", stdout, stderr, durationMs, detail);
            }
            default -> new TestCaseResult(tc.id(), "ERROR", stdout, stderr, durationMs,
                    message.isBlank() ? "Judge0 status: " + description : message);
        };
    }

    /* ---------------- source adaptation (Judge0 requires Main.java) ---------------- */

    private static final Pattern CLASS_DECL_PATTERN = Pattern.compile(
            "(\\bpublic\\s+)?(\\bfinal\\s+)?class\\s+([A-Za-z0-9_$]+)");

    /**
     * Judge0 writes every Java submission to {@code Main.java}, so the source
     * must declare {@code public class Main} (or a package-private {@code Main}
     * carrying {@code main}). Notebooks may use any class name — rename the
     * declared class to {@code Main} and rewrite references outside string
     * literals and comments.
     */
    static String adaptSourceForJudge0(String code) {
        String className = detectClassName(code);
        if (className.equals("Main")) {
            return code;
        }
        String renamed = renameClassDeclaration(code, className);
        return replaceIdentifierOutsideLiterals(renamed, className, "Main");
    }

    private static String renameClassDeclaration(String code, String className) {
        Matcher m = CLASS_DECL_PATTERN.matcher(code);
        if (!m.find()) {
            return code;
        }
        String replacement = (m.group(1) == null ? "" : m.group(1))
                + (m.group(2) == null ? "" : m.group(2))
                + "class Main";
        return m.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    /**
     * Replace every {@code oldName} identifier with {@code newName}, skipping
     * occurrences inside {@code //} and {@code /* *}{@code /} comments, string
     * literals, char literals and text blocks. Identifier boundaries use the
     * Java identifier alphabet, so {@code OldClass} in {@code oldClassName}
     * is untouched.
     */
    static String replaceIdentifierOutsideLiterals(String code, String oldName, String newName) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        int state = 0; // 0=code, 1=line comment, 2=block comment, 3=string, 4=char, 5=text block
        while (i < n) {
            char c = code.charAt(i);
            char next = i + 1 < n ? code.charAt(i + 1) : 0;
            switch (state) {
                case 1: // line comment
                    out.append(c);
                    if (c == '\n') {
                        state = 0;
                    }
                    i++;
                    break;
                case 2: // block comment
                    out.append(c);
                    if (c == '*' && next == '/') {
                        out.append('/');
                        i += 2;
                        state = 0;
                    } else {
                        i++;
                    }
                    break;
                case 3: // string literal
                    out.append(c);
                    if (c == '\\') {
                        if (i + 1 < n) {
                            out.append(code.charAt(i + 1));
                            i += 2;
                        } else {
                            i++;
                        }
                    } else if (c == '"') {
                        state = 0;
                        i++;
                    } else {
                        i++;
                    }
                    break;
                case 4: // char literal
                    out.append(c);
                    if (c == '\\') {
                        if (i + 1 < n) {
                            out.append(code.charAt(i + 1));
                            i += 2;
                        } else {
                            i++;
                        }
                    } else if (c == '\'') {
                        state = 0;
                        i++;
                    } else {
                        i++;
                    }
                    break;
                case 5: // text block
                    out.append(c);
                    if (c == '"' && next == '"' && i + 2 < n && code.charAt(i + 2) == '"') {
                        out.append("\"\"");
                        i += 3;
                        state = 0;
                    } else {
                        i++;
                    }
                    break;
                default: // code
                    if (c == '/' && next == '/') {
                        out.append("//");
                        i += 2;
                        state = 1;
                    } else if (c == '/' && next == '*') {
                        out.append("/*");
                        i += 2;
                        state = 2;
                    } else if (c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                        out.append("\"\"\"");
                        i += 3;
                        state = 5;
                    } else if (c == '"') {
                        out.append(c);
                        i++;
                        state = 3;
                    } else if (c == '\'') {
                        out.append(c);
                        i++;
                        state = 4;
                    } else if (isIdentifierStart(c) && code.startsWith(oldName, i)) {
                        int end = i + oldName.length();
                        boolean boundary = end >= n || !isIdentifierPart(code.charAt(end));
                        boolean prevOk = i == 0 || !isIdentifierPart(code.charAt(i - 1));
                        if (boundary && prevOk) {
                            out.append(newName);
                            i = end;
                        } else {
                            out.append(c);
                            i++;
                        }
                    } else {
                        out.append(c);
                        i++;
                    }
            }
        }
        return out.toString();
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isJavaIdentifierStart(c);
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /* ---------------- helpers ---------------- */

    private static String detectClassName(String code) {
        Matcher m = Pattern.compile("public\\s+(?:final\\s+)?class\\s+([A-Za-z0-9_$]+)").matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("class\\s+([A-Za-z0-9_$]+)").matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        return "Main";
    }

    private static void emitForEach(List<TestCase> cases, Consumer<TestCaseResult> sink,
                                    TestCaseResult template) {
        for (TestCase tc : cases) {
            sink.accept(new TestCaseResult(tc.id(), template.status(), template.stdout(),
                    template.stderr(), template.durationMs(), template.error()));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}