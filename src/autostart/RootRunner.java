// SPDX-License-Identifier: Apache-2.0
package autostart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root execution, outside the Shizuku API.
 *
 * <p>The plugin process itself is not root. It spawns {@code su}, which
 * runs the argv as root. That is the whole root backend: no Shizuku
 * server, grant or binder is involved, which is why it works when
 * Shizuku is down, provided Kiosk Satellite's UID has a silent su grant
 * (for example via Magisk).
 *
 * <p>There is no single su syntax. Two are in common use, and this
 * class tries them in order, once, and remembers which one worked:
 * <ul>
 * <li>{@code su 0 <argv...>} — direct argv, no shell. This is the
 * syntax on the Tuya F5-3 panel: {@code su -c ...} fails there with
 * exit 1, which is why v1.0's probe wrongly reported root as
 * unavailable on that panel even though su works.
 * <li>{@code su -c <shell string>} — the Magisk-style form, with the
 * argv shell-quoted into one string.
 * </ul>
 * The probe runs {@code /system/bin/id} (no side effects) through
 * each form and caches the first form that reports uid 0. Commands
 * then run exactly once, through the cached form, so a command is
 * never executed twice because the first su form was wrong.
 */
public final class RootRunner {
    static final int OUTPUT_CAP = 32768;

    private static final String FORM_SU0 = "su0";
    private static final String FORM_SHELL = "suShell";
    private static final String FORM_NONE = "none";

    /** Cached su form for this session: FORM_SU0, FORM_SHELL, FORM_NONE, or null (unknown). */
    private static volatile String rootForm = null;

    private RootRunner() {}

    /** The direct-argv su command for a given argv, for tests. */
    static List<String> su0Command(List<String> argv) {
        List<String> out = new ArrayList<String>();
        out.add("su");
        out.add("0");
        out.addAll(argv);
        return out;
    }

    /** The shell-form su command for a given argv, for tests. */
    static List<String> suShellCommand(List<String> argv) {
        List<String> out = new ArrayList<String>();
        out.add("su");
        out.add("-c");
        out.add(ArgParser.joinShell(argv));
        return out;
    }

    /** Human label for the cached form, for probe messages. */
    static String formLabel() {
        if (FORM_SU0.equals(rootForm)) return "su 0";
        if (FORM_SHELL.equals(rootForm)) return "su -c";
        return "unknown";
    }

    /** Run argv as root. Never throws for a failed command; it returns it. */
    public static Result run(List<String> argv, int timeoutSeconds) {
        if (rootForm == null) probe();
        if (FORM_NONE.equals(rootForm)) {
            return Result.failure("Root is unavailable: su did not report uid 0 with either su 0 or su -c");
        }
        List<String> command = FORM_SU0.equals(rootForm) ? su0Command(argv) : suShellCommand(argv);
        return runProcess(command, timeoutSeconds);
    }

    /** True when su runs and reports uid 0, through either form. Caches the working form. */
    public static synchronized boolean probe() {
        if (rootForm != null) return !FORM_NONE.equals(rootForm);
        Result su0 = runProcess(su0Command(java.util.Arrays.asList("/system/bin/id")), 5);
        if (su0.succeeded() && su0.output.contains("uid=0")) {
            rootForm = FORM_SU0;
            return true;
        }
        Result shell = runProcess(suShellCommand(java.util.Arrays.asList("/system/bin/id")), 5);
        if (shell.succeeded() && shell.output.contains("uid=0")) {
            rootForm = FORM_SHELL;
            return true;
        }
        rootForm = FORM_NONE;
        return false;
    }

    private static Result runProcess(List<String> command, int timeoutSeconds) {
        Process process = null;
        Thread readerThread = null;
        final StringBuilder output = new StringBuilder();
        final boolean[] truncated = new boolean[1];
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream in = process.getInputStream();
            readerThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        char[] buffer = new char[4096];
                        int read;
                        while ((read = reader.read(buffer)) != -1) {
                            if (output.length() < OUTPUT_CAP) {
                                int take = Math.min(read, OUTPUT_CAP - output.length());
                                output.append(buffer, 0, take);
                                if (take < read) truncated[0] = true;
                            } else {
                                truncated[0] = true;
                            }
                        }
                    } catch (IOException ignored) {}
                }
            }, "autostart-root-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return new Result(-1, true, truncated[0], output.toString(), null);
            }
            try { readerThread.join(2000); } catch (InterruptedException ignored) {}
            return new Result(process.exitValue(), false, truncated[0], output.toString(), null);
        } catch (IOException e) {
            return Result.failure("Root is unavailable: " + message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return Result.failure("Root command was interrupted");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static String message(Exception e) {
        String text = e.getMessage();
        return text == null ? e.toString() : text;
    }
}
