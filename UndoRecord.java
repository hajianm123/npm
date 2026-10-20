public final class UndoRecord {

    public enum Action {
        NODE_ADDED,
        NODE_REMOVED,
        REF_INCREMENTED,
        REF_DECREMENTED,
        DIRECT_INCREMENTED,
        DIRECT_DECREMENTED
    }

    private final Action action;
    private final String packageName;
    private final PackageNode removedNode;
    private final int removedIndex;

    private UndoRecord(Action action, String packageName, PackageNode removedNode, int removedIndex) {
        this.action = action;
        this.packageName = packageName;
        this.removedNode = removedNode;
        this.removedIndex = removedIndex;
    }

    public static UndoRecord nodeAdded(String packageName) {
        return new UndoRecord(Action.NODE_ADDED, packageName, null, -1);
    }

    public static UndoRecord nodeRemoved(PackageNode node, int index) {
        return new UndoRecord(Action.NODE_REMOVED, node.getName(), node, index);
    }

    public static UndoRecord refIncremented(String packageName) {
        return new UndoRecord(Action.REF_INCREMENTED, packageName, null, -1);
    }

    public static UndoRecord refDecremented(String packageName) {
        return new UndoRecord(Action.REF_DECREMENTED, packageName, null, -1);
    }

    public static UndoRecord directIncremented(String packageName) {
        return new UndoRecord(Action.DIRECT_INCREMENTED, packageName, null, -1);
    }

    public static UndoRecord directDecremented(String packageName) {
        return new UndoRecord(Action.DIRECT_DECREMENTED, packageName, null, -1);
    }

    public Action getAction() {
        return action;
    }

    public String getPackageName() {
        return packageName;
    }

    public PackageNode getRemovedNode() {
        return removedNode;
    }

    public int getRemovedIndex() {
        return removedIndex;
    }
}
