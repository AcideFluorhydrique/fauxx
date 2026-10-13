package com.fauxx.targeting.layer3

import com.fauxx.locale.SupportedLocale
import kotlin.random.Random

/**
 * Per-locale persona names (#328), so a persona generating Spanish, French or Russian noise is not
 * called "Jordan Miller".
 *
 * The name is shown in the app (Targeting, the dashboard) and travels with the persona over LAN
 * sync; it is not sent to any site. It should still read as someone from the noise locale, since
 * the rest of the persona (region, interests, queries, sites) does.
 *
 * Lists are common given and family names for each language's default region (US, Spain, France,
 * Russia), picked from the most frequent names of the last few decades so a persona of any age
 * band is plausible. Given names are split by gender only because Russian surnames agree with it
 * (Иванов / Иванова); every Russian surname here takes the regular -ов/-ев/-ин form whose feminine
 * adds "а". Spanish names take two surnames (paternal, maternal), as on a Spanish ID card.
 *
 * Existing personas keep the name they were generated with: this only affects new rotations.
 */
object PersonaNames {

    private class NameTable(
        val femaleGiven: List<String>,
        val maleGiven: List<String>,
        val surnames: List<String>,
    )

    private val TABLES: Map<SupportedLocale, NameTable> = mapOf(
        SupportedLocale.EN to NameTable(
            femaleGiven = listOf(
                "Jennifer", "Jessica", "Ashley", "Sarah", "Emily", "Amanda", "Megan", "Lauren",
                "Rachel", "Nicole", "Michelle", "Elizabeth", "Hannah", "Olivia", "Emma", "Taylor",
            ),
            maleGiven = listOf(
                "Michael", "Christopher", "Matthew", "Joshua", "David", "Daniel", "James", "Ryan",
                "Andrew", "John", "Justin", "Brandon", "Tyler", "Kevin", "Jordan", "Alex",
            ),
            surnames = listOf(
                "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
                "Rodriguez", "Martinez", "Wilson", "Anderson", "Thomas", "Moore", "Jackson",
                "Martin", "Lee", "Thompson", "White", "Harris", "Clark", "Lewis", "Walker", "Hall",
            ),
        ),
        SupportedLocale.ES to NameTable(
            femaleGiven = listOf(
                "María", "Lucía", "Laura", "Carmen", "Ana", "Marta", "Paula", "Sara",
                "Cristina", "Elena", "Isabel", "Raquel", "Andrea", "Patricia", "Sofía", "Julia",
            ),
            maleGiven = listOf(
                "Antonio", "Manuel", "José", "Javier", "David", "Daniel", "Carlos", "Alejandro",
                "Pablo", "Sergio", "Jorge", "Alberto", "Rubén", "Adrián", "Álvaro", "Hugo",
            ),
            surnames = listOf(
                "García", "Rodríguez", "González", "Fernández", "López", "Martínez", "Sánchez",
                "Pérez", "Gómez", "Martín", "Jiménez", "Ruiz", "Hernández", "Díaz", "Moreno",
                "Muñoz", "Álvarez", "Romero", "Alonso", "Gutiérrez", "Navarro", "Torres",
            ),
        ),
        SupportedLocale.FR to NameTable(
            femaleGiven = listOf(
                "Marie", "Julie", "Camille", "Sophie", "Céline", "Aurélie", "Émilie", "Claire",
                "Sarah", "Laura", "Manon", "Léa", "Chloé", "Pauline", "Nathalie", "Isabelle",
            ),
            maleGiven = listOf(
                "Thomas", "Nicolas", "Julien", "Maxime", "Alexandre", "Antoine", "Kevin", "Romain",
                "Guillaume", "Sébastien", "Pierre", "Mathieu", "Lucas", "Hugo", "Olivier", "David",
            ),
            surnames = listOf(
                "Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand",
                "Leroy", "Moreau", "Simon", "Laurent", "Lefebvre", "Michel", "Garcia", "David",
                "Bertrand", "Roux", "Vincent", "Fournier", "Morel", "Girard", "Bonnet", "Mercier",
            ),
        ),
        SupportedLocale.RU to NameTable(
            femaleGiven = listOf(
                "Анна", "Мария", "Елена", "Ольга", "Наталья", "Екатерина", "Татьяна", "Ирина",
                "Светлана", "Юлия", "Анастасия", "Дарья", "Ксения", "Алина", "Виктория", "Полина",
            ),
            maleGiven = listOf(
                "Александр", "Дмитрий", "Максим", "Сергей", "Андрей", "Алексей", "Артём", "Илья",
                "Кирилл", "Михаил", "Иван", "Николай", "Евгений", "Денис", "Владимир", "Павел",
            ),
            // Masculine forms; the feminine is formed by [russianFeminine].
            surnames = listOf(
                "Иванов", "Смирнов", "Кузнецов", "Попов", "Васильев", "Петров", "Соколов",
                "Михайлов", "Новиков", "Фёдоров", "Морозов", "Волков", "Алексеев", "Лебедев",
                "Семёнов", "Егоров", "Павлов", "Козлов", "Степанов", "Николаев", "Орлов",
                "Захаров", "Зайцев", "Соловьёв", "Борисов", "Ильин", "Никитин", "Калинин",
            ),
        ),
    )

    /** A random full name for [locale]. Falls back to the English table for an unmapped locale. */
    fun generate(locale: SupportedLocale, random: Random): String {
        val table = TABLES[locale] ?: TABLES.getValue(SupportedLocale.EN)
        val female = random.nextBoolean()
        val given = (if (female) table.femaleGiven else table.maleGiven).random(random)
        return when (locale) {
            SupportedLocale.RU -> {
                val surname = table.surnames.random(random)
                "$given ${if (female) russianFeminine(surname) else surname}"
            }
            SupportedLocale.ES -> {
                val paternal = table.surnames.random(random)
                val maternal = table.surnames.filter { it != paternal }.random(random)
                "$given $paternal $maternal"
            }
            else -> "$given ${table.surnames.random(random)}"
        }
    }

    /**
     * Feminine form of a regular Russian surname: -ов, -ев, -ёв and -ин take "а" (Иванов ->
     * Иванова, Ильин -> Ильина). Anything else is returned unchanged; the table above only holds
     * the regular forms.
     */
    internal fun russianFeminine(surname: String): String =
        if (REGULAR_RU_SUFFIXES.any { surname.endsWith(it) }) "${surname}а" else surname

    private val REGULAR_RU_SUFFIXES = listOf("ов", "ев", "ёв", "ин")

    /** Test seam: every given name and surname for [locale], to check script and agreement. */
    internal fun namesFor(locale: SupportedLocale): Triple<List<String>, List<String>, List<String>> {
        val table = TABLES.getValue(locale)
        return Triple(table.femaleGiven, table.maleGiven, table.surnames)
    }
}
