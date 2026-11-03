package com.nexusai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                NexusScreen()
            }
        }
    }
}

@Composable
fun NexusScreen() {
    var isListening by remember { mutableStateOf(false) }

    val backgroundColor = Color.Black
    val ringColor = Color(0xFF383838)
    val highlightColor = Color(0xFFD9D9D9)

    val infiniteTransition = rememberInfiniteTransition(label = "nexusRings")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRotation"
    )

    val middleRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "middleRotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRotation"
    )

    val centerScale by animateFloatAsState(
        targetValue = if (isListening) 1.12f else 1f,
        animationSpec = tween(350),
        label = "centerScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text = "PERSONAL AI ASSISTANT",
                color = Color(0xFF666666),
                fontSize = 9.sp,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(38.dp))

            Box(
                modifier = Modifier.size(280.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer mechanical ring
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .rotate(outerRotation)
                        .border(1.dp, ringColor, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .width(22.dp)
                            .height(3.dp)
                            .background(highlightColor)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(22.dp)
                            .height(3.dp)
                            .background(ringColor)
                    )
                }

                // Middle ring
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .rotate(middleRotation)
                        .border(2.dp, Color(0xFF555555), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(4.dp)
                            .height(18.dp)
                            .background(highlightColor)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(4.dp)
                            .height(18.dp)
                            .background(ringColor)
                    )
                }

                // Inner ring
                Box(
                    modifier = Modifier
                        .size(155.dp)
                        .rotate(innerRotation)
                        .border(1.dp, Color(0xFF777777), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(7.dp)
                            .background(highlightColor, CircleShape)
                    )
                }

                // Interactive center
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .scale(centerScale)
                        .background(Color(0xFF111111), CircleShape)
                        .border(
                            width = if (isListening) 2.dp else 1.dp,
                            color = if (isListening) highlightColor else ringColor,
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember {
                                MutableInteractionSource()
                            },
                            indication = null
                        ) {
                            isListening = !isListening
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .background(
                                color = if (isListening) Color.White
                                else highlightColor,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(42.dp))

            Text(
                text = "NEXUS",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "INTELLIGENCE IN MOTION",
                color = Color(0xFF888888),
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = if (isListening) "LISTENING" else "STANDBY",
                color = if (isListening) Color.White else Color(0xFF888888),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isListening) {
                    "VOICE INPUT NOT CONNECTED"
                } else {
                    "TAP CENTER TO ACTIVATE"
                },
                color = Color(0xFF555555),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp
            )
        }
    }
}
