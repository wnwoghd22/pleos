package com.jamkkanstop.app.data

import java.time.LocalTime

/** 공공데이터의 자유 형식 개방시간 문자열을 해석한다. */
object OpenHours {

    enum class Status { OPEN, CLOSED, UNKNOWN }

    private val alwaysOpenWords = listOf("24시간", "24시", "상시", "연중무휴", "00:00~24:00", "00:00-24:00")
    private val range = Regex("""(\d{1,2})\s*:\s*(\d{2})\s*[~\-]\s*(\d{1,2})\s*:\s*(\d{2})""")

    fun status(openHours: String?, now: LocalTime): Status {
        if (openHours.isNullOrBlank()) return Status.UNKNOWN
        val text = openHours.replace(" ", "")
        if (alwaysOpenWords.any { text.contains(it.replace(" ", "")) }) return Status.OPEN

        val match = range.find(openHours) ?: return Status.UNKNOWN
        val (h1, m1, h2, m2) = match.destructured
        val start = h1.toInt() * 60 + m1.toInt()
        val end = h2.toInt() * 60 + m2.toInt() // 24:00 → 1440
        val minute = now.hour * 60 + now.minute

        val open = if (start <= end) {
            minute in start until end
        } else {
            // 자정을 넘기는 경우 (예: 18:00~02:00)
            minute >= start || minute < end
        }
        return if (open) Status.OPEN else Status.CLOSED
    }
}
