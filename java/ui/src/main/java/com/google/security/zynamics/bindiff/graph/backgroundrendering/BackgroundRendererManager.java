package com.google.security.zynamics.bindiff.graph.backgroundrendering;

import com.google.common.base.Preconditions;
import com.google.security.zynamics.bindiff.enums.EGraph;
import com.google.security.zynamics.bindiff.enums.ESide;
import com.google.security.zynamics.bindiff.graph.settings.GraphDisplaySettings;
import com.google.security.zynamics.bindiff.graph.settings.GraphSettings;
import com.google.security.zynamics.bindiff.graph.settings.GraphSettingsChangedListenerAdapter;
import com.google.security.zynamics.bindiff.project.userview.CallGraphViewData;
import com.google.security.zynamics.bindiff.project.userview.FlowGraphViewData;
import com.google.security.zynamics.bindiff.project.userview.ViewData;
import com.google.security.zynamics.bindiff.resources.Colors;

import y.view.Graph2DView;

import java.awt.Color;

public class BackgroundRendererManager {
  private final Color COMBINED_GRADIENT_END_COLOR = new Color(200, 210, 190);

  private final GraphSettings settings;

  private final InternalGraphSettingsChangedListener settingsChangedListener =
      new InternalGraphSettingsChangedListener();

  private final Graph2DView view;

  private final ImageBackgroundRenderer imageBackgroundRenderer;

  private final GradientBackgroundRenderer gradientBackgroundRenderer;

  public BackgroundRendererManager(
      final ViewData viewData,
      final Graph2DView graph2DView,
      final EGraph graphType,
      final GraphSettings settings) {
    Preconditions.checkNotNull(viewData);
    Preconditions.checkNotNull(graphType);
    this.settings = Preconditions.checkNotNull(settings);

    this.view = graph2DView;

    this.imageBackgroundRenderer = new ImageBackgroundRenderer(viewData, view, graphType);
    this.gradientBackgroundRenderer =
        new GradientBackgroundRenderer(viewData, view, getGradientEndColor(graphType), graphType);

    // init
    this.settingsChangedListener.gradientBackgroundChanged(settings.getDisplaySettings());

    settings.addListener(settingsChangedListener);
  }

  protected static String buildTitle(final ViewData viewData, final EGraph type) {
    if (viewData instanceof FlowGraphViewData) {
      final FlowGraphViewData data = (FlowGraphViewData) viewData;

      switch (type) {
        case PRIMARY_GRAPH:
          {
            return data.getAddress(ESide.PRIMARY) == null
                ? ""
                : data.getAddress(ESide.PRIMARY) + "   " + data.getFunctionName(ESide.PRIMARY);
          }
        case SECONDARY_GRAPH:
          {
            return data.getAddress(ESide.SECONDARY) == null
                ? ""
                : data.getFunctionName(ESide.SECONDARY) + "   " + data.getAddress(ESide.SECONDARY);
          }
        case COMBINED_GRAPH:
          {
            return String.format(
                "%s%s%s%s%s",
                data.getAddress(ESide.PRIMARY) == null
                    ? ""
                    : data.getAddress(ESide.PRIMARY) + "   ",
                data.getFunctionName(ESide.PRIMARY) == null
                    ? ""
                    : data.getFunctionName(ESide.PRIMARY),
                data.getAddress(ESide.PRIMARY) == null || data.getAddress(ESide.SECONDARY) == null
                    ? ""
                    : "   vs   ",
                data.getAddress(ESide.SECONDARY) == null
                    ? ""
                    : data.getAddress(ESide.SECONDARY) + "   ",
                data.getFunctionName(ESide.SECONDARY) == null
                    ? ""
                    : data.getFunctionName(ESide.SECONDARY));
          }
        default:
      }
    } else if (viewData instanceof CallGraphViewData) {
      final CallGraphViewData data = (CallGraphViewData) viewData;

      switch (type) {
        case PRIMARY_GRAPH:
          return data.getImageName(ESide.PRIMARY);
        case SECONDARY_GRAPH:
          return data.getImageName(ESide.SECONDARY);
        case COMBINED_GRAPH:
          return String.format(
              "%s vs %s", data.getImageName(ESide.PRIMARY), data.getImageName(ESide.SECONDARY));
        default:
      }
    }

    return "";
  }

  private Color getGradientEndColor(final EGraph type) {
    switch (type) {
      case PRIMARY_GRAPH:
        return Colors.PRIMARY_BASE;
      case SECONDARY_GRAPH:
        return Colors.SECONDARY_BASE;
      default:
    }

    return COMBINED_GRADIENT_END_COLOR;
  }

  public void addListeners() {
    settings.addListener(settingsChangedListener);
  }

  public void removeListener() {
    settings.removeListener(settingsChangedListener);
  }

  private class InternalGraphSettingsChangedListener extends GraphSettingsChangedListenerAdapter {
    @Override
    public void diffViewModeChanged(final GraphSettings settings) {
      if (settings.getDisplaySettings().getGradientBackground()) {
        gradientBackgroundRenderer.update();

      } else {
        imageBackgroundRenderer.update();
      }
    }

    @Override
    public void gradientBackgroundChanged(final GraphDisplaySettings settings) {
      if (settings.getGradientBackground()) {
        view.setBackgroundRenderer(gradientBackgroundRenderer);
        gradientBackgroundRenderer.update();
      } else {
        view.setBackgroundRenderer(imageBackgroundRenderer);
        imageBackgroundRenderer.update();
      }
    }
  }
}
