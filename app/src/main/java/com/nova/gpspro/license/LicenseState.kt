package com.nova.gpspro.license

/** Why a license code / stored activation / server answer was refused. Mapped to user text in the UI layer only. */
enum class LicenseError {
    EMPTY_CODE,
    /** Not a NOVA license code at all (wrong shape / prefix / encoding). */
    MALFORMED,
    /** Signed by someone else, or modified after signing (payload or signature). */
    INVALID_SIGNATURE,
    /** Unknown license version/type, or the license needs a newer app version. */
    UNSUPPORTED_VERSION,
    WRONG_PRODUCT,
    /** Bound to a different device (server answer LICENSE_ALREADY_BOUND, or a stored activation of another device). */
    LICENSE_ALREADY_BOUND,
    LICENSE_NOT_FOUND,
    LICENSE_REVOKED,
    /** First activation needs the server and it could not be reached. */
    NETWORK_REQUIRED,
    /** Server unreachable in a non-network way / unexpected answer. */
    SERVER_ERROR,
    /** The server's activation receipt did not verify (forged / wrong device / wrong license). */
    INVALID_ACTIVATION,
    /** This build has no license keys / server URL configured → nothing can be activated (fail closed). */
    NOT_CONFIGURED,
    STORAGE_FAILED
}

/** Verified facts about an activated license. Only ever produced by [LicenseManager] after BOTH proofs verified. */
data class LicenseInfo(
    val licenseId: String,
    val issuedAtEpochSec: Long,
    val firstActivatedAtEpochSec: Long
)

/** Result of a license check/activation. There is intentionally no boolean "isPremium" anywhere. */
sealed class LicenseState {
    /** Nothing stored on this device. */
    data object NotActivated : LicenseState()

    /** Signed license + server receipt for THIS device both verified locally. */
    data class Activated(val info: LicenseInfo) : LicenseState()

    /** A license was present/entered but refused. */
    data class Rejected(val error: LicenseError) : LicenseState()
}
