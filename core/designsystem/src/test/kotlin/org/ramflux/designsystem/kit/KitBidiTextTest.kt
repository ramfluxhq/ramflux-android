// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.core.designsystem.R
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class KitBidiTextTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val deadline = KitDeadline(60)

    @Test fun overridesAndEmbeddingsInNamesBecomeVisibleAndInert() {
        assertEquals("\uFFFDevil\uFFFD", Bidi.sanitize("\u202Eevil\u202C", UntrustedKind.Name))
        assertEquals("ab", Bidi.sanitize("a\u200Fb", UntrustedKind.Name))
        assertEquals("ab\u200F", Bidi.sanitize("a\u202Bb\u200F", UntrustedKind.Excerpt))
        assertTrue(Bidi.hasControls("x\u2067y"))
        assertFalse(Bidi.hasControls("plain"))
    }

    @Test fun anUnbalancedIsolateInANameCannotSwallowTheSentence() {
        var rendered = ""
        compose.setContent {
            RamfluxTheme { rendered = uiText(R.string.unread_count, UiText.Untrusted("\u0645\u0631\u064A\u0645\u2067")).render() }
        }
        compose.runOnIdle {
            assertEquals("\u2068\u0645\u0631\u064A\u0645\uFFFD\u2069 unread", rendered)
            assertEquals(1, rendered.count { it == Bidi.FSI })
            assertEquals(1, rendered.count { it == Bidi.PDI })
        }
    }

    @Test fun aReversedFileExtensionIsShownAsTheRealOne() {
        val name = "invoice\u202Efdp.exe"
        val parts = Bidi.splitFileName(name)
        assertEquals("exe", parts.extension)
        assertEquals("invoice\uFFFDfdp", parts.base)
        assertEquals("invoice\uFFFDfdp\u2066.exe\u2069", untrustedDisplay(name, UntrustedKind.FileName))
        assertEquals("\u2068invoice\uFFFDfdp\u2066.exe\u2069\u2069", Bidi.isolate(name, UntrustedKind.FileName))
        compose.setContent { RamfluxTheme { UntrustedText(name, kind = UntrustedKind.FileName) } }
        compose.onNodeWithText("invoice\uFFFDfdp\u2066.exe\u2069").assertExists()
    }

    @Test fun codesUseAsciiDigitsAndDropControls() {
        assertEquals(listOf("1234", "5678"), ltrCodeGroups("\u0661\u0662\u0663\u0664 \u06F5\u06F6\u06F7\u06F8"))
        assertEquals(listOf("ABCD", "EF12"), ltrCodeGroups("AB\u202ECD EF12"))
        assertEquals(listOf("1234", "5678", "90"), ltrCodeGroups("1234 56\n7890", groupSize = 4))
        assertEquals(listOf("1 apple", "2 river"), ltrCodeGroups("apple  river", numbered = true))
        assertEquals("\u20661234 5678\u2069", Bidi.ltr("\u0661\u0662\u0663\u0664 5678"))
    }

    @Test fun codeGroupsKeepLeftToRightOrderInARightToLeftLayout() {
        compose.setContent {
            RamfluxTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(Modifier.width(360.dp)) { LtrCode("1234 5678 9012", tag = "code") }
                }
            }
        }
        val lefts = listOf("1234", "5678", "9012").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
        val direction = layout("5678").getParagraphDirection(0)
        assertEquals(ResolvedTextDirection.Ltr, direction)
    }

    @Test fun untrustedTextTakesItsDirectionFromItsContent() {
        compose.setContent {
            RamfluxTheme {
                Column {
                    UntrustedText("\u0645\u0631\u064A\u0645 Ada")
                    CompositionLocalProvider(
                        LocalLayoutDirection provides LayoutDirection.Rtl
                    ) { UntrustedText("Ada \u0645\u0631\u064A\u0645") }
                }
            }
        }
        assertEquals(ResolvedTextDirection.Rtl, layout("\u0645\u0631\u064A\u0645 Ada").getParagraphDirection(0))
        assertEquals(ResolvedTextDirection.Ltr, layout("Ada \u0645\u0631\u064A\u0645").getParagraphDirection(0))
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)
        checkNotNull(action).action?.invoke(results)
        return results.single()
    }
}
