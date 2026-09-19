package forge.halcyon;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Filename helpers matching Halcyon's packaging conventions.
 */
public final class HalcyonFileNames {
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");

    private HalcyonFileNames() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            return "Unnamed";
        }
        String sanitized = INVALID_FILENAME_CHARS.matcher(value).replaceAll("").trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return sanitized.isEmpty() ? "Unnamed" : sanitized;
    }

    public static String editionFileName(String setName) {
        return sanitize(setName) + ".txt";
    }

    public static String scriptFileName(String cardName) {
        return normalizeCardName(cardName) + ".txt";
    }

    public static String normalizeCardName(String cardName) {
        String lower = sanitize(cardName).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean underscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                underscore = false;
            } else if (!underscore) {
                sb.append('_');
                underscore = true;
            }
        }
        String result = sb.toString();
        while (result.startsWith("_")) {
            result = result.substring(1);
        }
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "unnamed" : result;
    }

    public static String scriptRelativePath(String cardName) {
        String filename = scriptFileName(cardName);
        char folder = filename.isEmpty() || !Character.isLetterOrDigit(filename.charAt(0))
                ? '_'
                : filename.charAt(0);
        return folder + "/" + filename;
    }
}
