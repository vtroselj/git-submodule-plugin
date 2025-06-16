package com.example.gitsubmodules.cache

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cache service for performance optimization
 */
@Service(Service.Level.PROJECT)
class SubmoduleCacheService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(SubmoduleCacheService::class.java)
        private const val CACHE_EXPIRY_MINUTES = 5L
    }

    private data class CacheEntry<T>(
        val value: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean {
            val age = System.currentTimeMillis() - timestamp
            return age > TimeUnit.MINUTES.toMillis(CACHE_EXPIRY_MINUTES)
        }
    }

    // Caches
    private val branchCache = ConcurrentHashMap<String, CacheEntry<List<String>>>()
    private val submoduleInfoCache = ConcurrentHashMap<String, CacheEntry<List<com.example.gitsubmodules.SubmoduleService.SubmoduleInfo>>>()
    private val gitConfigCache = ConcurrentHashMap<String, CacheEntry<String>>()

    init {
        // Register file system listener to invalidate cache on .gitmodules changes

        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file
                    if (file != null && file.name == ".gitmodules") {
                        when (event) {
                            is VFileContentChangeEvent -> {
                                LOG.info("Invalidating submodule cache due to .gitmodules change")
                                invalidateSubmoduleCache()
                            }
                            is VFileCreateEvent -> {
                                invalidateSubmoduleCache()
                            }
                            is VFileDeleteEvent -> {
                                invalidateSubmoduleCache()
                            }
                        }
                    }
                }
            }
        })

        Disposer.register(project, this)
    }

    /**
     * Get cached remote branches for a URL
     */
    fun getCachedBranches(url: String): List<String>? {
        val entry = branchCache[url]
        return if (entry != null && !entry.isExpired()) {
            LOG.debug("Cache hit for branches: $url")
            entry.value
        } else {
            if (entry != null) {
                LOG.debug("Cache expired for branches: $url")
                branchCache.remove(url)
            }
            null
        }
    }

    /**
     * Cache remote branches for a URL
     */
    fun cacheBranches(url: String, branches: List<String>) {
        LOG.debug("Caching ${branches.size} branches for: $url")
        branchCache[url] = CacheEntry(branches)
    }

    /**
     * Get cached submodule information
     */
    fun getCachedSubmodules(repoPath: String): List<com.example.gitsubmodules.SubmoduleService.SubmoduleInfo>? {
        val entry = submoduleInfoCache[repoPath]
        return if (entry != null && !entry.isExpired()) {
            LOG.debug("Cache hit for submodules: $repoPath")
            entry.value
        } else {
            if (entry != null) {
                LOG.debug("Cache expired for submodules: $repoPath")
                submoduleInfoCache.remove(repoPath)
            }
            null
        }
    }

    /**
     * Cache submodule information
     */
    fun cacheSubmodules(repoPath: String, submodules: List<com.example.gitsubmodules.SubmoduleService.SubmoduleInfo>) {
        LOG.debug("Caching ${submodules.size} submodules for: $repoPath")
        submoduleInfoCache[repoPath] = CacheEntry(submodules)
    }

    /**
     * Get cached git config value
     */
    fun getCachedGitConfig(key: String): String? {
        val entry = gitConfigCache[key]
        return if (entry != null && !entry.isExpired()) {
            LOG.debug("Cache hit for git config: $key")
            entry.value
        } else {
            if (entry != null) {
                LOG.debug("Cache expired for git config: $key")
                gitConfigCache.remove(key)
            }
            null
        }
    }

    /**
     * Cache git config value
     */
    fun cacheGitConfig(key: String, value: String) {
        LOG.debug("Caching git config: $key = $value")
        gitConfigCache[key] = CacheEntry(value)
    }

    /**
     * Invalidate all caches
     */
    fun invalidateAll() {
        LOG.info("Invalidating all caches")
        branchCache.clear()
        submoduleInfoCache.clear()
        gitConfigCache.clear()
    }

    /**
     * Invalidate submodule-related caches
     */
    fun invalidateSubmoduleCache() {
        LOG.info("Invalidating submodule cache")
        submoduleInfoCache.clear()
    }

    /**
     * Invalidate branch cache for specific URL
     */
    fun invalidateBranchCache(url: String) {
        LOG.info("Invalidating branch cache for: $url")
        branchCache.remove(url)
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            branchCacheSize = branchCache.size,
            submoduleCacheSize = submoduleInfoCache.size,
            gitConfigCacheSize = gitConfigCache.size,
            totalCacheEntries = branchCache.size + submoduleInfoCache.size + gitConfigCache.size
        )
    }

    data class CacheStats(
        val branchCacheSize: Int,
        val submoduleCacheSize: Int,
        val gitConfigCacheSize: Int,
        val totalCacheEntries: Int
    )

    override fun dispose() {
        invalidateAll()
    }
}