package dev.nylo.plugin.screenshots.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.nylo.plugin.screenshots.device.DeviceDetector
import dev.nylo.plugin.screenshots.model.NyloPage
import dev.nylo.plugin.screenshots.model.TargetDevice
import dev.nylo.plugin.screenshots.project.LocaleScanner
import dev.nylo.plugin.screenshots.project.RouterParser
import dev.nylo.plugin.screenshots.run.ScreenshotOrchestrator
import dev.nylo.plugin.screenshots.run.ScreenshotRunRequest
import dev.nylo.plugin.screenshots.scaffold.ScreenshotsConfigScaffolder
import dev.nylo.plugin.state.NyloPluginState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The "Nylo Screenshots" tool window. Lists the app's pages (parsed from
 * `router.dart`), detected locales (`lang/`) and runnable devices
 * (`flutter devices`), then drives the framework's screenshot mode across them,
 * capturing each screen into `screenshots/<device>/<locale>/`.
 */
class ScreenshotStudioPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val state = NyloPluginState.getInstance(project)

    private val pagesList = CheckBoxList<NyloPage>()
    private val localesList = CheckBoxList<String>()
    private val devicesList = CheckBoxList<TargetDevice>()

    private val outputField = JBTextField(state.screenshotOutputDir, 14)
    private val settleSpinner = JBIntSpinner(state.screenshotSettleMs, 0, 60_000, 100)
    private val windowSpinner = JBIntSpinner(state.screenshotWindowMs, 100, 60_000, 100)
    private val cleanStatusBarBox = JBCheckBox("Clean status bar (9:41, full battery)", state.screenshotCleanStatusBar)

    private val refreshButton = JButton("Refresh")
    private val scaffoldButton = JButton("Scaffold").apply { toolTipText = "Generate lib/config/screenshots.dart" }
    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")

    private val pageSearchField = SearchTextField(false)
    private val selectAllButton = JButton("All")
    private val selectNoneButton = JButton("None")
    private val pagesCountLabel = JBLabel("0 selected")
    private val pagesLoadingPanel = JBLoadingPanel(BorderLayout(), parent).apply { setLoadingText("Loading pages…") }

    private val statusLabel = JBLabel(" ")
    private val logArea = JBTextArea().apply { isEditable = false; lineWrap = false }

    private var allPages: List<NyloPage> = emptyList()

    /** Routes the user has checked — the source of truth for page selection, kept
     *  independent of the (search-filtered) visible list. */
    private val checkedRoutes = LinkedHashSet<String>()
    private var suppressPageEvents = false

    @Volatile
    private var cancelRequested = false

    init {
        Disposer.register(parent, this)

        refreshButton.addActionListener { refresh() }
        scaffoldButton.addActionListener { scaffold() }
        startButton.addActionListener { start() }
        stopButton.addActionListener { cancelRequested = true; appendLog("Stopping…") }
        stopButton.isEnabled = false

        pageSearchField.textEditor.emptyText.text = "Search pages"
        pageSearchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = renderPages()
        })
        selectAllButton.addActionListener {
            checkedRoutes.addAll(filteredPages().map { it.route }); renderPages(); persistRoutes()
        }
        selectNoneButton.addActionListener {
            checkedRoutes.removeAll(filteredPages().map { it.route }.toSet()); renderPages(); persistRoutes()
        }
        pagesList.setCheckBoxListListener { index, value ->
            if (suppressPageEvents) return@setCheckBoxListListener
            pagesList.getItemAt(index)?.let { page ->
                if (value) checkedRoutes.add(page.route) else checkedRoutes.remove(page.route)
            }
            updatePagesCount()
            persistRoutes()
        }

        val splitter = OnePixelSplitter(true, 0.62f).apply {
            firstComponent = JBScrollPane(buildControls())
            secondComponent = buildLogPanel()
        }
        add(splitter, BorderLayout.CENTER)

        appendLog("Boot the simulators/emulators you want to target, then click Refresh.")
        refresh()
    }

    // ---- layout -------------------------------------------------------------

    private fun buildControls(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6)
        }
        root.add(pagesSection())
        root.add(section("Locales", localesList, 90))
        root.add(devicesSection())
        root.add(paramsSection())
        root.add(buttonsRow())
        return root
    }

    private fun section(title: String, body: JComponent, height: Int): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder(title, false)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val scroll = JBScrollPane(body).apply {
            preferredSize = Dimension(220, height)
            maximumSize = Dimension(Int.MAX_VALUE, height)
        }
        panel.add(scroll, BorderLayout.CENTER)
        panel.maximumSize = Dimension(Int.MAX_VALUE, height + JBUI.scale(28))
        return panel
    }

    private fun pagesSection(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder("Pages", false)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        pageSearchField.maximumSize = Dimension(Int.MAX_VALUE, pageSearchField.preferredSize.height)
        val actions = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
            add(selectAllButton)
            add(selectNoneButton)
            add(pagesCountLabel)
        }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(pageSearchField)
            add(actions)
        }
        panel.add(header, BorderLayout.NORTH)
        pagesLoadingPanel.add(
            JBScrollPane(pagesList).apply { preferredSize = Dimension(220, JBUI.scale(190)) },
            BorderLayout.CENTER,
        )
        panel.add(pagesLoadingPanel, BorderLayout.CENTER)
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun devicesSection(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder("Devices", false)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(JBScrollPane(devicesList).apply { preferredSize = Dimension(220, 80) }, BorderLayout.CENTER)
        panel.add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply { add(refreshButton) }, BorderLayout.SOUTH)
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80 + 60))
        return panel
    }

    private fun paramsSection(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = IdeBorderFactory.createTitledBorder("Options", false)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(labeledRow("Output folder:", outputField))
        panel.add(labeledRow("Settle (ms):", settleSpinner))
        panel.add(labeledRow("Hold window (ms):", windowSpinner))
        panel.add(leftRow(cleanStatusBarBox))
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun buttonsRow(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(4))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(scaffoldButton)
            add(startButton)
            add(stopButton)
        }
        // Size after the buttons are added, or the row collapses to an empty-height strip
        // and clips them.
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun labeledRow(label: String, field: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(2))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(label).apply { preferredSize = Dimension(JBUI.scale(110), preferredSize.height) })
            add(field)
        }

    private fun leftRow(comp: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(2))).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(comp)
        }

    private fun buildLogPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JBScrollPane(logArea), BorderLayout.CENTER)
            border = JBUI.Borders.empty(4)
        }

    // ---- data ---------------------------------------------------------------

    private fun refresh() {
        statusLabel.text = "Scanning…"
        pagesLoadingPanel.startLoading()
        ApplicationManager.getApplication().executeOnPooledThread {
            val pages = RouterParser.parse(project)
            val locales = LocaleScanner.scan(project)
            val devices = DeviceDetector.detect(project)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                allPages = pages
                populate(pages, locales, devices)
                pagesLoadingPanel.stopLoading()
                statusLabel.text = "${pages.size} pages · ${locales.size} locales · ${devices.size} device(s)"
                if (devices.isEmpty()) appendLog("No running devices found. Boot a simulator/emulator and Refresh.")
            }, ModalityState.any())
        }
    }

    private fun populate(pages: List<NyloPage>, locales: List<String>, devices: List<TargetDevice>) {
        // Pages start unchecked; only a previously-saved selection is restored.
        checkedRoutes.clear()
        checkedRoutes.addAll(state.screenshotRoutes)
        renderPages()

        val savedLocales = state.screenshotLocales
        val defaultLocale = locales.firstOrNull { it == "en" } ?: locales.firstOrNull()
        localesList.clear()
        locales.forEach { code ->
            val selected = if (savedLocales.isEmpty()) code == defaultLocale else code in savedLocales
            localesList.addItem(code, code, selected)
        }

        val savedDevices = state.screenshotDevices
        devicesList.clear()
        devices.forEach { device ->
            val selected = if (savedDevices.isEmpty()) true else device.id in savedDevices
            devicesList.addItem(device, deviceLabel(device), selected)
        }
    }

    private fun filteredPages(): List<NyloPage> {
        val query = pageSearchField.text.trim().lowercase()
        if (query.isEmpty()) return allPages
        return allPages.filter {
            it.displayName.lowercase().contains(query) ||
                it.route.lowercase().contains(query) ||
                it.className.lowercase().contains(query)
        }
    }

    /** Repopulates the visible list from [filteredPages], reflecting [checkedRoutes]. */
    private fun renderPages() {
        suppressPageEvents = true
        try {
            pagesList.clear()
            filteredPages().forEach { pagesList.addItem(it, pageLabel(it), it.route in checkedRoutes) }
        } finally {
            suppressPageEvents = false
        }
        updatePagesCount()
    }

    private fun updatePagesCount() {
        pagesCountLabel.text = "${checkedRoutes.size} selected"
    }

    /** Remembers the current page selection so it's restored next time the pane opens. */
    private fun persistRoutes() = state.setScreenshotRoutes(checkedRoutes)

    /** All checked pages, regardless of the current search filter. */
    private fun selectedPages(): List<NyloPage> = allPages.filter { it.route in checkedRoutes }

    private fun pageLabel(page: NyloPage): String {
        val lock = if (page.authenticated) "  🔒" else ""
        val unresolved = if (!page.routeResolved) "  (?)" else ""
        return "${page.displayName}   ${page.route}$lock$unresolved"
    }

    private fun deviceLabel(device: TargetDevice): String =
        "${device.name}  ·  ${device.platform.name.lowercase()}${if (device.emulator) "" else " (device)"}"

    // ---- actions ------------------------------------------------------------

    private fun scaffold() {
        val pages = selectedPages().ifEmpty { allPages }
        if (pages.isEmpty()) {
            appendLog("No pages to scaffold — click Refresh first.")
            return
        }
        // The file is hand-edited after scaffolding (per-route data builders, the seed() hook);
        // overwriting it regenerates commented stubs and destroys that work — always confirm.
        if (ScreenshotsConfigScaffolder.exists(project)) {
            val choice = Messages.showYesNoDialog(
                project,
                "lib/config/screenshots.dart already exists. Overwrite it with a fresh stub? " +
                    "Any dataForRoute builders and seed() code you added will be lost.",
                "Overwrite screenshots.dart?",
                "Overwrite",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (choice != Messages.YES) return
        }
        val file = ScreenshotsConfigScaffolder.write(project, pages) ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
        appendLog("Wrote ${file.path}")
        notify("Generated lib/config/screenshots.dart — fill in per-route data, then call ScreenshotsConfig.register() in main().")
    }

    private fun start() {
        val pages = selectedPages()
        val locales = localesList.checkedItems()
        val devices = devicesList.checkedItems()
        if (pages.isEmpty() || locales.isEmpty() || devices.isEmpty()) {
            appendLog("Select at least one page, one locale and one device.")
            return
        }
        val base = project.basePath?.let(::File) ?: return

        var outDir = File(outputField.text.trim().ifEmpty { "screenshots" })
        if (!outDir.isAbsolute) outDir = File(base, outDir.path)

        persist(pages, locales, devices)

        val request = ScreenshotRunRequest(
            projectDir = base,
            devices = devices,
            pages = pages,
            locales = locales,
            outputDir = outDir,
            settleMs = settleSpinner.number,
            windowMs = windowSpinner.number,
            cleanStatusBar = cleanStatusBarBox.isSelected,
        )

        cancelRequested = false
        setRunning(true)
        appendLog("Capturing ${pages.size} page(s) × ${locales.size} locale(s) on ${devices.size} device(s)…")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Capturing Nylo screenshots", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val orchestrator = ScreenshotOrchestrator(
                    request = request,
                    log = { msg -> appendLogAsync(msg) },
                    onProgress = { done, total, label ->
                        indicator.fraction = if (total > 0) done.toDouble() / total else 0.0
                        indicator.text = label
                    },
                    isCancelled = { cancelRequested || indicator.isCanceled },
                )
                val result = orchestrator.run()
                appendLogAsync("Done — ${result.captured} captured, ${result.skipped} skipped.")
                if (result.files.isNotEmpty()) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, outDir)
                    notify("Captured ${result.captured} screenshot(s) into ${outDir.name}/.")
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater({ setRunning(false) }, ModalityState.any())
            }
        })
    }

    private fun persist(pages: List<NyloPage>, locales: List<String>, devices: List<TargetDevice>) {
        state.setScreenshotSelection(pages.map { it.route }, locales, devices.map { it.id })
        state.screenshotOutputDir = outputField.text.trim()
        state.screenshotSettleMs = settleSpinner.number
        state.screenshotWindowMs = windowSpinner.number
        state.screenshotCleanStatusBar = cleanStatusBarBox.isSelected
    }

    private fun setRunning(running: Boolean) {
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        refreshButton.isEnabled = !running
        scaffoldButton.isEnabled = !running
        selectAllButton.isEnabled = !running
        selectNoneButton.isEnabled = !running
        pageSearchField.textEditor.isEnabled = !running
        pagesList.isEnabled = !running
        localesList.isEnabled = !running
        devicesList.isEnabled = !running
    }

    // ---- helpers ------------------------------------------------------------

    private fun <T> CheckBoxList<T>.checkedItems(): List<T> {
        val out = ArrayList<T>()
        for (i in 0 until itemsCount) {
            if (isItemSelected(i)) getItemAt(i)?.let(out::add)
        }
        return out
    }

    private fun appendLog(message: String) {
        logArea.append(message + "\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun appendLogAsync(message: String) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) appendLog(message) }, ModalityState.any())
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Nylo")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun dispose() {}
}
