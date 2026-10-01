package com.questcast.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questcast.app.model.CastState
import com.questcast.app.model.DiagnosticsInfo
import com.questcast.app.util.QrCodeGenerator

@Composable
fun MainScreen(
    diagnostics: DiagnosticsInfo,
    onStartCasting: () -> Unit,
    onStopCasting: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isCastingActive = diagnostics.state != CastState.IDLE &&
            diagnostics.state != CastState.STOPPED &&
            diagnostics.state != CastState.ERROR

    val activeUrl = if (diagnostics.httpsReceiverUrl.isNotBlank()) diagnostics.httpsReceiverUrl else diagnostics.receiverUrl
    val qrImageBitmap = remember(activeUrl) {
        if (activeUrl.isNotBlank()) {
            QrCodeGenerator.generateQrImageBitmap(activeUrl, 400)
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Title Header
            Text(
                text = "QUESTCAST",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                color = Color(0xFF00E5FF)
            )
            Text(
                text = "Meta Quest 2 Low-Latency Wi-Fi Screen Caster",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B))
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "STATUS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF64748B)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        val (dotColor, statusText) = when (diagnostics.state) {
                            CastState.IDLE, CastState.STOPPED -> Color(0xFF64748B) to "Not Casting"
                            CastState.STARTING -> Color(0xFFF59E0B) to "Starting Servers..."
                            CastState.SERVER_READY -> Color(0xFF38BDF8) to "Waiting for Receiver..."
                            CastState.RECEIVER_CONNECTED -> Color(0xFF34D399) to "Receiver Connected"
                            CastState.STREAMING -> Color(0xFF10B981) to "CASTING • LIVE"
                            CastState.ERROR -> Color(0xFFEF4444) to "Error"
                        }

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = dotColor
                        )
                    }

                    if (diagnostics.lastError != null) {
                        Text(
                            text = diagnostics.lastError,
                            fontSize = 13.sp,
                            color = Color(0xFFF87171),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Start / Stop Action Button
                    if (!isCastingActive) {
                        Button(
                            onClick = onStartCasting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text(
                                text = "START CASTING",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF040914)
                            )
                        }
                    } else {
                        Button(
                            onClick = onStopCasting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text(
                                text = "STOP CASTING",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Connection URL & QR Code Card (When casting is active or servers ready)
            if (isCastingActive && diagnostics.receiverUrl.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00E5FF).copy(alpha = 0.4f))
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "RECEIVER URL (PUSH-TO-TALK ENABLED)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color(0xFF00E5FF)
                        )

                        Text(
                            text = if (diagnostics.httpsReceiverUrl.isNotBlank()) diagnostics.httpsReceiverUrl else diagnostics.receiverUrl,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )

                        if (diagnostics.httpsReceiverUrl.isNotBlank()) {
                            Text(
                                text = "HTTP fallback: ${diagnostics.receiverUrl}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        Text(
                            text = "Scan QR or open in any phone/tablet browser.\n(First time: tap 'Advanced' -> 'Proceed' to enable mic)",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )

                        // Rendered QR Code
                        if (qrImageBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrImageBitmap,
                                    contentDescription = "Receiver URL QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Diagnostics & Telemetry Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1F2937))
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = "DIAGNOSTICS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    DiagnosticRow("Quest LAN IP", diagnostics.ipAddress)
                    DiagnosticRow("HTTP Server Port", diagnostics.httpPort.toString())
                    DiagnosticRow("Signaling Port", diagnostics.wsPort.toString())
                    DiagnosticRow("Active Receivers", diagnostics.connectedReceivers.toString())
                    DiagnosticRow("Video Pipeline", "${diagnostics.resolution} @ ${diagnostics.fps} FPS")
                    DiagnosticRow("Video Codec", "H.264 Hardware (${diagnostics.bitrateKbps} kbps)")
                    DiagnosticRow("WebRTC ICE State", diagnostics.iceState)
                    DiagnosticRow("Peer Connection", diagnostics.connectionState)
                }
            }

            // Security Notice
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.5f))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Security Notice: LAN-Only streaming. Anyone on your local Wi-Fi who navigates to this URL can view your Quest screen. No internet or external cloud services are used.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF94A3B8)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE2E8F0)
        )
    }
}
