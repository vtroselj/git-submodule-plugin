package com.example.gitsubmodules.dialogs

import com.example.gitsubmodules.SubmoduleService
import com.example.gitsubmodules.ui.BaseDialog
import com.example.gitsubmodules.ui.ValidationMixin
import com.example.gitsubmodules.utils.AsyncHandler
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
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class AddSubmoduleDialog(project: Project) : BaseDialog(project), ValidationMixin {

    private val urlField = JBTextField()
    private val pathField = TextFieldWithBrowseButton()
    private val branchComboBox = ComboBox<String>()
    private val submoduleService = project.service<SubmoduleService>()

    private var lastLoadedUrl: String? = null
    private var isLoadingBranches = false

    companion object {
        private val LOG = thisLogger()
        private val DEFAULT_BRANCHES = arrayOf("main", "master", "develop")
    }

    init {
        title = "Add Git Submodule"
        setupComponents()
        init()
    }

    private fun setupComponents() {
        setupPathBrowser()
        setupUrlField()
        setupBranchComboBox()
    }

    private fun setupPathBrowser() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false).apply {
            title = "Select Submodule Directory"
            description = "Choose the directory where the submodule should be created"
            withShowHiddenFiles(false)
            withTreeRootVisible(true)
        }

        pathField.addBrowseFolderListener(
            "Select Submodule Directory",
            "Choose the directory where the submodule should be created",
            project,
            descriptor
        )

        // Set default to project root
        project.basePath?.let { pathField.text = it }
    }

    private fun setupUrlField() {
        // Add document listener for real-time validation
        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Clear any previous error state
                setErrorText(null)
            }
        })

        // Load branches when URL field loses focus
        urlField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                val currentUrl = urlField.text.trim()
                if (currentUrl.isNotBlank() && currentUrl != lastLoadedUrl) {
                    loadBranches(currentUrl)
                }
            }
        })
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
            branchComboBox.model = DefaultComboBoxModel(arrayOf("Loading..."))
            branchComboBox.isEnabled = false
        }

        submoduleService.getRemoteBranches(url)
            .thenAccept { branches ->
                AsyncHandler.runOnEDT {
                    updateBranchComboBox(branches)
                    isLoadingBranches = false
                }
            }
            .exceptionally { throwable ->
                LOG.warn("Failed to load branches", throwable)
                AsyncHandler.runOnEDT {
                    branchComboBox.model = DefaultComboBoxModel(DEFAULT_BRANCHES)
                    branchComboBox.selectedItem = "main"
                    branchComboBox.isEnabled = true
                    isLoadingBranches = false
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
            }
            row("Local path:") {
                cell(pathField)
                    .comment("Directory where the submodule will be created")
                    .resizableColumn()
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