package io.github.elvismanchkin.taskondemand.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon
import javax.swing.JComponent

/** A toolbar action backed by lambdas; [enabled] is evaluated on the EDT. */
fun panelAction(text: String, icon: Icon?, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
    object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = perform()
    }

fun toolbar(place: String, target: JComponent, vararg actions: AnAction): ActionToolbar =
    ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), true).also {
        it.targetComponent = target
    }
