package org.rafikioutreach.pin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.rafikioutreach.pin.data.Currency
import org.rafikioutreach.pin.data.Money

/** Settings › Currency: the country picks the start, amounts convert with ≈ and keep the KES next to them. */
class CurrencyTest {
    @After fun back() { Currency.code.value = "KES" }

    @Test fun countryPicksTheCurrency() {
        assertEquals("USD", Currency.forCountry("us"))
        assertEquals("KES", Currency.forCountry("KE"))
        assertEquals("UGX", Currency.forCountry("UG"))
        assertEquals("TZS", Currency.forCountry("TZ"))
        assertEquals("MWK", Currency.forCountry("MW"))
        assertEquals("EUR", Currency.forCountry("DE"))
        assertEquals("", Currency.forCountry("JP"))
    }

    @Test fun automaticFollowsWhereThePhoneIsThenTheHomeCountry() {
        assertEquals("USD", Currency.autoFor("US", "ke", "KE")) // in the USA: dollars
        assertEquals("KES", Currency.autoFor("", "ke", "US"))   // not known where: the home country, not the phone's language
        assertEquals("UGX", Currency.autoFor("", "", "UG"))
        assertEquals("USD", Currency.autoFor("", "", ""))
        assertEquals("KES", Currency.autoFor("JP", "ke", ""))   // not one of ours: the next one known
    }

    @Test fun kesStaysAsEntered() {
        Currency.code.value = "KES"
        assertEquals("KES 4,500", Money.kes(4500))
    }

    @Test fun otherCurrenciesShowApproxWithKes() {
        Currency.code.value = "USD"
        assertEquals("≈ $35 (KES 4,500)", Money.kes(4500)) // 4,500 / 129
        Currency.code.value = "UGX"
        assertEquals("≈ UGX 127,000 (KES 4,500)", Money.kes(4500)) // rounded to the nearest 1,000
        assertEquals(127000L, Currency.num(4500))
    }
}
