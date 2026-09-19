package forge.screens.home.settings;

import forge.gui.FThreads;
import forge.gui.SOverlayUtils;
import forge.gui.util.SOptionPane;
import forge.halcyon.HalcyonCatalog;
import forge.halcyon.HalcyonInstallRegistry;
import forge.halcyon.HalcyonPackageInstaller;
import forge.halcyon.HalcyonRepoPreferences;
import forge.halcyon.HalcyonRepoUrl;
import forge.localinstance.skin.FSkinProp;
import forge.toolbox.FButton;
import forge.toolbox.FComboBox;
import forge.toolbox.FLabel;
import forge.toolbox.FOverlay;
import forge.toolbox.FPanel;
import forge.toolbox.FProgressBar;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import java.awt.Font;

/**
 * Overlay dialog for browsing and installing Halcyon custom-set packages.
 */
public class DialogHalcyonPackages {
    private static final Localizer localizer = Localizer.getInstance();

    private final HalcyonInstallRegistry registry = new HalcyonInstallRegistry();
    private final FComboBox<String> cbxRepos = new FComboBox<>();
    private final JPanel setsPanel = new JPanel(new MigLayout("insets 0, gap 8, wrap, fillx"));
    private final FLabel statusLabel = new FLabel.Builder().text("").fontSize(12).build();
    private final FProgressBar progressBar = new FProgressBar();
    private FPanel mainPanel;
    private boolean busy;

    public void show() {
        buildMainPanel();
        showOverlay();
        reloadRepoCombo();
        refreshCatalog();
    }

    private void buildMainPanel() {
        mainPanel = new FPanel(new MigLayout("insets dialog, gap 6, wrap, fill"));
        mainPanel.setOpaque(false);
        mainPanel.setBackgroundTexture(FSkin.getIcon(FSkinProp.BG_TEXTURE));

        FLabel title = new FLabel.Builder()
                .text(localizer.getMessage("lblHalcyonPackagesTitle"))
                .fontSize(18)
                .fontStyle(Font.BOLD)
                .build();
        mainPanel.add(title, "growx");

        JPanel repoRow = new JPanel(new MigLayout("insets 0, gap 4, fillx"));
        repoRow.setOpaque(false);
        repoRow.add(cbxRepos, "growx, pushx");
        FButton btnAdd = new FButton(localizer.getMessage("lblHalcyonAddRepo"));
        FButton btnRemove = new FButton(localizer.getMessage("lblHalcyonRemoveRepo"));
        FButton btnRefresh = new FButton(localizer.getMessage("lblHalcyonRefresh"));
        btnAdd.addActionListener(e -> addRepo());
        btnRemove.addActionListener(e -> removeRepo());
        btnRefresh.addActionListener(e -> refreshCatalog());
        repoRow.add(btnAdd, "w 120!");
        repoRow.add(btnRemove, "w 140!");
        repoRow.add(btnRefresh, "w 100!");
        mainPanel.add(repoRow, "growx");

        setsPanel.setOpaque(false);
        FScrollPane scroll = new FScrollPane(setsPanel, true);
        mainPanel.add(scroll, "w 720!, h 420!, grow");

        progressBar.setVisible(false);
        mainPanel.add(progressBar, "growx");
        mainPanel.add(statusLabel, "growx");

        FButton btnClose = new FButton(localizer.getMessage("lblClose"));
        btnClose.addActionListener(e -> SOverlayUtils.hideOverlay());
        mainPanel.add(btnClose, "w 160!, center");

        cbxRepos.addActionListener(e -> {
            if (!busy) {
                refreshCatalog();
            }
        });
    }

    private void showOverlay() {
        JPanel overlay = FOverlay.SINGLETON_INSTANCE.getPanel();
        overlay.setLayout(new MigLayout("insets 0, gap 0, ax center, ay center"));
        overlay.add(mainPanel);
        SOverlayUtils.showOverlay();
    }

    private void reloadRepoCombo() {
        busy = true;
        String selected = cbxRepos.getSelectedItem();
        cbxRepos.removeAllItems();
        for (String url : HalcyonRepoPreferences.getRepos()) {
            cbxRepos.addItem(url);
        }
        if (selected != null) {
            cbxRepos.setSelectedItem(selected);
        }
        busy = false;
    }

    private void addRepo() {
        String input = SOptionPane.showInputDialog(
                localizer.getMessage("lblHalcyonRepoUrlPrompt"),
                localizer.getMessage("lblHalcyonAddRepo"));
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        try {
            HalcyonRepoPreferences.addRepo(input);
            reloadRepoCombo();
            cbxRepos.setSelectedItem(HalcyonRepoUrl.resolveBase(input));
            refreshCatalog();
        } catch (IllegalArgumentException ex) {
            SOptionPane.showErrorDialog(localizer.getMessage("lblHalcyonInvalidRepo", ex.getMessage()));
        }
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
        setsPanel.removeAll();
        String selected = cbxRepos.getSelectedItem();
        if (selected == null) {
            setsPanel.add(new FLabel.Builder().text(localizer.getMessage("lblHalcyonNoRepos")).build(), "growx");
            setsPanel.revalidate();
            setsPanel.repaint();
            return;
        }

        statusLabel.setText(localizer.getMessage("lblHalcyonLoading"));
        FThreads.invokeInBackgroundThread(() -> {
            try {
                HalcyonCatalog.Catalog catalog = HalcyonCatalog.fetch(selected);
                FThreads.invokeInEdtLater(() -> showCatalog(catalog));
            } catch (Exception ex) {
                FThreads.invokeInEdtLater(() -> {
                    statusLabel.setText("");
                    setsPanel.removeAll();
                    setsPanel.add(new FLabel.Builder()
                            .text(localizer.getMessage("lblHalcyonCatalogFailed", ex.getMessage()))
                            .build(), "growx");
                    setsPanel.revalidate();
                    setsPanel.repaint();
                });
            }
        });
    }

    private void showCatalog(HalcyonCatalog.Catalog catalog) {
        statusLabel.setText(catalog.getName() != null ? catalog.getName() : catalog.getRepoBaseUrl());
        setsPanel.removeAll();
        if (catalog.getSets().isEmpty()) {
            setsPanel.add(new FLabel.Builder().text(localizer.getMessage("lblHalcyonNoSets")).build(), "growx");
        } else {
            for (HalcyonCatalog.SetEntry entry : catalog.getSets()) {
                setsPanel.add(buildSetRow(entry, catalog.getRepoBaseUrl()), "growx");
            }
        }
        setsPanel.revalidate();
        setsPanel.repaint();
    }

    private JPanel buildSetRow(HalcyonCatalog.SetEntry entry, String repoBase) {
        JPanel row = new JPanel(new MigLayout("insets 8, gap 2, wrap, fillx"));
        row.setOpaque(true);
        row.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2).getColor());

        HalcyonInstallRegistry.Record installed = registry.find(entry.getId());
        HalcyonCatalog.InstallStatus status = HalcyonCatalog.statusFor(entry, installed);

        row.add(new FLabel.Builder().text(entry.getName()).fontSize(15).fontStyle(Font.BOLD).build(), "growx");
        row.add(new FLabel.Builder().text(entry.getCode()).fontSize(13).build(), "growx");
        if (entry.getDescription() != null && !entry.getDescription().isEmpty()) {
            row.add(new FLabel.Builder().text(entry.getDescription()).fontSize(12).build(), "growx");
        }
        FLabel modified = new FLabel.Builder()
                .text(localizer.getMessage("lblHalcyonLastModified", HalcyonCatalog.formatDisplayDate(entry.getLastModified())))
                .fontSize(11)
                .fontStyle(Font.ITALIC)
                .build();
        row.add(modified, "growx");

        JPanel actions = new JPanel(new MigLayout("insets 0, gap 4"));
        actions.setOpaque(false);
        if (status == HalcyonCatalog.InstallStatus.UPDATE_AVAILABLE) {
            actions.add(new FLabel.Builder()
                    .text(localizer.getMessage("lblHalcyonUpdateAvailable"))
                    .fontSize(12)
                    .fontStyle(Font.BOLD)
                    .build());
        }
        if (status == HalcyonCatalog.InstallStatus.NOT_INSTALLED) {
            FButton install = new FButton(localizer.getMessage("btnHalcyonInstall"));
            install.addActionListener(e -> runInstall(entry, repoBase));
            actions.add(install, "w 110!");
        } else {
            FButton reinstall = new FButton(localizer.getMessage("btnHalcyonReinstall"));
            FButton uninstall = new FButton(localizer.getMessage("btnHalcyonUninstall"));
            reinstall.addActionListener(e -> runInstall(entry, repoBase));
            uninstall.addActionListener(e -> runUninstall(entry));
            actions.add(reinstall, "w 110!");
            actions.add(uninstall, "w 110!");
        }
        row.add(actions, "growx");
        return row;
    }

    private void runInstall(HalcyonCatalog.SetEntry entry, String repoBase) {
        if (busy) {
            return;
        }
        busy = true;
        progressBar.setVisible(true);
        progressBar.reset();
        progressBar.setPercentMode(true);
        progressBar.setDescription(localizer.getMessage("lblHalcyonInstalling", entry.getName()));
        FThreads.invokeInBackgroundThread(() -> {
            try {
                HalcyonPackageInstaller installer = new HalcyonPackageInstaller(registry,
                        (desc, percent) -> FThreads.invokeInEdtLater(() -> {
                            progressBar.setDescription(desc);
                            progressBar.setValue(percent);
                        }));
                installer.install(entry, repoBase);
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    refreshCatalog();
                    SOptionPane.showMessageDialog(
                            localizer.getMessage("lblHalcyonRestartRequired"),
                            localizer.getMessage("lblHalcyonPackagesTitle"),
                            SOptionPane.WARNING_ICON);
                });
            } catch (Exception ex) {
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    SOptionPane.showErrorDialog(localizer.getMessage("lblHalcyonInstallFailed", ex.getMessage()));
                });
            }
        });
    }

    private void runUninstall(HalcyonCatalog.SetEntry entry) {
        if (busy) {
            return;
        }
        if (!SOptionPane.showConfirmDialog(
                localizer.getMessage("lblHalcyonConfirmUninstall", entry.getName()),
                localizer.getMessage("btnHalcyonUninstall"))) {
            return;
        }
        busy = true;
        progressBar.setVisible(true);
        progressBar.reset();
        progressBar.setDescription(localizer.getMessage("lblHalcyonUninstalling", entry.getName()));
        FThreads.invokeInBackgroundThread(() -> {
            try {
                HalcyonPackageInstaller.UninstallResult result =
                        new HalcyonPackageInstaller(registry, null).uninstall(entry.getId());
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    if (!result.isSuccess()) {
                        SOptionPane.showErrorDialog(localizer.getMessage("lblHalcyonUninstallFailed",
                                result.getError() != null ? result.getError() : "unknown"));
                        return;
                    }
                    refreshCatalog();
                    SOptionPane.showMessageDialog(
                            localizer.getMessage("lblHalcyonRestartRequired"),
                            localizer.getMessage("lblHalcyonPackagesTitle"),
                            SOptionPane.WARNING_ICON);
                });
            } catch (Exception ex) {
                FThreads.invokeInEdtLater(() -> {
                    busy = false;
                    progressBar.setVisible(false);
                    SOptionPane.showErrorDialog(localizer.getMessage("lblHalcyonUninstallFailed", ex.getMessage()));
                });
            }
        });
    }
}
