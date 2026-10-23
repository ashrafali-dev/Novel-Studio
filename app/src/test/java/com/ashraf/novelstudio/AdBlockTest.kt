package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure helper behind the "never block the site's own requests" rule (no Android types involved). */
class AdBlockTest {
    @Test fun cdnSubdomainOfTheSameSiteIsFirstParty() {
        assertEquals(AdBlock.registrable("toongod.org"), AdBlock.registrable("cdn.toongod.org"))
        assertEquals(AdBlock.registrable("www.toongod.org"), AdBlock.registrable("img1.cdn.toongod.org"))
    }

    @Test fun differentSitesAreThirdParty() {
        assert(AdBlock.registrable("toongod.org") != AdBlock.registrable("doubleclick.net"))
    }

    @Test fun twoLevelPublicSuffixesKeepThreeLabels() {
        assertEquals("site.co.uk", AdBlock.registrable("a.b.site.co.uk"))
        assertEquals("site.com.bd", AdBlock.registrable("m.site.com.bd"))
        assertEquals("example.com", AdBlock.registrable("example.com"))
    }
}
