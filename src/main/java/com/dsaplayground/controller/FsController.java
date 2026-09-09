package com.dsaplayground.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dsaplayground.service.FsService;
import com.dsaplayground.service.FsService.Entry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FsController {

    private static final Logger log = LoggerFactory.getLogger(FsController.class);
    private final FsService fs;

    public FsController(FsService fs) {
        this.fs = fs;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        long start = System.nanoTime();
        log.trace("FsController.health() ENTRY");
        String root = fs.root().toString();
        String rootName = fs.rootName();
        log.trace("FsController.health(): root={} rootName={}", root, rootName);
        Map<String, Object> result = Map.of(
                "status", "ok",
                "root", root,
                "rootName", rootName);
        log.trace("FsController.health() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, result);
        return result;
    }

    /** List a directory relative to the workspace root. Empty path = root. */
    @GetMapping("/fs/list")
    public List<Entry> list(@RequestParam(defaultValue = "") String path) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.list(path='{}') ENTRY", path == null ? "(empty)" : path);
        List<Entry> entries = fs.list(path);
        log.trace("FsController.list(path='{}') EXIT in {} ms -> {} entries",
                path, (System.nanoTime() - start) / 1_000_000, entries.size());
        return entries;
    }

    @GetMapping("/fs/read")
    public Map<String, Object> read(@RequestParam String path) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.read(path='{}') ENTRY", path);
        Path file = fs.resolve(path);
        log.trace("FsController.read(): resolved file={}", file);
        String content = fs.read(path);
        log.trace("FsController.read(): content length={} chars");
        Map<String, Object> result = Map.of(
                "path", path,
                "name", file.getFileName().toString(),
                "content", content);
        log.trace("FsController.read() EXIT in {} ms -> {} ({} chars)",
                (System.nanoTime() - start) / 1_000_000, result.get("name"), content.length());
        return result;
    }

    @PostMapping("/fs/save")
    public Map<String, Object> save(@RequestBody SaveRequest request) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.save(path='{}', contentLen={}) ENTRY",
                request.path() == null ? "null" : request.path(),
                request.content() == null ? "null" : request.content().length());
        fs.write(request.path(), request.content());
        Map<String, Object> result = Map.of("ok", true, "path", request.path());
        log.trace("FsController.save() EXIT in {} ms -> ok", (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    /** Create a new file or directory. */
    @PostMapping("/fs/create")
    public Map<String, Object> create(@RequestBody CreateRequest request) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.create(path='{}', directory={}) ENTRY", request.path(), request.directory());
        Path created = request.directory() ? fs.mkdir(request.path()) : fs.createFile(request.path());
        log.trace("FsController.create(): created path={}", created);
        String rel = fs.root().relativize(created).toString().replace('\\', '/');
        Map<String, Object> result = Map.of("ok", true, "path", rel);
        log.trace("FsController.create() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, rel);
        return result;
    }

    /** Rename a file or directory (new name must be a bare name, no slashes). */
    @PostMapping("/fs/rename")
    public Map<String, Object> rename(@RequestBody RenameRequest request) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.rename(path='{}', newName='{}') ENTRY", request.path(), request.newName());
        fs.rename(request.path(), request.newName());
        Map<String, Object> result = Map.of("ok", true, "path", request.path());
        log.trace("FsController.rename() EXIT in {} ms -> ok", (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    /** Delete a file or a directory tree. */
    @PostMapping("/fs/delete")
    public Map<String, Object> delete(@RequestBody DeleteRequest request) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.delete(path='{}') ENTRY", request.path());
        fs.delete(request.path());
        Map<String, Object> result = Map.of("ok", true, "path", request.path());
        log.trace("FsController.delete() EXIT in {} ms -> ok", (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    /** Duplicate a file or folder as “name copy[ n]”. */
    @PostMapping("/fs/duplicate")
    public Map<String, Object> duplicate(@RequestBody DuplicateRequest request) throws IOException {
        long start = System.nanoTime();
        log.trace("FsController.duplicate(path='{}') ENTRY", request.path());
        Path created = fs.duplicate(request.path());
        log.trace("FsController.duplicate(): created={}", created);
        String rel = fs.root().relativize(created).toString().replace('\\', '/');
        Map<String, Object> result = Map.of("ok", true, "path", rel);
        log.trace("FsController.duplicate() EXIT in {} ms -> {}", (System.nanoTime() - start) / 1_000_000, rel);
        return result;
    }

    public record SaveRequest(String path, String content) {}
    public record CreateRequest(String path, boolean directory) {}
    public record RenameRequest(String path, String newName) {}
    public record DeleteRequest(String path) {}
    public record DuplicateRequest(String path) {}
}