public final class PackageNode {

    private final String name;
    private int refCount;
    private int directCount;
    private PackageNode next;

    public PackageNode(String name) {
        this.name = name;
        this.refCount = 1;
    }

    public String getName() {
        return name;
    }

    public int getRefCount() {
        return refCount;
    }

    public void incrementRefCount() {
        refCount++;
    }

    public void decrementRefCount() {
        refCount--;
    }

    public int getDirectCount() {
        return directCount;
    }

    public void incrementDirectCount() {
        directCount++;
    }

    public void decrementDirectCount() {
        directCount--;
    }

    public PackageNode getNext() {
        return next;
    }

    public void setNext(PackageNode next) {
        this.next = next;
    }
}
