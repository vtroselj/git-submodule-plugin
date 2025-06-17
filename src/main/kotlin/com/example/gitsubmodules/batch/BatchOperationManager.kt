package com.example.gitsubmodules.batch

import com.example.gitsubmodules.SubmoduleService
import com.example.gitsubmodules.git.GitCommandExecutor
import com.example.gitsubmodules.notifications.NotificationService
import com.example.gitsubmodules.utils.AsyncHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages batch operations on multiple submodules
 */
@Service(Service.Level.PROJECT)
class BatchOperationManager(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(BatchOperationManager::class.java)
    }

    private val submoduleService = project.service<SubmoduleService>()

    data class BatchResult(
        val successful: List<String>,
        val failed: List<Pair<String, String>>, // path to error message
        val totalProcessed: Int
    )

    sealed class BatchOperation {
        data class Update(val paths: List<String>) : BatchOperation()
        data class Init(val paths: List<String>) : BatchOperation()
        data class SwitchBranch(val paths: List<String>, val branch: String) : BatchOperation()
        data class Remove(val paths: List<String>) : BatchOperation()
    }

    /**
     * Execute batch operation on multiple submodules
     */
    fun executeBatch(operation: BatchOperation): CompletableFuture<BatchResult> {
        return when (operation) {
            is BatchOperation.Update -> batchUpdateSubmodules(operation.paths)
            is BatchOperation.Init -> batchInitSubmodules(operation.paths)
            is BatchOperation.SwitchBranch -> batchSwitchBranch(operation.paths, operation.branch)
            is BatchOperation.Remove -> batchRemoveSubmodules(operation.paths)
        }
    }

    /**
     * Update multiple submodules in parallel
     */
    private fun batchUpdateSubmodules(paths: List<String>): CompletableFuture<BatchResult> {
        val future = CompletableFuture<BatchResult>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Updating ${paths.size} Submodules",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val successful = ConcurrentLinkedQueue<String>()
                val failed = ConcurrentLinkedQueue<Pair<String, String>>()
                val processed = AtomicInteger(0)

                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                val repoRoot = project.basePath ?: throw IllegalStateException("Project base path is null")

                val futures = paths.map { path ->
                    CompletableFuture.supplyAsync {
                        try {
                            indicator.text = "Updating: $path"

                            // Execute git submodule update for specific path
                            val executor = GitCommandExecutor(repoRoot)
                            val result = executor.executeSync(
                                "submodule", "update", "--remote", "--recursive", path
                            )

                            if (result.isSuccess) {
                                successful.add(path)
                                LOG.info("Successfully updated submodule: $path")
                            } else {
                                failed.add(path to result.error)
                                LOG.warn("Failed to update submodule: $path - ${result.error}")
                            }
                        } catch (e: Exception) {
                            failed.add(path to (e.message ?: "Unknown error"))
                            LOG.error("Exception updating submodule: $path", e)
                        } finally {
                            val count = processed.incrementAndGet()
                            indicator.fraction = count.toDouble() / paths.size
                        }
                    }
                }

                // Wait for all operations to complete
                CompletableFuture.allOf(*futures.toTypedArray()).join()

                val result = BatchResult(
                    successful = successful.toList(),
                    failed = failed.toList(),
                    totalProcessed = paths.size
                )

                future.complete(result)
            }

            override fun onCancel() {
                future.cancel(true)
            }
        })

        return future
    }

    /**
     * Initialize multiple submodules
     */
    private fun batchInitSubmodules(paths: List<String>): CompletableFuture<BatchResult> {
        val future = CompletableFuture<BatchResult>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Initializing ${paths.size} Submodules",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val successful = ConcurrentLinkedQueue<String>()
                val failed = ConcurrentLinkedQueue<Pair<String, String>>()

                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                val repoRoot = project.basePath ?: throw IllegalStateException("Project base path is null")

                paths.forEachIndexed { index, path ->
                    if (indicator.isCanceled) return

                    indicator.text = "Initializing: $path"
                    indicator.fraction = index.toDouble() / paths.size

                    try {
                        val executor = GitCommandExecutor(repoRoot)

                        // Init specific submodule
                        val initResult = executor.executeSync("submodule", "init", path)
                        if (!initResult.isSuccess) {
                            failed.add(path to initResult.error)
                            return@forEachIndexed
                        }

                        // Update to fetch content
                        val updateResult = executor.executeSync(
                            "submodule", "update", "--recursive", path
                        )

                        if (updateResult.isSuccess) {
                            successful.add(path)
                        } else {
                            failed.add(path to updateResult.error)
                        }
                    } catch (e: Exception) {
                        failed.add(path to (e.message ?: "Unknown error"))
                    }
                }

                future.complete(BatchResult(
                    successful = successful.toList(),
                    failed = failed.toList(),
                    totalProcessed = paths.size
                ))
            }
        })

        return future
    }

    /**
     * Switch branch for multiple submodules
     */
    private fun batchSwitchBranch(paths: List<String>, branch: String): CompletableFuture<BatchResult> {
        val future = CompletableFuture<BatchResult>()

        AsyncHandler.runInBackground(
            project,
            "Switching ${paths.size} Submodules to $branch",
            true
        ) { indicator ->
            val successful = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()

            paths.forEachIndexed { index, path ->
                if (indicator.isCanceled) return@runInBackground BatchResult(successful, failed, index)

                indicator.text = "Switching $path to $branch"
                indicator.fraction = index.toDouble() / paths.size

                try {
                    val result = submoduleService.switchSubmoduleBranch(path, branch).get()
                    if (result) {
                        successful.add(path)
                    } else {
                        failed.add(path to "Failed to switch branch")
                    }
                } catch (e: Exception) {
                    failed.add(path to (e.message ?: "Unknown error"))
                }
            }

            BatchResult(successful, failed, paths.size)
        }.thenAccept { result ->
            future.complete(result)
            showResultNotification(result, "Switch Branch")
        }

        return future
    }

    /**
     * Remove multiple submodules
     */
    private fun batchRemoveSubmodules(paths: List<String>): CompletableFuture<BatchResult> {
        val future = CompletableFuture<BatchResult>()

        AsyncHandler.runInBackground(
            project,
            "Removing ${paths.size} Submodules",
            true
        ) { indicator ->
            val successful = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            val repoRoot = project.basePath ?: throw IllegalStateException("Project base path is null")

            // Get current submodules to verify paths exist
            val existingSubmodules = submoduleService.getSubmodules().map { it.path }.toSet()

            paths.forEachIndexed { index, path ->
                if (indicator.isCanceled) return@runInBackground BatchResult(successful, failed, index)

                indicator.text = "Removing: $path"
                indicator.fraction = index.toDouble() / paths.size

                // Check if submodule actually exists
                if (!existingSubmodules.contains(path)) {
                    LOG.warn("Submodule not found in .gitmodules: $path")
                    failed.add(path to "Submodule not found")
                    return@forEachIndexed
                }

                try {
                    val executor = GitCommandExecutor(repoRoot)

                    // Step 1: Deinitialize submodule (may fail if already deinitialized)
                    try {
                        val deinitResult = executor.executeSync("submodule", "deinit", "-f", path)
                        if (!deinitResult.isSuccess) {
                            LOG.warn("Failed to deinit submodule (may already be deinitialized): $path")
                            // Continue anyway, this might not be critical
                        }
                    } catch (e: Exception) {
                        LOG.warn("Exception during deinit (continuing): ${e.message}")
                    }

                    // Step 2: Remove from index (may fail if not in index)
                    try {
                        val rmResult = executor.executeSync("rm", "--cached", path)
                        if (!rmResult.isSuccess) {
                            // Try alternative approach - remove from .gitmodules directly
                            LOG.warn("Failed to remove from index, will clean .gitmodules: $path")
                        }
                    } catch (e: Exception) {
                        LOG.warn("Exception during rm --cached (continuing): ${e.message}")
                    }

                    // Step 3: Remove .gitmodules entry
                    try {
                        // Remove the submodule section from .gitmodules
                        val configResult = executor.executeSync("config", "--file", ".gitmodules", "--remove-section", "submodule.$path")
                        if (!configResult.isSuccess) {
                            LOG.warn("Failed to remove from .gitmodules config: $path")
                        }
                    } catch (e: Exception) {
                        LOG.error("Failed to update .gitmodules", e)
                    }

                    // Step 4: Stage .gitmodules changes
                    try {
                        executor.executeSync("add", ".gitmodules")
                    } catch (e: Exception) {
                        LOG.warn("Failed to stage .gitmodules: ${e.message}")
                    }

                    // Step 5: Remove directory if exists
                    val submoduleDir = File(repoRoot, path)
                    if (submoduleDir.exists()) {
                        try {
                            submoduleDir.deleteRecursively()
                            LOG.info("Removed submodule directory: $path")
                        } catch (e: Exception) {
                            LOG.warn("Failed to remove directory: $path", e)
                        }
                    }

                    // Step 6: Clean .git/modules (if exists)
                    val gitModulesDir = File(repoRoot, ".git/modules/$path")
                    if (gitModulesDir.exists()) {
                        try {
                            gitModulesDir.deleteRecursively()
                            LOG.info("Removed .git/modules directory: $path")
                        } catch (e: Exception) {
                            LOG.warn("Failed to remove .git/modules directory: $path", e)
                        }
                    }

                    // Step 7: Remove VCS mapping
                    removeVcsMapping(path)

                    successful.add(path)
                    LOG.info("Successfully removed submodule: $path")

                } catch (e: Exception) {
                    LOG.error("Unexpected error removing submodule: $path", e)
                    failed.add(path to (e.message ?: "Unknown error"))
                }
            }

            BatchResult(successful, failed, paths.size)
        }.thenAccept { result ->
            future.complete(result)

            // Invalidate cache after batch removal
            project.service<com.example.gitsubmodules.cache.SubmoduleCacheService>()
                .invalidateSubmoduleCache()

            showResultNotification(result, "Remove")
        }

        return future
    }

    /**
     * Show notification for batch operation results
     */
    private fun showResultNotification(result: BatchResult, operation: String) {
        val message = buildString {
            append("$operation operation completed:\n")
            append("✓ Successful: ${result.successful.size}\n")
            if (result.failed.isNotEmpty()) {
                append("✗ Failed: ${result.failed.size}")
            }
        }

        if (result.failed.isEmpty()) {
            NotificationService.notifySuccess(project, message, "Batch Operation Completed")
        } else {
            NotificationService.notifyWarning(project, message, "Batch Operation Completed with Errors")
        }
    }

    private fun removeVcsMapping(submodulePath: String) {
        try {
            // Try both relative and absolute paths
            val relativePath = submodulePath
            val absolutePath = File(project.basePath ?: return, submodulePath).absolutePath

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
}