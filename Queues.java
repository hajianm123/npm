/**
 * Small helper for turning a queue of names into an array.
 * The queue is emptied: that is how a queue is read.
 */
final class Queues {

    private Queues() {
    }

    static String[] drainToArray(LinkedQueue<String> queue) {
        String[] result = new String[queue.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = queue.dequeue();
        }
        return result;
    }
}
