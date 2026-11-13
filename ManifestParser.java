/**
 * Reads the dependencies of a tiny {@code package.json}-style manifest, for example:
 *
 * <pre>
 * { "name": "my-app", "dependencies": ["ui", "logger"] }
 * </pre>
 *
 * The npm style {@code "dependencies": { "ui": "1.0.0" }} is accepted too (only the names are used).
 *
 * <p>Parsing happens in two steps:
 * <ol>
 *   <li>{@link #checkBrackets}: every {@code { [} must be closed by the matching {@code } ]}, in the
 *       right order. A {@link LinkedStack} of open brackets does this: the bracket closed first is
 *       always the one opened last. Brackets inside quoted strings are ignored.</li>
 *   <li>{@link #readDependencies}: the names are read in order into a {@link LinkedQueue}, so they are
 *       installed in the order the manifest lists them.</li>
 * </ol>
 */
public final class ManifestParser {

    private static final class Bracket {
        private final char symbol;
        private final int position;

        private Bracket(char symbol, int position) {
            this.symbol = symbol;
            this.position = position;
        }
    }

    private ManifestParser() {
    }

    public static LinkedQueue<String> parseDependencies(String text) throws PackageManagerException {
        if (text == null || text.trim().isEmpty()) {
            throw new PackageManagerException("Manifest error: the manifest is empty.");
        }
        checkBrackets(text);
        String trimmed = text.trim();
        if (trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            throw new PackageManagerException("Manifest error: the manifest must be a single { ... } object.");
        }
        return readDependencies(text);
    }

    // ---- step 1: balanced brackets, checked with a stack ----

    private static void checkBrackets(String text) throws PackageManagerException {
        LinkedStack<Bracket> open = new LinkedStack<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = endOfString(text, i);
            } else if (c == '{' || c == '[') {
                open.push(new Bracket(c, i));
            } else if (c == '}' || c == ']') {
                if (open.isEmpty()) {
                    throw new PackageManagerException("Manifest error: unexpected '" + c + "' at position "
                            + (i + 1) + ", there is nothing to close.");
                }
                Bracket top = open.pop();
                if (!isPair(top.symbol, c)) {
                    throw new PackageManagerException("Manifest error: '" + top.symbol + "' opened at position "
                            + (top.position + 1) + " is closed by '" + c + "' at position " + (i + 1) + ".");
                }
            }
        }
        if (!open.isEmpty()) {
            Bracket unclosed = open.pop();
            throw new PackageManagerException("Manifest error: '" + unclosed.symbol + "' opened at position "
                    + (unclosed.position + 1) + " is never closed.");
        }
    }

    private static boolean isPair(char open, char close) {
        return (open == '{' && close == '}') || (open == '[' && close == ']');
    }

    // ---- step 2: find "dependencies" at the top level and queue its names ----

    private static LinkedQueue<String> readDependencies(String text) throws PackageManagerException {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = endOfString(text, i);
                if (depth == 1 && unescape(text.substring(i + 1, end)).equals("dependencies")) {
                    int colon = skipSpaces(text, end + 1);
                    if (charAt(text, colon) == ':') {
                        return readNames(text, skipSpaces(text, colon + 1));
                    }
                }
                i = end;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        throw new PackageManagerException("Manifest error: no \"dependencies\" key was found.");
    }

    private static LinkedQueue<String> readNames(String text, int pos) throws PackageManagerException {
        LinkedQueue<String> names = new LinkedQueue<>();
        char open = charAt(text, pos);
        if (open != '[' && open != '{') {
            throw new PackageManagerException("Manifest error: \"dependencies\" must be a list [ ... ] or an "
                    + "object { ... } (found " + describe(text, pos) + ").");
        }
        char close = (open == '[') ? ']' : '}';
        boolean isObject = (open == '{');
        pos = skipSpaces(text, pos + 1);
        if (charAt(text, pos) == close) {
            return names;
        }
        while (true) {
            pos = skipSpaces(text, pos);
            requireQuote(text, pos);
            int end = endOfString(text, pos);
            names.enqueue(unescape(text.substring(pos + 1, end)));
            pos = skipSpaces(text, end + 1);
            if (isObject) {
                if (charAt(text, pos) != ':') {
                    throw new PackageManagerException("Manifest error: expected ':' at " + describe(text, pos) + ".");
                }
                pos = skipSpaces(text, pos + 1);
                requireQuote(text, pos);
                pos = skipSpaces(text, endOfString(text, pos) + 1);
            }
            char next = charAt(text, pos);
            if (next == ',') {
                pos++;
            } else if (next == close) {
                return names;
            } else {
                throw new PackageManagerException("Manifest error: expected ',' or '" + close + "' at "
                        + describe(text, pos) + ".");
            }
        }
    }

    // ---- small helpers ----

    private static void requireQuote(String text, int pos) throws PackageManagerException {
        if (charAt(text, pos) != '"') {
            throw new PackageManagerException("Manifest error: expected a quoted package name at "
                    + describe(text, pos) + ".");
        }
    }

    /** Index of the quote that closes the string opened at {@code start}. */
    private static int endOfString(String text, int start) throws PackageManagerException {
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        throw new PackageManagerException("Manifest error: the string starting at position " + (start + 1)
                + " is never closed.");
    }

    private static int skipSpaces(String text, int pos) {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static char charAt(String text, int pos) {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private static String describe(String text, int pos) {
        return pos < text.length() ? "position " + (pos + 1) : "the end of the manifest";
    }

    private static String unescape(String raw) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                i++;
                c = raw.charAt(i);
            }
            result.append(c);
        }
        return result.toString();
    }
}
