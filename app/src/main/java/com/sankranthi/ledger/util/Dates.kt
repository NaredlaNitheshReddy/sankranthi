package com.sankranthi.ledger.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Dates cross the wire as ISO `yyyy-MM-dd` strings, which is what Postgres
 * `date` columns accept and return.
 */
object Dates {

    private val display = DateTimeFormatter.ofPattern("d MMM yyyy")

    fun today(): String = LocalDate.now().toString()

    fun parseOrNull(iso: String): LocalDate? =
        try { LocalDate.parse(iso) } catch (_: DateTimeParseException) { null }

    /** "12 Jan 2026", falling back to the raw value if it will not parse. */
    fun forDisplay(iso: String): String = parseOrNull(iso)?.format(display) ?: iso

    /** Material's date picker speaks UTC epoch millis. */
    fun fromEpochMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()

    fun toEpochMillis(iso: String): Long? =
        parseOrNull(iso)?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()

    /** Newest first, unparseable values last. */
    fun descendingComparator(): Comparator<String> =
        compareByDescending { parseOrNull(it) ?: LocalDate.MIN }
}
