public final class ManagerTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
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

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static PackageManager newManager() {
        return new PackageManager(PackageRegistry.createDefault());
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
        check("cycle found late: earlier changes (utils counter) rolled back", before, m.snapshot());
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
