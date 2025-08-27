package com.github.saintedlittle.repo

import java.time.Instant

enum class ReportStatus { OPEN, ANSWERED, CLOSED, AUTO_HIDDEN }

data class Report(
    val id: Long,
    val target: String,
    val text: String,
    val author: String,           // "Anonymous" for Telegram-origin reports
    val createdAt: Long,          // epoch seconds
    var status: ReportStatus,
    var lastAnswerAt: Long? = null
)

data class ReportAnswer(
    val id: Long,
    val reportId: Long,
    val author: String,
    val text: String,
    val createdAt: Long
)

fun now() = Instant.now().epochSecond
