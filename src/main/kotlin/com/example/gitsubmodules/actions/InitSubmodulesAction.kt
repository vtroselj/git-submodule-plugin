package com.example.gitsubmodules.actions

import com.example.gitsubmodules.SubmoduleService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import git4idea.repo.GitRepositoryManager

class InitSubmodulesAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val submoduleService = project.service<SubmoduleService>()

        submoduleService.initSubmodules()
            .thenAccept { _ ->
                // Handle result
            }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasGitRepo = project?.let {
            GitRepositoryManager.getInstance(it).repositories.isNotEmpty()
        } ?: false

        e.presentation.isEnabled = hasGitRepo
    }
}