package com.max.neuraldimension.features.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.domain.PinLock
import com.max.neuraldimension.features.chat.AppViewModel

/** Wires the stateless LockScreen to the AppViewModel lock state machine. */
@Composable
fun LockGate(vm: AppViewModel) {
    var pin by remember { mutableStateOf("") }
    val error by vm.lockError.collectAsState()
    LockScreen(
        entered = pin.length,
        error = error,
        onDigit = { if (pin.length < PinLock.MAX_PIN) pin += it },
        onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
        onSubmit = {
            if (pin.length >= PinLock.MIN_PIN) {
                vm.unlock(pin)
                pin = ""
            }
        }
    )
}

/**
 * Blueprint Part 19: full-screen PIN gate shown before any app content when a
 * PIN is set. Stateless: the caller (LockGate / tests) owns the entered digits
 * and error text, so the screen renders deterministically under test.
 */
@Composable
fun LockScreen(
    entered: Int,
    error: String?,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(NdBlack).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("MAX LOCKED", color = NdLavender, fontSize = 15.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Enter your PIN", color = NdMuted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        // Entered digits as filled dots; remaining slots hollow.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(PinLock.MAX_PIN) { i ->
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(if (i < entered) NdLavender else androidx.compose.ui.graphics.Color.Transparent)
                        .border(1.5.dp, NdLavender, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            if (error != null) Text(error, color = androidx.compose.ui.graphics.Color(0xFFFF6B81), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d -> PadKey(d.toString()) { onDigit(d) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PadKey("OK", accent = true) { onSubmit() }
            PadKey("0") { onDigit('0') }
            PadKey("DEL") { onDelete() }
        }
        Spacer(Modifier.height(28.dp))
        Text("PIN is stored only as a salted hash on this device.", color = NdMuted, fontSize = 11.sp)
    }
}

@Composable
private fun PadKey(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(if (accent) NdLavender.copy(alpha = 0.25f) else NdSilver.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (accent) NdLavender else NdSilver,
            fontSize = if (label.length > 1) 13.sp else 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
