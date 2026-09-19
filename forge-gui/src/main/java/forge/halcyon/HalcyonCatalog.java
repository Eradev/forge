package forge.halcyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches and parses a Halcyon repository catalog {@code manifest.json}.
 */
public final class HalcyonCatalog {
    private static final int TIMEOUT_MS = 30_000;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private HalcyonCatalog() {
    }

    public static Catalog fetch(String repoBaseUrl) throws IOException {
        String base = HalcyonRepoUrl.resolveBase(repoBaseUrl);
        String url = HalcyonRepoUrl.manifestUrl(base);
        JsonObject root = fetchJson(url);
        if (root == null) {
            throw new IOException("Catalog not found: " + url);
        }
        return parse(base, root);
    }

    static Catalog parse(String repoBaseUrl, JsonObject root) {
        String repoName = str(root, "name");
        List<SetEntry> sets = new ArrayList<>();
        JsonArray arr = root.has("sets") && root.get("sets").isJsonArray()
                ? root.getAsJsonArray("sets")
                : new JsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                Logger.warn("Skipping non-object set entry in Halcyon catalog");
                continue;
            }
            SetEntry entry = parseSet(el.getAsJsonObject());
            if (entry != null) {
                sets.add(entry);
            }
        }
        return new Catalog(repoBaseUrl, repoName, Collections.unmodifiableList(sets));
    }

    private static SetEntry parseSet(JsonObject obj) {
        String id = str(obj, "id");
        String name = str(obj, "name");
        String packagePath = str(obj, "package");
        String lastModified = str(obj, "lastModified");
        if (isBlank(id) || isBlank(name) || isBlank(packagePath) || isBlank(lastModified)) {
            Logger.warn("Skipping Halcyon catalog set missing required fields: {}", obj);
            return null;
        }
        String code = str(obj, "code");
        if (isBlank(code)) {
            code = id;
        }
        String description = str(obj, "description");
        if (description == null) {
            description = "";
        }
        return new SetEntry(id, code, name, description, lastModified, packagePath);
    }

    static JsonObject fetchJson(String urlStr) throws IOException {
        HttpURLConnection conn = open(urlStr);
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + status + " for " + urlStr);
        }
        try (InputStream is = conn.getInputStream();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }

    static HttpURLConnection open(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json, application/octet-stream, */*");
        conn.setRequestProperty("User-Agent", forge.util.BuildInfo.getUserAgent());
        conn.connect();
        return conn;
    }

    public static String formatDisplayDate(String lastModified) {
        Instant instant = parseInstant(lastModified);
        if (instant != null) {
            return LocalDate.ofInstant(instant, java.time.ZoneOffset.UTC).format(DISPLAY_DATE);
        }
        try {
            return LocalDate.parse(lastModified.substring(0, Math.min(10, lastModified.length()))).format(DISPLAY_DATE);
        } catch (Exception ex) {
            return lastModified;
        }
    }

    public static Instant parseInstant(String lastModified) {
        if (isBlank(lastModified)) {
            return null;
        }
        try {
            return Instant.parse(lastModified);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(lastModified).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(lastModified.substring(0, Math.min(10, lastModified.length())))
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** @return negative if catalog is older, 0 if equal/unknown, positive if catalog is newer */
    public static int compareLastModified(String catalogValue, String installedValue) {
        Instant catalog = parseInstant(catalogValue);
        Instant installed = parseInstant(installedValue);
        if (catalog == null || installed == null) {
            return catalogValue.compareTo(installedValue);
        }
        return catalog.compareTo(installed);
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static final class Catalog {
        private final String repoBaseUrl;
        private final String name;
        private final List<SetEntry> sets;

        public Catalog(String repoBaseUrl, String name, List<SetEntry> sets) {
            this.repoBaseUrl = repoBaseUrl;
            this.name = name;
            this.sets = sets;
        }

        public String getRepoBaseUrl() {
            return repoBaseUrl;
        }

        public String getName() {
            return name;
        }

        public List<SetEntry> getSets() {
            return sets;
        }
    }

    public static final class SetEntry {
        private final String id;
        private final String code;
        private final String name;
        private final String description;
        private final String lastModified;
        private final String packagePath;

        public SetEntry(String id, String code, String name, String description,
                        String lastModified, String packagePath) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.description = description;
            this.lastModified = lastModified;
            this.packagePath = packagePath;
        }

        public String getId() {
            return id;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getLastModified() {
            return lastModified;
        }

        public String getPackagePath() {
            return packagePath;
        }
    }

    public enum InstallStatus {
        NOT_INSTALLED,
        INSTALLED,
        UPDATE_AVAILABLE
    }

    public static InstallStatus statusFor(SetEntry entry, HalcyonInstallRegistry.Record installed) {
        if (installed == null) {
            return InstallStatus.NOT_INSTALLED;
        }
        if (compareLastModified(entry.getLastModified(), installed.getInstalledLastModified()) > 0) {
            return InstallStatus.UPDATE_AVAILABLE;
        }
        return InstallStatus.INSTALLED;
    }
}
