package com.idimus.sevenui

import androidx.compose.animation.core.Easing

/** Fast start, long soft landing: the curve the reference launcher uses for page snaps. */
val QuinticOut = Easing { t -> 1f - (1f - t).let { it * it * it * it * it } }
