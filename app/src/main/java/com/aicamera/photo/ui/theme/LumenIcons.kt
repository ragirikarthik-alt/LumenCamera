package com.aicamera.photo.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicamera.photo.domain.RecommendationType

/**
 * The app's single icon set: Material Symbols "Rounded" - soft corners and a consistent stroke.
 * Every screen takes icons from here so the style stays uniform.
 */
object LumenIcons {
    val Back = Icons.AutoMirrored.Rounded.ArrowBack
    val Settings = Icons.Rounded.Settings
    val Library = Icons.Rounded.PhotoLibrary
    val Academy = Icons.Rounded.School
    val Lesson = Icons.AutoMirrored.Rounded.MenuBook
    val Ai = Icons.Rounded.AutoAwesome
    val Camera = Icons.Rounded.CameraAlt
    val Shoot = Icons.Rounded.PhotoCamera
    val Pro = Icons.Rounded.Tune
    val Refresh = Icons.Rounded.Refresh
    val FlashOn = Icons.Rounded.FlashOn
    val FlashOff = Icons.Rounded.FlashOff
    val FlashAuto = Icons.Rounded.FlashAuto
    val Delete = Icons.Rounded.Delete
    val Compare = Icons.AutoMirrored.Rounded.CompareArrows
    val Close = Icons.Rounded.Close
    val Done = Icons.Rounded.CheckCircle
    val Grid = Icons.Rounded.GridView
    val Memory = Icons.Rounded.Memory
    val Storage = Icons.Rounded.Storage
    val Privacy = Icons.Rounded.Lock
    val Diagnostics = Icons.Rounded.MonitorHeart
    val Info = Icons.Rounded.Info
    val Download = Icons.Rounded.Download

    /** One recognisable symbol per kind of coaching tip. */
    fun forTip(type: RecommendationType): ImageVector = when (type) {
        RecommendationType.HORIZON -> Icons.Rounded.Straighten
        RecommendationType.SUBJECT_PLACEMENT -> Icons.Rounded.OpenWith
        RecommendationType.SEPARATION -> Icons.Rounded.Layers
        RecommendationType.FOREGROUND_DEPTH -> Icons.Rounded.Landscape
        RecommendationType.EXPOSURE -> Icons.Rounded.WbSunny
        RecommendationType.EDGE_DISTRACTION -> Icons.Rounded.CropFree
        RecommendationType.STABILITY -> Icons.Rounded.PanTool
        RecommendationType.VIEWPOINT -> Icons.Rounded.Visibility
        RecommendationType.NEGATIVE_SPACE -> Icons.Rounded.BlurOn
        RecommendationType.LENS_CHOICE -> Icons.Rounded.ZoomIn
        RecommendationType.LIGHT_DIRECTION -> Icons.Rounded.WbTwilight
        RecommendationType.FOCUS -> Icons.Rounded.CenterFocusStrong
        RecommendationType.CLOSER -> Icons.Rounded.ZoomIn
    }

    val Tip = Icons.Rounded.Lightbulb
}
