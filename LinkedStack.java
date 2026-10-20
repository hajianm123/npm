public final class LinkedStack<T> {

    private static final class Node<T> {
        private final T value;
        private final Node<T> below;

        private Node(T value, Node<T> below) {
            this.value = value;
            this.below = below;
        }
    }

    private Node<T> top;
    private int size;

    public void push(T value) {
        top = new Node<>(value, top);
        size++;
    }

    public T pop() {
        if (top == null) {
            throw new IllegalStateException("Cannot pop from an empty stack.");
        }
        T value = top.value;
        top = top.below;
        size--;
        return value;
    }

    public boolean isEmpty() {
        return top == null;
    }

    public int size() {
        return size;
    }
}
