package com.google.security.zynamics.bindiff.gui.dialogs;

import com.google.common.base.Preconditions;
import com.google.security.zynamics.bindiff.config.BinDiffConfig;
import com.google.security.zynamics.bindiff.enums.ESide;
import com.google.security.zynamics.bindiff.gui.components.MessageBox;
import com.google.security.zynamics.bindiff.gui.tabpanels.TabPanel;
import com.google.security.zynamics.bindiff.gui.tabpanels.TabPanelManager;
import com.google.security.zynamics.bindiff.gui.tabpanels.projecttabpanel.WorkspaceTabPanel;
import com.google.security.zynamics.bindiff.gui.window.MainWindow;
import com.google.security.zynamics.bindiff.log.Logger;
import com.google.security.zynamics.bindiff.project.Workspace;
import com.google.security.zynamics.bindiff.project.WorkspaceLoader;
import com.google.security.zynamics.bindiff.project.diff.Diff;
import com.google.security.zynamics.bindiff.project.matches.DiffMetaData;
import com.google.security.zynamics.bindiff.resources.Constants;
import com.google.security.zynamics.bindiff.utils.CFileUtils;
import com.google.security.zynamics.zylib.gui.CFilenameFormatter;
import com.google.security.zynamics.zylib.gui.GuiHelper;
import com.google.security.zynamics.zylib.io.FileUtils;
import com.google.security.zynamics.zylib.system.SystemHelpers;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class SaveFunctionDiffViewDialog extends BaseDialog {
  private static final Color NORMAL_COLOR = new JFormattedTextField().getBackground();
  private static final Color OVERRIDE_COLOR = new Color(233, 200, 200);

  private static final int DLGWIDTH = 650;
  private static final int DLGHEIGHT = 190;

  private static final String FUNCTION_DIFF_VIEWS_DIRECTORY_NAME = "Function Diff Views";

  private final JFormattedTextField diffDatabaseFileName =
      new JFormattedTextField(
          new CFilenameFormatter(new File(SystemHelpers.getApplicationDataDirectory())));
  private final JFormattedTextField primaryExportFileName =
      new JFormattedTextField(
          new CFilenameFormatter(new File(SystemHelpers.getApplicationDataDirectory())));
  private final JFormattedTextField secondaryExportFileName =
      new JFormattedTextField(
          new CFilenameFormatter(new File(SystemHelpers.getApplicationDataDirectory())));

  private final JCheckBox diffDatabaseOverwrite = new JCheckBox("Overwrite");
  private final JCheckBox primaryExportOverwrite = new JCheckBox("Overwrite");
  private final JCheckBox secondaryExportOverwrite = new JCheckBox("Overwrite");

  private final JButton okButton = new JButton("Ok");
  private final JButton cancelButton = new JButton("Cancel");

  private final DocumentListener documentListener = new InternalDocumentListener();
  private final ActionListener checkBoxListener = new InternalCheckboxListener();
  private final ActionListener buttonListener = new InternalButtonListener();

  private boolean okPressed = false;

  private final MainWindow window;
  private final Workspace workspace;
  private final Diff diff;

  private File destinationDir = null;

  private final Map<String, String> exportFilePathToMd5 = new HashMap<>();

  public SaveFunctionDiffViewDialog(
      final MainWindow window, final String title, final Workspace workspace, final Diff diff) {
    super(Preconditions.checkNotNull(window), title);

    this.window = window;
    this.workspace = Preconditions.checkNotNull(workspace);
    this.diff = Preconditions.checkNotNull(diff);

    init();

    diffDatabaseOverwrite.setEnabled(false);
    primaryExportOverwrite.setEnabled(false);
    secondaryExportOverwrite.setEnabled(false);

    diffDatabaseFileName.getDocument().addDocumentListener(documentListener);
    primaryExportFileName.getDocument().addDocumentListener(documentListener);
    secondaryExportFileName.getDocument().addDocumentListener(documentListener);

    diffDatabaseOverwrite.addActionListener(checkBoxListener);
    primaryExportOverwrite.addActionListener(checkBoxListener);
    secondaryExportOverwrite.addActionListener(checkBoxListener);

    okButton.addActionListener(buttonListener);
    cancelButton.addActionListener(buttonListener);
  }

  private JPanel createButtonPanel() {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(new EmptyBorder(7, 5, 5, 10));

    final JPanel innerPanel = new JPanel(new GridLayout(1, 2, 5, 5));
    innerPanel.add(okButton);
    innerPanel.add(cancelButton);

    panel.add(innerPanel, BorderLayout.EAST);

    return panel;
  }

  private boolean createDestinationDirectory() {
    final DiffMetaData metaData = diff.getMetaData();

    String imagePart =
        String.format(
            "%s vs %s",
            metaData.getImageName(ESide.PRIMARY), metaData.getImageName(ESide.SECONDARY));
    final String separator = " - ";
    final String hashPart =
        String.format(
            "%s-%s", metaData.getImageHash(ESide.PRIMARY), metaData.getImageHash(ESide.SECONDARY));

    final int length = imagePart.length() + separator.length() + hashPart.length();

    if (length > 254) {
      final int delta = length - 254;
      imagePart = imagePart.substring(0, imagePart.length() - 1 - delta);
    }

    String destinationDirectoryPath = FileUtils.ensureTrailingSlash(destinationDir.getPath());
    destinationDirectoryPath += String.format("%s%s%s", imagePart, separator, hashPart);

    destinationDir = new File(destinationDirectoryPath);

    if (!destinationDir.exists()) {
      return destinationDir.mkdir();
    }

    return true;
  }

  private JPanel createFilenameLinePanel(
      final String description,
      final JFormattedTextField textField,
      final String extension,
      final JCheckBox checkbox) {
    final JPanel panel = new JPanel(new BorderLayout());

    final JPanel leftSide = new JPanel(new BorderLayout());
    final JLabel descriptionLabel = new JLabel(description);
    descriptionLabel.setPreferredSize(new Dimension(150, 25));
    descriptionLabel.setMaximumSize(new Dimension(150, 25));
    leftSide.add(descriptionLabel, BorderLayout.WEST);
    textField.setPreferredSize(new Dimension(250, 25));
    leftSide.add(textField, BorderLayout.CENTER);

    final JPanel rightSide = new JPanel(new BorderLayout());
    final JLabel extensionLabel = new JLabel(extension);
    extensionLabel.setPreferredSize(new Dimension(65, 25));
    extensionLabel.setMaximumSize(new Dimension(65, 25));
    rightSide.add(extensionLabel, BorderLayout.WEST);
    rightSide.add(checkbox, BorderLayout.CENTER);

    leftSide.add(rightSide, BorderLayout.EAST);
    panel.add(leftSide, BorderLayout.NORTH);

    return panel;
  }

  private JPanel createFilenamePanel() {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(
        BorderFactory.createCompoundBorder(new EmptyBorder(5, 5, 5, 5), new TitledBorder("")));

    final JPanel gridPanel = new JPanel(new GridLayout(3, 1, 5, 5));
    gridPanel.add(
        createFilenameLinePanel(
            "Diff Name:",
            diffDatabaseFileName,
            "." + Constants.BINDIFF_MATCHES_DB_EXTENSION,
            diffDatabaseOverwrite));
    gridPanel.add(
        createFilenameLinePanel(
            "Primary Export Name:",
            primaryExportFileName,
            "." + Constants.BINDIFF_BINEXPORT_EXTENSION,
            primaryExportOverwrite));
    gridPanel.add(
        createFilenameLinePanel(
            "Secondary Export Name:",
            secondaryExportFileName,
            "." + Constants.BINDIFF_BINEXPORT_EXTENSION,
            secondaryExportOverwrite));

    panel.add(gridPanel, BorderLayout.NORTH);

    return panel;
  }

  private boolean createSingleViewsDirectory() {
    String functionDiffViewsPath = workspace.getWorkspaceFile().getParentFile().getPath();
    functionDiffViewsPath = FileUtils.ensureTrailingSlash(functionDiffViewsPath);
    functionDiffViewsPath += FUNCTION_DIFF_VIEWS_DIRECTORY_NAME;

    destinationDir = new File(functionDiffViewsPath);
    if (!destinationDir.exists()) {
      return destinationDir.mkdir();
    }

    return true;
  }

  private void loadWorkspace() {
    if (!workspace.isLoaded()) {
      final String defaultWorkspace =
          BinDiffConfig.getInstance().getMainSettings().getDefaultWorkspace();

      boolean noOption = false;
      if (new File(defaultWorkspace).exists()) {
        final int answer =
            MessageBox.showYesNoCancelQuestion(
                getParent(),
                "A workspace has to be loaded to save a function diff view. Load the default "
                    + "workspace?");

        if (answer == JOptionPane.YES_OPTION) {
          final File workspaceFile = new File(defaultWorkspace);

          final WorkspaceLoader loader = new WorkspaceLoader(workspaceFile, workspace);
          try {
            ProgressDialog.show(
                (Window) getParent(),
                String.format("Loading Workspace '%s'", workspaceFile.getName()),
                loader);
          } catch (final Exception e) {
            Logger.logException(
                e, String.format("Load default workspace failed. '%s'", workspaceFile.getPath()));
            MessageBox.showError(
                getParent(),
                String.format(
                    "Faild to load the current default workspace.\n'%s'", workspaceFile.getPath()));
          }
        } else if (answer == JOptionPane.NO_OPTION) {
          noOption = true;
        }
      }

      if (noOption || !new File(defaultWorkspace).exists()) {
        if (!new File(defaultWorkspace).exists()) {
          final int answer =
              MessageBox.showYesNoQuestion(
                  window,
                  "A workspace has to be loaded to save a function diff view. Load workspace?");
          if (answer == JOptionPane.NO_OPTION) {
            return;
          }
        }

        try {
          final TabPanelManager tabPanelManager = window.getController().getTabPanelManager();
          final WorkspaceTabPanel workspaceTabPanel = tabPanelManager.getWorkspaceTabPanel();
          workspaceTabPanel.getController().loadWorkspace();
        } catch (final Exception e) {
          Logger.logException(e, "Load workspace failed.");
          MessageBox.showError(window, "Load workspace failed.");
        }
      }
    }
  }

  private void setDefaultNames() throws IOException {
    final String defaultDiffName =
        ((TabPanel)
                window.getController().getTabPanelManager().getTabbedPane().getSelectedComponent())
            .getTitle();
    setDefaultText(diffDatabaseFileName, defaultDiffName);

    String defaultPrimaryExportName = diff.getExportFile(ESide.PRIMARY).getName();
    defaultPrimaryExportName =
        CFileUtils.forceFilenameEndsNotWithExtension(
            defaultPrimaryExportName, Constants.BINDIFF_BINEXPORT_EXTENSION);
    setDefaultText(primaryExportFileName, defaultPrimaryExportName);

    String defaultSecondaryExportName = diff.getExportFile(ESide.SECONDARY).getName();
    defaultSecondaryExportName =
        CFileUtils.forceFilenameEndsNotWithExtension(
            defaultSecondaryExportName, Constants.BINDIFF_BINEXPORT_EXTENSION);
    setDefaultText(secondaryExportFileName, defaultSecondaryExportName);

    updateOnExistanceChanged();
  }

  private void setDefaultText(final JFormattedTextField textField, final String defaultName) {
    String lastValid = "";
    for (int i = 0; i < defaultName.length(); ++i) {
      String current = textField.getText();

      if (current.equals("")) {
        current = lastValid + defaultName.charAt(i);
      } else {
        current += defaultName.charAt(i);
      }

      textField.setText(current);
      if (!textField.getText().equals("")) {
        lastValid = current;
      }
    }
  }

  private void updateOnExistanceChanged() throws IOException {
    boolean override = getMatchesDatabaseTargetFile().exists();
    diffDatabaseFileName.setBackground(override ? OVERRIDE_COLOR : NORMAL_COLOR);
    diffDatabaseOverwrite.setEnabled(override);

    override =
        getExportBinaryTargetFile(ESide.PRIMARY).exists() && !validateExportName(ESide.PRIMARY);
    primaryExportFileName.setBackground(override ? OVERRIDE_COLOR : NORMAL_COLOR);
    primaryExportOverwrite.setEnabled(override);
    primaryExportOverwrite.setSelected(!override ? false : primaryExportOverwrite.isSelected());

    override =
        getExportBinaryTargetFile(ESide.SECONDARY).exists() && !validateExportName(ESide.SECONDARY);
    secondaryExportFileName.setBackground(override ? OVERRIDE_COLOR : NORMAL_COLOR);
    secondaryExportOverwrite.setEnabled(override);
    secondaryExportOverwrite.setSelected(!override ? false : secondaryExportOverwrite.isSelected());
  }

  private boolean validateDiffName() {
    final File matchesDbFile = getMatchesDatabaseTargetFile();

    if (matchesDbFile.exists() && !diffDatabaseOverwrite.isSelected()) {
      return false;
    }

    return true;
  }

  private boolean validateExportName(final ESide side) throws IOException {
    final File exportBinaryFile = getExportBinaryTargetFile(side);

    if (exportBinaryFile.exists()) {
      String sourceMd5 = null;
      sourceMd5 = diff.getBinExportMD5(side);

      String targetMd5 = exportFilePathToMd5.get(exportBinaryFile.getPath());
      if (targetMd5 == null) {
        targetMd5 = FileUtils.calcMD5(exportBinaryFile);
        exportFilePathToMd5.put(exportBinaryFile.getPath(), targetMd5);
      }

      if (!targetMd5.equals(sourceMd5)) {
        if (side == ESide.PRIMARY && !primaryExportOverwrite.isSelected()) {
          return false;
        } else if (side == ESide.SECONDARY && !secondaryExportOverwrite.isSelected()) {
          return false;
        }
      }
    }

    return true;
  }

  private boolean validateInput() throws IOException {
    if (!validateViewWithSameNameIsAlreadyOpen()) {
      MessageBox.showError(
          this,
          "There is already another view with the same diff name open. "
              + "Close open view first or rename diff.");
      return false;
    }

    if (!validateDiffName()) {
      MessageBox.showError(
          this,
          String.format(
              "There is alreay a BinDiff file with the name '%s' existing.\n"
                  + "Rename or select the checkbox to override.",
              getMatchesDatabaseTargetFile().getName()));

      return false;
    }

    if (!validateExportName(ESide.PRIMARY)) {
      MessageBox.showError(
          this,
          String.format(
              "There is alreay a primary BinExport file named '%s'\n"
                  + "existing with different content. Rename or select the checkbox for override.",
              getExportBinaryTargetFile(ESide.PRIMARY).getName()));

      return false;
    }

    if (!validateExportName(ESide.SECONDARY)) {
      MessageBox.showError(
          this,
          String.format(
              "There is alreay a secondary BinExport file named '%s'\n"
                  + "existing with different content. Rename or select the checkbox for override.",
              getExportBinaryTargetFile(ESide.SECONDARY).getName()));

      return false;
    }

    return true;
  }

  private boolean validateViewWithSameNameIsAlreadyOpen() {
    for (final Diff diff : workspace.getDiffList()) {
      if (diff.getMatchesDatabase().equals(getMatchesDatabaseTargetFile())) {
        if (this.diff != diff) {
          return diff.getViewManager().getFlowgraphViewsData().size() == 0;
        }
      }
    }

    return true;
  }

  private void init() {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(new EmptyBorder(5, 5, 5, 5));

    panel.add(createFilenamePanel(), BorderLayout.NORTH);
    panel.add(createButtonPanel(), BorderLayout.SOUTH);

    add(panel, BorderLayout.CENTER);

    pack();

    setPreferredSize(new Dimension(DLGWIDTH, DLGHEIGHT));
    setMinimumSize(new Dimension(DLGWIDTH, DLGHEIGHT));

    GuiHelper.centerChildToParent(getParent(), this, true);
  }

  @Override
  public void dispose() {
    diffDatabaseFileName.getDocument().removeDocumentListener(documentListener);
    primaryExportFileName.getDocument().removeDocumentListener(documentListener);
    secondaryExportFileName.getDocument().removeDocumentListener(documentListener);

    diffDatabaseOverwrite.removeActionListener(checkBoxListener);
    primaryExportOverwrite.removeActionListener(checkBoxListener);
    secondaryExportOverwrite.removeActionListener(checkBoxListener);

    okButton.removeActionListener(buttonListener);
    cancelButton.removeActionListener(buttonListener);

    super.dispose();
  }

  public File getExportBinaryTargetFile(final ESide side) {
    String exportBinaryPath = FileUtils.ensureTrailingSlash(destinationDir.getPath());
    exportBinaryPath +=
        side == ESide.PRIMARY ? primaryExportFileName.getText() : secondaryExportFileName.getText();
    exportBinaryPath =
        CFileUtils.forceFilenameEndsWithExtension(
            exportBinaryPath, Constants.BINDIFF_BINEXPORT_EXTENSION);

    return new File(exportBinaryPath);
  }

  public String getFunctionDiffName() {
    return CFileUtils.forceFilenameEndsNotWithExtension(
        getMatchesDatabaseTargetFile().getName(), Constants.BINDIFF_MATCHES_DB_EXTENSION);
  }

  public File getMatchesDatabaseTargetFile() {
    String matchesDbPath = FileUtils.ensureTrailingSlash(destinationDir.getPath());
    matchesDbPath += diffDatabaseFileName.getText();
    matchesDbPath =
        CFileUtils.forceFilenameEndsWithExtension(
            matchesDbPath, Constants.BINDIFF_MATCHES_DB_EXTENSION);

    return new File(matchesDbPath);
  }

  public boolean isOkPressed() {
    return okPressed;
  }

  public boolean isOverrideExportBinary(final ESide side) {
    if (!okPressed) {
      return false;
    }

    if (side == ESide.PRIMARY) {
      return primaryExportOverwrite.isSelected();
    }

    return secondaryExportOverwrite.isSelected();
  }

  @Override
  public void setVisible(final boolean visible) {
    loadWorkspace();

    if (!workspace.isLoaded()) {
      dispose();

      return;
    }

    if (!createSingleViewsDirectory()) {
      Logger.logSevere(
          "Save function diff view failed. Couldn't create 'Function Diffs' directory'.");
      MessageBox.showError(
          window, "Save function diff view failed. Couldn't create 'Function Diffs' directory'.");

      dispose();

      return;
    }

    if (!createDestinationDirectory()) {
      Logger.logSevere("Save function diff view failed. Couldn't create destination directory.");
      MessageBox.showError(
          window, "Save function diff view failed. Couldn't create destination directory.");

      dispose();

      return;
    }

    try {
      setDefaultNames();
    } catch (final IOException e) {
      Logger.logSevere("Save function diff view failed. Couldn't calculate source BinExport MD5.");
      MessageBox.showError(
          window, "Save function diff view failed. Couldn't calculate source BinExport MD5.");

      return;
    }

    if (visible) {
      okPressed = false;
    }

    super.setVisible(visible);
  }

  private class InternalButtonListener implements ActionListener {
    @Override
    public void actionPerformed(final ActionEvent event) {
      if (event.getSource() == okButton) {
        try {
          if (!validateInput()) {
            return;
          }

          okPressed = true;
        } catch (final IOException e) {
          Logger.logSevere(
              "Save function diff view failed. Couldn't calculate source BinExport MD5.");
          MessageBox.showError(
              getParent(),
              "Save function diff view failed. Couldn't calculate source BinExport MD5.");

          return;
        }
      }

      dispose();
    }
  }

  private class InternalCheckboxListener implements ActionListener {
    @Override
    public void actionPerformed(final ActionEvent event) {
      if ((event.getSource() == primaryExportOverwrite && primaryExportOverwrite.isSelected())
          || (event.getSource() == secondaryExportOverwrite
              && secondaryExportOverwrite.isSelected())) {
        MessageBox.showWarning(
            SaveFunctionDiffViewDialog.this,
            String.format(
                "The file '%s' already exists with different contents!\n"
                    + "If you choose override make sure that the new and the existing "
                    + "disassembly are\n"
                    + "structurally identical, e.g. only comments have been added or modified. "
                    + "Otherwise,\n"
                    + "existing older saved views may not be loadable anymore!",
                getExportBinaryTargetFile(
                        event.getSource() == primaryExportOverwrite
                            ? ESide.PRIMARY
                            : ESide.SECONDARY)
                    .getName()));
      }
    }
  }

  private class InternalDocumentListener implements DocumentListener {
    // Note: All this functions are called for each character changed, even if a string is inserted
    // by setText()

    private void update() {
      try {
        updateOnExistanceChanged();
      } catch (final IOException e1) {
        // Do nothing, because this function is called for literally
        // every single char of String when setText(String) is called,
        // e.g. in the setDefaultText() function.
      }
    }

    @Override
    public void changedUpdate(final DocumentEvent e) {
      update();
    }

    @Override
    public void insertUpdate(final DocumentEvent e) {
      update();
    }

    @Override
    public void removeUpdate(final DocumentEvent e) {
      update();
    }
  }
}
