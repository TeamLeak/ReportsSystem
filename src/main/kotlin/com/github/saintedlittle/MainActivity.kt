package com.github.saintedlittle

import com.github.saintedlittle.cfg.ConfigManager
import com.github.saintedlittle.i18n.Messages
import com.github.saintedlittle.repo.ReportService
import com.github.saintedlittle.telegram.TelegramBridge
import com.github.saintedlittle.ui.ReportsMenu
import com.github.saintedlittle.util.QuickReplySessions
import com.github.saintedlittle.cmd.ReportCommand
import org.bukkit.plugin.java.JavaPlugin

class MainActivity : JavaPlugin() {

    lateinit var cfg: ConfigManager
        private set
    lateinit var messages: Messages
        private set
    lateinit var reports: ReportService
        private set
    lateinit var reportsMenu: ReportsMenu
        private set

    var telegram: TelegramBridge? = null
        private set

    override fun onEnable() {
        saveDefaultConfig()
        saveResource("messages.yml", false)

        cfg = ConfigManager(this)
        messages = Messages(this, cfg.locale)
        reports = ReportService(this, cfg, messages)

        // Один экземпляр меню
        reportsMenu = ReportsMenu(this)
        server.pluginManager.registerEvents(reportsMenu, this)
        server.pluginManager.registerEvents(QuickReplySessions(this), this)

        // Команды
        val reportCmd = ReportCommand(this)
        getCommand("report")!!.setExecutor(reportCmd)
        getCommand("report")!!.tabCompleter = reportCmd
        getCommand("reports")!!.setExecutor(reportCmd)

        val myCmd = com.github.saintedlittle.cmd.MyReportCommand(this)
        getCommand("myreport")!!.setExecutor(myCmd)
        // Авто-скрытие
        reports.startAutoHideTask()
        reports.migrateAutoHiddenToClosed()

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            try {
                com.github.saintedlittle.papi.ReportsPlaceholderExpansion(this).register()
                log("PlaceholderAPI detected: registered %reports_stat_*% placeholders.")
            } catch (t: Throwable) {
                log("PlaceholderAPI present but failed to register: ${t.message}")
            }
        }

        // Telegram
        if (cfg.telegramEnabled) {
            telegram = TelegramBridge(this, cfg, reports, messages)
            telegram!!.start()
            log("Telegram bot started.")
        }

        log("Enabled.")
    }

    override fun onDisable() {
        telegram?.shutdown()
        reports.shutdown()
        log("Disabled.")
    }

    fun reloadAll() {
        reloadConfig()
        cfg.reload()
        messages.reload(cfg.locale)
        reports.reload()
        telegram?.shutdown()
        telegram = null
        if (cfg.telegramEnabled) {
            telegram = TelegramBridge(this, cfg, reports, messages)
            telegram!!.start()
        }
    }

    fun log(msg: String) = logger.info("[HGN-Reports] $msg")
    fun debug(msg: String) { if (cfg.debug) logger.info("[DEBUG] $msg") }
}
