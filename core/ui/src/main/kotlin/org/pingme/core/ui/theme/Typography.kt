// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import org.pingme.core.ui.R
import java.io.File
import java.io.RandomAccessFile

@get:FontRes
val BundledFont.resource: Int
    get() =
        when (this) {
            BundledFont.ROBOTO_FLEX -> R.font.roboto_flex
            BundledFont.INTER -> R.font.inter
            BundledFont.MANROPE -> R.font.manrope
            BundledFont.NUNITO -> R.font.nunito
            BundledFont.LEXEND -> R.font.lexend
            BundledFont.ATKINSON_HYPERLEGIBLE -> R.font.atkinson_hyperlegible_next
            BundledFont.JETBRAINS_MONO -> R.font.jetbrains_mono
        }

private val WEIGHTS = (100..900 step 100).map(::FontWeight)

/**
 * The font family for a choice (UI_DESIGN.md 4.4). Variable fonts get one instance per
 * weight, set on the font's weight axis, so emphasized styles are true weights. An
 * imported font that is not variable is used as it is, and bold is synthesized from it.
 * An imported file that has gone missing falls back to Roboto Flex.
 */
fun fontFamilyFor(choice: FontChoice): FontFamily =
    when (choice) {
        is FontChoice.Bundled -> {
            FontFamily(
                WEIGHTS.map {
                    Font(
                        choice.font.resource,
                        it,
                        variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)),
                    )
                },
            )
        }

        is FontChoice.Imported -> {
            val file = File(choice.path)
            when {
                !file.isFile -> {
                    fontFamilyFor(FontChoice.Bundled(BundledFont.ROBOTO_FLEX))
                }

                isVariableFont(file) -> {
                    FontFamily(
                        WEIGHTS.map {
                            Font(
                                file,
                                it,
                                variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)),
                            )
                        },
                    )
                }

                else -> {
                    FontFamily(Font(file))
                }
            }
        }
    }

/** Whether a TrueType/OpenType file has a weight axis to vary: it carries an `fvar` table. */
fun isVariableFont(file: File): Boolean =
    runCatching {
        RandomAccessFile(file, "r").use { font ->
            font.skipBytes(SFNT_VERSION_BYTES)
            val tables = font.readUnsignedShort()
            font.skipBytes(SFNT_HEADER_REST)
            (0 until tables).any {
                val tag = ByteArray(TAG_BYTES).also(font::readFully)
                font.skipBytes(TABLE_RECORD_REST)
                String(tag, Charsets.US_ASCII) == "fvar"
            }
        }
    }.getOrDefault(false)

private const val SFNT_VERSION_BYTES = 4
private const val SFNT_HEADER_REST = 6
private const val TAG_BYTES = 4
private const val TABLE_RECORD_REST = 12

/**
 * The app's type scale: Material 3's scale (including the Expressive emphasized styles)
 * in the chosen UI font, sized by the in-app text size and line height on top of the
 * system font scale.
 */
fun pingMeTypography(
    family: FontFamily,
    fontScale: Float,
    lineHeightScale: Float,
): Typography {
    val base = Typography()

    fun TextStyle.scaled() =
        copy(
            fontFamily = family,
            fontSize = fontSize * fontScale,
            lineHeight = lineHeight.times(fontScale * lineHeightScale),
        )
    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
        displayLargeEmphasized = base.displayLargeEmphasized.scaled(),
        displayMediumEmphasized = base.displayMediumEmphasized.scaled(),
        displaySmallEmphasized = base.displaySmallEmphasized.scaled(),
        headlineLargeEmphasized = base.headlineLargeEmphasized.scaled(),
        headlineMediumEmphasized = base.headlineMediumEmphasized.scaled(),
        headlineSmallEmphasized = base.headlineSmallEmphasized.scaled(),
        titleLargeEmphasized = base.titleLargeEmphasized.scaled(),
        titleMediumEmphasized = base.titleMediumEmphasized.scaled(),
        titleSmallEmphasized = base.titleSmallEmphasized.scaled(),
        bodyLargeEmphasized = base.bodyLargeEmphasized.scaled(),
        bodyMediumEmphasized = base.bodyMediumEmphasized.scaled(),
        bodySmallEmphasized = base.bodySmallEmphasized.scaled(),
        labelLargeEmphasized = base.labelLargeEmphasized.scaled(),
        labelMediumEmphasized = base.labelMediumEmphasized.scaled(),
        labelSmallEmphasized = base.labelSmallEmphasized.scaled(),
    )
}

private fun TextUnit.times(factor: Float): TextUnit = if (isSpecified) this * factor else this
