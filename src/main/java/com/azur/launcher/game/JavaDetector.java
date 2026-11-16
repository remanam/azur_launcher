package com.azur.launcher.game;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds installed Java runtimes (JAVA_HOME, this JVM, PATH, standard per-OS install directories) and picks
 * the one a Minecraft version needs. Probing runs {@code java -version} only when the {@code release} file
 * does not give the version, so a scan stays cheap.
 */
public final class JavaDetector {

    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"");
    private static final Pattern RELEASE_ARCH_PATTERN = Pattern.compile("OS_ARCH=\"([^\"]+)\"");
    private static final int SCAN_DEPTH = 2;
    private static final int MAX_CHILDREN = 64;

    private JavaDetector() {
    }

    /** One installed runtime. */
    public static class JavaInfo {
        private final Path home;
        private final Path javaExecutable;
        private final String version;
        private final int majorVersion;
        private final String arch;

        public JavaInfo(Path home, Path javaExecutable, String version, int majorVersion, String arch) {
            this.home = home;
            this.javaExecutable = javaExecutable;
            this.version = version;
            this.majorVersion = majorVersion;
            this.arch = Objects.isNull(arch) ? "" : arch;
        }

        public Path getHome() {
            return home;
        }

        public Path getJavaExecutable() {
            return javaExecutable;
        }

        public String getVersion() {
            return version;
        }

        public int getMajorVersion() {
            return majorVersion;
        }

        public String getArch() {
            return arch;
        }

        @Override
        public String toString() {
            return "Java " + version + (arch.isEmpty() ? "" : " (" + arch + ")") + " - " + home;
        }
    }

    /** All runtimes found on this machine, newest first. */
    public static List<JavaInfo> detectAll() {
        Map<String, JavaInfo> unique = new LinkedHashMap<>();
        String thisHome = System.getProperty("java.home");
        if (Objects.nonNull(thisHome) && !thisHome.isBlank()) {
            addHome(unique, Path.of(thisHome));
        }
        addEnvHome(unique, "JAVA_HOME");
        addEnvHome(unique, "JDK_HOME");
        if (isMac()) {
            addHome(unique, macJavaHome(21));
        }
        for (Path exe : findOnPath()) {
            addExecutable(unique, exe);
        }
        for (Path root : installRoots()) {
            scanHomeDir(unique, root, SCAN_DEPTH);
        }
        List<JavaInfo> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingInt(JavaInfo::getMajorVersion).reversed()
                .thenComparing((a, b) -> compareVersion(b.getVersion(), a.getVersion()))
                .thenComparing(a -> String.valueOf(a.getHome()).toLowerCase(Locale.ROOT)));
        return result;
    }

    /**
     * Home reported by {@code /usr/libexec/java_home -v N}, or null. Only a path that exists is accepted, so the
     * "Unable to locate a Java Runtime" message of the macOS stub is never mistaken for a home.
     */
    static Path macJavaHome(int major) {
        Path tool = Path.of("/usr/libexec/java_home");
        if (!Files.isExecutable(tool)) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(tool.toString(), "-v", String.valueOf(major)).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return parseJavaHomeOutput(out);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (Objects.nonNull(process) && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** First output line when it is an existing directory, otherwise null. */
    static Path parseJavaHomeOutput(String out) {
        if (Objects.isNull(out) || out.isBlank()) {
            return null;
        }
        String line = out.lines().findFirst().orElse("").trim();
        if (!line.startsWith("/")) {
            return null;
        }
        Path path = Path.of(line);
        return Files.isDirectory(path) ? path : null;
    }

    private static void addEnvHome(Map<String, JavaInfo> unique, String name) {
        String value = System.getenv(name);
        if (Objects.nonNull(value) && !value.isBlank()) {
            addHome(unique, Path.of(value.trim().replace("\"", "")));
        }
    }

    /** Standard places where each OS keeps JDKs. Entries that do not exist are skipped by the scan. */
    static List<Path> installRoots() {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (isWindows()) {
            for (String pf : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
                if (Objects.isNull(pf) || pf.isBlank()) {
                    continue;
                }
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft",
                        "Amazon Corretto", "Semeru", "GraalVM"}) {
                    roots.add(Path.of(pf, vendor));
                }
            }
            String local = System.getenv("LOCALAPPDATA");
            if (Objects.nonNull(local) && !local.isBlank()) {
                roots.add(Path.of(local, "Programs", "Eclipse Adoptium"));
                roots.add(Path.of(local, "Programs", "Microsoft"));
            }
            String appData = System.getenv("APPDATA");
            if (Objects.nonNull(appData) && !appData.isBlank()) {
                roots.add(Path.of(appData, ".minecraft", "runtime"));
            }
        } else if (isMac()) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            roots.add(Path.of(home, "Library", "Java", "JavaVirtualMachines"));
            roots.addAll(homebrewJdks("/opt/homebrew/opt"));
            roots.addAll(homebrewJdks("/usr/local/opt"));
            roots.add(Path.of(home, "Library", "Application Support", "minecraft", "runtime"));
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/usr/java"));
            roots.add(Path.of(home, ".sdkman", "candidates", "java"));
            roots.add(Path.of(home, ".minecraft", "runtime"));
        }
        roots.add(Path.of(home, ".jdks"));
        return roots;
    }

    /** Homebrew keeps {@code openjdk@N} under {@code opt/<name>/libexec/openjdk.jdk}. */
    private static List<Path> homebrewJdks(String optDir) {
        List<Path> result = new ArrayList<>();
        Path opt = Path.of(optDir);
        if (!Files.isDirectory(opt)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(opt)) {
            stream.filter(p -> p.getFileName().toString().startsWith("openjdk"))
                    .forEach(p -> result.add(p.resolve("libexec").resolve("openjdk.jdk")));
        } catch (IOException ignored) {
            // no Homebrew JDKs
        }
        return result;
    }

    private static void scanHomeDir(Map<String, JavaInfo> unique, Path root, int depth) {
        if (Objects.isNull(root) || !Files.isDirectory(root)) {
            return;
        }
        Path home = macHome(root);
        if (Files.isRegularFile(javaExecutableOf(home))) {
            addHome(unique, home);
            return;
        }
        if (depth <= 0) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path child : stream.filter(Files::isDirectory).limit(MAX_CHILDREN).toList()) {
                scanHomeDir(unique, child, depth - 1);
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable directory simply yields no candidates
        }
    }

    /** macOS bundles keep the runtime in {@code <name>.jdk/Contents/Home}. */
    private static Path macHome(Path dir) {
        Path bundled = dir.resolve("Contents").resolve("Home");
        return Files.isDirectory(bundled) ? bundled : dir;
    }

    private static void addHome(Map<String, JavaInfo> unique, Path home) {
        if (Objects.isNull(home)) {
            return;
        }
        putUnique(unique, probe(home));
    }

    private static void addExecutable(Map<String, JavaInfo> unique, Path exe) {
        putUnique(unique, probeExecutable(exe));
    }

    private static void putUnique(Map<String, JavaInfo> unique, JavaInfo info) {
        if (Objects.isNull(info)) {
            return;
        }
        Path exe = info.getJavaExecutable().toAbsolutePath().normalize();
        try {
            exe = exe.toRealPath();
        } catch (IOException | RuntimeException ignored) {
            // keep the normalised path
        }
        unique.putIfAbsent(exe.toString().toLowerCase(Locale.ROOT), info);
    }

    // ------------------------------------------------------------------ selection

    /** Newest runtime with exactly {@code requiredMajor} that fits this CPU, or null. */
    public static JavaInfo pickBest(List<JavaInfo> candidates, int requiredMajor) {
        JavaInfo best = null;
        for (JavaInfo info : candidates) {
            if (info.getMajorVersion() == requiredMajor && isArchCompatible(info)
                    && (Objects.isNull(best) || compareVersion(info.getVersion(), best.getVersion()) > 0)) {
                best = info;
            }
        }
        return best;
    }

    /** Exact match, otherwise the closest higher major (a lower one cannot run the game). */
    public static JavaInfo pickFallback(List<JavaInfo> candidates, int requiredMajor) {
        JavaInfo exact = pickBest(candidates, requiredMajor);
        if (Objects.nonNull(exact)) {
            return exact;
        }
        JavaInfo higher = null;
        for (JavaInfo info : candidates) {
            int major = info.getMajorVersion();
            if (!isArchCompatible(info) || major <= 0) {
                continue;
            }
            if (major < requiredMajor) {
                continue;
            } else if (Objects.isNull(higher) || major < higher.getMajorVersion()) {
                higher = info;
            }
        }
        return higher;
    }

    /** A 32-bit runtime cannot run the game on a 64-bit host. */
    public static boolean isArchCompatible(JavaInfo info) {
        if (Objects.isNull(info)) {
            return false;
        }
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(osArch.contains("64"))) {
            return true;
        }
        String javaArch = info.getArch().toLowerCase(Locale.ROOT);
        return !(javaArch.equals("x86") || javaArch.equals("i386") || javaArch.equals("i686"));
    }

    /** Numeric, segment-wise version comparison ({@code 21.0.4 > 21.0.3}). */
    public static int compareVersion(String left, String right) {
        String[] a = splitVersion(left);
        String[] b = splitVersion(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? toInt(a[i]) : 0;
            int y = i < b.length ? toInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static String[] splitVersion(String version) {
        return Objects.isNull(version) || version.isBlank() ? new String[0] : version.trim().split("[^0-9]+");
    }

    private static int toInt(String value) {
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ probing

    /** Probes a runtime by its home directory; null when it is not a usable Java. */
    public static JavaInfo probe(Path home) {
        if (Objects.isNull(home)) {
            return null;
        }
        Path exe = javaExecutableOf(home);
        return Files.isRegularFile(exe) ? probeExecutable(exe) : null;
    }

    /** Probes a {@code java} executable; null when the version cannot be determined. */
    public static JavaInfo probeExecutable(Path javaExecutable) {
        if (Objects.isNull(javaExecutable) || !Files.isRegularFile(javaExecutable)) {
            return null;
        }
        Path home = javaExecutable.getParent();
        if (Objects.nonNull(home) && Objects.nonNull(home.getFileName()) && "bin".equalsIgnoreCase(home.getFileName().toString())) {
            home = home.getParent();
        }
        String release = readRelease(home);
        String version = matchGroup(RELEASE_VERSION_PATTERN, release);
        if (Objects.isNull(version)) {
            version = queryVersion(javaExecutable);
        }
        if (Objects.isNull(version)) {
            return null;
        }
        String arch = matchGroup(RELEASE_ARCH_PATTERN, release);
        return new JavaInfo(Objects.isNull(home) ? javaExecutable.getParent() : home, javaExecutable, version,
                parseMajorVersion(version), Objects.isNull(arch) ? "" : arch);
    }

    private static String readRelease(Path home) {
        if (Objects.isNull(home) || !Files.isRegularFile(home.resolve("release"))) {
            return "";
        }
        try {
            return Files.readString(home.resolve("release"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String matchGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Runs {@code java -version} and returns the quoted version string, or null. */
    public static String queryVersion(Path javaExecutable) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExecutable.toString(), "-version");
            pb.redirectErrorStream(true);
            process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (Objects.nonNull((line = reader.readLine()))) {
                    sb.append(line).append('\n');
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return matchGroup(JAVA_VERSION_PATTERN, sb.toString());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (Objects.nonNull(process) && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** {@code 1.8.0_402 -> 8}, {@code 21.0.4 -> 21}, unparsable -> -1. */
    public static int parseMajorVersion(String version) {
        if (Objects.isNull(version) || version.isBlank()) {
            return -1;
        }
        String v = version.trim();
        try {
            if (v.startsWith("1.")) {
                String[] parts = v.split("\\.");
                return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            }
            int idx = 0;
            while (idx < v.length() && Character.isDigit(v.charAt(idx))) {
                idx++;
            }
            return idx == 0 ? -1 : Integer.parseInt(v.substring(0, idx));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<Path> findOnPath() {
        List<Path> found = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (Objects.isNull(pathEnv) || isMac()) {
            // macOS keeps a /usr/bin/java stub that pops up an installer dialog when run, so it is not probed.
            return found;
        }
        String exeName = isWindows() ? "java.exe" : "java";
        for (String segment : pathEnv.split(File.pathSeparator)) {
            if (segment.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(segment.trim().replace("\"", "")).resolve(exeName);
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate);
                }
            } catch (RuntimeException ignored) {
                // a malformed PATH entry is skipped
            }
        }
        return found;
    }

    private static Path javaExecutableOf(Path home) {
        return home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
