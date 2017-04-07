package com.google.security.zynamics.bindiff.graph.listeners;

import com.google.common.base.Preconditions;
import com.google.security.zynamics.bindiff.enums.EGraphType;
import com.google.security.zynamics.bindiff.graph.SingleGraph;
import com.google.security.zynamics.bindiff.graph.synchronizer.GraphViewCanvasSynchronizer;
import com.google.security.zynamics.bindiff.gui.tabpanels.viewtabpanel.ViewTabPanelFunctions;

import java.awt.geom.Point2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class SingleViewCanvasListener implements PropertyChangeListener {
  private final SingleGraph graph;

  private final ViewTabPanelFunctions viewPanelController;

  private boolean suppressUpdateGraph = false;

  protected SingleViewCanvasListener(
      final ViewTabPanelFunctions controller, final SingleGraph graph) {
    viewPanelController = Preconditions.checkNotNull(controller);
    this.graph = Preconditions.checkNotNull(graph);

    addListener();
  }

  public void addListener() {
    graph.getView().getCanvasComponent().addPropertyChangeListener(this);
  }

  @Override
  public void propertyChange(final PropertyChangeEvent event) {
    if (graph.getGraphType() == EGraphType.FLOWGRAPH && graph.getFunctionAddress() == null) {
      // don't sync zoom and view point if it's is an unmatched view;
      return;
    }

    if ("Zoom".equals(event.getPropertyName())) {
      GraphViewCanvasSynchronizer.adoptZoom(viewPanelController.getGraphListenerManager(), graph);
    } else if ("ViewPoint".equals(event.getPropertyName())) {
      GraphViewCanvasSynchronizer.adoptViewPoint(
          viewPanelController.getGraphListenerManager(),
          graph,
          (Point2D.Double) event.getOldValue(),
          suppressUpdateGraph);
    }
  }

  public void removeListener() {
    graph.getView().getCanvasComponent().removePropertyChangeListener(this);
  }

  public void suppressUpdateGraph(final boolean suppress) {
    suppressUpdateGraph = suppress;
  }
}
