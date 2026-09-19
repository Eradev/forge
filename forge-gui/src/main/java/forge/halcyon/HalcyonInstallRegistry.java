package forge.halcyon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import forge.localinstance.properties.ForgeConstants;
import forge.util.FileUtil;
import org.tinylog.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists Halcyon package install metadata under the custom content directory.
 */
public final class HalcyonInstallRegistry {
    private static final String REGISTRY_FILE = "halcyon-installs.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File registryFile;
    private RegistryData data;

    public HalcyonInstallRegistry() {
        this(new File(ForgeConstants.USER_CUSTOM_DIR, REGISTRY_FILE));
    }

    HalcyonInstallRegistry(File registryFile) {
        this.registryFile = registryFile;
        this.data = load();
    }

    public synchronized Record find(String id) {
        for (Record record : data.installs) {
            if (record.id != null && record.id.equalsIgnoreCase(id)) {
                return record;
            }
        }
        return null;
    }

    public synchronized List<Record> all() {
        return Collections.unmodifiableList(new ArrayList<>(data.installs));
    }

    public synchronized void put(Record record) {
        List<Record> next = new ArrayList<>();
        for (Record existing : data.installs) {
            if (existing.id == null || !existing.id.equalsIgnoreCase(record.id)) {
                next.add(existing);
            }
        }
        next.add(record);
        data.installs = next;
        save();
    }

    public synchronized void remove(String id) {
        List<Record> next = new ArrayList<>();
        for (Record existing : data.installs) {
            if (existing.id == null || !existing.id.equalsIgnoreCase(id)) {
                next.add(existing);
            }
        }
        data.installs = next;
        save();
    }

    private RegistryData load() {
        if (!registryFile.exists()) {
            return new RegistryData();
        }
        try {
            String json = FileUtil.readFileToString(registryFile);
            RegistryData parsed = GSON.fromJson(json, RegistryData.class);
            if (parsed == null) {
                return new RegistryData();
            }
            if (parsed.installs == null) {
                parsed.installs = new ArrayList<>();
            }
            return parsed;
        } catch (Exception ex) {
            Logger.warn(ex, "Failed to read Halcyon install registry; starting empty");
            return new RegistryData();
        }
    }

    private void save() {
        FileUtil.ensureDirectoryExists(registryFile.getParentFile());
        FileUtil.writeFile(registryFile, GSON.toJson(data));
    }

    private static final class RegistryData {
        @SerializedName("installs")
        List<Record> installs = new ArrayList<>();
    }

    public static final class Record {
        private String id;
        private String name;
        private String code;
        private String repoBase;
        private String installedLastModified;
        private String editionPath;
        private List<String> cardScriptPaths = new ArrayList<>();
        private List<String> tokenScriptPaths = new ArrayList<>();
        private String imageDir;
        private String tokenImageDir;

        public Record() {
        }

        public Record(String id, String name, String code, String repoBase, String installedLastModified,
                      String editionPath, List<String> cardScriptPaths, List<String> tokenScriptPaths,
                      String imageDir, String tokenImageDir) {
            this.id = id;
            this.name = name;
            this.code = code;
            this.repoBase = repoBase;
            this.installedLastModified = installedLastModified;
            this.editionPath = editionPath;
            this.cardScriptPaths = cardScriptPaths != null ? new ArrayList<>(cardScriptPaths) : new ArrayList<>();
            this.tokenScriptPaths = tokenScriptPaths != null ? new ArrayList<>(tokenScriptPaths) : new ArrayList<>();
            this.imageDir = imageDir;
            this.tokenImageDir = tokenImageDir;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getCode() {
            return code;
        }

        public String getRepoBase() {
            return repoBase;
        }

        public String getInstalledLastModified() {
            return installedLastModified;
        }

        public String getEditionPath() {
            return editionPath;
        }

        public List<String> getCardScriptPaths() {
            return cardScriptPaths != null ? cardScriptPaths : Collections.emptyList();
        }

        public List<String> getTokenScriptPaths() {
            return tokenScriptPaths != null ? tokenScriptPaths : Collections.emptyList();
        }

        public String getImageDir() {
            return imageDir;
        }

        public String getTokenImageDir() {
            return tokenImageDir;
        }
    }
}
