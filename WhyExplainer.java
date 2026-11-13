/**
 * Answers "why is this package installed?" (like {@code npm why}).
 *
 * <p>The search starts at the target and walks <em>upwards</em>: from a package to the installed
 * packages that depend on it, level by level, using a {@link LinkedQueue}. Every step remembers the
 * step it came from ({@code towardTarget}), so a finished chain can be read from the package you
 * installed straight down to the target, with no copying and no reversing.
 *
 * <p>A chain is reported when it reaches a package that was installed directly
 * ({@code direct_count > 0}). Shorter chains are found first.
 */
final class WhyExplainer {

    private static final class Step {
        private final String packageName;
        private final Step towardTarget;

        private Step(String packageName, Step towardTarget) {
            this.packageName = packageName;
            this.towardTarget = towardTarget;
        }

        /** True if the package already appears in this chain (protects against cycles). */
        private boolean contains(String name) {
            for (Step step = this; step != null; step = step.towardTarget) {
                if (step.packageName.equals(name)) {
                    return true;
                }
            }
            return false;
        }

        /** "app -> ui -> core": this step first, the target last. */
        private String describe() {
            if (towardTarget == null) {
                return packageName + " (installed directly)";
            }
            StringBuilder text = new StringBuilder();
            for (Step step = this; step != null; step = step.towardTarget) {
                if (text.length() > 0) {
                    text.append(" -> ");
                }
                text.append(step.packageName);
            }
            return text.toString();
        }
    }

    private final PackageRegistry registry;
    private final InstalledPackageList installed;

    WhyExplainer(PackageRegistry registry, InstalledPackageList installed) {
        this.registry = registry;
        this.installed = installed;
    }

    String[] explain(String targetName) throws PackageManagerException {
        if (installed.find(targetName) == null) {
            throw new PackageManagerException("Package '" + targetName + "' is not installed.");
        }
        LinkedQueue<Step> pending = new LinkedQueue<>();
        LinkedQueue<String> chains = new LinkedQueue<>();
        pending.enqueue(new Step(targetName, null));

        while (!pending.isEmpty()) {
            Step current = pending.dequeue();
            PackageNode node = installed.find(current.packageName);
            if (node != null && node.getDirectCount() > 0) {
                chains.enqueue(current.describe());
            }
            for (PackageNode candidate = installed.first(); candidate != null; candidate = candidate.getNext()) {
                String candidateName = candidate.getName();
                if (!current.contains(candidateName) && dependsOn(candidateName, current.packageName)) {
                    pending.enqueue(new Step(candidateName, current));
                }
            }
        }
        return Queues.drainToArray(chains);
    }

    private boolean dependsOn(String packageName, String dependency) {
        String[] dependencies = registry.findDependencies(packageName);
        if (dependencies == null) {
            return false;
        }
        for (String name : dependencies) {
            if (name.equals(dependency)) {
                return true;
            }
        }
        return false;
    }
}
