package com.idimus.sevenui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FolderOverlay(state: LauncherState) {
    val id = state.openFolder
    val folder = id?.let { state.layout.folders[it] }
    AnimatedVisibility(
        visible = folder != null,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
    ) {
        // Keep the last folder while the exit animation runs.
        val shown = remember { mutableStateOf<Pair<String, Folder>?>(null) }
        if (id != null && folder != null) shown.value = id to folder
        val (fid, f) = shown.value ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { state.openFolder = null } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .background(Color(0xE63A3A3D), RoundedCornerShape(34.dp))
                    // Taps inside the card must not close the folder.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FolderName(state, fid, f.name)
                ColorDots(f.color) { state.setFolderColor(fid, it) }
                f.items.chunked(Grid.FOLDER_COLS).forEachIndexed { row, keys ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        for (col in 0 until Grid.FOLDER_COLS) {
                            Box(Modifier.weight(1f).height(100.dp)) {
                                val k = keys.getOrNull(col)
                                if (k != null) {
                                    ItemCell(state, k, Slot.InFolder(fid, row * Grid.FOLDER_COLS + col), Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FOLDER_COLORS.forEachIndexed { i, c ->
            Box(
                Modifier
                    .size(22.dp)
                    .background(Color(c).copy(alpha = 1f), CircleShape)
                    .then(if (i == selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                    .clickable { onPick(i) },
            )
        }
    }
}

@Composable
private fun FolderName(state: LauncherState, id: String, name: String) {
    var text by remember(id) { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.focusFolderName) {
        if (state.focusFolderName) {
            focus.requestFocus()
            state.focusFolderName = false
        }
    }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            state.renameFolder(id, it)
        },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(focus),
    )
}
