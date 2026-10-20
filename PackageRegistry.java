public final class PackageRegistry {

    private static final int INITIAL_CAPACITY = 8;

    private static final class Entry {
        private final String name;
        private String[] dependencies;

        private Entry(String name, String[] dependencies) {
            this.name = name;
            this.dependencies = dependencies;
        }
    }

    private Entry[] entries = new Entry[INITIAL_CAPACITY];
    private int count;

    public static PackageRegistry createDefault() {
        PackageRegistry registry = new PackageRegistry();
        registry.register("app", "ui", "net");
        registry.register("ui", "core", "utils");
        registry.register("net", "core");
        registry.register("core","app");
        registry.register("utils");
        registry.register("logger", "utils");
        registry.register("x", "y");
        registry.register("y", "x");
        registry.register("selfref", "selfref");
        registry.register("broken", "utils", "ghost");
        return registry;
    }

    public void register(String name, String... dependencies) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Package name must not be empty.");
        }
        Entry existing = findEntry(name);
        if (existing != null) {
            existing.dependencies = dependencies.clone();
            return;
        }
        growIfFull();
        entries[count++] = new Entry(name, dependencies.clone());
    }

    public String[] findDependencies(String name) {
        Entry entry = findEntry(name);
        return entry == null ? null : entry.dependencies.clone();
    }

    private Entry findEntry(String name) {
        for (int i = 0; i < count; i++) {
            if (entries[i].name.equals(name)) {
                return entries[i];
            }
        }
        return null;
    }

    private void growIfFull() {
        if (count < entries.length) {
            return;
        }
        Entry[] bigger = new Entry[entries.length * 2];
        for (int i = 0; i < count; i++) {
            bigger[i] = entries[i];
        }
        entries = bigger;
    }
}
