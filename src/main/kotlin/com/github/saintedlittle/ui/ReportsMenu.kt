package com.github.saintedlittle.ui

import com.github.saintedlittle.MainActivity
import com.github.saintedlittle.repo.ReportStatus
import com.github.saintedlittle.util.QuickReplySessions
import dev.lone.itemsadder.api.CustomStack
import dev.lone.itemsadder.api.FontImages.FontImageWrapper
import dev.lone.itemsadder.api.FontImages.TexturedInventoryWrapper
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

class ReportsMenu(private val plugin: MainActivity) : Listener {

    // --- режим просмотра меню
    private enum class ViewMode { ACTIVE, CLOSED }

    // --- Текстурное меню ---
    private val TITLE = ""
    private val SIZE = 45                    // 5 рядов
    private val TEXTURE_ID = "arrows:reports_menu"
    private val OFFSET_X = 0
    private val OFFSET_Y = -11

    // Навигация (последняя строка)
    private val SLOT_BACK = 36               // первый слот 5-го ряда
    private val SLOT_NEXT = 44               // последний слот 5-го ряда

    // PDC
    private val KEY_ACTION = NamespacedKey(plugin, "reports_action")
    private val KEY_REPORT_ID = NamespacedKey(plugin, "reports_id")
    private val ACT_PREV = "prev"
    private val ACT_NEXT = "next"
    private val ACT_OPEN = "open"

    // состояние
    private val viewersPage = mutableMapOf<Player, Int>()
    private val viewersMode = mutableMapOf<Player, ViewMode>()
    private val openViewers = mutableSetOf<Player>()

    /* ----------------------------- API ----------------------------- */

    /** Открыть меню: по умолчанию активные; если [closedOnly]==true — только закрытые. */
    fun open(p: Player, closedOnly: Boolean = false) {
        if (!p.hasPermission("hgnreports.admin")) { plugin.messages.send(p, "no_permission"); return }
        viewersPage[p] = 0
        viewersMode[p] = if (closedOnly) ViewMode.CLOSED else ViewMode.ACTIVE
        openViewers += p

        val tiw = TexturedInventoryWrapper(null, SIZE, TITLE, OFFSET_X, OFFSET_Y, FontImageWrapper(TEXTURE_ID))
        val inv = tiw.internal
        fill(inv, p, 0, viewersMode[p]!!)
        tiw.showInventory(p)
    }

    fun isViewing(p: Player) = p in openViewers
    fun refreshFor(p: Player) {
        if (!isViewing(p)) return
        val page = viewersPage[p] ?: 0
        val mode = viewersMode[p] ?: ViewMode.ACTIVE
        fill(p.openInventory.topInventory, p, page, mode)
    }
    fun refreshAll() { Bukkit.getOnlinePlayers().forEach { if (isViewing(it)) refreshFor(it) } }

    /* --------------------------- Рендер ---------------------------- */

    private fun buildReportItem(
        id: Long, target: String, author: String, status: String, created: String, text: String
    ): ItemStack {
        val base = CustomStack.getInstance("report_item")
            ?: CustomStack.getInstance("arrows:report_item")
        val it = (base?.itemStack?.clone()) ?: ItemStack(Material.PAPER)

        val m: ItemMeta = it.itemMeta
        m.setDisplayName(
            plugin.messages.color(
                plugin.messages.get("gui_item_title")
                    .replace("%id%", id.toString())
                    .replace("%target%", target)
            )
        )
        val lore = plugin.messages.getList("gui_item_lore").map {
            it.replace("%author%", author)
                .replace("%status%", status)
                .replace("%created%", created)
                .replace("%text%", trim(text))
                .replace("%id%", id.toString())
        }
        m.lore = lore

        m.persistentDataContainer.set(KEY_ACTION, PersistentDataType.STRING, ACT_OPEN)
        m.persistentDataContainer.set(KEY_REPORT_ID, PersistentDataType.LONG, id)
        it.itemMeta = m
        return it
    }

    private fun trim(s: String): String {
        val max = 120
        return if (s.length <= max) s else s.substring(0, max - 3) + "..."
    }

    private fun totalReports(mode: ViewMode): List<com.github.saintedlittle.repo.Report> =
        when (mode) {
            ViewMode.ACTIVE ->
                plugin.reports.listForGui(true, 10_000) // только OPEN/ANSWERED
            ViewMode.CLOSED ->
                plugin.reports.listForGui(false, 10_000).filter { it.status == ReportStatus.CLOSED }
        }

    private fun pages(total: Int, per: Int) = if (total == 0) 1 else (total - 1) / per + 1

    private fun fill(inv: Inventory, p: Player, page: Int, mode: ViewMode) {
        inv.clear()

        val all = totalReports(mode)
        val per = SLOT_BACK                       // слотов на страницу (0..35)
        val maxPage = pages(all.size, per) - 1
        val cur = page.coerceIn(0, maxPage)
        if (cur != page) viewersPage[p] = cur

        val start = cur * per
        val end = (start + per).coerceAtMost(all.size)
        val slice = if (start < end) all.subList(start, end) else emptyList()

        var idx = 0
        for (slot in 0 until SLOT_BACK) {
            val r = slice.getOrNull(idx++) ?: break
            inv.setItem(
                slot,
                buildReportItem(
                    id = r.id,
                    target = r.target,
                    author = r.author,
                    status = r.status.name,
                    created = plugin.reports.formatEpoch(r.createdAt),
                    text = r.text
                )
            )
        }

        if (cur > 0) inv.setItem(SLOT_BACK, iaButton("arrows:back_arrow", ACT_PREV))
        if (cur < maxPage) inv.setItem(SLOT_NEXT, iaButton("arrows:next_arrow", ACT_NEXT))
    }

    private fun iaButton(id: String, action: String): ItemStack {
        val base = CustomStack.getInstance(id)
        val it = (base?.itemStack?.clone()) ?: ItemStack(Material.ARROW)
        val m = it.itemMeta
        m.persistentDataContainer.set(KEY_ACTION, PersistentDataType.STRING, action)
        it.itemMeta = m
        return it
    }

    /* ---------------------------- События --------------------------- */

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if (!isViewing(p)) return
        if (e.clickedInventory !== e.view.topInventory) return
        e.isCancelled = true

        val it = e.currentItem ?: return
        val meta = it.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val act = pdc.get(KEY_ACTION, PersistentDataType.STRING) ?: return
        val mode = viewersMode[p] ?: ViewMode.ACTIVE

        when (act) {
            ACT_PREV -> {
                val cur = viewersPage[p] ?: 0
                viewersPage[p] = (cur - 1).coerceAtLeast(0)
                fill(e.inventory, p, viewersPage[p]!!, mode)
            }
            ACT_NEXT -> {
                val cur = viewersPage[p] ?: 0
                val total = totalReports(mode).size
                val per = SLOT_BACK
                val max = pages(total, per) - 1
                viewersPage[p] = (cur + 1).coerceAtMost(max)
                fill(e.inventory, p, viewersPage[p]!!, mode)
            }
            ACT_OPEN -> {
                val id = pdc.get(KEY_REPORT_ID, PersistentDataType.LONG) ?: return
                val r = plugin.reports.findReport(id) ?: run {
                    plugin.messages.send(p, "report_not_found")
                    return
                }
                val createdFmt = plugin.reports.formatEpoch(r.createdAt)

                fun details(withPrompt: Boolean) {
                    plugin.messages.getList("chat_details").forEach { line ->
                        if (line == "%answers%") {
                            val answers = plugin.reports.listAnswers(id)
                            if (answers.isEmpty()) {
                                p.sendMessage(plugin.messages.prefix() + plugin.messages.get("chat_no_answers"))
                            } else {
                                answers.forEach { a -> p.sendMessage("§7- §f${a.author}§7: §f${a.text}") }
                            }
                        } else {
                            p.sendMessage(
                                plugin.messages.color(
                                    line
                                        .replace("%id%", id.toString())
                                        .replace("%target%", r.target)
                                        .replace("%author%", r.author)
                                        .replace("%status%", r.status.name)
                                        .replace("%created%", createdFmt)
                                        .replace("%text%", r.text)
                                )
                            )
                        }
                    }
                    // В закрытых репортах не предлагаем быстрый ответ
                    if (withPrompt && r.status != ReportStatus.CLOSED) plugin.messages.send(p, "prompt_quick_reply")
                }

                if (e.click.isLeftClick) {
                    p.closeInventory()
                    details(withPrompt = true)
                    if (r.status != ReportStatus.CLOSED) {
                        QuickReplySessions.start(p, id)
                        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            if (QuickReplySessions.target(p) == id) QuickReplySessions.stop(p)
                        }, 20L * 60)
                    }
                } else {
                    details(withPrompt = false)
                }
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        if (p in openViewers) {
            openViewers.remove(p)
            viewersPage.remove(p)
            viewersMode.remove(p)
        }
    }
}
