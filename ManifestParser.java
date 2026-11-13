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
 *   <li>An iterative JSON grammar parser checks every property and the end of the input.
 *       Only top-level dependency names are collected in a {@link LinkedQueue}.</li>
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
        return new Parser(text).parse();
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

    // Complete JSON grammar, represented as frames on the project's linked stack.
    private enum Kind { OBJECT, ARRAY, END_OBJECT, END_ARRAY, COLON, COMMA, STRING, NUMBER, LITERAL, END }
    private enum State { KEY_OR_END, KEY, COLON, VALUE, VALUE_OR_END, COMMA_OR_END }
    private enum Mode { NORMAL, DEPENDENCIES }

    private static final class Token {
        final Kind kind;
        final String value;
        final int position;
        Token(Kind kind, String value, int position) {
            this.kind = kind; this.value = value; this.position = position;
        }
    }

    private static final class Frame {
        final boolean object, root;
        final Mode mode;
        State state;
        boolean dependencyValue;
        Frame(boolean object, boolean root, Mode mode) {
            this.object = object; this.root = root; this.mode = mode;
            state = object ? State.KEY_OR_END : State.VALUE_OR_END;
        }
    }

    private static final class Parser {
        final String text;
        final LinkedQueue<String> names = new LinkedQueue<>();
        final LinkedStack<Frame> frames = new LinkedStack<>();
        int cursor;
        boolean foundDependencies;

        Parser(String text) { this.text = text; }

        LinkedQueue<String> parse() throws PackageManagerException {
            Token first = next();
            if (first.kind != Kind.OBJECT) throw error("the manifest must be a single { ... } object", first.position);
            frames.push(new Frame(true, true, Mode.NORMAL));
            while (!frames.isEmpty()) {
                Frame frame = frames.pop();
                Token token = next();
                switch (frame.state) {
                    case KEY_OR_END:
                        if (token.kind == Kind.END_OBJECT) break;
                        readKey(frame, token);
                        break;
                    case KEY:
                        readKey(frame, token);
                        break;
                    case COLON:
                        require(token, Kind.COLON, "expected ':'");
                        frame.state = State.VALUE;
                        frames.push(frame);
                        break;
                    case VALUE_OR_END:
                        if (token.kind == Kind.END_ARRAY) break;
                        readValue(frame, token);
                        break;
                    case VALUE:
                        readValue(frame, token);
                        break;
                    case COMMA_OR_END:
                        Kind end = frame.object ? Kind.END_OBJECT : Kind.END_ARRAY;
                        if (token.kind == end) break;
                        require(token, Kind.COMMA, "expected ',' or '" + (frame.object ? '}' : ']') + "'");
                        frame.state = frame.object ? State.KEY : State.VALUE;
                        frames.push(frame);
                        break;
                    default: throw new IllegalStateException("Unknown JSON parser state");
                }
            }
            Token trailing = next();
            if (trailing.kind != Kind.END) throw error("the manifest must be a single { ... } object; unexpected trailing content", trailing.position);
            if (!foundDependencies) throw error("no \"dependencies\" key was found", cursor);
            return names;
        }

        void readKey(Frame frame, Token token) throws PackageManagerException {
            require(token, Kind.STRING, "expected a quoted object key");
            frame.dependencyValue = frame.root && token.value.equals("dependencies");
            if (frame.dependencyValue) {
                if (foundDependencies) throw error("duplicate top-level \"dependencies\" key", token.position);
                foundDependencies = true;
            }
            if (frame.mode == Mode.DEPENDENCIES) names.enqueue(token.value);
            frame.state = State.COLON;
            frames.push(frame);
        }

        void readValue(Frame frame, Token token) throws PackageManagerException {
            if (frame.dependencyValue && token.kind != Kind.OBJECT && token.kind != Kind.ARRAY)
                throw error("\"dependencies\" must be a list [ ... ] or an object { ... }", token.position);
            if (frame.mode == Mode.DEPENDENCIES) {
                require(token, Kind.STRING, frame.object ? "expected a quoted version string" : "expected a quoted package name");
                if (!frame.object) names.enqueue(token.value);
            } else if (token.kind != Kind.OBJECT && token.kind != Kind.ARRAY && token.kind != Kind.STRING
                    && token.kind != Kind.NUMBER && token.kind != Kind.LITERAL) {
                throw error("expected a JSON value", token.position);
            }
            frame.state = State.COMMA_OR_END;
            frames.push(frame);
            if (token.kind == Kind.OBJECT || token.kind == Kind.ARRAY)
                frames.push(new Frame(token.kind == Kind.OBJECT, false,
                        frame.dependencyValue ? Mode.DEPENDENCIES : Mode.NORMAL));
        }

        Token next() throws PackageManagerException {
            while (cursor < text.length() && isSpace(text.charAt(cursor))) cursor++;
            int start = cursor;
            if (cursor == text.length()) return new Token(Kind.END, null, start);
            char c = text.charAt(cursor++);
            switch (c) {
                case '{': return new Token(Kind.OBJECT, null, start);
                case '[': return new Token(Kind.ARRAY, null, start);
                case '}': return new Token(Kind.END_OBJECT, null, start);
                case ']': return new Token(Kind.END_ARRAY, null, start);
                case ':': return new Token(Kind.COLON, null, start);
                case ',': return new Token(Kind.COMMA, null, start);
                case '"': return new Token(Kind.STRING, string(), start);
                case 't': literal("rue"); return new Token(Kind.LITERAL, null, start);
                case 'f': literal("alse"); return new Token(Kind.LITERAL, null, start);
                case 'n': literal("ull"); return new Token(Kind.LITERAL, null, start);
                default:
                    if (c == '-' || isDigit(c)) {
                        cursor = start; number(); return new Token(Kind.NUMBER, null, start);
                    }
                    throw error("unexpected character '" + c + "'", start);
            }
        }

        String string() throws PackageManagerException {
            StringBuilder value = new StringBuilder();
            while (cursor < text.length()) {
                char c = text.charAt(cursor++);
                if (c == '"') return value.toString();
                if (c < 0x20) throw error("unescaped control character in string", cursor - 1);
                if (c != '\\') { value.append(c); continue; }
                if (cursor == text.length()) throw error("incomplete string escape", cursor);
                char escape = text.charAt(cursor++);
                switch (escape) {
                    case '"': case '\\': case '/': value.append(escape); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            if (cursor == text.length()) throw error("incomplete Unicode escape", cursor);
                            int hex = hex(text.charAt(cursor++));
                            if (hex < 0) throw error("invalid Unicode escape", cursor - 1);
                            code = code * 16 + hex;
                        }
                        value.append((char) code);
                        break;
                    default: throw error("invalid string escape", cursor - 1);
                }
            }
            throw error("unterminated string", cursor);
        }

        void literal(String suffix) throws PackageManagerException {
            if (!text.startsWith(suffix, cursor)) throw error("invalid JSON literal", cursor - 1);
            cursor += suffix.length();
        }

        void number() throws PackageManagerException {
            if (at('-')) cursor++;
            if (at('0')) cursor++;
            else {
                if (cursor == text.length() || text.charAt(cursor) < '1' || text.charAt(cursor) > '9')
                    throw error("expected a digit in number", cursor);
                digits();
            }
            if (at('.')) { cursor++; requiredDigits(); }
            if (at('e') || at('E')) {
                cursor++;
                if (at('+') || at('-')) cursor++;
                requiredDigits();
            }
        }

        void requiredDigits() throws PackageManagerException {
            if (cursor == text.length() || !isDigit(text.charAt(cursor))) throw error("expected a digit in number", cursor);
            digits();
        }
        void digits() { while (cursor < text.length() && isDigit(text.charAt(cursor))) cursor++; }
        boolean at(char c) { return cursor < text.length() && text.charAt(cursor) == c; }
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }
    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    private static void require(Token token, Kind kind, String message) throws PackageManagerException {
        if (token.kind != kind) throw error(message, token.position);
    }
    private static PackageManagerException error(String message, int position) {
        return new PackageManagerException("Manifest error: " + message + " at position " + (position + 1) + ".");
    }

    private static int endOfString(String text, int start) throws PackageManagerException {
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        throw new PackageManagerException("Manifest error: the string starting at position " + (start + 1)
                + " is never closed.");
    }
}
