/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.appMessages
import io.github.ketraterm.app.config.KetraTermConfig
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.app.config.SuggestionKeyBindings
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.ui.swing.host.SwingDialogRequest
import io.github.ketraterm.ui.swing.host.SwingMessageDialogs
import io.github.ketraterm.ui.swing.settings.SwingPromptDecoration
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAutomaticMode
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import io.github.ketraterm.workspace.TerminalProfileRegistry
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DisposableHandle
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.util.*
import java.util.concurrent.ExecutionException
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * A highly polished, IDE-style settings dialog featuring a clean sidebar and flat form layouts.
 */
internal class SettingsDialog(
    parent: JFrame,
    private val settings: KetraTermSettings,
    profileRegistry: TerminalProfileRegistry,
    private val resetCompletionLearning: () -> Deferred<Unit>,
) : JDialog(parent, appMessages.text("settings.title"), true) {
    private var learningResetObserver: DisposableHandle? = null
    private val resetLearningButton =
        JButton(appMessages.text("settings.resetLearning")).apply {
            addActionListener { confirmLearningReset() }
        }
    private val learningResetStatus = JLabel()
    private val cardLayout = CardLayout()

    // Opaque panel is critical for CardLayout to clear previous artifacts correctly
    private val cardPanel =
        object : JPanel(cardLayout) {
            override fun getPreferredSize(): Dimension = components.firstOrNull { it.isVisible }?.preferredSize ?: super.getPreferredSize()
        }.apply {
            isOpaque = true
            background = Chrome.surface
        }
    private val pageScrollPane =
        JScrollPane(cardPanel).apply {
            isOpaque = true
            viewport.isOpaque = true
            viewport.background = Chrome.surface
            border = BorderFactory.createMatteBorder(0, 1, 0, 0, Chrome.border)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    private val sidebarPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = Chrome.surface
            border = EmptyBorder(8, 0, 8, 0)
            preferredSize = Dimension(180, -1)
        }

    private val categories = mutableListOf<CategoryLabel>()
    private val applyButton = JButton(appMessages.text("button.apply"))
    private val model = SettingsModel(settings, profileRegistry)
    private var saving = false

    // Factory Helpers
    private fun createTextField(
        initialValue: String,
        width: Int,
    ) = JTextField(initialValue).apply { applySizing(this, width) }

    private fun createSpinner(
        initialValue: Int,
        min: Int,
        max: Int,
        step: Int,
        width: Int,
    ) = JSpinner(SpinnerNumberModel(initialValue, min, max, step)).apply {
        applySizing(this, width)
    }

    private fun createFloatSpinner(
        initialValue: Float,
        min: Double,
        max: Double,
        step: Double,
        width: Int,
    ) = JSpinner(SpinnerNumberModel(initialValue.toDouble(), min, max, step)).apply {
        applySizing(this, width)
    }

    private fun <T> createComboBox(
        items: Array<T>,
        initialValue: T,
        width: Int,
    ) = JComboBox(items).apply {
        selectedItem = initialValue
        applySizing(this, width)
    }

    private val availableProfiles = profileRegistry.availableProfiles()
    private val matchedProfile = findProfile(settings.config.shellPath)
    private val isCustomShell = matchedProfile == null

    // Form Controls - Application
    private val shellPathCombo =
        JComboBox<Any>().apply {
            availableProfiles.forEach { addItem(it) }
            addItem(appMessages.text("settings.custom"))
            selectedItem = matchedProfile ?: appMessages.text("settings.custom")
            renderer =
                object : DefaultListCellRenderer() {
                    private val profileIcons = ProfileIcons()

                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component {
                        val label =
                            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                        if (value is TerminalProfile) {
                            label.text = value.displayName
                            label.icon = profileIcons.icon(value.kind)
                        } else if (value is String) {
                            label.text = value
                            if (value == appMessages.text("settings.custom")) {
                                label.icon = profileIcons.icon(TerminalProfileKind.DEFAULT)
                            } else {
                                label.icon = null
                            }
                        }
                        return label
                    }
                }
            applySizing(this, 220)
        }
    private val customShellField = createTextField(if (isCustomShell) settings.config.shellPath else "", 140) // Will be wrapped with button
    private val startDirectoryField = createTextField(settings.config.startDirectory, 140) // Will be wrapped with button
    private val startupCommandField =
        createTextField(settings.config.startupCommand, 140).apply {
            toolTipText = appMessages.text("settings.startup.tooltip")
        }
    private val audibleBellCheckbox = JCheckBox(appMessages.text("settings.audibleBell"), settings.config.audibleBell)
    private val visualBellCheckbox = JCheckBox(appMessages.text("settings.visualBell"), settings.config.visualBell)
    private val desktopNotificationsCheckbox =
        JCheckBox(appMessages.text("settings.desktopNotifications"), settings.config.desktopNotificationsEnabled)
    private val commandFinishedAlertsCheckbox =
        JCheckBox(appMessages.text("settings.commandFinishedAlerts"), settings.config.commandFinishedAlertsEnabled)
    private val commandFinishedAlertThresholdSpinner =
        createSpinner(
            settings.config.commandFinishedAlertThresholdSeconds,
            KetraTermConfig.COMMAND_FINISHED_ALERT_THRESHOLD_MIN,
            KetraTermConfig.COMMAND_FINISHED_ALERT_THRESHOLD_MAX,
            1,
            80,
        ).apply { name = "commandFinishedAlertThresholdSeconds" }

    // Form Controls - Appearance
    private val fontFamilyCombo =
        run {
            val monospaceFamilies = SwingSettings.getMonospaceFontFamilies().toMutableList()
            val currentFamily = settings.config.fontFamily
            if (currentFamily !in monospaceFamilies) {
                monospaceFamilies.add(0, currentFamily)
            }
            createComboBox(monospaceFamilies.toTypedArray(), currentFamily, 220)
        }
    private val fontSizeSpinner =
        createSpinner(settings.config.fontSize, KetraTermConfig.FONT_SIZE_MIN, KetraTermConfig.FONT_SIZE_MAX, 1, 80)
    private val lineHeightSpinner =
        createFloatSpinner(
            settings.config.lineHeight,
            KetraTermConfig.LINE_HEIGHT_MIN.toDouble(),
            KetraTermConfig.LINE_HEIGHT_MAX.toDouble(),
            0.1,
            80,
        )
    private val columnsSpinner =
        createSpinner(settings.config.columns, KetraTermConfig.COLUMNS_MIN, KetraTermConfig.COLUMNS_MAX, 1, 80)
    private val rowsSpinner =
        createSpinner(settings.config.rows, KetraTermConfig.ROWS_MIN, KetraTermConfig.ROWS_MAX, 1, 80)
    private val scrollbackSpinner =
        createSpinner(settings.config.scrollbackLines, KetraTermConfig.SCROLLBACK_MIN, KetraTermConfig.SCROLLBACK_MAX, 100, 80)
    private val promptDecorationCombo =
        createComboBox(
            arrayOf(SwingPromptDecoration.GUTTER, SwingPromptDecoration.DIVIDER, SwingPromptDecoration.NONE),
            settings.config.promptDecoration,
            150,
        ).apply {
            toolTipText = appMessages.text("settings.prompt.tooltip")
            renderer =
                object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                            text =
                                when (value) {
                                    SwingPromptDecoration.GUTTER -> appMessages.text("settings.prompt.gutter")
                                    SwingPromptDecoration.DIVIDER -> appMessages.text("settings.prompt.divider")
                                    SwingPromptDecoration.NONE -> appMessages.text("settings.prompt.none")
                                    else -> ""
                                }
                        }
                }
        }
    private val stickyCommandHeadersCheckbox =
        JCheckBox(appMessages.text("settings.stickyCommandHeaders"), settings.config.stickyCommandHeadersEnabled).apply {
            toolTipText = appMessages.text("settings.stickyCommandHeaders.tooltip")
        }
    private val themeCombo = createComboBox(TerminalTheme.entries.toTypedArray(), settings.theme, 220)

    // Form Controls - Behavior
    private val treatAmbiguousCheckbox = JCheckBox(appMessages.text("settings.ambiguousWidth"), settings.config.treatAmbiguousAsWide)
    private val useSystemFallbackCheckbox = JCheckBox(appMessages.text("settings.fontFallback"), settings.config.useSystemFallbackFonts)
    private val pasteOnMiddleClickCheckbox = JCheckBox(appMessages.text("settings.middleClickPaste"), settings.config.pasteOnMiddleClick)
    private val pasteSanitizationCombo =
        createComboBox(
            PASTE_SANITIZATION_OPTIONS.toTypedArray(),
            PASTE_SANITIZATION_OPTIONS.first { it.policy == settings.config.pasteControlPolicy },
            220,
        ).apply {
            toolTipText = appMessages.text("settings.paste.tooltip")
        }
    private val shellRequestResizeWindowCheckbox =
        JCheckBox(appMessages.text("settings.allowResize"), settings.config.shellRequestResizeWindow)
    private val shellRequestWindowManipulationCheckbox =
        JCheckBox(appMessages.text("settings.allowWindowManipulation"), settings.config.shellRequestWindowManipulation)

    private val smartSuggestionsCheckbox =
        JCheckBox(appMessages.text("settings.smartSuggestions"), settings.config.smartSuggestionsEnabled)

    private enum class PopupMode(
        private val labelKey: String,
    ) {
        OFF("settings.popupMode.off"),
        MANUAL("settings.popupMode.manual"),
        AUTOMATIC("settings.popupMode.automatic"),
        ;

        override fun toString(): String = appMessages.text(labelKey)
    }

    private val popupModeCombo =
        createComboBox(
            PopupMode.entries.toTypedArray(),
            when {
                !settings.config.popupSuggestionsEnabled -> PopupMode.OFF
                settings.config.shellSuggestionsEnabled -> PopupMode.AUTOMATIC
                else -> PopupMode.MANUAL
            },
            170,
        ).apply { name = "popupSuggestionMode" }
    private val popupAcceptKeys =
        ShortcutEditor(
            settings.config.suggestionKeyBindings.effectivePopupAccept(settings.config.acceptSelectedSuggestionWithEnter),
            appMessages.text("settings.popupAcceptKeys"),
        ).apply { name = "popupAcceptKeys" }
    private val inlineAcceptKeys =
        ShortcutEditor(settings.config.suggestionKeyBindings.inlineAccept, appMessages.text("settings.inlineAcceptKeys"))
            .apply { name = "inlineAcceptKeys" }
    private val suggestionTriggerKeys =
        ShortcutEditor(settings.config.suggestionKeyBindings.trigger, appMessages.text("settings.suggestionTriggerKeys"))
            .apply { name = "suggestionTriggerKeys" }
    private val inlineSuggestionsCheckbox =
        JCheckBox(appMessages.text("settings.inlineSuggestions"), settings.config.inlineSuggestionsEnabled)
    private val automaticCommandNamesCheckbox =
        JCheckBox(
            appMessages.text("settings.suggestions.commandNames"),
            settings.config.shellSuggestionAutomaticMode == SwingShellSuggestionAutomaticMode.ALWAYS,
        )
    private val persistentSuggestionLearningCheckbox =
        JCheckBox(appMessages.text("settings.persistSuggestionLearning"), settings.config.persistentSuggestionLearningEnabled)
    private val scrollOnOutputCheckbox = JCheckBox(appMessages.text("settings.scrollOnOutput"), settings.config.scrollOnOutput)
    private val showForegroundProcessNameCheckbox =
        JCheckBox(appMessages.text("settings.processTitle"), settings.config.showForegroundProcessName)
    private val cursorBlinkSpinner =
        createSpinner(settings.config.cursorBlinkMillis, KetraTermConfig.CURSOR_BLINK_MIN, KetraTermConfig.CURSOR_BLINK_MAX, 50, 70)
    private val cursorShapeCombo =
        createComboBox(arrayOf("block", "underline", "beam"), settings.config.cursorShape.lowercase(Locale.ROOT), 150).apply {
            renderer =
                object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): Component =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                            text =
                                when (value) {
                                    "block" -> appMessages.text("settings.cursor.block")
                                    "underline" -> appMessages.text("settings.cursor.underline")
                                    "beam" -> appMessages.text("settings.cursor.beam")
                                    else -> ""
                                }
                        }
                }
        }

    // Form Controls - Security
    private val clipboardWriteCombo = createClipboardPermissionCombo(settings.config.clipboardWrite)
    private val clipboardReadCombo = createClipboardPermissionCombo(settings.config.clipboardRead)
    private val clipboardMaxDecodedBytesSpinner = createSpinner(settings.config.clipboardMaxDecodedBytes, 0, Int.MAX_VALUE, 1024, 150)
    private val titlePermissionCheckbox =
        JCheckBox(appMessages.text("settings.allowTitle"), settings.config.titlePermission == TerminalTitlePermission.ALLOW)

    init {
        size = Dimension(820, 600)
        setLocationRelativeTo(parent)
        layout = BorderLayout()
        isUndecorated = false
        defaultCloseOperation = DISPOSE_ON_CLOSE

        // Setup Main Container
        val splitPane =
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = Chrome.surface
                add(sidebarPanel, BorderLayout.WEST)
                add(pageScrollPane, BorderLayout.CENTER)
            }

        add(splitPane, BorderLayout.CENTER)
        add(buildFooterPanel(), BorderLayout.SOUTH)

        // Add Pages
        addPage("settings.general", buildGeneralPanel())
        addPage("settings.appearance", buildAppearancePanel())
        addPage("settings.behavior", buildBehaviorPanel())
        addPage("settings.suggestions", buildSuggestionsPanel())
        addPage("settings.security", buildSecurityPanel())

        selectCategory(categories.first().categoryId)

        updateApplyButtonState()

        val updateApplyState = {
            updateApplyButtonState()
        }

        registerChangeListener(shellPathCombo, updateApplyState)
        registerChangeListener(customShellField, updateApplyState)
        registerChangeListener(startDirectoryField, updateApplyState)
        registerChangeListener(startupCommandField, updateApplyState)
        registerChangeListener(audibleBellCheckbox, updateApplyState)
        registerChangeListener(visualBellCheckbox, updateApplyState)
        registerChangeListener(desktopNotificationsCheckbox, updateApplyState)
        registerChangeListener(commandFinishedAlertsCheckbox, updateApplyState)
        registerChangeListener(commandFinishedAlertThresholdSpinner, updateApplyState)
        registerChangeListener(fontFamilyCombo, updateApplyState)
        registerChangeListener(fontSizeSpinner, updateApplyState)
        registerChangeListener(lineHeightSpinner, updateApplyState)
        registerChangeListener(columnsSpinner, updateApplyState)
        registerChangeListener(rowsSpinner, updateApplyState)
        registerChangeListener(scrollbackSpinner, updateApplyState)
        registerChangeListener(themeCombo, updateApplyState)
        registerChangeListener(treatAmbiguousCheckbox, updateApplyState)
        registerChangeListener(useSystemFallbackCheckbox, updateApplyState)
        registerChangeListener(clipboardWriteCombo, updateApplyState)
        registerChangeListener(clipboardReadCombo, updateApplyState)
        registerChangeListener(clipboardMaxDecodedBytesSpinner, updateApplyState)
        registerChangeListener(titlePermissionCheckbox, updateApplyState)
        registerChangeListener(pasteOnMiddleClickCheckbox, updateApplyState)
        registerChangeListener(pasteSanitizationCombo, updateApplyState)
        registerChangeListener(shellRequestResizeWindowCheckbox, updateApplyState)
        registerChangeListener(shellRequestWindowManipulationCheckbox, updateApplyState)
        registerChangeListener(smartSuggestionsCheckbox, updateApplyState)
        registerChangeListener(popupModeCombo, updateApplyState)
        for (field in listOf(popupAcceptKeys, inlineAcceptKeys, suggestionTriggerKeys)) {
            registerChangeListener(field, updateApplyState)
        }
        registerChangeListener(inlineSuggestionsCheckbox, updateApplyState)
        registerChangeListener(automaticCommandNamesCheckbox, updateApplyState)
        smartSuggestionsCheckbox.addActionListener { updateAutomaticSuggestionModes() }
        popupModeCombo.addActionListener { updateAutomaticSuggestionModes() }
        inlineSuggestionsCheckbox.addActionListener { updateAutomaticSuggestionModes() }
        updateAutomaticSuggestionModes()
        registerChangeListener(persistentSuggestionLearningCheckbox, updateApplyState)
        registerChangeListener(scrollOnOutputCheckbox, updateApplyState)
        registerChangeListener(showForegroundProcessNameCheckbox, updateApplyState)
        registerChangeListener(cursorBlinkSpinner, updateApplyState)
        registerChangeListener(cursorShapeCombo, updateApplyState)
        registerChangeListener(promptDecorationCombo, updateApplyState)
        registerChangeListener(stickyCommandHeadersCheckbox, updateApplyState)
        sidebarPanel.preferredSize =
            Dimension(maxOf(180, sidebarPanel.minimumSize.width), sidebarPanel.preferredSize.height)
        pack()
        size = Dimension(maxOf(820, width), 600)
        minimumSize = size
        setLocationRelativeTo(parent)
    }

    private fun applySizing(
        component: JComponent,
        width: Int,
    ) {
        component.preferredSize = Dimension(width, 26)
    }

    private fun applyMinimumSizing(
        component: JComponent,
        width: Int,
    ) {
        val naturalSize = component.preferredSize
        component.preferredSize = Dimension(maxOf(width, naturalSize.width), maxOf(26, naturalSize.height))
    }

    private fun addPage(
        categoryId: String,
        panel: JPanel,
    ) {
        val categoryLabel = CategoryLabel(categoryId).apply { name = categoryId }
        categories.add(categoryLabel)
        sidebarPanel.add(categoryLabel)

        // Container with padding to hold the content panel
        val contentContainer =
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = Chrome.surface
                name = "$categoryId.page"
                border = EmptyBorder(0, 16, 16, 16)
                add(panel, BorderLayout.NORTH)
            }

        cardPanel.add(contentContainer, categoryId)

        categoryLabel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    selectCategory(categoryId)
                }
            },
        )
    }

    private fun selectCategory(categoryId: String) {
        categories.forEach { it.updateState(it.categoryId == categoryId) }
        cardLayout.show(cardPanel, categoryId)
        cardPanel.revalidate()
        pageScrollPane.viewport.viewPosition = Point(0, 0)
    }

    private fun buildGeneralPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader(appMessages.text("settings.shellSession")))
        val projectSection = createSectionPanel()
        addFormRow(projectSection, 0, appMessages.text("settings.defaultShell"), shellPathCombo)

        val customShellWrapper =
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(customShellField, BorderLayout.CENTER)
                val browseBtn =
                    JButton(appMessages.text("button.browse")).apply {
                        applyMinimumSizing(this, 72)
                        addActionListener {
                            val chooser =
                                JFileChooser(customShellField.text).apply {
                                    fileSelectionMode = JFileChooser.FILES_ONLY
                                }
                            if (chooser.showOpenDialog(this@SettingsDialog) == JFileChooser.APPROVE_OPTION) {
                                customShellField.text = chooser.selectedFile.absolutePath
                            }
                        }
                    }
                add(browseBtn, BorderLayout.EAST)
            }
        val customShellLabel = addFormRow(projectSection, 1, appMessages.text("settings.customPath"), customShellWrapper)
        customShellLabel.isVisible = isCustomShell
        customShellWrapper.isVisible = isCustomShell

        shellPathCombo.addItemListener { e ->
            if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                val isCustom = e.item == appMessages.text("settings.custom")
                customShellLabel.isVisible = isCustom
                customShellWrapper.isVisible = isCustom
                projectSection.revalidate()
                projectSection.repaint()
            }
        }

        val startDirWrapper =
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(startDirectoryField, BorderLayout.CENTER)
                val browseBtn =
                    JButton(appMessages.text("button.browse")).apply {
                        applyMinimumSizing(this, 72)
                        addActionListener {
                            val chooser =
                                JFileChooser(startDirectoryField.text).apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                }
                            if (chooser.showOpenDialog(this@SettingsDialog) == JFileChooser.APPROVE_OPTION) {
                                startDirectoryField.text = chooser.selectedFile.absolutePath
                            }
                        }
                    }
                add(browseBtn, BorderLayout.EAST)
            }
        addFormRow(projectSection, 2, appMessages.text("settings.startDirectory"), startDirWrapper)
        addFormRow(projectSection, 3, appMessages.text("settings.startupCommand"), startupCommandField)
        panel.add(projectSection)

        panel.add(SectionHeader(appMessages.text("settings.bells")))
        val appSection = createSectionPanel()
        addCheckboxRow(
            appSection,
            0,
            audibleBellCheckbox,
            appMessages.text("settings.audibleBell.description"),
        )
        addCheckboxRow(
            appSection,
            2,
            visualBellCheckbox,
            appMessages.text("settings.visualBell.description"),
        )
        panel.add(appSection)

        panel.add(SectionHeader(appMessages.text("settings.notifications")))
        val notificationSection = createSectionPanel()
        addCheckboxRow(notificationSection, 0, desktopNotificationsCheckbox, appMessages.text("settings.desktopNotifications.description"))
        addCheckboxRow(
            notificationSection,
            2,
            commandFinishedAlertsCheckbox,
            appMessages.text("settings.commandFinishedAlerts.description"),
        )
        addFormRow(notificationSection, 4, appMessages.text("settings.commandFinishedAlertThreshold"), commandFinishedAlertThresholdSpinner)
        panel.add(notificationSection)

        return panel
    }

    private fun buildAppearancePanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader(appMessages.text("settings.typography")))
        val typoSection = createSectionPanel()
        addFormRow(typoSection, 0, appMessages.text("settings.fontFamily"), fontFamilyCombo)

        val fontGridWrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(fontSizeSpinner)
                add(Box.createHorizontalStrut(24))
                add(
                    JLabel(appMessages.text("settings.lineHeight")).apply {
                        foreground = Chrome.textPrimary
                        font = font.deriveFont(Font.PLAIN, 13f)
                        border = EmptyBorder(0, 0, 0, 12)
                    },
                )
                add(lineHeightSpinner)
            }
        addFormRow(typoSection, 1, appMessages.text("settings.fontSize"), fontGridWrapper)

        addFormRow(typoSection, 2, appMessages.text("settings.colorTheme"), themeCombo)
        addCheckboxRow(
            typoSection,
            3,
            useSystemFallbackCheckbox,
            appMessages.text("settings.fontFallback.description"),
        )
        panel.add(typoSection)

        panel.add(SectionHeader(appMessages.text("settings.cursor")))
        val cursorSection = createSectionPanel()
        addFormRow(cursorSection, 0, appMessages.text("settings.cursorShape"), cursorShapeCombo)
        addFormRow(cursorSection, 1, appMessages.text("settings.cursorBlink"), cursorBlinkSpinner)
        panel.add(cursorSection)

        panel.add(SectionHeader(appMessages.text("settings.layout")))
        val windowSection = createSectionPanel()

        val layoutGridWrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(columnsSpinner)
                add(Box.createHorizontalStrut(24))
                add(
                    JLabel(appMessages.text("settings.rows")).apply {
                        foreground = Chrome.textPrimary
                        font = font.deriveFont(Font.PLAIN, 13f)
                        border = EmptyBorder(0, 0, 0, 12)
                    },
                )
                add(rowsSpinner)
            }
        addFormRow(windowSection, 0, appMessages.text("settings.columns"), layoutGridWrapper)
        addFormRow(windowSection, 1, appMessages.text("settings.scrollback"), scrollbackSpinner)
        addFormRow(windowSection, 2, appMessages.text("settings.promptStyle"), promptDecorationCombo)
        addFormRow(windowSection, 3, "", stickyCommandHeadersCheckbox)
        panel.add(windowSection)

        return panel
    }

    private fun buildBehaviorPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader(appMessages.text("settings.input")))
        val keyboardSection = createSectionPanel()
        addCheckboxRow(
            keyboardSection,
            0,
            treatAmbiguousCheckbox,
            appMessages.text("settings.ambiguousWidth.description"),
        )
        panel.add(keyboardSection)

        panel.add(SectionHeader(appMessages.text("settings.mouse")))
        val mouseSection = createSectionPanel()
        addCheckboxRow(
            mouseSection,
            0,
            pasteOnMiddleClickCheckbox,
            appMessages.text("settings.middleClickPaste.description"),
        )
        panel.add(mouseSection)

        panel.add(SectionHeader(appMessages.text("settings.scrolling")))
        val scrollingSection = createSectionPanel()
        addCheckboxRow(
            scrollingSection,
            0,
            scrollOnOutputCheckbox,
            appMessages.text("settings.scrollOnOutput.description"),
        )
        panel.add(scrollingSection)

        panel.add(SectionHeader(appMessages.text("settings.tabTitles")))
        val titleSection = createSectionPanel()
        addCheckboxRow(
            titleSection,
            0,
            showForegroundProcessNameCheckbox,
            appMessages.text("settings.processTitle.description"),
        )
        panel.add(titleSection)

        return panel
    }

    private fun buildSuggestionsPanel(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(
                SectionHeader(appMessages.text("settings.suggestions")).apply {
                    toolTipText = appMessages.text("settings.suggestions.experimental")
                },
            )
            val general = createSectionPanel()
            inlineSuggestionsCheckbox.toolTipText = appMessages.text("settings.inlineSuggestions.description")
            automaticCommandNamesCheckbox.toolTipText = appMessages.text("settings.suggestions.commandNames.description")
            addCheckboxRow(general, 0, smartSuggestionsCheckbox)
            addCheckboxRow(general, 1, inlineSuggestionsCheckbox)
            addFormRow(general, 2, appMessages.text("settings.popupSuggestions"), popupModeCombo)
            addCheckboxRow(general, 3, automaticCommandNamesCheckbox)
            add(general)
            add(SectionHeader(appMessages.text("settings.shortcuts")))
            val shortcuts = createSectionPanel()
            addFormRow(shortcuts, 0, appMessages.text("settings.inlineAcceptKeys"), inlineAcceptKeys)
            addFormRow(shortcuts, 1, appMessages.text("settings.popupAcceptKeys"), popupAcceptKeys)
            addFormRow(shortcuts, 2, appMessages.text("settings.suggestionTriggerKeys"), suggestionTriggerKeys)
            add(shortcuts)
            add(SectionHeader(appMessages.text("settings.learning")))
            val learning = createSectionPanel()
            persistentSuggestionLearningCheckbox.toolTipText = appMessages.text("settings.persistSuggestionLearning.description")
            addCheckboxRow(learning, 0, persistentSuggestionLearningCheckbox)
            learning.add(
                JPanel(FlowLayout(FlowLayout.LEADING, 0, 8)).apply {
                    isOpaque = false
                    add(resetLearningButton)
                    add(Box.createHorizontalStrut(12))
                    add(learningResetStatus)
                },
                GridBagConstraints().apply {
                    gridy = 1
                    gridwidth = 2
                    anchor = GridBagConstraints.WEST
                },
            )
            add(learning)
        }

    private fun createSectionPanel(): JPanel =
        JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = EmptyBorder(0, 16, 0, 0)
        }

    private fun addFormRow(
        panel: JPanel,
        row: Int,
        labelText: String,
        comp1: Component,
    ): JLabel {
        val label =
            JLabel(labelText).apply {
                foreground = Chrome.textPrimary
                font = font.deriveFont(Font.PLAIN, 13f)
                applyMinimumSizing(this, 190)
            }

        val gbc =
            GridBagConstraints().apply {
                gridy = row
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.WEST
            }

        // Column 0: Label
        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.gridwidth = 1
        gbc.insets = Insets(6, 0, 6, 12)
        panel.add(label, gbc)

        // Column 1: Component
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.gridwidth = 3
        if (comp1 is ShortcutEditor) gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(6, 0, 6, 0)
        panel.add(comp1, gbc)

        return label
    }

    private fun addCheckboxRow(
        panel: JPanel,
        row: Int,
        checkbox: JCheckBox,
        description: String? = null,
    ) {
        val gbc =
            GridBagConstraints().apply {
                gridy = row
                gridx = 0
                gridwidth = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 0, 0, 0)
            }
        panel.add(checkbox, gbc)

        if (description != null) {
            val descGbc =
                GridBagConstraints().apply {
                    gridy = row + 1
                    gridx = 0
                    gridwidth = 2
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(2, 22, 12, 0) // Indent to match checkbox text
                }
            val descLabel =
                JLabel("<html>$description</html>").apply {
                    foreground = Chrome.textSecondary
                    font = font.deriveFont(Font.PLAIN, 12f)
                }
            panel.add(descLabel, descGbc)
        }
    }

    private fun buildSecurityPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        panel.add(SectionHeader(appMessages.text("settings.clipboard")))
        val clipboardSection = createSectionPanel()
        addFormRow(clipboardSection, 0, appMessages.text("settings.clipboardWrite"), clipboardWriteCombo)
        addFormRow(clipboardSection, 1, appMessages.text("settings.clipboardRead"), clipboardReadCombo)
        addFormRow(clipboardSection, 2, appMessages.text("settings.clipboardLimit"), clipboardMaxDecodedBytesSpinner)
        panel.add(clipboardSection)

        panel.add(SectionHeader(appMessages.text("settings.paste")))
        val pasteSection = createSectionPanel()
        addFormRow(pasteSection, 0, appMessages.text("settings.pasteHandling"), pasteSanitizationCombo)
        panel.add(pasteSection)

        panel.add(SectionHeader(appMessages.text("settings.windowSecurity")))
        val windowSection = createSectionPanel()
        addCheckboxRow(
            windowSection,
            0,
            shellRequestResizeWindowCheckbox,
            appMessages.text("settings.allowResize.description"),
        )
        addCheckboxRow(
            windowSection,
            2,
            shellRequestWindowManipulationCheckbox,
            appMessages.text("settings.allowWindowManipulation.description"),
        )
        panel.add(windowSection)

        panel.add(SectionHeader(appMessages.text("settings.titleSecurity")))
        val titleSection = createSectionPanel()
        addCheckboxRow(
            titleSection,
            0,
            titlePermissionCheckbox,
            appMessages.text("settings.allowTitle.description"),
        )
        panel.add(titleSection)

        return panel
    }

    private fun buildFooterPanel(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = Chrome.surface
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Chrome.border)

            val leftPanel =
                JPanel(FlowLayout(FlowLayout.LEFT, 12, 12)).apply {
                    isOpaque = false
                }
            val rightPanel =
                JPanel(FlowLayout(FlowLayout.RIGHT, 12, 12)).apply {
                    isOpaque = false
                }

            val resetButton =
                JButton(appMessages.text("button.resetDefaults")).apply {
                    addActionListener { resetToDefaults() }
                }
            leftPanel.add(resetButton)

            val okButton =
                JButton(appMessages.text("button.ok")).apply {
                    addActionListener { applyChanges(closeAfterSave = true) }
                }

            val cancelButton =
                JButton(appMessages.text("button.cancel")).apply {
                    addActionListener { dispose() }
                }

            applyButton.addActionListener { applyChanges() }

            rightPanel.add(cancelButton)
            rightPanel.add(applyButton)
            rightPanel.add(okButton)

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
            this@SettingsDialog.rootPane.defaultButton = okButton
        }

    private fun findProfile(shellPath: String): TerminalProfile? = availableProfiles.firstOrNull { it.command.firstOrNull() == shellPath }

    private fun selectShell(shellPath: String) {
        val profile = findProfile(shellPath)
        shellPathCombo.selectedItem = profile ?: appMessages.text("settings.custom")
        customShellField.text = if (profile == null) shellPath else ""
    }

    private fun resetToDefaults() {
        selectShell(KetraTermConfig.DEFAULT_SHELL_PATH)
        startDirectoryField.text = KetraTermConfig.DEFAULT_START_DIRECTORY
        startupCommandField.text = ""
        audibleBellCheckbox.isSelected = KetraTermConfig.DEFAULT_AUDIBLE_BELL
        visualBellCheckbox.isSelected = KetraTermConfig.DEFAULT_VISUAL_BELL
        desktopNotificationsCheckbox.isSelected = KetraTermConfig.DEFAULT_DESKTOP_NOTIFICATIONS_ENABLED
        commandFinishedAlertsCheckbox.isSelected = KetraTermConfig.DEFAULT_COMMAND_FINISHED_ALERTS_ENABLED
        commandFinishedAlertThresholdSpinner.value = KetraTermConfig.DEFAULT_COMMAND_FINISHED_ALERT_THRESHOLD_SECONDS

        fontFamilyCombo.selectedItem = KetraTermConfig.DEFAULT_FONT_FAMILY
        fontSizeSpinner.value = KetraTermConfig.DEFAULT_FONT_SIZE
        lineHeightSpinner.value = KetraTermConfig.DEFAULT_LINE_HEIGHT.toDouble()
        columnsSpinner.value = KetraTermConfig.DEFAULT_COLUMNS
        rowsSpinner.value = KetraTermConfig.DEFAULT_ROWS
        scrollbackSpinner.value = KetraTermConfig.DEFAULT_SCROLLBACK_LINES
        themeCombo.selectedItem = requireNotNull(TerminalTheme.fromId(KetraTermConfig.DEFAULT_THEME))

        treatAmbiguousCheckbox.isSelected = KetraTermConfig.DEFAULT_TREAT_AMBIGUOUS_AS_WIDE
        useSystemFallbackCheckbox.isSelected = KetraTermConfig.DEFAULT_USE_SYSTEM_FALLBACK_FONTS
        pasteOnMiddleClickCheckbox.isSelected = KetraTermConfig.DEFAULT_PASTE_ON_MIDDLE_CLICK
        pasteSanitizationCombo.selectedItem =
            PASTE_SANITIZATION_OPTIONS.first {
                it.policy == KetraTermConfig.DEFAULT_PASTE_CONTROL_POLICY
            }
        shellRequestResizeWindowCheckbox.isSelected = KetraTermConfig.DEFAULT_SHELL_REQUEST_RESIZE_WINDOW
        shellRequestWindowManipulationCheckbox.isSelected = KetraTermConfig.DEFAULT_SHELL_REQUEST_WINDOW_MANIPULATION
        smartSuggestionsCheckbox.isSelected = KetraTermConfig.DEFAULT_SMART_SUGGESTIONS_ENABLED
        popupModeCombo.selectedItem = PopupMode.AUTOMATIC
        val bindingDefaults = SuggestionKeyBindings()
        popupAcceptKeys.shortcuts = bindingDefaults.popupAccept
        inlineAcceptKeys.shortcuts = bindingDefaults.inlineAccept
        suggestionTriggerKeys.shortcuts = bindingDefaults.trigger
        inlineSuggestionsCheckbox.isSelected = KetraTermConfig.DEFAULT_INLINE_SUGGESTIONS_ENABLED
        automaticCommandNamesCheckbox.isSelected = true
        updateAutomaticSuggestionModes()
        persistentSuggestionLearningCheckbox.isSelected = KetraTermConfig.DEFAULT_PERSISTENT_SUGGESTION_LEARNING_ENABLED
        scrollOnOutputCheckbox.isSelected = KetraTermConfig.DEFAULT_SCROLL_ON_OUTPUT
        showForegroundProcessNameCheckbox.isSelected = KetraTermConfig.DEFAULT_SHOW_FOREGROUND_PROCESS_NAME
        cursorBlinkSpinner.value = KetraTermConfig.DEFAULT_CURSOR_BLINK_MILLIS
        cursorShapeCombo.selectedItem = KetraTermConfig.DEFAULT_CURSOR_SHAPE
        promptDecorationCombo.selectedItem = KetraTermConfig.DEFAULT_PROMPT_DECORATION
        stickyCommandHeadersCheckbox.isSelected = true

        clipboardWriteCombo.selectedItem = KetraTermConfig.DEFAULT_CLIPBOARD_WRITE
        clipboardReadCombo.selectedItem = KetraTermConfig.DEFAULT_CLIPBOARD_READ
        clipboardMaxDecodedBytesSpinner.value = KetraTermConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES
        titlePermissionCheckbox.isSelected = KetraTermConfig.DEFAULT_TITLE_PERMISSION == TerminalTitlePermission.ALLOW
    }

    private fun applyChanges(closeAfterSave: Boolean = false) {
        if (saving) return
        val uiState = getUiState()
        try {
            TerminalStartupCommand.fromText(uiState.startupCommand)
        } catch (_: IllegalArgumentException) {
            SwingMessageDialogs.show(
                this,
                SwingDialogRequest(
                    appMessages.text("settings.startup.invalidTitle"),
                    if (uiState.startupCommand.length > TerminalStartupCommand.MAX_LENGTH) {
                        appMessages.text("settings.startup.invalidLength", TerminalStartupCommand.MAX_LENGTH)
                    } else {
                        appMessages.text("settings.startup.invalid")
                    },
                    SwingDialogRequest.Severity.ERROR,
                    options = listOf(appMessages.text("button.ok")),
                ),
            )
            return
        }
        if (!model.hasChanges(uiState)) {
            if (closeAfterSave) dispose()
            return
        }
        setSaving(true)
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                model.applyChanges(uiState)
            }

            override fun done() {
                setSaving(false)
                try {
                    get()
                    if (uiState.shellPath != settings.config.shellPath) selectShell(settings.config.shellPath)
                    updateApplyButtonState()
                    if (closeAfterSave) dispose()
                } catch (failure: ExecutionException) {
                    val cause = failure.cause ?: failure
                    if (cause !is IOException) throw cause
                    SwingMessageDialogs.show(
                        this@SettingsDialog,
                        SwingDialogRequest(
                            appMessages.text("settings.save.errorTitle"),
                            appMessages.text("settings.save.error", cause.message ?: cause.javaClass.simpleName),
                            SwingDialogRequest.Severity.ERROR,
                            options = listOf(appMessages.text("button.ok")),
                        ),
                    )
                }
            }
        }.execute()
    }

    private fun confirmLearningReset() {
        val choice =
            SwingMessageDialogs.show(
                this,
                SwingDialogRequest(
                    appMessages.text("settings.resetLearning.title"),
                    appMessages.text("settings.resetLearning.confirmation"),
                    SwingDialogRequest.Severity.WARNING,
                    options = listOf(appMessages.text("settings.resetLearning.confirm"), appMessages.text("button.cancel")),
                ),
            )
        if (choice != 0) return
        resetLearningButton.isEnabled = false
        learningResetStatus.text = appMessages.text("settings.resetLearning.progress")
        learningResetStatus.toolTipText = null
        try {
            learningResetObserver =
                resetCompletionLearning().invokeOnCompletion { failure ->
                    SwingUtilities.invokeLater { if (isDisplayable) finishLearningReset(failure) }
                }
        } catch (failure: Exception) {
            finishLearningReset(failure)
        }
    }

    private fun finishLearningReset(failure: Throwable?) {
        learningResetObserver = null
        resetLearningButton.isEnabled = !saving
        learningResetStatus.text =
            appMessages.text(
                if (failure == null) "settings.resetLearning.complete" else "settings.resetLearning.error",
            )
        learningResetStatus.toolTipText = failure?.message
    }

    private fun setSaving(value: Boolean) {
        saving = value

        fun updateEnabled(component: Component) {
            component.isEnabled = !value
            if (component is Container) component.components.forEach(::updateEnabled)
        }
        updateEnabled(contentPane)
        if (!value) updateAutomaticSuggestionModes()
        resetLearningButton.isEnabled = !value && learningResetObserver == null
        updateApplyButtonState()
    }

    override fun dispose() {
        if (!saving) {
            learningResetObserver?.dispose()
            learningResetObserver = null
            super.dispose()
        }
    }

    private fun updateApplyButtonState() {
        val hasChanges = model.hasChanges(getUiState())
        applyButton.isEnabled = !saving && hasChanges
        if (hasChanges) {
            applyButton.putClientProperty("JButton.buttonType", "default")
            applyButton.background = UIManager.getColor("Button.default.background") ?: Chrome.accent
            applyButton.foreground = UIManager.getColor("Button.default.foreground") ?: Chrome.surface
        } else {
            applyButton.putClientProperty("JButton.buttonType", null)
            applyButton.background = null
            applyButton.foreground = null
        }
        applyButton.repaint()
    }

    private fun getUiState(): KetraTermConfig {
        val selected = shellPathCombo.selectedItem
        val nextShellPath =
            if (selected is TerminalProfile) {
                selected.command.firstOrNull() ?: ""
            } else {
                customShellField.text
            }
        val current = settings.config
        val popupKeysChanged =
            popupAcceptKeys.shortcuts !=
                current.suggestionKeyBindings.effectivePopupAccept(current.acceptSelectedSuggestionWithEnter)
        return current.copy(
            theme = (themeCombo.selectedItem as TerminalTheme).id,
            treatAmbiguousAsWide = treatAmbiguousCheckbox.isSelected,
            fontFamily = fontFamilyCombo.selectedItem as? String ?: "",
            fontSize = fontSizeSpinner.value as? Int ?: KetraTermConfig.DEFAULT_FONT_SIZE,
            columns = columnsSpinner.value as? Int ?: KetraTermConfig.DEFAULT_COLUMNS,
            rows = rowsSpinner.value as? Int ?: KetraTermConfig.DEFAULT_ROWS,
            cursorBlinkMillis = cursorBlinkSpinner.value as? Int ?: KetraTermConfig.DEFAULT_CURSOR_BLINK_MILLIS,
            useSystemFallbackFonts = useSystemFallbackCheckbox.isSelected,
            cursorShape = cursorShapeCombo.selectedItem as? String ?: "",
            promptDecoration = promptDecorationCombo.selectedItem as? SwingPromptDecoration ?: KetraTermConfig.DEFAULT_PROMPT_DECORATION,
            stickyCommandHeadersEnabled = stickyCommandHeadersCheckbox.isSelected,
            shellPath = nextShellPath.ifBlank { KetraTermConfig.DEFAULT_SHELL_PATH },
            startDirectory = startDirectoryField.text,
            startupCommand = startupCommandField.text,
            audibleBell = audibleBellCheckbox.isSelected,
            visualBell = visualBellCheckbox.isSelected,
            desktopNotificationsEnabled = desktopNotificationsCheckbox.isSelected,
            commandFinishedAlertsEnabled = commandFinishedAlertsCheckbox.isSelected,
            commandFinishedAlertThresholdSeconds = commandFinishedAlertThresholdSpinner.value as Int,
            pasteOnMiddleClick = pasteOnMiddleClickCheckbox.isSelected,
            pasteControlPolicy =
                (pasteSanitizationCombo.selectedItem as? PasteSanitizationOption)?.policy
                    ?: KetraTermConfig.DEFAULT_PASTE_CONTROL_POLICY,
            scrollbackLines = scrollbackSpinner.value as? Int ?: KetraTermConfig.DEFAULT_SCROLLBACK_LINES,
            lineHeight = (lineHeightSpinner.value as Number).toFloat(),
            shellRequestResizeWindow = shellRequestResizeWindowCheckbox.isSelected,
            shellRequestWindowManipulation = shellRequestWindowManipulationCheckbox.isSelected,
            smartSuggestionsEnabled = smartSuggestionsCheckbox.isSelected,
            shellSuggestionsEnabled =
                when (popupModeCombo.selectedItem) {
                    PopupMode.OFF -> current.shellSuggestionsEnabled
                    PopupMode.AUTOMATIC -> true
                    else -> false
                },
            popupSuggestionsEnabled = popupModeCombo.selectedItem != PopupMode.OFF,
            suggestionKeyBindings =
                SuggestionKeyBindings(
                    popupAccept = if (popupKeysChanged) popupAcceptKeys.shortcuts else current.suggestionKeyBindings.popupAccept,
                    inlineAccept = inlineAcceptKeys.shortcuts,
                    trigger = suggestionTriggerKeys.shortcuts,
                ),
            inlineSuggestionsEnabled = inlineSuggestionsCheckbox.isSelected,
            shellSuggestionAutomaticMode =
                if (automaticCommandNamesCheckbox.isSelected) {
                    SwingShellSuggestionAutomaticMode.ALWAYS
                } else {
                    SwingShellSuggestionAutomaticMode.PARAMETERS_ONLY
                },
            acceptSelectedSuggestionWithEnter =
                if (popupKeysChanged) {
                    popupAcceptKeys.shortcuts.any { it.keyCode == KeyEvent.VK_ENTER }
                } else {
                    current.acceptSelectedSuggestionWithEnter
                },
            persistentSuggestionLearningEnabled = persistentSuggestionLearningCheckbox.isSelected,
            clipboardWrite = clipboardWriteCombo.selectedItem as TerminalClipboardPermission,
            clipboardRead = clipboardReadCombo.selectedItem as TerminalClipboardPermission,
            clipboardMaxDecodedBytes =
                clipboardMaxDecodedBytesSpinner.value as? Int
                    ?: KetraTermConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES,
            titlePermission =
                if (titlePermissionCheckbox.isSelected) {
                    TerminalTitlePermission.ALLOW
                } else {
                    TerminalTitlePermission.DENY
                },
            scrollOnOutput = scrollOnOutputCheckbox.isSelected,
            showForegroundProcessName = showForegroundProcessNameCheckbox.isSelected,
        )
    }

    private fun updateAutomaticSuggestionModes() {
        val enabled = smartSuggestionsCheckbox.isSelected
        val popupEnabled = popupModeCombo.selectedItem != PopupMode.OFF
        inlineSuggestionsCheckbox.isEnabled = enabled
        popupModeCombo.isEnabled = enabled
        automaticCommandNamesCheckbox.isVisible = popupModeCombo.selectedItem == PopupMode.AUTOMATIC
        automaticCommandNamesCheckbox.isEnabled = enabled && popupModeCombo.selectedItem == PopupMode.AUTOMATIC
        popupAcceptKeys.isEnabled = enabled && popupEnabled
        suggestionTriggerKeys.isEnabled = enabled && popupEnabled
        inlineAcceptKeys.isEnabled = enabled && inlineSuggestionsCheckbox.isSelected
        persistentSuggestionLearningCheckbox.isEnabled = enabled
    }

    private fun registerChangeListener(
        comp: Component,
        callback: () -> Unit,
    ) {
        when (comp) {
            is ShortcutEditor -> comp.addPropertyChangeListener("shortcuts") { callback() }
            is JTextField ->
                comp.document.addDocumentListener(
                    object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = callback()

                        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = callback()

                        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = callback()
                    },
                )
            is JComboBox<*> -> comp.addActionListener { callback() }
            is AbstractButton -> comp.addActionListener { callback() }
            is JSpinner -> comp.addChangeListener { callback() }
        }
    }

    private inner class CategoryLabel(
        val categoryId: String,
    ) : JPanel() {
        private var selected = false
        private var hovered = false

        private val nameLabel =
            JLabel(appMessages.text(categoryId)).apply {
                font = font.deriveFont(Font.PLAIN, 13f)
                foreground = Chrome.textPrimary
                border = EmptyBorder(0, 12, 0, 0)
            }

        init {
            layout = BorderLayout()
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            preferredSize = Dimension(Int.MAX_VALUE, 32)

            add(nameLabel, BorderLayout.CENTER)

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        hovered = false
                        repaint()
                    }
                },
            )
        }

        fun updateState(isSelected: Boolean) {
            this.selected = isSelected
            nameLabel.font = nameLabel.font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            if (selected) {
                g.color = Chrome.controlHover
                g.fillRect(8, 0, width - 16, height)
            } else if (hovered) {
                g.color = Chrome.tabHoverBackground
                g.fillRect(8, 0, width - 16, height)
            }
            super.paintComponent(g)
        }
    }
}

private data class PasteSanitizationOption(
    val label: String,
    val policy: io.github.ketraterm.input.policy.PasteControlPolicy,
) {
    override fun toString(): String = label
}

private fun createClipboardPermissionCombo(current: TerminalClipboardPermission): JComboBox<TerminalClipboardPermission> =
    JComboBox(TerminalClipboardPermission.entries.toTypedArray()).apply {
        selectedItem = current
        preferredSize = Dimension(150, 26)
        renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component =
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                        text =
                            when (value) {
                                TerminalClipboardPermission.DENY -> appMessages.text("permission.deny")
                                TerminalClipboardPermission.PROMPT -> appMessages.text("permission.ask")
                                TerminalClipboardPermission.ALLOW -> appMessages.text("permission.allow")
                                else -> ""
                            }
                    }
            }
    }

private val PASTE_SANITIZATION_OPTIONS =
    listOf(
        PasteSanitizationOption(appMessages.text("settings.paste.preserve"), io.github.ketraterm.input.policy.PasteControlPolicy.PRESERVE),
        PasteSanitizationOption(
            appMessages.text("settings.paste.stripControls"),
            io.github.ketraterm.input.policy.PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
        ),
    )
