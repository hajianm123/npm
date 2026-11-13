public final class PackageManager {

    private static final byte VISITING = 1;
    private static final byte VALIDATED = 2;

    private static final class ValidationFrame {
        private final int packageIndex;
        private final DependencyRequest request;
        private final String[] dependencies;
        private int nextDependency;

        private ValidationFrame(int packageIndex, DependencyRequest request, String[] dependencies) {
            this.packageIndex = packageIndex;
            this.request = request;
            this.dependencies = dependencies;
        }
    }

    private final PackageRegistry registry;
    private final InstalledPackageList installed = new InstalledPackageList();
    private final WhyExplainer whyExplainer;

    public PackageManager(PackageRegistry registry) {
        this.registry = registry;
        this.whyExplainer = new WhyExplainer(registry, installed);
    }

    public int install(String packageName) throws PackageManagerException {
        validateInstall(packageName);
        LinkedStack<UndoRecord> undoStack = new LinkedStack<>();
        try {
            return performInstall(packageName, undoStack);
        } catch (PackageManagerException | RuntimeException failure) {
            throw abort(failure, undoStack);
        }
    }

    /**
     * Installs several packages as ONE command (used by the manifest install): all of them or none.
     * The queue is consumed: names are installed in the order they were enqueued.
     */
    public int installAll(LinkedQueue<String> packageNames) throws PackageManagerException {
        if (packageNames == null) {
            throw new PackageManagerException("Package list must not be null. No changes were made.");
        }
        LinkedQueue<String> validated = new LinkedQueue<>();
        byte[] validationState = new byte[registry.size()];
        while (!packageNames.isEmpty()) {
            String packageName = packageNames.dequeue();
            validateInstall(packageName, validationState);
            validated.enqueue(packageName);
        }
        while (!validated.isEmpty()) {
            packageNames.enqueue(validated.dequeue());
        }

        LinkedStack<UndoRecord> undoStack = new LinkedStack<>();
        try {
            int sizeBefore = installed.size();
            while (!packageNames.isEmpty()) {
                performInstall(packageNames.dequeue(), undoStack);
            }
            return installed.size() - sizeBefore;
        } catch (PackageManagerException | RuntimeException failure) {
            throw abort(failure, undoStack);
        }
    }

    /**
     * {@code install --dry-run}: finds out what an install would do, then takes it all back.
     * It reuses the undo stack: popping gives the newest change first (that is what undo needs),
     * and pushing every popped record onto a second stack turns it back into install order.
     */
    public InstallPlan planInstall(String packageName) throws PackageManagerException {
        validateInstall(packageName);
        LinkedStack<UndoRecord> undoStack = new LinkedStack<>();
        try {
            performInstall(packageName, undoStack);
        } catch (PackageManagerException | RuntimeException failure) {
            throw abort(failure, undoStack);
        }
        LinkedStack<UndoRecord> inInstallOrder = new LinkedStack<>();
        while (!undoStack.isEmpty()) {
            UndoRecord record = undoStack.pop();
            undo(record);
            inInstallOrder.push(record);
        }
        LinkedQueue<String> added = new LinkedQueue<>();
        LinkedQueue<String> referenced = new LinkedQueue<>();
        while (!inInstallOrder.isEmpty()) {
            UndoRecord record = inInstallOrder.pop();
            if (record.getAction() == UndoRecord.Action.NODE_ADDED) {
                added.enqueue(record.getPackageName());
            } else if (record.getAction() == UndoRecord.Action.REF_INCREMENTED) {
                referenced.enqueue(record.getPackageName());
            }
        }
        return new InstallPlan(packageName, Queues.drainToArray(added), Queues.drainToArray(referenced));
    }

    /** {@code why}: every chain from a directly installed package down to {@code packageName}. */
    public String[] explainWhy(String packageName) throws PackageManagerException {
        requireValidName(packageName);
        return whyExplainer.explain(packageName);
    }

    private void validateInstall(String packageName) throws PackageManagerException {
        validateInstall(packageName, new byte[registry.size()]);
    }

    /** Iterative three-color DFS: each reachable node is expanded once per command. */
    private void validateInstall(String packageName, byte[] state) throws PackageManagerException {
        requireValidName(packageName);
        LinkedStack<ValidationFrame> pending = new LinkedStack<>();
        pushValidation(new DependencyRequest(packageName, null), state, pending);

        while (!pending.isEmpty()) {
            ValidationFrame frame = pending.pop();
            if (frame.nextDependency == frame.dependencies.length) {
                state[frame.packageIndex] = VALIDATED;
            } else {
                String dependency = frame.dependencies[frame.nextDependency++];
                // Resume the parent only after this child has finished (no recursion).
                pending.push(frame);
                pushValidation(new DependencyRequest(dependency, frame.request), state, pending);
            }
        }
    }

    private void pushValidation(DependencyRequest request, byte[] state,
                                LinkedStack<ValidationFrame> pending) throws PackageManagerException {
        String name = request.getPackageName();
        int index = registry.indexOf(name);
        if (index < 0) {
            throw new PackageManagerException("Package '" + name + "' was not found in the registry.");
        }
        if (state[index] == VISITING) {
            throw new PackageManagerException("Circular dependency detected: " + request.describeChain() + ".");
        }
        if (state[index] == VALIDATED) {
            return;
        }
        state[index] = VISITING;
        pending.push(new ValidationFrame(index, request, registry.findDependencies(name)));
    }

    private int performInstall(String packageName, LinkedStack<UndoRecord> undoStack)
            throws PackageManagerException {
        requireValidName(packageName);
        int sizeBefore = installed.size();
        LinkedQueue<DependencyRequest> pending = new LinkedQueue<>();
        pending.enqueue(new DependencyRequest(packageName, null));

        PackageNode requested = acquire(pending.dequeue(), pending, undoStack);
        requested.incrementDirectCount();
        undoStack.push(UndoRecord.directIncremented(packageName));

        while (!pending.isEmpty()) {
            acquire(pending.dequeue(), pending, undoStack);
        }
        return installed.size() - sizeBefore;
    }

    private PackageNode acquire(DependencyRequest request, LinkedQueue<DependencyRequest> pending,
                                LinkedStack<UndoRecord> undoStack) throws PackageManagerException {
        if (request.isPartOfCycle()) {
            throw new PackageManagerException("Circular dependency detected: " + request.describeChain() + ".");
        }
        String name = request.getPackageName();
        String[] dependencies = registry.findDependencies(name);
        if (dependencies == null) {
            throw new PackageManagerException("Package '" + name + "' was not found in the registry.");
        }
        PackageNode node = installed.find(name);
        if (node != null) {
            node.incrementRefCount();
            undoStack.push(UndoRecord.refIncremented(name));
            return node;
        }
        node = new PackageNode(name);
        installed.append(node);
        undoStack.push(UndoRecord.nodeAdded(name));
        for (String dependency : dependencies) {
            pending.enqueue(new DependencyRequest(dependency, request));
        }
        return node;
    }

    public int remove(String packageName) throws PackageManagerException {
        LinkedStack<UndoRecord> undoStack = new LinkedStack<>();
        try {
            return performRemove(packageName, undoStack);
        } catch (PackageManagerException | RuntimeException failure) {
            throw abort(failure, undoStack);
        }
    }

    private int performRemove(String packageName, LinkedStack<UndoRecord> undoStack)
            throws PackageManagerException {
        requireValidName(packageName);
        PackageNode target = installed.find(packageName);
        if (target == null) {
            throw new PackageManagerException("Package '" + packageName + "' is not installed.");
        }
        if (target.getDirectCount() == 0) {
            throw new PackageManagerException("Package '" + packageName + "' was not installed directly; "
                    + "it is required by other installed packages and cannot be removed on its own.");
        }
        int sizeBefore = installed.size();
        target.decrementDirectCount();
        undoStack.push(UndoRecord.directDecremented(packageName));

        LinkedQueue<String> pending = new LinkedQueue<>();
        pending.enqueue(packageName);
        while (!pending.isEmpty()) {
            release(pending.dequeue(), pending, undoStack);
        }
        return sizeBefore - installed.size();
    }

    private void release(String name, LinkedQueue<String> pending,
                         LinkedStack<UndoRecord> undoStack) throws PackageManagerException {
        PackageNode node = installed.find(name);
        if (node == null) {
            throw new PackageManagerException("Inconsistent state: '" + name
                    + "' should be installed but is missing.");
        }
        node.decrementRefCount();
        undoStack.push(UndoRecord.refDecremented(name));
        if (node.getRefCount() > 0) {
            return;
        }
        String[] dependencies = registry.findDependencies(name);
        if (dependencies == null) {
            throw new PackageManagerException("Registry has no entry for installed package '" + name + "'.");
        }
        int index = installed.indexOf(name);
        installed.remove(name);
        undoStack.push(UndoRecord.nodeRemoved(node, index));
        for (String dependency : dependencies) {
            pending.enqueue(dependency);
        }
    }

    public String[] describeInstalled() {
        String[] lines = new String[installed.size()];
        int index = 0;
        for (PackageNode node = installed.first(); node != null; node = node.getNext()) {
            lines[index++] = node.getName() + " (ref_count = " + node.getRefCount() + ")";
        }
        return lines;
    }

    String snapshot() {
        return installed.snapshot();
    }

    private PackageManagerException abort(Exception failure, LinkedStack<UndoRecord> undoStack) {
        int undone = rollback(undoStack);
        String reason = (failure instanceof PackageManagerException)
                ? failure.getMessage()
                : "Internal error (" + failure + ").";
        String outcome = (undone == 0)
                ? "No changes were made."
                : "All " + undone + " change(s) of this command were rolled back.";
        return new PackageManagerException(reason + " " + outcome);
    }

    private int rollback(LinkedStack<UndoRecord> undoStack) {
        int undone = 0;
        while (!undoStack.isEmpty()) {
            undo(undoStack.pop());
            undone++;
        }
        return undone;
    }

    private void undo(UndoRecord record) {
        switch (record.getAction()) {
            case NODE_ADDED:
                installed.remove(record.getPackageName());
                break;
            case NODE_REMOVED:
                installed.insertAt(record.getRemovedIndex(), record.getRemovedNode());
                break;
            case REF_INCREMENTED:
                requireNode(record).decrementRefCount();
                break;
            case REF_DECREMENTED:
                requireNode(record).incrementRefCount();
                break;
            case DIRECT_INCREMENTED:
                requireNode(record).decrementDirectCount();
                break;
            case DIRECT_DECREMENTED:
                requireNode(record).incrementDirectCount();
                break;
            default:
                throw new IllegalStateException("Unknown undo action: " + record.getAction());
        }
    }

    private PackageNode requireNode(UndoRecord record) {
        PackageNode node = installed.find(record.getPackageName());
        if (node == null) {
            throw new IllegalStateException("Cannot undo: '" + record.getPackageName() + "' is missing.");
        }
        return node;
    }

    private void requireValidName(String packageName) throws PackageManagerException {
        if (packageName == null || packageName.isEmpty()) {
            throw new PackageManagerException("Package name must not be empty.");
        }
    }
}
