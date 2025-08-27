package com.github.saintedlittle.util

import com.github.saintedlittle.MainActivity
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class QuickReplySessions(private val plugin: MainActivity) : Listener {
    companion object {
        private val active: MutableMap<UUID, Long> = ConcurrentHashMap() // player -> reportId
        fun start(p: Player, reportId: Long) { active[p.uniqueId] = reportId }
        fun stop(p: Player) { active.remove(p.uniqueId) }
        fun target(p: Player) = active[p.uniqueId]
    }

    @EventHandler
    fun onChat(e: AsyncPlayerChatEvent) {
        val p = e.player
        val rid = target(p) ?: return
        val txt = e.message.trim()

        e.isCancelled = true

        if (txt.equals("cancel", true)) {
            stop(p)
            object : BukkitRunnable() {
                override fun run() {
                    plugin.messages.send(p, "quick_reply_cancelled")
                }
            }.runTask(plugin)
            return
        }

        stop(p)

        // DB write off-thread
        object : BukkitRunnable() {
            override fun run() {
                val ok = plugin.reports.addAnswer(rid, p.name, txt)
                object : BukkitRunnable() {
                    override fun run() {
                        if (ok) {
                            plugin.messages.send(p, "quick_reply_done", "id" to rid.toString())
                            plugin.reports.notifyStaffAnswer(rid, p.name, txt)    // ← ИМЯ
                            plugin.reports.notifyPlayerAnswer(rid, p.name, txt)   // ← ИГРОКУ-АВТОРУ
                            plugin.telegram?.sendAnswerEcho(rid, "${p.name}: $txt")
                        } else {
                            plugin.messages.send(p, "report_not_found")
                        }
                    }
                }.runTask(plugin)
            }
        }.runTaskAsynchronously(plugin)

    }
}
