# AlgoLab

A local-first Java notebook for DSA practice. Write Java → make test cases → run →
see output → compare with expected → iterate. A "notebook for Java DSA", not an IDE and
not a competitive-programming platform.

> **Priority note:** this tool exists to make DSA practice better. If building it starts
> taking time away from solving DSA problems, stop building it.

## Quick start

```bash
./algolab                       # native macOS build; workspace = current directory
./algolab ~/DSA                 # workspace = ~/DSA
./algolab ~/DSA/merge-sort.algolab   # workspace = file's folder, file opens in the editor

# Or run the plain JVM jar (needs JDK 17+ / Maven 3.9+ for the first build):
mvn -q -DskipTests package
java -jar target/dsa-playground-0.1.0.jar
```

The app picks a free localhost port, starts, opens the browser, and runs in the
foreground — Ctrl+C stops the server. It needs internet only for its CDN assets
(Tailwind, Monaco Editor) and, when no local JDK exists, for Judge0.

## How Java execution works

AlgoLab is **local-first**: it prefers running your code with a JDK discovered on
your machine (offline, private, instant), and treats that as the normal case.

- **Local JDK (default).** `JdkManager` discovers JDKs from `JAVA_HOME`, the app's own
  runtime, macOS `/usr/libexec/java_home -V` + `~/Library/Java/JavaVirtualMachines` and
  `/Library/Java/JavaVirtualMachines`, Linux `/usr/lib/jvm` + SDKMAN, Windows
  `ProgramFiles\Java` + Eclipse Adoptium, and `PATH`. Code is compiled with the
  machine's own `javac` (honouring the `--release` level chosen in the UI) and run as a
  local subprocess with stdin piped in — fully offline.
- **Judge0 fallback.** Only when *no* usable local JDK exists does the app fall back to
  the [Judge0](https://judge0.com) API, so users without a JDK can still run Java. All
  Judge0 details (batch submission, polling, the `Main.java` class-name requirement) are
  isolated in `Judge0Executor` — the rest of the app just asks to "run Java" and receives
  structured results.

The two mechanisms are interchangeable behind `JavaExecutionService`; the UI shows a
small status-badge (Local JDK / Judge0 / No executor) so it's always clear where code
ran, without making execution mode a feature you have to care about.

### Judge0 configuration

Copy the template and fill in values — see [`.env.example`](.env.example):

```bash
cp .env.example .env
```

| Variable | Purpose | Default |
| --- | --- | --- |
| `JUDGE0_API_URL` | Judge0 base URL (`https://ce.judge0.com` public CE, or a self-hosted instance) | `https://ce.judge0.com` |
| `JUDGE0_API_KEY` | Optional credential; blank = no auth header | unset |
| `JUDGE0_AUTH_HEADER` | Header the key is sent as (`X-Auth-Token` for self-hosted, `X-RapidAPI-Key` for RapidAPI) | `X-Auth-Token` |
| `JUDGE0_JAVA_LANGUAGE_ID` | Judge0 language id for Java (`91` = JDK 17 on current CE images, `62` = OpenJDK 13) | `91` |

`.env` is **gitignored** — real keys live there, never in the repo. The app loads it at
startup (real exported environment variables always win), so `./algolab` picks it up
automatically from the working directory. Remote submissions run on Judge0's Java 17
image; code that needs a newer language level should be run with a local JDK instead.

## What's in this build

- **Spring Boot backend** serving the static frontend, a file-manager API, and the run
  pipeline (`JavaExecutionService` → local JDK or Judge0).
- **Explorer** rooted at the workspace directory: expandable folders, lazy-loaded,
  collapsible & resizable. Notebooks use the `.algolab` extension; legacy `.dsa` files
  are still readable and trigger a one-time offer to rename them.
- **Editor** — Monaco Editor with Java syntax highlighting, live offline linting (the
  machine's own `javac`), plus a **Vim mode** toggle.
- **Test cases** — right pane with a tab per case: name, stdin, and an optional
  *Expected output* checkbox that reveals the expected stdout box.
- **Output pane** — bottom of the screen, with a tab per test case. When expected output
  is set it shows a green ✓ / red ✗ verdict; stdout/stderr/duration/errors render for
  every case. Verdicts stream in as each case finishes.
- **Save / load** — Ctrl/Cmd+S saves the open `.algolab` notebook (human-readable JSON);
  running also saves the file first.
- **Theme + settings** — a gear menu on the top right holds JDK/Java-level picks, Vim,
  lint & autocomplete toggles, and editor themes (dark + light) with System-follow.

## Backend API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health` | Server status, workspace root |
| `GET /api/jdks` | Discovered JDKs + active execution mode (`local` / `judge0` / `none`) and Judge0 status |
| `GET /api/fs/list?path=` | List one directory (relative to root, empty = root) |
| `GET /api/fs/read?path=` | Read a file's text content |
| `POST /api/fs/save` | Save a notebook (`{path, content}`) |
| `POST /api/run` | Run a notebook; streams one newline-delimited JSON verdict per test case |
| `POST /api/lint` | Offline javac diagnostics for the editor (`{code, jdk, javaVersion}`) |

All paths are resolved relative to the workspace root; traversal outside the root is
rejected. The root defaults to the directory the app was started from and can be
overridden with the `DSA_ROOT` env var.

## `.algolab` file format

```json
{
  "name": "Merge Sort",
  "language": "java",
  "javaVersion": 21,
  "code": "public class Main { ... }",
  "testCases": [
    {
      "id": "case-1",
      "name": "Normal",
      "stdin": "5\n4 2 7 1 3\n",
      "expected": "1 2 3 4 7"
    }
  ]
}
```

`expected: null` means the case has no expected output (the ✓/✗ verdict is skipped).
A `.algolab` file that isn't valid JSON opens as raw code and is wrapped into the format on
save. No database — the file is the source of truth.

## Building

The normal build produces a plain Spring Boot jar:

```bash
mvn -q -DskipTests package
java -jar target/dsa-playground-0.1.0.jar
```

### Native macOS application

The `algolab` binary is a GraalVM native image of the same app — one self-contained
executable, no JVM install required. Rebuild it with GraalVM 21+ (its `native-image`
must be on `PATH` or `GRAALVM_HOME/bin`):

```bash
mvn -q -DskipTests package
native-image -jar target/dsa-playground-0.1.0.jar algolab
```

The binary is a build artifact (gitignored) — regenerate it after code changes. In a
native build there is no embedded `javac`, so live linting and local execution require a
separately installed JDK; Judge0 covers execution when none exists.

## Project layout

```
algolab                       native macOS binary (build artifact — see "Native macOS application")
pom.xml                       Spring Boot 3.5 / Java 21, Maven
src/main/java/.../execution/  JavaExecutionService + LocalJavaExecutor + Judge0Executor (the run boundary)
src/main/java/.../service/    JdkManager, FsService, CompileService, .env loading, banner
src/main/java/.../controller/ REST API (run, jdks, lint, fs, settings)
src/main/resources/static/    index.html + css/ + js/ (Tailwind, Monaco, vanilla JS)
.env.example                  committed template for local config (copy to .env; .env is gitignored)
samples/                      sample .algolab notebooks
```

## Roadmap (in priority order)

1. ✅ JdkManager — discover local JDKs (`JAVA_HOME`, `PATH`, `/usr/libexec/java_home -V`)
2. ✅ JavaExecutionService — local JDK compile/run per test case, streaming verdicts,
   Judge0 fallback for machines without a JDK
3. CLI polish (`algolab` on PATH, optional brew formula)

Everything in the project note's "Future ideas — DO NOT BUILD YET" list (visualization,
other languages, AI, hosting, sharing, ...) stays out until the core loop is useful.