package com.roberto.eliasaitutor
import org.junit.Assert.assertFalse
import org.junit.Test
class LicenseBuildTest {
    @Test fun licensePolicyIsCompiledIntoVariant() {
        assertFalse(BuildConfig.TEST_LICENSE_BYPASS)
    }
}
