package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileReplacementTest {

    @Test
    fun `reuses existing guids by connection identity one time per profile`() {
        val existing = linkedMapOf(
            "old-a" to profile(remarks = "old label A", server = "host", port = "443", password = "secret"),
            "old-b" to profile(remarks = "old label B", server = "host", port = "443", password = "secret"),
        )
        val replacements = listOf(
            profile(remarks = "new label A", server = "host", port = "443", password = "secret"),
            profile(remarks = "new label B", server = "host", port = "443", password = "secret"),
            profile(remarks = "different", server = "other", port = "443", password = "secret"),
        )

        assertEquals(
            listOf("old-a", "old-b", null),
            ProfileReplacement.reusableGuids(replacements, existing),
        )
    }

    @Test
    fun `matches duplicate connection identities by label before using fallback order`() {
        val existing = linkedMapOf(
            "old-a" to profile(remarks = "A", server = "host", port = "443", password = "secret"),
            "old-b" to profile(remarks = "B", server = "host", port = "443", password = "secret"),
        )

        assertEquals(
            listOf("old-b", "old-a"),
            ProfileReplacement.reusableGuids(
                replacements = listOf(
                    profile(remarks = "B", server = "host", port = "443", password = "secret"),
                    profile(remarks = "A", server = "host", port = "443", password = "secret"),
                ),
                existingProfiles = existing,
            ),
        )
    }

    @Test
    fun `does not reuse a guid when connection settings changed`() {
        val existing = mapOf(
            "old" to profile(server = "host", port = "443", password = "old-secret"),
        )

        assertEquals(
            listOf(null),
            ProfileReplacement.reusableGuids(
                listOf(profile(server = "host", port = "443", password = "new-secret")),
                existing,
            ),
        )
    }

    @Test
    fun `prefers a full match over a remarks-only match`() {
        val profiles = linkedMapOf(
            "remarks" to profile(remarks = "selected", server = "other"),
            "full" to profile(remarks = "selected", server = "host", port = "443", password = "secret"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(
                remarks = " selected ",
                server = "HOST",
                port = "443",
                password = "secret",
            ),
        )

        assertEquals("full", result)
    }

    @Test
    fun `keeps the selected guid when the replacement reuses it`() {
        val profiles = linkedMapOf(
            "selected-guid" to profile(remarks = "renamed", server = "host"),
            "other-guid" to profile(remarks = "other", server = "other"),
        )

        assertEquals(
            "selected-guid",
            ProfileReplacement.findSelectedReplacement(
                profiles = profiles,
                currentSelection = "selected-guid",
                selectedProfile = profile(remarks = "old name", server = "host"),
            ),
        )
    }

    @Test
    fun `uses a remarks match when no full match exists`() {
        val profiles = linkedMapOf(
            "other" to profile(remarks = "other", server = "host"),
            "remarks" to profile(remarks = "selected", server = "other"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(remarks = "selected", server = "host"),
        )

        assertEquals("remarks", result)
    }

    @Test
    fun `matches endpoint and password when remarks are unavailable`() {
        val profiles = linkedMapOf(
            "endpoint" to profile(server = "host", port = "443", password = "secret"),
            "other" to profile(server = "other", port = "443", password = "secret"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(server = "HOST", port = "443", password = "secret"),
        )

        assertEquals("endpoint", result)
    }

    @Test
    fun `falls back to the first replacement profile`() {
        val profiles = linkedMapOf(
            "first" to profile(server = "first"),
            "second" to profile(server = "second"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(server = "unmatched"),
        )

        assertEquals("first", result)
    }

    @Test
    fun `selects the first profile when there is no current selection`() {
        val result = ProfileReplacement.findSelectedReplacement(
            profiles = linkedMapOf(
                "first" to profile(server = "first"),
                "second" to profile(server = "second"),
            ),
            currentSelection = null,
            selectedProfile = null,
        )

        assertEquals("first", result)
    }

    @Test
    fun `keeps an existing selection when it is outside the replaced group`() {
        val result = ProfileReplacement.findSelectedReplacement(
            profiles = mapOf("candidate" to profile(server = "host")),
            currentSelection = "other-group",
            selectedProfile = null,
        )

        assertNull(result)
    }

    @Test
    fun `removes only unreferenced superseded payloads`() {
        val result = ProfileReplacement.findRemovablePayloads(
            replacedServers = listOf("orphan", "selected", "replacement", "cross-group"),
            replacementServers = setOf("replacement"),
            protectedServer = "selected",
            serversReferencedByOtherGroups = setOf("cross-group"),
        )

        assertEquals(setOf("orphan"), result)
    }

    @Test
    fun `keeps all payloads when another group index is unreadable`() {
        val result = ProfileReplacement.findRemovablePayloads(
            replacedServers = listOf("candidate"),
            replacementServers = emptySet(),
            protectedServer = null,
            serversReferencedByOtherGroups = null,
        )

        assertEquals(emptySet<String>(), result)
    }

    private fun profile(
        remarks: String = "",
        server: String = "",
        port: String = "",
        password: String = "",
    ) = ProfileItem.create(EConfigType.VMESS).apply {
        this.remarks = remarks
        this.server = server
        this.serverPort = port
        this.password = password
    }
}
