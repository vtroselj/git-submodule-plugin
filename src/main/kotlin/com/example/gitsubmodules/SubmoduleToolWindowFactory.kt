package com.example.gitsubmodules

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

class SubmoduleToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val submoduleService = project.service<SubmoduleService>()
        val panel = createSubmodulePanel(submoduleService)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createSubmodulePanel(submoduleService: SubmoduleService): JPanel {
        val panel = JPanel(BorderLayout())

        val tableModel = DefaultTableModel(
            arrayOf("Name", "Path", "URL", "Branch", "Status"), 0
        )
        val table = JBTable(tableModel)

        // Load submodules
        val submodules = submoduleService.getSubmodules()
        for (submodule in submodules) {
            tableModel.addRow(arrayOf(
                submodule.name,
                submodule.path,
                submodule.url,
                submodule.branch ?: "default",
                if (submodule.initialized) "Initialized" else "Not initialized"
            ))
        }

        panel.add(JScrollPane(table), BorderLayout.CENTER)
        return panel
    }
}
