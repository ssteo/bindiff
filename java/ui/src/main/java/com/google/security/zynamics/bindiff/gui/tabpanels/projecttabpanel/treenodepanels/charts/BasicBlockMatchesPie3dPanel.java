package com.google.security.zynamics.bindiff.gui.tabpanels.projecttabpanel.treenodepanels.charts;

import com.google.common.base.Preconditions;
import com.google.security.zynamics.bindiff.enums.ESide;
import com.google.security.zynamics.bindiff.project.diff.Diff;
import com.google.security.zynamics.bindiff.project.diff.DiffChangeAdapter;
import com.google.security.zynamics.bindiff.project.matches.MatchData;
import com.google.security.zynamics.bindiff.resources.Colors;
import java.awt.BorderLayout;
import java.text.AttributedString;
import javax.swing.JPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.PieSectionLabelGenerator;
import org.jfree.chart.labels.PieToolTipGenerator;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.general.PieDataset;

public class BasicBlockMatchesPie3dPanel extends JPanel {
  private static final int MATCHED_BASICBLOCKS = 0;
  private static final int PRIMRAY_UNMATCHED_BASICBLOCKS = 1;
  private static final int SECONDARY_UNMATCHED_BASICBLOCKS = 2;

  private static final String[] PLOTS = {
    "Matched Basicblocks", "Primary unmatched Basicblocks", "Secondary unmatched Basicblocks"
  };

  private int matchedCount = 0;
  private int primaryUnmatchedCount = 0;
  private int secondaryUnmatchedCount = 0;

  private double matchedPercent = 0.;
  private double primaryUnmatchedPercent = 0.;
  private double secondaryUnmatchedPercent = 0.;

  private final boolean includeUnmtachedFunctions;

  private final Diff diff;

  private final Pie3dPanel piePanel;

  private final DefaultPieDataset dataset = new DefaultPieDataset();

  private final InternalFlowgraphCachedCountsListener changeListener =
      new InternalFlowgraphCachedCountsListener();

  public BasicBlockMatchesPie3dPanel(final Diff diff, final boolean includeUnmatchedFunctions) {
    super(new BorderLayout());
    Preconditions.checkNotNull(diff);

    includeUnmtachedFunctions = includeUnmatchedFunctions;

    this.diff = diff;

    piePanel = new Pie3dPanel(getTitle(), dataset, new CustomLabelGenerator());

    piePanel.setSectionColor(PLOTS[MATCHED_BASICBLOCKS], Colors.PIE_MATCHED);
    piePanel.setSectionColor(PLOTS[PRIMRAY_UNMATCHED_BASICBLOCKS], Colors.PIE_PRIMARY_UNMATCHED);
    piePanel.setSectionColor(
        PLOTS[SECONDARY_UNMATCHED_BASICBLOCKS], Colors.PIE_SECONDARY_UNMATCHED);

    piePanel.setTooltipGenerator(new CustomTooltipGenerator());

    add(piePanel, BorderLayout.CENTER);

    if (includeUnmtachedFunctions) {
      diff.getMetaData().addListener(changeListener);

      updateDataset();
    }
  }

  private String getTitle() {
    if (Double.isNaN(matchedPercent)) {
      return "Basic Blocks";
    }

    return String.format("%s %.1f%s", "Basic Blocks", matchedPercent, "%");
  }

  private void updateDataset() {
    final MatchData matches = diff.getMatches();

    matchedCount = matches.getSizeOfMatchedBasicblocks();
    primaryUnmatchedCount = matches.getSizeOfUnmatchedBasicblocks(ESide.PRIMARY);
    secondaryUnmatchedCount = matches.getSizeOfUnmatchedBasicblocks(ESide.SECONDARY);

    final int total = matchedCount + primaryUnmatchedCount + secondaryUnmatchedCount;

    matchedPercent = (double) matchedCount / (double) total * 100.;
    primaryUnmatchedPercent = (double) primaryUnmatchedCount / (double) total * 100.;
    secondaryUnmatchedPercent = (double) secondaryUnmatchedCount / (double) total * 100.;

    dataset.setValue(PLOTS[MATCHED_BASICBLOCKS], matchedPercent);
    dataset.setValue(PLOTS[PRIMRAY_UNMATCHED_BASICBLOCKS], primaryUnmatchedPercent);
    dataset.setValue(PLOTS[SECONDARY_UNMATCHED_BASICBLOCKS], secondaryUnmatchedPercent);

    piePanel.setTitle(getTitle());

    piePanel.fireChartChanged();
  }

  public void dispose() {
    if (includeUnmtachedFunctions) {
      diff.getMetaData().removeListener(changeListener);
    }
  }

  public JFreeChart getChart() {
    return piePanel.getChart();
  }

  public void updateDataset(
      final int matchedBasicblocks,
      final int primaryUnmatchedBasiocblocks,
      final int secondaryUnmatchedBasicblock) {
    if (!includeUnmtachedFunctions) {
      matchedCount = matchedBasicblocks;
      primaryUnmatchedCount = primaryUnmatchedBasiocblocks;
      secondaryUnmatchedCount = secondaryUnmatchedBasicblock;

      final int total = matchedCount + primaryUnmatchedCount + secondaryUnmatchedCount;

      matchedPercent = (double) matchedCount / (double) total * 100.;
      primaryUnmatchedPercent = (double) primaryUnmatchedCount / (double) total * 100.;
      secondaryUnmatchedPercent = (double) secondaryUnmatchedCount / (double) total * 100.;

      dataset.setValue(PLOTS[MATCHED_BASICBLOCKS], matchedPercent);
      dataset.setValue(PLOTS[PRIMRAY_UNMATCHED_BASICBLOCKS], primaryUnmatchedPercent);
      dataset.setValue(PLOTS[SECONDARY_UNMATCHED_BASICBLOCKS], secondaryUnmatchedPercent);

      piePanel.setTitle(getTitle());

      piePanel.fireChartChanged();
    }
  }

  private class CustomLabelGenerator implements PieSectionLabelGenerator {
    @Override
    @SuppressWarnings("rawtypes")
    public AttributedString generateAttributedSectionLabel(
        final PieDataset arg0, final Comparable arg1) {
      return null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public String generateSectionLabel(final PieDataset dataset, final Comparable key) {
      String result = null;
      if (dataset != null) {
        if (key.equals(PLOTS[MATCHED_BASICBLOCKS])) {
          result = String.format("%d\n%.1f%s", matchedCount, matchedPercent, "%");
        } else if (key.equals(PLOTS[PRIMRAY_UNMATCHED_BASICBLOCKS])) {
          result = String.format("%d\n%.1f%s", primaryUnmatchedCount, primaryUnmatchedPercent, "%");
        } else if (key.equals(PLOTS[SECONDARY_UNMATCHED_BASICBLOCKS])) {
          result =
              String.format("%d\n%.1f%s", secondaryUnmatchedCount, secondaryUnmatchedPercent, "%");
        }
      }
      return result;
    }
  }

  private class CustomTooltipGenerator implements PieToolTipGenerator {
    @Override
    @SuppressWarnings("rawtypes")
    public String generateToolTip(final PieDataset dataset, final Comparable key) {
      if (dataset != null) {
        if (key.equals(PLOTS[MATCHED_BASICBLOCKS])) {
          return String.format(
              "%s %d (%.1f%s)", PLOTS[MATCHED_BASICBLOCKS], matchedCount, matchedPercent, "%");
        }
        if (key.equals(PLOTS[PRIMRAY_UNMATCHED_BASICBLOCKS])) {
          return String.format(
              "%s %d (%.1f%s)",
              PLOTS[PRIMRAY_UNMATCHED_BASICBLOCKS],
              primaryUnmatchedCount,
              primaryUnmatchedPercent,
              "%");
        }
        if (key.equals(PLOTS[SECONDARY_UNMATCHED_BASICBLOCKS])) {
          return String.format(
              "%s %d (%.1f%s)",
              PLOTS[SECONDARY_UNMATCHED_BASICBLOCKS],
              secondaryUnmatchedCount,
              secondaryUnmatchedPercent,
              "%");
        }
      }
      return null;
    }
  }

  private class InternalFlowgraphCachedCountsListener extends DiffChangeAdapter {
    @Override
    public void basicblocksCountChanged() {
      updateDataset();
    }
  }
}