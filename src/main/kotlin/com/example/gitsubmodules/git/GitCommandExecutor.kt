package com.example.gitsubmodules.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Centralized Git command executor with consistent error handling
 */
class GitCommandExecutor(private val workingDirectory: String) {

    companion object {
        private val LOG = Logger.getInstance(GitCommandExecutor::class.java)
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
    }

    data class GitResult(
        val exitCode: Int,
        val output: String,
        val error: String,
        val isSuccess: Boolean = exitCode == 0
    )

    /**
     * Execute a git command synchronously
     */
    fun executeSync(
        vararg parameters: String,
        timeout: Long = DEFAULT_TIMEOUT_SECONDS,
        indicator: ProgressIndicator? = null
    ): GitResult {
        val commandLine = createCommandLine(*parameters)
        LOG.info("Executing git command: ${parameters.joinToString(" ")} in $workingDirectory")

        val handler = OSProcessHandler(commandLine)
        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> outputBuilder.append(text)
                    ProcessOutputTypes.STDERR -> errorBuilder.append(text)
                }
            }
        })

        handler.startNotify()

        // Handle cancellation
        if (indicator != null) {
            while (!handler.isProcessTerminated && !indicator.isCanceled) {
                Thread.sleep(100)
            }
            if (indicator.isCanceled && !handler.isProcessTerminated) {
                handler.destroyProcess()
                throw ProcessCanceledException()
            }
        }

        val terminated = handler.waitFor(TimeUnit.SECONDS.toMillis(timeout))
        if (!terminated) {
            handler.destroyProcess()
            throw GitCommandTimeoutException("Git command timed out after $timeout seconds")
        }

        val exitCode = handler.exitCode ?: -1
        val result = GitResult(exitCode, outputBuilder.toString(), errorBuilder.toString())

        if (!result.isSuccess) {
            LOG.warn("Git command failed with exit code $exitCode: ${errorBuilder.toString().trim()}")
        }

        return result
    }

    /**
     * Execute a git command asynchronously
     */
    fun executeAsync(
        vararg parameters: String,
        timeout: Long = DEFAULT_TIMEOUT_SECONDS
    ): CompletableFuture<GitResult> {
        return CompletableFuture.supplyAsync {
            executeSync(*parameters, timeout = timeout)
        }.orTimeout(timeout + 5, TimeUnit.SECONDS) // Add buffer to timeout
    }

    /**
     * Execute git command and parse output lines
     */
    fun executeAndParseLines(
        vararg parameters: String,
        timeout: Long = DEFAULT_TIMEOUT_SECONDS,
        indicator: ProgressIndicator? = null
    ): List<String> {
        val result = executeSync(*parameters, timeout = timeout, indicator = indicator)
        if (!result.isSuccess) {
            throw GitCommandException("Git command failed: ${result.error}")
        }
        return result.output.lines().filter { it.isNotBlank() }
    }

    private fun createCommandLine(vararg parameters: String): GeneralCommandLine {
        return GeneralCommandLine()
            .withExePath("git")
            .withWorkDirectory(workingDirectory)
            .withParameters(*parameters)
            .withCharset(Charsets.UTF_8)
    }
}

class GitCommandException(message: String) : Exception(message)
class GitCommandTimeoutException(message: String) : Exception(message)
class ProcessCanceledException : Exception("Process was cancelled")
