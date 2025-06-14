package com.example.gitsubmodules.dialogs

import com.example.gitsubmodules.SubmoduleService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class SwitchSubmoduleBranchDialog(
    private val project: Project,
    private val submoduleService: SubmoduleService
) : DialogWrapper(project) {

    private val submoduleComboBox = ComboBox<String>()
    private val branchComboBox = ComboBox<String>()
    private val customBranchField = JBTextField()
    private val submodules = submoduleService.getSubmodules().filter { it.initialized }

    init {
        title = "Switch Submodule Branch/Commit"
        setupSubmoduleSelection()
        init()
    }

    private fun setupSubmoduleSelection() {
        // Populate submodule dropdown
        val submoduleNames = submodules.map { "${it.name} (${it.path})" }.toTypedArray()
        submoduleComboBox.model = DefaultComboBoxModel(submoduleNames)

        // Setup branch loading when submodule changes
        submoduleComboBox.addActionListener(object : ActionListener {
            override fun actionPerformed(e: ActionEvent) {
                loadBranchesForSelectedSubmodule()
            }
        })

        branchComboBox.isEditable = true
        branchComboBox.model = DefaultComboBoxModel(arrayOf("main", "master", "develop"))

        // Load branches for initially selected submodule
        if (submodules.isNotEmpty()) {
            loadBranchesForSelectedSubmodule()
        }
    }

    private fun loadBranchesForSelectedSubmodule() {
        val selectedIndex = submoduleComboBox.selectedIndex
        if (selectedIndex < 0 || selectedIndex >= submodules.size) return

        val submodule = submodules[selectedIndex]
        branchComboBox.model = DefaultComboBoxModel(arrayOf("Loading..."))
        branchComboBox.isEnabled = false

        submoduleService.getRemoteBranches(submodule.url)
            .thenAccept { branches ->
                javax.swing.SwingUtilities.invokeLater {
                    if (branches.isNotEmpty()) {
                        val branchArray = branches.toTypedArray()
                        branchComboBox.model = DefaultComboBoxModel(branchArray)

                        // Select current branch if available
                        if (submodule.branch != null && branches.contains(submodule.branch)) {
                            branchComboBox.selectedItem = submodule.branch
                        } else {
                            when {
                                branches.contains("main") -> branchComboBox.selectedItem = "main"
                                branches.contains("master") -> branchComboBox.selectedItem = "master"
                                else -> branchComboBox.selectedIndex = 0
                            }
                        }
                    } else {
                        branchComboBox.model = DefaultComboBoxModel(arrayOf("main", "master", "develop"))
                        branchComboBox.selectedItem = submodule.branch ?: "main"
                    }
                    branchComboBox.isEnabled = true
                }
            }
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Submodule:") {
                cell(submoduleComboBox)
                    .comment("Select the submodule to switch")
                    .resizableColumn()
            }
            row("Branch/Tag:") {
                cell(branchComboBox)
                    .comment("Select branch or tag from dropdown")
                    .resizableColumn()
            }
            row("Or custom commit/branch:") {
                cell(customBranchField)
                    .comment("Enter custom branch name, tag, or commit SHA")
                    .resizableColumn()
            }
        }
    }

    fun getSelectedSubmodulePath(): String {
        val selectedIndex = submoduleComboBox.selectedIndex
        return if (selectedIndex >= 0 && selectedIndex < submodules.size) {
            submodules[selectedIndex].path
        } else ""
    }

    fun getBranchOrCommit(): String {
        val customBranch = customBranchField.text?.trim()
        return if (!customBranch.isNullOrBlank()) {
            customBranch
        } else {
            (branchComboBox.selectedItem as? String)?.takeIf { it != "Loading..." } ?: ""
        }
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        if (submoduleComboBox.selectedIndex < 0) {
            return com.intellij.openapi.ui.ValidationInfo("Please select a submodule", submoduleComboBox)
        }

        val branchOrCommit = getBranchOrCommit()
        if (branchOrCommit.isEmpty()) {
            return com.intellij.openapi.ui.ValidationInfo("Please select or enter a branch/commit", branchComboBox)
        }

        return null
    }
}
