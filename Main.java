import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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
        } else if (command.equalsIgnoreCase("why")) {
            handleWhy(tokens);
        } else if (command.equalsIgnoreCase("install-json")) {
            handleInstallJson(line);
        } else if (command.equalsIgnoreCase("install-file")) {
            handleInstallFile(line);
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
        boolean dryRun = false;
        String name = null;
        int extraArguments = 0;
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].equals("--dry-run")) {
                dryRun = true;
            } else if (name == null) {
                name = tokens[i];
            } else {
                extraArguments++;
            }
        }
        if (name == null || extraArguments > 0) {
            System.out.println("Usage: install [--dry-run] <package_name>");
            return;
        }
        if (dryRun) {
            handleDryRun(name);
            return;
        }
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

    private void handleDryRun(String name) {
        try {
            InstallPlan plan = manager.planInstall(name);
            String[] added = plan.getAddedPackages();
            String[] referenced = plan.getReferencedPackages();
            System.out.println("Dry run of 'install " + name + "' (nothing is changed):");
            for (String addedName : added) {
                System.out.println("  + would add " + addedName);
            }
            for (String reused : referenced) {
                System.out.println("  ~ would raise ref_count of " + reused + " by 1");
            }
            System.out.println("Summary: " + added.length + " new package(s), "
                    + referenced.length + " existing package reference(s) raised.");
            history.record("install --dry-run " + name);
        } catch (PackageManagerException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void handleWhy(String[] tokens) {
        if (!hasArgumentCount(tokens, 1, "why <package_name>")) {
            return;
        }
        String name = tokens[1];
        try {
            String[] chains = manager.explainWhy(name);
            if (chains.length == 0) {
                System.out.println("No chain leading to '" + name + "' was found.");
            } else {
                System.out.println("'" + name + "' is installed because (each chain starts at a package you installed):");
                for (String chain : chains) {
                    System.out.println("  " + chain);
                }
            }
            history.record("why " + name);
        } catch (PackageManagerException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void handleInstallJson(String line) {
        String text = restOfLine(line);
        if (text.isEmpty()) {
            System.out.println("Usage: install-json <manifest>   e.g. install-json {\"dependencies\":[\"ui\",\"logger\"]}");
            return;
        }
        installFromManifest(text, "install-json " + text);
    }

    private void handleInstallFile(String line) {
        String path = restOfLine(line);
        if (path.isEmpty()) {
            path = "package.json";
        } else if (path.charAt(0) == '"' || path.charAt(0) == '\'') {
            char quote = path.charAt(0);
            if (path.length() < 2 || path.charAt(path.length() - 1) != quote) {
                System.out.println("Error: the file path has an unclosed quote.");
                return;
            }
            path = path.substring(1, path.length() - 1);
        }
        if (path.isEmpty()) {
            System.out.println("Usage: install-file [path]   (default path: package.json)");
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.out.println("Error: cannot read '" + path + "'.");
            return;
        }
        installFromManifest(text, "install-file " + path);
    }

    private void installFromManifest(String text, String historyEntry) {
        try {
            LinkedQueue<String> dependencies = ManifestParser.parseDependencies(text);
            int requested = dependencies.size();
            if (requested == 0) {
                System.out.println("The manifest lists no dependencies: nothing to install.");
                history.record(historyEntry);
                return;
            }
            int added = manager.installAll(dependencies);
            System.out.println("Installed " + requested + " package(s) from the manifest ("
                    + added + " new package(s) added).");
            history.record(historyEntry);
        } catch (PackageManagerException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    /** Everything after the first word of the line, exactly as typed. */
    private String restOfLine(String line) {
        String trimmed = line.trim();
        int firstSpace = 0;
        while (firstSpace < trimmed.length() && !Character.isWhitespace(trimmed.charAt(firstSpace))) {
            firstSpace++;
        }
        return trimmed.substring(firstSpace).trim();
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
        System.out.println("  install --dry-run <package_name>");
        System.out.println("                          show what an install would do, without doing it");
        System.out.println("  why <package_name>      show why a package is installed");
        System.out.println("  install-json <text>     install the \"dependencies\" of a manifest, e.g.");
        System.out.println("                          install-json {\"dependencies\":[\"ui\",\"logger\"]}");
        System.out.println("  install-file [path]     same, reading the manifest from a file (default: package.json)");
        System.out.println("  remove <package_name>   remove a package you installed directly");
        System.out.println("  list                    show installed packages and their ref_count");
        System.out.println("  history                 show successful commands, newest first");
        System.out.println("  exit                    quit");
        System.out.println("Sample packages: app, ui, net, core, utils, logger, x, y, selfref, broken");
    }
}
