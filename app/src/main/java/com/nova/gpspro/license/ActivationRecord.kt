package com.nova.gpspro.license

/**
 * What is persisted (encrypted by [LicenseStorage]) after a successful activation: the signed license
 * and the server's signed receipt for this device. Both are re-verified on every start; neither is trusted
 * just because it is stored.
 */
data class ActivationRecord(val license: String, val receipt: String) {
    fun encode(): String = "$license\n$receipt"

    companion object {
        fun decode(raw: String): ActivationRecord? {
            val parts = raw.split('\n')
            if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
            return ActivationRecord(parts[0], parts[1])
        }
    }
}
