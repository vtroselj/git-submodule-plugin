package com.example.gitsubmodules.dialogs

import com.example.gitsubmodules.SubmoduleService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AddSubmoduleDialog(private val project: Project) : DialogWrapper(project) {

    private val urlField = JBTextField()
    private val pathField = TextFieldWithBrowseButton()
    private val branchComboBox = ComboBox<String>()
    private val submoduleService = project.service<SubmoduleService>()

    companion object {
        private val LOG = thisLogger()
    }

    init {
        title = "Add Git Submodule"
        setupBranchLoading()
        setupPathBrowser()
        init()
    }

    private fun setupPathBrowser() {
        // Create a file chooser descriptor for directories only
        val descriptor = FileChooserDescriptor(
            false, // chooseFiles
            true,  // chooseFolders
            false, // chooseJars
            false, // chooseJarsAsFiles
            false, // chooseJarContents
            false  // chooseMultiple
        ).apply {
            title = "Select Submodule Directory"
            description = "Choose the directory where the submodule should be created"
            withShowHiddenFiles(false)
            withTreeRootVisible(true)
        }

        // Set up the browse button action
        pathField.addBrowseFolderListener(
            "Select Submodule Directory",
            "Choose the directory where the submodule should be created",
            project,
            descriptor
        )

        // Set the project root as the initial directory
        val projectPath = project.basePath
        if (projectPath != null) {
            pathField.text = projectPath
        }
    }

    private fun setupBranchLoading() {
        branchComboBox.isEditable = true
        branchComboBox.model = DefaultComboBoxModel(arrayOf("master", "main"))

        urlField.addActionListener(object : ActionListener {
            override fun actionPerformed(e: ActionEvent) {
                loadBranches()
            }
        })

        // Also load branches when URL field loses focus
        urlField.addPropertyChangeListener("focusOwner") {
            if (!urlField.hasFocus()) {
                loadBranches()
            }
        }
    }

    private fun loadBranches() {
        val url = urlField.text?.trim()
        if (url.isNullOrBlank()) return

        LOG.info("Loading branches for URL: $url")
        branchComboBox.model = DefaultComboBoxModel(arrayOf("Loading..."))
        branchComboBox.isEnabled = false

        submoduleService.getRemoteBranches(url)
            .thenAccept { branches ->
                javax.swing.SwingUtilities.invokeLater {
                    LOG.info("Loaded ${branches.size} branches: $branches")
                    if (branches.isNotEmpty()) {
                        val branchArray = branches.toTypedArray()
                        branchComboBox.model = DefaultComboBoxModel(branchArray)
                        // Set default to main or master if available
                        when {
                            branches.contains("main") -> branchComboBox.selectedItem = "main"
                            branches.contains("master") -> branchComboBox.selectedItem = "master"
                            else -> branchComboBox.selectedIndex = 0
                        }
                    } else {
                        branchComboBox.model = DefaultComboBoxModel(arrayOf("main", "master"))
                        branchComboBox.selectedItem = "main"
                    }
                    branchComboBox.isEnabled = true
                }
            }
            .exceptionally { throwable ->
                LOG.warn("Failed to load branches", throwable)
                javax.swing.SwingUtilities.invokeLater {
                    branchComboBox.model = DefaultComboBoxModel(arrayOf("main", "master"))
                    branchComboBox.selectedItem = "main"
                    branchComboBox.isEnabled = true
                }
                null
            }
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Repository URL:") {
                cell(urlField)
                    .comment("Git repository URL (https://github.com/user/repo.git)")
                    .focused()
                    .resizableColumn()
            }
            row("Local path:") {
                cell(pathField)
                    .comment("Local directory path for the submodule (click browse to select)")
                    .resizableColumn()
            }
            row("Branch/Tag:") {
                cell(branchComboBox)
                    .comment("Branch, tag, or commit to track (leave empty for default)")
                    .resizableColumn()
            }
        }
    }

    fun getUrl(): String {
        val url = urlField.text.orEmpty().trim()
        LOG.info("getUrl() returning: '$url'")
        return url
    }

    fun getPath(): String {
        val path = pathField.text.orEmpty().trim()
        LOG.info("getPath() returning: '$path'")
        return path
    }

    fun getBranch(): String? {
        val selected = branchComboBox.selectedItem as? String
        val branch = if (selected.isNullOrBlank() || selected == "Loading...") null else selected
        LOG.info("getBranch() returning: '$branch'")
        return branch
    }

    override fun doValidate(): ValidationInfo? {
        val url = getUrl()
        val path = getPath()

        LOG.info("doValidate() called - URL: '$url', Path: '$path'")

        if (url.isEmpty()) {
            LOG.info("Validation failed: URL is empty")
            return ValidationInfo("Repository URL is required", urlField)
        }
        if (path.isEmpty()) {
            LOG.info("Validation failed: Path is empty")
            return ValidationInfo("Local path is required", pathField)
        }

        // Additional validation to check if the path is valid
        try {
            val file = File(path)
            if (file.exists() && !file.isDirectory) {
                LOG.info("Validation failed: Path exists but is not a directory")
                return ValidationInfo("Path must be a directory", pathField)
            }
        } catch (e: Exception) {
            LOG.info("Validation failed: Invalid path format", e)
            return ValidationInfo("Invalid path format", pathField)
        }

        LOG.info("Validation passed")
        return null
    }

    override fun doOKAction() {
        LOG.info("doOKAction() called")
        super.doOKAction()
    }
}