package app.momentmal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.momentmal.R
import app.momentmal.srs.Mastery

object MM {
    val Bg = Color(0xFF111418)
    val Surface = Color(0xFF1A1F25)
    val SurfaceHigh = Color(0xFF20262D)
    val Border = Color(0xFF262C33)
    val Text = Color(0xFFE7E3DA)
    val Muted = Color(0xFF8C939C)
    val Sage = Color(0xFF8FB3A3)

    val Red = Color(0xFFC88080)
    val Yellow = Color(0xFFD4B26E)
    val Green = Color(0xFF8DB895)
    val Blue = Color(0xFF8BA6C6)

    fun artikel(a: String?) = when (a) {
        "der" -> Blue
        "die" -> Red
        "das" -> Green
        else -> Text
    }

    /** Ordinal levels → a single sage ramp, darker = less learned (validated on the dark surface). */
    fun mastery(m: Mastery) = when (m) {
        Mastery.NEU -> Color(0xFF2C333B)
        Mastery.LERNEN -> Color(0xFF3E5A4F)
        Mastery.JUNG -> Color(0xFF5A8070)
        Mastery.GEFESTIGT -> Color(0xFF8FB3A3)
        Mastery.GEMEISTERT -> Color(0xFFC6DED2)
    }
}

val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
private fun literata(weight: Int, style: FontStyle = FontStyle.Normal) = Font(
    R.font.literata, FontWeight(weight), style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Reading font for card content. */
val Literata = FontFamily(literata(400), literata(500), literata(600))

private val typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = Atkinson, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = Atkinson, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Atkinson, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = Atkinson, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = Atkinson, fontWeight = FontWeight.Bold),
        bodyLarge = bodyLarge.copy(fontFamily = Atkinson),
        bodyMedium = bodyMedium.copy(fontFamily = Atkinson),
        bodySmall = bodySmall.copy(fontFamily = Atkinson),
        labelLarge = labelLarge.copy(fontFamily = Atkinson, fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.copy(fontFamily = Atkinson),
        labelSmall = labelSmall.copy(fontFamily = Atkinson, letterSpacing = 1.2.sp),
    )
}

object CardText {
    val big = TextStyle(fontFamily = Literata, fontWeight = FontWeight(500), fontSize = 25.sp, lineHeight = 34.sp, color = MM.Text)
    val body = TextStyle(fontFamily = Literata, fontSize = 18.sp, lineHeight = 28.sp, color = MM.Text)
    val exampleDe = TextStyle(fontFamily = Literata, fontStyle = FontStyle.Italic, fontSize = 17.sp, lineHeight = 26.sp, color = MM.Text)
    val muted = TextStyle(fontFamily = Atkinson, fontSize = 15.sp, lineHeight = 22.sp, color = MM.Muted)
    val label = TextStyle(fontFamily = Atkinson, fontSize = 12.sp, letterSpacing = 1.2.sp, color = MM.Muted)
    val hint = TextStyle(fontFamily = Atkinson, fontSize = 14.sp, lineHeight = 21.sp, color = MM.Sage)
}

@Composable
fun MomentMalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MM.Sage, onPrimary = MM.Bg,
            background = MM.Bg, onBackground = MM.Text,
            surface = MM.Surface, onSurface = MM.Text,
            surfaceVariant = MM.SurfaceHigh, onSurfaceVariant = MM.Muted,
            outline = MM.Border, error = MM.Red,
        ),
        typography = typography,
        content = content,
    )
}
