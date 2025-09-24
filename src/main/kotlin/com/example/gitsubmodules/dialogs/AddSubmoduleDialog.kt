package com.example.gitsubmodules.dialogs

import com.example.gitsubmodules.SubmoduleService
import com.example.gitsubmodules.ui.BaseDialog
import com.example.gitsubmodules.ui.ValidationMixin
import com.example.gitsubmodules.utils.AsyncHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class AddSubmoduleDialog(project: Project) : BaseDialog(project), ValidationMixin {

    private val urlField = JBTextField()
    private val pathField = TextFieldWithBrowseButton()
    private val branchComboBox = ComboBox<String>()
    private val submoduleService = project.service<SubmoduleService>()

    private var lastLoadedUrl: String? = null
    private var isLoadingBranches = false
    private var branchLoadTimer: Timer? = null

    companion object {
        private val LOG = thisLogger()
        private val DEFAULT_BRANCHES = arrayOf("main", "master", "develop")
    }

    init {
        title = "Add Git Submodule"
        setupComponents()
        init()

        // Enable OK button updates on validation
        setOKActionEnabled(false)
    }

    private fun setupComponents() {
        setupPathBrowser()
        setupUrlField()
        setupBranchComboBox()

        // Validate on startup to show initial state
        updateValidationState()
    }

    private fun setupPathBrowser() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false).apply {
            title = "Select Parent Directory for Submodule"
            description = "Choose the parent directory where the submodule folder will be created"
            withShowHiddenFiles(false)
            withTreeRootVisible(true)

            // Set project root as the default location for file chooser
            project.basePath?.let { basePath ->
                val projectRoot = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath)
                if (projectRoot != null) {
                    roots = listOf(projectRoot)
                }
            }
        }

        pathField.addBrowseFolderListener(
            "Select Parent Directory",
            "Choose the parent directory where the submodule folder will be created",
            project,
            descriptor
        )

        // Leave path field empty by default - don't set project path
        pathField.text = ""

        // Make the text field expand to show full path
        pathField.textField.columns = 0 // Allow field to expand

        // Add document listener for validation
        pathField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Trigger validation to update OK button
                updateValidationState()
            }
        })

        // Handle browse button selection
        pathField.addActionListener {
            // After selecting a directory, append repo name if URL is already entered
            val selectedPath = pathField.text.trim()
            if (selectedPath.isNotBlank()) {
                val url = urlField.text.trim()
                if (url.isNotBlank() && isValidGitUrl(url)) {
                    val repoName = extractRepoNameFromUrl(url)
                    if (repoName != null) {
                        // Only append if the selected path doesn't already end with repo name
                        if (!selectedPath.endsWith(repoName)) {
                            pathField.text = "$selectedPath${File.separator}$repoName"
                        }
                    }
                }
            }
        }
    }

    private fun setupUrlField() {
        // Add document listener for real-time validation and branch loading
        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Clear any previous error state
                setErrorText(null)

                // Trigger validation to update OK button
                updateValidationState()

                val currentUrl = urlField.text.trim()

                // Check if we should load branches
                if (isValidGitUrl(currentUrl) && currentUrl != lastLoadedUrl) {
                    // Cancel any pending branch load
                    branchLoadTimer?.stop()

                    // Reset loading state if URL changed
                    if (isLoadingBranches) {
                        isLoadingBranches = false
                    }

                    // Schedule new branch load with a small delay to avoid too many requests
                    branchLoadTimer = Timer(500) {
                        loadBranches(currentUrl)
                    }
                    branchLoadTimer?.isRepeats = false
                    branchLoadTimer?.start()
                }
            }
        })
    }

    private fun extractRepoNameFromUrl(url: String): String? {
        return try {
            when {
                // GitHub, GitLab, Bitbucket HTTPS URLs
                url.matches(Regex("^https?://.*/(.*?)(\\.git)?/?$")) -> {
                    url.substringAfterLast("/")
                        .removeSuffix(".git")
                        .removeSuffix("/")
                        .takeIf { it.isNotBlank() }
                }
                // SSH URLs (git@github.com:user/repo.git)
                url.matches(Regex("^git@.*:.*/(.*?)(\\.git)?$")) -> {
                    url.substringAfterLast("/")
                        .removeSuffix(".git")
                        .takeIf { it.isNotBlank() }
                }
                // File URLs
                url.startsWith("file://") -> {
                    url.substringAfterLast("/")
                        .removeSuffix(".git")
                        .takeIf { it.isNotBlank() }
                }
                else -> null
            }
        } catch (e: Exception) {
            LOG.debug("Failed to extract repo name from URL: $url", e)
            null
        }
    }

    private fun isValidGitUrl(url: String): Boolean {
        if (url.isBlank()) return false

        // Basic validation for common Git URL patterns
        val gitUrlPatterns = listOf(
            Regex("^https?://.*\\.git$"),
            Regex("^https?://github\\.com/[^/]+/[^/]+/?$"),
            Regex("^https?://gitlab\\.com/[^/]+/[^/]+/?$"),
            Regex("^https?://bitbucket\\.org/[^/]+/[^/]+/?$"),
            Regex("^git@.*:.*\\.git$"),
            Regex("^git@github\\.com:[^/]+/[^/]+\\.git$"),
            Regex("^ssh://.*\\.git$"),
            Regex("^file://.*$")
        )

        return gitUrlPatterns.any { pattern -> pattern.matches(url) }
    }

    private fun setupBranchComboBox() {
        branchComboBox.isEditable = true
        branchComboBox.model = DefaultComboBoxModel(DEFAULT_BRANCHES)
    }

    private fun loadBranches(url: String) {
        if (isLoadingBranches || url == lastLoadedUrl) return

        LOG.info("Loading branches for URL: $url")
        isLoadingBranches = true
        lastLoadedUrl = url

        AsyncHandler.runOnEDT {
            branchComboBox.model = DefaultComboBoxModel(arrayOf("Loading branches..."))
            branchComboBox.isEnabled = false

            // Show loading feedback in URL field
            urlField.putClientProperty("JComponent.outline", "warning")
        }

        submoduleService.getRemoteBranches(url)
            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS) // Add timeout to prevent hanging
            .thenAccept { branches ->
                AsyncHandler.runOnEDT {
                    updateBranchComboBox(branches)
                    isLoadingBranches = false

                    // Clear loading feedback
                    urlField.putClientProperty("JComponent.outline", null)
                }
            }
            .exceptionally { throwable ->
                LOG.warn("Failed to load branches", throwable)
                AsyncHandler.runOnEDT {
                    // Reset to default branches on error
                    branchComboBox.model = DefaultComboBoxModel(DEFAULT_BRANCHES)
                    branchComboBox.selectedItem = "main"
                    branchComboBox.isEnabled = true
                    isLoadingBranches = false

                    // Clear loading state
                    urlField.putClientProperty("JComponent.outline", null)

                    // Don't show error for branch loading failure - just use defaults
                    LOG.info("Using default branches for URL: $url")
                }
                null
            }
    }

    private fun updateBranchComboBox(branches: List<String>) {
        if (branches.isEmpty()) {
            branchComboBox.model = DefaultComboBoxModel(DEFAULT_BRANCHES)
            branchComboBox.selectedItem = "main"
        } else {
            val branchArray = branches.toTypedArray()
            branchComboBox.model = DefaultComboBoxModel(branchArray)

            // Select appropriate default branch
            when {
                branches.contains("main") -> branchComboBox.selectedItem = "main"
                branches.contains("master") -> branchComboBox.selectedItem = "master"
                branches.contains("develop") -> branchComboBox.selectedItem = "develop"
                else -> branchComboBox.selectedIndex = 0
            }
        }
        branchComboBox.isEnabled = true
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Repository URL:") {
                cell(urlField)
                    .comment("Git repository URL (e.g., https://github.com/user/repo.git)")
                    .focused()
                    .resizableColumn()
                    .applyToComponent {
                        columns = 0 // Allow expansion
                    }
            }
            row("Local path:") {
                cell(pathField)
                    .comment("Directory where the submodule will be created (required)")
                    .resizableColumn()
                    .applyToComponent {
                        // Make the text field component expand
                        textField.columns = 0
                    }
            }
            row("Branch/Tag:") {
                cell(branchComboBox)
                    .comment("Branch, tag, or commit to track (optional)")
                    .resizableColumn()
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val url = getUrl()
        val path = getPath()

        LOG.debug("Validating - URL: '$url', Path: '$path'")

        // Use validation mixin methods
        validateUrl(url, urlField)?.let { return it }
        validatePath(path, pathField.textField)?.let { return it }

        return null
    }

    private fun updateValidationState() {
        ApplicationManager.getApplication().invokeLater {
            val validationInfo = doValidate()
            setOKActionEnabled(validationInfo == null)
            setErrorText(validationInfo?.message)
        }
    }

    override fun dispose() {
        branchLoadTimer?.stop()
        super.dispose()
    }

    override fun onOKAction(): Boolean {
        // Dialog validation passed, action will be handled by the caller
        return true
    }

    fun getUrl(): String = urlField.text.trim()

    fun getPath(): String = pathField.text.trim()

    fun getBranch(): String? {
        val selected = branchComboBox.selectedItem as? String
        return when {
            selected.isNullOrBlank() -> null
            selected == "Loading..." -> null
            else -> selected
        }
    }
}