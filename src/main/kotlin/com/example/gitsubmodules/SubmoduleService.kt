package com.example.gitsubmodules

import com.example.gitsubmodules.git.GitCommandExecutor
import com.example.gitsubmodules.git.GitCommandException
import com.example.gitsubmodules.model.SubmoduleResult
import com.example.gitsubmodules.utils.AsyncHandler
import com.intellij.openapi.components.Service
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
        return CompletableFuture.supplyAsync {
            try {
                // Use a temporary directory for ls-remote
                val executor = GitCommandExecutor(System.getProperty("user.dir"))
                val lines = executor.executeAndParseLines("ls-remote", "--heads", url)

                lines.mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) {
                        parts[1].removePrefix("refs/heads/")
                    } else null
                }
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
                indicator.text = "Updating submodules..."
                val executor = GitCommandExecutor(repository.root.path)
                val result = executor.executeSync(
                    "submodule", "update", "--remote", "--recursive",
                    indicator = indicator,
                    timeout = 60 // Longer timeout for updates
                )

                if (result.isSuccess) {
                    AsyncHandler.runOnEDT {
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }
                }

                result.isSuccess
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
                indicator.text = "Initializing submodules..."
                val executor = GitCommandExecutor(repository.root.path)

                // First init
                indicator.text2 = "Running git submodule init..."
                val initResult = executor.executeSync("submodule", "init", indicator = indicator)

                if (!initResult.isSuccess) {
                    LOG.warn("Submodule init failed: ${initResult.error}")
                    return@runInBackground false
                }

                // Then update
                indicator.text2 = "Running git submodule update..."
                val updateResult = executor.executeSync(
                    "submodule", "update", "--recursive",
                    indicator = indicator,
                    timeout = 60
                )

                if (updateResult.isSuccess) {
                    AsyncHandler.runOnEDT {
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }
                }

                updateResult.isSuccess
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

        return try {
            val gitmodulesFile = File(repository.root.path, ".gitmodules")
            if (!gitmodulesFile.exists()) return emptyList()

            parseGitmodulesFile(gitmodulesFile, repository)
        } catch (e: Exception) {
            LOG.error("Error reading submodules", e)
            emptyList()
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
