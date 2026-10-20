public final class InstalledPackageList {

    private PackageNode head;
    private PackageNode tail;
    private int size;

    public PackageNode first() {
        return head;
    }

    public int size() {
        return size;
    }

    public PackageNode find(String name) {
        for (PackageNode current = head; current != null; current = current.getNext()) {
            if (current.getName().equals(name)) {
                return current;
            }
        }
        return null;
    }

    public int indexOf(String name) {
        int index = 0;
        for (PackageNode current = head; current != null; current = current.getNext()) {
            if (current.getName().equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    public void append(PackageNode node) {
        node.setNext(null);
        if (head == null) {
            head = node;
        } else {
            tail.setNext(node);
        }
        tail = node;
        size++;
    }

    public void insertAt(int index, PackageNode node) {
        if (index < 0 || index > size) {
            throw new IllegalArgumentException("Invalid insert position: " + index);
        }
        if (index == size) {
            append(node);
            return;
        }
        if (index == 0) {
            node.setNext(head);
            head = node;
        } else {
            PackageNode previous = nodeAt(index - 1);
            node.setNext(previous.getNext());
            previous.setNext(node);
        }
        size++;
    }

    public PackageNode remove(String name) {
        PackageNode previous = null;
        PackageNode current = head;
        while (current != null && !current.getName().equals(name)) {
            previous = current;
            current = current.getNext();
        }
        if (current == null) {
            return null;
        }
        if (previous == null) {
            head = current.getNext();
        } else {
            previous.setNext(current.getNext());
        }
        if (current == tail) {
            tail = previous;
        }
        current.setNext(null);
        size--;
        return current;
    }

    String snapshot() {
        StringBuilder text = new StringBuilder();
        for (PackageNode current = head; current != null; current = current.getNext()) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(current.getName())
                .append("[ref=").append(current.getRefCount())
                .append(",direct=").append(current.getDirectCount())
                .append(']');
        }
        return text.toString();
    }

    private PackageNode nodeAt(int index) {
        PackageNode current = head;
        for (int step = 0; step < index; step++) {
            current = current.getNext();
        }
        return current;
    }
}
