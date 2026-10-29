package app.pureeq.headphones

import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.hearing.HeadphoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqTest {

    /** Lines as they appear in AutoEq's results/INDEX.md. */
    private val index = """
        # Index
        This is a list of all equalization profiles. Target is in parentheses if there are results with multiple targets
        from the same source.

        - [Apple AirPods Pro](./oratory1990/in-ear/Apple%20AirPods%20Pro) by oratory1990
        - [Apple AirPods Pro 2 (51dB + ANC)](./crinacle/711%20in-ear/Apple%20AirPods%20Pro%202%20(51dB%20+%20ANC)) by crinacle on 711
        - [Apple AirPods Pro 2](./Rtings/Bruel%20&%20Kjaer%205128%20in-ear/Apple%20AirPods%20Pro%202) by Rtings on Bruel & Kjaer 5128
        - [Samsung Galaxy Buds2](./crinacle/711%20in-ear/Samsung%20Galaxy%20Buds2) by crinacle on 711
        - [Samsung Galaxy Buds2 Pro](./crinacle/711%20in-ear/Samsung%20Galaxy%20Buds2%20Pro) by crinacle on 711
        - [Samsung Galaxy Buds2 Pro](./oratory1990/in-ear/Samsung%20Galaxy%20Buds2%20Pro) by oratory1990
        - [Samsung Galaxy Buds2 Pro (ANC mode)](./crinacle/711%20in-ear/Samsung%20Galaxy%20Buds2%20Pro%20(ANC%20mode)) by crinacle on 711
        - [Samsung Galaxy Buds FE](./DHRME/in-ear/Samsung%20Galaxy%20Buds%20FE) by DHRME
        - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
        - [Sony WF-1000XM5](./oratory1990/in-ear/Sony%20WF-1000XM5) by oratory1990
        - [Sony WH-1000XM5](./oratory1990/over-ear/Sony%20WH-1000XM5) by oratory1990
    """.trimIndent()

    private val entries = AutoEqIndex.parse(index)
    private val searchable = HeadphoneIndex(entries)

    @Test
    fun parsesTheIndex() {
        assertEquals(11, entries.size)
        val variant = entries[1]
        assertEquals("Apple AirPods Pro 2 (51dB + ANC)", variant.name)
        assertEquals("crinacle", variant.source)
        assertEquals("711", variant.rig)
        assertEquals("crinacle/711%20in-ear/Apple%20AirPods%20Pro%202%20(51dB%20+%20ANC)", variant.path)
        assertEquals("Bruel & Kjaer 5128", entries[2].rig)
        assertNull(entries[0].rig)
        assertEquals(HeadphoneType.IN_EAR, variant.type)
        assertEquals(HeadphoneType.OVER_EAR, entries.first { it.name == "Sennheiser HD 650" }.type)
    }

    @Test
    fun normalisesDeviceNames() {
        assertEquals(listOf("galaxy", "buds", "2", "pro"), HeadphoneMatcher.tokens("Galaxy Buds2 Pro (3A2F)"))
        assertEquals(listOf("airpods", "pro"), HeadphoneMatcher.tokens("Alex’s AirPods Pro"))
        assertEquals(listOf("wh", "1000", "xm", "5"), HeadphoneMatcher.tokens("LE_WH-1000XM5"))
    }

    @Test
    fun findsTheRightModelAndPrefersTrustedMeasurers() {
        val buds = HeadphoneMatcher.search(searchable, "Galaxy Buds2 Pro").map { it.entry }
        assertEquals("Samsung Galaxy Buds2 Pro", buds[0].name)
        assertEquals("oratory1990", buds[0].source)
        assertEquals("Samsung Galaxy Buds2 Pro", buds[1].name)
        assertEquals("crinacle", buds[1].source)
        // The plain Buds2 and the ANC variant rank below the exact model.
        assertTrue(buds.indexOfFirst { it.name == "Samsung Galaxy Buds2" } > 1)
        assertEquals("Sony WH-1000XM5", HeadphoneMatcher.search(searchable, "WH-1000XM5")[0].entry.name)
        assertEquals("Sony WF-1000XM5", HeadphoneMatcher.search(searchable, "WF-1000XM5")[0].entry.name)
        assertEquals("Apple AirPods Pro", HeadphoneMatcher.search(searchable, "My AirPods Pro")[0].entry.name)
        assertTrue(HeadphoneMatcher.search(searchable, "Phone speaker").isEmpty())
    }

    @Test
    fun readsAutoEqParametricCorrections() {
        // AutoEq's Sennheiser HD 650 (oratory1990) correction, as published.
        val text = """
            Preamp: -6.1 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
            Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
            Filter 3: ON PK Fc 118 Hz Gain -3.1 dB Q 0.50
            Filter 4: ON PK Fc 37 Hz Gain 0.7 dB Q 3.96
            Filter 5: ON PK Fc 3169 Hz Gain -1.7 dB Q 3.89
            Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
            Filter 7: ON PK Fc 1227 Hz Gain -1.2 dB Q 2.53
            Filter 8: ON PK Fc 2055 Hz Gain 1.2 dB Q 3.23
            Filter 9: ON PK Fc 587 Hz Gain 0.4 dB Q 1.19
            Filter 10: ON PK Fc 5332 Hz Gain -1.1 dB Q 5.75
        """.trimIndent()
        val f = ParametricEqText.parse(text)
        assertEquals(10, f.size)
        assertEquals(FilterSpec(FilterType.LOW_SHELF, 105.0, 6.4, 0.7), f[0])
        assertEquals(FilterSpec(FilterType.PEAK, 3169.0, -1.7, 3.89), f[4])
        assertEquals(FilterType.HIGH_SHELF, f[5].type)
    }
}
