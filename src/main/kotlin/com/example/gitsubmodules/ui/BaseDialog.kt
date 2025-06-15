package com.example.gitsubmodules.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import javax.swing.JComponent

/**
 * Base class for all plugin dialogs with consistent error handling
 */
abstract class BaseDialog(
    project: Project,
    canBeParent: Boolean = true
) : DialogWrapper(project, canBeParent) {

    companion object {
        private val LOG = Logger.getInstance(BaseDialog::class.java)
    }

    protected val project: Project = project

    override fun doOKAction() {
        LOG.info("OK action triggered in ${this::class.simpleName}")

        try {
            if (onOKAction()) {
                super.doOKAction()
            }
        } catch (e: Exception) {
            LOG.error("Error in OK action", e)
            setErrorText("An error occurred: ${e.message}")
        }
    }

    /**
     * Override this to implement OK action logic
     * @return true if dialog should close, false otherwise
     */
    protected open fun onOKAction(): Boolean = true

    override fun doCancelAction() {
        LOG.info("Cancel action triggered in ${this::class.simpleName}")
        onCancelAction()
        super.doCancelAction()
    }

    /**
     * Override this to implement cancel action logic
     */
    protected open fun onCancelAction() {
        // Default implementation does nothing
    }
}

/**
 * Base validation mixin for consistent validation behavior
 */
interface ValidationMixin {

    fun validateNotEmpty(value: String?, component: JComponent, fieldName: String): ValidationInfo? {
        return if (value.isNullOrBlank()) {
            ValidationInfo("$fieldName is required", component)
        } else {
            null
        }
    }

    fun validateUrl(url: String?, component: JComponent): ValidationInfo? {
        if (url.isNullOrBlank()) {
            return ValidationInfo("URL is required", component)
        }

        // Basic URL validation
        val urlPattern = Regex("^(https?://|git@|ssh://|file://).+")
        return if (!url.matches(urlPattern)) {
            ValidationInfo("Invalid URL format", component)
        } else {
            null
        }
    }

    fun validatePath(path: String?, component: JComponent): ValidationInfo? {
        if (path.isNullOrBlank()) {
            return ValidationInfo("Path is required", component)
        }

        // Check for invalid characters
        val invalidChars = listOf('<', '>', ':', '"', '|', '?', '*')
        val hasInvalidChars = invalidChars.any { path.contains(it) }

        return if (hasInvalidChars) {
            ValidationInfo("Path contains invalid characters", component)
        } else {
            null
        }
    }
}

/**
 * Extension functions for UI components
 */
object UIExtensions {

    fun JComponent.setErrorBorder() {
        // Add error border styling
        putClientProperty("JComponent.outline", "error")
    }

    fun JComponent.clearErrorBorder() {
        // Remove error border styling
        putClientProperty("JComponent.outline", null)
    }
}
