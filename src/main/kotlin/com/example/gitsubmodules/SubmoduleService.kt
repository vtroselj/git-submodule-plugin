package com.example.gitsubmodules

import com.example.gitsubmodules.cache.SubmoduleCacheService
import com.example.gitsubmodules.events.SubmoduleTopics
import com.example.gitsubmodules.git.GitCommandExecutor
import com.example.gitsubmodules.git.GitCommandException
import com.example.gitsubmodules.model.SubmoduleResult
import com.example.gitsubmodules.utils.AsyncHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class SubmoduleService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(SubmoduleService::class.java)
    }

    private val cacheService = project.service<SubmoduleCacheService>()

    data class SubmoduleInfo(
        val name: String,
        val path: String,
        val url: String,
        val sha: String?,
        val branch: String?,
        val initialized: Boolean
    )

    /**
     * Add a new submodule with proper error handling and progress reporting
     */
    fun addSubmodule(
        url: String,
        path: String,
        branch: String? = null
    ): CompletableFuture<SubmoduleResult> {
        return AsyncHandler.runInBackground(
            project,
            "Adding Git Submodule",
            canBeCancelled = true
        ) { indicator ->
            indicator.text = "Validating repository..."

            val repository = getMainRepository() ?: return@runInBackground SubmoduleResult.Error(
                "No Git repository found in the current project"
            )

            indicator.text = "Validating submodule path..."
            val validationResult = validateSubmodulePath(repository, path)
            if (validationResult.error != null) {
                return@runInBackground SubmoduleResult.Error(validationResult.error)
            }

            val relativePath = validationResult.normalizedPath!!
            indicator.text = "Adding submodule: $relativePath"

            try {
                val executor = GitCommandExecutor(repository.root.path)
                val parameters = buildList {
                    add("submodule")
                    add("add")
                    if (!branch.isNullOrBlank()) {
                        add("-b")
                        add(branch)
                    }
                    add(url)
                    add(relativePath)
                }

                val result = executor.executeSync(
                    *parameters.toTypedArray(),
                    indicator = indicator
                )

                if (result.isSuccess) {
                    indicator.text = "Refreshing project files..."
                    AsyncHandler.runOnEDT {
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }
                    // Invalidate cache after adding submodule
                    cacheService.invalidateSubmoduleCache()

                    // Notify listeners about change
                    project.messageBus.syncPublisher(SubmoduleTopics.SUBMODULE_CHANGE_TOPIC).submodulesChanged()

                    SubmoduleResult.Success
                } else {
                    SubmoduleResult.Error(parseGitError(result.error))
                }
            } catch (e: Exception) {
                LOG.error("Failed to add submodule", e)
                SubmoduleResult.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    /**
     * Get remote branches for a repository URL
     */
    fun getRemoteBranches(url: String): CompletableFuture<List<String>> {
        // Check cache first
        val cached = cacheService.getCachedBranches(url)
        if (cached != null) {
            LOG.debug("Using cached branches for: $url")
            return CompletableFuture.completedFuture(cached)
        }

        return CompletableFuture.supplyAsync {
            try {
                // Use a temporary directory for ls-remote
                val executor = GitCommandExecutor(System.getProperty("user.dir"))
                val lines = executor.executeAndParseLines("ls-remote", "--heads", url)

                val branches = lines.mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) {
                        parts[1].removePrefix("refs/heads/")
                    } else null
                }

                // Cache the results
                cacheService.cacheBranches(url, branches)
                branches
            } catch (e: Exception) {
                LOG.warn("Failed to fetch remote branches", e)
                emptyList()
            }
        }
    }

    /**
     * Switch submodule to a different branch or commit
     */
    fun switchSubmoduleBranch(
        submodulePath: String,
        branchOrCommit: String
    ): CompletableFuture<Boolean> {
        return AsyncHandler.runInBackground(
            project,
            "Switching Submodule Branch",
            canBeCancelled = true
        ) { indicator ->
            indicator.text = "Finding repository..."

            val repository = getMainRepository() ?: return@runInBackground false
            val submoduleDir = File(repository.root.path, submodulePath)

            if (!submoduleDir.exists()) {
                LOG.warn("Submodule directory does not exist: $submodulePath")
                return@runInBackground false
            }

            try {
                indicator.text = "Checking out $branchOrCommit..."
                val executor = GitCommandExecutor(submoduleDir.path)
                val checkoutResult = executor.executeSync("checkout", branchOrCommit, indicator = indicator)

                if (!checkoutResult.isSuccess) {
                    LOG.warn("Failed to checkout: ${checkoutResult.error}")
                    return@runInBackground false
                }

                indicator.text = "Updating parent repository..."
                val parentExecutor = GitCommandExecutor(repository.root.path)
                val addResult = parentExecutor.executeSync("add", submodulePath, indicator = indicator)

                addResult.isSuccess
            } catch (e: Exception) {
                LOG.error("Failed to switch submodule branch", e)
                false
            }
        }
    }

    /**
     * Update all submodules
     */
    fun updateSubmodules(): CompletableFuture<Boolean> {
        return AsyncHandler.runInBackground(
            project,
            "Updating Submodules",
            canBeCancelled = true
        ) { indicator ->
            indicator.text = "Finding repository..."
            val repository = getMainRepository() ?: return@runInBackground false

            try {
                indicator.text = "Reading submodule configurations..."
                val submodules = getSubmodules()
                val executor = GitCommandExecutor(repository.root.path)
                var allSuccess = true

                // Update each submodule based on its branch configuration
                submodules.forEachIndexed { index, submodule ->
                    indicator.text = "Updating ${submodule.name}..."
                    indicator.fraction = index.toDouble() / submodules.size

                    val result = if (submodule.branch != null) {
                        // Update with --remote to get latest from the tracked branch
                        executor.executeSync(
                            "submodule", "update", "--remote", "--recursive", submodule.path,
                            indicator = indicator,
                            timeout = 60
                        )
                    } else {
                        // Update to the commit specified in the parent repository
                        executor.executeSync(
                            "submodule", "update", "--recursive", submodule.path,
                            indicator = indicator,
                            timeout = 60
                        )
                    }

                    if (!result.isSuccess) {
                        LOG.warn("Failed to update submodule ${submodule.path}: ${result.error}")
                        allSuccess = false
                    } else if (submodule.branch != null) {
                        // After updating, ensure we're on the right branch
                        val submoduleDir = File(repository.root.path, submodule.path)
                        if (submoduleDir.exists()) {
                            val submoduleExecutor = GitCommandExecutor(submoduleDir.path)
                            val checkoutResult = submoduleExecutor.executeSync(
                                "checkout", submodule.branch,
                                indicator = indicator
                            )
                            if (!checkoutResult.isSuccess) {
                                LOG.warn("Failed to checkout branch ${submodule.branch} in ${submodule.path}")
                            }
                        }
                    }
                }

                if (allSuccess) {
                    AsyncHandler.runOnEDT {
                        VirtualFileManager.getInstance().asyncRefresh(null)

                        // Notify listeners about change
                        project.messageBus.syncPublisher(SubmoduleTopics.SUBMODULE_CHANGE_TOPIC).submodulesChanged()
                    }
                    // Invalidate cache after update
                    cacheService.invalidateSubmoduleCache()
                }

                allSuccess
            } catch (e: Exception) {
                LOG.error("Failed to update submodules", e)
                false
            }
        }
    }

    /**
     * Initialize all submodules
     */
    fun initSubmodules(): CompletableFuture<Boolean> {
        return AsyncHandler.runInBackground(
            project,
            "Initializing Submodules",
            canBeCancelled = true
        ) { indicator ->
            indicator.text = "Finding repository..."
            val repository = getMainRepository() ?: return@runInBackground false

            try {
                indicator.text = "Reading submodule configurations..."
                val submodules = getSubmodules()
                val executor = GitCommandExecutor(repository.root.path)

                // First init all submodules
                indicator.text2 = "Running git submodule init..."
                val initResult = executor.executeSync("submodule", "init", indicator = indicator)

                if (!initResult.isSuccess) {
                    LOG.warn("Submodule init failed: ${initResult.error}")
                    return@runInBackground false
                }

                // Then update each submodule with branch tracking if specified
                indicator.text2 = "Updating submodules with branch configuration..."
                var allSuccess = true

                submodules.forEachIndexed { index, submodule ->
                    indicator.text = "Updating ${submodule.name}..."
                    indicator.fraction = index.toDouble() / submodules.size

                    if (submodule.branch != null) {
                        // If branch is specified, update with --remote to track the branch
                        val updateResult = executor.executeSync(
                            "submodule", "update", "--remote", "--recursive", submodule.path,
                            indicator = indicator,
                            timeout = 60
                        )

                        if (!updateResult.isSuccess) {
                            LOG.warn("Failed to update submodule ${submodule.path} to branch ${submodule.branch}: ${updateResult.error}")
                            allSuccess = false
                        } else {
                            // Checkout the branch in the submodule
                            val submoduleDir = File(repository.root.path, submodule.path)
                            if (submoduleDir.exists()) {
                                val submoduleExecutor = GitCommandExecutor(submoduleDir.path)
                                val checkoutResult = submoduleExecutor.executeSync(
                                    "checkout", submodule.branch,
                                    indicator = indicator
                                )
                                if (!checkoutResult.isSuccess) {
                                    LOG.warn("Failed to checkout branch ${submodule.branch} in ${submodule.path}")
                                }
                            }
                        }
                    } else {
                        // No branch specified, update normally
                        val updateResult = executor.executeSync(
                            "submodule", "update", "--recursive", submodule.path,
                            indicator = indicator,
                            timeout = 60
                        )

                        if (!updateResult.isSuccess) {
                            LOG.warn("Failed to update submodule ${submodule.path}: ${updateResult.error}")
                            allSuccess = false
                        }
                    }
                }

                if (allSuccess) {
                    AsyncHandler.runOnEDT {
                        VirtualFileManager.getInstance().asyncRefresh(null)

                        // Notify listeners about change
                        project.messageBus.syncPublisher(SubmoduleTopics.SUBMODULE_CHANGE_TOPIC).submodulesChanged()
                    }
                    // Invalidate cache after init
                    cacheService.invalidateSubmoduleCache()
                }

                allSuccess
            } catch (e: Exception) {
                LOG.error("Failed to initialize submodules", e)
                false
            }
        }
    }

    /**
     * Get list of all submodules in the project
     */
    fun getSubmodules(): List<SubmoduleInfo> {
        val repository = getMainRepository() ?: return emptyList()

        // Check cache first
        val cached = cacheService.getCachedSubmodules(repository.root.path)
        if (cached != null) {
            LOG.debug("Using cached submodules for: ${repository.root.path}")
            return cached
        }

        return try {
            val gitmodulesFile = File(repository.root.path, ".gitmodules")
            if (!gitmodulesFile.exists()) return emptyList()

            val submodules = parseGitmodulesFile(gitmodulesFile, repository)

            // Cache the results
            cacheService.cacheSubmodules(repository.root.path, submodules)
            submodules
        } catch (e: Exception) {
            LOG.error("Error reading submodules", e)
            emptyList()
        }
    }

    /**
     * Remove a submodule safely
     */
    fun removeSubmodule(path: String): CompletableFuture<SubmoduleResult> {
        return AsyncHandler.runInBackground(
            project,
            "Removing Submodule: $path",
            canBeCancelled = true
        ) { indicator ->
            indicator.text = "Validating submodule..."

            val repository = getMainRepository() ?: return@runInBackground SubmoduleResult.Error(
                "No Git repository found"
            )

            // Check if submodule exists
            val submodules = getSubmodules()
            val submoduleExists = submodules.any { it.path == path }

            if (!submoduleExists) {
                return@runInBackground SubmoduleResult.Error("Submodule not found: $path")
            }

            try {
                val executor = GitCommandExecutor(repository.root.path)

                indicator.text = "Deinitializing submodule..."
                // Deinitialize (ignore errors as it might already be deinitialized)
                executor.executeSync("submodule", "deinit", "-f", path, timeout = 10)

                indicator.text = "Removing from Git index..."
                // Remove from index (ignore errors as it might not be in index)
                executor.executeSync("rm", "--cached", path, timeout = 10)

                indicator.text = "Updating .gitmodules..."
                // Remove from .gitmodules
                val configResult = executor.executeSync(
                    "config", "--file", ".gitmodules",
                    "--remove-section", "submodule.$path",
                    timeout = 10
                )

                if (configResult.isSuccess) {
                    // Stage .gitmodules changes
                    executor.executeSync("add", ".gitmodules")
                }

                indicator.text = "Cleaning up directories..."
                // Remove physical directory
                val submoduleDir = File(repository.root.path, path)
                if (submoduleDir.exists()) {
                    submoduleDir.deleteRecursively()
                }

                // Clean .git/modules
                val gitModulesDir = File(repository.root.path, ".git/modules/$path")
                if (gitModulesDir.exists()) {
                    gitModulesDir.deleteRecursively()
                }

                // Remove VCS mapping
                removeVcsMapping(path)

                // Refresh and invalidate cache
                AsyncHandler.runOnEDT {
                    VirtualFileManager.getInstance().asyncRefresh(null)

                    // Notify listeners about change
                    project.messageBus.syncPublisher(SubmoduleTopics.SUBMODULE_CHANGE_TOPIC).submodulesChanged()
                }
                cacheService.invalidateSubmoduleCache()

                LOG.info("Successfully removed submodule: $path")
                SubmoduleResult.Success

            } catch (e: Exception) {
                LOG.error("Failed to remove submodule: $path", e)
                SubmoduleResult.Error("Failed to remove submodule: ${e.message}")
            }
        }
    }

    private fun parseGitmodulesFile(
        gitmodulesFile: File,
        repository: GitRepository
    ): List<SubmoduleInfo> {
        val content = gitmodulesFile.readText()
        val submodules = mutableListOf<SubmoduleInfo>()

        // Simple regex-based parser for .gitmodules
        val submodulePattern = Regex(
            """\[submodule "([^"]+)"]\s*path\s*=\s*(.+?)\s*url\s*=\s*(.+?)(?:\s*branch\s*=\s*(.+?))?(?=\[|$)""",
            RegexOption.DOT_MATCHES_ALL
        )

        submodulePattern.findAll(content).forEach { match ->
            val (name, path, url, branch) = match.destructured
            val trimmedPath = path.trim()
            val trimmedUrl = url.trim()
            val trimmedBranch = branch.trim().takeIf { it.isNotEmpty() }

            val submoduleDir = File(repository.root.path, trimmedPath)
            val initialized = submoduleDir.exists() && File(submoduleDir, ".git").exists()

            val sha = if (initialized) {
                getCurrentSubmoduleCommit(repository.root.path, trimmedPath)
            } else null

            submodules.add(
                SubmoduleInfo(
                    name = name,
                    path = trimmedPath,
                    url = trimmedUrl,
                    sha = sha,
                    branch = trimmedBranch,
                    initialized = initialized
                )
            )
        }

        return submodules
    }

    private fun getCurrentSubmoduleCommit(repositoryPath: String, submodulePath: String): String? {
        return try {
            val executor = GitCommandExecutor(repositoryPath)
            val lines = executor.executeAndParseLines("ls-tree", "HEAD", submodulePath)

            lines.firstOrNull()?.let { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 3) parts[2] else null
            }
        } catch (e: Exception) {
            LOG.debug("Error getting submodule commit", e)
            null
        }
    }

    private fun getMainRepository(): GitRepository? {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repositories = repositoryManager.repositories

        if (repositories.isEmpty()) {
            LOG.warn("No Git repositories found in project")
            return null
        }

        // Find the main repository (not a submodule)
        return repositories.find { repo ->
            File(repo.root.path, ".git").isDirectory
        } ?: repositories.firstOrNull()
    }

    data class PathValidationResult(
        val error: String? = null,
        val normalizedPath: String? = null
    )

    private fun validateSubmodulePath(repository: GitRepository, path: String): PathValidationResult {
        val repoRoot = repository.root.path

        // Normalize the path
        val normalizedPath = try {
            val absolutePath = if (Paths.get(path).isAbsolute) {
                Paths.get(path)
            } else {
                Paths.get(repoRoot).resolve(path)
            }

            Paths.get(repoRoot).relativize(absolutePath).toString().replace('\\', '/')
        } catch (e: Exception) {
            return PathValidationResult(error = "Invalid path: ${e.message}")
        }

        // Check if path already exists in .gitmodules
        val gitmodulesFile = File(repoRoot, ".gitmodules")
        if (gitmodulesFile.exists() && gitmodulesFile.readText().contains("path = $normalizedPath")) {
            return PathValidationResult(error = "Submodule already exists at path: $normalizedPath")
        }

        // Check if directory exists and is not empty
        val targetDir = File(repoRoot, normalizedPath)
        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            return PathValidationResult(error = "Directory is not empty: $normalizedPath")
        }

        return PathValidationResult(normalizedPath = normalizedPath)
    }

    private fun removeVcsMapping(submodulePath: String) {
        try {
            val repository = getMainRepository() ?: return

            // Try both relative and absolute paths
            val relativePath = submodulePath
            val absolutePath = File(repository.root.path, submodulePath).absolutePath

            AsyncHandler.runWriteAction {
                val vcsManager = com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl.getInstance(project)
                val currentMappings = vcsManager.directoryMappings.toMutableList()

                // Remove mapping - check for both absolute and relative paths
                val removed = currentMappings.removeIf { mapping ->
                    mapping.directory == absolutePath ||
                            mapping.directory == relativePath ||
                            mapping.directory.endsWith("/$relativePath") ||
                            mapping.directory.endsWith("\\$relativePath")
                }

                if (removed) {
                    // Use setDirectoryMappings
                    vcsManager.setDirectoryMappings(currentMappings)

                    // Force immediate save for removal operations
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.application.ApplicationManager.getApplication().saveSettings()
                        project.save()
                    }

                    LOG.info("Removed VCS mapping for: $absolutePath")
                } else {
                    LOG.warn("No VCS mapping found to remove for: $absolutePath")
                }
            }
        } catch (e: Exception) {
            LOG.error("Error removing VCS mapping", e)
        }
    }

    private fun parseGitError(errorOutput: String): String {
        val firstLine = errorOutput.lines().firstOrNull { it.isNotBlank() } ?: "Unknown error"

        return when {
            errorOutput.contains("already exists", ignoreCase = true) ->
                "Path already exists in the repository"
            errorOutput.contains("not a git repository", ignoreCase = true) ->
                "Not a Git repository"
            errorOutput.contains("could not read from remote", ignoreCase = true) ->
                "Could not access remote repository. Check URL and credentials"
            errorOutput.contains("permission denied", ignoreCase = true) ->
                "Permission denied. Check your access rights"
            else -> firstLine
        }
    }
}