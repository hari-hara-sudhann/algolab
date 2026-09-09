# Websockets for Lint & Autocomplete — Exploration Spec

Status: **Draft for review** (no code written yet)
Date: 2026-09-06
Audience: AlgoLab (DSA Playground) maintainer

---

## 1. Request summary

Three complaints/ideas, explored together:

1. **Linting is very slow.** After ~900 ms of idle typing, the whole file is
   POSTed to `/api/lint`, which writes a temp file and spawns a **fresh
   `javac` process per request** (≈320 ms of pure JVM startup measured on this
   machine, even for a trivial file). Markers therefore land ~1.3–1.5 s after
   the user stops typing, and rapid typing can stack multiple concurrent
   `javac` processes. There is no cancellation.
2. **Autocomplete is shallow.** Today it is a purely client-side, static list
   of snippets/keywords (`sout`, `fori`, `Arrays.sort`, …). No member
   suggestions after a receiver (`Arrays.`, `sc.`), no in-scope variables.
3. **Test-case numbering is confusing.** The default case shows as “Case 1”,
   but cases created afterwards start at “Case 5”, “Case 6”, … (details in §7).
4. **Original prompt**: “Explore the possibilities of including websockets to
   improve linting and autocompletion features.”

The spec must **honestly evaluate** whether websockets help, then recommend an
architecture **with evidence**, and design the chosen approach.

### Interview decisions (locked in)

| Topic | Decision |
| --- | --- |
| Scope | All three areas, **plus run streaming** (§6), in one spec |
| Compile model | In-process `javax.tools` compile service in the app JVM, with fallback to today’s `javac` spawn when the chosen JDK ≠ the app’s runtime JDK (§4.1) |
| Completion depth | **Client-side upgrade only** — no LSP, no server-assisted completions |
| Completion must-haves | Members after a receiver (`Arrays.`, `sc.`) **and** in-scope variable suggestions; existing snippets kept; signature help not required |
| Completion symbol scope | JDK common types **+ user-defined classes in the same file** |
| Run streaming | Included; each case’s **verdict appears the moment it finishes** (no live stdout trickle needed) |
| Transport stance | Spec evaluates **WebSocket vs SSE vs plain HTTP** fairly and picks with evidence |
| Concurrency model | Single local user, single session; route by doc path; no session/broker management |
| Stale markers | **Clear markers immediately on every edit** (no stale squiggles linger) |
| Diagnostics | **Two-tier**: instant client-side syntax pass (tree-sitter already in-page) + fast server semantic pass |
| Lint cadence | Trailing debounce ~300–500 ms (default 400 ms) |
| Lint coverage | Active document only (same as today) |
| Perf target | Semantic markers **<100 ms** after debounce fires (“feels instant”) |
| Case naming | **Count-based**: with N cases in a doc, next default name is `Case N+1` (deletion gaps not reused) |
| Case ids | **Per-document** ids (`case-1`…`case-N`), rebuilt deterministically on load |
| Delivery | One coherent change (all four workstreams together) |

---

## 2. Current behavior & measured evidence

### 2.1 The lint path (today)

```
typing pause (900 ms debounce, scheduleLint)
  → runLint(): POST /api/lint { code, jdk, javaVersion }        (whole file, every time)
    → JavaRunner.lint(): temp dir → write <Class>.java → spawn javac -Xlint:all …
    → parse javac stderr → Monaco markers (seq guard: only latest response applies)
```

Contributing factors to the perceived lag:

1. **Process spawn dominates.** Cold `javac` on a ~10-line file measured
   **0.32–0.35 s** (5 runs) on this machine (GraalVM JDK 21). The JVM startup,
   not the compile, is the cost.
2. **No shared work.** Every request re-creates the temp dir, re-writes the
   file, re-spawns a JVM. Identical code between pauses is deduped
   (`lastLintKey`) but any edit invalidates it.
3. **No cancellation.** A new lint fires while old `javac` processes are still
   running; responses are discarded by a client-side sequence guard, but the
   server work is wasted and can stack.
4. **Debounce is long relative to compile.** 900 ms + ~400–600 ms round trip =
   markers up to ~1.5 s after the last keystroke.

### 2.2 Measured alternative: in-process compiler

Same trivial file compiled via `javax.tools.ToolProvider.getSystemJavaCompiler()`
inside an already-running JVM:

| Call | Time |
| --- | --- |
| 1st (class load / warm-up) | ~250 ms |
| 2nd | ~31 ms |
| 3rd–5th | ~18–27 ms |

**~10–15× faster once warm.** This is the same trick Maven/Gradle daemons and
the Mill blog post’s analysis point to: the JVM startup is the dominant cost,
so keep the compiler alive.

### 2.3 Implication for the websocket question

The bottleneck is **process-per-request compilation, not transport**. Local
HTTP vs WebSocket vs SSE differs by single-digit milliseconds at most; the
compile model differs by ~300 ms per pause. A websocket layer *by itself* would
barely move the perceived latency — **any** fast design must first kill the
per-lint JVM spawn. The transport evaluation (§5) is therefore about the right
shape for push/streaming (run verdicts), not about fixing lint latency.

---

## 3. Goals & non-goals

### Goals

1. Semantic markers (errors/warnings) land **<100 ms** after the debounce
   (default 400 ms) fires, on the active document.
2. Syntax-level mistakes (unbalanced `{`, stray `)`, obvious tree-sitter
   `ERROR`/`MISSING` nodes) get **instant** red squiggles from a client-side
   pass — cleared immediately on further typing.
3. Autocomplete: typing `Arrays.` / `sc.` / `arr.` / `n.` (own class)
   suggests that type’s members; declared local variables and fields are
   suggested; current snippets keep working.
4. Test-case verdicts (PASS/FAIL/TIMEOUT/ERROR + stdout + duration) appear as
   each case finishes, instead of all-at-once after the last case.
5. Test-case default naming/ids fixed: per-doc `case-1…case-N`; next default
   name `Case N+1`.
6. Honest transport recommendation (WS vs SSE vs plain HTTP) backed by the
   measurements above and the single-user reality of the app.

### Non-goals (explicitly out)

- Full Java LSP (Eclipse JDT-LS or similar): hundreds of MB of downloads,
  slow first start, high memory — contradicts “local-first / offline / a
  notebook, not an IDE” and the roadmap note *“if building it starts taking
  time away from solving DSA problems, stop building it.”*
- Server-assisted completions (any completion request to the backend).
- Signature help / hover tooltips / go-to-definition / refactoring.
- Live stdout **trickle** during a run (only per-case verdicts).
- Background linting of non-active open tabs.
- Multi-user or multi-session support.

---

## 4. Design — fast linting

### 4.1 Backend: an in-process compile service with a javac fallback

New Spring service `CompileService` (adjacent to `JavaRunner`, which stays for
`run`): keeps one warm `JavaCompiler` per `(release)` used, obtained from
`ToolProvider.getSystemJavaCompiler()`.

- **When to use in-process:** the chosen JDK (UI setting) *is* the JDK the app
  itself runs on (`JdkManager.defaultInstallation()` or runtime `java.home`).
  `JdkManager` already exposes the discovered installations; compare homes.
- **When to fall back to a `javac` spawn (unchanged code path):** the user
  picked a different JDK (e.g., app on 21, linting with 17). Cross-JDK
  in-process compilation via `--release` is possible but the app already
  routes by actual JDK home; keep behavior identical there. Optionally note in
  the UI (“linting uses app JDK; full compile speed only when they match”) —
  **decide at implementation time; not required.**
- Compile **into a per-JDK in-memory scratch**: `StandardJavaFileManager` over
  a temp dir per job (fast) — do **not** run user code, only compile.
  Cleanup via the file manager + `deleteOnExit`, same `deleteDirQuietly`-style
  helper as today.
- `-Xlint:all` and `--release` flags must match the current `compileArgs`
  semantics exactly so marker output stays identical.
- Guard with the existing `MAX_MARKERS = 300` cap and 15 s timeout *semantics*
  (an in-process task can’t be killed mid-flight the way a process can —
  see §4.3).
- Threading: a small executor (e.g., 2 threads) so a long compile never blocks
  other requests; jobs carry a **generation id** so superseded results can be
  dropped (§4.3).

**Fallback correctness note:** when the chosen JDK ≠ app runtime, fall back to
the *current* per-request `javac` spawn so no behavior regresses for users who
switch JDKs (compile stays correct; only that config keeps today’s latency).

### 4.2 Client: two-tier diagnostics

- **Tier 1 — syntax (instant, client-side).** Reuse the existing whole-file
  tree-sitter Java parse already running in-page (`ts-highlight.js` — it keeps
  a per-model, versioned parse cache). Walk the tree for `ERROR` /
  `MISSING` / `UNEXPECTED` nodes and map them to Monaco markers under owner
  `'syntax'`.
  - Runs with a very short debounce (≤150 ms) or synchronously on change.
  - **Cleared on every edit** (user decision: no stale squiggles), then
    re-derived from the fresh tree. This is cheap because the parse is already
    needed for highlighting and is versioned/cached.
  - Not a replacement for javac: it cannot flag “cannot find symbol”, type
    errors, etc.
- **Tier 2 — semantic (server, fast).** Existing `runLint()` flow, minus the
  900 ms delay and minus the process spawn (per §4.1):
  - Trailing debounce **400 ms** after typing stops (scheduleLint default).
  - Markers applied under owner `'semantic'`.
  - Clear `'semantic'` markers immediately on edit too, so only tier-1
    markers are visible mid-typing.
- **Problems badge** (`#problems`): show combined counts
  (e.g. `⚠ 1 error · 2 warnings`); marker source can be a tooltip detail later
  if desired.
- Keep the existing `lastLintKey` dedupe and active-doc-only behavior.

### 4.3 Race handling (no stale markers, no wasted work)

Client:

- On every editor change: clear `'syntax'` + `'semantic'` markers for the
  active model, bump a `docGen` counter for the doc, reset the debounce timer.
- When the debounce fires: snapshot `docGen`, send lint job with
  `{ path, generation: docGen, code, jdk, javaVersion }`.
- Apply a response only if `generation` still equals the doc’s current
  `docGen` **and** the doc is still the active one (replaces today’s
  `lintSeq` guard).

Server:

- Per-request **generation id** lets the service skip or abandon work:
  - If a compile is queued/running for the same `(doc path, release)` and a
    newer generation arrives, the old job result is discarded (and, for the
    in-process case, the compile task is *not* started or is allowed to finish
    but never returned — in-process javac can’t be forcibly killed, so we
    discard rather than interrupt).
  - Optionally maintain a tiny in-flight registry so repeated `javac`-fallback
    spawns for superseded generations are not started at all.

### 4.4 What does NOT change

- `JavaRunner.lint`/`run` signatures used by `/api/run` keep working.
- Marker JSON shape (`line`, `column`, `severity`, `message`) unchanged.
- Settings toggles (Live linting on/off) and JDK/level change → re-lint
  behavior unchanged.
- Offline/local-first property preserved (no new network dependency).

---

## 5. Transport evaluation: WebSocket vs SSE vs plain HTTP

### 5.1 The actual message flows

| Flow | Direction | Frequency | Size | Shape |
| --- | --- | --- | --- | --- |
| Lint job | client → server | per debounce pause | small (code) | request/response |
| Lint result | server → client | same | small (markers) | correlated to one request |
| Run job | client → server | on ▶ Run | small–medium | request → **multiple responses** (one verdict per case) |
| Completions | — | — | — | **client-side only** (out of scope for transport) |

Only **run verdicts** are genuinely multi-response server→client. Everything
else is plain request/response. And the app has **one user, one browser tab,
localhost**.

### 5.2 Options

**A. Plain HTTP (POST) + HTTP response streaming for runs.**
Run endpoint returns `200` with `Content-Type: application/x-ndjson` (or
`text/event-stream`) and writes one JSON verdict line per finished case; the
client reads the body incrementally via `fetch` + `ReadableStream`. Lint stays
a normal POST returning JSON.
- Zero new dependencies (Spring `StreamingResponseBody` / `SseEmitter` are in
  `spring-boot-starter-web`).
- Simplest, matches request/response correlation naturally; reconnects are
  just retries.
- No push capability (server can’t spontaneously talk to the page) — not
  needed here.

**B. Server-Sent Events (SSE).**
A long-lived `EventSource`/`fetch` stream from server→client. Works when you
need ongoing server push without per-message request correlation.
- Trivial to consume client-side; auto-reconnect built into `EventSource`
  (GET-only though — a POST-based variant needs `fetch` + stream, i.e. it
  converges on option A).
- Would need either one stream per job or an event bus multiplexing doc paths.

**C. Raw WebSocket.**
Full-duplex binary/text frames; the only option that helps when the *client*
also sends many small messages that benefit from a persistent connection, or
when the server must push to many subscribers.
- Here: client messages are tiny, infrequent job starts; subscribers = 1.
- Adds `spring-boot-starter-websocket` (or raw `WebSocketHandler`), a
  handshake, heartbeats, reconnection + re-sync logic on the client, and
  application-level request/response correlation that HTTP already gives us
  for free.
- STOMP-over-WS / SockJS are heavier still (topics, broker) — overkill for a
  single-session localhost tool.

### 5.3 Recommendation (for the spec’s sign-off)

**Do not introduce websockets.** Given the single-user, localhost, notebook
reality and the measured data:

1. **Lint latency** is fixed by the compile model (§4), not transport — keep
   plain `POST /api/lint`.
2. **Run verdicts-as-they-finish** is delivered with **HTTP response
   streaming** (option A): one POST, NDJSON/SSE-shaped body, verdicts flushed
   per case. Reuses existing `api()` fetch plumbing with a body reader.
3. **SSE (option B)** is the fallback if we later want unsolicited pushes
   (e.g., server-initiated re-lint after a JDK change); **WebSocket (option C)**
   is documented as the right call only if we someday add real-time
   collaboration, remote sessions, or a chat-style channel — all on the
   project’s “future ideas — DO NOT BUILD” list.

This is the *honest* answer to “include websockets to improve linting”: the
websocket would carry ~20–30 ms of work over a channel whose setup and
reconnect complexity exceeds the messages it transports. Keep the option open
in the protocol design (§8) so a later swap to WS/SSE doesn’t change the
backend job model.

*(If reviewers still want websockets for their own sake: implement §8 over a
raw `WebSocketHandler`; the job/generation/message schema is identical — only
the transport adapter differs. Call this out as an explicit
decision-for-review at the end.)*

---

## 6. Design — run streaming (verdicts as they finish)

### 6.1 Today

`POST /api/run` → `JavaRunner.run` compiles once, then runs cases **serially**
(TestCaseRunner, 5 s timeout each), and returns every result only after the
last case finishes. The UI (`renderOutput`) paints all verdicts at once; the
▶ button shows “Running…” the whole time.

### 6.2 Target

- Same compile-once-then-run-cases model; the *response* becomes a stream:
  - `event: started` (compile phase) — optional heartbeat
  - one `event: verdict` line **as each case completes**:
    `{ path, caseId, status, stdout, stderr, durationMs, error }`
  - `event: done` at the end.
- Client paints each verdict into the per-case output tab the moment it
  arrives (`renderOutput` is already idempotent per case id — verify during
  implementation that partial `results` render cleanly).
- `RunResponse` JSON contract stays available for a non-streaming fallback if
  the request can’t stream (old clients/tests) — or simply accept the stream
  everywhere since backend + UI ship together.
- Keep auto-save-before-run, per-case expected-output comparison, and the
  `runInFlight` guard exactly as today.
- If code has a compile error: stream ends after a single `verdict`-style
  COMPILE_ERROR line per case (as today) or an `error` event with the compile
  diagnostics — implementation detail; UI must show the same ✗ per case.

### 6.3 Failure modes

- Mid-stream disconnect: server keeps running the cases (results are
  discarded), client shows “connection lost”; on ▶ again the run re-executes —
  acceptable for a local tool; note it in the README/UI copy if needed.
- Timeouts/`ERROR` cases stream through as ordinary verdict events.

---

## 7. Design — test-case numbering fix

### 7.1 Root cause (confirmed in code)

`state.caseCounter` is a **global, session-wide counter**:

- `normalizeTestCases()` raises it to at least the *count of cases in every
  loaded notebook* (`state.caseCounter = Math.max(caseCounter, i + 1)`).
- The **default** case of a fresh doc calls `newCase('Case 1')`, which
  *increments* the counter and *consumes a number* even though the visible
  name is hardcoded “Case 1”.
- Subsequent `addCase()` → `newCase()` (no name) names from the inflated
  counter: “Case 5”, “Case 6”, …

Repro (matches the report):

1. Open `samples/merge-sort.algolab` (3 cases) → counter = 3.
2. Open/create a brand-new empty notebook → default case `newCase('Case 1')`
   bumps counter to 4 (name shown: “Case 1”).
3. Click **＋ Add** → `newCase()` → counter 5 → tab reads **“Case 5”**.
   Every subsequent add continues from there.

Secondary artifacts of the global counter: ids like `case-5` can collide
across open docs; default names in one doc depend on which other notebooks were
opened earlier in the session.

### 7.2 Fix

Decouple **ids** and **display names**, and scope both to the document:

- **Ids — per doc:** each doc gets its own small counter. On load, ids are
  rebuilt deterministically from the persisted list: keep a persisted id only
  if unique within the doc (today’s dedupe appends `-i` on collisions; keep),
  otherwise derive `case-<index+1>`. New case ids come from a per-doc counter
  seeded to the case count at load (`max(i+1)` over that doc only).
- **Default names — count-based:** `newCase()` derives the name from
  `doc.testCases.length + 1` → with N cases, the next auto-added case is
  `Case N+1`. The default first case of an empty doc stays “Case 1” (0 cases →
  Case 1). Deletion gaps are **not** reused (locked decision). Renames by the
  user are untouched (no auto-renaming of existing cases).
- Remove the global `caseCounter` from `state`; it becomes per-doc
  (`doc.nextCaseNum`), initialized in `newDoc`/`normalizeTestCases` from that
  doc’s own list only. `normalizeTestCases` must no longer touch any shared
  counter.
- Edge cases to cover in tests:
  - Fresh doc → default “Case 1”, add → “Case 2”, id `case-2`.
  - Open 3-case notebook → add → “Case 4”, id `case-4`.
  - Delete “Case 2” from a 3-case doc → add → “Case 4” (count-based, gap not
    reused).
  - Two notebooks open; adding cases in one must not affect the other’s names
    or ids.
  - Reload/reopen a saved doc → names and ids stable; adding after reopen
    continues from count (no drift from prior session).

### 7.3 Why not the alternatives (locked out by interview)

- Smallest-free-number naming: rejected (gaps not reused).
- Persisted per-notebook next-case counter in the file: rejected (count-based
  is stateless and deterministic on load).
- Keep-global-unique ids with fixed names only: rejected (per-doc ids chosen).

---

## 8. Proposed message/protocol contracts

Even though §5.3 recommends **not** adopting websockets, define the job model
transport-agnostically so WS/SSE remain drop-in options.

### Lint (plain POST — unchanged shape)

Request: `POST /api/lint` `{ path?, code, jdk, javaVersion, generation }`
Response: `200` `{ markers: [{ line, column, severity, message }], generation }`

### Run (HTTP stream — new)

Request: `POST /api/run` `{ path, code, testCases, jdk, javaVersion }`
Response: `200`, `Content-Type: application/x-ndjson` (one JSON object per
line, flushed as produced):

```json
{ "type": "started", "caseId": "case-1", "caseName": "Case 1" }
{ "type": "verdict", "caseId": "case-1", "status": "PASS", "stdout": "…", "stderr": "", "durationMs": 12, "error": null }
{ "type": "started", "caseId": "case-2", "caseName": "Case 2" }
{ "type": "verdict", "caseId": "case-2", "status": "TIMEOUT", "stdout": "", "stderr": "", "durationMs": 5000, "error": "Time limit exceeded (5000 ms)" }
{ "type": "done" }
```

Compile error → `{ "type": "compileError", "markers": […] }` then `done`
(UI shows ✗ per case as today).

### (Not built) WebSocket variant for review

One socket per page; messages are the above NDJSON objects wrapped as
`{ type: 'lint'|'run', path, generation, payload }` both directions; server
acks with the matching response objects. Reconnect = client re-sends the
latest pending job with the same `generation`.

---

## 9. Frontend workstreams (indexed)

1. **Two-tier markers** (`app.js`): owner-separated marker layers
   (`'syntax'`, `'semantic'`), clear-on-edit, `docGen` counter, badge counts.
2. **Debounce + sequencing**: `scheduleLint` default 400 ms; send + validate
   `generation`; drop stale responses.
3. **Run stream client**: replace `api('/api/run')` all-at-once handling with a
   `fetch` body-stream reader that calls `renderOutput()` per verdict;
   preserve button-busy + auto-save behavior.
4. **Case numbering fix** (§7).
5. **Autocomplete engine** (§10) + tests.

No UI copy/layout changes required except the Problems badge wording (kept).

---

## 10. Design — client-side autocomplete upgrade

### 10.1 Where completions come from

Extend (not replace) the existing `JAVA_COMPLETIONS` static list with an
engine that answers two questions for the current cursor:

1. **“What is the receiver?”** Parse the text before the cursor: if it ends in
   `expr.`, identify `expr` as one of: a declared local/field name, a static
   type name (`Arrays`, `Math`, `Integer`, …), a literal (`"..."` → String),
   or a chained known member (`System.out` → PrintStream).
2. **“Which members does that type offer?”** Look it up in shipped tables or in
   the same-file user classes.

When the receiver is unknown (or there is none), fall back to today’s
keyword/snippet suggestions.

### 10.2 Symbol table source (must-have behaviors)

Must support: **members after a receiver** + **in-scope variable suggestions**,
for **JDK common types + same-file user types**.

- **Variables in scope:** walk the cached tree-sitter AST from the cursor’s
  enclosing method/class outward; collect fields, method params, and locals
  declared in enclosing blocks. Each `variable_declarator` gives
  `name → declared type token` (normalize generics to the raw type:
  `ArrayList<String>` → `ArrayList`). Suggest these names when the user types
  a prefix with no `.`; on `.`, resolve the name to its type.
- **JDK member tables (shipped, static):** per type, a hand-curated list
  focused on DSA practice:
  - classes: `String`, `StringBuilder`, `Integer`, `Long`, `Double`,
    `Math`, `Arrays`, `Collections`, `Scanner`, `ArrayList`, `LinkedList`,
    `HashSet`, `TreeSet`, `LinkedHashSet`, `HashMap`, `TreeMap`,
    `LinkedHashMap`, `PriorityQueue`, `ArrayDeque`, `Stack`, `Iterator`,
    `List`, `Set`, `Map`, `PrintStream` (for `System.out`), plus
    `int[]`/arrays (`length`, `clone`) and String-literal receivers.
  - `System` maps specially (`out` → PrintStream, `err`, `currentTimeMillis`).
  - Built from a small table format `{ type → [{ name, params, returns,
    detail, doc? }] }` — same rough shape as today’s entries so Monaco
    rendering code is shared. Keep file size small (target «10 KB gzipped
    uncompressed data).
- **Same-file user types:** when the declared type token matches a top-level
  or nested class/record defined in the current file, collect that type’s
  methods + fields (+ inherited `Object` members) from the AST. Include
  constructors? Not needed for member-dot completion.
- **Chained resolution:** allow 1–2 hops through known members when the table
  says so (e.g., `System.out.` → PrintStream). Arbitrary Java type inference
  is explicitly out of scope (would need a real compiler model).

### 10.3 Mechanics

- Reuse the tree-sitter infrastructure already in `ts-highlight.js`: it
  caches a per-model whole-file parse by version. Refactor minimally to expose
  `getParseTree(model)` (or a `SymbolTable` builder) so both highlighting and
  completions share one parse — do **not** parse twice.
- Monaco: register one `CompletionItemProvider` (already exists) that:
  - if cursor is after `.` → receiver analysis + member suggestions;
  - else → snippets + in-scope names (prefix-filtered by Monaco as today).
  - kind mapping: reuse the existing `kindMap`; members get
    `CompletionItemKind.Method/Field/Class` from the table’s `kind`.
- Guard behind the existing Autocomplete setting (`suggestEnabled`).

### 10.4 Known limitations to document in the code/README

- No generic-aware members (an `ArrayList<Integer>` gets the same members as
  `ArrayList<String>` — fine for DSA usage).
- No inference through method return values beyond the 1–2 documented hops
  (e.g., `sc.nextInt()` → int is *not* resolved).
- Wildcard `import java.util.*` is assumed when common collections appear
  without an explicit import (consistent with today’s snippet behavior).

---

## 11. Test plan / acceptance criteria

Project has no unit tests today (only a throwaway `.ui-test.mjs` headless
Chrome script) — spec assumes we add focused checks where cheap, plus manual
UI verification via that script pattern.

1. **Lint latency (perf):** with a ~50-line file, time from last keystroke to
   semantic markers on this machine:
   - today: ~1.3–1.5 s (900 ms debounce + ~0.4 s spawn round trip)
   - target: debounce 400 ms + **<100 ms** compile/apply.
   Measure before/after with the app’s runtime JDK chosen (in-process path).
2. **No stale markers:** typing continuously shows only current-file markers;
   an old “error on line 3” never lingers after line 3 is fixed.
3. **Two-tier:** unbalanced `{` flags instantly (client pass); a genuine type
   error flags after the semantic round trip.
4. **Completions:** `Arrays.` suggests `sort`; `sc.` (a declared
   `Scanner sc`) suggests `nextInt`; `n.` where `Node n` is user-declared in
   the same file lists Node’s methods; typing a variable prefix suggests it;
   no `.` → snippets still appear.
5. **Run streaming:** with 3 cases, verdicts paint one-by-one as each case
   finishes; a case that sleeps 2 s shows its two faster siblings first.
6. **Numbering:** the repro in §7.1 yields “Case 2” not “Case 5”; the other
   edge cases in §7.2 pass.
7. **Regression:** JDK/level switches still re-lint; fallback (chosen JDK ≠
   app JDK) still lints correctly (just slower); save/load; Vim mode; existing
   `.ui-test.mjs` checks still green.

---

## 12. Milestones (one coherent change; internal order by dependency)

1. **Case-numbering fix** (§7) — independent, no risk to editor plumbing;
   can land first inside the same change.
2. **Backend compile service** (§4.1) + wiring `JavaRunner.lint` to it with
   the javac fallback; keep `/api/lint` contract.
3. **Client two-tier + debounce/sequencing** (§4.2–4.3).
4. **Autocomplete engine** (§10) on top of the shared tree-sitter parse.
5. **Run streaming** (§6) over HTTP NDJSON; client verdict-by-verdict paint.
6. Perf measurement + acceptance checks (§11), then ship.

Each step keeps the app green (backend changes are additive; marker payloads
unchanged) so the change can be reviewed as one PR or split at these seams.

---

## 13. Resolved decisions (approved 2026-09-06)

All §13 items are now locked by the maintainer:

- **No websockets.** The recommendation stands: `POST /api/lint` stays; runs
  stream over HTTP NDJSON; the §8 WebSocket variant remains designed but
  unbuilt. Rationale: transport was never the bottleneck (Appendix A).
- **Mismatched-JDK lint** keeps today’s slower-but-correct `javac`-spawn path;
  no out-of-process worker for now.
- **NDJSON** (`application/x-ndjson`) for the run response stream.
- **Combined Problems badge** — one error/warning count; no syntax/semantic
  tagging in the badge.
- Delivery: implement the milestones in §12 as one coherent change, starting
  with the case-numbering fix.

---

*Appendix A — measurement methodology*: `/usr/bin/time -p javac -Xlint:all`
over 5 runs of a 6-line class (JDK 21.0.11 GraalVM): 0.32–0.35 s real.
In-process `ToolProvider.getSystemJavaCompiler()` over the same source in a
warm JVM: first call ~248 ms, calls 2–5: 18–31 ms. Numbers are machine-local
and will vary; the ~10× gap is the robust takeaway.
