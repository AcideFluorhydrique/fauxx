package com.fauxx.targeting

import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer3.PersonaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Persona names follow the noise locale (#328). */
class PersonaNamesTest {

    private val cyrillic = Regex("^[\\p{IsCyrillic} ]+$")
    private val latin = Regex("^[\\p{IsLatin} ]+$")

    private fun names(locale: SupportedLocale, n: Int = 500, seed: Int = 328) =
        Random(seed).let { r -> List(n) { PersonaNames.generate(locale, r) } }

    @Test
    fun `English names are Latin given name plus surname`() {
        names(SupportedLocale.EN).forEach {
            assertTrue(it, latin.matches(it))
            assertEquals(it, 2, it.split(' ').size)
        }
    }

    @Test
    fun `Spanish names carry two different surnames`() {
        names(SupportedLocale.ES).forEach {
            assertTrue(it, latin.matches(it))
            val parts = it.split(' ')
            assertEquals(it, 3, parts.size)
            assertNotEquals(it, parts[1], parts[2])
        }
    }

    @Test
    fun `French names come from the French table`() {
        val (female, male, surnames) = PersonaNames.namesFor(SupportedLocale.FR)
        names(SupportedLocale.FR).forEach {
            val (given, surname) = it.split(' ')
            assertTrue(it, given in female || given in male)
            assertTrue(it, surname in surnames)
        }
    }

    @Test
    fun `Russian names are Cyrillic and the surname agrees with the given name`() {
        val (female, male, surnames) = PersonaNames.namesFor(SupportedLocale.RU)
        var sawFemale = false
        var sawMale = false
        names(SupportedLocale.RU).forEach {
            assertTrue(it, cyrillic.matches(it))
            val (given, surname) = it.split(' ')
            when (given) {
                in female -> {
                    sawFemale = true
                    assertTrue("$it: feminine surname expected", surname.endsWith("а"))
                    assertTrue(it, surname.dropLast(1) in surnames)
                }
                in male -> {
                    sawMale = true
                    assertTrue("$it: masculine surname expected", surname in surnames)
                }
                else -> throw AssertionError("unknown given name in $it")
            }
        }
        assertTrue(sawFemale && sawMale)
    }

    @Test
    fun `every Russian surname takes the regular feminine form`() {
        val (_, _, surnames) = PersonaNames.namesFor(SupportedLocale.RU)
        surnames.forEach { assertEquals("${it}а", PersonaNames.russianFeminine(it)) }
        assertEquals("Иванова", PersonaNames.russianFeminine("Иванов"))
        assertEquals("Ильина", PersonaNames.russianFeminine("Ильин"))
        // An irregular surname is left alone rather than mangled.
        assertEquals("Черных", PersonaNames.russianFeminine("Черных"))
    }

    @Test
    fun `names vary and every locale has a table`() {
        SupportedLocale.entries.forEach { locale ->
            assertTrue("$locale names should vary", names(locale, 200).toSet().size > 50)
        }
    }
}
