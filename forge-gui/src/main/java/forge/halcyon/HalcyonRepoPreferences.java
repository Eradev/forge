package forge.halcyon;

import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists the user's Halcyon repository URL list in Forge preferences.
 */
public final class HalcyonRepoPreferences {
    private HalcyonRepoPreferences() {
    }

    public static List<String> getRepos() {
        ForgePreferences prefs = FModel.getPreferences();
        String raw = prefs.getPref(FPref.HALCYON_REPO_URLS);
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> urls = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }

    public static void setRepos(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        if (urls != null) {
            for (String url : urls) {
                if (url == null) {
                    continue;
                }
                String trimmed = url.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(trimmed);
            }
        }
        ForgePreferences prefs = FModel.getPreferences();
        prefs.setPref(FPref.HALCYON_REPO_URLS, sb.toString());
        prefs.save();
    }

    public static void addRepo(String url) {
        String resolved = HalcyonRepoUrl.resolveBase(url);
        List<String> urls = new ArrayList<>(getRepos());
        for (String existing : urls) {
            if (HalcyonRepoUrl.resolveBase(existing).equalsIgnoreCase(resolved)) {
                return;
            }
        }
        urls.add(resolved);
        setRepos(urls);
    }

    public static void removeRepo(String url) {
        String target;
        try {
            target = HalcyonRepoUrl.resolveBase(url);
        } catch (IllegalArgumentException ex) {
            target = url.trim();
        }
        List<String> urls = new ArrayList<>();
        for (String existing : getRepos()) {
            try {
                if (!HalcyonRepoUrl.resolveBase(existing).equalsIgnoreCase(target)) {
                    urls.add(existing);
                }
            } catch (IllegalArgumentException ex) {
                if (!existing.equalsIgnoreCase(target)) {
                    urls.add(existing);
                }
            }
        }
        setRepos(urls);
    }
}
