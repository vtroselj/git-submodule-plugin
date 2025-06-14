package com.example.gitsubmodules

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.example.gitsubmodules.model.SubmoduleResult
import com.intellij.openapi.diagnostic.Logger

@Service(Service.Level.PROJECT)
class SubmoduleService(private val project: Project) {

    data class SubmoduleInfo(
        val name: String,
        val path: String,
        val url: String,
        val sha: String?,
        val branch: String?,
        val initialized: Boolean
    )

    fun addSubmodule(url: String, path: String, branch: String? = null): CompletableFuture<SubmoduleResult> {
        val future = CompletableFuture<SubmoduleResult>()
        val repository = getCurrentRepository() ?: run {
            future.complete(SubmoduleResult.Error("No Git repository found in the current project"))
            return future
        }

        // Verify we're working with the main repository
        if (!isMainRepository(repository)) {
            val mainRepo = findMainRepository(GitRepositoryManager.getInstance(project).repositories)
            if (mainRepo != null) {
                LOG.warn("Switching from ${repository.root.path} to main repository ${mainRepo.root.path}")
                return addSubmoduleToRepository(mainRepo, url, path, branch)
            } else {
                LOG.warn("Cannot determine main repository, proceeding with current: ${repository.root.path}")
            }
        }

        return addSubmoduleToRepository(repository, url, path, branch)
    }

    private fun isMainRepository(repository: GitRepository): Boolean {
        val gitDir = File(repository.root.path, ".git")
        // Main repository has .git as a directory, submodules have .git as a file pointing to parent
        return gitDir.isDirectory
    }

    private fun addSubmoduleToRepository(repository: GitRepository, url: String, path: String, branch: String?): CompletableFuture<SubmoduleResult> {
        val future = CompletableFuture<SubmoduleResult>()

        // Validate and normalize the submodule path
        val validationResult = validateSubmodulePath(repository, path)
        if (validationResult.error != null) {
            future.complete(SubmoduleResult.Error(validationResult.error))
            return future
        }

        val relativePath = validationResult.normalizedPath!!

        val parameters = mutableListOf("submodule", "add")
        if (!branch.isNullOrBlank()) {
            parameters.addAll(listOf("-b", branch))
        }
        parameters.addAll(listOf(url, relativePath))

        val commandLine = GeneralCommandLine()
            .withExePath("git")
            .withWorkDirectory(repository.root.path)
            .withParameters(parameters)

        LOG.info("Repository Info:")
        LOG.info("  Root: ${repository.root.path}")
        LOG.info("  Is main repository: ${isMainRepository(repository)}")
        LOG.info("  .git type: ${if (File(repository.root.path, ".git").isDirectory) "directory" else "file"}")
        LOG.info("Executing git command: ${parameters.joinToString(" ")} in directory: ${repository.root.path}")
        LOG.info("Original path: '$path' -> Normalized relative path: '$relativePath'")

        try {
            val process = OSProcessHandler(commandLine)
            val outputCollector = StringBuilder()
            val errorCollector = StringBuilder()

            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    val exitCode = event.exitCode

                    // Switch to EDT for any UI operations
                    ApplicationManager.getApplication().invokeLater {
                        when {
                            exitCode == 0 -> {
                                // Success - refresh VFS and complete with success
                                VirtualFileManager.getInstance().asyncRefresh(null)
                                future.complete(SubmoduleResult.Success)
                                LOG.info("Successfully added submodule: $path from $url")
                            }
                            exitCode == 128 -> {
                                // Git-specific error codes
                                val errorMessage = parseGitError(errorCollector.toString())
                                future.complete(SubmoduleResult.Error(errorMessage))
                                LOG.warn("Git error (128) adding submodule: $errorMessage")
                            }
                            else -> {
                                // Other error codes
                                val errorMessage = if (errorCollector.isNotEmpty()) {
                                    parseGitError(errorCollector.toString())
                                } else {
                                    "Git command failed with exit code: $exitCode"
                                }
                                future.complete(SubmoduleResult.Error(errorMessage))
                                LOG.warn("Failed to add submodule (exit code: $exitCode): $errorMessage")
                            }
                        }
                    }
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    when (outputType) {
                        ProcessOutputTypes.STDOUT -> {
                            outputCollector.append(text)
                            LOG.debug("Git stdout: $text")
                        }
                        ProcessOutputTypes.STDERR -> {
                            errorCollector.append(text)
                            LOG.debug("Git stderr: $text")
                        }
                    }
                }
            })

            // Set a timeout for the process
            process.startNotify()

            // Add timeout handling
            future.orTimeout(30, TimeUnit.SECONDS)
                .exceptionally { throwable ->
                    // Switch to EDT for UI operations
                    ApplicationManager.getApplication().invokeLater {
                        if (!process.isProcessTerminated) {
                            process.destroyProcess()
                        }
                    }
                    when (throwable) {
                        is java.util.concurrent.TimeoutException -> {
                            SubmoduleResult.Error("Operation timed out after 30 seconds")
                        }
                        else -> {
                            SubmoduleResult.Error("Unexpected error: ${throwable.message}")
                        }
                    }
                }

        } catch (e: Exception) {
            LOG.error("Exception while executing git submodule add command", e)
            future.complete(SubmoduleResult.Error("Failed to execute git command: ${e.message}"))
        }

        return future
    }

    fun switchSubmoduleBranch(path: String, branchOrCommit: String): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val repository = getCurrentRepository() ?: run {
            future.complete(false)
            return future
        }

        val submoduleDir = File(repository.root.path, path)
        if (!submoduleDir.exists()) {
            future.complete(false)
            return future
        }

        val commandLine = GeneralCommandLine()
            .withExePath("git")
            .withWorkDirectory(submoduleDir.path)
            .withParameters("checkout", branchOrCommit)

        try {
            val process = OSProcessHandler(commandLine)
            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    val success = event.exitCode == 0
                    // Switch to EDT for any UI operations
                    ApplicationManager.getApplication().invokeLater {
                        if (success) {
                            // Update parent repository to track the new commit
                            updateSubmoduleReference(path, future)
                        } else {
                            future.complete(false)
                        }
                    }
                }
            })
            process.startNotify()
        } catch (e: Exception) {
            future.complete(false)
        }

        return future
    }

    private fun updateSubmoduleReference(path: String, future: CompletableFuture<Boolean>) {
        val repository = getCurrentRepository() ?: run {
            future.complete(false)
            return
        }

        val commandLine = GeneralCommandLine()
            .withExePath("git")
            .withWorkDirectory(repository.root.path)
            .withParameters("add", path)

        try {
            val process = OSProcessHandler(commandLine)
            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    // Switch to EDT for any UI operations
                    ApplicationManager.getApplication().invokeLater {
                        future.complete(event.exitCode == 0)
                    }
                }
            })
            process.startNotify()
        } catch (e: Exception) {
            future.complete(false)
        }
    }

    fun getRemoteBranches(url: String): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()

        val commandLine = GeneralCommandLine()
            .withExePath("git")
            .withParameters("ls-remote", "--heads", url)

        try {
            val process = OSProcessHandler(commandLine)
            val output = StringBuilder()

            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    // Switch to EDT for any UI operations
                    ApplicationManager.getApplication().invokeLater {
                        if (event.exitCode == 0) {
                            val branches = output.toString()
                                .lines()
                                .filter { it.isNotBlank() }
                                .mapNotNull { line ->
                                    val parts = line.split("\t")
                                    if (parts.size >= 2) {
                                        parts[1].removePrefix("refs/heads/")
                                    } else null
                                }
                            future.complete(branches)
                        } else {
                            future.complete(emptyList())
                        }
                    }
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            })
            process.startNotify()
        } catch (e: Exception) {
            future.complete(emptyList())
        }

        return future
    }

    fun updateSubmodules(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val repository = getCurrentRepository() ?: run {
            future.complete(false)
            return future
        }

        // Ensure we're working with the main repository
        val mainRepo = findMainRepository(GitRepositoryManager.getInstance(project).repositories) ?: repository

        val commandLine = GeneralCommandLine()
            .withExePath("git")
            .withWorkDirectory(mainRepo.root.path)
            .withParameters("submodule", "update", "--remote")

        LOG.info("Updating submodules in main repository: ${mainRepo.root.path}")

        try {
            val process = OSProcessHandler(commandLine)
            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    val success = event.exitCode == 0
                    // Switch to EDT for any UI operations
                    ApplicationManager.getApplication().invokeLater {
                        if (success) {
                            VirtualFileManager.getInstance().asyncRefresh(null)
                        }
                        future.complete(success)
                    }
                }
            })
            process.startNotify()
        } catch (e: Exception) {
            future.complete(false)
        }

        return future
    }

    fun initSubmodules(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val repository = getCurrentRepository() ?: run {
            future.complete(false)
            return future
        }

        // Ensure we're working with the main repository
        val mainRepo = findMainRepository(GitRepositoryManager.getInstance(project).repositories) ?: repository

        val commandLine = GeneralCommandLine()
            .withExePath("git")
            .withWorkDirectory(mainRepo.root.path)
            .withParameters("submodule", "init")

        LOG.info("Initializing submodules in main repository: ${mainRepo.root.path}")

        try {
            val process = OSProcessHandler(commandLine)
            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    // Switch to EDT for any UI operations
                    ApplicationManager.getApplication().invokeLater {
                        future.complete(event.exitCode == 0)
                    }
                }
            })
            process.startNotify()
        } catch (e: Exception) {
            future.complete(false)
        }

        return future
    }

    fun getSubmodules(): List<SubmoduleInfo> {
        val repository = getCurrentRepository() ?: return emptyList()

        // Ensure we're reading from the main repository
        val mainRepo = findMainRepository(GitRepositoryManager.getInstance(project).repositories) ?: repository

        LOG.info("Reading submodules from main repository: ${mainRepo.root.path}")

        val submodules = mutableListOf<SubmoduleInfo>()

        try {
            val gitmodulesFile = File(mainRepo.root.path, ".gitmodules")
            if (!gitmodulesFile.exists()) return emptyList()

            val content = gitmodulesFile.readText()
            val submoduleBlocks = content.split(Regex("\\[submodule \"([^\"]+)\"\\]"))
                .drop(1)
                .chunked(2)
                .map { it[0] to it.getOrNull(1).orEmpty() }

            for ((name, block) in submoduleBlocks) {
                val pathMatch = Regex("path\\s*=\\s*(.+)").find(block)
                val urlMatch = Regex("url\\s*=\\s*(.+)").find(block)

                if (pathMatch != null && urlMatch != null) {
                    val path = pathMatch.groupValues[1].trim()
                    val url = urlMatch.groupValues[1].trim()
                    val branchMatch = Regex("branch\\s*=\\s*(.+)").find(block)
                    val branch = branchMatch?.groupValues?.get(1)?.trim()

                    val submoduleDir = File(mainRepo.root.path, path)
                    val initialized = submoduleDir.exists() && File(submoduleDir, ".git").exists()

                    // Get current commit SHA if initialized
                    val sha = if (initialized) {
                        getCurrentSubmoduleCommit(mainRepo.root.path, path)
                    } else null

                    submodules.add(SubmoduleInfo(
                        name = name,
                        path = path,
                        url = url,
                        sha = sha,
                        branch = branch,
                        initialized = initialized
                    ))
                }
            }
        } catch (e: Exception) {
            LOG.error("Error reading submodules", e)
        }

        return submodules
    }

    private fun getCurrentSubmoduleCommit(repositoryPath: String, submodulePath: String): String? {
        return try {
            val commandLine = GeneralCommandLine()
                .withExePath("git")
                .withWorkDirectory(repositoryPath)
                .withParameters("ls-tree", "HEAD", submodulePath)

            val process = OSProcessHandler(commandLine)
            val output = StringBuilder()

            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {}
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            })

            process.startNotify()
            process.waitFor()

            if (process.exitCode == 0) {
                val line = output.toString().trim()
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 3) parts[2] else null
            } else null
        } catch (e: Exception) {
            LOG.error("Error getting submodule commit", e)
            null
        }
    }

    data class PathValidationResult(
        val error: String? = null,
        val normalizedPath: String? = null
    )

    private fun validateSubmodulePath(repository: GitRepository, path: String): PathValidationResult {
        val repositoryRoot = File(repository.root.path)

        // Handle different path formats
        val submodulePath = when {
            File(path).isAbsolute -> {
                // Convert absolute path to relative
                val absolutePath = File(path)
                try {
                    val relativePath = repositoryRoot.toPath().relativize(absolutePath.toPath())
                    File(repositoryRoot, relativePath.toString())
                } catch (e: Exception) {
                    // If relativize fails, the path is likely outside the repository
                    return PathValidationResult(error = "Submodule path '$path' is outside the repository. Use a relative path within the repository.")
                }
            }
            else -> {
                // Already relative path
                File(repositoryRoot, path)
            }
        }

        // Get the normalized relative path
        val relativePath = try {
            repositoryRoot.toPath().relativize(submodulePath.toPath()).toString().replace('\\', '/')
        } catch (e: Exception) {
            return PathValidationResult(error = "Invalid path: $path")
        }

        // Check if the submodule path is within the repository
        val canonicalRepoRoot = try {
            repositoryRoot.canonicalPath
        } catch (e: Exception) {
            return PathValidationResult(error = "Cannot access repository root: ${e.message}")
        }

        val canonicalSubmodulePath = try {
            submodulePath.canonicalPath
        } catch (e: Exception) {
            // Path doesn't exist yet, which is okay for submodules
            submodulePath.absolutePath
        }

        if (!canonicalSubmodulePath.startsWith(canonicalRepoRoot)) {
            return PathValidationResult(error = "Submodule path '$path' is outside the repository. Use a relative path within the repository.")
        }

        // Check if the path already exists and has content
        if (submodulePath.exists()) {
            if (submodulePath.isDirectory) {
                val files = submodulePath.listFiles()
                if (files != null && files.isNotEmpty()) {
                    return PathValidationResult(error = "Directory '$relativePath' already exists and is not empty")
                }
            } else {
                return PathValidationResult(error = "File '$relativePath' already exists")
            }
        }

        // Check if submodule already exists in .gitmodules
        val gitmodulesFile = File(repositoryRoot, ".gitmodules")
        if (gitmodulesFile.exists()) {
            val content = gitmodulesFile.readText()
            if (content.contains("path = $relativePath") ||
                content.contains("path=$relativePath") ||
                content.contains("path = \"$relativePath\"") ||
                content.contains("path=\"$relativePath\"")) {
                return PathValidationResult(error = "Submodule with path '$relativePath' already exists in .gitmodules")
            }
        }

        return PathValidationResult(normalizedPath = relativePath)
    }

    private fun getCurrentRepository(): GitRepository? {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repositories = repositoryManager.repositories

        if (repositories.isEmpty()) {
            LOG.warn("No Git repositories found in project")
            return null
        }

        // Find the main/root repository
        val mainRepository = findMainRepository(repositories)

        LOG.info("Available repositories:")
        repositories.forEach { repo ->
            val isMain = repo == mainRepository
            LOG.info("  ${if (isMain) "[MAIN] " else ""}${repo.root.path}")
        }

        if (mainRepository != null) {
            LOG.info("Using main repository: ${mainRepository.root.path}")
        } else {
            LOG.warn("Could not determine main repository, using first available")
        }

        return mainRepository ?: repositories.first()
    }

    private fun findMainRepository(repositories: List<GitRepository>): GitRepository? {
        val projectBasePath = project.basePath

        // Strategy 1: Find repository that matches project base path exactly
        if (projectBasePath != null) {
            val exactMatch = repositories.find { repo ->
                File(repo.root.path).canonicalPath == File(projectBasePath).canonicalPath
            }
            if (exactMatch != null) {
                LOG.info("Found exact match with project base path: ${exactMatch.root.path}")
                return exactMatch
            }
        }

        // Strategy 2: Find the repository with the shortest path (likely the root)
        val shortestPathRepo = repositories.minByOrNull { it.root.path.length }
        if (shortestPathRepo != null) {
            LOG.info("Using repository with shortest path as main: ${shortestPathRepo.root.path}")
        }

        // Strategy 3: Find repository that contains the most other repositories as subdirectories
        val repoWithMostChildren = repositories.maxByOrNull { parentRepo ->
            repositories.count { childRepo ->
                childRepo != parentRepo &&
                        File(childRepo.root.path).canonicalPath.startsWith(File(parentRepo.root.path).canonicalPath)
            }
        }

        // Strategy 4: Prefer repository that doesn't have .git as a file (not a submodule)
        val nonSubmoduleRepos = repositories.filter { repo ->
            val gitDir = File(repo.root.path, ".git")
            gitDir.isDirectory // .git is a directory, not a file pointing to parent repo
        }

        return when {
            nonSubmoduleRepos.size == 1 -> {
                LOG.info("Found single non-submodule repository: ${nonSubmoduleRepos.first().root.path}")
                nonSubmoduleRepos.first()
            }
            repoWithMostChildren != null && nonSubmoduleRepos.contains(repoWithMostChildren) -> {
                LOG.info("Using repository with most children: ${repoWithMostChildren.root.path}")
                repoWithMostChildren
            }
            shortestPathRepo != null && nonSubmoduleRepos.contains(shortestPathRepo) -> {
                LOG.info("Using shortest path non-submodule repository: ${shortestPathRepo.root.path}")
                shortestPathRepo
            }
            nonSubmoduleRepos.isNotEmpty() -> {
                LOG.info("Using first non-submodule repository: ${nonSubmoduleRepos.first().root.path}")
                nonSubmoduleRepos.first()
            }
            else -> {
                LOG.info("Falling back to shortest path repository: ${shortestPathRepo?.root?.path}")
                shortestPathRepo
            }
        }
    }
}

private fun parseGitError(errorOutput: String): String {
    return when {
        errorOutput.contains("is outside repository") ->
            "The specified path is outside the current repository. Use a relative path within the repository."

        errorOutput.contains("already exists") ->
            "The specified path already exists in the repository"

        errorOutput.contains("not a git repository") ->
            "The current directory is not a Git repository"

        errorOutput.contains("fatal: repository") && errorOutput.contains("does not exist") ->
            "The remote repository URL is invalid or does not exist"

        errorOutput.contains("fatal: unable to access") ->
            "Unable to access the remote repository. Check your network connection and credentials"

        errorOutput.contains("already exists in the index") ->
            "A file or directory with this name already exists in the Git index"

        errorOutput.contains("clone of") && errorOutput.contains("into submodule path") ->
            "Failed to clone the repository into the submodule path"

        errorOutput.contains("permission denied") || errorOutput.contains("Permission denied") ->
            "Permission denied. Check your access rights to the repository"

        errorOutput.contains("authentication failed") || errorOutput.contains("Authentication failed") ->
            "Authentication failed. Check your credentials"

        errorOutput.contains("'.' is outside repository") ->
            "Cannot add submodule in the current directory structure. Check your repository configuration."

        errorOutput.isNotBlank() ->
            errorOutput.trim().lines().first { it.isNotBlank() }

        else ->
            "Unknown error occurred while adding submodule"
    }
}

private val LOG = Logger.getInstance("SubmoduleService")
