import java.util.*;
import java.util.regex.*;

/** Independent graph/model tests. No external test libraries or production changes. */
public final class AlgorithmTests {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        suite("all 512 directed three-node graphs", AlgorithmTests::allSmallGraphs);
        suite("seeded DAG command sequences and independent reference model", AlgorithmTests::modelSequences);
        suite("duplicate edges and duplicate batch roots", AlgorithmTests::duplicates);
        suite("deep dependency chain", AlgorithmTests::deepChain);
        suite("validation state is shared within a batch and fresh for each command", AlgorithmTests::validationLifetime);
        suite("API boundaries and defensive copies", AlgorithmTests::boundaries);
        suite("randomized linked structures", AlgorithmTests::structures);
        suite("manifest malformed-input robustness", AlgorithmTests::parserFuzz);
        suite("CLI commands, error recovery and successful-command history", AlgorithmTests::cliIntegration);
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) System.exit(1);
    }

    private interface Checked { void run() throws Exception; }
    private static void suite(String name, Checked action) {
        int before = failures;
        try { action.run(); }
        catch (Exception | AssertionError e) { failures++; System.out.println("FAIL " + name + ": " + e); }
        System.out.println((before == failures ? "PASS " : "FAIL ") + name);
    }
    private static void eq(Object expected, Object actual, String context) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(context + " expected=" + expected + " actual=" + actual);
    }
    private static void rejects(Checked action, String context) throws Exception {
        checks++;
        try { action.run(); }
        catch (PackageManagerException expected) { return; }
        throw new AssertionError(context + ": expected PackageManagerException");
    }
    private static String name(int i) { return "p" + i; }
    private static PackageRegistry registry(boolean[][] graph) {
        PackageRegistry registry = new PackageRegistry();
        for (int i = 0; i < graph.length; i++) {
            List<String> deps = new ArrayList<>();
            for (int j = 0; j < graph.length; j++) if (graph[i][j]) deps.add(name(j));
            registry.register(name(i), deps.toArray(new String[0]));
        }
        return registry;
    }
    // Three-color DFS is independent of the production queue/ancestor-chain algorithm.
    private static boolean cyclic(boolean[][] graph, int node, int[] color) {
        if (color[node] == 1) return true;
        if (color[node] == 2) return false;
        color[node] = 1;
        for (int j = 0; j < graph.length; j++) if (graph[node][j] && cyclic(graph, j, color)) return true;
        color[node] = 2;
        return false;
    }
    private static void allSmallGraphs() throws Exception {
        for (int mask = 0; mask < 512; mask++) {
            boolean[][] graph = new boolean[3][3];
            for (int i = 0; i < 9; i++) graph[i / 3][i % 3] = (mask & (1 << i)) != 0;
            for (int root = 0; root < 3; root++) {
                PackageRegistry r = registry(graph);
                r.register("sentinel");
                PackageManager m = new PackageManager(r);
                m.install("sentinel");
                String before = m.snapshot();
                final String requested = name(root);
                String context = "mask=" + mask + " root=" + root;
                if (cyclic(graph, root, new int[3])) {
                    rejects(() -> m.planInstall(requested), context + " cyclic dry run");
                    eq(before, m.snapshot(), context + " dry run rejection atomicity");
                    rejects(() -> m.install(requested), context);
                    eq(before, m.snapshot(), context + " rejection atomicity");
                    LinkedQueue<String> batch = new LinkedQueue<>();
                    batch.enqueue("sentinel"); batch.enqueue(requested);
                    rejects(() -> m.installAll(batch), context + " batch");
                    eq(before, m.snapshot(), context + " batch atomicity");
                } else {
                    m.install(requested);
                    int[] direct = new int[3]; direct[root] = 1;
                    Map<String, String> expected = expectedState(graph, direct);
                    expected.put("sentinel", "1/1");
                    eq(expected, state(m), context + " refs");
                    m.remove(requested);
                    eq(before, m.snapshot(), context + " cleanup");
                }
            }
        }
    }
    private static boolean[] reachable(boolean[][] graph, int[] direct) {
        boolean[] active = new boolean[graph.length];
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        for (int i = 0; i < direct.length; i++) if (direct[i] > 0) todo.add(i);
        while (!todo.isEmpty()) {
            int i = todo.remove();
            if (active[i]) continue;
            active[i] = true;
            for (int j = 0; j < graph.length; j++) if (graph[i][j]) todo.add(j);
        }
        return active;
    }
    private static Map<String, String> expectedState(boolean[][] graph, int[] direct) {
        boolean[] active = reachable(graph, direct);
        Map<String, String> result = new TreeMap<>();
        for (int i = 0; i < graph.length; i++) if (active[i]) {
            int refs = direct[i];
            for (int parent = 0; parent < graph.length; parent++) if (active[parent] && graph[parent][i]) refs++;
            result.put(name(i), refs + "/" + direct[i]);
        }
        return result;
    }
    private static Map<String, String> state(PackageManager m) {
        Map<String, String> result = new TreeMap<>();
        Matcher matcher = Pattern.compile("(\\w+)\\[ref=(\\d+),direct=(\\d+)\\]").matcher(m.snapshot());
        while (matcher.find()) {
            if (result.put(matcher.group(1), matcher.group(2) + "/" + matcher.group(3)) != null)
                throw new AssertionError("duplicate installed node");
        }
        return result;
    }
    private static void paths(boolean[][] graph, int node, int target, String prefix, List<String> out) {
        String chain = prefix.isEmpty() ? name(node) : prefix + " -> " + name(node);
        if (node == target) out.add(prefix.isEmpty() ? chain + " (installed directly)" : chain);
        for (int j = 0; j < graph.length; j++) if (graph[node][j]) paths(graph, j, target, chain, out);
    }
    private static void modelSequences() throws Exception {
        for (int seed = 0; seed < 40; seed++) {
            Random random = new Random(seed);
            boolean[][] graph = new boolean[8][8];
            for (int i = 0; i < 8; i++) for (int j = i + 1; j < 8; j++) graph[i][j] = random.nextInt(4) == 0;
            PackageManager m = new PackageManager(registry(graph));
            int[] direct = new int[8];
            for (int step = 0; step < 150; step++) {
                int root = random.nextInt(8);
                String context = "seed=" + seed + " step=" + step;
                int op = random.nextInt(5);
                if (op == 0) {
                    int oldSize = state(m).size();
                    direct[root]++;
                    eq(expectedState(graph, direct).size() - oldSize, m.install(name(root)), context + " install return");
                } else if (op == 1) {
                    if (direct[root] == 0) {
                        String before = m.snapshot();
                        rejects(() -> m.remove(name(root)), context + " remove");
                        eq(before, m.snapshot(), context + " failed remove");
                    } else {
                        int oldSize = state(m).size();
                        direct[root]--;
                        eq(oldSize - expectedState(graph, direct).size(), m.remove(name(root)), context + " remove return");
                    }
                } else if (op == 2) {
                    String before = m.snapshot();
                    InstallPlan plan = m.planInstall(name(root));
                    eq(before, m.snapshot(), context + " dry run exact state");
                    Set<String> added = new TreeSet<>(expectedState(graph, direct).keySet());
                    int[] prospective = direct.clone(); prospective[root]++;
                    Set<String> future = new TreeSet<>(expectedState(graph, prospective).keySet());
                    future.removeAll(added);
                    eq(future, new TreeSet<>(Arrays.asList(plan.getAddedPackages())), context + " dry run names");
                    eq(future.size(), plan.getAddedPackages().length, context + " dry run unique names");
                } else if (op == 3) {
                    LinkedQueue<String> batch = new LinkedQueue<>();
                    int oldSize = state(m).size();
                    for (int i = 0; i < 3; i++) { int p = random.nextInt(8); batch.enqueue(name(p)); direct[p]++; }
                    eq(expectedState(graph, direct).size() - oldSize, m.installAll(batch), context + " batch return");
                    eq(true, batch.isEmpty(), context + " consumed batch");
                } else if (reachable(graph, direct)[root]) {
                    List<String> expected = new ArrayList<>();
                    for (int i = 0; i < 8; i++) if (direct[i] > 0) paths(graph, i, root, "", expected);
                    String[] actual = m.explainWhy(name(root));
                    int lastLength = 0;
                    for (String path : actual) {
                        int length = path.split(" -> ").length;
                        eq(true, length >= lastLength, context + " why shortest first");
                        lastLength = length;
                    }
                    Collections.sort(expected);
                    Arrays.sort(actual);
                    eq(expected, Arrays.asList(actual), context + " why all paths");
                } else rejects(() -> m.explainWhy(name(root)), context + " why absent");
                eq(expectedState(graph, direct), state(m), context + " model invariant");
            }
            for (int i = 0; i < 8; i++) while (direct[i] > 0) { m.remove(name(i)); direct[i]--; }
            eq("", m.snapshot(), "complete cleanup seed=" + seed);
        }
    }
    private static void duplicates() throws Exception {
        PackageRegistry r = new PackageRegistry(); r.register("a", "b", "b"); r.register("b");
        PackageManager m = new PackageManager(r);
        LinkedQueue<String> batch = new LinkedQueue<>(); batch.enqueue("a"); batch.enqueue("a"); batch.enqueue("b");
        eq(2, m.installAll(batch), "duplicate batch newly added count");
        eq("a[ref=2,direct=2] b[ref=3,direct=1]", m.snapshot(), "duplicate refs count edges, not paths");
        m.remove("a"); m.remove("a");
        eq("b[ref=1,direct=1]", m.snapshot(), "duplicate edges released exactly twice");
        m.remove("b"); eq("", m.snapshot(), "duplicate cleanup");
    }
    private static void deepChain() throws Exception {
        PackageRegistry r = new PackageRegistry();
        for (int i = 0; i < 1500; i++) r.register(name(i), i == 1499 ? new String[0] : new String[]{name(i + 1)});
        PackageManager m = new PackageManager(r);
        eq(1500, m.install("p0"), "deep iterative install");
        eq(1, m.explainWhy("p1499").length, "deep why");
        eq(1500, m.remove("p0"), "deep iterative removal");
        eq("", m.snapshot(), "deep cleanup");
    }

    private static void validationLifetime() throws Exception {
        PackageRegistry r = new PackageRegistry();
        r.register("a", "leaf"); r.register("b", "leaf"); r.register("leaf");
        PackageManager m = new PackageManager(r);
        LinkedQueue<String> batch = new LinkedQueue<>(); batch.enqueue("a"); batch.enqueue("b"); batch.enqueue("a");
        eq(3, m.installAll(batch), "shared batch graph accepts cross edges and repeated roots");
        eq("a[ref=2,direct=2] leaf[ref=2,direct=0] b[ref=1,direct=1]", m.snapshot(), "batch references");
        String before = m.snapshot();
        r.register("leaf", "a");
        rejects(() -> m.install("a"), "installed root is revalidated on next install");
        rejects(() -> m.planInstall("b"), "dry run revalidates previously completed nodes");
        LinkedQueue<String> invalidBatch = new LinkedQueue<>(); invalidBatch.enqueue("a"); invalidBatch.enqueue("b");
        rejects(() -> m.installAll(invalidBatch), "batch does not reuse old command validation");
        eq(before, m.snapshot(), "failed revalidation leaves existing state unchanged");
        r.register("leaf");
        eq(0, m.install("b"), "recovery after registry cycle is removed");
        m.remove("a"); m.remove("a"); m.remove("b"); m.remove("b");
        eq("", m.snapshot(), "validation state never changes acquisition reference semantics");
    }
    private static void boundaries() throws Exception {
        PackageManager m = new PackageManager(PackageRegistry.createDefault());
        for (String invalid : new String[]{null, "", " ", "ghost", "LOGGER"}) {
            rejects(() -> m.install(invalid), "invalid install " + invalid);
            rejects(() -> m.remove(invalid), "invalid remove " + invalid);
            rejects(() -> m.planInstall(invalid), "invalid plan " + invalid);
            rejects(() -> m.explainWhy(invalid), "invalid why " + invalid);
        }
        eq(0, m.installAll(new LinkedQueue<>()), "empty batch");
        LinkedQueue<String> batch = new LinkedQueue<>(); batch.enqueue("logger"); batch.enqueue(null);
        rejects(() -> m.installAll(batch), "null batch element");
        eq("", m.snapshot(), "invalid batch atomicity");
        String[] deps = {"b"}; PackageRegistry r = new PackageRegistry(); r.register("a", deps); deps[0] = "changed";
        eq("b", r.findDependencies("a")[0], "registry input defensive copy");
        String[] exposed = r.findDependencies("a"); exposed[0] = "changed";
        eq("b", r.findDependencies("a")[0], "registry output defensive copy");
        for (int i = 0; i < 100; i++) r.register(name(i));
        eq(0, r.findDependencies("p99").length, "registry array grows");
        r.register("a"); eq(0, r.findDependencies("a").length, "registry update");
        String[] added = {"a"}; InstallPlan plan = new InstallPlan("a", added, new String[]{"b"}); added[0] = "changed";
        plan.getAddedPackages()[0] = "changed"; plan.getReferencedPackages()[0] = "changed";
        eq("a", plan.getAddedPackages()[0], "plan defensive added copy");
        eq("b", plan.getReferencedPackages()[0], "plan defensive referenced copy");
        eq("a", plan.getRequestedPackage(), "plan request");
    }
    private static void structures() {
        Random random = new Random(1491);
        LinkedQueue<Integer> queue = new LinkedQueue<>(); LinkedStack<Integer> stack = new LinkedStack<>();
        ArrayDeque<Integer> q = new ArrayDeque<>(), s = new ArrayDeque<>();
        InstalledPackageList list = new InstalledPackageList(); List<String> reference = new ArrayList<>();
        for (int step = 0; step < 3000; step++) {
            if (q.isEmpty() || random.nextBoolean()) { queue.enqueue(step); q.add(step); }
            else eq(q.remove(), queue.dequeue(), "queue sequence " + step);
            if (s.isEmpty() || random.nextBoolean()) { stack.push(step); s.push(step); }
            else eq(s.pop(), stack.pop(), "stack sequence " + step);
            eq(q.size(), queue.size(), "queue size"); eq(s.size(), stack.size(), "stack size");
            eq(q.isEmpty(), queue.isEmpty(), "queue empty"); eq(s.isEmpty(), stack.isEmpty(), "stack empty");
            if (reference.isEmpty() || random.nextBoolean()) {
                int index = random.nextInt(reference.size() + 1);
                reference.add(index, name(step)); list.insertAt(index, new PackageNode(name(step)));
            } else {
                int index = random.nextInt(reference.size());
                String target = reference.remove(index);
                eq(index, list.indexOf(target), "list index");
                PackageNode removed = list.remove(target);
                eq(target, removed.getName(), "removed name"); eq(null, removed.getNext(), "detached node");
            }
            List<String> actual = new ArrayList<>();
            for (PackageNode node = list.first(); node != null; node = node.getNext()) {
                actual.add(node.getName());
                if (actual.size() > reference.size()) throw new AssertionError("list cycle");
            }
            eq(reference, actual, "list ordering " + step); eq(reference.size(), list.size(), "list size");
        }
        for (int bad : new int[]{-1, list.size() + 1}) {
            checks++;
            try { list.insertAt(bad, new PackageNode("bad")); throw new AssertionError("invalid index accepted"); }
            catch (IllegalArgumentException expected) { /* expected */ }
        }
    }
    private static void parserFuzz() throws Exception {
        rejects(() -> ManifestParser.parseDependencies(null), "null manifest");
        Random random = new Random(20261113);
        String alphabet = "{}[]\"\\,: abcdef0123\n\t";
        for (int i = 0; i < 5000; i++) {
            StringBuilder input = new StringBuilder();
            for (int j = random.nextInt(100); j > 0; j--) input.append(alphabet.charAt(random.nextInt(alphabet.length())));
            try { ManifestParser.parseDependencies(input.toString()); }
            catch (PackageManagerException expected) { /* malformed text must not crash with runtime exceptions */ }
            checks++;
        }
    }

    private static void cliIntegration() throws Exception {
        String output = AuditRegressionTests.cli("\n  \nHELP\nINSTALL logger\ninstall --dry-run utils\n"
                + "why utils\nremove utils\ninstall-json {\"dependencies\":[\"logger\",\"ghost\"]}\n"
                + "install\nwhy utils extra\nremove\nunknown-command\ninstall-file no-such-manifest-20261113.json\n"
                + "list\nhistory\nquit\n");
        eq(true, output.contains("logger -> utils"), "CLI why");
        eq(true, output.contains("was not installed directly"), "CLI dependency removal rejection");
        eq(true, output.contains("Package 'ghost' was not found"), "CLI batch validation");
        eq(true, output.contains("cannot read 'no-such-manifest-20261113.json'"), "CLI file error");
        eq(true, output.contains("Unknown command 'unknown-command'"), "CLI unknown command");
        eq(true, output.contains("logger (ref_count = 1)"), "CLI failed batch and dry run preserve root ref");
        eq(true, output.contains("utils (ref_count = 1)"), "CLI dependency ref unchanged");
        String history = output.substring(output.lastIndexOf("Successful commands (newest first):"));
        eq(true, history.contains("1. list"), "history newest");
        eq(true, history.contains("2. why utils"), "history why");
        eq(true, history.contains("3. install --dry-run utils"), "history dry run");
        eq(true, history.contains("4. install logger"), "history install normalized command");
        eq(false, history.contains("5."), "failed commands excluded from history");

        String manifestOutput = AuditRegressionTests.cli("install-json {\"dependencies\":[\"logger\",\"utils\"]}\n"
                + "remove logger\nlist\nexit\n");
        eq(true, manifestOutput.contains("Installed 2 package(s) from the manifest (2 new package(s) added)"), "CLI manifest success");
        eq(true, manifestOutput.contains("Installed packages (1)"), "CLI manifest independent roots");
        eq(true, manifestOutput.contains("utils (ref_count = 1)"), "CLI direct dependency survives removal");
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("mini-npm-default-manifest-");
        java.nio.file.Path file = directory.resolve("package.json");
        try {
            java.nio.file.Files.writeString(file, "{\"dependencies\":[\"logger\",\"utils\"]}");
            String defaultManifest = AuditRegressionTests.cli("install-file\nlist\nexit\n", directory);
            eq(true, defaultManifest.contains("Installed 2 package(s) from the manifest (2 new package(s) added)"), "default manifest path");
            eq(true, defaultManifest.contains("utils (ref_count = 2)"), "default manifest installed references");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }
}
