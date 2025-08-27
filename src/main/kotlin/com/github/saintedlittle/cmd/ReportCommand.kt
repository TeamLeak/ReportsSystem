package com.github.saintedlittle.cmd

import com.github.saintedlittle.MainActivity
import com.github.saintedlittle.repo.ReportStatus
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class ReportCommand(private val plugin: MainActivity) : CommandExecutor, TabCompleter {

    // --- Кулдаун для не-админов на создание репорта ---
    private val cooldownMs = 5_000L
    private val lastReportAt = mutableMapOf<String, Long>() // sender.name -> last millis

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        when (cmd.name.lowercase()) {

            "reports" -> {
                if (sender !is Player) { plugin.messages.send(sender, "player_only"); return true }
                if (!sender.hasPermission("hgnreports.admin")) { plugin.messages.send(sender, "no_permission"); return true }
                plugin.reportsMenu.open(sender)
                return true
            }

            "report" -> {
                if (args.isEmpty()) { showUsage(sender); return true }

                when (args[0].lowercase()) {

                    "reply" -> {
                        val p = sender as? Player ?: run { plugin.messages.send(sender, "player_only"); return true }
                        if (args.size < 2) { plugin.messages.send(p, "usage_reply"); return true }

                        val tryId = args.getOrNull(1)?.toLongOrNull()
                        val text = if (tryId != null) args.drop(2).joinToString(" ") else args.drop(1).joinToString(" ")
                        if (text.isBlank()) { plugin.messages.send(p, "usage_reply"); return true }

                        object : BukkitRunnable() {
                            override fun run() {
                                val report = if (tryId != null)
                                    plugin.reports.findReportOwnedBy(tryId, p.name)
                                else
                                    plugin.reports.findLatestActiveByAuthor(p.name)

                                if (report != null && report.status == ReportStatus.CLOSED) {
                                    object : BukkitRunnable() {
                                        override fun run() { plugin.messages.send(p, "reply_closed") }
                                    }.runTask(plugin)
                                    return
                                }

                                val ok = report?.let { plugin.reports.addAnswer(it.id, p.name, text) } ?: false

                                object : BukkitRunnable() {
                                    override fun run() {
                                        if (report == null) {
                                            if (tryId != null) plugin.messages.send(p, "myreport_not_owner")
                                            else plugin.messages.send(p, "myreport_none")
                                            return
                                        }
                                        if (ok) {
                                            val rid = report.id
                                            plugin.messages.send(p, "quick_reply_done", "id" to rid.toString())
                                            plugin.reports.notifyStaffAnswer(rid, p.name, text)
                                            plugin.telegram?.sendAnswerEcho(rid, "${p.name}: $text")
                                        } else {
                                            plugin.messages.send(p, "report_not_found")
                                        }
                                    }
                                }.runTask(plugin)
                            }
                        }.runTaskAsynchronously(plugin)
                        return true
                    }

                    "answer" -> {
                        if (!sender.hasPermission("hgnreports.admin")) { plugin.messages.send(sender, "no_permission"); return true }
                        if (args.size < 3) { plugin.messages.send(sender, "usage_answer"); return true }
                        val id = args[1].toLongOrNull() ?: run { plugin.messages.send(sender, "usage_answer"); return true }
                        val text = args.drop(2).joinToString(" ")
                        val adminName = sender.name

                        object : BukkitRunnable() {
                            override fun run() {
                                val ok = plugin.reports.addAnswer(id, adminName, text)
                                object : BukkitRunnable() {
                                    override fun run() {
                                        if (ok) {
                                            plugin.messages.send(sender, "answered_ok", "id" to id.toString())
                                            plugin.reports.notifyStaffAnswer(id, adminName, text)
                                            plugin.reports.notifyPlayerAnswer(id, adminName, text)
                                            plugin.telegram?.sendAnswerEcho(id, "$adminName: $text")
                                            plugin.reportsMenu.refreshAll()
                                        } else {
                                            plugin.messages.send(sender, "report_not_found")
                                        }
                                    }
                                }.runTask(plugin)
                            }
                        }.runTaskAsynchronously(plugin)
                        return true
                    }

                    "close" -> {
                        if (!sender.hasPermission("hgnreports.admin")) { plugin.messages.send(sender, "no_permission"); return true }
                        if (args.size < 4 || !args.contains("with")) { plugin.messages.send(sender, "usage_close"); return true }
                        val id = args[1].toLongOrNull() ?: run { plugin.messages.send(sender, "usage_close"); return true }
                        val withIdx = args.indexOf("with")
                        val reason = args.drop(withIdx + 1).joinToString(" ")

                        object : BukkitRunnable() {
                            override fun run() {
                                val ok = plugin.reports.closeReportBy(id, reason, sender.name)
                                object : BukkitRunnable() {
                                    override fun run() {
                                        if (ok) {
                                            plugin.messages.send(sender, "closed_ok", "id" to id.toString(), "reason" to reason)
                                            plugin.reports.notifyStaffClose(id, reason)
                                            plugin.reports.notifyPlayerClose(id, sender.name, reason)
                                            plugin.telegram?.sendAnswerEcho(id, "Closed: $reason")
                                            plugin.reportsMenu.refreshAll()
                                        } else {
                                            plugin.messages.send(sender, "report_not_found")
                                        }
                                    }
                                }.runTask(plugin)
                            }
                        }.runTaskAsynchronously(plugin)
                        return true
                    }

                    "delete" -> {
                        if (!sender.hasPermission("hgnreports.admin")) { plugin.messages.send(sender, "no_permission"); return true }
                        if (args.size < 2) { plugin.messages.send(sender, "usage_delete"); return true }
                        val id = args[1].toLongOrNull() ?: run { plugin.messages.send(sender, "usage_delete"); return true }

                        object : BukkitRunnable() {
                            override fun run() {
                                val ok = plugin.reports.deleteReport(id)
                                object : BukkitRunnable() {
                                    override fun run() {
                                        if (ok) {
                                            plugin.messages.send(sender, "deleted_ok", "id" to id.toString())
                                            plugin.reportsMenu.refreshAll()
                                        } else {
                                            plugin.messages.send(sender, "report_not_found")
                                        }
                                    }
                                }.runTask(plugin)
                            }
                        }.runTaskAsynchronously(plugin)
                        return true
                    }

                    "admin" -> {
                        if (!sender.hasPermission("hgnreports.admin")) { plugin.messages.send(sender, "no_permission"); return true }
                        if (args.size < 3) { plugin.messages.send(sender, "usage_admin"); return true }
                        val action = args[1].lowercase()
                        val name = args[2]

                        when (action) {
                            "add" -> {
                                object : BukkitRunnable() {
                                    override fun run() {
                                        plugin.reports.addReportAdmin(name)
                                        object : BukkitRunnable() {
                                            override fun run() { plugin.messages.send(sender, "admin_added", "player" to name) }
                                        }.runTask(plugin)
                                    }
                                }.runTaskAsynchronously(plugin)
                            }
                            "remove" -> {
                                object : BukkitRunnable() {
                                    override fun run() {
                                        plugin.reports.removeReportAdmin(name)
                                        object : BukkitRunnable() {
                                            override fun run() { plugin.messages.send(sender, "admin_removed", "player" to name) }
                                        }.runTask(plugin)
                                    }
                                }.runTaskAsynchronously(plugin)
                            }
                            else -> plugin.messages.send(sender, "usage_admin")
                        }
                        return true
                    }

                    "reload" -> {
                        if (!sender.hasPermission("hgnreports.admin")) { plugin.messages.send(sender, "no_permission"); return true }
                        plugin.reloadAll()
                        plugin.messages.send(sender, "reloaded")
                        return true
                    }

                    "stats" -> {
                        if (!sender.hasPermission("hgnreports.head")) {
                            plugin.messages.send(sender, "no_permission"); return true
                        }

                        if (args.size == 1) {
                            object : BukkitRunnable() {
                                override fun run() {
                                    val s = plugin.reports.statsOverall()
                                    object : BukkitRunnable() {
                                        override fun run() {
                                            plugin.messages.send(sender, "stats_overall_header")
                                            plugin.messages.send(sender, "stats_overall_line",
                                                "total" to s.total.toString(),
                                                "open" to s.open.toString(),
                                                "answered" to s.answered.toString(),
                                                "closed" to s.closed.toString()
                                            )
                                            plugin.messages.send(sender, "stats_overall_periods",
                                                "today" to s.today.toString(),
                                                "last24h" to s.last24h.toString(),
                                                "last7d" to s.last7d.toString(),
                                                "month" to s.thisMonth.toString()
                                            )
                                        }
                                    }.runTask(plugin)
                                }
                            }.runTaskAsynchronously(plugin)
                        } else {
                            val name = args[1]
                            object : BukkitRunnable() {
                                override fun run() {
                                    val a = plugin.reports.statsForAdmin(name)
                                    object : BukkitRunnable() {
                                        override fun run() {
                                            plugin.messages.send(sender, "stats_admin_header", "admin" to a.admin)
                                            plugin.messages.send(sender, "stats_admin_line",
                                                "answers" to a.answers.toString(),
                                                "unique" to a.uniqueReports.toString(),
                                                "closes" to a.closes.toString(),
                                                "percent" to String.format("%.1f", a.processedPercent)
                                            )
                                        }
                                    }.runTask(plugin)
                                }
                            }.runTaskAsynchronously(plugin)
                        }
                        return true
                    }

                    // /report <ник> <текст> — создать репорт
                    else -> {
                        if (args.size < 2) { plugin.messages.send(sender, "usage_player"); return true }

                        // --- КУЛДАУН ДЛЯ ИГРОКОВ БЕЗ ПРАВ ---
                        if (sender is Player && !sender.hasPermission("hgnreports.admin")) {
                            val now = System.currentTimeMillis()
                            val last = lastReportAt[sender.name] ?: 0L
                            val left = cooldownMs - (now - last)
                            if (left > 0) {
                                val sec = ((left + 999) / 1000).toString()
                                plugin.messages.send(sender, "report_cooldown", "sec" to sec)
                                return true
                            }
                            lastReportAt[sender.name] = now
                        }
                        // -----------------------------------

                        val target = args[0]
                        val text = args.drop(1).joinToString(" ")

                        object : BukkitRunnable() {
                            override fun run() {
                                val id = plugin.reports.createReport(target, text, sender.name)
                                object : BukkitRunnable() {
                                    override fun run() {
                                        plugin.messages.send(sender, "created", "id" to id.toString())
                                        plugin.reports.notifyStaffNew(id, target, text)
                                        plugin.telegram?.forwardNewReport(id, target, text, sender.name)
                                    }
                                }.runTask(plugin)
                            }
                        }.runTaskAsynchronously(plugin)
                        return true
                    }
                }
            }
        }
        return true
    }

    private fun showUsage(sender: CommandSender) {
        val m = plugin.messages
        m.send(sender, "usage_player")
        m.send(sender, "usage_reply")
        m.send(sender, "myreport_usage")

        if (sender.hasPermission("hgnreports.admin")) {
            m.send(sender, "usage_reports")
            m.send(sender, "usage_answer")
            m.send(sender, "usage_close")
            m.send(sender, "usage_delete")
            m.send(sender, "usage_admin")
            m.send(sender, "usage_reload")
        }
        if (sender.hasPermission("hgnreports.head")) {
            m.send(sender, "usage_stats")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (command.name.equals("report", ignoreCase = true)) {
            if (args.size == 1) {
                if (sender is Player && !sender.hasPermission("hgnreports.admin")) {
                    val list = mutableListOf("reply")
                    list.addAll(Bukkit.getOnlinePlayers().map { it.name })
                    return list
                }
                val base = mutableListOf("answer","close","delete","admin","reload","reply")
                if (sender.hasPermission("hgnreports.head")) base += "stats"
                return base
            }
            if (args.size == 2 && args[0].equals("admin", ignoreCase = true)) {
                return mutableListOf("add", "remove")
            }
        }
        return mutableListOf()
    }
}
