public final class CommandHistory {

    private final LinkedStack<String> commands = new LinkedStack<>();

    public void record(String command) {
        commands.push(command);
    }

    public String[] newestFirst() {
        String[] result = new String[commands.size()];
        LinkedStack<String> buffer = new LinkedStack<>();
        int index = 0;
        while (!commands.isEmpty()) {
            String command = commands.pop();
            result[index++] = command;
            buffer.push(command);
        }
        while (!buffer.isEmpty()) {
            commands.push(buffer.pop());
        }
        return result;
    }
}
