package io.github.jvterm

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import javax.swing.JButton
import kotlin.random.Random

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow()
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow {
        private val content = JBPanel<JBPanel<*>>().apply {
            val label = JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.number.label", "?"))

            add(label)
            add(JButton(MyMessageBundle.message("toolwindow.MyToolWindow.shuffle.button")).apply {
                addActionListener {
                    label.text = MyMessageBundle.message(
                        "toolwindow.MyToolWindow.number.label", Random(System.currentTimeMillis()).nextInt(1000)
                    )
                }
            })
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
