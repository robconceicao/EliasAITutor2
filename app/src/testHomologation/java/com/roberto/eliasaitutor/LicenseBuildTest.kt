package com.roberto.eliasaitutor
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class LicenseBuildTest {
    @Test fun licensePolicyIsCompiledIntoVariant() {
        assertTrue(BuildConfig.TEST_LICENSE_BYPASS)
    }

    @Test fun ciApkUsesTheConfiguredRemoteBackend() {
        // Local developer builds may target the emulator; CI distributes to real devices.
        if (System.getenv("CI") != "true") return
        val endpoint = URI(BuildConfig.BACKEND_URL)
        assertEquals("https", endpoint.scheme)
        assertTrue("CI APK requires a remote backend host", !endpoint.host.isNullOrBlank())
        assertTrue("CI APK must not target an emulator or localhost",
            endpoint.host !in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]"))
        assertEquals(System.getenv("BACKEND_URL"), BuildConfig.BACKEND_URL)
    }
}
