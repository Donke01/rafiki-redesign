package org.rafikioutreach.pin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Reply
import org.rafikioutreach.pin.ui.mailResult
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MailResultTest {
    private fun r(ok: Boolean, status: Int, j: JSONObject) = Reply(ok, status, j, j.optString("reason").takeIf { it.isNotBlank() })

    @Test fun test_email_shows_why_resend_refused_it() {
        Lang.sw = false
        assertEquals("Sent ✓ Check the inbox.", mailResult(r(true, 200, JSONObject().put("ok", true))))
        assertEquals("Not sent. Resend said: The rafikioutreach.org domain is not verified.",
            mailResult(r(false, 503, JSONObject().put("ok", false).put("reason", "down").put("detail", "The rafikioutreach.org domain is not verified."))))
        assertEquals("E-mail is off: add RESEND_API_KEY and ALERT_FROM in Cloudflare, then redeploy.",
            mailResult(r(false, 503, JSONObject().put("ok", false).put("reason", "no-mail"))))
        assertEquals("Not sent: no connection. Try again with signal.", mailResult(r(false, 0, JSONObject().put("ok", false).put("reason", "down"))))
    }
}
