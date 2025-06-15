package com.example.gitsubmodules.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Centralized notification service for consistent user feedback
 */
object NotificationService {

    private const val GROUP_ID = "Git Submodules"

    fun notifySuccess(project: Project, content: String, title: String = "Success") {
        notify(project, title, content, NotificationType.INFORMATION)
    }

    fun notifyError(project: Project, content: String, title: String = "Error") {
        notify(project, title, content, NotificationType.ERROR)
    }

    fun notifyWarning(project: Project, content: String, title: String = "Warning") {
        notify(project, title, content, NotificationType.WARNING)
    }

    fun notifyInfo(project: Project, content: String, title: String = "Information") {
        notify(project, title, content, NotificationType.INFORMATION)
    }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
