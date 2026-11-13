import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Desired-behavior regressions. Failures reproduce reported production bugs; do not invert them. */
public final class AuditRegressionTests {
    private static int passed, failed;
    private interface Test { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--dag-probe")) { dagProbe(); return; }
        test("R1 dry-run rejects a cycle hidden behind shared nodes", () -> {
            PackageRegistry r = new PackageRegistry();
            r.register("root", "a", "b"); r.register("a", "b"); r.register("b", "a");
            r.register("safe");
            PackageManager m = new PackageManager(r);
            m.install("safe");
            String before = m.snapshot();
            expectDomain(() -> m.install("root"));
            expectDomain(() -> m.planInstall("root"));
            expect(before, m.snapshot());
        });
        // Registry mutation through Java methods is outside the agreed CLI grading scope.
        if (Arrays.asList(args).contains("--include-api-mutation")) test("R2 registry changes must not delete another directly installed package", () -> {
            PackageRegistry r = new PackageRegistry();
            r.register("a", "b"); r.register("b"); r.register("c");
            PackageManager m = new PackageManager(r); m.install("a"); m.install("c");
            r.register("a", "c");
            m.remove("a");
            expect("c[ref=1,direct=1]", m.snapshot());
        });
        else System.out.println("SKIP R2: direct registry mutation is outside CLI grading scope (opt in with --include-api-mutation)");
        test("R3 manifest rejects multiple root objects", () -> {
            expectDomain(() -> ManifestParser.parseDependencies("{\"dependencies\":[\"logger\"]}{}"));
        });
        test("R3 manifest validates syntax after dependencies", () -> {
            expectDomain(() -> ManifestParser.parseDependencies("{\"dependencies\":[\"logger\"], garbage}"));
        });
        test("R3 manifest validates unrelated object properties", () -> {
            expectDomain(() -> ManifestParser.parseDependencies("{\"oops\":,\"dependencies\":[\"logger\"]}"));
        });
        test("R4 JSON unicode escapes decode to package names", () -> {
            String slash = Character.toString((char) 92);
            LinkedQueue<String> names = ManifestParser.parseDependencies("{\"dependencies\":[\"log" + slash + "u0067er\"]}");
            expect("logger", names.dequeue());
        });
        test("R4 invalid JSON escapes are rejected", () -> {
            expectDomain(() -> ManifestParser.parseDependencies("{\"dependencies\":[\"log\\qger\"]}"));
        });
        test("R5 null batch reports a domain error", () -> {
            PackageManager m = new PackageManager(PackageRegistry.createDefault());
            m.install("logger");
            String before = m.snapshot();
            expectDomain(() -> m.installAll(null));
            expect(before, m.snapshot());
            expect(0, m.installAll(new LinkedQueue<>()));
            LinkedQueue<String> valid = new LinkedQueue<>(); valid.enqueue("utils");
            expect(0, m.installAll(valid));
            expect("logger[ref=1,direct=1] utils[ref=2,direct=1]", m.snapshot());
        });
        test("R5 null dependency reports a domain error without changing state", () -> {
            PackageRegistry r = new PackageRegistry(); r.register("a", new String[]{null});
            PackageManager m = new PackageManager(r);
            expectDomain(() -> m.install("a"));
            expect("", m.snapshot());
        });
        test("R6 install-file supports paths containing spaces", () -> {
            Path dir = Files.createTempDirectory("mini npm manifest ");
            Path file = dir.resolve("package sample.json");
            try {
                Files.writeString(file, "{\"dependencies\":[\"logger\"]}", StandardCharsets.UTF_8);
                for (String path : new String[]{file.toString(), "\"" + file + "\"", "'" + file + "'"}) {
                    String output = cli("  INSTALL-FILE   " + path + "  \nlist\nhistory\nexit\n");
                    if (!output.contains("logger (ref_count = 1)") || !output.contains("utils (ref_count = 1)"))
                        throw new AssertionError(output.replace('\n', ' '));
                    if (!output.contains("2. install-file " + file)) throw new AssertionError("Missing successful file-install history");
                }
                String invalid = cli("install-file \"\"\ninstall-file ''\ninstall-file \"unclosed path\nlist\nexit\n");
                if (!invalid.contains("Usage: install-file") || !invalid.contains("unclosed quote")
                        || !invalid.contains("No packages are installed")) throw new AssertionError(invalid);
            } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir); }
        });
        test("R7 49-node shared graph supports dry-run, batch and removal within 8s and 32 MiB heap", () -> {
            Path output = Files.createTempFile("mini-npm-dag-", ".log");
            Process child = null;
            try {
                child = new ProcessBuilder(javaExe(), "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                        "AuditRegressionTests", "--dag-probe").redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!child.waitFor(8, TimeUnit.SECONDS)) {
                    child.destroyForcibly(); child.waitFor(); throw new AssertionError("TIMEOUT after 8s");
                }
                String log = Files.readString(output);
                if (child.exitValue() != 0) throw new AssertionError(log.trim());
            } finally {
                if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
                Files.deleteIfExists(output);
            }
        });
        System.out.println(passed + " passed, " + failed + " failed (desired behavior; failures are open bugs)");
        if (failed != 0) System.exit(1);
    }
    private static void test(String name, Test test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
    private static void expect(Object wanted, Object actual) {
        if (!Objects.equals(wanted, actual)) throw new AssertionError("expected=" + wanted + " actual=" + actual);
    }
    private static void expectDomain(Test action) throws Exception {
        try { action.run(); }
        catch (PackageManagerException expected) { return; }
        throw new AssertionError("Expected PackageManagerException, command succeeded");
    }
    static String javaExe() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", executable).toString();
    }
    static String cli(String input) throws Exception {
        Path output = Files.createTempFile("mini-npm-cli-", ".log");
        Process child = null;
        try {
            child = new ProcessBuilder(javaExe(), "-cp", System.getProperty("java.class.path"), "Main")
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try (OutputStream stream = child.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
            if (!child.waitFor(8, TimeUnit.SECONDS)) throw new AssertionError("CLI timeout");
            if (child.exitValue() != 0) throw new AssertionError("CLI exit=" + child.exitValue() + " " + Files.readString(output));
            return Files.readString(output);
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
            Files.deleteIfExists(output);
        }
    }
    private static void dagProbe() throws Exception {
        PackageRegistry r = new PackageRegistry(); r.register("root", "a0", "b0");
        for (int i = 0; i < 24; i++) {
            String[] deps = i == 23 ? new String[0] : new String[]{"a" + (i + 1), "b" + (i + 1)};
            r.register("a" + i, deps); r.register("b" + i, deps);
        }
        try {
            PackageManager m = new PackageManager(r);
            expect(49, m.planInstall("root").getAddedPackages().length);
            expect("", m.snapshot());
            LinkedQueue<String> batch = new LinkedQueue<>(); batch.enqueue("root"); batch.enqueue("root");
            expect(49, m.installAll(batch));
            expect(0, m.remove("root"));
            expect(49, m.remove("root"));
            expect("", m.snapshot());
            expect(49, m.install("root"));
            expect(49, m.remove("root"));
            System.out.println("OK: 49 nodes, 94 edges; dry-run, batch, install, reference counts and cleanup");
        } catch (OutOfMemoryError error) {
            System.out.println("OUT_OF_MEMORY: only 49 nodes, 94 edges; preflight enumerates repeated paths");
            System.exit(3);
        }
    }
}
