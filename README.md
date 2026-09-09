# AlgoLab

A local-first Java notebook for DSA practice. Write Java → make test cases → run →
see output → compare with expected → iterate. A "notebook for Java DSA", not an IDE and
not a competitive-programming platform.

> **Priority note:** this tool exists to make DSA practice better. If building it starts
> taking time away from solving DSA problems, stop building it.

## Quick start

```bash
./dsa-playground                    # workspace = current directory
./dsa-playground ~/DSA              # workspace = ~/DSA
./dsa-playground ~/DSA/merge-sort.dsa   # workspace = file's folder, file opens in the editor
./dsa-playground --no-browser       # start without opening a browser tab
./dsa-playground --rebuild          # force a Maven rebuild
```

Requirements: JDK 17+ (built against 21), Maven 3.9+. The first run compiles the jar;
the app needs internet for its CDN assets (Tailwind, Monaco Editor).

The script picks a free localhost port, starts Spring Boot, opens the browser, and runs
in the foreground — Ctrl+C stops the server.

## What's in this build (UI-only)

- **Spring Boot backend** serving the static frontend plus a small file-manager API.
  No compile/run/test logic yet — `POST /api/run` is a documented stub (HTTP 501).
- **Explorer** rooted at the workspace directory: expandable folders, lazy-loaded,
  collapsible & resizable. Notebooks use the `.algolab` extension; legacy `.dsa` files
  are still readable and trigger a one-time offer to rename them.
- **Editor** — Monaco Editor with Java syntax highlighting, plus a **Vim mode** toggle
  (Normal / Insert / Visual).
- **Test cases** — right pane with a tab per case: name, stdin, and an optional
  *Expected output* checkbox that reveals the expected stdout box.
- **Output pane** — bottom of the screen (from the right edge of the explorer to the
  right edge of the viewport), with a tab per test case. When expected output is set it
  shows a green ✓ / red ✗ verdict; stdout/stderr/duration/errors render for every case.
- **Save / load** — Ctrl/Cmd+S saves the open `.algolab` notebook (human-readable JSON);
  running also saves the file first.
- **Theme + settings** — a gear menu on the top right holds JDK/Java-level picks, Vim,
  lint & autocomplete toggles, and 22 editor themes (dark + light) with System-follow.

Run (Ctrl/Cmd+Enter or the ▶ Run button) currently reports that the runner isn't wired
up yet — JavaRunner + TestCaseRunner are the next milestone.

## Backend API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health` | Server status, workspace root |
| `GET /api/fs/list?path=` | List one directory (relative to root, empty = root) |
| `GET /api/fs/read?path=` | Read a file's text content |
| `POST /api/fs/save` | Save a `.dsa` file (`{path, content}`) |
| `POST /api/run` | Stub (501); contract documented in `RunController` |

All paths are resolved relative to the workspace root; traversal outside the root is
rejected. The root defaults to the directory the app was started from and can be
overridden with the `DSA_ROOT` env var (the launcher sets it).

## `.dsa` file format

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
A `.dsa` file that isn't valid JSON opens as raw code and is wrapped into the format on
save. No database — the file is the source of truth.

## Project layout

```
dsa-playground            launcher script
pom.xml                   Spring Boot 3.5 / Java 21, Maven
src/main/java/.../        application, FsService, FsController, RunController (stub)
src/main/resources/static/  index.html + css/ + js/ (Tailwind, Monaco, vanilla JS)
samples/                  sample .dsa notebooks
```

## Roadmap (in priority order)

1. JdkManager — discover local JDKs (`JAVA_HOME`, `PATH`, `/usr/libexec/java_home -V`)
2. JavaRunner / TestCaseRunner — temp workspace, compile, pipe stdin, capture
   stdout/stderr, timeout, compare, structured results
3. CLI polish (`dsa` on PATH, optional brew formula)

Everything in the project note's "Future ideas — DO NOT BUILD YET" list (visualization,
other languages, AI, hosting, sharing, ...) stays out until the core loop is useful.