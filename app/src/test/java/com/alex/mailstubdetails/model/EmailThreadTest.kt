package com.alex.mailstubdetails.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailThreadTest {

    private fun msg(id: String, isRead: Boolean = true): EmailMessage =
        EmailMessage(
            id = id,
            fromName = "Sender $id",
            fromEmail = "$id@example.com",
            toList = listOf("me@example.com"),
            subject = "Subject $id",
            date = "date",
            htmlBody = "<p>$id</p>",
            plainPreview = "preview $id",
            isRead = isRead
        )

    @Test
    fun `latestMessage returns the last message`() {
        val a = msg("a")
        val b = msg("b")
        val c = msg("c")
        val thread = EmailThread(id = "t", subject = "s", messages = listOf(a, b, c))

        assertSame(c, thread.latestMessage)
    }

    @Test
    fun `messageCount equals messages size`() {
        val thread = EmailThread(
            id = "t",
            subject = "s",
            messages = listOf(msg("a"), msg("b"))
        )
        assertEquals(2, thread.messageCount)
    }

    @Test
    fun `isUnread is true when any message is unread`() {
        val thread = EmailThread(
            id = "t",
            subject = "s",
            messages = listOf(msg("a", isRead = true), msg("b", isRead = false))
        )
        assertTrue(thread.isUnread)
    }

    @Test
    fun `isUnread is false when all messages are read`() {
        val thread = EmailThread(
            id = "t",
            subject = "s",
            messages = listOf(msg("a"), msg("b"))
        )
        assertFalse(thread.isUnread)
    }

    @Test
    fun `single-message thread reports messageCount = 1`() {
        val thread = EmailThread(id = "t", subject = "s", messages = listOf(msg("a")))
        assertEquals(1, thread.messageCount)
        assertSame(thread.messages.first(), thread.latestMessage)
    }
}
