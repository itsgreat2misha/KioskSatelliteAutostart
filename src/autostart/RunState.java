// SPDX-License-Identifier: Apache-2.0
package autostart;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The once-per-boot record, stored in the plugin's {@code runState} setting.
 *
 * <p>Format, kept deliberately short because a string setting holds at
 * most 512 characters: {@code v1|<bootId>|<hash>,<hash>,...} where each
 * hash is the first 8 hex characters of the SHA-256 of a command's
 * executable plus its arguments. Storing hashes, not indexes or labels,
 * means reordering commands does not rerun them, while editing a command
 * changes its hash and does rerun it. The boot id is stored once, not
 * once per command: with 32 commands the record is still under 330
 * characters.
 *
 * <p>KS settings are the only SDK-sanctioned persistence for a plugin:
 * the host bridge does not expose file creation. This record is therefore
 * a setting the plugin manages with {@code updateSettings}, which does
 * not re-trigger {@code configure}.
 */
public final class RunState {
    static final int MAX_LENGTH = 512;

    private final String bootId;
    private final Set<String> completed;

    public RunState(String bootId, Set<String> completed) {
        this.bootId = bootId;
        this.completed = new LinkedHashSet<String>(completed);
    }

    public static RunState parse(String raw) {
        if (raw == null || raw.length() == 0) return new RunState(null, Collections.<String>emptySet());
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            return new RunState(null, Collections.<String>emptySet());
        }
        Set<String> done = new LinkedHashSet<String>();
        if (parts[2].length() > 0) {
            String[] hashes = parts[2].split(",");
            for (int i = 0; i < hashes.length; i++) {
                if (hashes[i].length() > 0) done.add(hashes[i]);
            }
        }
        return new RunState(parts[1], done);
    }

    public String bootId() { return bootId; }

    public boolean isCompleted(String hash) { return completed.contains(hash); }

    /** A copy with one more completed hash, or this state if it was already there. */
    public RunState withCompleted(String hash) {
        Set<String> next = new LinkedHashSet<String>(completed);
        next.add(hash);
        return new RunState(bootId, next);
    }

    /** Encode for the current boot, or null when the record would not fit. */
    public String encode(String currentBootId) {
        StringBuilder out = new StringBuilder();
        out.append("v1|").append(currentBootId).append("|");
        boolean first = true;
        for (String hash : new TreeSet<String>(completed)) {
            if (!first) out.append(',');
            first = false;
            out.append(hash);
        }
        String encoded = out.toString();
        return encoded.length() <= MAX_LENGTH ? encoded : null;
    }

    /** First 8 hex characters of SHA-256 over executable, NUL, arguments. */
    public static String hash(String executable, String args) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(executable.getBytes(Charset.forName("UTF-8")));
            digest.update((byte) 0);
            if (args != null) digest.update(args.getBytes(Charset.forName("UTF-8")));
            byte[] sum = digest.digest();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int v = sum[i] & 0xff;
                if (v < 16) out.append('0');
                out.append(Integer.toHexString(v));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
