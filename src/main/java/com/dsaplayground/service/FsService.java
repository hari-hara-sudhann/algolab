package com.dsaplayground.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * File manager for the workspace rooted at {@code dsa.root} (defaults to the
 * directory the app was started from).
 *
 * <p>All client-supplied paths are treated as relative to the root and are
 * resolved against it; anything that escapes the root is rejected.
 */
@Service
public class FsService {

    private static final Logger log = LoggerFactory.getLogger(FsService.class);

    public record Entry(String name, String path, boolean directory, long size, boolean isNotebook) {}

    /**
     * True for openable notebooks: {@code .algolab} (current) and {@code .dsa}
     * (legacy — still openable/savable, the UI nudges users to rename them).
     */
    public static boolean isNotebookFile(String name) {
        log.trace("FsService.isNotebookFile(name={}) ENTRY", name == null ? "null" : name);
        if (name == null) {
            log.trace("FsService.isNotebookFile(name=null) -> false");
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean result = lower.endsWith(".algolab") || lower.endsWith(".dsa");
        log.trace("FsService.isNotebookFile(name='{}') -> {} (lower='{}')", name, result, lower);
        return result;
    }

    private final Path root;

    public FsService(@Value("${dsa.root:${user.dir}}") String root) {
        log.trace("FsService.<init>(root='{}') ENTRY", root == null ? "null" : root);
        String resolvedRoot = root == null ? System.getProperty("user.dir", ".") : root;
        log.trace("FsService.<init>(): raw root='{}'", resolvedRoot);
        this.root = Path.of(resolvedRoot).toAbsolutePath().normalize();
        log.trace("FsService.<init>(): normalized root={}", this.root);
        log.trace("FsService.<init>(): root exists={} isDir={}", Files.exists(this.root), Files.isDirectory(this.root));
        log.trace("FsService.<init>() EXIT");
    }

    public Path root() {
        log.trace("FsService.root() -> {}", root);
        return root;
    }

    public String rootName() {
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        log.trace("FsService.rootName() -> '{}'", name);
        return name;
    }

    /** Resolve a client-supplied relative path against the workspace root. */
    public Path resolve(String relative) {
        log.trace("FsService.resolve(relative={}) ENTRY", relative == null ? "null" : relative);
        String rel = relative == null ? "" : relative.trim();
        log.trace("FsService.resolve(): trimmed relative='{}'", rel);
        if (rel.startsWith("/")) {
            rel = rel.substring(1); // tolerate absolute-looking input
            log.trace("FsService.resolve(): stripped leading slash -> '{}'", rel);
        }
        Path resolved = root.resolve(rel).normalize();
        log.trace("FsService.resolve(): root={} resolved={} normalized={}", root, root.resolve(rel), resolved);
        boolean startsWithRoot = resolved.startsWith(root);
        log.trace("FsService.resolve(): startsWith(root)={}", startsWithRoot);
        if (!startsWithRoot) {
            log.warn("FsService.resolve(): path '{}' escapes the workspace root '{}'", relative, root);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path escapes the workspace root: " + relative);
        }
        log.trace("FsService.resolve() -> {}", resolved);
        return resolved;
    }

    /**
     * List one directory (non-recursive). Directories first, then files,
     * alphabetically. Hidden entries (dot-prefixed) are skipped.
     */
    public List<Entry> list(String relative) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.list(relative={}) ENTRY", relative == null ? "(empty)" : relative);
        Path dir = resolve(relative);
        log.trace("FsService.list(): resolved dir={}", dir);
        boolean isDir = Files.isDirectory(dir);
        log.trace("FsService.list(): isDirectory={}", isDir);
        if (!isDir) {
            log.warn("FsService.list(): '{}' is not a directory", relative);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory: " + relative);
        }
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> all = paths.toList();
            log.trace("FsService.list(): total entries in dir: {}", all.size());
            List<Path> filtered = all.stream()
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        boolean hidden = fn.startsWith(".");
                        log.trace("FsService.list(): filtering '{}': hidden={} -> keep={}", fn, hidden, !hidden);
                        return !hidden;
                    })
                    .toList();
            log.trace("FsService.list(): after hidden filter: {} entries", filtered.size());
            List<Entry> entries = filtered.stream()
                    .map(this::toEntry)
                    .sorted(Comparator.comparing(Entry::directory).reversed()
                            .thenComparing(e -> e.name().toLowerCase()))
                    .toList();
            log.trace("FsService.list(): sorted {} entries (dirs first, alpha)", entries.size());
            for (Entry e : entries) {
                log.trace("FsService.list():   entry: name='{}' path='{}' dir={} size={} notebook={}",
                        e.name(), e.path(), e.directory(), e.size(), e.isNotebook());
            }
            log.trace("FsService.list() EXIT in {} ms -> {} entries", (System.nanoTime() - start) / 1_000_000, entries.size());
            return entries;
        }
    }

    public String read(String relative) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.read(relative='{}') ENTRY", relative == null ? "null" : relative);
        Path file = resolve(relative);
        log.trace("FsService.read(): resolved file={}", file);
        boolean isRegular = Files.isRegularFile(file);
        log.trace("FsService.read(): isRegularFile={}", isRegular);
        if (!isRegular) {
            log.warn("FsService.read(): '{}' is not a regular file", relative);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a file: " + relative);
        }
        long fileSize = Files.size(file);
        log.trace("FsService.read(): file size={} bytes", fileSize);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        log.trace("FsService.read(): read {} chars ({} bytes on disk)", content.length(), fileSize);
        log.trace("FsService.read() EXIT in {} ms", (System.nanoTime() - start) / 1_000_000);
        return content;
    }

    /** Write a notebook file (creating parent directories as needed). */
    public void write(String relative, String content) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.write(relative='{}', contentLen={}) ENTRY",
                relative == null ? "null" : relative, content == null ? "null" : content.length());
        if (relative == null || !isNotebookFile(relative)) {
            log.warn("FsService.write(): rejected non-notebook path '{}'", relative);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .algolab notebooks can be saved");
        }
        log.trace("FsService.write(): path validated as notebook");
        Path file = resolve(relative);
        log.trace("FsService.write(): resolved file={}", file);
        boolean exists = Files.exists(file);
        boolean isRegular = exists && Files.isRegularFile(file);
        log.trace("FsService.write(): exists={} isRegularFile={}", exists, isRegular);
        if (exists && !isRegular) {
            log.warn("FsService.write(): '{}' exists but is not a regular file", relative);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a regular file: " + relative);
        }
        Path parent = file.getParent();
        log.trace("FsService.write(): parent dir={}", parent);
        if (parent != null) {
            boolean parentExists = Files.isDirectory(parent);
            log.trace("FsService.write(): parent exists as dir={} -> createDirectories (if needed)", parentExists);
            Files.createDirectories(parent);
        }
        String finalContent = content == null ? "" : content;
        log.trace("FsService.write(): writing {} bytes to file", finalContent.length());
        Files.writeString(file, finalContent, StandardCharsets.UTF_8);
        long writtenSize = Files.size(file);
        log.trace("FsService.write(): write complete; on-disk size={} bytes", writtenSize);
        log.trace("FsService.write() EXIT in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    /** Code template every newly created notebook file starts from. */
    public static final String DSA_CODE_TEMPLATE =
            "public class Main {\n" +
            "\tpublic static void main(String[] args) {\n" +
            "\t\t\n" +
            "\t}\n" +
            "}\n";

    /** Create a new file. Notebook files (.algolab, legacy .dsa) are born as a valid notebook. */
    public Path createFile(String relative) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.createFile(relative='{}') ENTRY", relative == null ? "null" : relative);
        Path file = resolve(relative);
        log.trace("FsService.createFile(): resolved file={}", file);
        boolean exists = Files.exists(file);
        log.trace("FsService.createFile(): exists={}", exists);
        if (exists) {
            log.warn("FsService.createFile(): '{}' already exists", relative);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + relative);
        }
        requireParent(file, relative);
        log.trace("FsService.createFile(): creating file at {}", file);
        Files.createFile(file);
        log.trace("FsService.createFile(): file created");
        String name = file.getFileName().toString();
        log.trace("FsService.createFile(): file name='{}'", name);
        boolean isNotebook = isNotebookFile(name);
        log.trace("FsService.createFile(): isNotebookFile={}", isNotebook);
        if (isNotebook) {
            int dot = name.lastIndexOf('.');
            String baseName = dot > 0 ? name.substring(0, dot) : name;
            log.trace("FsService.createFile(): notebook baseName='{}'", baseName);
            String doc = defaultNotebookDoc(baseName);
            log.trace("FsService.createFile(): writing default notebook doc ({} chars)", doc.length());
            Files.writeString(file, doc, StandardCharsets.UTF_8);
            log.trace("FsService.createFile(): default notebook doc written");
        }
        log.trace("FsService.createFile() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, file);
        return file;
    }

    private static String defaultNotebookDoc(String name) {
        return "{\n"
                + "  \"name\": " + jsonString(name) + ",\n"
                + "  \"language\": \"java\",\n"
                + "  \"javaVersion\": null,\n"
                + "  \"code\": " + jsonString(DSA_CODE_TEMPLATE) + ",\n"
                + "  \"testCases\": []\n"
                + "}\n";
    }

    /** JSON-string literal escaping, shared with the notebook serializer in RunController. */
    public static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Duplicate a file or folder next to the original as “&lt;name&gt; copy”, adding
     * a numeric suffix when that already exists. Returns the created path.
     */
    public Path duplicate(String relative) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.duplicate(relative='{}') ENTRY", relative == null ? "null" : relative);
        Path from = resolve(relative);
        log.trace("FsService.duplicate(): resolved 'from'={}", from);
        boolean isRoot = from.equals(root);
        boolean exists = Files.exists(from);
        log.trace("FsService.duplicate(): from.equals(root)={} exists={}", isRoot, exists);
        if (isRoot || !exists) {
            log.warn("FsService.duplicate(): cannot duplicate '{}' (root={} exists={})", relative, isRoot, exists);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + relative);
        }
        Path target = nextCopyName(from);
        log.trace("FsService.duplicate(): target copy path={}", target);
        boolean isDir = Files.isDirectory(from);
        log.trace("FsService.duplicate(): from is directory={}", isDir);
        if (isDir) {
            log.trace("FsService.duplicate(): copying directory tree {} -> {}", from, target);
            copyTree(from, target);
        } else {
            Path targetParent = target.getParent();
            log.trace("FsService.duplicate(): ensuring parent dir exists: {}", targetParent);
            Files.createDirectories(targetParent);
            log.trace("FsService.duplicate(): copying file {} -> {} (COPY_ATTRIBUTES)", from, target);
            Files.copy(from, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        log.trace("FsService.duplicate() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, target);
        return target;
    }

    private static Path nextCopyName(Path from) {
        log.trace("FsService.nextCopyName(from={}) ENTRY", from);
        Path parent = from.getParent();
        log.trace("FsService.nextCopyName(): parent={}", parent);
        String fileName = from.getFileName().toString();
        log.trace("FsService.nextCopyName(): fileName='{}'", fileName);
        int dot = fileName.lastIndexOf('.');
        log.trace("FsService.nextCopyName(): dot={}", dot);
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        log.trace("FsService.nextCopyName(): stem='{}' ext='{}'", stem, ext);
        String candidate = stem + " copy" + ext;
        log.trace("FsService.nextCopyName(): initial candidate='{}'", candidate);
        int n = 2;
        while (Files.exists(parent.resolve(candidate))) {
            log.trace("FsService.nextCopyName(): candidate '{}' exists, trying n={}", candidate, n);
            candidate = stem + " copy " + n + ext;
            n++;
        }
        Path result = parent.resolve(candidate);
        log.trace("FsService.nextCopyName() -> {}", result);
        return result;
    }

    private void copyTree(Path from, Path to) throws IOException {
        log.trace("FsService.copyTree(from={}, to={}) ENTRY", from, to);
        long start = System.nanoTime();
        AtomicCounter counter = new AtomicCounter();
        Files.walkFileTree(from, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Path target = to.resolve(from.relativize(dir).toString());
                Files.createDirectories(target);
                log.trace("FsService.copyTree(): created dir: {} -> {}", dir, target);
                counter.incrementDirs();
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                log.trace("FsService.copyTree(): copied file: {} -> {} ({} bytes)",
                        file, target, attrs.size());
                counter.incrementFiles();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        log.trace("FsService.copyTree() EXIT in {} ms: {} dirs, {} files",
                (System.nanoTime() - start) / 1_000_000, counter.dirs(), counter.files());
    }

    private static final class AtomicCounter {
        int dirs() { return dirs; }
        int files() { return files; }
        void incrementDirs() { dirs++; }
        void incrementFiles() { files++; }
        private int dirs = 0;
        private int files = 0;
    }

    /** Create a new directory (the parent directory must exist). */
    public Path mkdir(String relative) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.mkdir(relative='{}') ENTRY", relative == null ? "null" : relative);
        Path dir = resolve(relative);
        log.trace("FsService.mkdir(): resolved dir={}", dir);
        boolean exists = Files.exists(dir);
        log.trace("FsService.mkdir(): exists={}", exists);
        if (exists) {
            log.warn("FsService.mkdir(): '{}' already exists", relative);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + relative);
        }
        requireParent(dir, relative);
        log.trace("FsService.mkdir(): creating directory {}", dir);
        Files.createDirectory(dir);
        log.trace("FsService.mkdir(): directory created");
        log.trace("FsService.mkdir() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, dir);
        return dir;
    }

    /** Rename a file or directory within its parent. */
    public void rename(String relative, String newName) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.rename(relative='{}', newName='{}') ENTRY",
                relative == null ? "null" : relative,
                newName == null ? "null" : newName);
        Path from = resolve(relative);
        log.trace("FsService.rename(): resolved 'from'={}", from);
        boolean isRoot = from.equals(root);
        log.trace("FsService.rename(): from.equals(root)={}", isRoot);
        if (isRoot) {
            log.warn("FsService.rename(): attempted to rename workspace root");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot rename the workspace root");
        }
        boolean exists = Files.exists(from);
        log.trace("FsService.rename(): from exists={}", exists);
        if (!exists) {
            log.warn("FsService.rename(): '{}' not found", relative);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + relative);
        }
        String validated = validateName(newName);
        log.trace("FsService.rename(): validated newName='{}'", validated);
        Path parent = from.getParent();
        log.trace("FsService.rename(): parent dir={}", parent);
        Path to = parent.resolve(validated);
        log.trace("FsService.rename(): target 'to'={}", to);
        boolean toExists = Files.exists(to);
        log.trace("FsService.rename(): target exists={}", toExists);
        if (toExists) {
            log.warn("FsService.rename(): target '{}' already exists", to.getFileName());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + to.getFileName());
        }
        log.trace("FsService.rename(): moving {} -> {}", from, to);
        Files.move(from, to);
        log.trace("FsService.rename(): move complete");
        log.trace("FsService.rename() EXIT in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    /** Delete a file, or a directory tree recursively. */
    public void delete(String relative) throws IOException {
        long start = System.nanoTime();
        log.trace("FsService.delete(relative='{}') ENTRY", relative == null ? "null" : relative);
        Path target = resolve(relative);
        log.trace("FsService.delete(): resolved target={}", target);
        boolean isRoot = target.equals(root);
        log.trace("FsService.delete(): target.equals(root)={}", isRoot);
        if (isRoot) {
            log.warn("FsService.delete(): attempted to delete workspace root");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete the workspace root");
        }
        boolean exists = Files.exists(target);
        log.trace("FsService.delete(): target exists={}", exists);
        if (!exists) {
            log.warn("FsService.delete(): '{}' not found", relative);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + relative);
        }
        boolean isDir = Files.isDirectory(target);
        log.trace("FsService.delete(): isDirectory={}", isDir);
        if (isDir) {
            try (Stream<Path> paths = Files.walk(target)) {
                List<Path> all = paths.toList();
                log.trace("FsService.delete(): walking directory tree: {} entries to delete", all.size());
                for (Path p : all) {
                    log.trace("FsService.delete():   deleting: {}", p);
                }
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                boolean deleted = Files.deleteIfExists(p);
                                log.trace("FsService.delete():   deleted {}: {}", p, deleted);
                            } catch (IOException e) {
                                log.warn("FsService.delete():   failed to delete {}: {}", p, e.getMessage());
                                throw new UncheckedIOException(e);
                            }
                        });
            }
            log.trace("FsService.delete(): directory tree deleted");
        } else {
            log.trace("FsService.delete(): deleting file {}", target);
            Files.delete(target);
            log.trace("FsService.delete(): file deleted");
        }
        log.trace("FsService.delete() EXIT in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    private void requireParent(Path child, String relative) {
        log.trace("FsService.requireParent(child={}, relative='{}') ENTRY", child, relative == null ? "null" : relative);
        Path parent = child.getParent();
        log.trace("FsService.requireParent(): parent={}", parent);
        boolean parentOk = parent != null && Files.isDirectory(parent);
        log.trace("FsService.requireParent(): parent OK={}", parentOk);
        if (!parentOk) {
            log.warn("FsService.requireParent(): parent directory does not exist for '{}'", relative);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent directory does not exist: " + relative);
        }
    }

    private String validateName(String name) {
        log.trace("FsService.validateName(name='{}') ENTRY", name == null ? "null" : name);
        if (name == null || name.isBlank()) {
            log.warn("FsService.validateName(): name is null/blank");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must not be empty");
        }
        String trimmed = name.trim();
        log.trace("FsService.validateName(): trimmed='{}'", trimmed);
        boolean isDot = trimmed.equals(".");
        boolean isDotDot = trimmed.equals("..");
        boolean hasSlash = trimmed.contains("/");
        boolean hasBackslash = trimmed.contains("\\");
        log.trace("FsService.validateName(): isDot={} isDotDot={} hasSlash={} hasBackslash={}",
                isDot, isDotDot, hasSlash, hasBackslash);
        if (isDot || isDotDot || hasSlash || hasBackslash) {
            log.warn("FsService.validateName(): invalid name '{}'", name);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name: " + name);
        }
        log.trace("FsService.validateName() -> '{}'", trimmed);
        return trimmed;
    }

    private Entry toEntry(Path p) {
        String name = p.getFileName().toString();
        String rel = root.relativize(p).toString().replace('\\', '/');
        boolean dir = Files.isDirectory(p);
        long size = dir ? 0 : (Files.isRegularFile(p) ? sizeOf(p) : 0);
        boolean notebook = !dir && isNotebookFile(name);
        log.trace("FsService.toEntry(p={}) -> Entry{name='{}', rel='{}', dir={}, size={}, notebook={}}",
                p, name, rel, dir, size, notebook);
        return new Entry(name, rel, dir, size, notebook);
    }

    private long sizeOf(Path p) {
        try {
            long sz = Files.size(p);
            log.trace("FsService.sizeOf(p={}) -> {} bytes", p, sz);
            return sz;
        } catch (IOException e) {
            log.trace("FsService.sizeOf(p={}): IOException -> 0 (file may be gone)", p);
            return 0;
        }
    }
}