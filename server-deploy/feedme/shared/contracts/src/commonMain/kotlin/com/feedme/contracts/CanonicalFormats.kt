package com.feedme.contracts

/**
 * The string formats present in the pinned canonical schema bundle. All parsing is common
 * Kotlin, deterministic and linear in the input length; validation never resolves a URI.
 * See docs/CANONICAL_FORMAT_NOTES.md for the standards and leap-second interpretation.
 */
object CanonicalFormats {
    fun supports(format: String): Boolean = format == "uri" || format == "uuid" || format == "date-time"

    fun accepts(format: String, value: String): Boolean = when (format) {
        "uri" -> uri(value)
        "uuid" -> uuid(value)
        "date-time" -> dateTime(value)
        else -> throw IllegalArgumentException("Unsupported canonical format")
    }

    private fun uuid(value: String): Boolean {
        if (value.length != 36) return false
        for (index in value.indices) {
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (value[index] != '-') return false
            } else if (!value[index].hexDigit()) return false
        }
        return true
    }

    private fun dateTime(value: String): Boolean {
        if (value.length < 20 || value[4] != '-' || value[7] != '-' ||
            (value[10] != 'T' && value[10] != 't') || value[13] != ':' || value[16] != ':') return false
        val year = value.decimal(0, 4)
        val month = value.decimal(5, 7)
        val day = value.decimal(8, 10)
        val hour = value.decimal(11, 13)
        val minute = value.decimal(14, 16)
        val second = value.decimal(17, 19)
        if (year < 0 || month !in 1..12 || day !in 1..daysInMonth(year, month) ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..60) return false

        var index = 19
        if (value[index] == '.') {
            index++
            val start = index
            while (index < value.length && value[index].asciiDigit()) index++
            if (index == start) return false
        }
        if (index == value.length) return false
        val offsetMinutes = when (value[index]) {
            'Z', 'z' -> {
                if (index + 1 != value.length) return false
                0
            }
            '+', '-' -> {
                if (value.length - index != 6 || value[index + 3] != ':') return false
                val offsetHour = value.decimal(index + 1, index + 3)
                val offsetMinute = value.decimal(index + 4, index + 6)
                if (offsetHour !in 0..23 || offsetMinute !in 0..59) return false
                (offsetHour * 60 + offsetMinute) * if (value[index] == '+') 1 else -1
            }
            else -> return false
        }
        if (second < 60) return true

        // RFC 3339 shifts the leap-second point by the numeric offset. Do this with
        // minute arithmetic, including the date rollover, without platform clocks.
        val utcMinute = hour * 60 + minute - offsetMinutes
        val minuteOfDay = ((utcMinute % 1440) + 1440) % 1440
        if (minuteOfDay != 1439) return false
        val utcDay = day + when {
            utcMinute < 0 -> -1
            utcMinute >= 1440 -> 1
            else -> 0
        }
        if (utcDay != 0 && utcDay != daysInMonth(year, month)) return false
        val utcMonth = if (utcDay == 0) { if (month == 1) 12 else month - 1 } else month
        val utcYear = if (utcDay == 0 && month == 1) year - 1 else year
        val utcDate = utcYear * 10_000 + utcMonth * 100 + daysInMonth(utcYear, utcMonth)
        return positiveLeapDates.binarySearch(utcDate) >= 0
    }

    // IERS Leap_Second.dat, verified through Bulletin C 72 (2026-07-06). Its
    // effective dates follow the inserted second by one day. No negative leap
    // seconds have been announced. Any newly announced event requires an explicit
    // reviewed table/test update; unannounced future :60 values are rejected.
    private val positiveLeapDates = intArrayOf(
        19720630, 19721231, 19731231, 19741231, 19751231, 19761231, 19771231,
        19781231, 19791231, 19810630, 19820630, 19830630, 19850630, 19871231,
        19891231, 19901231, 19920630, 19930630, 19940630, 19951231, 19970630,
        19981231, 20051231, 20081231, 20120630, 20150630, 20161231,
    )

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun uri(value: String): Boolean {
        if (value.isEmpty() || !value[0].asciiLetter()) return false
        var index = 1
        while (index < value.length && value[index] != ':') {
            val character = value[index]
            if (!character.asciiLetter() && !character.asciiDigit() && character !in "+-.") return false
            index++
        }
        if (index == value.length) return false
        index++
        if (index + 1 < value.length && value[index] == '/' && value[index + 1] == '/') {
            val authorityStart = index + 2
            index = authorityStart
            while (index < value.length && value[index] !in "/?#") index++
            if (!authority(value, authorityStart, index)) return false
        }
        // With an authority this is path-abempty; without one, the // prefix has
        // already been excluded, so it is path-absolute, path-rootless or path-empty.
        val pathStart = index
        while (index < value.length && value[index] != '?' && value[index] != '#') index++
        if (!component(value, pathStart, index, ":@/")) return false
        if (index < value.length && value[index] == '?') {
            val queryStart = ++index
            while (index < value.length && value[index] != '#') index++
            if (!component(value, queryStart, index, ":@/?")) return false
        }
        if (index < value.length) return component(value, index + 1, value.length, ":@/?")
        return true
    }

    private fun authority(value: String, start: Int, end: Int): Boolean {
        var hostStart = start
        var userInfoEnd = -1
        for (index in start until end) {
            if (value[index] == '@') {
                if (userInfoEnd != -1) return false
                userInfoEnd = index
            }
        }
        if (userInfoEnd != -1) {
            if (!component(value, start, userInfoEnd, ":")) return false
            hostStart = userInfoEnd + 1
        }
        if (hostStart < end && value[hostStart] == '[') {
            var close = hostStart + 1
            while (close < end && value[close] != ']') close++
            if (close == end || !ipLiteral(value, hostStart + 1, close)) return false
            if (close + 1 == end) return true
            return value[close + 1] == ':' && port(value, close + 2, end)
        }
        var hostEnd = hostStart
        while (hostEnd < end && value[hostEnd] != ':') hostEnd++
        // A dotted token that is not IPv4 can still be a reg-name (RFC 3986 §3.2.2).
        if (!component(value, hostStart, hostEnd, "")) return false
        return hostEnd == end || port(value, hostEnd + 1, end)
    }

    private fun port(value: String, start: Int, end: Int): Boolean {
        for (index in start until end) if (!value[index].asciiDigit()) return false
        return true // The RFC's *DIGIT permits an empty port and has no numeric maximum.
    }

    private fun ipLiteral(value: String, start: Int, end: Int): Boolean {
        if (start == end) return false
        if (value[start] == 'v' || value[start] == 'V') {
            var index = start + 1
            val versionStart = index
            while (index < end && value[index].hexDigit()) index++
            if (index == versionStart || index == end || value[index] != '.') return false
            index++
            if (index == end) return false
            while (index < end) {
                val character = value[index++]
                if (!character.unreserved() && !character.subDelimiter() && character != ':') return false
            }
            return true
        }
        return ipv6(value, start, end)
    }

    private fun ipv6(value: String, start: Int, end: Int): Boolean {
        var index = start
        var compressed = false
        var units = 0
        if (value[index] == ':') {
            if (index + 1 == end || value[index + 1] != ':') return false
            compressed = true
            index += 2
        }
        while (index < end) {
            val groupStart = index
            var dotted = false
            while (index < end && value[index] != ':') {
                if (value[index] == '.') dotted = true
                index++
            }
            if (dotted) {
                if (index != end || !ipv4(value, groupStart, index)) return false
                units += 2
            } else {
                if (index - groupStart !in 1..4) return false
                for (digit in groupStart until index) if (!value[digit].hexDigit()) return false
                units++
            }
            if (units > 8) return false
            if (index == end) break
            index++
            if (index < end && value[index] == ':') {
                if (compressed) return false
                compressed = true
                index++
            } else if (index == end) return false
        }
        return if (compressed) units < 8 else units == 8
    }

    private fun ipv4(value: String, start: Int, end: Int): Boolean {
        var index = start
        repeat(4) { octet ->
            val octetStart = index
            var number = 0
            while (index < end && value[index].asciiDigit()) {
                if (index - octetStart == 3) return false
                number = number * 10 + (value[index++] - '0')
            }
            if (index == octetStart || number > 255 || (index - octetStart > 1 && value[octetStart] == '0')) return false
            if (octet < 3) {
                if (index == end || value[index] != '.') return false
                index++
            }
        }
        return index == end
    }

    /** RFC 3986 component alphabet; percent triplets stay encoded and are never normalized. */
    private fun component(value: String, start: Int, end: Int, extra: String): Boolean {
        var index = start
        while (index < end) {
            val character = value[index]
            if (character == '%') {
                if (end - index < 3 || !value[index + 1].hexDigit() || !value[index + 2].hexDigit()) return false
                index += 3
            } else {
                if (!character.unreserved() && !character.subDelimiter() && character !in extra) return false
                index++
            }
        }
        return true
    }

    private fun String.decimal(start: Int, end: Int): Int {
        var result = 0
        for (index in start until end) {
            if (!this[index].asciiDigit()) return -1
            result = result * 10 + (this[index] - '0')
        }
        return result
    }

    private fun Char.asciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.asciiDigit(): Boolean = this in '0'..'9'
    private fun Char.hexDigit(): Boolean = asciiDigit() || this in 'a'..'f' || this in 'A'..'F'
    private fun Char.unreserved(): Boolean = asciiLetter() || asciiDigit() || this in "-._~"
    private fun Char.subDelimiter(): Boolean = this in "!$&'()*+,;="
}
