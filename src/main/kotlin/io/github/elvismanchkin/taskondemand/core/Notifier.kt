package io.github.elvismanchkin.taskondemand.core

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

object Notifier {
    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Task on Demand")

    fun info(project: Project?, message: String) = group().createNotification(message, NotificationType.INFORMATION).notify(project)

    fun error(project: Project?, message: String) = group().createNotification(message, NotificationType.ERROR).notify(project)

    /** For "not configured" situations: the notification links to the settings page. */
    fun notConfigured(project: Project?, what: String) {
        group().createNotification("$what is not configured", NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Task on Demand")
            })
            .notify(project)
    }
}
