package com.idimus.sevenui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private fun keyOf(component: ComponentName, user: UserHandle) =
        "${component.flattenToString()}#${userManager.getSerialNumberForUser(user)}"

    fun loadAll(): List<AppEntry> = userManager.userProfiles.flatMap { user ->
        launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { AppEntry(keyOf(it.componentName, user), it.label.toString(), it.componentName, user, it) }
    }

    fun loadIcon(entry: AppEntry, sizePx: Int): ImageBitmap =
        entry.info.getBadgedIcon(0).toBitmap(sizePx, sizePx).asImageBitmap()

    /** Phone, messages, browser and camera, as resolved by the user's default apps. */
    fun defaultDockKeys(apps: List<AppEntry>): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val owner = android.os.Process.myUserHandle()
        return intents.mapNotNull { intent ->
            val pkg = context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            // "android" is the chooser, meaning no default is set.
            if (pkg == null || pkg == "android") null
            else apps.firstOrNull { it.component.packageName == pkg && it.user == owner }?.key
        }.distinct()
    }

    fun launch(entry: AppEntry, bounds: Rect?) =
        launcherApps.startMainActivity(entry.component, entry.user, bounds, null)

    fun openAppInfo(entry: AppEntry) =
        launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)

    fun uninstall(entry: AppEntry) {
        val uri = Uri.fromParts("package", entry.component.packageName, null)
        context.startActivity(Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Works only while we are the default home app; otherwise the system refuses. */
    fun shortcuts(entry: AppEntry): List<ShortcutInfo> = try {
        val q = LauncherApps.ShortcutQuery()
            .setPackage(entry.component.packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        launcherApps.getShortcuts(q, entry.user).orEmpty()
            .filter { it.activity == null || it.activity == entry.component }
            .sortedBy { it.rank }
            .distinctBy { it.id }
            .take(4)
    } catch (_: Exception) {
        emptyList()
    }

    fun shortcutIcon(info: ShortcutInfo, sizePx: Int): ImageBitmap? = try {
        launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
            ?.toBitmap(sizePx, sizePx)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    fun startShortcut(info: ShortcutInfo) {
        try {
            launcherApps.startShortcut(info, null, null)
        } catch (_: Exception) {
        }
    }

    fun openWebSearch() = startSafely(Intent("android.search.action.GLOBAL_SEARCH"))

    fun openWallpaperPicker() =
        startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))

    private fun startSafely(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun registerCallback(onChange: (packages: List<String>) -> Unit): LauncherApps.Callback {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange(listOf(packageName))
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange(listOf(packageName))
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange(listOf(packageName))
            override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
                onChange(packageNames.toList())
            override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
                onChange(packageNames.toList())
        }
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
        return cb
    }

    fun unregisterCallback(cb: LauncherApps.Callback) = launcherApps.unregisterCallback(cb)

    // Hidden API, but it is the only way for a launcher to pull down the shade on swipe.
    fun expandNotifications() {
        try {
            val sbm = context.getSystemService("statusbar")
            sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (_: Exception) {
        }
    }
}
