package com.github.saintedlittle.telegram

import com.github.saintedlittle.MainActivity
import com.github.saintedlittle.cfg.ConfigManager
import com.github.saintedlittle.i18n.Messages
import com.github.saintedlittle.repo.ReportService
import org.bukkit.scheduler.BukkitRunnable
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.util.concurrent.Executors

class TelegramBridge(
    private val plugin: MainActivity,
    private val cfg: ConfigManager,
    private val reports: ReportService,
    private val messages: Messages
) : TelegramLongPollingBot(cfg.telegramToken) {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var api: TelegramBotsApi

    fun start() {
        executor.submit {
            api = TelegramBotsApi(DefaultBotSession::class.java)
            api.registerBot(this)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    override fun getBotUsername(): String = "HGN_Reports_Bot"

    override fun onUpdateReceived(update: Update) {
        val msg = update.message ?: return
        if (!msg.hasText()) return
        val chatId = msg.chatId
        val text = msg.text.trim()

        val fromId = msg.from?.id?.toLong()
        val isAdmin = fromId != null && cfg.telegramAdminIds.contains(fromId)

        val who = "TG-" + (msg.from?.userName ?: fromId?.toString() ?: "unknown")

        when {
            text.startsWith("/report ", true) -> {
                val parts = text.split(" ", limit = 3)
                if (parts.size < 3) { reply(chatId, "Usage: /report <username> <text>"); return }
                val target = parts[1]
                val body = parts[2]
                val author = "Anonymous"

                object : BukkitRunnable() {
                    override fun run() {
                        val id = reports.createReport(target, body, author)
                        object : BukkitRunnable() {
                            override fun run() {
                                reports.notifyStaffNew(id, target, body)
                                forwardNewReport(id, target, body, author)
                                reply(chatId, "Report #$id created (Anonymous).")
                            }
                        }.runTask(plugin)
                    }
                }.runTaskAsynchronously(plugin)
            }

            text.startsWith("/answer ", true) && isAdmin -> {
                val parts = text.split(" ", limit = 3)
                if (parts.size < 3) { reply(chatId, "Usage: /answer <id> <text>"); return }
                val id = parts[1].toLongOrNull() ?: run { reply(chatId, "Invalid id."); return }
                val body = parts[2]
                val who = "TG-${msg.from.userName ?: fromId}"

                object : BukkitRunnable() {
                    override fun run() {
                        val ok = reports.addAnswer(id, who, body)
                        object : BukkitRunnable() {
                            override fun run() {
                                if (ok) {
                                    reports.notifyStaffAnswer(id, who, body)
                                    reports.notifyPlayerAnswer(id, who, body)
                                    sendAnswerEcho(id, "$who: $body")
                                    reply(chatId, "Answered report #$id.")
                                    plugin.reportsMenu.refreshAll()   // <---
                                } else reply(chatId, "Report not found.")

                            }
                        }.runTask(plugin)
                    }
                }.runTaskAsynchronously(plugin)
            }

            text.startsWith("/close ", true) && isAdmin -> {
                val parts = text.split(" ")
                if (parts.size < 4 || !parts.contains("with")) { reply(chatId, "Usage: /close <id> with <reason>"); return }
                val id = parts[1].toLongOrNull() ?: run { reply(chatId, "Invalid id."); return }
                val withIdx = parts.indexOf("with")
                val reason = parts.drop(withIdx + 1).joinToString(" ")

                object : BukkitRunnable() {
                    override fun run() {
                        val ok = reports.closeReportBy(id, reason, who)
                        object : BukkitRunnable() {
                            override fun run() {
                                if (ok) {
                                    reports.notifyStaffClose(id, reason)
                                    reports.notifyPlayerClose(id, who, reason)
                                    sendAnswerEcho(id, "Closed: $reason")
                                    reply(chatId, "Closed report #$id.")
                                    plugin.reportsMenu.refreshAll()   // <---
                                } else reply(chatId, "Report not found.")

                            }
                        }.runTask(plugin)
                    }
                }.runTaskAsynchronously(plugin)
            }

            text.startsWith("/delete ", true) && isAdmin -> {
                val id = text.removePrefix("/delete").trim().toLongOrNull()
                if (id == null) { reply(chatId, "Usage: /delete <id>"); return }

                object : BukkitRunnable() {
                    override fun run() {
                        val ok = reports.deleteReport(id)
                        object : BukkitRunnable() {
                            override fun run() {
                                if (ok) reply(chatId, "Deleted report #$id.")
                                else reply(chatId, "Report not found.")
                            }
                        }.runTask(plugin)
                    }
                }.runTaskAsynchronously(plugin)
            }

            text.equals("/start", true) || text.equals("/help", true) -> {
                reply(
                    chatId,
                    "HGN Reports Bot:\n" +
                            "- /report <username> <text>\n" +
                            (if (isAdmin) "- /answer <id> <text>\n- /close <id> with <reason>\n- /delete <id>\n" else "")
                )
            }

            else -> { /* ignore */ }
        }
    }

    fun forwardNewReport(id: Long, target: String, text: String, author: String) {
        if (cfg.telegramNotifyChats.isEmpty()) return
        val msg = "New report #$id on $target\nFrom: $author\nText: $text"
        cfg.telegramNotifyChats.forEach { cid -> reply(cid, msg) }
    }

    fun sendAnswerEcho(reportId: Long, text: String) {
        if (cfg.telegramNotifyChats.isEmpty()) return
        val msg = "Report #$reportId: $text"
        cfg.telegramNotifyChats.forEach { cid -> reply(cid, msg) }
    }

    private fun reply(chatId: Long, text: String) {
        try {
            execute(SendMessage(chatId.toString(), text))
        } catch (_: Exception) { /* swallow */ }
    }
}
