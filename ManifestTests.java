import java.util.Arrays;
import java.util.Random;

/** Strict JSON syntax, decoding, deep nesting and installation atomicity. */
public final class ManifestTests {
    private static int passed;

    public static void main(String[] args) throws Exception {
        syntax();
        strings();
        generatedValues();
        deepNesting();
        cliAtomicity();
        System.out.println(passed + " passed, 0 failed");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
        passed++;
    }
    private static String[] parse(String text) throws PackageManagerException {
        return Queues.drainToArray(ManifestParser.parseDependencies(text));
    }
    private static void valid(String text, String... names) throws Exception {
        equal(Arrays.asList(names), Arrays.asList(parse(text)));
    }
    private static void invalid(String text) throws Exception {
        try { parse(text); }
        catch (PackageManagerException e) {
            if (!e.getMessage().contains("position") && !e.getMessage().contains("empty"))
                throw new AssertionError("Missing error position: " + e.getMessage());
            passed++;
            return;
        }
        throw new AssertionError("Accepted invalid manifest: " + text);
    }
    private static String withValue(String value) {
        return "{\"dependencies\":[\"logger\"],\"metadata\":" + value + "}";
    }
    private static void syntax() throws Exception {
        valid("{\"dependencies\":[]}");
        valid("{\"dependencies\":{}}");
        valid(" \r\n\t{\"dependencies\":{\"logger\":\"^1.2\",\"utils\":\"*\"}} \t", "logger", "utils");
        valid("{\"metadata\":{\"dependencies\":[false]},\"dependencies\":[\"logger\",\"logger\"]}", "logger", "logger");
        // JSON permits repeated unrelated keys. The manifest rejects ambiguous dependency sections.
        valid("{\"other\":1,\"other\":2,\"dependencies\":[]}");
        for (String bad : new String[]{
                "", " ", "[]", "null", "1", "{}", "{\"metadata\":{\"dependencies\":[]}}",
                "{\"dependencies\":[]}{}", "{\"dependencies\":[]} true", "{} {\"dependencies\":[]}",
                "{\"dependencies\":[],}", "{,\"dependencies\":[]}", "{\"dependencies\" []}",
                "{dependencies:[]}", "{\"dependencies\":[] \"x\":1}", "{\"dependencies\":[], garbage}",
                "{\"oops\":,\"dependencies\":[]}", "{\"x\":1,,\"dependencies\":[]}",
                "{\"dependencies\":[\"logger\",]}", "{\"dependencies\":[,\"logger\"]}",
                "{\"dependencies\":[\"logger\" \"utils\"]}", "{\"dependencies\":[1]}",
                "{\"dependencies\":[null]}", "{\"dependencies\":[{}]}", "{\"dependencies\":[[]]}",
                "{\"dependencies\":{\"logger\":1}}", "{\"dependencies\":{\"logger\":{}}}",
                "{\"dependencies\":{\"logger\":\"1\",}}", "{\"dependencies\":true}",
                "{\"dependencies\":[],\"dependencies\":[]}",
                "{\"dependencies\":[],\"x\":[1,]}", "{\"dependencies\":[],\"x\":{\"y\":}}",
                "\ufeff{\"dependencies\":[]}", "\u000b{\"dependencies\":[]}",
                "{\"dependencies\":[]\u00a0}", "{\"dependencies\":[]//comment\n}",
                "{\"dependencies\":[]/*comment*/}"}) invalid(bad);
        for (String value : new String[]{"0", "-0", "1", "-12", "0.5", "-0.2", "1e0", "1E+02", "1e-2",
                "99999999999999999999999999999", "1e999999999", "true", "false", "null", "\"text\"",
                "[]", "{}", "[0,true,false,null,{\"x\":[1,2]}]"}) valid(withValue(value), "logger");
        for (String value : new String[]{"01", "-01", "+1", ".1", "1.", "1e", "1e+", "--1", "-", "NaN", "Infinity",
                "True", "False", "NULL", "tru", "nullx", "truefalse", "0x10", "1 2", "[1,,2]", "[1,]", "{\"x\":1,}"})
            invalid(withValue(value));
    }
    private static void strings() throws Exception {
        String slash = "\\";
        valid("{\"dependencies\":[\"log" + slash + "u0067er\"]}", "logger");
        valid("{\"" + slash + "u0064ependencies\":{\"logger\":\"" + slash + "u0031\"}}", "logger");
        String encoded = "q" + slash + "\"" + slash + slash + slash + "/" + slash + "b" + slash + "f"
                + slash + "n" + slash + "r" + slash + "t";
        valid("{\"dependencies\":[\"" + encoded + "\"]}", "q\"\\/\b\f\n\r\t");
        valid("{\"dependencies\":[\"" + slash + "uD83D" + slash + "uDE00\"]}", new String(Character.toChars(0x1f600)));
        valid("{\"dependencies\":[\"فارسی\",\"a]b{c\"]}", "فارسی", "a]b{c");
        for (String escape : new String[]{"q", "v", "x20", "u000", "uZZZZ", "u12G4", "uＦＦ00"})
            invalid("{\"dependencies\":[\"a" + slash + escape + "\"]}");
        for (int c = 0; c < 32; c++) {
            invalid("{\"dependencies\":[\"a" + (char) c + "b\"]}");
            invalid("{\"dependencies\":[],\"ignored\":\"" + (char) c + "\"}");
        }
        // Errors in values that are ignored for installation must still be rejected.
        invalid("{\"dependencies\":[],\"ignored\":\"" + slash + "q\"}");
    }
    private static String value(Random r, int depth) {
        int kind = r.nextInt(depth == 0 ? 5 : 7);
        switch (kind) {
            case 0: return "null";
            case 1: return r.nextBoolean() ? "true" : "false";
            case 2: return Integer.toString(r.nextInt());
            case 3: return "\"escaped\\n\\u0061\\\"[]{}\"";
            case 4: return "-1.25e+10";
            case 5: return "[" + value(r, depth - 1) + "," + value(r, depth - 1) + "]";
            default: return "{\"dependencies\":" + value(r, depth - 1) + ",\"other\":" + value(r, depth - 1) + "}";
        }
    }
    private static void generatedValues() throws Exception {
        Random r = new Random(20261113);
        for (int i = 0; i < 1000; i++) {
            String metadata = value(r, 5);
            String document = "{\"before\":" + metadata + ",\"dependencies\":[\"logger\",\"utils\"],\"after\":" + metadata + "}";
            valid(document, "logger", "utils");
            invalid(document + "{}");
            invalid(document.substring(0, document.length() - 1) + ",}");
        }
    }
    private static void deepNesting() throws Exception {
        StringBuilder text = new StringBuilder("{\"dependencies\":[\"logger\"],\"deep\":");
        for (int i = 0; i < 10000; i++) text.append('[');
        text.append("null");
        for (int i = 0; i < 10000; i++) text.append(']');
        text.append('}');
        valid(text.toString(), "logger");
        invalid(text.substring(0, text.length() - 2) + ",]}" );
    }
    private static void cliAtomicity() throws Exception {
        String slash = "\\";
        String output = AuditRegressionTests.cli("install utils\n"
                + "install-json {\"dependencies\":[\"logger\"], garbage}\n"
                + "list\ninstall-json {\"dependencies\":[\"log" + slash + "u0067er\"]}\nlist\nexit\n");
        equal(true, output.contains("Manifest error:"));
        int firstList = output.indexOf("Installed packages (1)");
        int secondInstall = output.indexOf("Installed 1 package(s) from the manifest");
        equal(true, firstList >= 0 && secondInstall > firstList);
        equal(true, output.contains("utils (ref_count = 1)"));
        equal(true, output.contains("utils (ref_count = 2)"));
        equal(true, output.contains("logger (ref_count = 1)"));
    }
}
