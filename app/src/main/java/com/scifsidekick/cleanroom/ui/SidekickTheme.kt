package com.scifsidekick.cleanroom.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

enum class ThemeMode(
    val storageKey: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageKey == value } ?: SYSTEM
    }
}

/**
 * Preset accent swatches. Dark-mode tones are deliberately vivid/saturated rather than pale
 * pastels: `primary` is used directly as button/switch fill and the status bar color, always
 * paired with a light `onPrimary`, so a washed-out tone reads as illegible near-white-on-white.
 */
enum class AccentChoice(
    val storageKey: String,
    val label: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
) {
    RED("red", "Red", Color(0xFF8B0000), Color(0xFFB71C1C)),
    BLUE("blue", "Blue", Color(0xFF1565C0), Color(0xFF3D8BFF)),
    GREEN("green", "Green", Color(0xFF2E7D32), Color(0xFF43A047)),
    PURPLE("purple", "Purple", Color(0xFF7B1FA2), Color(0xFFAB47BC)),
    ORANGE("orange", "Orange", Color(0xFFB84D00), Color(0xFFF57C00)),
    TEAL("teal", "Teal", Color(0xFF006A67), Color(0xFF00897B)),
    ;

    companion object {
        fun fromStorage(value: String?): AccentChoice = entries.firstOrNull { it.storageKey == value } ?: RED
    }
}

enum class FontScale(
    val key: String,
    val label: String,
    val scale: Float,
) {
    SMALL("small", "Small", 0.9f),
    DEFAULT_SIZE("default", "Default", 1.0f),
    LARGE("large", "Large", 1.15f),
    EXTRA_LARGE("extra_large", "Extra large", 1.3f),
    ;

    companion object {
        fun fromKey(key: String): FontScale = entries.firstOrNull { it.key == key } ?: DEFAULT_SIZE
    }
}

/**
 * How many equal-width columns a preset-picker grid (theme, accent color, text size) gets at a
 * given text size. [compact] is the column count at Small/Default; Large and Extra large step
 * down to make room for the same single-word labels rendered bigger, rather than letting any
 * column get narrower than its own text -- fewer, wider columns rather than same-width text
 * crammed into a fixed column. Never below 1: a "grid" of one column is just a stack of
 * full-width rows, the safest possible layout at the largest size. Pure and UI-framework-free
 * so it can be unit- and fuzz-tested without a device.
 */
fun columnsFor(
    scale: FontScale,
    compact: Int,
): Int =
    when (scale) {
        FontScale.SMALL, FontScale.DEFAULT_SIZE -> compact.coerceAtLeast(1)
        FontScale.LARGE, FontScale.EXTRA_LARGE -> (compact - 1).coerceAtLeast(1)
    }

/** Either a built-in swatch, an exact color the user picked from the color wheel, or the
 *  hidden [RainbowRoad] easter egg (see [AppearancePreferences.rainbowRoadUnlocked]). */
sealed interface AccentSelection {
    data class Preset(
        val choice: AccentChoice,
    ) : AccentSelection

    data class Custom(
        val color: Color,
    ) : AccentSelection

    /** RGB-on-a-gaming-PC: the accent hue continuously cycles instead of holding still. Purely
     *  cosmetic -- every other color role still derives from it via the same [contrastingOn]
     *  logic every other accent uses, so contrast stays correct at every point in the cycle. */
    data object RainbowRoad : AccentSelection
}

/** Non-animated accent color, for call sites (the color wheel's initial seed, e.g.) that need a
 *  single static value rather than the live cycling [SidekickTheme] renders for [AccentSelection.RainbowRoad].*/
fun AccentSelection.primaryFor(dark: Boolean): Color =
    when (this) {
        is AccentSelection.Preset -> if (dark) choice.darkPrimary else choice.lightPrimary
        is AccentSelection.Custom -> color
        is AccentSelection.RainbowRoad -> Color(0xFFE040FB)
    }

fun AccentSelection.label(): String =
    when (this) {
        is AccentSelection.Preset -> choice.label
        is AccentSelection.Custom -> "Custom"
        is AccentSelection.RainbowRoad -> "Rainbow Road 🌈"
    }

/** Readable foreground (black or white) for content drawn on top of [color]. */
private fun contrastingOn(color: Color): Color = if (color.luminance() > 0.5f) Color.Black else Color.White

class AppearancePreferences(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode = ThemeMode.fromStorage(preferences.getString(KEY_THEME, null))

    fun accentSelection(): AccentSelection {
        val key = preferences.getString(KEY_ACCENT, null)
        return when (key) {
            CUSTOM_KEY -> AccentSelection.Custom(Color(preferences.getInt(KEY_CUSTOM_COLOR, AccentChoice.RED.lightPrimary.toArgb())))
            RAINBOW_ROAD_KEY -> AccentSelection.RainbowRoad
            else -> AccentSelection.Preset(AccentChoice.fromStorage(key))
        }
    }

    /** Whether the Rainbow Road easter egg has been unlocked (tapping "Appearance" seven times
     *  on the settings screen) and should therefore appear as a selectable accent at all. */
    fun rainbowRoadUnlocked(): Boolean = preferences.getBoolean(KEY_RAINBOW_UNLOCKED, false)

    fun unlockRainbowRoad() {
        preferences.edit().putBoolean(KEY_RAINBOW_UNLOCKED, true).apply()
    }

    /**
     * The last custom color the user actually picked, independent of whether a preset or custom
     * accent is the *currently active* one. [saveAccentSelection] never clears this key when
     * saving a preset -- it's write-once-per-custom-pick, not tied to the active selection -- so
     * switching to a preset and back to custom can restore the exact color instead of forgetting
     * it, on a cold start as well as within one running session. Null only when no custom color
     * has ever been picked at all.
     */
    fun lastCustomColor(): Color? =
        if (preferences.contains(KEY_CUSTOM_COLOR)) Color(preferences.getInt(KEY_CUSTOM_COLOR, 0)) else null

    fun saveThemeMode(value: ThemeMode) {
        preferences.edit().putString(KEY_THEME, value.storageKey).apply()
    }

    fun saveAccentSelection(selection: AccentSelection) {
        when (selection) {
            is AccentSelection.Preset ->
                preferences
                    .edit()
                    .putString(KEY_ACCENT, selection.choice.storageKey)
                    .apply()
            is AccentSelection.Custom ->
                preferences
                    .edit()
                    .putString(KEY_ACCENT, CUSTOM_KEY)
                    .putInt(KEY_CUSTOM_COLOR, selection.color.toArgb())
                    .apply()
            is AccentSelection.RainbowRoad ->
                preferences
                    .edit()
                    .putString(KEY_ACCENT, RAINBOW_ROAD_KEY)
                    .apply()
        }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ACCENT = "accent"
        const val KEY_CUSTOM_COLOR = "accent_custom_color"
        const val CUSTOM_KEY = "custom"
        const val RAINBOW_ROAD_KEY = "rainbow_road"
        const val KEY_RAINBOW_UNLOCKED = "rainbow_road_unlocked"
    }
}

/** Set by whichever screen is currently composed, via [LazyListState.reportScrollActivity] or
 *  [ScrollState.reportScrollActivity], so [rainbowRoadHue] can freeze instead of ticking while a
 *  scroll gesture is in progress -- see that function's doc comment for why. A single flag is
 *  enough: MainActivity swaps screens with a plain `when` on a state value, not a back-stacked
 *  nav host, so there is never a second scrollable screen concurrently in composition to conflict
 *  with this one. Every `LazyColumn`/`Modifier.verticalScroll` in the app must call one of these
 *  two -- an uncovered screen doesn't error, it just silently keeps ticking through its scrolls
 *  exactly like before either fix, which is exactly how the first pass at this missed three of
 *  the app's six scrollable regions (Home, Settings, and Developer tools, all inlined directly in
 *  MainActivity.kt rather than living in their own screen files like Activity/Filters) and so
 *  still janked on whichever of those the next report of this happened to be scrolling. */
private val scrollInProgress = mutableStateOf(false)

/** Wires a screen's [LazyListState] into the flag [rainbowRoadHue] reads. Call once per screen,
 *  passing the same state given to its `LazyColumn`. */
@Composable
fun LazyListState.reportScrollActivity() {
    LaunchedEffect(this) {
        snapshotFlow { isScrollInProgress }.collect { scrollInProgress.value = it }
    }
}

/** Wires a screen's [ScrollState] into the same flag, for a plain `Modifier.verticalScroll`
 *  region rather than a `LazyColumn`. Call once per screen, passing the same state given to
 *  `verticalScroll`. */
@Composable
fun ScrollState.reportScrollActivity() {
    LaunchedEffect(this) {
        snapshotFlow { isScrollInProgress }.collect { scrollInProgress.value = it }
    }
}

/** Degrees 0..360, advancing a full lap every [periodMs]. Deliberately hand-rolled off a raw
 *  elapsed-time clock rather than [androidx.compose.animation.core.animateFloat] -- Compose's
 *  `animate*` APIs read the coroutine context's `MotionDurationScale`, which a phone's
 *  system-wide "disable animations" developer/accessibility setting drives to 0, silently
 *  freezing an `infiniteRepeatable` at its starting value. Rainbow Road's entire point is the
 *  motion, not a decorative flourish worth suppressing for that setting, so this loop computes
 *  its own elapsed time instead of delegating to that machinery -- it keeps advancing regardless
 *  of any animation-duration-scale setting. [delay] is a plain coroutine suspension with no tie
 *  to `MotionDurationScale`, so throttling and pausing below don't reintroduce that dependency.
 *
 *  Writing [hue] on every display frame (the original implementation, via `withFrameNanos` in a
 *  tight loop) drives every composable anywhere in the app that reads a `primary`/`secondary`/
 *  `tertiary`-derived color role -- which, in a single-accent theme, is most visible chrome --
 *  through a full recompose-and-redraw up to 60 times a second, reported as the app dropping to
 *  ~15fps while this accent is active. Throttling the write rate alone (first to ~12Hz, then to
 *  5Hz) helped but never fully fixed scrolling specifically: a LazyColumn already spends most of
 *  a scrolling frame's budget composing/measuring newly visible items, so *any* hue-driven
 *  recompose landing on that same frame -- however rare -- competes for what's left and can tip
 *  it over budget. Invisible sitting still, but a scrolling frame's budget is tight enough that
 *  even an occasional collision reads as stutter on a continuous gesture.
 *
 *  [scrollInProgress] removes the collision instead of just making it rarer: the loop still ticks
 *  on schedule, but only *advances* [hue] -- and therefore only writes to it, which is the actual
 *  recomposition trigger -- when nothing is scrolling. The color simply holds still for the
 *  (typically well under one second) duration of a scroll gesture and resumes exactly where it
 *  left off, rather than jumping to catch up to wall-clock time. That's a better trade than
 *  perpetually chasing a lower update rate: idle smoothness no longer has to be sacrificed to fix
 *  scrolling, since the two cases no longer compete for the same budget at all. */
@Composable
private fun rainbowRoadHue(periodMs: Long = 6_000L): Float {
    val hue = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        var lastNanos = System.nanoTime()
        while (true) {
            delay(HUE_UPDATE_INTERVAL_MS)
            val nowNanos = System.nanoTime()
            val elapsedMs = (nowNanos - lastNanos) / 1_000_000L
            lastNanos = nowNanos
            if (!scrollInProgress.value) {
                val advanceDeg = elapsedMs.toFloat() / periodMs.toFloat() * 360f
                hue.floatValue = (hue.floatValue + advanceDeg) % 360f
            }
        }
    }
    return hue.floatValue
}

private const val HUE_UPDATE_INTERVAL_MS = 100L

/** [rainbowRoadHue] above, plus throttling and [scrollInProgress]-pausing, exist only because
 *  continuously changing a value read into `MaterialTheme.colorScheme` forces every composable
 *  that reads a primary/secondary/tertiary-derived role to recompose -- composition-time work
 *  that competes with whatever else Compose needs to do on the same frame, most visibly a
 *  scrolling list's own composing/measuring of newly visible rows. No throttle rate closes that
 *  gap without cost: fast enough to look continuous is also fast enough to occasionally land on a
 *  scroll frame, and pausing during a scroll -- while it works -- means the color visibly stops.
 *
 *  On API 31+, [SidekickTheme] instead applies Rainbow Road as a single [hueRotationColorFilter]
 *  in a `Modifier.graphicsLayer { renderEffect = ... }` wrapping the whole composed frame, reading
 *  [rememberRainbowRoadHueDegrees] *inside* that block. A value read inside a graphicsLayer block
 *  is Compose's documented mechanism for animating a rendered property without recomposition: the
 *  block reruns as part of that layer's own draw step on the RenderThread, never the composition
 *  or layout passes on the UI thread, so it can never compete with a scrolling list for the same
 *  frame budget in the first place -- there's nothing to throttle or pause. `colorScheme.primary`
 *  itself becomes [RAINBOW_ROAD_BASE_COLOR], a fixed value, so nothing reads a continuously
 *  changing color during composition at all; the shifting color is purely a render-phase filter
 *  sitting on top of an otherwise perfectly ordinary, unanimated theme.
 *
 *  This needs no throttling: nothing here costs composition or layout regardless of rate, so it
 *  runs at the display's own refresh rate via [withFrameNanos], the smoothest this can look. */
@Composable
private fun rememberRainbowRoadHueDegrees(periodMs: Long = 6_000L): FloatState {
    val hue = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos ->
                val elapsedMs = (frameNanos - startNanos) / 1_000_000L
                hue.floatValue = (elapsedMs % periodMs).toFloat() / periodMs.toFloat() * 360f
            }
        }
    }
    return hue
}

/** The fixed color [SidekickTheme] gives `colorScheme.primary` (and every role derived from it)
 *  on the [rememberRainbowRoadHueDegrees] render-effect path, where the actual shifting color is
 *  a filter applied on top rather than a changing theme value -- same saturation/value as the
 *  legacy [rainbowRoadHue] path (see that hue's own 0.62-value reasoning), at hue 0. Which exact
 *  hue this constant starts from doesn't matter for contrast: [hueRotationColorFilter] rotates
 *  hue around the luma axis, so luma -- and therefore whether black or white contrasts against
 *  it -- is identical at every rotation angle, not just this base value. */
private val RAINBOW_ROAD_BASE_COLOR = Color.hsv(0f, 0.85f, 0.62f)

/** Builds the luma-preserving hue-rotation matrix the W3C Filter Effects spec defines for CSS/SVG
 *  `hue-rotate()`: rotates a color's hue around the luma axis in RGB space, leaving luma itself --
 *  and therefore any already-gray pixel, like this app's black backgrounds and near-white text --
 *  completely unaffected. Only colors that actually carry saturation (Rainbow Road's accent-tinted
 *  elements) visibly shift; this can safely wrap the entire composed frame rather than needing to
 *  target individual accent-colored widgets. */
private fun hueRotationColorFilter(degrees: Float): android.graphics.ColorFilter {
    val radians = Math.toRadians(degrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val matrix =
        floatArrayOf(
            0.213f + cos * 0.787f - sin * 0.213f, 0.715f - cos * 0.715f - sin * 0.715f, 0.072f - cos * 0.072f + sin * 0.928f, 0f, 0f,
            0.213f - cos * 0.213f + sin * 0.143f, 0.715f + cos * 0.285f + sin * 0.140f, 0.072f - cos * 0.072f - sin * 0.283f, 0f, 0f,
            0.213f - cos * 0.213f - sin * 0.787f, 0.715f - cos * 0.715f + sin * 0.715f, 0.072f + cos * 0.928f + sin * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix))
}

/** Wraps [hueRotationColorFilter] as a [GraphicsLayerScope.renderEffect]-compatible value.
 *  `@RequiresApi` documents the real platform requirement; the caller's own direct
 *  `Build.VERSION.SDK_INT` check (not this annotation) is what Lint actually verifies against. */
@RequiresApi(Build.VERSION_CODES.S)
private fun rainbowRoadRenderEffect(degrees: Float): androidx.compose.ui.graphics.RenderEffect =
    android.graphics.RenderEffect.createColorFilterEffect(hueRotationColorFilter(degrees)).asComposeRenderEffect()

/** Returns the [Modifier.graphicsLayer] carrying Rainbow Road's render effect when [active], or
 *  a no-op [Modifier] otherwise -- deliberately a *modifier* rather than something that wraps
 *  `content` in an `if`/`else` at the call site. An `if`/`else` around a composable call puts each
 *  branch in its own distinct composition group; toggling [active] would then make Compose treat
 *  `content` as having moved to a structurally different parent and discard/recreate its entire
 *  subtree, wiping every bit of remembered state inside it -- including, concretely, the Settings
 *  screen's scroll position, reported as "picking an accent bumps me back to the top of the card"
 *  the one release this shipped in a version that did exactly that. A conditional *modifier* value
 *  applied to a Box that's *always* composed the same way carries no such risk: changing a
 *  modifier chain updates the existing node in place and never touches its children's identity or
 *  remembered state. */
@Composable
private fun Modifier.rainbowRoadHueRotation(active: Boolean): Modifier {
    if (active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val hueDegrees = rememberRainbowRoadHueDegrees()
        return this.graphicsLayer {
            // Read *here*, inside the graphicsLayer block, not in this function's own body --
            // see rememberRainbowRoadHueDegrees's doc comment for why that distinction matters.
            renderEffect = rainbowRoadRenderEffect(hueDegrees.floatValue)
        }
    }
    return Modifier
}

@Composable
fun SidekickTheme(
    themeMode: ThemeMode,
    accent: AccentSelection,
    fontScale: FontScale = FontScale.DEFAULT_SIZE,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val rainbowActive = accent is AccentSelection.RainbowRoad
    // See rememberRainbowRoadHueDegrees's doc comment for the full mechanism/why. Below API 31,
    // RenderEffect isn't available, so Rainbow Road falls back to the original approach: a value
    // read straight into colorScheme.primary, throttled and paused during scrolls to keep that
    // approach's inherent recomposition cost from competing with a scrolling list's own frame
    // budget.
    val useRenderEffectRainbow = rainbowActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val primary =
        when {
            useRenderEffectRainbow -> RAINBOW_ROAD_BASE_COLOR
            rainbowActive ->
                // A full hue sweep every 6s -- fast enough to actually read as "shifting RGB"
                // rather than a slow gradient, slow enough not to strobe. Fixed saturation and a
                // deliberately capped value: HSV's V is "brightest channel," not perceptual
                // luminance, so yellow/green hues read far brighter than blue/magenta at the
                // *same* V -- the original 0.8-0.9 let those hues cross contrastingOn's 0.5
                // threshold, flipping onPrimary between white and black mid-sweep (a jarring
                // flash, reported after the first release). 0.62 keeps even the brightest hue
                // (yellow, where R and G both roughly equal V) comfortably under that threshold
                // -- computed luminance ~0.32 there versus 0.56 at the old V=0.8 -- so onPrimary
                // settles on white once and never has to re-decide as the color keeps moving.
                // contrastingOn is still called live below rather than hardcoding white, so this
                // stays self-correcting if this value ever changes rather than silently
                // reintroducing the bug.
                Color.hsv(rainbowRoadHue(), 0.85f, 0.62f)
            else -> accent.primaryFor(dark)
        }
    val onPrimary = contrastingOn(primary)
    val scheme =
        if (dark) {
            val primaryContainer = lerp(primary, Color.Black, 0.35f)
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = contrastingOn(primaryContainer),
                // This is a single-accent theme: every "secondary"/"tertiary" role a Material3
                // component might reach for (SegmentedButton's selected state, for one) needs to
                // trace back to the same chosen accent, not Material3's unrelated default swatch.
                secondary = primary,
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer,
                onSecondaryContainer = contrastingOn(primaryContainer),
                tertiary = primary,
                onTertiary = onPrimary,
                tertiaryContainer = primaryContainer,
                onTertiaryContainer = contrastingOn(primaryContainer),
                background = Color.Black,
                onBackground = Color(0xFFF5F5F5),
                surface = Color.Black,
                onSurface = Color(0xFFF5F5F5),
                surfaceVariant = Color.Black,
                onSurfaceVariant = Color(0xFFD0D0D0),
                surfaceTint = Color.Transparent,
                inverseSurface = Color(0xFFF5F5F5),
                inverseOnSurface = Color(0xFF161616),
                outline = Color(0xFF777777),
                outlineVariant = Color(0xFF3A3A3A),
                scrim = Color.Black,
                surfaceBright = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerHigh = Color.Black,
                surfaceContainerHighest = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceDim = Color.Black,
            )
        } else {
            val primaryContainer = lerp(primary, Color.White, 0.84f)
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = primary,
                secondary = primary,
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer,
                onSecondaryContainer = primary,
                tertiary = primary,
                onTertiary = onPrimary,
                tertiaryContainer = primaryContainer,
                onTertiaryContainer = primary,
            )
        }
    // Edge-to-edge (enabled once in MainActivity.onCreate) is what actually controls bar color
    // on API 35+, where a direct Window.statusBarColor/navigationBarColor write is a no-op --
    // Android enforces edge-to-edge for any app targeting 35+. Material3's Scaffold/TopAppBar
    // already draw their own container color underneath the transparent system bars, so only
    // icon contrast needs setting here, and it works identically on every supported API level.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            // Both bars sit over this app's own background/surface -- Color.Black, unconditionally,
            // in dark mode; Material3's near-white default in light mode -- never over `primary`
            // (no TopAppBar or Scaffold container in this app is accent-colored). Icon contrast
            // therefore only ever needs to track `dark`, the same way isAppearanceLightNavigationBars
            // already correctly does below. Deriving this from scheme.primary.luminance() instead
            // was a latent bug: every fixed preset's dark-mode tone happened to stay under the 0.5
            // luminance threshold, so it silently always resolved the same way regardless -- until
            // Rainbow Road swept through every hue, including bright yellows/greens whose luminance
            // crosses 0.5, at which point it painted dark status-bar icons over the still-solid-black
            // bar background: invisible, exactly like this on a truly light bar would have made them
            // vanish on a truly dark one.
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * fontScale.scale)) {
        // Always the same Box-wrapping-MaterialTheme shape regardless of accent -- see
        // Modifier.rainbowRoadHueRotation's doc comment for why that has to be true rather than
        // conditionally wrapping content at all.
        Box(Modifier.fillMaxSize().rainbowRoadHueRotation(useRenderEffectRainbow)) {
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}
