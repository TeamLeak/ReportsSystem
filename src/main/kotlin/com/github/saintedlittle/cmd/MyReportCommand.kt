package com.github.saintedlittle.cmd

import com.github.saintedlittle.MainActivity
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class MyReportCommand(private val plugin: MainActivity) : CommandExecutor {

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        val p = sender as? Player ?: run {
            plugin.messages.send(sender, "player_only")
            return true
        }

        // 1) если указан id: показываем, только если это репорт игрока
        // 2) иначе — последний активный (OPEN/ANSWERED) репорт игрока
        val report = when {
            args.isNotEmpty() -> {
                val id = args[0].toLongOrNull()
                if (id == null) {
                    plugin.messages.send(p, "myreport_usage")
                    return true
                }
                plugin.reports.findReportOwnedBy(id, p.name) ?: run {
                    plugin.messages.send(p, "myreport_not_owner")
                    return true
                }
            }
            else -> plugin.reports.findLatestActiveByAuthor(p.name) ?: run {
                plugin.messages.send(p, "myreport_none")
                return true
            }
        }

        val createdFmt = plugin.reports.formatEpoch(report.createdAt)

        // используем те же шаблоны, что и в админ-деталях
        plugin.messages.getList("chat_details").forEach { line ->
            if (line == "%answers%") {
                val answers = plugin.reports.listAnswers(report.id)
                if (answers.isEmpty()) {
                    p.sendMessage(plugin.messages.prefix() + plugin.messages.get("chat_no_answers"))
                } else {
                    answers.forEach { a ->
                        p.sendMessage("§7- §f${a.author}§7: §f${a.text}")
                    }
                }
            } else {
                p.sendMessage(
                    plugin.messages.color(
                        line
                            .replace("%id%", report.id.toString())
                            .replace("%target%", report.target)
                            .replace("%author%", report.author)
                            .replace("%status%", report.status.name)
                            .replace("%created%", createdFmt)
                            .replace("%text%", report.text)
                    )
                )
            }
        }

        return true
    }
}
