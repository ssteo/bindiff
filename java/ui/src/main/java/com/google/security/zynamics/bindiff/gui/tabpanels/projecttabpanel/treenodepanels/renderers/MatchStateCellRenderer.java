package com.google.security.zynamics.bindiff.gui.tabpanels.projecttabpanel.treenodepanels.renderers;

import com.google.common.base.Preconditions;
import com.google.security.zynamics.bindiff.enums.EMatchState;
import com.google.security.zynamics.bindiff.gui.tabpanels.projecttabpanel.treenodepanels.tables.renderers.AbstractTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import javax.swing.JTable;
import javax.swing.SwingConstants;

public class MatchStateCellRenderer extends AbstractTableCellRenderer {
  private final Color textColor;

  private final Color matchedColor;
  private final Color primaryUnmatchedColor;
  private final Color secondaryUnmatchedColor;

  public MatchStateCellRenderer(
      final Color textColor,
      final Color matchedColor,
      final Color primaryUnmatchedColor,
      final Color secondaryUnmatchedColor) {
    this.textColor = Preconditions.checkNotNull(textColor);
    this.matchedColor = Preconditions.checkNotNull(matchedColor);
    this.primaryUnmatchedColor = Preconditions.checkNotNull(primaryUnmatchedColor);
    this.secondaryUnmatchedColor = Preconditions.checkNotNull(secondaryUnmatchedColor);
  }

  @Override
  public Component getTableCellRendererComponent(
      final JTable table,
      final Object value,
      final boolean selected,
      final boolean focused,
      final int row,
      final int column) {
    Preconditions.checkArgument(value instanceof EMatchState, "Value must be an EMatchState");

    buildAndSetToolTip(table, row);

    final EMatchState matchState = (EMatchState) value;

    final Color backgroundColor;
    if (matchState == EMatchState.PRIMARY_UNMATCHED) {
      backgroundColor = primaryUnmatchedColor;
    } else if (matchState == EMatchState.SECONDRAY_UNMATCHED) {
      backgroundColor = secondaryUnmatchedColor;
    } else {
      backgroundColor = matchedColor;
    }

    setIcon(
        new BackgroundIcon(
            "",
            SwingConstants.LEFT,
            textColor,
            backgroundColor,
            table.getSelectionBackground(),
            selected,
            0 - 1,
            0,
            table.getColumnModel().getColumn(column).getWidth() - 1,
            table.getRowHeight() - 1));

    return this;
  }
}
