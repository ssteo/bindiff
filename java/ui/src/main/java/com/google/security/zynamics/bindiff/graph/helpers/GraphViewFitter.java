package com.google.security.zynamics.bindiff.graph.helpers;

import com.google.security.zynamics.bindiff.graph.SingleGraph;
import com.google.security.zynamics.bindiff.graph.SuperGraph;

import y.view.Graph2D;
import y.view.Graph2DView;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;

public class GraphViewFitter {
  public static void adoptSuperViewCanvasProperties(final SuperGraph superGraph) {
    if (superGraph.getSettings().isSync()) {
      // adopt view point, world rectangle from supergraph and zoom factor from source graph
      final SingleGraph primaryGraph = superGraph.getPrimaryGraph();
      final SingleGraph secondaryGraph = superGraph.getSecondaryGraph();

      final Graph2DView superView = superGraph.getView();
      final Rectangle rect = superView.getWorldRect();
      final Point2D point = superView.getViewPoint2D();

      final Graph2DView priView = primaryGraph.getView();
      priView.setWorldRect(
          (int) rect.getX(), (int) rect.getY(), (int) rect.getWidth(), (int) rect.getHeight());
      priView.setZoom(superView.getZoom());

      final Graph2DView secView = secondaryGraph.getView();
      secView.setWorldRect(
          (int) rect.getX(), (int) rect.getY(), (int) rect.getWidth(), (int) rect.getHeight());
      secView.setZoom(superView.getZoom());

      if (priView.getWidth() > secView.getWidth()) {
        priView.setViewPoint((int) point.getX(), (int) point.getY());
        secView.setCenter(superView.getCenter().getX(), superView.getCenter().getY());
      } else if (priView.getWidth() < secView.getWidth()) {
        secView.setViewPoint((int) point.getX(), (int) point.getY());
        priView.setCenter(superView.getCenter().getX(), superView.getCenter().getY());
      } else {
        priView.setViewPoint((int) point.getX(), (int) point.getY());
        secView.setViewPoint((int) point.getX(), (int) point.getY());
      }
    }
  }

  // WARNING: This function is NOT thread safe!
  public static void fitSingleViewToSuperViewContent(final SuperGraph superGraph) {
    if (superGraph.getSettings().isSync()) {
      final SingleGraph primaryGraph = superGraph.getPrimaryGraph();
      final SingleGraph secondaryGraph = superGraph.getSecondaryGraph();

      final int priWidth = (int) Math.round(primaryGraph.getView().getSize().getWidth());
      final int secWidth = (int) Math.round(secondaryGraph.getView().getSize().getWidth());

      if (priWidth > secWidth && primaryGraph.getNodes().size() > 0) {
        final Graph2D pGraph = primaryGraph.getView().getGraph2D();
        primaryGraph.getView().setGraph2D(superGraph.getGraph());
        primaryGraph.getGraph().updateViews();
        primaryGraph.getView().fitContent();

        final double primaryZoom = primaryGraph.getView().getZoom();
        final Point primaryViewPort = primaryGraph.getView().getViewPoint();

        primaryGraph.getView().setGraph2D(pGraph);
        primaryGraph.getView().setViewPoint2D(primaryViewPort.getX(), primaryViewPort.getY());
        primaryGraph.getView().setZoom(primaryZoom * GraphZoomer.ZOOM_OUT_FACTOR);
      } else {
        final Graph2D sGraph = secondaryGraph.getView().getGraph2D();
        secondaryGraph.getView().setGraph2D(superGraph.getGraph());
        secondaryGraph.getGraph().updateViews();
        secondaryGraph.getView().fitContent();

        final double secondaryZoom = secondaryGraph.getView().getZoom();
        final Point secondaryViewPort = secondaryGraph.getView().getViewPoint();

        secondaryGraph.getView().setGraph2D(sGraph);
        secondaryGraph.getView().setViewPoint2D(secondaryViewPort.getX(), secondaryViewPort.getY());
        secondaryGraph.getView().setZoom(secondaryZoom * GraphZoomer.ZOOM_OUT_FACTOR);
      }
    }
  }
}
