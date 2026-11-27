package io.github.openscreen.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetsTest {
    @Test
    fun `same subnet detection`() {
        assertTrue(Subnets.sameSubnet("192.168.1.30", 24, "192.168.1.20"))
        assertFalse(Subnets.sameSubnet("192.168.1.30", 24, "192.168.0.20"))
        assertTrue(Subnets.sameSubnet("192.168.1.30", 23, "192.168.0.20"))
        assertTrue(Subnets.sameSubnet("10.1.2.3", 8, "10.200.0.1"))
        assertFalse(Subnets.sameSubnet("bad", 24, "192.168.1.1"))
        assertFalse(Subnets.sameSubnet("192.168.1.1", 33, "192.168.1.1"))
    }

    @Test
    fun `prioritizes same-subnet hosts but keeps the rest`() {
        val hosts = listOf("10.0.0.5", "192.168.1.20", "172.16.0.2")
        assertEquals(listOf("192.168.1.20", "10.0.0.5", "172.16.0.2"), Subnets.prioritize(hosts, "192.168.1.30", 24))
        assertEquals(hosts, Subnets.prioritize(hosts, null, null))
        assertEquals("192.168.1.20", Subnets.firstReachable(hosts, "192.168.1.30", 24))
        assertEquals(null, Subnets.firstReachable(hosts, "192.168.7.30", 24))
    }
}
