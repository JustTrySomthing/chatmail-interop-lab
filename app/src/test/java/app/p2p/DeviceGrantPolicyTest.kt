package app.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceGrantPolicyTest {
    @Test
    fun newerCredentialReplacesOldPhoneAndBlocksReplay() {
        assertEquals(GrantDecision.REPLACE, grantDecision("old", 10L, "replacement", 11L))
        assertEquals(GrantDecision.REJECT, grantDecision("replacement", 11L, "old", 10L))
        assertEquals(GrantDecision.REJECT, grantDecision("replacement", 11L, "other", 11L))
        assertEquals(GrantDecision.CURRENT, grantDecision("replacement", 11L, "replacement", 11L))
        assertEquals(GrantDecision.REJECT, grantDecision("", 0L, "invalid", 0L))
    }
}
