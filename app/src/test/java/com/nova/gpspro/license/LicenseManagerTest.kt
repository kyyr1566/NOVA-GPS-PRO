package com.nova.gpspro.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 12 acceptance scenarios, at app level (real verifier + manager, in-memory storage, modelled server). */
class LicenseManagerTest {
    private val issuer = TestSigner()
    private val activationKey = TestSigner()
    private val verifier = Ed25519LicenseVerifier(issuer.publicKey, activationKey.publicKey, appVersionCode = 1)
    private val licenseId = "NL-ABCDEFGHIJKLMNOPQRSTUVWXY2"
    private val code = issuer.license(licenseId)
    private val deviceX = sha256Hex("device-X")
    private val deviceY = sha256Hex("device-Y")

    private fun manager(storage: LicenseStorage, server: ActivationService, device: String = deviceX) =
        LicenseManager(FixedIdentity(device), verifier, storage, server, 1)

    private fun server(registered: Boolean = true) = FakeServer(activationKey, if (registered) setOf(licenseId) else emptySet())

    // 1
    @Test fun validLicenseNewDeviceActivatesOnline() {
        val storage = InMemoryStorage(); val srv = server()
        val st = manager(storage, srv).activate(code)
        assertTrue(st.toString(), st is LicenseState.Activated)
        assertEquals(licenseId, (st as LicenseState.Activated).info.licenseId)
        assertEquals(1, srv.requests)
        assertEquals(deviceX, srv.lastRequest!!.deviceHash)
        assertEquals("NOVA_GPS_PRO", srv.lastRequest!!.product)
        assertTrue(storage.data != null)
    }

    // 2 + 3 (app closed / phone rebooted = a brand-new process: empty in-memory cache, same storage) — and OFFLINE
    @Test fun worksOfflineAfterRestart() {
        val storage = InMemoryStorage(); val srv = server()
        manager(storage, srv).activate(code)
        srv.online = false
        val before = srv.requests
        repeat(2) { assertTrue(manager(storage, srv).check() is LicenseState.Activated) }
        assertEquals("check() must never contact the server", before, srv.requests)
    }

    // 4 (reinstall: storage is wiped, same device, same code → server allows re-activation)
    @Test fun reinstallOnSameDeviceReactivates() {
        val srv = server()
        manager(InMemoryStorage(), srv).activate(code)
        val fresh = InMemoryStorage()
        assertEquals(LicenseState.NotActivated, manager(fresh, srv).check())
        assertTrue(manager(fresh, srv).activate(code) is LicenseState.Activated)
        assertEquals(deviceX, srv.bound[licenseId])
    }

    // 5
    @Test fun sameCodeOnAnotherDeviceIsRefused() {
        val srv = server()
        manager(InMemoryStorage(), srv, deviceX).activate(code)
        val storageY = InMemoryStorage()
        assertEquals(LicenseState.Rejected(LicenseError.LICENSE_ALREADY_BOUND), manager(storageY, srv, deviceY).activate(code))
        assertNull(storageY.data)
        assertEquals(deviceX, srv.bound[licenseId])
    }

    // 6, 7, 8 – refused locally, before any network traffic, nothing stored
    @Test fun tamperedLicenseNeverReachesTheServerNorStorage() {
        val bad = listOf(
            code.dropLast(3),
            mutatePart(code, 1) { b -> b.also { it[30] = (it[30].toInt() xor 1).toByte() } },
            mutatePart(code, 2) { b -> b.also { it[2] = (it[2].toInt() xor 1).toByte() } },
            TestSigner().license(licenseId)
        )
        for (c in bad) {
            val storage = InMemoryStorage(); val srv = server()
            assertTrue(manager(storage, srv).activate(c) is LicenseState.Rejected)
            assertEquals(0, srv.requests)
            assertNull(storage.data)
        }
    }

    // 9
    @Test fun unknownLicenseIsRefused() {
        val storage = InMemoryStorage()
        assertEquals(LicenseState.Rejected(LicenseError.LICENSE_NOT_FOUND), manager(storage, server(registered = false)).activate(code))
        assertNull(storage.data)
    }

    // 10
    @Test fun noInternetOnFirstActivationMeansNotActivated() {
        val storage = InMemoryStorage(); val srv = server().apply { online = false }
        val m = manager(storage, srv)
        assertEquals(LicenseState.Rejected(LicenseError.NETWORK_REQUIRED), m.activate(code))
        assertEquals(LicenseState.NotActivated, m.check())
        assertNull(storage.data)
    }

    // 11
    @Test fun internetLossAfterActivationChangesNothing() {
        val storage = InMemoryStorage(); val srv = server()
        val m = manager(storage, srv)
        m.activate(code)
        srv.online = false
        assertTrue(m.check() is LicenseState.Activated)
        assertTrue(manager(storage, srv).check() is LicenseState.Activated)
    }

    // 12 is covered by MainActivity's own gate (see MainActivity.onCreate); here: storage alone never grants access
    @Test fun storedDataAloneIsNotTrusted() {
        // forged record: right shape, receipt signed by an unrelated key
        val forged = InMemoryStorage().apply { data = ActivationRecord(code, TestSigner().receipt(licenseId, deviceX)).encode() }
        assertEquals(LicenseState.Rejected(LicenseError.INVALID_ACTIVATION), manager(forged, server()).check())
        // license without any receipt
        val noReceipt = InMemoryStorage().apply { data = code }
        assertTrue(manager(noReceipt, server()).check() is LicenseState.Rejected)
        // random junk
        assertTrue(manager(InMemoryStorage().apply { data = "NOVA1.eA.eA\nNOVAACT1.eA.eA" }, server()).check() is LicenseState.Rejected)
    }

    @Test fun activationCopiedToAnotherDeviceIsRefused() {
        val storage = InMemoryStorage(); val srv = server()
        manager(storage, srv, deviceX).activate(code)
        assertEquals(LicenseState.Rejected(LicenseError.LICENSE_ALREADY_BOUND), manager(storage, srv, deviceY).check())
    }

    @Test fun forgedServerCannotActivate() {
        val liar = object : ActivationService {   // e.g. a hijacked host answering "ACTIVATED" with its own key
            override fun activate(request: ActivationRequest) =
                ActivationResult.Success(TestSigner().receipt(licenseId, request.deviceHash))
        }
        val storage = InMemoryStorage()
        assertEquals(LicenseState.Rejected(LicenseError.INVALID_ACTIVATION), manager(storage, liar).activate(code))
        assertNull(storage.data)
        val wrongDevice = object : ActivationService {
            override fun activate(request: ActivationRequest) = ActivationResult.Success(activationKey.receipt(licenseId, deviceY))
        }
        assertEquals(LicenseState.Rejected(LicenseError.LICENSE_ALREADY_BOUND), manager(storage, wrongDevice).activate(code))
        assertNull(storage.data)
    }

    @Test fun storageFailureMeansNotActivated() {
        val m = manager(InMemoryStorage(failSave = true), server())
        assertEquals(LicenseState.Rejected(LicenseError.STORAGE_FAILED), m.activate(code))
        assertEquals(LicenseState.NotActivated, m.check())
    }

    @Test fun unconfiguredBuildCannotActivate() {
        val storage = InMemoryStorage(); val srv = server()
        val m = LicenseManager(FixedIdentity(deviceX), Ed25519LicenseVerifier(null, null, 1), storage, srv, 1)
        assertEquals(LicenseState.Rejected(LicenseError.NOT_CONFIGURED), m.activate(code))
        assertEquals(0, srv.requests)
        assertNull(storage.data)
    }

    @Test fun emptyCodeRejected() =
        assertEquals(LicenseState.Rejected(LicenseError.EMPTY_CODE), manager(InMemoryStorage(), server()).activate("   "))

    @Test fun recordRoundTrip() {
        val r = ActivationRecord("a.b.c", "d.e.f")
        assertEquals(r, ActivationRecord.decode(r.encode()))
        assertNull(ActivationRecord.decode("only-one-line"))
        assertNull(ActivationRecord.decode("a\nb\nc"))
    }
}
