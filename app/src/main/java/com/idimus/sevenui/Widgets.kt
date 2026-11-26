package com.idimus.sevenui

import androidx.compose.ui.res.stringResource
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot

const val WIDGET_HOST_ID = 7007

class LauncherWidgetHost(context: Context) : AppWidgetHost(context, WIDGET_HOST_ID) {
    /** [x], [y] are the touch point inside [view]. */
    /** Returns false when the launcher ignores it, for example mid-swipe. */
    var onLongPress: (id: Int, view: View, x: Float, y: Float) -> Boolean = { _, _, _, _ -> false }

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LongPressWidgetView(context) { v, x, y -> onLongPress(appWidgetId, v, x, y) }
}

/**
 * Widgets swallow touches, so a long press has to be spotted before the widget sees the
 * events. Once it fires, the widget gets a CANCEL and the rest of the gesture is ignored.
 */
class LongPressWidgetView(context: Context, private val onLong: (View, Float, Float) -> Boolean) : AppWidgetHostView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val longPress = Runnable {
        if (!onLong(this, downX, downY)) return@Runnable
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                fired = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (fired) return true
        return super.dispatchTouchEvent(ev)
    }
}

/** Cells a widget wants, from its declared target size or its minimum size in dp. */
fun widgetSpan(info: AppWidgetProviderInfo, cellWidthDp: Float, cellHeightDp: Float, density: Float): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
        return info.targetCellWidth.coerceIn(1, Grid.HOME_COLS) to info.targetCellHeight.coerceIn(1, Grid.HOME_ROWS)
    }
    val x = ceil(info.minWidth / density / cellWidthDp).toInt().coerceIn(1, Grid.HOME_COLS)
    val y = ceil(info.minHeight / density / cellHeightDp).toInt().coerceIn(1, Grid.HOME_ROWS)
    return x to y
}

private data class WidgetGroup(val appLabel: String, val appIcon: ImageBitmap?, val widgets: List<AppWidgetProviderInfo>)

/** Full-screen widget list grouped by app, each with its preview and cell size. */
@Composable
fun WidgetPicker(state: LauncherState) {
    val context = LocalContext.current
    val groups by produceState<List<WidgetGroup>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            state.widgetProviders().groupBy { it.provider.packageName }.map { (pkg, list) ->
                val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                WidgetGroup(
                    appInfo?.loadLabel(pm)?.toString() ?: pkg,
                    appInfo?.loadIcon(pm)?.toBitmap(96, 96)?.asImageBitmap(),
                    list,
                )
            }.sortedBy { it.appLabel.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF010101)).systemBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.widgets), color = Color.White, fontSize = 30.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(groups, key = { it.appLabel }) { g ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
                    .background(Color(0xFF171717), RoundedCornerShape(26.dp)).padding(vertical = 14.dp)) {
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(g.appIcon, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(g.appLabel, color = Color.White, fontSize = 17.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(g.widgets, key = { it.provider.flattenToString() }) { info ->
                            WidgetCard(state, info)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(state: LauncherState, info: AppWidgetProviderInfo) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (info.loadPreviewImage(context, density.densityDpi) ?: info.loadIcon(context, density.densityDpi))
                    ?.toBitmap()?.asImageBitmap()
            }.getOrNull()
        }
    }
    val span = remember(info) { state.spanFor(info) }
    Column(
        Modifier.width(150.dp).clickable { state.requestAddWidget(info) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(150.dp, 120.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(6.dp))
        Text(info.loadLabel(context.packageManager), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${span.first} x ${span.second}", color = Color(0xFF9A9A9A), fontSize = 12.sp)
    }
}
