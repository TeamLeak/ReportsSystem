package com.github.saintedlittle.cfg

import org.bukkit.plugin.Plugin
import java.io.File

class ConfigManager(private val plugin: Plugin) {
    var sqliteFile: String = ""
        private set
    var telegramEnabled: Boolean = false
        private set
    var telegramToken: String = ""
        private set
    var telegramAdminIds: Set<Long> = emptySet()
        private set
    var telegramNotifyChats: Set<Long> = emptySet()
        private set

    var guiTitle: String = ""
        private set
    var showOnlyOpenByDefault: Boolean = true
        private set
    var maxVisible: Int = 40
        private set

    var autoHideSeconds: Long = 300
        private set

    var locale: String = "ru"
        private set
    var debug: Boolean = false
        private set

    init { reload() }

    fun reload() {
        plugin.reloadConfig()
        val c = plugin.config

        sqliteFile = c.getString("storage.sqlite_file", "plugins/HGN-Reports/reports.db")!!
        telegramEnabled = c.getBoolean("telegram.enabled", false)
        telegramToken = c.getString("telegram.bot_token", "")!!
        telegramAdminIds = c.getLongList("telegram.admin_ids").toSet()
        telegramNotifyChats = c.getLongList("telegram.notify_chats").toSet()

        guiTitle = c.getString("gui.title", "&cReports")!!
        showOnlyOpenByDefault = c.getBoolean("gui.show_only_open_by_default", true)
        maxVisible = c.getInt("gui.max_visible", 40)

        autoHideSeconds = c.getLong("behavior.auto_hide_seconds", 300)

        locale = c.getString("locale", "ru")!!
        debug = c.getBoolean("debug", false)

        // ensure db folder exists
        File(sqliteFile).parentFile?.mkdirs()
    }
}
