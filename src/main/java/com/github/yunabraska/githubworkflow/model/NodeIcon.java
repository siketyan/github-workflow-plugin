package com.github.yunabraska.githubworkflow.model;

import com.intellij.icons.AllIcons;

import javax.swing.*;

public enum NodeIcon {
    //https://jetbrains.design/intellij/resources/icons_list/
    //ORDINAL == PRIORITY
    ICON_NODE(AllIcons.Nodes.Interface),
    ICON_NEEDS(AllIcons.Nodes.Related),
    ICON_ENV(AllIcons.Nodes.Constant),
    ICON_RUNNER(AllIcons.Nodes.Variable),
    ICON_STEP(AllIcons.Nodes.Class),
    ICON_JOB(AllIcons.Nodes.Class),
    ICON_OUTPUT(AllIcons.Nodes.Variable),
    ICON_INPUT(AllIcons.Nodes.Parameter),
    ICON_SECRET_WORKFLOW(AllIcons.Nodes.Static),
    ICON_ENV_ROOT(AllIcons.Nodes.Constant),
    ICON_ENV_JOB(AllIcons.Nodes.Parameter),
    ICON_ENV_STEP(AllIcons.Nodes.Parameter),
    ICON_TEXT_VARIABLE(AllIcons.Nodes.Gvariable),

    // FIXES
    RELOAD(AllIcons.General.InlineRefresh),
    SETTINGS(AllIcons.General.Settings),
    HYPERLINK(AllIcons.Ide.External_link_arrow),
    JUMP_TO_IMPLEMENTATION(AllIcons.Gutter.ImplementedMethod),
    SUPPRESS_OFF(AllIcons.Actions.OfflineMode),
    ;

    final Icon icon;

    NodeIcon(final Icon icon) {
        this.icon = icon;
    }

    public Icon icon() {
        return icon;
    }
}
