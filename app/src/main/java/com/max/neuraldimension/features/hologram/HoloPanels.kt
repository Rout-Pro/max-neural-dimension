package com.max.neuraldimension.features.hologram

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.neuraldimension.core.theme.NdGlassLow
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdPanelBorder
import com.max.neuraldimension.core.theme.NdViolet

/**
 * Floating holographic glass panel. Content always reflects real app data
 * supplied by the caller; empty states are rendered honestly.
 */
@Composable
fun HoloPanel(
    title: String,
    icon: ImageVector,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val float by rememberInfiniteTransition(label = "float$index").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3400 + index * 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatAnim$index"
    )
    val dy = (float - 0.5f) * 10f
    val rot = (float - 0.5f) * 2.2f
    Column(
        modifier = modifier
            .graphicsLayer { translationY = dy.dp.toPx(); rotationZ = rot }
            .shadow(
                elevation = 18.dp, shape = shape, clip = false,
                ambientColor = NdViolet.copy(alpha = 0.35f),
                spotColor = NdViolet.copy(alpha = 0.45f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x33FFFFFF), NdGlassLow, Color(0x1A813EFF))
                )
            )
            .border(1.dp, Brush.verticalGradient(listOf(NdPanelBorder, Color(0x1AD9B9FF))), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = title, tint = NdLavender, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = NdLavender, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
fun PanelLine(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = NdMuted, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
}
