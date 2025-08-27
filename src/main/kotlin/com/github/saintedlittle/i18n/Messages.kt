package com.github.saintedlittle.i18n

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.logging.Level

class Messages(private val plugin: Plugin, lang: String) {
    private var section: ConfigurationSection = load(lang)

    fun reload(newLang: String) {
        section = load(newLang)
    }

    private fun load(lang: String): ConfigurationSection {
        val file = File(plugin.dataFolder, "messages.yml")
        val root = YamlConfiguration.loadConfiguration(file)
        val sec = root.getConfigurationSection(lang) ?: root
        // Небольшая подсказка в лог, если нужной секции нет
        if (sec === root && root.getConfigurationSection(lang) == null) {
            plugin.logger.log(Level.INFO, "[Messages] Language section '$lang' not found in messages.yml, using root.")
        }
        return sec
    }

    fun prefix(): String = color(getString("prefix", "&8[&cReports&8]&r "))

    fun get(path: String): String = color(getString(path, path))

    fun getList(path: String): List<String> {
        val raw = when (val v = section.get(path)) {
            is List<*> -> v.filterIsInstance<String>()
            is String -> listOf(v)
            else -> emptyList()
        }
        return raw.map { color(it) }
    }

    fun send(sender: CommandSender, path: String, vararg replaces: Pair<String, String>) {
        var msg = get(path)
        replaces.forEach { (k, v) -> msg = msg.replace("%$k%", v) }
        sender.sendMessage(prefix() + msg)
    }

    private fun getString(path: String, def: String): String =
        section.getString(path) ?: def

    fun color(s: String): String =
        ChatColor.translateAlternateColorCodes('&', s)
}
