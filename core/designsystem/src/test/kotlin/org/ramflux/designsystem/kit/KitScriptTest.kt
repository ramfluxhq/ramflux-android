// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitScriptTest {
    @Test fun theBundledLatinFaceIsOnlyChosenForLatinScriptLocales() {
        val latin = listOf("en", "de", "fr", "es", "pt-BR", "it", "tr", "id", "sr-Latn")
        val other = listOf("zh-CN", "zh-TW", "ja", "ko", "ru", "ar", "hi", "sr")
        latin.forEach { assertEquals(it, RfFonts.latin, RfFonts.forLocale(Locale.forLanguageTag(it))) }
        other.forEach { assertEquals(it, RfFonts.ui, RfFonts.forLocale(Locale.forLanguageTag(it))) }
    }

    @Test fun scriptsAreClassifiedPerString() {
        assertEquals(KitScript.Latin, KitScript.of("10:24 PDF"))
        assertEquals(KitScript.Other, KitScript.of("\u7248\u672c 10:24"))
        assertEquals(KitScript.Other, KitScript.of("\u041f\u0440\u0438\u0432\u0435\u0442"))
        assertEquals(KitScript.Tall, KitScript.of("Ada \u0645\u0631\u064a\u0645"))
        assertEquals(KitScript.Tall, KitScript.of("\u0928\u092e\u0938\u094d\u0924\u0947"))
    }

    @Test fun nonLatinTextDropsTheLatinFaceAndTrackingAndTallScriptsKeepTheFullLine() {
        val style = kitStyle(11f, FontWeight.Normal, androidx.compose.ui.graphics.Color.Black, RfMetrics.lineTitle, RfFonts.latin, 1f)
        assertEquals(style, style.forScript("PDF"))
        val cyrillic = style.forScript("\u0424\u0430\u0439\u043b")
        assertEquals(RfFonts.ui, cyrillic.fontFamily)
        assertEquals(0f.sp, cyrillic.letterSpacing)
        val arabic = style.forScript("\u0645\u0644\u0641")
        assertEquals(RfFonts.ui, arabic.fontFamily)
        assertEquals(0f.sp, arabic.letterSpacing)
        assertEquals((11f * RfMetrics.lineTallScript).sp, arabic.lineHeight)
        assertEquals(LineHeightStyle.Trim.None, arabic.lineHeightStyle?.trim)
    }
}
