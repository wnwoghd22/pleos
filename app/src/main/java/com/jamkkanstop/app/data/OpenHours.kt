package com.jamkkanstop.app.data

import java.time.LocalTime

/** 공공데이터의 자유 형식 개방시간 문자열을 해석한다. */
object OpenHours {

    enum class Status { OPEN, CLOSED, UNKNOWN }

    private val alwaysOpenWords = listOf("24시간", "24시", "상시", "연중무휴", "00:00~24:00", "00:00-24:00")
    // "09:00~18:00", "09:00 - 18:00", "09:00∼18:00", "09~18". 분은 생략될 수 있다.
    // 앞뒤에 숫자가 붙은 "0900~1800"이나 "7~9월" 같은 것은 시간으로 보지 않는다.
    private val range = Regex(
        """(?<!\d)(\d{1,2})(?:\s*:\s*(\d{2}))?\s*[~\-∼～]\s*(\d{1,2})(?:\s*:\s*(\d{2}))?(?![\d월일])""",
    )

    fun status(openHours: String?, now: LocalTime): Status {
        if (openHours.isNullOrBlank()) return Status.UNKNOWN
        val text = openHours.replace(" ", "")
        if (alwaysOpenWords.any { text.contains(it.replace(" ", "")) }) return Status.OPEN

        val match = range.find(openHours) ?: return Status.UNKNOWN
        val (h1, m1, h2, m2) = match.destructured
        if (h1.toInt() > 24 || h2.toInt() > 24) return Status.UNKNOWN
        val start = h1.toInt() * 60 + (m1.toIntOrNull() ?: 0)
        val end = h2.toInt() * 60 + (m2.toIntOrNull() ?: 0) // 24:00 → 1440
        if (start == end) return Status.OPEN // "00:00~00:00"은 하루 종일이라는 뜻으로 쓰인다
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
