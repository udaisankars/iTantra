package com.example.itantra

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val ITantraLightColors = lightColorScheme(
    primary = Color(0xFF4655C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF101A61),
    secondary = Color(0xFF006B5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF79F8E1),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF8B5000),
    tertiaryContainer = Color(0xFFFFDDB7),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurface = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFF46464F),
    error = Color(0xFFBA1A1A)
)

private val ITantraDarkColors = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF172575),
    primaryContainer = Color(0xFF303C9A),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFF58DBC5),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005045),
    onSecondaryContainer = Color(0xFF79F8E1),
    tertiary = Color(0xFFFFB95F),
    tertiaryContainer = Color(0xFF693C00),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF46464F),
    onSurface = Color(0xFFE4E1E9),
    onSurfaceVariant = Color(0xFFC7C5D0),
    error = Color(0xFFFFB4AB)
)

@Composable
fun ITantraExpressiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }

        darkTheme -> ITantraDarkColors
        else -> ITantraLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp),
            extraLarge = RoundedCornerShape(44.dp)
        ),
        content = content
    )
}

@Composable
fun ITantraExpressiveScreen(
    modelReady: Boolean,
    modelStatus: String,
    recording: Boolean,
    processing: Boolean,
    transcript: String,
    elapsedSeconds: Int,
    maxRecordSeconds: Int,
    ttsReady: Boolean,
    ttsStatus: String,
    appMode: AppMode,
    networkStatus: String,
    autoSendEnabled: Boolean,
    nearbyReceivers: List<BleReceiver>,
    selectedReceiver: BleReceiver?,
    appLanguage: AppLanguage,
    receivedOriginalText: String,
    onTranscriptChange: (String) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onAutoSendChange: (Boolean) -> Unit,
    onReceiverSelected: (BleReceiver) -> Unit,
    onModeChange: (AppMode) -> Unit,
    onRefreshNetwork: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onSpeak: () -> Unit,
    onSend: () -> Unit
) {
    val engineFailed = modelStatus.isFailureMessage() || ttsStatus.isFailureMessage()
    val preparing = (!modelReady || !ttsReady) && !engineFailed

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (preparing) {
            ExpressiveLoadingScreen(
                modelReady = modelReady,
                ttsReady = ttsReady
            )
        } else {
            MainExperience(
                modelReady = modelReady,
                modelStatus = modelStatus,
                recording = recording,
                processing = processing,
                transcript = transcript,
                elapsedSeconds = elapsedSeconds,
                maxRecordSeconds = maxRecordSeconds,
                ttsReady = ttsReady,
                ttsStatus = ttsStatus,
                appMode = appMode,
                networkStatus = networkStatus,
                autoSendEnabled = autoSendEnabled,
                nearbyReceivers = nearbyReceivers,
                selectedReceiver = selectedReceiver,
                appLanguage = appLanguage,
                receivedOriginalText = receivedOriginalText,
                onTranscriptChange = onTranscriptChange,
                onLanguageChange = onLanguageChange,
                onAutoSendChange = onAutoSendChange,
                onReceiverSelected = onReceiverSelected,
                onModeChange = onModeChange,
                onRefreshNetwork = onRefreshNetwork,
                onPressStart = onPressStart,
                onPressEnd = onPressEnd,
                onSpeak = onSpeak,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun ExpressiveLoadingScreen(
    modelReady: Boolean,
    ttsReady: Boolean
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val motion = rememberInfiniteTransition()
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = FastOutSlowInEasing)
        )
    )
    val breath by motion.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(190.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer(rotationZ = rotation)
            ) {
                val radius = size.minDimension * 0.16f
                drawCircle(
                    color = primary,
                    radius = radius,
                    center = Offset(size.width * 0.50f, size.height * 0.14f)
                )
                drawCircle(
                    color = secondary,
                    radius = radius * 0.86f,
                    center = Offset(size.width * 0.86f, size.height * 0.69f)
                )
                drawCircle(
                    color = tertiary,
                    radius = radius * 1.08f,
                    center = Offset(size.width * 0.16f, size.height * 0.72f)
                )
            }

            Surface(
                modifier = Modifier
                    .size(92.dp)
                    .graphicsLayer(scaleX = breath, scaleY = breath),
                shape = RoundedCornerShape(34.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "iT",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Preparing iTantra",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReadyPill(label = "Voice input", ready = modelReady)
            ReadyPill(label = "Voice output", ready = ttsReady)
        }
    }
}

@Composable
private fun ReadyPill(label: String, ready: Boolean) {
    val motion = rememberInfiniteTransition()
    val alpha by motion.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        )
    )

    Surface(
        shape = CircleShape,
        color = if (ready) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer(alpha = if (ready) 1f else alpha)
                    .clip(CircleShape)
                    .background(
                        if (ready) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary
                    )
            )
            Text(
                text = if (ready) "$label ready" else label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MainExperience(
    modelReady: Boolean,
    modelStatus: String,
    recording: Boolean,
    processing: Boolean,
    transcript: String,
    elapsedSeconds: Int,
    maxRecordSeconds: Int,
    ttsReady: Boolean,
    ttsStatus: String,
    appMode: AppMode,
    networkStatus: String,
    autoSendEnabled: Boolean,
    nearbyReceivers: List<BleReceiver>,
    selectedReceiver: BleReceiver?,
    appLanguage: AppLanguage,
    receivedOriginalText: String,
    onTranscriptChange: (String) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onAutoSendChange: (Boolean) -> Unit,
    onReceiverSelected: (BleReceiver) -> Unit,
    onModeChange: (AppMode) -> Unit,
    onRefreshNetwork: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onSpeak: () -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .animateContentSize(animationSpec = spring()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ExpressiveHeader()

        Spacer(modifier = Modifier.height(14.dp))

        AppLanguageSelector(
            selectedLanguage = appLanguage,
            enabled = !recording && !processing,
            onLanguageChange = onLanguageChange
        )

        Spacer(modifier = Modifier.height(20.dp))

        ExpressiveModeSelector(
            selectedMode = appMode,
            enabled = !recording && !processing,
            onModeChange = onModeChange
        )

        Spacer(modifier = Modifier.height(14.dp))

        ConnectionCard(
            appMode = appMode,
            networkStatus = networkStatus,
            nearbyReceivers = nearbyReceivers,
            selectedReceiver = selectedReceiver,
            enabled = !recording && !processing,
            onReceiverSelected = onReceiverSelected,
            onRefreshNetwork = onRefreshNetwork
        )

        Spacer(modifier = Modifier.height(22.dp))

        if (appMode == AppMode.SENDER) {
            PushToTalkHero(
                modelReady = modelReady,
                recording = recording,
                processing = processing,
                elapsedSeconds = elapsedSeconds,
                maxRecordSeconds = maxRecordSeconds,
                onPressStart = onPressStart,
                onPressEnd = onPressEnd
            )

            Spacer(modifier = Modifier.height(18.dp))

            AutoSendControl(
                checked = autoSendEnabled,
                enabled = !recording && !processing,
                onCheckedChange = onAutoSendChange
            )
        } else {
            ReceiverHero(networkStatus = networkStatus)
        }

        AnimatedVisibility(
            visible = processing,
            enter = fadeIn() + expandVertically() + slideInVertically { it / 3 },
            exit = fadeOut() + shrinkVertically() + slideOutVertically { -it / 3 }
        ) {
            ProcessingCard()
        }

        AnimatedVisibility(
            visible = transcript.isNotBlank() && !processing,
            enter = fadeIn(tween(350)) +
                    expandVertically(animationSpec = spring()) +
                    slideInVertically(animationSpec = spring()) { it / 2 },
            exit = fadeOut() + shrinkVertically()
        ) {
            RecognizedMessageCard(
                transcript = transcript,
                receivedOriginalText = receivedOriginalText,
                appLanguage = appLanguage,
                appMode = appMode,
                ttsReady = ttsReady,
                ttsStatus = ttsStatus,
                recording = recording,
                processing = processing,
                selectedReceiver = selectedReceiver,
                onTranscriptChange = onTranscriptChange,
                onSpeak = onSpeak,
                onSend = onSend
            )
        }

        AnimatedVisibility(
            visible = modelStatus.isFailureMessage() || ttsStatus.isFailureMessage(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ErrorCard(
                message = if (modelStatus.isFailureMessage()) modelStatus else ttsStatus
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
    }
}

@Composable
private fun ExpressiveHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "iTantra",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Text(
                text = "Voice, directly nearby",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = "OFFLINE",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun AppLanguageSelector(
    selectedLanguage: AppLanguage,
    enabled: Boolean,
    onLanguageChange: (AppLanguage) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "My language",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppLanguage.values().forEach { language ->
                    val selected = language == selectedLanguage
                    if (selected) {
                        Button(
                            onClick = { onLanguageChange(language) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp),
                            shape = RoundedCornerShape(20.dp, 20.dp, 8.dp, 20.dp)
                        ) {
                            Text(
                                text = language.nativeName,
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { onLanguageChange(language) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = language.nativeName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                    }
                }
            }
            Text(
                text = "Speak, receive and hear in ${selectedLanguage.nativeName}",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ExpressiveModeSelector(
    selectedMode: AppMode,
    enabled: Boolean,
    onModeChange: (AppMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModeButton(
                label = "Talk",
                selected = selectedMode == AppMode.SENDER,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(AppMode.SENDER) }
            )
            ModeButton(
                label = "Receive",
                selected = selectedMode == AppMode.RECEIVER,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(AppMode.RECEIVER) }
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val corner by animateFloatAsState(
        targetValue = if (selected) 22f else 16f,
        animationSpec = spring()
    )

    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(56.dp),
            shape = RoundedCornerShape(corner.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(56.dp),
            shape = RoundedCornerShape(corner.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color.Transparent
            )
        ) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ConnectionCard(
    appMode: AppMode,
    networkStatus: String,
    nearbyReceivers: List<BleReceiver>,
    selectedReceiver: BleReceiver?,
    enabled: Boolean,
    onReceiverSelected: (BleReceiver) -> Unit,
    onRefreshNetwork: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring()),
        shape = RoundedCornerShape(32.dp, 14.dp, 32.dp, 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (appMode == AppMode.SENDER) "Nearby receiver" else "Bluetooth receiver",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = conciseNetworkStatus(networkStatus, appMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (networkStatus.isFailureMessage()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                BluetoothMark(active = selectedReceiver != null || appMode == AppMode.RECEIVER)
            }

            if (appMode == AppMode.SENDER) {
                if (nearbyReceivers.isEmpty()) {
                    SearchingRow()
                } else {
                    nearbyReceivers.forEach { receiver ->
                        ReceiverChoice(
                            receiver = receiver,
                            selected = receiver.id == selectedReceiver?.id,
                            enabled = enabled,
                            onClick = { onReceiverSelected(receiver) }
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRefreshNetwork,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = if (nearbyReceivers.isEmpty()) "Search" else "Search again",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                ReceiverSignalMini()
            }
        }
    }
}

@Composable
private fun BluetoothMark(active: Boolean) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp),
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            BluetoothGlyph(
                color = if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(23.dp)
            )
        }
    }
}

@Composable
private fun BluetoothGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = 2.5.dp.toPx()
        val centerX = size.width * 0.46f
        val top = Offset(centerX, size.height * 0.06f)
        val bottom = Offset(centerX, size.height * 0.94f)
        val rightUpper = Offset(size.width * 0.78f, size.height * 0.29f)
        val leftUpper = Offset(size.width * 0.24f, size.height * 0.39f)
        val rightLower = Offset(size.width * 0.78f, size.height * 0.71f)
        val leftLower = Offset(size.width * 0.24f, size.height * 0.61f)

        drawLine(color, top, bottom, stroke, StrokeCap.Round)
        drawLine(color, top, rightUpper, stroke, StrokeCap.Round)
        drawLine(color, rightUpper, leftLower, stroke, StrokeCap.Round)
        drawLine(color, leftUpper, rightLower, stroke, StrokeCap.Round)
        drawLine(color, rightLower, bottom, stroke, StrokeCap.Round)
    }
}

@Composable
private fun SearchingRow() {
    val primary = MaterialTheme.colorScheme.primary
    val motion = rememberInfiniteTransition()
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1_200))
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Canvas(modifier = Modifier.size(34.dp)) {
            val base = size.minDimension * 0.16f
            drawCircle(
                color = primary,
                radius = base
            )
            drawCircle(
                color = primary.copy(alpha = (1f - phase) * 0.55f),
                radius = base + size.minDimension * 0.28f * phase,
                style = Stroke(width = 3.dp.toPx())
            )
        }
        Text(
            text = "Looking nearby…",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ReceiverChoice(
    receiver: BleReceiver,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val label = receiver.name.ifBlank { "iTantra receiver" }
    val signal = when {
        receiver.rssi >= -60 -> "Strong"
        receiver.rssi >= -75 -> "Good"
        else -> "Nearby"
    }

    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("$signal · Selected", style = MaterialTheme.typography.labelMedium)
            }
            Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(22.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(signal, style = MaterialTheme.typography.labelMedium)
            }
            Text("Select", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReceiverSignalMini() {
    val secondary = MaterialTheme.colorScheme.secondary
    val motion = rememberInfiniteTransition()
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1_500))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(42.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(
                color = secondary,
                radius = 5.dp.toPx()
            )
            drawCircle(
                color = secondary.copy(alpha = (1f - phase) * 0.5f),
                radius = 8.dp.toPx() + 10.dp.toPx() * phase,
                style = Stroke(width = stroke)
            )
        }
    }
}

@Composable
private fun PushToTalkHero(
    modelReady: Boolean,
    recording: Boolean,
    processing: Boolean,
    elapsedSeconds: Int,
    maxRecordSeconds: Int,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val enabled = modelReady && !processing
    val motion = rememberInfiniteTransition()
    val pulse by motion.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val heroScale by animateFloatAsState(
        targetValue = if (recording) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.56f, stiffness = 520f)
    )
    val shape = if (recording) {
        RoundedCornerShape(64.dp, 64.dp, 28.dp, 64.dp)
    } else {
        RoundedCornerShape(72.dp, 72.dp, 28.dp, 72.dp)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(232.dp),
            contentAlignment = Alignment.Center
        ) {
            if (recording) {
                Surface(
                    modifier = Modifier
                        .size(212.dp)
                        .graphicsLayer(scaleX = pulse, scaleY = pulse, alpha = 0.28f),
                    shape = shape,
                    color = MaterialTheme.colorScheme.error
                ) {}
            }

            Surface(
                modifier = Modifier
                    .size(196.dp)
                    .graphicsLayer(scaleX = heroScale, scaleY = heroScale)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Hold to talk"
                    }
                    .pointerInput(modelReady, processing) {
                        detectTapGestures(
                            onPress = {
                                if (modelReady && !processing) {
                                    onPressStart()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        onPressEnd()
                                    }
                                }
                            }
                        )
                    },
                shape = shape,
                color = when {
                    recording -> MaterialTheme.colorScheme.error
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                tonalElevation = if (recording) 2.dp else 10.dp,
                shadowElevation = if (recording) 2.dp else 6.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    MicrophoneGlyph(
                        color = when {
                            recording -> Color.White
                            enabled -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(13.dp))
                    Text(
                        text = if (recording) "RECORDING" else "HOLD TO TALK",
                        color = when {
                            recording -> Color.White
                            enabled -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 0.7.sp
                    )
                }
            }
        }

        Text(
            text = when {
                recording -> String.format(
                    Locale.US,
                    "%02d:%02d / 00:%02d",
                    elapsedSeconds / 60,
                    elapsedSeconds % 60,
                    maxRecordSeconds
                )
                processing -> "Recognizing…"
                !modelReady -> "Voice input unavailable"
                else -> "Press, speak, release"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (recording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun MicrophoneGlyph(color: Color) {
    Canvas(modifier = Modifier.size(54.dp)) {
        val strokeWidth = 5.dp.toPx()
        val micWidth = size.width * 0.34f
        val micHeight = size.height * 0.56f
        val micLeft = (size.width - micWidth) / 2f
        val micTop = size.height * 0.06f

        drawRoundRect(
            color = color,
            topLeft = Offset(micLeft, micTop),
            size = androidx.compose.ui.geometry.Size(micWidth, micHeight),
            cornerRadius = CornerRadius(micWidth / 2f, micWidth / 2f),
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.18f, size.height * 0.29f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.64f, size.height * 0.48f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.76f),
            end = Offset(size.width / 2f, size.height * 0.91f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.34f, size.height * 0.91f),
            end = Offset(size.width * 0.66f, size.height * 0.91f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun AutoSendControl(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Send after recognition",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun ReceiverHero(networkStatus: String) {
    val secondary = MaterialTheme.colorScheme.secondary
    val motion = rememberInfiniteTransition()
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1_650))
    )

    Column(
        modifier = Modifier.padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(184.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerRadius = 44.dp.toPx()
                drawCircle(
                    color = secondary.copy(alpha = (1f - phase) * 0.30f),
                    radius = centerRadius + 38.dp.toPx() * phase,
                    style = Stroke(width = 5.dp.toPx())
                )
                drawCircle(
                    color = secondary.copy(alpha = 0.16f),
                    radius = centerRadius + 22.dp.toPx()
                )
            }
            Surface(
                modifier = Modifier.size(92.dp),
                shape = RoundedCornerShape(32.dp, 32.dp, 12.dp, 32.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 5.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BluetoothGlyph(
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
        }
        Text(
            text = "Ready to receive",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black
        )
        Text(
            text = conciseNetworkStatus(networkStatus, AppMode.RECEIVER),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProcessingCard() {
    val motion = rememberInfiniteTransition()
    val first by motion.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        )
    )
    val second by motion.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        shape = RoundedCornerShape(14.dp, 32.dp, 32.dp, 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ProcessingDot(alpha = first, height = 16)
                ProcessingDot(alpha = second, height = 28)
                ProcessingDot(alpha = first, height = 21)
            }
            Column {
                Text(
                    text = "Recognizing voice",
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "On this phone",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ProcessingDot(alpha: Float, height: Int) {
    Box(
        modifier = Modifier
            .size(width = 7.dp, height = height.dp)
            .graphicsLayer(alpha = alpha)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
    )
}

@Composable
private fun RecognizedMessageCard(
    transcript: String,
    receivedOriginalText: String,
    appLanguage: AppLanguage,
    appMode: AppMode,
    ttsReady: Boolean,
    ttsStatus: String,
    recording: Boolean,
    processing: Boolean,
    selectedReceiver: BleReceiver?,
    onTranscriptChange: (String) -> Unit,
    onSpeak: () -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        shape = RoundedCornerShape(34.dp, 34.dp, 14.dp, 34.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (appMode == AppMode.SENDER) "Recognized message" else "Received message",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "READY",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AnimatedVisibility(visible = receivedOriginalText.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Original message",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = receivedOriginalText,
                            modifier = Modifier.padding(top = 5.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            OutlinedTextField(
                value = transcript,
                onValueChange = onTranscriptChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !recording && !processing,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 25.sp
                ),
                minLines = 3,
                maxLines = 7,
                shape = RoundedCornerShape(22.dp),
                label = { Text("Message · ${appLanguage.nativeName}") }
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (appMode == AppMode.SENDER) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSpeak,
                        enabled = ttsReady && transcript.isNotBlank() && !processing,
                        modifier = Modifier
                            .weight(0.82f)
                            .height(62.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = if (ttsStatus == "Speaking...") "Speaking" else "Listen",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }

                    Button(
                        onClick = onSend,
                        enabled = selectedReceiver != null &&
                                transcript.isNotBlank() &&
                                !recording &&
                                !processing,
                        modifier = Modifier
                            .weight(1.18f)
                            .height(62.dp),
                        shape = RoundedCornerShape(20.dp, 20.dp, 7.dp, 20.dp)
                    ) {
                        Text("Send now", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }
            } else {
                Button(
                    onClick = onSpeak,
                    enabled = ttsReady && transcript.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    shape = RoundedCornerShape(20.dp, 20.dp, 7.dp, 20.dp)
                ) {
                    Text(
                        text = if (ttsStatus == "Speaking...") "Speaking…" else "Play again",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun String.isFailureMessage(): Boolean {
    return contains("error", ignoreCase = true) ||
            contains("failed", ignoreCase = true) ||
            contains("unable", ignoreCase = true) ||
            contains("permission", ignoreCase = true) ||
            contains("not available", ignoreCase = true)
}

private fun conciseNetworkStatus(raw: String, mode: AppMode): String {
    if (raw.isFailureMessage()) return raw

    return when {
        raw.contains("search", ignoreCase = true) ||
                raw.contains("scan", ignoreCase = true) -> "Searching nearby…"
        raw.contains("selected", ignoreCase = true) -> raw
        raw.contains("sent", ignoreCase = true) -> "Message sent"
        raw.contains("received", ignoreCase = true) -> "Message received"
        raw.contains("advert", ignoreCase = true) ||
                raw.contains("ready", ignoreCase = true) ||
                raw.contains("listen", ignoreCase = true) -> {
            if (mode == AppMode.RECEIVER) "Visible to nearby iTantra phones" else raw
        }
        mode == AppMode.RECEIVER -> "Visible to nearby iTantra phones"
        else -> raw
    }
}
