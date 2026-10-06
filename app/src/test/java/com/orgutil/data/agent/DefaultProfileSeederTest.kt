package com.orgutil.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FM-P9: the legacy global Anthropic credentials seed a default
 * profile exactly once; an explicit default profile always wins.
 */
class DefaultProfileSeederTest {

    @Test
    fun `no legacy key and no profiles means nothing to seed`() {
        assertEquals(null, DefaultProfileSeeder.seed(legacyKey = null, legacyModel = "claude-sonnet-5"))
        assertEquals(null, DefaultProfileSeeder.seed(legacyKey = "  ", legacyModel = "claude-sonnet-5"))
    }

    @Test
    fun `legacy key and model become the default anthropic profile`() {
        val profile = DefaultProfileSeeder.seed(legacyKey = "sk-ant-legacy", legacyModel = "claude-sonnet-5")!!

        assertEquals("ANTHROPIC", profile.type)
        assertEquals("https://api.anthropic.com", profile.baseUrl)
        assertEquals("claude-sonnet-5", profile.model)
        assertTrue(profile.isDefault)
        assertTrue(profile.name.isNotBlank())
    }

    @Test
    fun `legacy key with default model still seeds`() {
        val profile = DefaultProfileSeeder.seed(legacyKey = "sk-ant-legacy", legacyModel = null)!!
        assertTrue(profile.model.isNotBlank())
    }
}
