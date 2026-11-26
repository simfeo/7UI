package com.idimus.sevenui

import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The Edge panel: a thin handle on the right screen edge, drawn over every app, that opens a
 * panel of favorite apps. Runs in the launcher process, which Android keeps alive as Home.
 */
class EdgePanelService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var repo: AppRepository
    private val owner = OverlayOwner()
    private var handle: View? = null
    private var panel: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        repo = AppRepository(this)
        owner.start()
        if (AndroidSettings.canDrawOverlays(this)) showHandle() else stopSelf()
    }

    override fun onDestroy() {
        handle?.let(wm::removeView)
        panel?.let(wm::removeView)
        owner.stop()
        super.onDestroy()
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun showHandle() {
        val d = resources.displayMetrics.density
        val left = EdgePrefs.onLeft(this)
        val edge = if (left) Gravity.START else Gravity.END
        val view = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 4 * d
                setColor(0x99FFFFFF.toInt())
            }
        }
        // The touch area is wider than the visible bar so it is easy to hit.
        val container = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams((6 * d).roundToInt(), FrameLayout.LayoutParams.MATCH_PARENT, edge))
            // Otherwise the system Back gesture claims swipes that start at the screen edge.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
            }
        }
        var downX = 0f
        container.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.rawX
                // Swiping away from the edge opens the panel, whichever side it is on.
                MotionEvent.ACTION_MOVE -> if ((if (left) e.rawX - downX else downX - e.rawX) > 16 * d && panel == null) openPanel()
                MotionEvent.ACTION_UP -> if (panel == null && kotlin.math.abs(downX - e.rawX) < 8 * d) openPanel()
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            (20 * d).roundToInt(), (120 * d).roundToInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = edge or Gravity.TOP
            y = (resources.displayMetrics.heightPixels * 0.3f).roundToInt()
        }
        wm.addView(container, lp)
        handle = container
        updateHandleVisibility()
    }

    private fun openPanel() {
        val compose = ComposeView(this).apply { setContent { EdgePanel(::closePanel) } }
        // Back has to close the panel; the window is focusable only for that.
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    closePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            // Compose looks the owners up from the window's root view.
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(compose)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.3f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 40
            }
        }
        wm.addView(root, lp)
        panel = root
        handle?.visibility = View.GONE
    }

    private fun closePanel() {
        panel?.let(wm::removeView)
        panel = null
        updateHandleVisibility()
    }

    // The reference design shows no Edge handle in landscape.
    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun updateHandleVisibility() {
        handle?.visibility = if (panel == null && !isLandscape()) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isLandscape()) closePanel() else updateHandleVisibility()
    }

    @Composable
    private fun EdgePanel(onClose: () -> Unit) {
        var keys by remember { mutableStateOf(EdgePrefs.load(this)) }
        var apps by remember { mutableStateOf<Map<String, AppEntry>>(emptyMap()) }
        val icons = remember { mutableStateMapOf<String, ImageBitmap>() }
        var editing by remember { mutableStateOf(false) }
        var adding by remember { mutableStateOf(false) }
        val iconPx = (48 * resources.displayMetrics.density).roundToInt()

        LaunchedEffect(Unit) {
            val list = withContext(Dispatchers.IO) { repo.loadAll() }.sortedBy { it.label.lowercase() }
            apps = list.associateBy { it.key }
            withContext(Dispatchers.Default) {
                for (e in list) {
                    val bmp = repo.loadIcon(e, iconPx)
                    withContext(Dispatchers.Main) { icons[e.key] = bmp }
                }
            }
        }
        fun save(k: List<String>) {
            keys = k
            EdgePrefs.save(this, k)
        }

        val left = remember { EdgePrefs.onLeft(this) }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } },
            contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            AnimatedVisibility(visible = true, enter = fadeIn() + slideInHorizontally { if (left) -it / 2 else it / 2 }) {
                Column(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(if (adding) 260.dp else 168.dp)
                        .heightIn(max = 560.dp)
                        .background(Color(0xE6202023), RoundedCornerShape(34.dp))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(vertical = 16.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (adding) {
                        AddList(apps.values.filter { it.key !in keys }, icons) { save(keys + it) }
                        PanelButton(Icons.Filled.Check) { adding = false }
                        return@Column
                    }
                    if (keys.none(apps::containsKey) && apps.isNotEmpty()) {
                        Text(stringResource(R.string.edge_panel_empty), color = Color(0xFFBBBBBB), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                    keys.filter(apps::containsKey).chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (k in row) {
                                EdgeItem(apps.getValue(k), icons[k], editing, onRemove = { save(keys - k) }) {
                                    repo.launch(apps.getValue(k), null)
                                    onClose()
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.width(72.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (editing) PanelButton(Icons.Filled.Add) { adding = true }
                        PanelButton(if (editing) Icons.Filled.Check else Icons.Filled.Edit) { editing = !editing }
                    }
                }
            }
        }
    }

    @Composable
    private fun EdgeItem(entry: AppEntry, icon: ImageBitmap?, editing: Boolean, onRemove: () -> Unit, onTap: () -> Unit) {
        Box(Modifier.width(72.dp).padding(vertical = 6.dp)) {
            Column(
                Modifier.fillMaxWidth().clickable(enabled = !editing, onClick = onTap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(icon, 48.dp)
                Text(entry.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp))
            }
            if (editing) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(22.dp).background(Color(0xFF3A3A3D), CircleShape).clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Clear, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }

    @Composable
    private fun AddList(candidates: Collection<AppEntry>, icons: Map<String, ImageBitmap>, onAdd: (String) -> Unit) {
        LazyColumn(Modifier.heightIn(max = 460.dp).fillMaxWidth()) {
            items(candidates.toList(), key = { it.key }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onAdd(e.key) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(icons[e.key], 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(e.label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
        Box(
            Modifier.size(36.dp).background(Color.White.copy(alpha = 0.12f), CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
    }

    companion object {
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, EdgePanelService::class.java)
            if (enabled && AndroidSettings.canDrawOverlays(context)) context.startService(intent)
            else context.stopService(intent)
        }
    }
}

/** Edge panel apps, shared between the launcher and the service through preferences. */
object EdgePrefs {
    private const val KEY = "edge_panel_apps"

    /** Read straight from preferences: the service runs apart from the launcher's [Settings]. */
    fun onLeft(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("edge_panel_left", false)

    fun load(context: Context): List<String> =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null)
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun save(context: Context, keys: List<String>) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, keys.joinToString("\n")).apply()
    }

    fun add(context: Context, key: String) {
        val current = load(context)
        if (key !in current) save(context, current + key)
    }
}

/** Lifecycle and saved-state owner so Compose can run inside an overlay window of a service. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
