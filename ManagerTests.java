public final class ManagerTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        explicitRegistryCyclesAreRejected();
        diamondInstallAndFullRemove();
        sharedDependencySurvivesRemoval();
        dependencyCannotBeRemovedDirectly();
        installRollbackRestoresEverything();
        duplicateInstallRequests();
        directAndIndirectReference();
        dependencyCyclesAreRejected();
        unknownAndMissingPackages();
        removeRollbackRestoresStateAndOrder();
        linkedListOperations();
        queueAndStack();
        historyIsNotDestroyedByReading();

        dryRunReportsPlanAndChangesNothing();
        dryRunWithAlreadyInstalledPackages();
        dryRunOfInstalledPackageOnlyRaisesReferences();
        dryRunFailureLeavesStateAlone();
        dryRunMatchesRealInstall();

        whyShowsEveryChain();
        whyListsShortestChainsFirst();
        whyPackageThatIsAlsoADependency();
        whyRejectsPackagesThatAreNotInstalled();
        whyTerminatesEvenWithCyclicRegistry();

        manifestListIsReadInOrder();
        manifestObjectStyleIsAccepted();
        manifestIgnoresOtherKeysAndNesting();
        manifestBracketsInsideStringsAreIgnored();
        manifestEmptyDependencies();
        manifestBracketErrorsAreReported();
        manifestStructureErrorsAreReported();
        manifestInstallIsAllOrNothing();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static PackageManager newManager() {
        // Valid diamond fixture; default registry deliberately contains cycles.
        PackageRegistry registry = PackageRegistry.createDefault();
        registry.register("core");
        return new PackageManager(registry);
    }

    private static void explicitRegistryCyclesAreRejected() {
        PackageRegistry registry = PackageRegistry.createDefault();
        // This test owns the cycle; production samples may legitimately change.
        registry.register("core", "app");
        PackageManager m = new PackageManager(registry);
        run(() -> m.install("logger"));
        String before = m.snapshot();
        for (String name : new String[]{"app", "ui", "net", "core", "x", "y", "selfref"}) {
            check("default cyclic package rejected: " + name, "true",
                    String.valueOf(fails(() -> m.install(name))));
            check("cycle rejection preserves counters and order: " + name, before, m.snapshot());
            check("cyclic dry run rejected: " + name, "true",
                    String.valueOf(fails(() -> m.planInstall(name))));
            check("failed dry run preserves state: " + name, before, m.snapshot());
        }
        check("default cycle path is explained", "true", String.valueOf(
                messageOf(() -> m.install("app")).contains("app -> ui -> core -> app")));
        check("batch containing default cycle rejected", "true", String.valueOf(
                fails(() -> m.installAll(queueOf("utils", "app")))));
        check("batch validation preserves pre-existing state", before, m.snapshot());
    }

    private static void diamondInstallAndFullRemove() {
        PackageManager m = newManager();
        run(() -> m.install("app"));
        check("diamond: shared 'core' has ref 2",
                "app[ref=1,direct=1] ui[ref=1,direct=0] net[ref=1,direct=0] core[ref=2,direct=0] utils[ref=1,direct=0]",
                m.snapshot());
        run(() -> m.remove("app"));
        check("diamond: removing app clears everything", "", m.snapshot());
    }

    private static void sharedDependencySurvivesRemoval() {
        PackageManager m = newManager();
        run(() -> m.install("app"));
        run(() -> m.install("logger"));
        run(() -> m.remove("app"));
        check("shared dependency 'utils' survives",
                "utils[ref=1,direct=0] logger[ref=1,direct=1]", m.snapshot());
    }

    private static void dependencyCannotBeRemovedDirectly() {
        PackageManager m = newManager();
        run(() -> m.install("logger"));
        String before = m.snapshot();
        boolean rejected = fails(() -> m.remove("utils"));
        check("removing a pure dependency is rejected", "true", String.valueOf(rejected));
        check("...and nothing changed", before, m.snapshot());
    }

    private static void installRollbackRestoresEverything() {
        PackageManager m = newManager();
        run(() -> m.install("logger"));
        String before = m.snapshot();
        boolean failedAsExpected = fails(() -> m.install("broken"));
        check("install of 'broken' fails", "true", String.valueOf(failedAsExpected));
        check("rollback restored the exact previous state", before, m.snapshot());
    }

    private static void duplicateInstallRequests() {
        PackageManager m = newManager();
        run(() -> m.install("app"));
        run(() -> m.install("app"));
        check("second install only raises app's counters",
                "app[ref=2,direct=2] ui[ref=1,direct=0] net[ref=1,direct=0] core[ref=2,direct=0] utils[ref=1,direct=0]",
                m.snapshot());
        run(() -> m.remove("app"));
        check("first remove only lowers app's counters",
                "app[ref=1,direct=1] ui[ref=1,direct=0] net[ref=1,direct=0] core[ref=2,direct=0] utils[ref=1,direct=0]",
                m.snapshot());
        run(() -> m.remove("app"));
        check("second remove deletes everything", "", m.snapshot());
    }

    private static void directAndIndirectReference() {
        PackageManager m = newManager();
        run(() -> m.install("logger"));
        run(() -> m.install("utils"));
        check("utils is both direct and a dependency",
                "logger[ref=1,direct=1] utils[ref=2,direct=1]", m.snapshot());
        run(() -> m.remove("logger"));
        check("utils survives its dependent", "utils[ref=1,direct=1]", m.snapshot());
        run(() -> m.remove("utils"));
        check("utils removed at ref 0", "", m.snapshot());
    }

    private static void dependencyCyclesAreRejected() {
        PackageRegistry registry = PackageRegistry.createDefault();
        registry.register("p", "q");
        registry.register("q", "r");
        registry.register("r", "p");
        registry.register("top", "utils", "p");
        PackageManager m = new PackageManager(registry);

        check("x <-> y is rejected", "true", String.valueOf(fails(() -> m.install("x"))));
        check("...and leaves the system empty", "", m.snapshot());
        check("self dependency is rejected", "true", String.valueOf(fails(() -> m.install("selfref"))));
        check("...and leaves the system empty", "", m.snapshot());
        check("error message shows the cycle", "true",
                String.valueOf(messageOf(() -> m.install("p")).contains("p -> q -> r -> p")));

        run(() -> m.install("logger"));
        String before = m.snapshot();
        check("cycle found late: command fails", "true", String.valueOf(fails(() -> m.install("top"))));
        check("preflight cycle rejection preserves utils counter", before, m.snapshot());
    }

    private static void unknownAndMissingPackages() {
        PackageManager m = newManager();
        check("install unknown package fails", "true", String.valueOf(fails(() -> m.install("nope"))));
        check("remove not-installed package fails", "true", String.valueOf(fails(() -> m.remove("app"))));
        check("empty name rejected", "true", String.valueOf(fails(() -> m.install(""))));
        check("state still empty", "", m.snapshot());
    }

    private static void removeRollbackRestoresStateAndOrder() {
        PackageRegistry registry = PackageRegistry.createDefault();
        registry.register("app2", "utils");
        PackageManager m = new PackageManager(registry);
        run(() -> m.install("app2"));
        run(() -> m.install("logger"));
        String before = m.snapshot();
        check("setup", "app2[ref=1,direct=1] utils[ref=2,direct=0] logger[ref=1,direct=1]", before);

        registry.register("app2", "utils", "phantom");
        boolean failedAsExpected = fails(() -> m.remove("app2"));
        check("remove fails halfway", "true", String.valueOf(failedAsExpected));
        check("rollback restored counters AND the original list position", before, m.snapshot());
    }

    private static void linkedListOperations() {
        InstalledPackageList list = new InstalledPackageList();
        list.append(new PackageNode("a"));
        list.append(new PackageNode("b"));
        list.append(new PackageNode("c"));
        PackageNode b = list.remove("b");
        list.insertAt(1, b);
        check("remove middle + insertAt restores order",
                "a[ref=1,direct=0] b[ref=1,direct=0] c[ref=1,direct=0]", list.snapshot());
        list.remove("c");
        list.append(new PackageNode("d"));
        check("tail pointer is correct after removing the tail",
                "a[ref=1,direct=0] b[ref=1,direct=0] d[ref=1,direct=0]", list.snapshot());
        PackageNode a = list.remove("a");
        list.insertAt(0, a);
        check("head insert", "a[ref=1,direct=0] b[ref=1,direct=0] d[ref=1,direct=0]", list.snapshot());
        list.remove("a");
        list.remove("b");
        list.remove("d");
        list.append(new PackageNode("z"));
        check("list works again after becoming empty", "z[ref=1,direct=0]", list.snapshot());
        check("remove of a missing name returns null", "true", String.valueOf(list.remove("nope") == null));
    }

    private static void queueAndStack() {
        LinkedQueue<String> queue = new LinkedQueue<>();
        queue.enqueue("1");
        queue.enqueue("2");
        queue.enqueue("3");
        check("queue is FIFO", "123", queue.dequeue() + queue.dequeue() + queue.dequeue());
        check("queue empty again", "true", String.valueOf(queue.isEmpty()));
        queue.enqueue("4");
        check("queue reusable after emptying", "4", queue.dequeue());
        check("dequeue on empty throws", "true", String.valueOf(throwsIllegalState(() -> queue.dequeue())));

        LinkedStack<String> stack = new LinkedStack<>();
        stack.push("1");
        stack.push("2");
        stack.push("3");
        check("stack is LIFO", "321", stack.pop() + stack.pop() + stack.pop());
        check("pop on empty throws", "true", String.valueOf(throwsIllegalState(() -> stack.pop())));
    }

    private static void historyIsNotDestroyedByReading() {
        CommandHistory history = new CommandHistory();
        history.record("install app");
        history.record("list");
        history.record("remove app");
        String first = join(history.newestFirst());
        String second = join(history.newestFirst());
        check("history newest first", "remove app|list|install app", first);
        check("reading history twice gives the same result", first, second);
    }

    // ---------------------------------------------------------------- install --dry-run

    private static void dryRunReportsPlanAndChangesNothing() {
        PackageManager m = newManager();
        InstallPlan plan = planOf(m, "app");
        if (plan == null) {
            return;
        }
        check("dry run: new packages in install order", "app|ui|net|core|utils", join(plan.getAddedPackages()));
        check("dry run: shared 'core' only gets a reference", "core", join(plan.getReferencedPackages()));
        check("dry run: nothing was installed", "", m.snapshot());
    }

    private static void dryRunWithAlreadyInstalledPackages() {
        PackageManager m = newManager();
        run(() -> m.install("logger"));
        String before = m.snapshot();
        InstallPlan plan = planOf(m, "app");
        if (plan == null) {
            return;
        }
        check("dry run: 'utils' is already there, so it is only referenced",
                "app|ui|net|core", join(plan.getAddedPackages()));
        check("dry run: references raised", "utils|core", join(plan.getReferencedPackages()));
        check("dry run: counters and list order untouched", before, m.snapshot());
    }

    private static void dryRunOfInstalledPackageOnlyRaisesReferences() {
        PackageManager m = newManager();
        run(() -> m.install("app"));
        String before = m.snapshot();
        InstallPlan plan = planOf(m, "app");
        if (plan == null) {
            return;
        }
        check("dry run of an installed package adds nothing", "", join(plan.getAddedPackages()));
        check("...it would only raise its own reference", "app", join(plan.getReferencedPackages()));
        check("...and the state is unchanged", before, m.snapshot());
    }

    private static void dryRunFailureLeavesStateAlone() {
        PackageManager m = newManager();
        run(() -> m.install("logger"));
        String before = m.snapshot();
        check("dry run of 'broken' fails", "true", String.valueOf(fails(() -> m.planInstall("broken"))));
        check("dry run of a cycle fails", "true", String.valueOf(fails(() -> m.planInstall("x"))));
        check("dry run of an unknown package fails", "true", String.valueOf(fails(() -> m.planInstall("nope"))));
        check("failed dry runs leave the state alone", before, m.snapshot());
    }

    private static void dryRunMatchesRealInstall() {
        PackageManager m = newManager();
        InstallPlan plan = planOf(m, "app");
        if (plan == null) {
            return;
        }
        int[] realCount = new int[1];
        run(() -> realCount[0] = m.install("app"));
        check("dry run predicted exactly how many packages the real install adds",
                String.valueOf(plan.getAddedPackages().length), String.valueOf(realCount[0]));
        check("a dry run beforehand does not change the real result",
                "app[ref=1,direct=1] ui[ref=1,direct=0] net[ref=1,direct=0] core[ref=2,direct=0] utils[ref=1,direct=0]",
                m.snapshot());
    }

    // ---------------------------------------------------------------------------- why

    private static void whyShowsEveryChain() {
        PackageManager m = newManager();
        run(() -> m.install("app"));
        check("why core: both chains", "app -> ui -> core|app -> net -> core", whyOf(m, "core"));
        check("why utils", "app -> ui -> utils", whyOf(m, "utils"));
        check("why app: installed directly", "app (installed directly)", whyOf(m, "app"));
    }

    private static void whyListsShortestChainsFirst() {
        PackageManager m = newManager();
        run(() -> m.install("app"));
        run(() -> m.install("logger"));
        check("why utils: the short chain comes first", "logger -> utils|app -> ui -> utils", whyOf(m, "utils"));
    }

    private static void whyPackageThatIsAlsoADependency() {
        PackageManager m = newManager();
        run(() -> m.install("logger"));
        run(() -> m.install("utils"));
        check("why utils: direct AND needed by logger", "utils (installed directly)|logger -> utils", whyOf(m, "utils"));
    }

    private static void whyRejectsPackagesThatAreNotInstalled() {
        PackageManager m = newManager();
        check("why on a package that is not installed fails", "true", String.valueOf(fails(() -> m.explainWhy("app"))));
        check("why with an empty name fails", "true", String.valueOf(fails(() -> m.explainWhy(""))));
    }

    private static void whyTerminatesEvenWithCyclicRegistry() {
        PackageRegistry registry = new PackageRegistry();
        registry.register("a", "b");
        registry.register("b");
        PackageManager m = new PackageManager(registry);
        run(() -> m.install("a"));
        registry.register("b", "a");
        check("why does not loop forever if the registry later gains a cycle",
                "a (installed directly)", whyOf(m, "a"));
    }

    // ------------------------------------------------------------- manifest install

    private static void manifestListIsReadInOrder() {
        check("manifest: names come out in file order",
                "ui|logger", manifestNames("{\"name\":\"app\",\"dependencies\":[\"ui\",\"logger\"]}"));
        check("manifest: whitespace and newlines are fine",
                "ui|logger", manifestNames("{\n  \"dependencies\" :\n [ \"ui\" ,\n \"logger\" ]\n}"));
    }

    private static void manifestObjectStyleIsAccepted() {
        check("manifest: npm style object, versions ignored",
                "ui|logger", manifestNames("{\"dependencies\":{\"ui\":\"^1.0.0\",\"logger\":\"2.1\"}}"));
    }

    private static void manifestIgnoresOtherKeysAndNesting() {
        check("manifest: only the top-level \"dependencies\" key counts", "core",
                manifestNames("{\"note\":\"dependencies\",\"devDependencies\":[\"bad\"],"
                        + "\"scripts\":{\"dependencies\":[\"worse\"]},\"dependencies\":[\"core\"]}"));
    }

    private static void manifestBracketsInsideStringsAreIgnored() {
        check("manifest: brackets inside quotes are just text",
                "a]b|c{d", manifestNames("{\"dependencies\":[\"a]b\",\"c{d\"]}"));
    }

    private static void manifestEmptyDependencies() {
        check("manifest: empty list", "", manifestNames("{\"dependencies\":[]}"));
        check("manifest: empty object", "", manifestNames("{\"dependencies\":{}}"));
    }

    private static void manifestBracketErrorsAreReported() {
        check("unclosed bracket is named with its position", "true", String.valueOf(
                manifestNames("{\"dependencies\":[\"ui\"]").contains("'{' opened at position 1 is never closed")));
        check("extra closing bracket", "true", String.valueOf(
                manifestNames("{\"dependencies\":[\"ui\"]}}").contains("unexpected '}' at position 24")));
        check("wrong kind of closing bracket", "true", String.valueOf(manifestNames("{\"dependencies\":[\"ui\"}]")
                .contains("'[' opened at position 17 is closed by '}' at position 22")));
        check("unterminated string", "true", String.valueOf(
                manifestNames("{\"dependencies\":[\"ui]}").contains("string starting at position 18 is never closed")));
    }

    private static void manifestStructureErrorsAreReported() {
        check("empty manifest", "true", String.valueOf(manifestNames("   ").contains("empty")));
        check("root must be an object", "true", String.valueOf(
                manifestNames("[\"ui\"]").contains("single { ... } object")));
        check("missing dependencies key", "true", String.valueOf(
                manifestNames("{\"name\":\"x\"}").contains("no \"dependencies\" key")));
        check("trailing comma", "true", String.valueOf(
                manifestNames("{\"dependencies\":[\"ui\",]}").contains("expected a quoted package name")));
        check("non-string entry", "true", String.valueOf(
                manifestNames("{\"dependencies\":[1]}").contains("expected a quoted package name")));
        check("missing comma", "true", String.valueOf(
                manifestNames("{\"dependencies\":[\"ui\" \"net\"]}").contains("expected ',' or ']'")));
        check("dependencies of the wrong type", "true", String.valueOf(
                manifestNames("{\"dependencies\":\"ui\"}").contains("must be a list")));
    }

    private static void manifestInstallIsAllOrNothing() {
        PackageManager m = newManager();
        run(() -> m.installAll(queueOf("ui", "logger")));
        check("manifest install: packages installed in manifest order, shared 'utils' counted twice",
                "ui[ref=1,direct=1] core[ref=1,direct=0] utils[ref=2,direct=0] logger[ref=1,direct=1]", m.snapshot());

        PackageManager fresh = newManager();
        check("manifest with an unknown package fails", "true",
                String.valueOf(fails(() -> fresh.installAll(queueOf("ui", "ghost")))));
        check("preflight rejects the batch before installing ui", "", fresh.snapshot());

        PackageManager busy = newManager();
        run(() -> busy.install("logger"));
        String before = busy.snapshot();
        check("manifest hitting a failing package fails", "true",
                String.valueOf(fails(() -> busy.installAll(queueOf("net", "broken")))));
        check("...and the previous state is restored exactly", before, busy.snapshot());
    }

    private static InstallPlan planOf(PackageManager m, String name) {
        try {
            return m.planInstall(name);
        } catch (PackageManagerException e) {
            check("unexpected failure: " + e.getMessage(), "no exception", "exception");
            return null;
        }
    }

    private static String whyOf(PackageManager m, String name) {
        try {
            return join(m.explainWhy(name));
        } catch (PackageManagerException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static String manifestNames(String text) {
        try {
            return join(Queues.drainToArray(ManifestParser.parseDependencies(text)));
        } catch (PackageManagerException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static LinkedQueue<String> queueOf(String... names) {
        LinkedQueue<String> queue = new LinkedQueue<>();
        for (String name : names) {
            queue.enqueue(name);
        }
        return queue;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void run(Action action) {
        try {
            action.run();
        } catch (Exception e) {
            check("unexpected failure: " + e.getMessage(), "no exception", "exception");
        }
    }

    private static String messageOf(Action action) {
        try {
            action.run();
            return "";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static boolean fails(Action action) {
        try {
            action.run();
            return false;
        } catch (PackageManagerException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean throwsIllegalState(Action action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String join(String[] parts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                text.append('|');
            }
            text.append(parts[i]);
        }
        return text.toString();
    }

    private static void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "\n      expected: " + expected + "\n      actual:   " + actual);
        }
    }
}
