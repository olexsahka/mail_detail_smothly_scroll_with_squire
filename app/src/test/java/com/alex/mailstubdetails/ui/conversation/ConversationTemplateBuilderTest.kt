package com.alex.mailstubdetails.ui.conversation

import com.alex.mailstubdetails.model.EmailMessage
import com.alex.mailstubdetails.model.EmailThread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTemplateBuilderTest {

    private fun msg(id: String, html: String = "<p>$id</p>") = EmailMessage(
        id = id,
        fromName = "n",
        fromEmail = "$id@e.com",
        toList = listOf("me@e.com"),
        subject = "s",
        date = "d",
        htmlBody = html,
        plainPreview = "p"
    )

    private fun thread(id: String, vararg msgs: EmailMessage) =
        EmailThread(id = id, subject = "subj-$id", messages = msgs.toList())

    @Test
    fun `payload has thread meta and one entry per message`() {
        val t = thread("t1", msg("a"), msg("b"), msg("c"))

        val json = JSONObject(ConversationTemplateBuilder.buildPayload(t, emptySet(), emptySet()))

        assertEquals("t1", json.getString("threadId"))
        assertEquals("subj-t1", json.getString("subject"))
        assertEquals(3, json.getJSONArray("messages").length())
    }

    @Test
    fun `only loaded messages carry an html field`() {
        val t = thread("t", msg("a", html = "<p>A</p>"), msg("b", html = "<p>B</p>"))

        val json = JSONObject(
            ConversationTemplateBuilder.buildPayload(
                thread = t,
                expandedIds = setOf("a", "b"),
                loadedIds = setOf("a")
            )
        )
        val arr = json.getJSONArray("messages")
        val a = arr.getJSONObject(0)
        val b = arr.getJSONObject(1)

        assertTrue(a.has("html"))
        assertEquals("<p>A</p>", a.getString("html"))
        assertFalse("unloaded message must not leak html into DOM", b.has("html"))
    }

    @Test
    fun `expanded and loaded flags mirror input sets`() {
        val t = thread("t", msg("a"), msg("b"), msg("c"))

        val json = JSONObject(
            ConversationTemplateBuilder.buildPayload(
                thread = t,
                expandedIds = setOf("a", "c"),
                loadedIds = setOf("b")
            )
        )
        val arr = json.getJSONArray("messages")

        assertTrue(arr.getJSONObject(0).getBoolean("expanded"))
        assertFalse(arr.getJSONObject(0).getBoolean("loaded"))

        assertFalse(arr.getJSONObject(1).getBoolean("expanded"))
        assertTrue(arr.getJSONObject(1).getBoolean("loaded"))

        assertTrue(arr.getJSONObject(2).getBoolean("expanded"))
        assertFalse(arr.getJSONObject(2).getBoolean("loaded"))
    }

    @Test
    fun `message id survives round-trip`() {
        val t = thread("t", msg("weird'id\"with\\chars"))
        val json = JSONObject(ConversationTemplateBuilder.buildPayload(t, emptySet(), emptySet()))
        assertEquals(
            "weird'id\"with\\chars",
            json.getJSONArray("messages").getJSONObject(0).getString("id")
        )
    }

    @Test
    fun `defaultExpandedIds returns first message id`() {
        val t = thread("t", msg("first"), msg("second"))
        assertEquals(setOf("first"), ConversationTemplateBuilder.defaultExpandedIds(t))
    }

    @Test
    fun `defaultExpandedIds returns empty set for empty thread`() {
        val t = EmailThread(id = "t", subject = "s", messages = emptyList())
        assertTrue(ConversationTemplateBuilder.defaultExpandedIds(t).isEmpty())
    }

    @Test
    fun `TEMPLATE_URL is composed from BASE_URL and TEMPLATE_PATH`() {
        assertEquals(
            ConversationTemplateBuilder.BASE_URL + ConversationTemplateBuilder.TEMPLATE_PATH,
            ConversationTemplateBuilder.TEMPLATE_URL
        )
    }
}
