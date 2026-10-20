import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class Main {

    private static final String PROMPT = "mini-npm> ";

    private final PackageManager manager = new PackageManager(PackageRegistry.createDefault());
    private final CommandHistory history = new CommandHistory();

    public static void main(String[] args) throws IOException {
        new Main().run();
    }

    private void run() throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Mini-NPM - type 'help' to see the commands.");
        boolean running = true;
        while (running) {
            System.out.print(PROMPT);
            System.out.flush();
            String line = input.readLine();
            if (line == null) {
                System.out.println();
                break;
            }
            running = handle(line);
        }
        System.out.println("Bye.");
    }

    private boolean handle(String line) {
        String[] tokens = line.trim().split("\\s+");
        String command = tokens[0];
        if (command.isEmpty()) {
            return true;
        }
        if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
            return false;
        }
        if (command.equalsIgnoreCase("install")) {
            handleInstall(tokens);
        } else if (command.equalsIgnoreCase("remove")) {
            handleRemove(tokens);
        } else if (command.equalsIgnoreCase("list")) {
            handleList(tokens);
        } else if (command.equalsIgnoreCase("history")) {
            handleHistory(tokens);
        } else if (command.equalsIgnoreCase("help")) {
            printHelp();
        } else {
            System.out.println("Unknown command '" + command + "'. Type 'help' to see the commands.");
        }
        return true;
    }

    private void handleInstall(String[] tokens) {
        if (!hasArgumentCount(tokens, 1, "install <package_name>")) {
            return;
        }
        String name = tokens[1];
        try {
            int added = manager.install(name);
            if (added == 0) {
                System.out.println("'" + name + "' was already installed: ref_count increased.");
            } else {
                System.out.println("Installed '" + name + "' (" + added + " new package(s) added).");
            }
            history.record("install " + name);
        } catch (PackageManagerException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void handleRemove(String[] tokens) {
        if (!hasArgumentCount(tokens, 1, "remove <package_name>")) {
            return;
        }
        String name = tokens[1];
        try {
            int deleted = manager.remove(name);
            if (deleted == 0) {
                System.out.println("'" + name + "' is still in use: ref_count decreased, nothing deleted.");
            } else {
                System.out.println("Removed '" + name + "' (" + deleted + " package(s) deleted).");
            }
            history.record("remove " + name);
        } catch (PackageManagerException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void handleList(String[] tokens) {
        if (!hasArgumentCount(tokens, 0, "list")) {
            return;
        }
        String[] lines = manager.describeInstalled();
        if (lines.length == 0) {
            System.out.println("No packages are installed.");
        } else {
            System.out.println("Installed packages (" + lines.length + "):");
            for (String line : lines) {
                System.out.println("  - " + line);
            }
        }
        history.record("list");
    }

    private void handleHistory(String[] tokens) {
        if (!hasArgumentCount(tokens, 0, "history")) {
            return;
        }
        String[] commands = history.newestFirst();
        if (commands.length == 0) {
            System.out.println("History is empty.");
            return;
        }
        System.out.println("Successful commands (newest first):");
        for (int i = 0; i < commands.length; i++) {
            System.out.println("  " + (i + 1) + ". " + commands[i]);
        }
    }

    private boolean hasArgumentCount(String[] tokens, int expected, String usage) {
        if (tokens.length - 1 == expected) {
            return true;
        }
        System.out.println("Usage: " + usage);
        return false;
    }

    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  install <package_name>  install a package with its dependencies");
        System.out.println("  remove <package_name>   remove a package you installed directly");
        System.out.println("  list                    show installed packages and their ref_count");
        System.out.println("  history                 show successful commands, newest first");
        System.out.println("  exit                    quit");
        System.out.println("Sample packages: app, ui, net, core, utils, logger, x, y, selfref, broken");
    }
}
