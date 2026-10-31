package com.v2ray.ang.ui.simple

import com.v2ray.ang.handler.SimpleVpnRepository
import com.v2ray.ang.handler.SimpleVpnRepository.SimpleServerUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleVpnSourceTabStateTest {

    private fun server(guid: String) = SimpleServerUiModel(
        guid = guid,
        displayName = "Server $guid",
        countryName = null,
        flagEmoji = "",
        delayMillis = null,
    )

    @Test
    fun selectsV2RayTabAndKeepsOnlyItsSelectedGuid() {
        val servers = listOf(server("v2ray-1"), server("v2ray-2"))

        val result = selectSimpleVpnSourceTab(
            current = SimpleVpnUiState(licenseValid = true),
            sourceId = SimpleVpnRepository.SIMPLE_SUB_ID,
            servers = servers,
            selectedGuid = "v2ray-2",
            canConnect = true,
        )

        assertEquals(SimpleVpnRepository.SIMPLE_SUB_ID, result?.selectedSourceId)
        assertEquals("v2ray-2", result?.selectedGuid)
        assertEquals(servers, result?.servers)
        assertTrue(result?.canConnect == true)
    }

    @Test
    fun selectsAetherTab() {
        val servers = listOf(server("aether-1"))

        val result = selectSimpleVpnSourceTab(
            current = SimpleVpnUiState(licenseValid = true),
            sourceId = SimpleVpnRepository.AETHER_SUB_ID,
            servers = servers,
            selectedGuid = "aether-1",
            canConnect = true,
        )

        assertEquals(SimpleVpnRepository.AETHER_SUB_ID, result?.selectedSourceId)
        assertEquals("aether-1", result?.selectedGuid)
    }

    @Test
    fun emptyTabHasNoSelectionAndCannotConnect() {
        val result = selectSimpleVpnSourceTab(
            current = SimpleVpnUiState(licenseValid = true, canConnect = true),
            sourceId = SimpleVpnRepository.AETHER_SUB_ID,
            servers = emptyList(),
            selectedGuid = null,
            canConnect = true,
        )

        assertTrue(result?.servers?.isEmpty() == true)
        assertNull(result?.selectedGuid)
        assertTrue(result?.canConnect == false)
    }

    @Test
    fun rejectsUnknownSourceWithoutChangingState() {
        val current = SimpleVpnUiState(licenseValid = true)

        val result = selectSimpleVpnSourceTab(
            current = current,
            sourceId = "unknown",
            servers = listOf(server("server-1")),
            selectedGuid = "server-1",
            canConnect = true,
        )

        assertNull(result)
    }

    @Test
    fun rejectsSelectionNotPresentInTheActiveTab() {
        val result = selectSimpleVpnSourceTab(
            current = SimpleVpnUiState(licenseValid = true),
            sourceId = SimpleVpnRepository.SIMPLE_SUB_ID,
            servers = listOf(server("v2ray-1")),
            selectedGuid = "aether-1",
            canConnect = true,
        )

        assertNull(result?.selectedGuid)
        assertTrue(result?.canConnect == false)
    }
}
