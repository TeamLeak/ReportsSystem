package com.github.saintedlittle.repo

import com.github.saintedlittle.MainActivity
import com.github.saintedlittle.cfg.ConfigManager
import com.github.saintedlittle.i18n.Messages
import org.bukkit.Bukkit
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReportService(
    private val plugin: MainActivity,
    private val cfg: ConfigManager,
    private val msg: Messages
) {
    private var conn: Connection = connect()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private var taskId: Int = -1

    /* ============================== LIFECYCLE ============================== */

    private fun connect(): Connection {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${cfg.sqliteFile}")
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS reports (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  target TEXT NOT NULL,
                  text TEXT NOT NULL,
                  author TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  last_answer_at INTEGER
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS report_answers (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  report_id INTEGER NOT NULL,
                  author TEXT NOT NULL,
                  text TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS report_admins (
                  name TEXT PRIMARY KEY
                )
                """.trimIndent()
            )
            // helpful indexes for stats and lookups
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_status_created ON reports(status, created_at)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_answers_author ON report_answers(author)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_answers_report_id ON report_answers(report_id)")
        }
        return c
    }

    fun reload() {
        try { conn.close() } catch (_: Exception) {}
        conn = connect()
    }

    fun shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId)
        try { conn.close() } catch (_: Exception) {}
    }

    /* ============================== UTIL ============================== */

    fun formatEpoch(ts: Long): String = dateFmt.format(Instant.ofEpochSecond(ts))

    private fun rsReport(rs: ResultSet) = Report(
        id = rs.getLong("id"),
        target = rs.getString("target"),
        text = rs.getString("text"),
        author = rs.getString("author"),
        createdAt = rs.getLong("created_at"),
        status = ReportStatus.valueOf(rs.getString("status")),
        lastAnswerAt = rs.getLong("last_answer_at").takeIf { it != 0L }
    )

    private fun startOfTodayEpoch(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    private fun startOfMonthEpoch(): Long =
        LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    /* ============================== CORE OPS ============================== */

    fun createReport(target: String, text: String, author: String): Long {
        val now = now()
        conn.prepareStatement(
            """
            INSERT INTO reports(target,text,author,created_at,status,last_answer_at) 
            VALUES (?,?,?,?,'OPEN',NULL)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, target)
            ps.setString(2, text)
            ps.setString(3, author)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT last_insert_rowid() as id")
            if (rs.next()) return rs.getLong("id")
        }
        error("Failed to insert report")
    }

    /**
     * Adds an answer and updates last_answer_at.
     * IMPORTANT: if report already CLOSED, we keep it CLOSED (do not revert to ANSWERED).
     */
    fun addAnswer(reportId: Long, author: String, text: String): Boolean {
        val now = now()
        conn.prepareStatement(
            """
            INSERT INTO report_answers(report_id,author,text,created_at) VALUES (?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, reportId)
            ps.setString(2, author)
            ps.setString(3, text)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """
            UPDATE reports 
            SET last_answer_at=?,
                status = CASE WHEN status='CLOSED' THEN 'CLOSED' ELSE 'ANSWERED' END
            WHERE id=?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, now)
            ps.setLong(2, reportId)
            if (ps.executeUpdate() == 0) return false
        }
        return true
    }

    /**
     * Close report by specific admin (or actor). Adds "Closed: ..." answer authored by [closer].
     */
    fun closeReportBy(reportId: Long, reason: String, closer: String): Boolean {
        conn.prepareStatement("UPDATE reports SET status='CLOSED' WHERE id=?").use { ps ->
            ps.setLong(1, reportId)
            if (ps.executeUpdate() == 0) return false
        }
        // addAnswer will NOT revert CLOSED back to ANSWERED
        addAnswer(reportId, closer, "Closed: $reason")
        return true
    }

    fun deleteReport(reportId: Long): Boolean {
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM report_answers WHERE report_id=?").use { ps ->
                ps.setLong(1, reportId); ps.executeUpdate()
            }
            val deleted = conn.prepareStatement("DELETE FROM reports WHERE id=?").use { ps ->
                ps.setLong(1, reportId); ps.executeUpdate()
            }
            conn.commit()
            return deleted > 0
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun findReport(id: Long): Report? {
        conn.prepareStatement("SELECT * FROM reports WHERE id=?").use { ps ->
            ps.setLong(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) return rsReport(rs)
        }
        return null
    }

    fun listAnswers(reportId: Long): List<ReportAnswer> {
        conn.prepareStatement(
            "SELECT * FROM report_answers WHERE report_id=? ORDER BY id ASC"
        ).use { ps ->
            ps.setLong(1, reportId)
            val rs = ps.executeQuery()
            val out = mutableListOf<ReportAnswer>()
            while (rs.next()) {
                out += ReportAnswer(
                    id = rs.getLong("id"),
                    reportId = rs.getLong("report_id"),
                    author = rs.getString("author"),
                    text = rs.getString("text"),
                    createdAt = rs.getLong("created_at")
                )
            }
            return out
        }
    }

    /**
     * For admin GUI. When [onlyOpen] true — show ACTIVE only (OPEN, ANSWERED).
     */
    fun listForGui(onlyOpen: Boolean, limit: Int): List<Report> {
        val sql = if (onlyOpen)
            "SELECT * FROM reports WHERE status IN ('OPEN','ANSWERED') ORDER BY id DESC LIMIT ?"
        else
            "SELECT * FROM reports ORDER BY id DESC LIMIT ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, limit)
            val rs = ps.executeQuery()
            val out = mutableListOf<Report>()
            while (rs.next()) out += rsReport(rs)
            return out
        }
    }

    /* ============================== PLAYER-SCOPED QUERIES ============================== */

    fun findLatestActiveByAuthor(author: String): Report? {
        conn.prepareStatement(
            """
            SELECT * FROM reports 
            WHERE lower(author)=lower(?) AND status IN ('OPEN','ANSWERED')
            ORDER BY id DESC LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, author)
            val rs = ps.executeQuery()
            return if (rs.next()) rsReport(rs) else null
        }
    }

    fun findReportOwnedBy(id: Long, owner: String): Report? {
        conn.prepareStatement(
            "SELECT * FROM reports WHERE id=? AND lower(author)=lower(?)"
        ).use { ps ->
            ps.setLong(1, id)
            ps.setString(2, owner)
            val rs = ps.executeQuery()
            return if (rs.next()) rsReport(rs) else null
        }
    }

    /* ============================== SCHEDULER (AUTO-CLOSE) ============================== */

    /**
     * Convert legacy AUTO_HIDDEN to CLOSED at boot/reload (with reason).
     */
    fun migrateAutoHiddenToClosed() {
        val toClose = mutableListOf<Long>()
        conn.prepareStatement("SELECT id FROM reports WHERE status='AUTO_HIDDEN'").use { ps ->
            val rs = ps.executeQuery()
            while (rs.next()) toClose += rs.getLong("id")
        }
        if (toClose.isEmpty()) return

        val reason = msg.get("auto_close_reason").replace("%seconds%", cfg.autoHideSeconds.toString())
        conn.autoCommit = false
        try {
            conn.prepareStatement("UPDATE reports SET status='CLOSED' WHERE id=?").use { ps ->
                toClose.forEach { id -> ps.setLong(1, id); ps.addBatch() }
                ps.executeBatch()
            }
            toClose.forEach { id -> addAnswer(id, "System", "Closed: $reason") }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * Auto-close OPEN reports with no answers older than cfg.autoHideSeconds.
     * Notifies staff and refreshes admin GUI.
     */
    fun startAutoHideTask() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId)
        val period = 20L // 1s
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, {
            try {
                val threshold = now() - cfg.autoHideSeconds
                val toClose = mutableListOf<Long>()

                conn.prepareStatement(
                    """
                    SELECT id, created_at, last_answer_at, status FROM reports 
                    WHERE status IN ('OPEN','ANSWERED')
                    """.trimIndent()
                ).use { ps ->
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        val id = rs.getLong("id")
                        val last = rs.getLong("last_answer_at").takeIf { it != 0L }
                        val status = rs.getString("status")
                        if (status == "OPEN") {
                            val created = rs.getLong("created_at")
                            if (last == null && created <= threshold) {
                                toClose.add(id)
                            }
                        }
                    }
                }

                if (toClose.isNotEmpty()) {
                    val reason = msg.get("auto_close_reason")
                        .replace("%seconds%", cfg.autoHideSeconds.toString())

                    toClose.forEach { id ->
                        try {
                            // System actor for auto-close; we still show reason to staff
                            closeReportBy(id, reason, "System")
                            notifyStaffClose(id, reason)
                        } catch (_: Exception) { /* keep task alive */ }
                    }
                    plugin.reportsMenu.refreshAll()
                }
            } catch (_: Exception) {
                // keep task alive
            }
        }, period, period)
    }

    /* ============================== ADMINS LIST ============================== */

    fun isReportAdmin(name: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM report_admins WHERE name=?").use { ps ->
            ps.setString(1, name.lowercase(Locale.ROOT))
            val rs = ps.executeQuery()
            return rs.next()
        }
    }

    fun addReportAdmin(name: String): Boolean {
        conn.prepareStatement("INSERT OR IGNORE INTO report_admins(name) VALUES (?)").use { ps ->
            ps.setString(1, name.lowercase(Locale.ROOT))
            return ps.executeUpdate() > 0
        }
    }

    fun removeReportAdmin(name: String): Boolean {
        conn.prepareStatement("DELETE FROM report_admins WHERE name=?").use { ps ->
            ps.setString(1, name.lowercase(Locale.ROOT))
            return ps.executeUpdate() > 0
        }
    }

    /* ============================== NOTIFICATIONS ============================== */

    fun notifyStaffNew(id: Long, target: String, text: String) {
        Bukkit.getOnlinePlayers().filter { it.hasPermission("hgnreports.admin") }.forEach {
            it.sendMessage(
                msg.prefix() + msg.get("notify_staff_new")
                    .replace("%id%", id.toString())
                    .replace("%target%", target)
                    .replace("%text%", text)
            )
        }
    }

    fun notifyStaffAnswer(id: Long, admin: String, text: String) {
        Bukkit.getOnlinePlayers().filter { it.hasPermission("hgnreports.admin") }.forEach {
            it.sendMessage(
                msg.prefix() + msg.get("notify_staff_answer")
                    .replace("%id%", id.toString())
                    .replace("%admin%", admin)
                    .replace("%text%", text)
            )
        }
    }

    fun notifyStaffClose(id: Long, reason: String) {
        Bukkit.getOnlinePlayers().filter { it.hasPermission("hgnreports.admin") }.forEach {
            it.sendMessage(
                msg.prefix() + msg.get("notify_staff_close")
                    .replace("%id%", id.toString())
                    .replace("%reason%", reason)
            )
        }
    }

    fun notifyPlayerAnswer(id: Long, admin: String, text: String) {
        val r = findReport(id) ?: return
        val authorName = r.author
        if (authorName.equals("Anonymous", true)) return
        val p = Bukkit.getPlayerExact(authorName) ?: return
        p.sendMessage(
            msg.prefix() + msg.get("notify_player_answer")
                .replace("%id%", id.toString())
                .replace("%admin%", admin)
                .replace("%text%", text)
        )
    }

    fun notifyPlayerClose(id: Long, admin: String, reason: String) {
        val r = findReport(id) ?: return
        val authorName = r.author
        if (authorName.equals("Anonymous", true)) return
        val p = Bukkit.getPlayerExact(authorName) ?: return
        p.sendMessage(
            msg.prefix() + msg.get("notify_player_close")
                .replace("%id%", id.toString())
                .replace("%admin%", admin)
                .replace("%reason%", reason)
        )
    }

    /* ============================== STATS ============================== */

    data class StatsOverall(
        val total: Long,
        val open: Long,
        val answered: Long,
        val closed: Long,
        val today: Long,
        val last24h: Long,
        val last7d: Long,
        val thisMonth: Long
    )

    data class StatsAdmin(
        val admin: String,
        val answers: Long,
        val uniqueReports: Long,
        val closes: Long,
        val processedPercent: Double
    )

    fun statsOverall(): StatsOverall {
        fun count(sql: String, binder: (java.sql.PreparedStatement.() -> Unit)? = null): Long {
            conn.prepareStatement(sql).use { ps ->
                binder?.invoke(ps)
                val rs = ps.executeQuery()
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }

        val total    = count("SELECT COUNT(*) FROM reports")
        val open     = count("SELECT COUNT(*) FROM reports WHERE status='OPEN'")
        val answered = count("SELECT COUNT(*) FROM reports WHERE status='ANSWERED'")
        val closed   = count("SELECT COUNT(*) FROM reports WHERE status='CLOSED'")

        val todayTs  = startOfTodayEpoch()
        val monthTs  = startOfMonthEpoch()
        val nowTs    = now()
        val last24Ts = nowTs - 24 * 3600
        val last7Ts  = nowTs - 7 * 24 * 3600

        val today   = count("SELECT COUNT(*) FROM reports WHERE created_at>=?", { setLong(1, todayTs) })
        val last24h = count("SELECT COUNT(*) FROM reports WHERE created_at>=?", { setLong(1, last24Ts) })
        val last7d  = count("SELECT COUNT(*) FROM reports WHERE created_at>=?", { setLong(1, last7Ts) })
        val month   = count("SELECT COUNT(*) FROM reports WHERE created_at>=?", { setLong(1, monthTs) })

        return StatsOverall(total, open, answered, closed, today, last24h, last7d, month)
    }

    fun statsForAdmin(adminName: String): StatsAdmin {
        val low = adminName.lowercase(Locale.ROOT)

        fun count(sql: String, binder: (java.sql.PreparedStatement.() -> Unit)? = null): Long {
            conn.prepareStatement(sql).use { ps ->
                binder?.invoke(ps)
                val rs = ps.executeQuery()
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }

        val answers = count(
            "SELECT COUNT(*) FROM report_answers WHERE lower(author)=?"
        ) { setString(1, low) }

        val uniqueReports = count(
            "SELECT COUNT(DISTINCT report_id) FROM report_answers WHERE lower(author)=?"
        ) { setString(1, low) }

        val closes = count(
            "SELECT COUNT(*) FROM report_answers WHERE lower(author)=? AND text LIKE 'Closed:%'"
        ) { setString(1, low) }

        val totalReports = count("SELECT COUNT(*) FROM reports").coerceAtLeast(1L)
        val processedPercent = (uniqueReports.toDouble() / totalReports.toDouble()) * 100.0

        return StatsAdmin(adminName, answers, uniqueReports, closes, processedPercent)
    }
}
