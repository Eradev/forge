package forge.halcyon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.card.CardEdition;
import forge.localinstance.properties.ForgeConstants;
import forge.util.FileSection;
import forge.util.FileUtil;
import org.tinylog.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads and installs/uninstalls Halcyon {@code .forgepkg.zip} packages.
 */
public final class HalcyonPackageInstaller {
    public interface ProgressCallback {
        void onProgress(String description, int percent);
    }

    private final HalcyonInstallRegistry registry;
    private final ProgressCallback progress;

    public HalcyonPackageInstaller() {
        this(new HalcyonInstallRegistry(), null);
    }

    public HalcyonPackageInstaller(HalcyonInstallRegistry registry, ProgressCallback progress) {
        this.registry = registry;
        this.progress = progress;
    }

    public InstallResult install(HalcyonCatalog.SetEntry entry, String repoBaseUrl) throws IOException {
        String base = HalcyonRepoUrl.resolveBase(repoBaseUrl);
        String packageUrl = HalcyonRepoUrl.resolvePackageUrl(base, entry.getPackagePath());
        report("Downloading " + entry.getName(), 0);

        File tempZip = File.createTempFile("halcyon-", ".forgepkg.zip");
        try {
            downloadTo(packageUrl, tempZip);
            report("Installing " + entry.getName(), 50);
            InstallResult result = installFromZip(tempZip, entry, base);
            report("Installed " + entry.getName(), 100);
            return result;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
        }
    }

    public InstallResult installFromZip(File packageFile, HalcyonCatalog.SetEntry catalogEntry, String repoBaseUrl)
            throws IOException {
        try (ZipFile zip = new ZipFile(packageFile)) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            ZipEntry editionEntry = zip.getEntry("edition.txt");
            if (manifestEntry == null || editionEntry == null) {
                throw new IOException("Package must contain manifest.json and edition.txt.");
            }

            JsonObject packageManifest = JsonParser.parseString(readEntry(zip, manifestEntry)).getAsJsonObject();
            String editionText = readEntry(zip, editionEntry);
            Map<String, List<String>> sections = FileSection.parseSections(toLines(editionText));
            List<String> metadataLines = sections.get("metadata");
            FileSection metadata = FileSection.parse(
                    metadataLines != null ? metadataLines : List.of(), FileSection.EQUALS_KV_SEPARATOR);

            String setName = firstNonBlank(metadata.get("Name"), str(packageManifest, "name"), catalogEntry.getName());
            String setCode = firstNonBlank(metadata.get("Code"), str(packageManifest, "code"), catalogEntry.getCode());

            File editionFile = new File(ForgeConstants.USER_CUSTOM_EDITIONS_DIR, HalcyonFileNames.editionFileName(setName));
            FileUtil.ensureDirectoryExists(ForgeConstants.USER_CUSTOM_EDITIONS_DIR);

            List<String> cardScriptPaths = new ArrayList<>();
            List<String> tokenScriptPaths = new ArrayList<>();
            int cards = 0;
            int images = 0;
            int tokens = 0;
            int tokenImages = 0;

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeZipPath(entry.getName());
                if (name.startsWith("cards/") && name.endsWith(".txt")) {
                    String scriptText = readEntry(zip, entry);
                    String cardName = extractCardName(scriptText);
                    if (cardName == null) {
                        Logger.warn("Skipped package script without Name field: {}", name);
                        continue;
                    }
                    File target = scriptTarget(ForgeConstants.USER_CUSTOM_CARDS_DIR, cardName);
                    FileUtil.ensureDirectoryExists(target.getParentFile());
                    writeText(target, scriptText);
                    cardScriptPaths.add(target.getAbsolutePath());
                    cards++;
                } else if (name.startsWith("tokens/") && name.endsWith(".txt")) {
                    String fileName = new File(name).getName();
                    File target = new File(ForgeConstants.USER_CUSTOM_TOKENS_DIR, fileName);
                    FileUtil.ensureDirectoryExists(ForgeConstants.USER_CUSTOM_TOKENS_DIR);
                    copyEntry(zip, entry, target);
                    tokenScriptPaths.add(target.getAbsolutePath());
                    tokens++;
                }
            }

            File imageDir = new File(ForgeConstants.CACHE_CARD_PICS_DIR, HalcyonFileNames.sanitize(setCode));
            FileUtil.ensureDirectoryExists(imageDir);
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeZipPath(entry.getName());
                if (name.startsWith("images/") && !name.endsWith("/")) {
                    File target = new File(imageDir, new File(name).getName());
                    copyEntry(zip, entry, target);
                    images++;
                }
            }

            File tokenImageDir = new File(ForgeConstants.CACHE_TOKEN_PICS_DIR, HalcyonFileNames.sanitize(setCode));
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeZipPath(entry.getName());
                if (name.startsWith("token_images/") && !name.endsWith("/")) {
                    FileUtil.ensureDirectoryExists(tokenImageDir);
                    File target = new File(tokenImageDir, new File(name).getName());
                    copyEntry(zip, entry, target);
                    tokenImages++;
                }
            }

            writeText(editionFile, editionText);

            HalcyonInstallRegistry.Record record = new HalcyonInstallRegistry.Record(
                    catalogEntry.getId(),
                    setName,
                    setCode,
                    HalcyonRepoUrl.resolveBase(repoBaseUrl),
                    catalogEntry.getLastModified(),
                    editionFile.getAbsolutePath(),
                    cardScriptPaths,
                    tokenScriptPaths,
                    imageDir.getAbsolutePath(),
                    tokenImageDir.exists() ? tokenImageDir.getAbsolutePath() : null
            );
            registry.put(record);

            return new InstallResult(setName, setCode, cards, images, tokens, tokenImages);
        }
    }

    public UninstallResult uninstall(String setId) {
        HalcyonInstallRegistry.Record record = registry.find(setId);
        if (record == null) {
            return new UninstallResult(false, "Set is not installed.", 0, 0);
        }

        // Remove edition first so remaining editions no longer list this set's cards.
        deleteQuietly(record.getEditionPath());

        Set<String> referencedCardNames = collectReferencedCardNames(record.getId());
        int deletedScripts = 0;
        int retainedScripts = 0;
        for (String path : record.getCardScriptPaths()) {
            File script = new File(path);
            if (!script.exists()) {
                continue;
            }
            String cardName = extractCardName(FileUtil.readFileToString(script));
            if (cardName != null && referencedCardNames.contains(normalizeNameKey(cardName))) {
                retainedScripts++;
                Logger.debug("Retained shared Halcyon card script: {}", path);
            } else {
                deleteQuietly(path);
                deletedScripts++;
            }
        }

        // Token scripts are typically set-scoped identifiers; remove those we installed.
        for (String path : record.getTokenScriptPaths()) {
            deleteQuietly(path);
        }

        deleteDirectoryQuietly(record.getImageDir());
        deleteDirectoryQuietly(record.getTokenImageDir());

        registry.remove(setId);
        return new UninstallResult(true, null, deletedScripts, retainedScripts);
    }

    private Set<String> collectReferencedCardNames(String excludeInstallId) {
        Set<String> names = new HashSet<>();

        File editionsDir = new File(ForgeConstants.USER_CUSTOM_EDITIONS_DIR);
        File[] editionFiles = editionsDir.isDirectory() ? editionsDir.listFiles((dir, name) -> name.endsWith(".txt")) : null;
        if (editionFiles != null) {
            for (File editionFile : editionFiles) {
                names.addAll(cardNamesFromEditionFile(editionFile));
            }
        }

        for (HalcyonInstallRegistry.Record other : registry.all()) {
            if (other.getId() != null && other.getId().equalsIgnoreCase(excludeInstallId)) {
                continue;
            }
            for (String path : other.getCardScriptPaths()) {
                File script = new File(path);
                if (!script.exists()) {
                    continue;
                }
                String cardName = extractCardName(FileUtil.readFileToString(script));
                if (cardName != null) {
                    names.add(normalizeNameKey(cardName));
                }
            }
        }
        return names;
    }

    private static Set<String> cardNamesFromEditionFile(File editionFile) {
        Set<String> names = new HashSet<>();
        try {
            Map<String, List<String>> sections = FileSection.parseSections(FileUtil.readFile(editionFile));
            for (Map.Entry<String, List<String>> section : sections.entrySet()) {
                String sectionName = section.getKey();
                if ("metadata".equalsIgnoreCase(sectionName) || "tokens".equalsIgnoreCase(sectionName)
                        || "boosters".equalsIgnoreCase(sectionName) || sectionName.toLowerCase(Locale.ROOT).endsWith("types")) {
                    continue;
                }
                for (String line : section.getValue()) {
                    Matcher matcher = CardEdition.Reader.CARD_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String cardName = matcher.group(5);
                        if (cardName != null) {
                            names.add(normalizeNameKey(cardName.trim()));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.warn(ex, "Failed to parse custom edition for Halcyon uninstall: {}", editionFile);
        }
        return names;
    }

    private void downloadTo(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = HalcyonCatalog.open(urlStr);
        try {
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " downloading " + urlStr);
            }
            long contentLength = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest.toPath()))) {
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (contentLength > 0) {
                        report("Downloading", (int) (100 * total / contentLength));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void report(String description, int percent) {
        if (progress != null) {
            progress.onProgress(description, Math.max(0, Math.min(100, percent)));
        }
    }

    private static File scriptTarget(String cardsDir, String cardName) {
        return new File(cardsDir, HalcyonFileNames.scriptRelativePath(cardName).replace('/', File.separatorChar));
    }

    static String extractCardName(String scriptText) {
        if (scriptText == null) {
            return null;
        }
        for (String line : scriptText.split("\\R")) {
            if (line.startsWith("Name:")) {
                String name = line.substring("Name:".length()).trim();
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    private static String normalizeZipPath(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        if (n.contains("..")) {
            throw new IllegalArgumentException("Unsafe zip entry path: " + name);
        }
        return n;
    }

    private static String normalizeNameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "CUSTOM";
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(readFully(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File target, String text) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, File target) throws IOException {
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()))) {
            copy(in, out);
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        copy(in, buffer);
        return buffer.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
    }

    private static List<String> toLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String line : text.split("\\R", -1)) {
            lines.add(line);
        }
        return lines;
    }

    private static void deleteQuietly(String path) {
        if (path == null) {
            return;
        }
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            Logger.warn("Could not delete {}", path);
        }
    }

    private static void deleteDirectoryQuietly(String path) {
        if (path == null) {
            return;
        }
        File dir = new File(path);
        if (dir.isDirectory()) {
            FileUtil.deleteDirectory(dir);
        }
    }

    public static final class InstallResult {
        private final String setName;
        private final String setCode;
        private final int cards;
        private final int images;
        private final int tokens;
        private final int tokenImages;

        public InstallResult(String setName, String setCode, int cards, int images, int tokens, int tokenImages) {
            this.setName = setName;
            this.setCode = setCode;
            this.cards = cards;
            this.images = images;
            this.tokens = tokens;
            this.tokenImages = tokenImages;
        }

        public String getSetName() {
            return setName;
        }

        public String getSetCode() {
            return setCode;
        }

        public int getCards() {
            return cards;
        }

        public int getImages() {
            return images;
        }

        public int getTokens() {
            return tokens;
        }

        public int getTokenImages() {
            return tokenImages;
        }
    }

    public static final class UninstallResult {
        private final boolean success;
        private final String error;
        private final int deletedScripts;
        private final int retainedScripts;

        public UninstallResult(boolean success, String error, int deletedScripts, int retainedScripts) {
            this.success = success;
            this.error = error;
            this.deletedScripts = deletedScripts;
            this.retainedScripts = retainedScripts;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }

        public int getDeletedScripts() {
            return deletedScripts;
        }

        public int getRetainedScripts() {
            return retainedScripts;
        }
    }
}
