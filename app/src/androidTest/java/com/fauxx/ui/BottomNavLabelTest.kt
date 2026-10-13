package com.fauxx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.ui.navigation.BottomNavLabel
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for [BottomNavLabel] (#347).
 *
 * Renders a five-item [NavigationBar] at 320dp, the narrowest width the app supports, with the
 * bottom-nav labels of every shipped language (copied from values*, strings.xml `nav_*`), and
 * checks each label is laid out on a single line. The English labels must also fit without an
 * ellipsis.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BottomNavLabelTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private val english = listOf("Dashboard", "Targeting", "Modules", "Log", "Settings")
    private val spanish = listOf("Inicio", "Segmentación", "Módulos", "Registro", "Ajustes")
    private val french = listOf("Tableau de bord", "Ciblage", "Modules", "Journal", "Paramètres")
    private val russian = listOf("Панель", "Таргетинг", "Модули", "Журнал", "Настройки")

    @Test
    fun englishLabels_fitOnOneLineWithoutEllipsis_at320dp() {
        setNavBar(english)
        english.forEach { label ->
            val layout = textLayout(label)
            assertEquals("\"$label\" should be one line", 1, layout.lineCount)
            assertFalse("\"$label\" should not be ellipsized", layout.isLineEllipsized(0))
        }
    }

    @Test
    fun spanishLabels_stayOnOneLine_at320dp() = assertSingleLine(spanish)

    @Test
    fun frenchLabels_stayOnOneLine_at320dp() = assertSingleLine(french)

    @Test
    fun russianLabels_stayOnOneLine_at320dp() = assertSingleLine(russian)

    private fun assertSingleLine(labels: List<String>) {
        setNavBar(labels)
        labels.forEach { label ->
            assertEquals("\"$label\" should be one line", 1, textLayout(label).lineCount)
        }
    }

    private fun setNavBar(labels: List<String>) {
        composeRule.setContent {
            FauxxTheme {
                Box(Modifier.width(320.dp)) {
                    NavigationBar {
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                                label = { BottomNavLabel(label) },
                                selected = index == 0,
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun textLayout(label: String): TextLayoutResult {
        val node = composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }
}
