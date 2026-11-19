package app.murmur.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {
    private val t = DesignSource.tokens

    /** Color is an inline class: the backing field holds its packed Long. */
    private fun MurmurColors.byName(name: String): Color = Color((field(this, name) as Long).toULong())

    @Test
    fun accentIsUltramarine() {
        // DESIGN decision 2: one accent, #2F4BE8 (light) / #8296FF (dark).
        assertEquals(0xFF2F4BE8.toInt(), TokenColors.light.accent.toArgb())
        assertEquals(0xFF8296FF.toInt(), TokenColors.dark.accent.toArgb())
    }

    @Test
    fun everyColorRoleIsPresentInBothThemesWithItsTokenValue() {
        val color = t.getValue("color").jsonObject
        val fields = MurmurColors::class.java.declaredFields.map { it.name }.filter { !it.contains('$') }.toSet()
        for (theme in listOf("light", "dark")) {
            val roles = color.getValue(theme).jsonObject
            val colors = if (theme == "light") TokenColors.light else TokenColors.dark
            assertEquals("roles of $theme", roles.keys, fields)
            assertEquals(25, roles.size)
            for ((role, v) in roles) {
                val hex = v.jsonObject.getValue("\$value").jsonPrimitive.content
                assertEquals("$theme.$role", DesignSource.argb(hex), colors.byName(role).toArgb())
            }
        }
        val palette = color.getValue("appTile").jsonObject.getValue("palette").jsonArray.map { DesignSource.argb(it.jsonPrimitive.content) }
        assertEquals(palette, TokenColors.appTilePalette.map { it.toArgb() })
    }

    @Test
    fun typeSpecsMatchTokensAndTextStylesFollowThem() {
        val typo = t.getValue("typography").jsonObject
        assertEquals(22, typo.size)
        assertEquals(typo.keys.toList(), TokenType.all.keys.toList())
        val styles = MurmurType.Default.all
        assertEquals(typo.keys.toList(), styles.keys.toList())
        for ((name, raw) in typo) {
            val v = raw.jsonObject
            val spec = TokenType.all.getValue(name)
            val style = styles.getValue(name)
            assertEquals(name, v.int("fontSize"), spec.size)
            assertEquals(name, v.int("lineHeight"), spec.line)
            assertEquals(name, v.int("fontWeight"), spec.weight)
            assertEquals(name, v.float("letterSpacing"), spec.tracking, 0f)
            assertEquals(name, spec.size.sp, style.fontSize)
            assertEquals(name, spec.line.sp, style.lineHeight)
            assertEquals(name, FontWeight(spec.weight), style.fontWeight)
            assertEquals(name, spec.tracking.sp, style.letterSpacing)
            assertEquals(name, if (v["fontFamily"]!!.jsonPrimitive.content == "mono") MurmurFonts.mono else MurmurFonts.sans, style.fontFamily)
            assertEquals(name, v["fontFeatures"]?.jsonPrimitive?.content == "tnum", style.fontFeatureSettings == "tnum")
            assertFalse(name, style.platformStyle!!.paragraphStyle!!.includeFontPadding)
        }
        // DESIGN 3.2 spot checks.
        assertEquals(44, TokenType.display.size)
        assertEquals(48, TokenType.display.line)
        assertEquals(28, TokenType.headline.size)
        assertEquals(11, TokenType.overline.size)
        assertTrue(TokenType.overline.uppercase)
        assertEquals("TODAY", MurmurType.Default.transform("overline", "Today"))
        assertEquals("Today", MurmurType.Default.transform("caption", "Today"))
        assertNull(MurmurType.Default.body.fontFeatureSettings)
        assertEquals("tnum", MurmurType.Default.monoTimer.fontFeatureSettings)
    }

    @Test
    fun spacingRadiusSizesAndLayoutMatchTokens() {
        val spacing = t.getValue("spacing").jsonObject.filterKeys { !it.startsWith("$") }
        for ((k, v) in spacing) assertEquals(k, v.jsonPrimitive.float, field(Spacing, k).dpValue(), 0f)
        val radius = t.getValue("radius").jsonObject.filterKeys { !it.startsWith("$") }
        for ((k, v) in radius) assertEquals(k, v.jsonPrimitive.float, field(Radius, k).dpValue(), 0f)
        assertEquals(48f, Sizes.touchTargetMin.value, 0f)
        assertEquals(1.75f, Sizes.iconStroke.value, 0f)
        assertEquals(52f, Sizes.toggleW.value, 0f)
        assertEquals(22f, Sizes.bubbleEdgeTabW.value, 0f)
        assertEquals(7, Sizes.bubbleLevelBarsCount)
        assertEquals(300f, Layout.historyListPane.value, 0f)
        assertEquals(600f, Layout.mediumMinWidth.value, 0f)
    }

    @Test
    fun elevationAndMotionMatchTokens() {
        val elev = t.getValue("elevation").jsonObject
        for ((k, raw) in elev) {
            val spec = field(Elevation, k) as ElevationSpec
            val shadow = raw.jsonObject["shadow"]
            val layers = if (shadow is kotlinx.serialization.json.JsonArray) shadow.map { it.jsonObject } else emptyList()
            assertEquals(k, layers.size, spec.layers.size)
            layers.zip(spec.layers).forEach { (l, s) ->
                assertEquals(l.float("y"), s.y.value, 0f)
                assertEquals(l.float("blur"), s.blur.value, 0f)
                assertEquals(DesignSource.argb(l.getValue("color").jsonPrimitive.content), s.color.toArgb())
            }
        }
        assertEquals(0, Elevation.e0.layers.size)
        val motion = t.getValue("motion").jsonObject
        assertEquals(150, Motion.shortMs)
        assertEquals(motion.getValue("duration").jsonObject.int("long"), Motion.longMs)
        assertEquals(4000, Motion.tuckAfterIdleMs)
        assertEquals(30, Motion.levelSampleRateHz)
        assertEquals(8f, Motion.dragSlop.value, 0f)
        val snap = motion.getValue("spring").jsonObject.getValue("bubbleSnap").jsonObject
        assertEquals(snap.float("stiffness"), Motion.bubbleSnapSpring.stiffness, 0f)
        assertEquals(snap.float("dampingRatio"), Motion.bubbleSnapSpring.dampingRatio, 0f)
        assertEquals(1500f, Motion.bubbleMorphSpring.stiffness, 0f)
        assertEquals(0.75f, Motion.bubbleAppearSpring.dampingRatio, 0f)
        // standard easing (0.2, 0, 0, 1): halfway in time is well past halfway in progress.
        assertTrue(Motion.standardEasing.transform(0.5f) > 0.7f)
        assertEquals(1f, Motion.decelerateEasing.transform(1f), 1e-4f)
    }

    /** Reads a property's backing field (getters of inline-class properties have mangled JVM names). */
    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    /** Dp is an inline Float class: reflection hands back the boxed Float. */
    private fun Any?.dpValue(): Float = this as Float

    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.float(k: String) = getValue(k).jsonPrimitive.float
}
