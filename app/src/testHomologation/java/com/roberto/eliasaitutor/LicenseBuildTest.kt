package com.roberto.eliasaitutor
import org.junit.Assert.assertTrue
import org.junit.Test
class LicenseBuildTest {
    @Test fun licensePolicyIsCompiledIntoVariant() {
        assertTrue(BuildConfig.TEST_LICENSE_BYPASS)
    }
}
