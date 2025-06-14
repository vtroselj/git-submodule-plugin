package com.example.gitsubmodules.actions

import com.example.gitsubmodules.SubmoduleService
import com.example.gitsubmodules.dialogs.AddSubmoduleDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class AddSubmoduleAction : AnAction() {

    companion object {
        private val LOG = thisLogger()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        showAddSubmoduleDialog(project)
    }

    private fun showAddSubmoduleDialog(project: Project) {
        LOG.info("AddSubmoduleAction triggered for project: ${project.name}")
        LOG.info("Project base path: ${project.basePath}")

        val dialog = AddSubmoduleDialog(project)
        if (dialog.showAndGet()) {
            val url = dialog.getUrl()
            val path = dialog.getPath()
            val branch = dialog.getBranch()

            LOG.info("Dialog accepted with URL: '$url', Path: '$path', Branch: '$branch'")

            // Validate inputs before proceeding
            val validationError = validateInputs(project, path, url)
            if (validationError != null) {
                LOG.error("Input validation failed: $validationError")
                Messages.showErrorDialog(project, validationError, "Invalid Input")
                return
            }

            // Add the submodule with VCS integration
            addSubmoduleWithVcsIntegration(project, url, path, branch)
        } else {
            LOG.info("Dialog was cancelled")
        }
    }

    private fun validateInputs(project: Project, path: String, url: String): String? {
        LOG.info("Validating inputs - Project: ${project.name}, Path: '$path', URL: '$url'")

        // Validate URL
        if (url.isBlank()) {
            return "Repository URL cannot be empty"
        }

        // Validate path
        if (path.isBlank()) {
            return "Local path cannot be empty"
        }

        // Normalize and validate the path
        val normalizedPath = try {
            normalizePath(project, path)
        } catch (e: InvalidPathException) {
            LOG.error("Invalid path format: '$path'", e)
            return "Invalid path format: ${e.message}"
        } catch (e: Exception) {
            LOG.error("Error processing path: '$path'", e)
            return "Error processing path: ${e.message}"
        }

        LOG.info("Normalized path: '$normalizedPath'")

        // Check if the target path would be within the project
        val projectBasePath = project.basePath
        if (projectBasePath != null) {
            val projectPath = Paths.get(projectBasePath).normalize()
            val targetPath = normalizedPath.normalize()

            // Check if target path is within project bounds
            if (!targetPath.startsWith(projectPath)) {
                LOG.warn("Target path is outside project: project='$projectPath', target='$targetPath'")
                // This is just a warning, not an error - allow it but log it
            }
        }

        // Check if the path already exists and is not empty
        if (normalizedPath.exists()) {
            if (!normalizedPath.isDirectory()) {
                return "Path exists but is not a directory: $normalizedPath"
            }

            val directory = normalizedPath.toFile()
            if (directory.listFiles()?.isNotEmpty() == true) {
                return "Directory is not empty: $normalizedPath"
            }
        }

        LOG.info("Input validation passed")
        return null
    }

    private fun normalizePath(project: Project, inputPath: String): Path {
        LOG.info("Normalizing path: '$inputPath'")

        // Clean the input path
        val cleanPath = inputPath.trim()
            .replace("\\", "/")  // Normalize separators to forward slashes first
            .replace("//", "/")   // Remove double slashes

        LOG.info("Cleaned path: '$cleanPath'")

        // Try to create the path
        val path = try {
            when {
                // If it's already an absolute path, use it directly
                cleanPath.matches(Regex("^[A-Za-z]:/.*")) || cleanPath.startsWith("/") -> {
                    LOG.info("Using absolute path")
                    Paths.get(cleanPath)
                }
                // If it's a relative path, resolve it against the project base
                else -> {
                    val projectBasePath = project.basePath
                    if (projectBasePath != null) {
                        val projectPath = Paths.get(projectBasePath)
                        LOG.info("Resolving relative path against project base: '$projectBasePath'")
                        projectPath.resolve(cleanPath)
                    } else {
                        LOG.info("No project base path, using path as-is")
                        Paths.get(cleanPath)
                    }
                }
            }
        } catch (e: InvalidPathException) {
            LOG.error("Failed to create path from: '$cleanPath'", e)
            throw e
        }

        val normalizedPath = path.normalize()
        LOG.info("Final normalized path: '$normalizedPath'")

        return normalizedPath
    }

    private fun addSubmoduleWithVcsIntegration(project: Project, url: String, path: String, branch: String?) {
        LOG.info("Adding submodule with VCS integration - URL: '$url', Path: '$path', Branch: '$branch'")

        try {
            val normalizedPath = normalizePath(project, path)
            val submoduleService = project.service<SubmoduleService>()
            val pathString = normalizedPath.toString()

            LOG.info("Calling submodule service with path: '$pathString'")

            submoduleService.addSubmodule(url, pathString, branch)
                .thenRun {
                    LOG.info("Submodule added successfully, now integrating with VCS")

                    // Run VCS integration on background thread
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            // Wait for submodule directory to be created and give it time to stabilize
                            waitForSubmoduleDirectory(pathString, 10) // Wait up to 10 seconds

                            // Step 1: Add submodule files to git
                            addSubmoduleToGit(project, pathString)

                            // Step 2: Update VCS mappings (with proper validation)
                            updateVcsMappings(project, pathString)

                            // Step 3: Refresh project files
                            refreshProjectFiles(project, pathString)

                            LOG.info("VCS integration completed successfully")

                            // Show success message on EDT
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(
                                    project,
                                    "Submodule added successfully and integrated with VCS at: $pathString",
                                    "Success"
                                )
                            }
                        } catch (e: Exception) {
                            LOG.error("Error during VCS integration", e)

                            // Show error message on EDT
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showWarningDialog(
                                    project,
                                    "Submodule added but VCS integration failed: ${e.message}",
                                    "Partial Success"
                                )
                            }
                        }
                    }
                }
                .exceptionally { throwable ->
                    LOG.error("Failed to add submodule", throwable)
                    ApplicationManager.getApplication().invokeLater {
                        handleSubmoduleError(project, throwable, pathString)
                    }
                    null
                }
        } catch (e: Exception) {
            LOG.error("Error in addSubmoduleWithVcsIntegration", e)
            Messages.showErrorDialog(
                project,
                "Error adding submodule: ${e.message}",
                "Error"
            )
        }
    }

    /**
     * Wait for submodule directory to be created and initialized
     * This prevents race conditions during VCS integration
     */
    private fun waitForSubmoduleDirectory(submodulePath: String, maxWaitSeconds: Int): Boolean {
        LOG.info("Waiting for submodule directory to be created: $submodulePath")

        val maxAttempts = maxWaitSeconds * 2 // Check every 500ms
        var attempts = 0

        while (attempts < maxAttempts) {
            val dir = File(submodulePath)
            if (dir.exists() && dir.isDirectory) {
                // Additional check: ensure it's a proper git repository
                val gitDir = File(dir, ".git")
                if (gitDir.exists()) {
                    LOG.info("Submodule directory ready after ${attempts * 500}ms: $submodulePath")
                    return true
                }
            }

            Thread.sleep(500)
            attempts++
        }

        LOG.warn("Submodule directory not ready after ${maxWaitSeconds}s: $submodulePath")
        return false
    }

    private fun addSubmoduleToGit(project: Project, submodulePath: String) {
        LOG.info("Adding submodule to git: $submodulePath")

        val projectBasePath = project.basePath ?: return
        val git = Git.getInstance()

        try {
            // Add .gitmodules file
            val gitmodulesHandler = GitLineHandler(project, File(projectBasePath), GitCommand.ADD)
            gitmodulesHandler.addParameters(".gitmodules")
            git.runCommand(gitmodulesHandler)
            LOG.info("Added .gitmodules to git")

            // Add submodule directory
            val submoduleHandler = GitLineHandler(project, File(projectBasePath), GitCommand.ADD)
            val relativePath = getRelativePathFromProject(project, submodulePath)
            submoduleHandler.addParameters(relativePath)
            git.runCommand(submoduleHandler)
            LOG.info("Added submodule directory to git: $relativePath")

            LOG.info("Submodule files added to staging area (commit manually if needed)")

        } catch (e: Exception) {
            LOG.error("Error adding submodule to git", e)
            throw e
        }
    }

    private fun updateVcsMappings(project: Project, submodulePath: String) {
        LOG.info("Updating VCS mappings for submodule: $submodulePath")

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val vcsManager = ProjectLevelVcsManagerImpl.getInstance(project)
                    val currentMappings = vcsManager.directoryMappings.toMutableList()

                    // Use absolute path instead of $PROJECT_DIR$ variable
                    val absolutePath = File(submodulePath).absolutePath

                    // Validate the path exists and is a directory before adding mapping
                    val submoduleDir = File(absolutePath)
                    if (!submoduleDir.exists()) {
                        LOG.warn("Submodule directory does not exist yet: $absolutePath")
                        // Don't add VCS mapping if directory doesn't exist yet
                        // This prevents the warning but allows the process to continue
                        return@runWriteCommandAction
                    }

                    if (!submoduleDir.isDirectory) {
                        LOG.warn("Submodule path is not a directory: $absolutePath")
                        return@runWriteCommandAction
                    }

                    // Additional check: ensure it's a git repository
                    val gitDir = File(submoduleDir, ".git")
                    if (!gitDir.exists()) {
                        LOG.warn("Submodule directory is not a git repository: $absolutePath")
                        // Still continue, as the .git might be a file pointing to the main repo's modules
                    }

                    // Check if mapping already exists
                    val existingMapping = currentMappings.find { mapping ->
                        val mappingFile = File(mapping.directory)
                        mappingFile.absolutePath == absolutePath
                    }

                    if (existingMapping == null) {
                        // Add new VCS mapping for the submodule using absolute path
                        val newMapping = VcsDirectoryMapping(absolutePath, "Git")
                        currentMappings.add(newMapping)

                        // Update the VCS mappings
                        vcsManager.directoryMappings = currentMappings
                        LOG.info("Added VCS mapping: $absolutePath -> Git")
                    } else {
                        LOG.info("VCS mapping already exists: $absolutePath")
                    }

                } catch (e: Exception) {
                    LOG.error("Error updating VCS mappings", e)
                    throw e
                }
            }
        }
    }

    private fun refreshProjectFiles(project: Project, submodulePath: String) {
        LOG.info("Refreshing project files for submodule: $submodulePath")

        try {
            ApplicationManager.getApplication().invokeAndWait {
                // Refresh the local file system
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(submodulePath)
                virtualFile?.refresh(false, true)

                // Refresh project base directory
                project.baseDir?.refresh(false, true)
            }

            LOG.info("Project files refreshed successfully")
        } catch (e: Exception) {
            LOG.error("Error refreshing project files", e)
            // Don't throw here as this is not critical
        }
    }

    private fun handleSubmoduleError(project: Project, throwable: Throwable, pathString: String) {
        val errorMessage = throwable.message ?: "Unknown error"

        when {
            // More comprehensive path existence checks covering actual Git error messages
            errorMessage.contains("already exists in the repository", ignoreCase = true) ||
                    errorMessage.contains("path already exists", ignoreCase = true) ||
                    errorMessage.contains("destination path already exists", ignoreCase = true) ||
                    errorMessage.contains("already exists in the index", ignoreCase = true) ||
                    errorMessage.contains("already exists and is not a valid git repo", ignoreCase = true) ||
                    errorMessage.contains("already exists and is not an empty directory", ignoreCase = true) -> {
                // Handle path already exists error
                val options = arrayOf("Remove and Re-add", "Choose Different Path", "Cancel")
                val choice = Messages.showDialog(
                    project,
                    "The path '$pathString' already exists in the repository.\n\n" +
                            "This could mean:\n" +
                            "• A submodule already exists at this path\n" +
                            "• Regular files/folders exist at this path\n" +
                            "• The path was previously used for a submodule\n" +
                            "• The path exists in the Git index\n\n" +
                            "Error details: $errorMessage\n\n" +
                            "What would you like to do?",
                    "Path Already Exists",
                    options,
                    0,
                    Messages.getWarningIcon()
                )

                when (choice) {
                    0 -> handleRemoveAndReadd(project, pathString)
                    1 -> reopenDialogWithSuggestedPath(project, pathString)
                    // 2 or -1 (Cancel/Close) - do nothing
                }
            }

            errorMessage.contains("not a git repository", ignoreCase = true) -> {
                Messages.showErrorDialog(
                    project,
                    "The current project is not a Git repository.\n\n" +
                            "Please initialize Git in your project first:\n" +
                            "VCS → Import into Version Control → Create Git Repository",
                    "Not a Git Repository"
                )
            }

            errorMessage.contains("invalid url", ignoreCase = true) ||
                    errorMessage.contains("could not read from remote repository", ignoreCase = true) -> {
                Messages.showErrorDialog(
                    project,
                    "Invalid or inaccessible repository URL.\n\n" +
                            "Please check:\n" +
                            "• The URL is correct\n" +
                            "• You have access to the repository\n" +
                            "• Your network connection\n" +
                            "• Your Git credentials",
                    "Repository Access Error"
                )
            }

            errorMessage.contains("permission denied", ignoreCase = true) ||
                    errorMessage.contains("authentication failed", ignoreCase = true) -> {
                Messages.showErrorDialog(
                    project,
                    "Authentication failed.\n\n" +
                            "Please check your Git credentials and repository access permissions.\n\n" +
                            "You may need to:\n" +
                            "• Set up SSH keys\n" +
                            "• Configure Git credentials\n" +
                            "• Use a personal access token",
                    "Authentication Error"
                )
            }

            else -> {
                // Generic error handling - show the actual error message for debugging
                Messages.showErrorDialog(
                    project,
                    "Failed to add submodule: $errorMessage\n\n" +
                            "Please check the Git output in the Version Control console for more details.",
                    "Submodule Error"
                )
            }
        }
    }

    private fun handleRemoveAndReadd(project: Project, pathString: String) {
        val confirmation = Messages.showYesNoDialog(
            project,
            "This will remove any existing content at '$pathString' and add the new submodule.\n\n" +
                    "WARNING: This action cannot be undone!\n\n" +
                    "Are you sure you want to continue?",
            "Confirm Removal",
            "Remove and Add Submodule",
            "Cancel",
            Messages.getWarningIcon()
        )

        if (confirmation == Messages.YES) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // Remove existing path with improved cleanup
                    removeExistingPathCompletely(project, pathString)

                    // Add a small delay to ensure cleanup is complete
                    Thread.sleep(1000)

                    // Show dialog again to re-add
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "Path removed successfully. Please try adding the submodule again.",
                            "Path Cleared"
                        )
                        // Re-trigger the dialog directly instead of calling actionPerformed
                        showAddSubmoduleDialog(project)
                    }
                } catch (e: Exception) {
                    LOG.error("Error removing existing path", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to remove existing path: ${e.message}",
                            "Removal Error"
                        )
                    }
                }
            }
        }
    }

    /**
     * Enhanced cleanup that ensures complete removal of submodule traces
     */
    private fun removeExistingPathCompletely(project: Project, pathString: String) {
        val projectBasePath = project.basePath ?: return
        val git = Git.getInstance()
        val relativePath = getRelativePathFromProject(project, pathString)

        LOG.info("Starting complete cleanup of existing submodule at: $relativePath")

        try {
            // Step 1: Force deinitialize the submodule
            try {
                val deinitHandler = GitLineHandler(project, File(projectBasePath), GitCommand.SUBMODULE)
                deinitHandler.addParameters("deinit", "-f", "--all")
                git.runCommand(deinitHandler)
                LOG.info("Force deinitialized all submodules")
            } catch (e: Exception) {
                LOG.warn("Could not deinitialize submodules: ${e.message}")
            }

            // Step 2: Remove from git index (both cached and working tree)
            try {
                val rmHandler = GitLineHandler(project, File(projectBasePath), GitCommand.RM)
                rmHandler.addParameters("-r", "--cached", relativePath)
                git.runCommand(rmHandler)
                LOG.info("Removed from git index: $relativePath")
            } catch (e: Exception) {
                LOG.warn("Could not remove from git index: ${e.message}")
            }

            // Step 3: Remove the physical directory completely
            val pathFile = File(pathString)
            if (pathFile.exists()) {
                try {
                    // Use more aggressive deletion
                    pathFile.deleteRecursively()

                    // Double-check and force deletion if needed
                    if (pathFile.exists()) {
                        // Wait a bit and try again
                        Thread.sleep(500)
                        pathFile.deleteRecursively()
                    }

                    LOG.info("Removed directory: $pathString")
                } catch (e: Exception) {
                    LOG.error("Could not remove directory: ${e.message}", e)
                }
            }

            // Step 4: Clean up .git/modules directory (most critical)
            try {
                val gitModulesDir = File(projectBasePath, ".git/modules")
                if (gitModulesDir.exists()) {
                    // Remove the specific submodule directory
                    val submoduleGitDir = File(gitModulesDir, relativePath)
                    if (submoduleGitDir.exists()) {
                        submoduleGitDir.deleteRecursively()
                        LOG.info("Removed git modules directory: ${submoduleGitDir.absolutePath}")
                    }

                    // Also try to remove any parent directories that might be empty
                    cleanupEmptyParentDirectories(submoduleGitDir.parentFile, gitModulesDir)
                }
            } catch (e: Exception) {
                LOG.error("Could not remove git modules directory: ${e.message}", e)
            }

            // Step 5: Clean up .gitmodules file
            try {
                cleanupGitmodulesFile(project, relativePath)
            } catch (e: Exception) {
                LOG.error("Could not clean .gitmodules file: ${e.message}", e)
            }

            // Step 6: Clean up any git configuration references
            try {
                cleanupGitConfig(project, relativePath)
            } catch (e: Exception) {
                LOG.warn("Could not clean git config: ${e.message}")
            }

            LOG.info("Complete cleanup finished for: $relativePath")

        } catch (e: Exception) {
            LOG.error("Error during complete cleanup of existing path: $pathString", e)
            throw e
        }
    }

    /**
     * Clean up empty parent directories in .git/modules
     */
    private fun cleanupEmptyParentDirectories(dir: File?, stopAt: File) {
        if (dir == null || !dir.exists() || dir.absolutePath == stopAt.absolutePath) {
            return
        }

        val files = dir.listFiles()
        if (files != null && files.isEmpty()) {
            LOG.info("Removing empty directory: ${dir.absolutePath}")
            dir.delete()
            cleanupEmptyParentDirectories(dir.parentFile, stopAt)
        }
    }

    /**
     * Clean up git configuration references to the submodule
     */
    private fun cleanupGitConfig(project: Project, relativePath: String) {
        val projectBasePath = project.basePath ?: return
        val git = Git.getInstance()

        try {
            // Remove submodule configuration
            val configHandler = GitLineHandler(project, File(projectBasePath), GitCommand.CONFIG)
            configHandler.addParameters("--remove-section", "submodule.$relativePath")
            git.runCommand(configHandler)
            LOG.info("Removed git config section for submodule: $relativePath")
        } catch (e: Exception) {
            // This is expected if the section doesn't exist
            LOG.debug("Git config section not found for submodule: $relativePath")
        }
    }

    private fun cleanupGitmodulesFile(project: Project, relativePath: String) {
        val projectBasePath = project.basePath ?: return
        val gitmodulesFile = File(projectBasePath, ".gitmodules")

        if (!gitmodulesFile.exists()) {
            LOG.info(".gitmodules file does not exist")
            return
        }

        try {
            val lines = gitmodulesFile.readLines().toMutableList()
            val newLines = mutableListOf<String>()
            var skipSection = false
            var i = 0

            while (i < lines.size) {
                val line = lines[i].trim()

                // Check if this is the start of our submodule section
                if (line.startsWith("[submodule ") && line.contains("\"$relativePath\"")) {
                    skipSection = true
                    LOG.info("Found submodule section to remove: $line")
                } else if (line.startsWith("[submodule ") && skipSection) {
                    // Start of a different submodule section
                    skipSection = false
                    newLines.add(lines[i])
                } else if (line.startsWith("[") && !line.startsWith("[submodule ") && skipSection) {
                    // Start of a different section entirely
                    skipSection = false
                    newLines.add(lines[i])
                } else if (!skipSection) {
                    newLines.add(lines[i])
                }
                i++
            }

            // Write back the cleaned content
            if (newLines.size < lines.size) {
                gitmodulesFile.writeText(newLines.joinToString("\n") + "\n")
                LOG.info("Cleaned .gitmodules file, removed $relativePath section")
            } else {
                LOG.info("No changes needed to .gitmodules file")
            }

        } catch (e: Exception) {
            LOG.error("Error cleaning .gitmodules file", e)
            throw e
        }
    }

    private fun reopenDialogWithSuggestedPath(project: Project, originalPath: String) {
        // Generate a suggested alternative path
        val suggestedPath = generateAlternativePath(originalPath)

        Messages.showInfoMessage(
            project,
            "Please choose a different path for the submodule.\n\n" +
                    "Original path: $originalPath\n" +
                    "Suggested alternative: $suggestedPath",
            "Choose Different Path"
        )

        // You could implement logic here to pre-populate the dialog with the suggested path
        // This would require modifying your AddSubmoduleDialog to accept initial values
    }

    private fun generateAlternativePath(originalPath: String): String {
        val path = Paths.get(originalPath)
        val parent = path.parent
        val fileName = path.fileName.toString()

        // Try adding numbers until we find a non-existing path
        for (i in 1..99) {
            val newPath = if (parent != null) {
                parent.resolve("${fileName}_$i")
            } else {
                Paths.get("${fileName}_$i")
            }

            if (!newPath.toFile().exists()) {
                return newPath.toString()
            }
        }

        // Fallback
        return "${originalPath}_new"
    }

    private fun getRelativePathFromProject(project: Project, absolutePath: String): String {
        val projectBasePath = project.basePath ?: return absolutePath
        val projectPath = Paths.get(projectBasePath).normalize()
        val targetPath = Paths.get(absolutePath).normalize()

        return if (targetPath.startsWith(projectPath)) {
            projectPath.relativize(targetPath).toString().replace("\\", "/")
        } else {
            absolutePath
        }
    }
}