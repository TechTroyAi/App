package ai.techtroy.clockcanvas.ui.compose

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.WidgetBucket
import ai.techtroy.clockcanvas.data.DesignStore
import ai.techtroy.clockcanvas.render.ClockRenderer
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The optional Compose layer: the responsive size-comparison screen.
 *
 * It exists to prove one thing visually - that the *same* design JSON re-lays
 * itself sanely at every widget bucket - and it renders through
 * [ClockRenderer], the identical code the widget runs, so what you see here is
 * what the launcher will show. Nothing else in the app depends on Compose: see
 * `build.gradle.kts` (`offlineOnly` disables the plugin) and
 * `tools/build-offline-apk.py`, which skips `ui/compose/` entirely and drops this
 * activity from the manifest when it was not compiled.
 */
class PreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(ai.techtroy.clockcanvas.ui.ComposeBridge.EXTRA_DESIGN_ID)
        val design = DesignStore.of(this).resolveOrFallback(id)
        setContent {
            MaterialTheme {
                SizeComparison(design = design)
            }
        }
    }

    companion object {
        fun intentFor(context: Activity, design: ClockDesign): Intent =
            Intent(context, PreviewActivity::class.java)
                .putExtra(ai.techtroy.clockcanvas.ui.ComposeBridge.EXTRA_DESIGN_ID, design.id)
    }
}

private data class Spec(val label: String, val widthDp: Int, val heightDp: Int, val bucket: WidgetBucket)

private val SPECS = listOf(
    Spec("Small · 2x2", 160, 80, WidgetBucket.SMALL),
    Spec("Medium · 4x2", 320, 120, WidgetBucket.MEDIUM),
    Spec("Large · 4x4", 320, 280, WidgetBucket.LARGE),
    Spec("Wide · 6x1", 420, 64, WidgetBucket.WIDE),
    Spec("Tall · 2x4", 140, 320, WidgetBucket.TALL),
)

@Composable
private fun SizeComparison(design: ClockDesign) {
    val density = LocalDensity.current.density
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF09070F)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = design.name.ifEmpty { "Preview" },
                color = Color(0xFFF5F3FF),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Every tile is the same design JSON drawn by the widget renderer at that cell size - " +
                    "font scale, date visibility, padding and border width are all re-derived.",
                color = Color(0xFF817895),
                fontSize = 12.5.sp,
            )
            SPECS.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { spec ->
                        Column(modifier = Modifier.weight(1f)) {
                            Tile(design = design, spec = spec, density = density)
                        }
                    }
                    if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Tile(design: ClockDesign, spec: Spec, density: Float) {
    val widthPx = (spec.widthDp * density).toInt()
    val heightPx = (spec.heightDp * density).toInt()
    val bitmap: Bitmap? = rememberBitmap(design, widthPx, heightPx)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = spec.label, color = Color(0xFFB8AEC9), fontSize = 12.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161222))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "${spec.label} preview of ${design.name}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(text = "too large to render", color = Color(0xFFF87171), fontSize = 12.sp)
            }
        }
    }
}

/** `remember`'s calculation block is not composable, so the context is read first. */
@Composable
private fun rememberBitmap(design: ClockDesign, widthPx: Int, heightPx: Int): Bitmap? {
    val renderer = ClockRenderer.of(androidx.compose.ui.platform.LocalContext.current)
    return androidx.compose.runtime.remember(design, widthPx, heightPx) {
        try {
            renderer.renderPx(design, widthPx, heightPx)
        } catch (e: Throwable) {
            null
        }
    }
}
