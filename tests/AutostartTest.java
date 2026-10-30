// SPDX-License-Identifier: Apache-2.0
package autostart;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain-main tests for the pure-Java parts. Run with: java -ea -cp <classes> autostart.AutostartTest */
public final class AutostartTest {
    public static void main(String[] args) {
        // Argument splitting.
        List<String> split = ArgParser.split("/system/bin/am start -n 'com.example/.Main' \"a b\" c\\ d");
        check(split.size() == 6, "split size, got " + split);
        check(split.get(3).equals("com.example/.Main"), "single quotes");
        check(split.get(4).equals("a b"), "double quotes");
        check(split.get(5).equals("c d"), "escaped space");
        check(ArgParser.split("").isEmpty(), "empty split");
        boolean threw = false;
        try { ArgParser.split("unterminated 'quote"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "unterminated quote throws");

        // Shell quoting round-trips through split.
        List<String> argv = Arrays.asList("/system/bin/am", "start", "-n", "com.example/.Main", "a b", "it's");
        String joined = ArgParser.joinShell(argv);
        check(ArgParser.split(joined).equals(argv), "quote round-trip: " + joined);
        check(ArgParser.shellQuote("simple").equals("simple"), "safe arg unquoted");
        check(ArgParser.shellQuote("").equals("''"), "empty arg quoted");

        // RunState encoding: one boot id, hashes only, fits the 512-char setting limit.
        RunState empty = RunState.parse("");
        check(!empty.isCompleted("abcdef12"), "empty state");
        RunState one = empty.withCompleted(RunState.hash("/system/bin/id", ""));
        String encoded = one.encode("11111111-2222-3333-4444-555555555555");
        check(encoded != null && encoded.length() < 100, "encoded length: " + encoded);
        RunState parsed = RunState.parse(encoded);
        check(parsed.bootId().equals("11111111-2222-3333-4444-555555555555"), "boot id round-trip");
        check(parsed.isCompleted(RunState.hash("/system/bin/id", "")), "hash round-trip");
        check(!parsed.isCompleted(RunState.hash("/system/bin/id", "changed")), "edited command changes hash");
        check(RunState.parse("garbage").bootId() == null, "malformed state ignored");
        check(RunState.parse("v1|old-boot|abcdef12").bootId().equals("old-boot"), "old boot id parsed");

        // CommandSpec parsing, including number-as-double (how KS JSON numbers arrive).
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("label", "Rotation");
        row.put("enabled", Boolean.TRUE);
        row.put("executable", "/system/bin/settings");
        row.put("args", "put system user_rotation 1");
        row.put("backend", "Shizuku");
        row.put("runMode", "Once per boot");
        row.put("timeoutSeconds", Double.valueOf(10.0));
        CommandSpec spec = CommandSpec.fromRow(row);
        check(spec.argv().size() == 5, "argv size");
        check(spec.argv().get(1).equals("put"), "argv put");
        check(spec.timeoutSeconds == 10, "timeout as double");
        Map<String, Object> bad = new HashMap<String, Object>();
        bad.put("executable", "/system/bin/id");
        bad.put("backend", "Nope");
        bad.put("timeoutSeconds", Integer.valueOf(99));
        CommandSpec badSpec = CommandSpec.fromRow(bad);
        check(badSpec.backend.equals("Default"), "unknown backend becomes Default");
        check(badSpec.timeoutSeconds == 30, "timeout clamped to 30");
        check(badSpec.enabled, "enabled defaults to true when absent");

        System.out.println("AutostartTest: all checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("AutostartTest failed: " + message);
    }
}
