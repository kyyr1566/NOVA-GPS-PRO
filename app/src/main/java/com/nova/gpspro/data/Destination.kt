package com.nova.gpspro.data

data class Destination(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val photoPath: String? = null,
    val notes: String? = null,
    val createdAt: Long
) {
    companion object {
        /** Strict numeric parse – rejects any non-numeric text. Accepts Arabic-Indic digits. */
        fun parseCoordinate(raw: String): Double? {
            val s = raw.trim()
                .map { c -> when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); '٫' -> '.'; '−' -> '-'; else -> c } }
                .joinToString("")
            if (!Regex("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$").matches(s)) return null
            return s.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        fun validLat(v: Double?) = v != null && v >= -90.0 && v <= 90.0
        fun validLon(v: Double?) = v != null && v >= -180.0 && v <= 180.0
    }
}
