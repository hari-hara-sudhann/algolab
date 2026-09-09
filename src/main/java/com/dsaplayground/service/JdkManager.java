package com.dsaplayground.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Discovers the JDKs installed on this machine and hands out binaries on
 * request. The app only needs {@code java}/{@code javac} from a home that
 * actually contains a JDK (a JRE alone cannot be used to compile).
 */
@Service
public class JdkManager {

    private static final Logger log = LoggerFactory.getLogger(JdkManager.class);

    /** LTS language levels we offer, plus the JDK's own feature version. */
    private static final int[] LTS_LEVELS = {8, 11, 17, 21};

    private static final Pattern QUOTED_VERSION = Pattern.compile("\"([0-9]+)(?:\\.([0-9]+))?");

    /** A usable JDK: absolute paths to its binaries plus the parsed feature version. */
    public record JdkInstallation(
            String home,
            Path javaBinary,
            Path javacBinary,
            int version,
            String name,
            String versionLine,
            List<Integer> levels) {
    }

    private final List<JdkInstallation> installations;
    private final JdkInstallation defaultInstallation;

    public JdkManager() {
        log.trace("JdkManager.<init>() ENTRY");
        long ctorStart = System.nanoTime();
        // Ordered probe list: first entry wins as the default, everything found is kept.
        Set<Path> probeHomes = new LinkedHashSet<>();
        log.trace("JdkManager.<init>(): starting JDK discovery");
        log.trace("JdkManager.<init>(): os.name={} java.version={}",
                System.getProperty("os.name", "unknown"),
                System.getProperty("java.version", "unknown"));
        log.trace("JdkManager.<init>(): java.home={} JAVA_HOME={}",
                System.getProperty("java.home", "(none)"),
                System.getenv("JAVA_HOME") == null ? "(none)" : System.getenv("JAVA_HOME"));

        String envJavaHome = Env.get("JAVA_HOME");
        log.trace("JdkManager.<init>(): JAVA_HOME env={}", envJavaHome == null ? "(none)" : envJavaHome);
        if (notBlank(envJavaHome)) {
            log.trace("JdkManager.<init>(): adding JAVA_HOME probe: {}", envJavaHome);
            probeHomes.add(Path.of(envJavaHome));
        }
        String propJavaHome = System.getProperty("java.home");
        log.trace("JdkManager.<init>(): java.home sys-prop={}", propJavaHome == null ? "(none)" : propJavaHome);
        if (notBlank(propJavaHome)) {
            log.trace("JdkManager.<init>(): adding java.home probe: {}", propJavaHome);
            probeHomes.add(Path.of(propJavaHome));
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        log.trace("JdkManager.<init>(): os.name.lower={} -> platform family detection", os);
        String osFamily = os.contains("mac") ? "mac" : os.contains("linux") ? "linux" : os.contains("win") ? "windows" : "unknown";
        log.trace("JdkManager.<init>(): detected osFamily={}", osFamily);
        if (os.contains("mac")) {
            log.trace("JdkManager.<init>(): macOS path: /usr/libexec/java_home -V");
            List<Path> macHomes = queryMacJavaHomes();
            log.trace("JdkManager.<init>(): /usr/libexec/java_home -V returned {} homes", macHomes.size());
            macHomes.forEach(h -> log.trace("JdkManager.<init>():   mac-home: {}", h));
            macHomes.forEach(probeHomes::add);
            Path macJvms = Path.of(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines");
            log.trace("JdkManager.<init>(): scanning user Mac JVMs dir: {}", macJvms);
            List<Path> userJvms = scanJavaVirtualMachines(macJvms);
            log.trace("JdkManager.<init>(): user JVMs found: {}", userJvms.size());
            userJvms.forEach(h -> log.trace("JdkManager.<init>():   user-jvm: {}", h));
            probeHomes.addAll(userJvms);
            Path globalMacJvms = Path.of("/Library/Java/JavaVirtualMachines");
            log.trace("JdkManager.<init>(): scanning global Mac JVMs dir: {}", globalMacJvms);
            List<Path> globalJvms = scanJavaVirtualMachines(globalMacJvms);
            log.trace("JdkManager.<init>(): global JVMs found: {}", globalJvms.size());
            globalJvms.forEach(h -> log.trace("JdkManager.<init>():   global-jvm: {}", h));
            probeHomes.addAll(globalJvms);
        } else if (os.contains("linux")) {
            Path linuxJvm = Path.of("/usr/lib/jvm");
            log.trace("JdkManager.<init>(): scanning Linux /usr/lib/jvm");
            List<Path> linuxDirs = scanJvmHomeDirs(linuxJvm);
            log.trace("JdkManager.<init>(): /usr/lib/jvm dirs found: {}", linuxDirs.size());
            linuxDirs.forEach(h -> log.trace("JdkManager.<init>():   linux-jvm: {}", h));
            probeHomes.addAll(linuxDirs);
            Path sdkman = Path.of(System.getProperty("user.home"), ".sdkman/candidates/java");
            log.trace("JdkManager.<init>(): scanning SDKMAN dir: {}", sdkman);
            List<Path> sdkmanDirs = scanJvmHomeDirs(sdkman);
            log.trace("JdkManager.<init>(): SDKMAN dirs found: {}", sdkmanDirs.size());
            sdkmanDirs.forEach(h -> log.trace("JdkManager.<init>():   sdkman: {}", h));
            probeHomes.addAll(sdkmanDirs);
        } else if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles");
            log.trace("JdkManager.<init>(): Windows ProgramFiles={} env", pf == null ? "(none)" : pf);
            if (pf != null) {
                Path winJava = Path.of(pf, "Java");
                log.trace("JdkManager.<init>(): scanning Windows Java dir: {}", winJava);
                List<Path> winJavaDirs = scanJvmHomeDirs(winJava);
                log.trace("JdkManager.<init>(): Windows Java dirs found: {}", winJavaDirs.size());
                winJavaDirs.forEach(h -> log.trace("JdkManager.<init>():   win-java: {}", h));
                probeHomes.addAll(winJavaDirs);
                Path eclipseAdoptium = Path.of(pf, "Eclipse Adoptium");
                log.trace("JdkManager.<init>(): scanning Windows Eclipse Adoptium dir: {}", eclipseAdoptium);
                List<Path> adoptiumDirs = scanJvmHomeDirs(eclipseAdoptium);
                log.trace("JdkManager.<init>(): Eclipse Adoptium dirs found: {}", adoptiumDirs.size());
                adoptiumDirs.forEach(h -> log.trace("JdkManager.<init>():   adoptium: {}", h));
                probeHomes.addAll(adoptiumDirs);
            }
        }

        // PATH fallback: derive the home from the located javac binary.
        log.trace("JdkManager.<init>(): probing PATH for javac home");
        Optional<Path> pathHome = pathJavacHome();
        log.trace("JdkManager.<init>(): PATH javac home resolved: {}", pathHome.isPresent() ? pathHome.get() : "(none)");
        pathHome.ifPresent(h -> {
            log.trace("JdkManager.<init>(): adding PATH-derived home: {}", h);
            probeHomes.add(h);
        });

        log.trace("JdkManager.<init>(): total probe homes (dedup pending): {}", probeHomes.size());
        log.trace("JdkManager.<init>(): probe homes list:");
        int probeIdx = 0;
        for (Path p : probeHomes) {
            log.trace("JdkManager.<init>():   probe[{}]: {}", probeIdx++, p);
        }

        Map<String, JdkInstallation> found = new LinkedHashMap<>();
        int buildAttempt = 0;
        int buildSuccess = 0;
        int buildSkippedDup = 0;
        int buildSkippedNull = 0;
        for (Path probe : probeHomes) {
            buildAttempt++;
            if (probe == null) {
                log.trace("JdkManager.<init>(): build[{}] SKIPPED null probe", buildAttempt);
                buildSkippedNull++;
                continue;
            }
            log.trace("JdkManager.<init>(): build[{}] resolving home from probe: {}", buildAttempt, probe);
            Path home = resolveHome(probe);
            if (home == null) {
                log.trace("JdkManager.<init>(): build[{}] resolveHome returned null for probe: {}", buildAttempt, probe);
                continue;
            }
            log.trace("JdkManager.<init>(): build[{}] resolved home: {}", buildAttempt, home);
            if (found.containsKey(home.toString())) {
                log.trace("JdkManager.<init>(): build[{}] SKIPPED duplicate home: {}", buildAttempt, home);
                buildSkippedDup++;
                continue;
            }
            log.trace("JdkManager.<init>(): build[{}] building installation for home: {}", buildAttempt, home);
            Optional<JdkInstallation> inst = buildInstallation(home);
            if (inst.isPresent()) {
                JdkInstallation i = inst.get();
                log.trace("JdkManager.<init>(): build[{}] SUCCESS: name={} version={} home={}",
                        buildAttempt, i.name(), i.version(), i.home());
                found.put(home.toString(), i);
                buildSuccess++;
            } else {
                log.trace("JdkManager.<init>(): build[{}] FAILED to build installation for home: {}",
                        buildAttempt, home);
            }
        }

        log.trace("JdkManager.<init>(): discovery summary: totalProbes={} attempts={} success={} dupSkipped={} nullSkipped={}",
                probeHomes.size(), buildAttempt, buildSuccess, buildSkippedDup, buildSkippedNull);

        List<JdkInstallation> sorted = new ArrayList<>(found.values());
        log.trace("JdkManager.<init>(): pre-sort installations (count={}):", sorted.size());
        for (JdkInstallation inst : sorted) {
            log.trace("JdkManager.<init>():   unsorted: name={} version={} home={}", inst.name(), inst.version(), inst.home());
        }
        sorted.sort(Comparator.comparingInt(JdkInstallation::version).reversed()
                .thenComparing(JdkInstallation::name, String.CASE_INSENSITIVE_ORDER));

        this.installations = List.copyOf(sorted);
        log.trace("JdkManager.<init>(): post-sort installations (count={}):", installations.size());
        for (JdkInstallation inst : installations) {
            log.trace("JdkManager.<init>():   sorted: name={} version={} levels={} home={}",
                    inst.name(), inst.version(), inst.levels(), inst.home());
        }
        // Default = the first usable JDK in probe order (JAVA_HOME, this app's own
        // runtime, macOS default, PATH…) — i.e. what plain `java` would use.
        // Null when no usable JDK exists on this machine; the execution layer
        // treats that as "fall back to Judge0".
        this.defaultInstallation = found.values().stream().findFirst().orElse(null);
        if (defaultInstallation == null) {
            log.warn("JdkManager: no usable JDK found on this machine — local execution unavailable");
        } else {
            log.trace("JdkManager.<init>(): default installation: name={} version={} home={}",
                    defaultInstallation.name(), defaultInstallation.version(), defaultInstallation.home());
        }
        log.info("Discovered {} JDK(s); default: {}", installations.size(),
                defaultInstallation == null ? "none"
                        : defaultInstallation.name() + " (Java " + defaultInstallation.version() + ")");
        log.trace("JdkManager.<init>() EXIT in {} ms", (System.nanoTime() - ctorStart) / 1_000_000);
    }

    /** Every usable JDK found on this machine, highest version first. */
    public List<JdkInstallation> available() {
        log.trace("JdkManager.available() -> {} installations", installations.size());
        return installations;
    }

    /**
     * The JDK this app itself prefers (JAVA_HOME / own runtime / mac default),
     * or empty when no usable JDK exists on this machine.
     */
    public Optional<JdkInstallation> defaultInstallation() {
        log.trace("JdkManager.defaultInstallation() -> {}",
                defaultInstallation == null ? "none" : defaultInstallation);
        return Optional.ofNullable(defaultInstallation);
    }

    /**
     * Resolve a client-supplied home path to an installation. A blank or
     * unknown home falls back to the default JDK; empty is returned only when
     * no usable JDK exists at all (the execution layer then falls back to
     * Judge0).
     */
    public Optional<JdkInstallation> requireInstallation(String home) {
        log.trace("JdkManager.requireInstallation(home={}) ENTRY", home == null ? "null" : home);
        if (home != null && !home.isBlank()) {
            log.trace("JdkManager.requireInstallation(): looking up explicit home: {}", home);
            String normalized = Path.of(home).toAbsolutePath().normalize().toString();
            log.trace("JdkManager.requireInstallation(): normalized lookup path: {}", normalized);
            for (JdkInstallation inst : installations) {
                log.trace("JdkManager.requireInstallation():   comparing against: home={} (match={})",
                        inst.home(), inst.home().equals(normalized));
                if (inst.home().equals(normalized)) {
                    log.trace("JdkManager.requireInstallation() -> matched: {} (Java {})", inst.name(), inst.version());
                    return Optional.of(inst);
                }
            }
            log.warn("Unknown JDK home '{}', falling back to default", home);
            log.trace("JdkManager.requireInstallation(): no match; falling back");
        } else {
            log.trace("JdkManager.requireInstallation(): home is blank/null; using default");
        }
        log.trace("JdkManager.requireInstallation() -> default: {}",
                defaultInstallation == null ? "none" : defaultInstallation);
        return Optional.ofNullable(defaultInstallation);
    }

    /** Language levels this JDK can compile for, newest first (e.g. 21, 17, 11, 8). */
    public static List<Integer> levelsFor(int featureVersion) {
        log.trace("JdkManager.levelsFor(featureVersion={}) ENTRY", featureVersion);
        Set<Integer> set = new LinkedHashSet<>();
        set.add(featureVersion);
        log.trace("JdkManager.levelsFor(): initial set with own version: {}", set);
        for (int lts : LTS_LEVELS) {
            log.trace("JdkManager.levelsFor(): checking LTS level {} <= {} -> {}", lts, featureVersion, lts <= featureVersion);
            if (lts <= featureVersion) {
                set.add(lts);
            }
        }
        log.trace("JdkManager.levelsFor(): set after LTS inclusion: {}", set);
        List<Integer> levels = new ArrayList<>(set);
        levels.sort(Comparator.reverseOrder());
        log.trace("JdkManager.levelsFor() -> sorted levels: {}", levels);
        return List.copyOf(levels);
    }

    /* ---------------- discovery helpers ---------------- */

    /** Accepts either a JDK home directly or a macOS JVM bundle (…/Contents/Home). */
    private static Path resolveHome(Path candidate) {
        log.trace("JdkManager.resolveHome(candidate={}) ENTRY", candidate == null ? "null" : candidate);
        if (candidate == null) {
            log.trace("JdkManager.resolveHome() -> null (null input)");
            return null;
        }
        Path abs = candidate.toAbsolutePath().normalize();
        log.trace("JdkManager.resolveHome(): absolute normalized: {}", abs);
        log.trace("JdkManager.resolveHome(): hasJavac(abs)={}", hasJavac(abs));
        if (hasJavac(abs)) {
            log.trace("JdkManager.resolveHome() -> abs (javac found)");
            return abs;
        }
        // macOS layout: <jvm-bundle>/Contents/Home
        Path contentsHome = abs.resolve("Contents/Home");
        log.trace("JdkManager.resolveHome(): trying macOS Contents/Home: {}", contentsHome);
        log.trace("JdkManager.resolveHome(): hasJavac(contentsHome)={}", hasJavac(contentsHome));
        if (hasJavac(contentsHome)) {
            log.trace("JdkManager.resolveHome() -> contentsHome (javac found)");
            return contentsHome;
        }
        log.trace("JdkManager.resolveHome() -> null (no javac found)");
        return null;
    }

    private static boolean hasJavac(Path home) {
        String javacName = exe("javac");
        Path bin = home.resolve("bin");
        Path javac = bin.resolve(javacName);
        log.trace("JdkManager.hasJavac(home={}) -> bin={} javac={} exists={} executable={}",
                home, bin, javac, Files.exists(javac), Files.isExecutable(javac));
        return Files.isExecutable(javac);
    }

    private static Optional<JdkInstallation> buildInstallation(Path home) {
        log.trace("JdkManager.buildInstallation(home={}) ENTRY", home);
        Path java = home.resolve("bin").resolve(exe("java"));
        Path javac = home.resolve("bin").resolve(exe("javac"));
        log.trace("JdkManager.buildInstallation(): java={} isExecutable={} javac={} isExecutable={}",
                java, Files.isExecutable(java), javac, Files.isExecutable(javac));
        if (!Files.isExecutable(java) || !Files.isExecutable(javac)) {
            log.trace("JdkManager.buildInstallation() -> empty (binary missing)");
            return Optional.empty();
        }
        VersionInfo info = probeVersion(java);
        log.trace("JdkManager.buildInstallation(): version probe result: feature={} line='{}'", info.feature, info.line);
        // macOS homes live at …/<jvm-bundle>/Contents/Home — label by the bundle
        Path labelDir = home;
        int walkSteps = 0;
        while (labelDir != null && labelDir.getParent() != null) {
            String dirName = labelDir.getFileName().toString();
            log.trace("JdkManager.buildInstallation(): walk step {}: dirName='{}' (root={})",
                    walkSteps++, dirName, labelDir.equals(home));
            if (!"Home".equals(dirName) && !"Contents".equals(dirName)) {
                log.trace("JdkManager.buildInstallation(): stop walking; non-Home/Contents dir: '{}'", dirName);
                break;
            }
            log.trace("JdkManager.buildInstallation(): continuing up to parent={}", labelDir.getParent());
            labelDir = labelDir.getParent();
        }
        String name = labelDir == null || labelDir.getFileName() == null
                ? home.toString()
                : labelDir.getFileName().toString();
        log.trace("JdkManager.buildInstallation(): label dir resolved to name='{}'", name);
        List<Integer> levels = levelsFor(info.feature);
        log.trace("JdkManager.buildInstallation(): levels for version {}: {}", info.feature, levels);
        JdkInstallation inst = new JdkInstallation(
                home.toString(),
                java,
                javac,
                info.feature,
                name,
                info.line,
                levels);
        log.trace("JdkManager.buildInstallation() -> OK: name='{}' version={} levels={} home={} javaBin={} javacBin={}",
                inst.name(), inst.version(), inst.levels(), inst.home(), java, javac);
        return Optional.of(inst);
    }

    private record VersionInfo(int feature, String line) {
    }

    private static VersionInfo probeVersion(Path javaBinary) {
        log.trace("JdkManager.probeVersion(javaBinary={}) ENTRY", javaBinary);
        try {
            log.trace("JdkManager.probeVersion(): spawning: {} -version", javaBinary);
            Process p = new ProcessBuilder(javaBinary.toString(), "-version").redirectErrorStream(true).start();
            log.trace("JdkManager.probeVersion(): process started pid={}", p.pid());
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            log.trace("JdkManager.probeVersion(): waitFor finished={} exit={} (timeout={})",
                    finished, finished ? p.exitValue() : -1, !finished);
            if (!finished) {
                log.trace("JdkManager.probeVersion(): killing timed-out process");
                p.destroyForcibly();
                return new VersionInfo(Runtime.version().feature(), "");
            }
            String out = new String(p.getInputStream().readAllBytes());
            log.trace("JdkManager.probeVersion(): stdout ({} chars): '{}'", out.length(), out.replace("\n", " | "));
            String firstLine = out.lines().findFirst().orElse("");
            log.trace("JdkManager.probeVersion(): firstLine='{}'", firstLine);
            Matcher m = QUOTED_VERSION.matcher(firstLine);
            log.trace("JdkManager.probeVersion(): version regex matches={}", m.matches());
            if (m.find()) {
                int feature = ("1".equals(m.group(1)) && m.group(2) != null)
                        ? Integer.parseInt(m.group(2))
                        : Integer.parseInt(m.group(1));
                log.trace("JdkManager.probeVersion(): parsed feature={} (group1='{}' group2='{}')",
                        feature, m.group(1), m.group(2));
                return new VersionInfo(feature, firstLine.trim());
            }
            log.trace("JdkManager.probeVersion(): regex did not match; falling back to runtime version");
        } catch (Exception e) {
            log.trace("JdkManager.probeVersion(): exception during probe: {}", e.getMessage());
        }
        int fallback = Runtime.version().feature();
        log.trace("JdkManager.probeVersion() -> fallback: feature={} (runtime)", fallback);
        return new VersionInfo(fallback, "");
    }

    /** mac: returns every JVM reported by /usr/libexec/java_home -V plus the default one. */
    private static List<Path> queryMacJavaHomes() {
        log.trace("JdkManager.queryMacJavaHomes() ENTRY");
        List<Path> homes = new ArrayList<>();
        try {
            log.trace("JdkManager.queryMacJavaHomes(): running /usr/libexec/java_home -V");
            Process p = new ProcessBuilder("/usr/libexec/java_home", "-V")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            log.trace("JdkManager.queryMacJavaHomes(): /usr/libexec/java_home -V finished={} exit={}", finished, finished ? p.exitValue() : -1);
            if (finished) {
                String out = new String(p.getInputStream().readAllBytes());
                log.trace("JdkManager.queryMacJavaHomes(): -V stdout ({} chars): {}", out.length(), out.replace("\n", " | "));
                for (String line : out.lines().toList()) {
                    int i = line.indexOf("/");
                    if (i > 0) {
                        Path home = Path.of(line.substring(i).trim());
                        if (home.getNameCount() > 0) {
                            log.trace("JdkManager.queryMacJavaHomes():   parsed home from line: {}", home);
                            homes.add(home);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("JdkManager.queryMacJavaHomes(): /usr/libexec/java_home -V failed: {}", e.getMessage());
        }
        try {
            log.trace("JdkManager.queryMacJavaHomes(): running /usr/libexec/java_home (default)");
            Process p = new ProcessBuilder("/usr/libexec/java_home").start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            log.trace("JdkManager.queryMacJavaHomes(): default java_home finished={} exit={}", finished, finished ? p.exitValue() : -1);
            if (finished && p.exitValue() == 0) {
                String out = new String(p.getInputStream().readAllBytes()).trim();
                log.trace("JdkManager.queryMacJavaHomes(): default java_home stdout='{}'", out);
                if (!out.isBlank()) {
                    Path home = Path.of(out);
                    log.trace("JdkManager.queryMacJavaHomes():   default home: {}", home);
                    homes.add(home);
                }
            }
        } catch (Exception e) {
            log.trace("JdkManager.queryMacJavaHomes(): /usr/libexec/java_home (default) failed: {}", e.getMessage());
        }
        log.trace("JdkManager.queryMacJavaHomes() -> {} homes total", homes.size());
        return homes;
    }

    /** macOS JavaVirtualMachines dir: children are JVM bundles with Contents/Home. */
    private static List<Path> scanJavaVirtualMachines(Path parent) {
        log.trace("JdkManager.scanJavaVirtualMachines(parent={}) ENTRY", parent);
        List<Path> dirs = listDirectories(parent);
        log.trace("JdkManager.scanJavaVirtualMachines(parent={}): listDirectories returned {} dirs: {}",
                parent, dirs.size(), dirs);
        if (dirs.isEmpty() || !System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            log.trace("JdkManager.scanJavaVirtualMachines(parent={}) -> {} dirs (not mac or empty)", parent, dirs.size());
            return dirs;
        }
        List<Path> homes = new ArrayList<>();
        for (Path d : dirs) {
            Path home = d.resolve("Contents/Home");
            log.trace("JdkManager.scanJavaVirtualMachines(): dir={} Contents/Home={} isDir={}", d, home, Files.isDirectory(home));
            Path selected = Files.isDirectory(home) ? home : d;
            log.trace("JdkManager.scanJavaVirtualMachines(): selected home for {}: {}", d, selected);
            homes.add(selected);
        }
        log.trace("JdkManager.scanJavaVirtualMachines(parent={}) -> {} homes", parent, homes.size());
        return homes;
    }

    /** Linux/Windows: children are JDK homes (each has bin/javac). */
    private static List<Path> scanJvmHomeDirs(Path parent) {
        log.trace("JdkManager.scanJvmHomeDirs(parent={}) ENTRY", parent);
        List<Path> dirs = listDirectories(parent);
        log.trace("JdkManager.scanJvmHomeDirs(parent={}) -> {} dirs", parent, dirs.size());
        return dirs;
    }

    private static List<Path> listDirectories(Path parent) {
        log.trace("JdkManager.listDirectories(parent={}) ENTRY", parent);
        if (parent == null || !Files.isDirectory(parent)) {
            log.trace("JdkManager.listDirectories(parent={}) -> empty (null or not a directory)", parent);
            return List.of();
        }
        log.trace("JdkManager.listDirectories(parent={}): listing...", parent);
        try (var stream = Files.list(parent)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            log.trace("JdkManager.listDirectories(parent={}): found {} dirs: {}", parent, dirs.size(), dirs);
            return dirs;
        } catch (IOException e) {
            log.trace("JdkManager.listDirectories(parent={}): IOException: {}", parent, e.getMessage());
            return List.of();
        }
    }

    /** When javac is on PATH, derive its JDK home (parent of bin/). */
    private static Optional<Path> pathJavacHome() {
        log.trace("JdkManager.pathJavacHome() ENTRY");
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            log.trace("JdkManager.pathJavacHome() -> empty (PATH env not set)");
            return Optional.empty();
        }
        log.trace("JdkManager.pathJavacHome(): PATH entries (count={}): {}", pathEnv.split(File.pathSeparator).length, pathEnv);
        int examined = 0;
        for (String p : pathEnv.split(File.pathSeparator)) {
            examined++;
            Path bin = Path.of(p);
            log.trace("JdkManager.pathJavacHome(): examining PATH entry [{}]: {}", examined, bin);
            if (!Files.isDirectory(bin)) {
                log.trace("JdkManager.pathJavacHome():   not a directory, skip");
                continue;
            }
            Path javac = bin.resolve(exe("javac"));
            log.trace("JdkManager.pathJavacHome():   javac candidate: {} exists={} executable={}",
                    javac, Files.exists(javac), Files.isExecutable(javac));
            if (Files.isExecutable(javac)) {
                Path home = bin.getParent();
                log.trace("JdkManager.pathJavacHome():   FOUND javac; home={} (parent of bin)", home);
                if (home != null) {
                    log.trace("JdkManager.pathJavacHome() -> {}", home);
                    return Optional.of(home);
                }
            }
        }
        log.trace("JdkManager.pathJavacHome() -> empty (no javac on PATH)");
        return Optional.empty();
    }

    private static String exe(String name) {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWin = os.contains("win");
        String exeName = isWin ? name + ".exe" : name;
        log.trace("JdkManager.exe(name={} os.contains(win)={}) -> {}", name, isWin, exeName);
        return exeName;
    }

    private static boolean notBlank(String s) {
        boolean result = s != null && !s.isBlank();
        log.trace("JdkManager.notBlank(s={}) -> {}", s == null ? "null" : "'" + s + "'", result);
        return result;
    }
}
