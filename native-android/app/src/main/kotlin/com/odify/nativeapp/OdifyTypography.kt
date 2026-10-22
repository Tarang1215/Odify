package com.odify.nativeapp

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Pretendard is distributed under the SIL Open Font License (OFL). */
val OdifyFontFamily = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold)
)

private fun TextStyle.odify() = copy(fontFamily = OdifyFontFamily)

/** Material3 has no defaultFontFamily argument; override all 15 text styles. */
val OdifyTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.odify(),
        displayMedium = base.displayMedium.odify(),
        displaySmall = base.displaySmall.odify(),
        headlineLarge = base.headlineLarge.odify(),
        headlineMedium = base.headlineMedium.odify(),
        headlineSmall = base.headlineSmall.odify(),
        titleLarge = base.titleLarge.odify(),
        titleMedium = base.titleMedium.odify(),
        titleSmall = base.titleSmall.odify(),
        bodyLarge = base.bodyLarge.odify(),
        bodyMedium = base.bodyMedium.odify(),
        bodySmall = base.bodySmall.odify(),
        labelLarge = base.labelLarge.odify(),
        labelMedium = base.labelMedium.odify(),
        labelSmall = base.labelSmall.odify()
    )
}
