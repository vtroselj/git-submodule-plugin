package com.example.gitsubmodules.actions

import com.example.gitsubmodules.SubmoduleService
import com.example.gitsubmodules.dialogs.AddSubmoduleDialog
import com.example.gitsubmodules.model.SubmoduleResult
import com.example.gitsubmodules.notifications.NotificationService
import com.example.gitsubmodules.utils.AsyncHandler
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File

class AddSubmoduleAction : AnAction() {

    companion object {
        private val LOG = thisLogger()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dialog = AddSubmoduleDialog(project)
        if (dialog.showAndGet()) {
            val url = dialog.getUrl()
            val path = dialog.getPath()
            val branch = dialog.getBranch()

            LOG.info("Adding submodule - URL: '$url', Path: '$path', Branch: '$branch'")

            addSubmodule(project, url, path, branch)
        }
    }

    private fun addSubmodule(project: Project, url: String, path: String, branch: String?) {
        val submoduleService = project.service<SubmoduleService>()

        submoduleService.addSubmodule(url, path, branch)
            .thenAccept { result ->
                when (result) {
                    is SubmoduleResult.Success -> {
                        handleSuccess(project, path)
                    }
                    is SubmoduleResult.Error -> {
                        handleError(project, result.message, path)
                    }
                }
            }
            .exceptionally { throwable ->
                LOG.error("Unexpected error adding submodule", throwable)
                AsyncHandler.runOnEDT {
                    NotificationService.notifyError(
                        project,
                        "Unexpected error: ${throwable.message}"
                    )
                }
                null
            }
    }

    private fun handleSuccess(project: Project, path: String) {
        LOG.info("Submodule added successfully at: $path")

        // Perform VCS integration
        AsyncHandler.runInBackground(
            project,
            "Integrating with VCS",
            canBeCancelled = false
        ) { indicator ->
            indicator.text = "Adding to Git..."
            addSubmoduleToGit(project, path)

            indicator.text = "Updating VCS mappings..."
            updateVcsMappings(project, path)

            indicator.text = "Refreshing project files..."
            refreshProjectFiles(project, path)

            true
        }.thenAccept {
            AsyncHandler.runOnEDT {
                NotificationService.notifySuccess(
                    project,
                    "Submodule added and integrated successfully at: $path"
                )
            }
        }
    }

    private fun handleError(project: Project, errorMessage: String, path: String) {
        LOG.warn("Failed to add submodule: $errorMessage")

        AsyncHandler.runOnEDT {
            when {
                errorMessage.contains("already exists", ignoreCase = true) -> {
                    showPathExistsDialog(project, path, errorMessage)
                }
                errorMessage.contains("not a git repository", ignoreCase = true) -> {
                    NotificationService.notifyError(
                        project,
                        "The current project is not a Git repository.\n" +
                                "Initialize Git first: VCS → Import into Version Control → Create Git Repository"
                    )
                }
                errorMessage.contains("permission denied", ignoreCase = true) ||
                        errorMessage.contains("authentication failed", ignoreCase = true) -> {
                    NotificationService.notifyError(
                        project,
                        "Authentication failed. Please check your Git credentials."
                    )
                }
                else -> {
                    NotificationService.notifyError(project, errorMessage)
                }
            }
        }
    }

    private fun showPathExistsDialog(project: Project, path: String, errorMessage: String) {
        val options = arrayOf("Choose Different Path", "Cancel")
        val choice = Messages.showDialog(
            project,
            "The path '$path' already exists.\n\n$errorMessage",
            "Path Already Exists",
            options,
            0,
            Messages.getWarningIcon()
        )

        if (choice == 0) {
            // Re-open dialog
            val dialog = AddSubmoduleDialog(project)
            if (dialog.showAndGet()) {
                val newUrl = dialog.getUrl()
                val newPath = dialog.getPath()
                val newBranch = dialog.getBranch()
                addSubmodule(project, newUrl, newPath, newBranch)
            }
        }
    }

    private fun addSubmoduleToGit(project: Project, submodulePath: String) {
        val projectBasePath = project.basePath ?: return
        val git = Git.getInstance()

        try {
            // Add .gitmodules
            val gitmodulesHandler = GitLineHandler(project, File(projectBasePath), GitCommand.ADD)
            gitmodulesHandler.addParameters(".gitmodules")
            git.runCommand(gitmodulesHandler)

            // Add submodule directory
            val relativePath = getRelativePath(projectBasePath, submodulePath)
            val submoduleHandler = GitLineHandler(project, File(projectBasePath), GitCommand.ADD)
            submoduleHandler.addParameters(relativePath)
            git.runCommand(submoduleHandler)

            LOG.info("Added submodule files to Git")
        } catch (e: Exception) {
            LOG.error("Error adding submodule to Git", e)
        }
    }

    private fun updateVcsMappings(project: Project, submodulePath: String) {
        AsyncHandler.runWriteAction {
            try {
                val vcsManager = ProjectLevelVcsManagerImpl.getInstance(project)
                val currentMappings = vcsManager.directoryMappings.toMutableList()

                val absolutePath = File(submodulePath).absolutePath

                // Check if mapping already exists
                val exists = currentMappings.any { it.directory == absolutePath }

                if (!exists && File(absolutePath).exists()) {
                    val newMapping = VcsDirectoryMapping(absolutePath, "Git")
                    currentMappings.add(newMapping)

                    // Update the mappings - this will trigger save to vcs.xml
                    vcsManager.setDirectoryMappings(currentMappings)

                    LOG.info("Added VCS mapping for: $absolutePath")
                }
            } catch (e: Exception) {
                LOG.error("Error updating VCS mappings", e)
            }
        }
    }

    private fun refreshProjectFiles(project: Project, submodulePath: String) {
        AsyncHandler.runOnEDT {
            try {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(submodulePath)
                virtualFile?.refresh(false, true)

                // Refresh project files
                project.basePath?.let { basePath ->
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)?.refresh(false, true)
                }
                LOG.info("Project files refreshed")
            } catch (e: Exception) {
                LOG.error("Error refreshing project files", e)
            }
        }
    }

    private fun getRelativePath(basePath: String, absolutePath: String): String {
        return try {
            File(basePath).toPath().relativize(File(absolutePath).toPath()).toString()
                .replace('\\', '/')
        } catch (e: Exception) {
            absolutePath
        }
    }
}