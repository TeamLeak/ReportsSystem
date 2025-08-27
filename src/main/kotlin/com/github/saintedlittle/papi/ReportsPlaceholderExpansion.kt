// src/main/kotlin/com/github/saintedlittle/papi/ReportsPlaceholderExpansion.kt
package com.github.saintedlittle.papi

import com.github.saintedlittle.MainActivity
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.util.Locale

class ReportsPlaceholderExpansion(
    private val plugin: MainActivity
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "reports_stat"
    override fun getAuthor(): String = "HGN-Reports"
    override fun getVersion(): String = "1.0.0"
    override fun persist(): Boolean = true
    // при желании:
    // override fun canRegister(): Boolean = true

    // ✅ Правильная сигнатура для PAPI 2.11+
    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        // Форматы:
        // %reports_stat_total%
        // %reports_stat_open%
        // %reports_stat_answered%
        // %reports_stat_closed%
        // %reports_stat_today%
        // %reports_stat_last24h%
        // %reports_stat_last7d%
        // %reports_stat_month%
        //
        // По администратору:
        // %reports_stat_answers_<name>%
        // %reports_stat_unique_<name>%
        // %reports_stat_closes_<name>%
        // %reports_stat_percent_<name>%
        // Вместо <name> можно 'self' — возьмём player.name (если null — вернём "0")
        val parts = params.split("_")
        if (parts.isEmpty()) return null

        fun overall(type: String): String {
            val s = plugin.reports.statsOverall()
            return when (type.lowercase(Locale.ROOT)) {
                "total"    -> s.total.toString()
                "open"     -> s.open.toString()
                "answered" -> s.answered.toString()
                "closed"   -> s.closed.toString()
                "today"    -> s.today.toString()
                "last24h"  -> s.last24h.toString()
                "last7d"   -> s.last7d.toString()
                "month"    -> s.thisMonth.toString()
                else       -> "0"
            }
        }

        fun admin(type: String, nameRaw: String): String {
            val name = if (nameRaw.equals("self", true)) {
                // OfflinePlayer.name может быть null (например, для неразрешённых UUID)
                player?.name ?: return "0"
            } else {
                nameRaw
            }
            val a = plugin.reports.statsForAdmin(name)
            return when (type.lowercase(Locale.ROOT)) {
                "answers" -> a.answers.toString()
                "unique"  -> a.uniqueReports.toString()
                "closes"  -> a.closes.toString()
                "percent" -> String.format(Locale.US, "%.1f", a.processedPercent)
                else      -> "0"
            }
        }

        return if (parts.size == 1) {
            overall(parts[0])
        } else {
            admin(parts[0], parts.drop(1).joinToString("_"))
        }
    }
}
