package com.google.security.zynamics.bindiff.gui.dialogs.criteriadialog.expressiontree.actions;

import com.google.security.zynamics.bindiff.gui.dialogs.criteriadialog.expressiontree.ExpressionTreeActionProvider;
import com.google.security.zynamics.bindiff.gui.dialogs.criteriadialog.operators.AndCriterium;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;

public class AppendAndOperatorAction extends AbstractAction {
  private final ExpressionTreeActionProvider actionProvider;

  public AppendAndOperatorAction(final ExpressionTreeActionProvider actionProvider) {
    super("Append AND");

    this.actionProvider = actionProvider;
  }

  @Override
  public void actionPerformed(final ActionEvent e) {
    actionProvider.appendCriterium(new AndCriterium());
  }
}
