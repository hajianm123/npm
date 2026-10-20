public final class PackageManager {

    private final PackageRegistry registry;
    private final InstalledPackageList installed = new InstalledPackageList();

    public PackageManager(PackageRegistry registry) {
        this.registry = registry;
    }

    public int install(String packageName) throws PackageManagerException {
        LinkedStack<UndoRecord> undoStack = new LinkedStack<>();
        try {
            return performInstall(packageName, undoStack);
        } catch (PackageManagerException | RuntimeException failure) {
            throw abort(failure, undoStack);
        }
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
