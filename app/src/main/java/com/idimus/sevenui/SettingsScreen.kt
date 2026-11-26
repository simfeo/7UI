package com.idimus.sevenui

import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val screenBg = Color(0xFF010101)
private val cardBg = Color(0xFF171717)
private val accent = Color(0xFF3E91FF)
private val subText = Color(0xFF9A9A9A)

/** Settings layout: a big title filling the top of the screen for one-handed reach, then rounded groups. */
@Composable
fun SettingsScreen(state: LauncherState) {
    val s = state.settings
    var backupWarning by remember { mutableStateOf(false) }
    if (backupWarning) {
        AlertDialog(
            onDismissRequest = { backupWarning = false },
            title = { Text(stringResource(R.string.backup_warning_title)) },
            text = {
                Text(stringResource(R.string.backup_warning_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    backupWarning = false
                    state.onBackup()
                }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(screenBg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.settings_title), color = Color.White, fontSize = 34.sp)
        }
        if (!state.isDefaultHome) {
            Group {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { state.requestDefaultHome() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(stringResource(R.string.set_default_home), color = accent, fontSize = 17.sp)
                    Text(stringResource(R.string.not_default_home), color = subText, fontSize = 14.sp)
                }
            }
        }
        Group {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.apps_screen_grid), color = Color.White, fontSize = 17.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Grid.DRAWER_GRIDS.forEachIndexed { i, (c, r) ->
                    val selected = s.drawerGrid == i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(if (selected) accent else Color.White.copy(alpha = 0.08f), CircleShape)
                            .clickable { state.setDrawerGrid(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${c}x$r", color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Group {
            SwitchRow(stringResource(R.string.loop_pages), stringResource(R.string.loop_pages_summary), s.loopPages) {
                s.loopPages = it
            }
            Divider()
            SwitchRow(stringResource(R.string.edge_panel), stringResource(R.string.edge_panel_summary), s.edgePanel) {
                state.setEdgePanel(it)
            }
            if (s.edgePanel) {
                Divider()
                SwitchRow(stringResource(R.string.edge_panel_left), null, s.edgePanelLeft) { state.setEdgePanelLeft(it) }
            }
            Divider()
            SwitchRow(stringResource(R.string.home_search_bar), null, s.homeSearchBar) { s.homeSearchBar = it }
            Divider()
            SwitchRow(stringResource(R.string.swipe_down_notifications), null, s.swipeDownForNotifications) {
                s.swipeDownForNotifications = it
            }
            Divider()
            SwitchRow(stringResource(R.string.lock_layout), stringResource(R.string.lock_layout_summary), s.lockLayout) {
                s.lockLayout = it
            }
        }
        Group {
            Column(Modifier.fillMaxWidth().clickable { backupWarning = true }.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.backup), color = Color.White, fontSize = 17.sp)
                Text(stringResource(R.string.backup_summary), color = subText, fontSize = 14.sp)
            }
            Divider()
            Column(Modifier.fillMaxWidth().clickable { state.onRestore() }.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.restore), color = Color.White, fontSize = 17.sp)
                Text(stringResource(R.string.restore_summary), color = subText, fontSize = 14.sp)
            }
        }
        Group {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.about), color = Color.White, fontSize = 17.sp)
                Text(stringResource(R.string.version, "1.1.0"), color = subText, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(cardBg, RoundedCornerShape(26.dp))
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color.White.copy(alpha = 0.08f))
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            if (summary != null) Text(summary, color = subText, fontSize = 14.sp)
        }
        Spacer(Modifier.padding(start = 12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White),
        )
    }
}
