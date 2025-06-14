package com.example.gitsubmodules.actions

import com.example.gitsubmodules.SubmoduleService
import com.example.gitsubmodules.dialogs.SwitchSubmoduleBranchDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import git4idea.repo.GitRepositoryManager

class SwitchSubmoduleBranchAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val submoduleService = project.service<SubmoduleService>()

        val dialog = SwitchSubmoduleBranchDialog(project, submoduleService)
        if (dialog.showAndGet()) {
            val submodulePath = dialog.getSelectedSubmodulePath()
            val branchOrCommit = dialog.getBranchOrCommit()

            if (submodulePath.isNotBlank() && branchOrCommit.isNotBlank()) {
                submoduleService.switchSubmoduleBranch(submodulePath, branchOrCommit)
                    .thenAccept { success ->
                        if (success) {
                            // Show success notification
                        } else {
                            // Show error notification
                        }
                    }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasGitRepo = project?.let {
            val submoduleService = it.service<SubmoduleService>()
            GitRepositoryManager.getInstance(it).repositories.isNotEmpty() &&
                    submoduleService.getSubmodules().any { sub -> sub.initialized }
        } ?: false

        e.presentation.isEnabled = hasGitRepo
    }
}
