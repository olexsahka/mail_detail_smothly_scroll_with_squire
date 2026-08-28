package com.alex.mailstubdetails.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `static routes have expected string values`() {
        assertEquals("inbox", Routes.INBOX)
        assertEquals("thread/{threadId}", Routes.THREAD)
        assertEquals("compose", Routes.COMPOSE)
    }

    @Test
    fun `thread(id) substitutes threadId path segment`() {
        assertEquals("thread/abc123", Routes.thread("abc123"))
    }

    @Test
    fun `thread(id) passes through unusual characters as-is`() {
        // Nav callers are responsible for URL encoding — Routes.thread
        // is a pure string builder and must not silently transform ids.
        assertEquals("thread/id with spaces", Routes.thread("id with spaces"))
    }
}
