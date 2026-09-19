package forge.screens.settings;

import com.badlogic.gdx.utils.Align;
import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gui.FThreads;
import forge.halcyon.HalcyonCatalog;
import forge.halcyon.HalcyonInstallRegistry;
import forge.halcyon.HalcyonPackageInstaller;
import forge.halcyon.HalcyonRepoPreferences;
import forge.halcyon.HalcyonRepoUrl;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FComboBox;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.toolbox.FProgressBar;
import forge.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic mobile screen for browsing and installing Halcyon custom-set packages.
 */
public class HalcyonPackagesScreen extends FScreen {
    private static final float PADDING = Utils.scale(5);
    private static final float FIELD_HEIGHT = Math.round(Utils.AVG_FINGER_HEIGHT * 0.75f);
    private static final float BTN_HEIGHT = Math.round(Utils.AVG_FINGER_HEIGHT * 0.85f);

    private final HalcyonInstallRegistry registry = new HalcyonInstallRegistry();
    private final FComboBox<String> cbxRepos;
    private final FButton btnAdd;
    private final FButton btnRemove;
    private final FButton btnRefresh;
    private final FLabel statusLabel;
    private final FProgressBar progressBar;
    private final FList<SetRow> setList;

    private String currentRepoBase;
    private boolean busy;
    private boolean suppressRepoChange;

    public HalcyonPackagesScreen() {
        super(Forge.getLocalizer().getMessage("lblHalcyonPackagesTitle"));

        cbxRepos = add(new FComboBox<>());
        cbxRepos.setChangedHandler(event -> {
            if (!suppressRepoChange && !busy) {
                refreshCatalog();
            }
        });

        btnAdd = add(new FButton(Forge.getLocalizer().getMessage("lblHalcyonAddRepo")));
        btnAdd.setCommand(event -> addRepo());
        btnRemove = add(new FButton(Forge.getLocalizer().getMessage("lblHalcyonRemoveRepo")));
        btnRemove.setCommand(event -> removeRepo());
        btnRefresh = add(new FButton(Forge.getLocalizer().getMessage("lblHalcyonRefresh")));
        btnRefresh.setCommand(event -> refreshCatalog());

        statusLabel = add(new FLabel.Builder().text("").font(FSkinFont.get(12)).build());
        progressBar = add(new FProgressBar());
        progressBar.setVisible(false);

        setList = add(new FList<>());
        setList.setListItemRenderer(new SetRowRenderer());

        reloadRepoCombo();
        refreshCatalog();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float x = PADDING;
        float y = startY + PADDING;
        float w = width - 2 * PADDING;

        cbxRepos.setBounds(x, y, w, FIELD_HEIGHT);
        y += FIELD_HEIGHT + PADDING;

        float btnW = (w - 2 * PADDING) / 3f;
        btnAdd.setBounds(x, y, btnW, BTN_HEIGHT);
        btnRemove.setBounds(x + btnW + PADDING, y, btnW, BTN_HEIGHT);
        btnRefresh.setBounds(x + 2 * (btnW + PADDING), y, btnW, BTN_HEIGHT);
        y += BTN_HEIGHT + PADDING;

        statusLabel.setBounds(x, y, w, FIELD_HEIGHT);
        y += FIELD_HEIGHT + PADDING;

        if (progressBar.isVisible()) {
            progressBar.setBounds(x, y, w, FIELD_HEIGHT * 0.75f);
            y += FIELD_HEIGHT * 0.75f + PADDING;
        }

        setList.setBounds(x, y, w, height - y - PADDING);
    }

    private void reloadRepoCombo() {
        suppressRepoChange = true;
        String selected = cbxRepos.getSelectedItem();
        List<String> repos = new ArrayList<>(HalcyonRepoPreferences.getRepos());
        cbxRepos.setItems(repos, selected != null && repos.contains(selected)
                ? selected
                : (repos.isEmpty() ? null : repos.get(0)));
        suppressRepoChange = false;
    }

    private void addRepo() {
        FOptionPane.showInputDialog(
                Forge.getLocalizer().getMessage("lblHalcyonRepoUrlPrompt"),
                Forge.getLocalizer().getMessage("lblHalcyonAddRepo"),
                "",
                null,
                input -> {
                    if (input == null || input.trim().isEmpty()) {
                        return;
                    }
                    try {
                        HalcyonRepoPreferences.addRepo(input);
                        String resolved = HalcyonRepoUrl.resolveBase(input);
                        reloadRepoCombo();
                        suppressRepoChange = true;
                        cbxRepos.setSelectedItem(resolved);
                        suppressRepoChange = false;
                        refreshCatalog();
                    } catch (IllegalArgumentException ex) {
                        FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblHalcyonInvalidRepo", ex.getMessage()));
                    }
                },
                false);
    }

    private void removeRepo() {
        String selected = cbxRepos.getSelectedItem();
        if (selected == null) {
            return;
        }
        HalcyonRepoPreferences.removeRepo(selected);
        reloadRepoCombo();
        refreshCatalog();
    }

    private void refreshCatalog() {
        setList.clear();
        String selected = cbxRepos.getSelectedItem();
        currentRepoBase = selected;
        if (selected == null) {
            statusLabel.setText(Forge.getLocalizer().getMessage("lblHalcyonNoRepos"));
            return;
        }
        statusLabel.setText(Forge.getLocalizer().getMessage("lblHalcyonLoading"));
        FThreads.invokeInBackgroundThread(() -> {
            try {
                HalcyonCatalog.Catalog catalog = HalcyonCatalog.fetch(selected);
                FThreads.invokeInEdtLater(() -> showCatalog(catalog));
            } catch (Exception ex) {
                FThreads.invokeInEdtLater(() -> {
                    statusLabel.setText(Forge.getLocalizer().getMessage("lblHalcyonCatalogFailed", ex.getMessage()));
                    setList.clear();
                });
            }
        });
    }

    private void showCatalog(HalcyonCatalog.Catalog catalog) {
        currentRepoBase = catalog.getRepoBaseUrl();
        statusLabel.setText(catalog.getName() != null ? catalog.getName() : catalog.getRepoBaseUrl());
        setList.clear();
        if (catalog.getSets().isEmpty()) {
            statusLabel.setText(Forge.getLocalizer().getMessage("lblHalcyonNoSets"));
            return;
        }
        List<SetRow> rows = new ArrayList<>();
        for (HalcyonCatalog.SetEntry entry : catalog.getSets()) {
            HalcyonInstallRegistry.Record installed = registry.find(entry.getId());
            rows.add(new SetRow(entry, HalcyonCatalog.statusFor(entry, installed)));
        }
        setList.setListData(rows);
    }

    private void onSetSelected(SetRow row) {
        if (busy || currentRepoBase == null) {
            return;
        }
        HalcyonCatalog.SetEntry entry = row.entry;
        if (row.status == HalcyonCatalog.InstallStatus.NOT_INSTALLED) {
            runInstall(entry);
            return;
        }
        List<String> options = new ArrayList<>();
        options.add(Forge.getLocalizer().getMessage("btnHalcyonReinstall"));
        options.add(Forge.getLocalizer().getMessage("btnHalcyonUninstall"));
        options.add(Forge.getLocalizer().getMessage("lblCancel"));
        String title = entry.getName();
        if (row.status == HalcyonCatalog.InstallStatus.UPDATE_AVAILABLE) {
            title = entry.getName() + " — " + Forge.getLocalizer().getMessage("lblHalcyonUpdateAvailable");
        }
        FOptionPane.showOptionDialog("", title, FOptionPane.QUESTION_ICON, options, 0, result -> {
            if (result != null && result == 0) {
                runInstall(entry);
            } else if (result != null && result == 1) {
                confirmUninstall(entry);
            }
        });
    }

    private void confirmUninstall(HalcyonCatalog.SetEntry entry) {
        FOptionPane.showConfirmDialog(
                Forge.getLocalizer().getMessage("lblHalcyonConfirmUninstall", entry.getName()),
                Forge.getLocalizer().getMessage("btnHalcyonUninstall"),
                ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        runUninstall(entry);
                    }
                });
    }

    private void runInstall(HalcyonCatalog.SetEntry entry) {
        if (busy) {
            return;
        }
        busy = true;
        progressBar.setVisible(true);
        progressBar.setDescription(Forge.getLocalizer().getMessage("lblHalcyonInstalling", entry.getName()));
        revalidate();
        FThreads.invokeInBackgroundThread(() -> {
            try {
                HalcyonPackageInstaller installer = new HalcyonPackageInstaller(registry,
                        (desc, percent) -> FThreads.invokeInEdtLater(() -> {
                            progressBar.setDescription(desc);
                            progressBar.setValue(percent);
                        }));
                installer.install(entry, currentRepoBase);
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    revalidate();
                    refreshCatalog();
                    FOptionPane.showMessageDialog(
                            Forge.getLocalizer().getMessage("lblHalcyonRestartRequired"),
                            Forge.getLocalizer().getMessage("lblHalcyonPackagesTitle"),
                            FOptionPane.WARNING_ICON);
                });
            } catch (Exception ex) {
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    revalidate();
                    FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblHalcyonInstallFailed", ex.getMessage()));
                });
            }
        });
    }

    private void runUninstall(HalcyonCatalog.SetEntry entry) {
        if (busy) {
            return;
        }
        busy = true;
        progressBar.setVisible(true);
        progressBar.setDescription(Forge.getLocalizer().getMessage("lblHalcyonUninstalling", entry.getName()));
        revalidate();
        FThreads.invokeInBackgroundThread(() -> {
            try {
                HalcyonPackageInstaller.UninstallResult result =
                        new HalcyonPackageInstaller(registry, null).uninstall(entry.getId());
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    revalidate();
                    if (!result.isSuccess()) {
                        FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblHalcyonUninstallFailed",
                                result.getError() != null ? result.getError() : "unknown"));
                        return;
                    }
                    refreshCatalog();
                    FOptionPane.showMessageDialog(
                            Forge.getLocalizer().getMessage("lblHalcyonRestartRequired"),
                            Forge.getLocalizer().getMessage("lblHalcyonPackagesTitle"),
                            FOptionPane.WARNING_ICON);
                });
            } catch (Exception ex) {
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    revalidate();
                    FOptionPane.showErrorDialog(Forge.getLocalizer().getMessage("lblHalcyonUninstallFailed", ex.getMessage()));
                });
            }
        });
    }

    private static final class SetRow {
        private final HalcyonCatalog.SetEntry entry;
        private final HalcyonCatalog.InstallStatus status;

        private SetRow(HalcyonCatalog.SetEntry entry, HalcyonCatalog.InstallStatus status) {
            this.entry = entry;
            this.status = status;
        }
    }

    private class SetRowRenderer extends FList.ListItemRenderer<SetRow> {
        @Override
        public float getItemHeight() {
            return Utils.AVG_FINGER_HEIGHT * 2.4f;
        }

        @Override
        public boolean tap(Integer index, SetRow value, float x, float y, int count) {
            onSetSelected(value);
            return true;
        }

        @Override
        public void drawValue(Graphics g, Integer index, SetRow value, FSkinFont font, FSkinColor foreColor,
                              FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
            float pad = Utils.scale(4);
            float cy = y + pad;
            FSkinFont header = FSkinFont.get(14);
            FSkinFont body = FSkinFont.get(12);
            FSkinFont small = FSkinFont.get(11);

            g.drawText(value.entry.getName(), header, foreColor, x, cy, w, header.getLineHeight(), false, Align.left, false);
            cy += header.getLineHeight() + pad * 0.5f;
            g.drawText(value.entry.getCode(), body, foreColor, x, cy, w, body.getLineHeight(), false, Align.left, false);
            cy += body.getLineHeight() + pad * 0.5f;
            String desc = value.entry.getDescription();
            if (desc != null && !desc.isEmpty()) {
                g.drawText(desc, body, foreColor, x, cy, w, body.getLineHeight(), false, Align.left, false);
                cy += body.getLineHeight() + pad * 0.5f;
            }
            String modified = Forge.getLocalizer().getMessage("lblHalcyonLastModified",
                    HalcyonCatalog.formatDisplayDate(value.entry.getLastModified()));
            if (value.status == HalcyonCatalog.InstallStatus.UPDATE_AVAILABLE) {
                modified += "  •  " + Forge.getLocalizer().getMessage("lblHalcyonUpdateAvailable");
            }
            g.drawText(modified, small, foreColor.alphaColor(0.85f), x, cy, w, small.getLineHeight(), false, Align.left, false);
        }
    }
}
