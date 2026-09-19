package forge.halcyon;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves user-entered Halcyon repository URLs to a raw base URL ending with {@code /}.
 */
public final class HalcyonRepoUrl {
    private static final Pattern OWNER_REPO = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([^/]+)/([^/#?]+)(?:/tree/([^/#?]+))?/?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_FORM = Pattern.compile(
            "^([^/@\\s]+)/([^/@\\s]+)(?:@([^/@\\s]+))?$");

    private HalcyonRepoUrl() {
    }

    /**
     * Normalize {@code input} to a repository base URL with trailing slash.
     *
     * @throws IllegalArgumentException if the input cannot be resolved
     */
    public static String resolveBase(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Repository URL is empty.");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Repository URL is empty.");
        }

        Matcher github = OWNER_REPO.matcher(trimmed);
        if (github.matches()) {
            return rawGithub(github.group(1), stripGitSuffix(github.group(2)),
                    github.group(3) != null ? github.group(3) : "main");
        }

        Matcher shortForm = SHORT_FORM.matcher(trimmed);
        if (shortForm.matches() && !trimmed.toLowerCase(Locale.ROOT).startsWith("http")) {
            return rawGithub(shortForm.group(1), stripGitSuffix(shortForm.group(2)),
                    shortForm.group(3) != null ? shortForm.group(3) : "main");
        }

        if (trimmed.toLowerCase(Locale.ROOT).endsWith("manifest.json")) {
            int slash = trimmed.lastIndexOf('/');
            if (slash > 0) {
                trimmed = trimmed.substring(0, slash + 1);
            }
        }

        if (!trimmed.contains("://")) {
            trimmed = "https://" + trimmed;
        }

        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Invalid repository URL: " + input);
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid repository URL: " + input, ex);
        }

        return ensureTrailingSlash(trimmed);
    }

    public static String manifestUrl(String baseUrl) {
        return ensureTrailingSlash(baseUrl) + "manifest.json";
    }

    public static String resolvePackageUrl(String baseUrl, String packagePath) {
        if (packagePath == null || packagePath.isEmpty()) {
            throw new IllegalArgumentException("Package path is empty.");
        }
        String path = packagePath.trim();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return path;
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return ensureTrailingSlash(baseUrl) + path;
    }

    private static String rawGithub(String owner, String repo, String branch) {
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/";
    }

    private static String stripGitSuffix(String repo) {
        return repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
