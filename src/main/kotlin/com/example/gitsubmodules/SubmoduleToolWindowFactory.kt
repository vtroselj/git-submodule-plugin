package com.example.gitsubmodules

import com.example.gitsubmodules.batch.BatchOperationManager
import com.example.gitsubmodules.cache.SubmoduleCacheService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SubmoduleToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SubmoduleToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class SubmoduleToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val submoduleService = project.service<SubmoduleService>()
    private val batchManager = project.service<BatchOperationManager>()
    private val cacheService = project.service<SubmoduleCacheService>()

    private val tableModel = SubmoduleTableModel()
    private val table = JBTable(tableModel)

    init {
        setupUI()
        refreshSubmodules()
    }

    private fun setupUI() {
        // Configure table
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.setShowGrid(true)
        table.rowHeight = 25

        // Add context menu
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        // Add to scroll pane
        val scrollPane = ScrollPaneFactory.createScrollPane(table)

        // Create toolbar
        val toolbar = createToolbar()

        // Layout
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Status bar
        val statusBar = createStatusBar()
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(AddSubmoduleToolbarAction())
            add(InitSelectedAction())
            add(UpdateSelectedAction())
            add(RemoveSelectedAction())
            addSeparator()
            add(ShowCacheStatsAction())
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "GitSubmodulesToolbar",
            group,
            true
        )
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun createStatusBar(): JComponent {
        val statusLabel = JLabel("Ready")
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
            add(statusLabel, BorderLayout.WEST)
        }
        return panel
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
        }

        val menu = JPopupMenu().apply {
            add(JMenuItem("Update Selected").apply {
                addActionListener { updateSelectedSubmodules() }
            })
            add(JMenuItem("Switch Branch...").apply {
                addActionListener { switchBranchForSelected() }
            })
            addSeparator()
            add(JMenuItem("Open in Terminal").apply {
                addActionListener { openInTerminal() }
            })
            add(JMenuItem("Browse").apply {
                addActionListener { browseSelected() }
            })
            addSeparator()
            add(JMenuItem("Remove Selected").apply {
                addActionListener { removeSelectedSubmodules() }
            })
        }

        menu.show(table, e.x, e.y)
    }

    fun refreshSubmodules() {
        SwingUtilities.invokeLater {
            tableModel.clearAll()

            val submodules = submoduleService.getSubmodules()
            submodules.forEach { submodule ->
                tableModel.addSubmodule(submodule)
            }

            // Update cache stats in status bar
            updateCacheStats()
        }
    }

    private fun updateCacheStats() {
        // Implementation for updating cache stats in status bar
        // Currently just a placeholder
    }

    private fun getSelectedPaths(): List<String> {
        return table.selectedRows.map { row ->
            tableModel.getValueAt(row, SubmoduleTableModel.COLUMN_PATH) as String
        }
    }

    private fun updateSelectedSubmodules() {
        val paths = getSelectedPaths()
        if (paths.isEmpty()) return

        batchManager.executeBatch(
            BatchOperationManager.BatchOperation.Update(paths)
        ).thenAccept {
            refreshSubmodules()
        }
    }

    private fun switchBranchForSelected() {
        val paths = getSelectedPaths()
        if (paths.isEmpty()) return

        // Show branch selection dialog
        val branch = JOptionPane.showInputDialog(
            this,
            "Enter branch name:",
            "Switch Branch",
            JOptionPane.QUESTION_MESSAGE
        )

        if (!branch.isNullOrBlank()) {
            batchManager.executeBatch(
                BatchOperationManager.BatchOperation.SwitchBranch(paths, branch)
            ).thenAccept {
                refreshSubmodules()
            }
        }
    }

    private fun removeSelectedSubmodules() {
        val paths = getSelectedPaths()
        if (paths.isEmpty()) return

        val result = JOptionPane.showConfirmDialog(
            this,
            "Remove ${paths.size} selected submodule(s)?",
            "Confirm Removal",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result == JOptionPane.YES_OPTION) {
            batchManager.executeBatch(
                BatchOperationManager.BatchOperation.Remove(paths)
            ).thenAccept {
                refreshSubmodules()
            }
        }
    }

    private fun openInTerminal() {
        // Implementation depends on OS
    }

    private fun browseSelected() {
        // Open file browser
    }

    // Table model
    private class SubmoduleTableModel : DefaultTableModel() {
        companion object {
            const val COLUMN_NAME = 0
            const val COLUMN_PATH = 1
            const val COLUMN_URL = 2
            const val COLUMN_BRANCH = 3
            const val COLUMN_STATUS = 4
            const val COLUMN_SHA = 5

            private val COLUMN_NAMES = arrayOf(
                "Name", "Path", "URL", "Branch", "Status", "SHA"
            )
        }

        init {
            COLUMN_NAMES.forEach { addColumn(it) }
        }

        override fun isCellEditable(row: Int, column: Int) = false

        fun addSubmodule(info: SubmoduleService.SubmoduleInfo) {
            addRow(arrayOf(
                info.name,
                info.path,
                info.url,
                info.branch ?: "default",
                if (info.initialized) "Initialized" else "Not initialized",
                info.sha?.take(7) ?: "N/A"
            ))
        }

        fun clearAll() {
            rowCount = 0
        }
    }

    // Actions
    private inner class RefreshAction : AnAction(
        "Refresh",
        "Refresh submodule list",
        AllIcons.Actions.Refresh
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            refreshSubmodules()
        }
    }

    private inner class AddSubmoduleToolbarAction : AnAction(
        "Add Submodule",
        "Add new submodule",
        AllIcons.General.Add
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            ActionManager.getInstance()
                .getAction("AddSubmodule")
                ?.actionPerformed(e)
        }
    }

    private inner class InitSelectedAction : AnAction(
        "Initialize Selected",
        "Initialize selected submodules",
        AllIcons.Actions.Execute
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val paths = getSelectedPaths()
            if (paths.isNotEmpty()) {
                batchManager.executeBatch(
                    BatchOperationManager.BatchOperation.Init(paths)
                ).thenAccept {
                    refreshSubmodules()
                }
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount > 0
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private inner class UpdateSelectedAction : AnAction(
        "Update Selected",
        "Update selected submodules",
        AllIcons.Actions.Download
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            updateSelectedSubmodules()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount > 0
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private inner class RemoveSelectedAction : AnAction(
        "Remove Selected",
        "Remove selected submodules",
        AllIcons.General.Remove
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            removeSelectedSubmodules()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount > 0
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private inner class ShowCacheStatsAction : AnAction(
        "Cache Stats",
        "Show cache statistics",
        AllIcons.Actions.Properties
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val stats = cacheService.getCacheStats()
            JOptionPane.showMessageDialog(
                this@SubmoduleToolWindowPanel,
                """
                Cache Statistics:
                
                Branch Cache: ${stats.branchCacheSize} entries
                Submodule Cache: ${stats.submoduleCacheSize} entries
                Git Config Cache: ${stats.gitConfigCacheSize} entries
                Total: ${stats.totalCacheEntries} entries
                """.trimIndent(),
                "Cache Statistics",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
}
