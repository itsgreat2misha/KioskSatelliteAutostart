// SPDX-License-Identifier: Apache-2.0
package autostart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root execution, outside the Shizuku API.
 *
 * <p>The plugin process itself is not root. It spawns {@code su}, which
 * runs the joined, shell-quoted argv as root. That is the whole root
 * backend: no Shizuku server, grant or binder is involved, which is why
 * it works when Shizuku is down, provided Kiosk Satellite's UID has a
 * silent su grant (for example via Magisk).
 */
public final class RootRunner {
    static final int OUTPUT_CAP = 32768;

    private RootRunner() {}

    /** Run argv as root. Never throws for a failed command; it returns it. */
    public static Result run(List<String> argv, int timeoutSeconds) {
        String shell = ArgParser.joinShell(argv);
        Process process = null;
        Thread readerThread = null;
        final StringBuilder output = new StringBuilder();
        final boolean[] truncated = new boolean[1];
        try {
            process = new ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start();
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

    /** True when su runs and reports uid 0. Used once per session, then cached by the caller. */
    public static boolean probe() {
        Result result = run(java.util.Arrays.asList("/system/bin/id"), 5);
        return result.succeeded() && result.output.contains("uid=0");
    }

    static String message(Exception e) {
        String text = e.getMessage();
        return text == null ? e.toString() : text;
    }
}
