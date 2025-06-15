package com.example.gitsubmodules.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/**
 * Utility class for handling async operations with proper thread management
 */
object AsyncHandler {

    /**
     * Executes a task in background with progress indicator
     */
    fun <T> runInBackground(
        project: Project,
        title: String,
        canBeCancelled: Boolean = true,
        task: (ProgressIndicator) -> T
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, canBeCancelled) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = task(indicator)
                    future.complete(result)
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }

            override fun onCancel() {
                future.cancel(true)
            }
        })

        return future
    }

    /**
     * Ensures code runs on EDT
     */
    fun runOnEDT(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            action()
        } else {
            ApplicationManager.getApplication().invokeLater(action)
        }
    }

    /**
     * Ensures code runs on EDT and waits for completion
     */
    fun runOnEDTAndWait(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            action()
        } else {
            ApplicationManager.getApplication().invokeAndWait(action)
        }
    }

    /**
     * Runs write action with proper thread handling
     */
    fun runWriteAction(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction(action)
        }
    }
}
