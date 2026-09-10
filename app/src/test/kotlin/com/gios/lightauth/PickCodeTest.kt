package com.gios.lightauth

import com.gios.lightauth.data.StoredAccount
import kotlin.test.Test
import kotlin.test.assertEquals

class PickCodeTest {
    private fun acc(id: Long, issuer: String, label: String) =
        StoredAccount(id = id, issuer = issuer, label = label, digits = 6, period = 30, algorithm = "SHA1")

    @Test
    fun siteKeys() {
        assertEquals("google", PickCodeActivity.siteKey("accounts.google.com"))
        assertEquals("github", PickCodeActivity.siteKey("www.github.com"))
        assertEquals("bbc", PickCodeActivity.siteKey("www.bbc.co.uk"))
        assertEquals("localhost", PickCodeActivity.siteKey("localhost"))
        assertEquals("", PickCodeActivity.siteKey(""))
    }

    @Test
    fun matchingAccountsComeFirst() {
        val all = listOf(acc(1, "Microsoft", "gio@lrparis.com"), acc(2, "GitHub", "gi-os"), acc(3, "", "gio@github.com"))
        assertEquals(listOf(2L, 3L, 1L), PickCodeActivity.rank(all, "github.com").map { it.id })
        assertEquals(listOf(1L, 2L, 3L), PickCodeActivity.rank(all, "").map { it.id })
    }
}
