package org.phononai.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.phononai.app.AppState
import org.phononai.app.Kiosk
import org.phononai.app.PhononService
import org.phononai.app.Settings
import org.phononai.app.ui.avatar.AvatarView
import org.phononai.app.ui.theme.PhononColors
import java.io.File
import kotlin.math.abs
import kotlin.math.max

private enum class Tab(val label: String) {
    Serve("Serve"),
    Models("Models"),
    Settings("Settings"),
}

private enum class AvatarKind {
    Stopped,
    Loading,
    Ready,
    Generating,
    Paused,
    Error,
}

private enum class ActionTone {
    Cyan,
    Amber,
    Dim,
}

private data class PrimaryAction(
    val label: String,
    val tone: ActionTone,
    val onClick: () -> Unit,
)

@Composable
fun PhononApp() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var health by remember { mutableStateOf<Health?>(null) }
    var download by remember { mutableStateOf<DownloadProgress?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var tpsHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var tabName by rememberSaveable { mutableStateOf(Tab.Serve.name) }
    var bypassSetup by rememberSaveable { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val service = AppState.service
            val nextHealth = try {
                service?.let { parseHealth(it.uiHealthJson()) }
            } catch (_: Throwable) {
                null
            }
            val nextDownload = try {
                service?.let { parseDownload(it.uiDownloadJson()) }
            } catch (_: Throwable) {
                null
            }
            health = nextHealth
            download = nextDownload
            tick++
            val tps = nextHealth?.lastDecodeTps
            if (tps != null && tps > 0) {
                val last = tpsHistory.lastOrNull()
                if (last == null || abs(last - tps.toFloat()) > 0.05f) {
                    tpsHistory = (tpsHistory + tps.toFloat()).takeLast(32)
                }
            }
            delay(1_000)
        }
    }

    val models = remember(tick, settings.modelPath) {
        installedModels(context, settings.modelPath)
    }
    val showSetup = models.isEmpty() && !bypassSetup

    CompositionLocalProvider(LocalContentColor provides PhononColors.Text) {
    Box(Modifier.fillMaxSize().background(PhononColors.Background)) {
        if (showSetup) {
            SetupScreen(
                settings = settings,
                hasModel = models.isNotEmpty(),
                onDownloadFirst = { bypassSetup = true; tabName = Tab.Models.name },
            )
        } else {
            val tab = Tab.entries.firstOrNull { it.name == tabName } ?: Tab.Serve
            val kind = avatarKind(health)
            val primary = when (tab) {
                Tab.Serve -> serveAction(health, kind, context)
                Tab.Models -> null
                Tab.Settings -> null
            }
            MainFrame(
                tab = tab,
                onTab = { tabName = it.name },
                primary = primary,
            ) {
                when (tab) {
                    Tab.Serve -> ServeScreen(
                        health = health,
                        settings = settings,
                        kind = kind,
                        tpsHistory = tpsHistory,
                    )
                    Tab.Models -> ModelsScreen(
                        context = context,
                        settings = settings,
                        models = models,
                        download = download,
                        tick = tick,
                    )
                    Tab.Settings -> SettingsScreen(
                        context = context,
                        settings = settings,
                        health = health,
                        models = models,
                        onShowLog = { showLog = true },
                    )
                }
            }
        }
    }
    }

    if (showLog) {
        LogDialog(onDismiss = { showLog = false })
    }
}

@Composable
private fun MainFrame(
    tab: Tab,
    onTab: (Tab) -> Unit,
    primary: PrimaryAction?,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(PhononColors.Background),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            if (primary != null) {
                Spacer(Modifier.height(10.dp))
                PrimaryButton(primary)
            }
            Spacer(Modifier.height(12.dp))
            TabBar(tab, onTab)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PrimaryButton(action: PrimaryAction) {
    val colors = when (action.tone) {
        ActionTone.Cyan -> ButtonDefaults.buttonColors(
            containerColor = PhononColors.Cyan,
            contentColor = Color(0xFF001318),
        )
        ActionTone.Amber -> ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1A222C),
            contentColor = PhononColors.Warn,
        )
        ActionTone.Dim -> ButtonDefaults.buttonColors(
            containerColor = PhononColors.SurfaceHigh,
            contentColor = PhononColors.TextDim,
        )
    }
    Button(
        onClick = action.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .then(
                if (action.tone == ActionTone.Amber) {
                    Modifier.border(1.dp, PhononColors.Warn, RoundedCornerShape(18.dp))
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(18.dp),
        colors = colors,
    ) {
        Text(action.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabBar(selected: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF060B10))
            .border(1.dp, PhononColors.Border, RoundedCornerShape(18.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTab(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = if (on) PhononColors.Cyan else PhononColors.TextDim,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Serve
// ---------------------------------------------------------------------------

@Composable
private fun ServeScreen(
    health: Health?,
    settings: Settings,
    kind: AvatarKind,
    tpsHistory: List<Float>,
) {
    val accent = accentFor(kind)
    val stateWord = when (kind) {
        AvatarKind.Stopped -> "Stopped"
        AvatarKind.Loading -> "Starting"
        AvatarKind.Ready -> "Serving"
        AvatarKind.Generating -> "Generating"
        AvatarKind.Paused -> "Paused — too warm"
        AvatarKind.Error -> "Error"
    }
    val modelName = modelLabel(
        health?.model?.takeIf { it.isNotBlank() }
            ?: File(settings.modelPath).name,
    )
    val contextK = "${settings.contextSize / 1024}K context"
    val meta = when (kind) {
        AvatarKind.Stopped -> "Server is off · the key stays on this phone"
        AvatarKind.Loading -> "Loading $modelName into memory"
        AvatarKind.Ready -> "$modelName · $contextK · top-app"
        AvatarKind.Generating -> "$modelName · ${health?.lastDecodeTps?.let { "%.1f tok/s decode".format(it) } ?: "decode in flight"}"
        AvatarKind.Paused -> "Resumes automatically at ${restartThreshold(settings.thermalResumeC)} °C"
        AvatarKind.Error -> health?.error ?: "The model server did not start"
    }
    val tps = health?.lastDecodeTps?.toFloat() ?: 0f
    val animatedTps by animateFloatAsState(
        targetValue = tps,
        animationSpec = tween(400),
        label = "decode tps",
    )
    val (upValue, upUnit) = formatUptime(health?.uptimeS)
    val temp = health?.batteryTempC
    val paused = kind == AvatarKind.Paused
    val temperatureFraction = ((temp ?: 0f) / 60f).coerceIn(0.04f, 1f)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // AvatarView draws the particle body at 0.32 × the shorter side.
            // Measured on the Pixel 7: a 140 dp slot gave a 77 dp visible orb,
            // so 172 dp yields a ~92 dp visible cloud with the 1.55 × halo
            // still inside the bounds. The old 92 dp slot produced the ~60 dp
            // smudge this fixes.
            Avatar(kind = kind, amp = (tps / 20f).coerceIn(0f, 1f), modifier = Modifier.size(172.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(PhononColors.Cyan))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "PHONON",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF00E5FF),
                            letterSpacing = 2.4.sp,
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stateWord,
                    color = accent,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    meta,
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        val hasMeasuredRate = tps > 0f
        val generating = kind == AvatarKind.Generating
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = when {
                    generating -> "Throughput"
                    hasMeasuredRate -> "Last throughput"
                    else -> "Throughput"
                },
                value = when {
                    !hasMeasuredRate -> "idle"
                    generating -> "%.1f".format(animatedTps)
                    else -> "%.1f".format(tps)
                },
                unit = if (hasMeasuredRate) "tok/s" else "",
                valueColor = if (!hasMeasuredRate || !generating) PhononColors.TextDim else PhononColors.Text,
                accent = accent,
            ) {
                if (hasMeasuredRate) {
                    Sparkline(
                        tpsHistory,
                        if (generating) accent else accent.copy(alpha = 0.45f),
                        Modifier.fillMaxWidth().height(26.dp).padding(top = 8.dp),
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (kind == AvatarKind.Stopped) "Server is off" else "Awaiting first request",
                        color = PhononColors.TextDim.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                    )
                }
            }
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "Uptime",
                value = upValue,
                unit = upUnit,
                accent = accent,
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${health?.requestsCompleted ?: 0} requests",
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        EndpointCard(health = health, accent = accent)
        Spacer(Modifier.height(14.dp))
        ThermalCard(
            temp = temp,
            fraction = temperatureFraction,
            paused = paused,
            settings = settings,
        )
        // Pin the activity line to the bottom of the content area instead of
        // leaving a dead third of the screen above the primary action.
        Spacer(Modifier.weight(1f))
        RecentActivity(health = health, accent = accent)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    label: String,
    value: String,
    unit: String,
    accent: Color,
    valueColor: Color = PhononColors.Text,
    extra: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text(
            label.uppercase(),
            color = PhononColors.TextDim,
            fontSize = 11.sp,
            letterSpacing = 0.9.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = valueColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    color = PhononColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        extra()
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(22.dp)
                .height(2.dp)
                .background(accent.copy(alpha = 0.35f), RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun Sparkline(points: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val data = if (points.size >= 2) points else listOf(0.35f, 0.35f)
        val maxValue = max(20f, data.maxOrNull() ?: 20f)
        val stepX = size.width / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value / maxValue).coerceIn(0f, 1f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.9f),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun EndpointCard(health: Health?, accent: Color) {
    val context = LocalContext.current
    val url = endpointUrl(health, context)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "OPENAI-COMPATIBLE",
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                letterSpacing = 0.9.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                url,
                color = Color(0xFFD7E3DF),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, PhononColors.Border, RoundedCornerShape(12.dp))
                .clickable {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Phonon endpoint", url))
                    toast(context, "Endpoint copied")
                },
            contentAlignment = Alignment.Center,
        ) {
            CopyGlyph(accent)
        }
    }
}

@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = 1.3.dp.toPx())
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
            size = Size(10.dp.toPx(), 10.dp.toPx()),
            cornerRadius = radius,
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(10.dp.toPx(), 10.dp.toPx()),
            cornerRadius = radius,
            style = stroke,
        )
    }
}

@Composable
private fun ThermalCard(
    temp: Float?,
    fraction: Float,
    paused: Boolean,
    settings: Settings,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "THERMALS",
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                letterSpacing = 0.9.sp,
            )
            Text(
                formatTemp(temp),
                color = if (paused) PhononColors.Warn else PhononColors.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1A2124)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (paused) {
                            Brush.horizontalGradient(listOf(PhononColors.Warn, PhononColors.Danger))
                        } else {
                            Brush.horizontalGradient(listOf(PhononColors.Cyan, PhononColors.Violet))
                        },
                    ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (paused) {
                "Model unloaded while hot · reconnects on resume"
            } else {
                "Pauses at ${restartThreshold(settings.thermalStopC)} °C · resumes at ${restartThreshold(settings.thermalResumeC)} °C"
            },
            color = if (paused) PhononColors.Warn.copy(alpha = 0.9f) else PhononColors.TextDim,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun RecentActivity(health: Health?, accent: Color) {
    val completed = health?.requestsCompleted ?: 0
    val last = health?.lastCompletionAt
    val summary = when {
        completed == 0 && last == null -> "No requests yet"
        last == null -> "$completed completed"
        else -> "$completed completed · ${ageLabel(last)}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(accent.copy(alpha = 0.7f)))
        Spacer(Modifier.width(8.dp))
        Text(
            "RECENT ACTIVITY",
            color = PhononColors.TextDim,
            fontSize = 10.sp,
            letterSpacing = 0.9.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            summary,
            color = PhononColors.TextDim,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Avatar(kind: AvatarKind, amp: Float, modifier: Modifier) {
    val driver = remember { AvatarDriver() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AvatarView(context).apply {
                setIdleTheme("aura")
                applyStateShapes(
                    speaking = "waveform",
                    listening = "iris",
                    thinking = "flame",
                )
                setVisualCategory("lumen")
                driver.attach(this)
                driver.apply(kind, amp)
            }
        },
        update = { view ->
            driver.attach(view)
            driver.apply(kind, amp)
        },
    )
}

private class AvatarDriver {
    private var view: AvatarView? = null
    private var lastKind: AvatarKind? = null
    private var lastAmp: Float = -1f

    fun attach(view: AvatarView) {
        this.view = view
    }

    fun apply(kind: AvatarKind, amp: Float) {
        val v = view ?: return
        val ampChanged = abs(lastAmp - amp) >= 0.04f
        if (kind == lastKind && !(kind == AvatarKind.Generating && ampChanged)) return
        lastKind = kind
        lastAmp = amp
        when (kind) {
            AvatarKind.Stopped -> {
                v.setIdleTheme("aura")
                v.setPresent("idle", null, 0f, 0f)
            }
            AvatarKind.Loading -> v.setPresent("gather", null, 0.6f, 0f)
            AvatarKind.Ready -> v.setPresent("listening", null, 0f, 0f)
            AvatarKind.Generating -> v.setPresent("speaking", null, amp * 0.55f, amp)
            AvatarKind.Paused -> v.setPresent("waiting", null, 0f, 0f)
            AvatarKind.Error -> {
                v.setPresent("recoil", null, 0.8f, 0.45f)
                v.postDelayed({
                    if (lastKind == AvatarKind.Error) {
                        v.setPresent("waiting", null, 0f, 0f)
                    }
                }, 480L)
            }
        }
    }
}

private fun avatarKind(health: Health?): AvatarKind {
    val h = health ?: return AvatarKind.Loading
    return when (h.state) {
        "stopped" -> AvatarKind.Stopped
        "loading", "restarting" -> AvatarKind.Loading
        "thermal_pause" -> AvatarKind.Paused
        "error" -> AvatarKind.Error
        "ready" -> {
            val recentlyFinished = h.lastCompletionAt?.let {
                System.currentTimeMillis() - it < 1_400L
            } ?: false
            if (h.requestsInFlight > 0 || recentlyFinished) {
                AvatarKind.Generating
            } else {
                AvatarKind.Ready
            }
        }
        else -> AvatarKind.Loading
    }
}

@Composable
private fun serveAction(health: Health?, kind: AvatarKind, context: Context): PrimaryAction =
    when (kind) {
        AvatarKind.Stopped, AvatarKind.Error -> PrimaryAction("Start serving", ActionTone.Cyan) {
            sendServiceAction(context, PhononService.ACTION_START_SERVER)
        }
        AvatarKind.Paused -> PrimaryAction("Resume manually", ActionTone.Amber) {
            AppState.service?.uiResumeThermal() ?: toast(context, "Service is starting")
        }
        AvatarKind.Loading -> PrimaryAction("Stop serving", ActionTone.Dim) {
            sendServiceAction(context, PhononService.ACTION_STOP_SERVER)
        }
        AvatarKind.Ready, AvatarKind.Generating -> PrimaryAction("Stop serving", ActionTone.Cyan) {
            sendServiceAction(context, PhononService.ACTION_STOP_SERVER)
        }
    }

private fun accentFor(kind: AvatarKind): Color = when (kind) {
    AvatarKind.Stopped -> PhononColors.DimCyan
    AvatarKind.Loading -> PhononColors.Violet
    AvatarKind.Ready -> PhononColors.Cyan
    AvatarKind.Generating -> PhononColors.Violet
    AvatarKind.Paused -> PhononColors.Warn
    AvatarKind.Error -> PhononColors.Danger
}

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

@Composable
private fun ModelsScreen(
    context: Context,
    settings: Settings,
    models: List<InstalledModel>,
    download: DownloadProgress?,
    tick: Int,
) {
    var downloadUrl by rememberSaveable { mutableStateOf(settings.downloadUrl) }
    val storage = modelStorageBytes(models)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("Models", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "GGUF files on this phone. The active model is what the endpoint serves.",
            color = PhononColors.TextDim,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(PhononColors.Surface)
                .border(1.dp, PhononColors.Border, RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Text("STORAGE USED", color = PhononColors.TextDim, fontSize = 11.sp, letterSpacing = 0.9.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatBytes(storage),
                    color = PhononColors.Text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${models.size} model${if (models.size == 1) "" else "s"}",
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (models.isEmpty()) {
            PhononCard {
                Text("No models installed", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Download a GGUF below, or copy one into the app's models folder.",
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                models.forEach { model ->
                    ModelCard(
                        context = context,
                        model = model,
                        onChanged = { tick },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PhononCard {
            Text("Download a model", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "ModelDownloader resumes a partial file and verifies the declared size.",
                color = PhononColors.TextDim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            PhononField(
                value = downloadUrl,
                onValueChange = { downloadUrl = it },
                label = "GGUF URL",
                placeholder = "https://huggingface.co/…/model.gguf",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste a direct GGUF link. Nothing downloads until you press Download.",
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            val active = download?.active == true
            Button(
                onClick = {
                    val url = downloadUrl.trim()
                    val service = AppState.service
                    when {
                        url.isEmpty() -> toast(context, "Paste a GGUF URL first")
                        service == null -> toast(context, "Service is starting")
                        else -> {
                            val result = service.startDownload(url)
                            if (result.contains("\"error\"")) {
                                toast(context, JSONObject(result).optString("error", "Download failed"))
                            } else {
                                toast(context, "Download started")
                            }
                        }
                    }
                },
                enabled = !active && downloadUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PhononColors.Cyan,
                    contentColor = Color(0xFF001318),
                    disabledContainerColor = PhononColors.SurfaceHigh,
                    disabledContentColor = PhononColors.TextDim,
                ),
            ) {
                Text(if (active) "Downloading…" else "Download", fontWeight = FontWeight.Bold)
            }
            if (download != null && (download.active || download.error != null || download.done)) {
                Spacer(Modifier.height(12.dp))
                val fraction = if (download.total > 0) {
                    (download.bytes.toFloat() / download.total.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (download.active) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1A2124)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(PhononColors.Cyan, RoundedCornerShape(3.dp)),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    when {
                        download.error != null -> "Failed: ${download.error}"
                        download.active -> "${formatBytes(download.bytes)} of ${if (download.total > 0) formatBytes(download.total) else "…"}"
                        else -> "Downloaded ${formatBytes(download.bytes)}"
                    },
                    color = if (download.error != null) PhononColors.Danger else PhononColors.TextDim,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelCard(context: Context, model: InstalledModel, onChanged: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PhononColors.Surface)
            .border(
                1.dp,
                if (model.active) PhononColors.Cyan.copy(alpha = 0.45f) else PhononColors.Border,
                RoundedCornerShape(18.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.name,
                    color = PhononColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(formatBytes(model.sizeBytes), color = PhononColors.TextDim, fontSize = 13.sp)
            }
            if (model.active) Chip("ACTIVE", PhononColors.Cyan)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!model.active) {
                TextButton(
                    onClick = {
                        val service = AppState.service
                        if (service == null) {
                            toast(context, "Service is starting")
                        } else {
                            val body = JSONObject().put("model_path", model.file.absolutePath)
                            val result = service.updateSettings(body.toString())
                            if (result.contains("\"error\"")) {
                                toast(context, JSONObject(result).optString("error", "Could not switch model"))
                            } else {
                                toast(context, "Active model: ${model.name}")
                                onChanged()
                            }
                        }
                    },
                ) {
                    Text("Use this model", color = PhononColors.Cyan, fontSize = 13.sp)
                }
            }
            if (!model.active) {
                TextButton(
                    onClick = {
                        if (model.file.delete()) {
                            toast(context, "Deleted ${model.name}")
                            onChanged()
                        } else {
                            toast(context, "Could not delete ${model.name}")
                        }
                    },
                ) {
                    Text("Delete", color = PhononColors.Danger, fontSize = 13.sp)
                }
            } else {
                Text(
                    "Stop serving before deleting the active model.",
                    color = PhononColors.TextDim,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

private class SettingsDraft(context: Context) {
    private val s = Settings(context)
    var modelPath by mutableStateOf(s.modelPath)
    var contextSize by mutableStateOf(s.contextSize.toString())
    var threads by mutableStateOf(s.threads.toString())
    var bindAddress by mutableStateOf(s.bindAddress)
    var port by mutableStateOf(s.port.toString())
    var backendPort by mutableStateOf(s.backendPort.toString())
    var alias by mutableStateOf(s.alias)
    var downloadUrl by mutableStateOf(s.downloadUrl)
    var keepWarmInterval by mutableStateOf(s.keepWarmIntervalS.toString())
    var thermalStop by mutableStateOf(s.thermalStopC.toString())
    var thermalResume by mutableStateOf(s.thermalResumeC.toString())
    var loadMode by mutableStateOf(s.loadMode)
    var keepWarm by mutableStateOf(s.keepWarm)
    var reasoning by mutableStateOf(s.reasoning)
    var autoStart by mutableStateOf(s.autoStart)
}

@Composable
private fun SettingsScreen(
    context: Context,
    settings: Settings,
    health: Health?,
    models: List<InstalledModel>,
    onShowLog: () -> Unit,
) {
    val draft = remember { SettingsDraft(context) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var keySet by remember { mutableStateOf(settings.apiKey.isNotBlank()) }
    var showKey by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val activeModel = models.firstOrNull { it.active }

    fun save(): String? {
        val service = AppState.service ?: return "Service is starting"
        val ctxSize = draft.contextSize.toIntOrNull() ?: return "Context must be a number"
        val threadCount = draft.threads.toIntOrNull() ?: return "Threads must be a number"
        val publicPort = draft.port.toIntOrNull() ?: return "API port must be a number"
        val internalPort = draft.backendPort.toIntOrNull() ?: return "Backend port must be a number"
        val stop = draft.thermalStop.toFloatOrNull() ?: return "Pause threshold must be a number"
        val resume = draft.thermalResume.toFloatOrNull() ?: return "Resume threshold must be a number"
        val warmInterval = draft.keepWarmInterval.toIntOrNull() ?: return "Keep-warm interval must be a number"
        val body = JSONObject().apply {
            put("model_path", draft.modelPath.trim())
            put("context", ctxSize)
            put("threads", threadCount)
            put("bind_address", draft.bindAddress.trim())
            put("port", publicPort)
            put("backend_port", internalPort)
            put("alias", draft.alias.trim())
            // Empty means "no default": omit it so the API's http(s) validation
            // only applies to a URL the operator actually entered.
            draft.downloadUrl.trim().takeIf { it.isNotEmpty() }?.let { put("download_url", it) }
            put("keep_warm_interval_s", warmInterval)
            put("thermal_stop_c", stop.toDouble())
            put("thermal_resume_c", resume.toDouble())
            put("load_mode", draft.loadMode)
            put("keep_warm", draft.keepWarm)
            put("reasoning", draft.reasoning)
            put("auto_start", draft.autoStart)
        }
        val result = service.updateSettings(body.toString())
        if (result.contains("\"error\"")) {
            return try {
                JSONObject(result).optString("error", result)
            } catch (_: Throwable) {
                result
            }
        }
        keySet = settings.apiKey.isNotBlank()
        return null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Defaults are tuned for an 8 GB phone on a charger.",
            color = PhononColors.TextDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(22.dp))

        PhononCard {
            Text("Model", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                activeModel?.name ?: "No model installed",
                color = PhononColors.Text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${draft.contextSize.toIntOrNull()?.div(1024) ?: "—"}K context · ${draft.threads} threads · reasoning ${if (draft.reasoning) "on" else "off"}",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Text("Thermal policy", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pause at ${restartThreshold(draft.thermalStop.toFloatOrNull() ?: 0f)} °C, resume at ${restartThreshold(draft.thermalResume.toFloatOrNull() ?: 0f)} °C",
                color = PhononColors.Text,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Hysteresis prevents flapping. The model stays warm between requests while it is below the pause threshold.",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhononField(
                    value = draft.thermalStop,
                    onValueChange = { draft.thermalStop = it },
                    label = "Pause °C",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                )
                PhononField(
                    value = draft.thermalResume,
                    onValueChange = { draft.thermalResume = it },
                    label = "Resume °C",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep warm", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Send a one-token completion while idle so Android does not evict the model's pages.",
                        color = PhononColors.TextDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                PhononSwitch(draft.keepWarm) { draft.keepWarm = it }
            }
            Spacer(Modifier.height(12.dp))
            PhononField(
                value = draft.keepWarmInterval,
                onValueChange = { draft.keepWarmInterval = it },
                label = "Interval (seconds, min ${Settings.MIN_KEEP_WARM_INTERVAL_S})",
                keyboardType = KeyboardType.Number,
            )
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Text("Endpoint", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                endpointUrl(health, context),
                color = Color(0xFFD7E3DF),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (keySet) "API key set · off-loopback binding is allowed" else "No API key · phone-only binding",
                color = if (keySet) PhononColors.TextDim else PhononColors.Warn,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Phonon endpoint", endpointUrl(health, context)))
                    toast(context, "Endpoint copied")
                }) {
                    Text("Copy", color = PhononColors.Cyan, fontSize = 13.sp)
                }
                TextButton(onClick = {
                    val service = AppState.service
                    if (service == null) {
                        toast(context, "Service is starting")
                    } else {
                        val newKey = generateApiKey()
                        val result = service.updateSettings(JSONObject().put("api_key", newKey).toString())
                        if (result.contains("\"error\"")) {
                            toast(context, JSONObject(result).optString("error", "Could not rotate key"))
                        } else {
                            keySet = true
                            showKey = newKey
                        }
                    }
                }) {
                    Text("Rotate key", color = PhononColors.Cyan, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val activity = context.findActivity()
        if (activity != null) {
            KioskCard(activity)
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${draft.loadMode} · backend ${draft.backendPort} · keep-warm ${draft.keepWarmInterval}s",
                        color = PhononColors.TextDim,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Hide" else "Show", color = PhononColors.Cyan, fontSize = 13.sp)
                }
            }
            if (advanced) {
                Spacer(Modifier.height(12.dp))
                PhononField(
                    value = draft.modelPath,
                    onValueChange = { draft.modelPath = it },
                    label = "Model path",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhononField(
                        draft.contextSize,
                        { draft.contextSize = it },
                        "Context",
                        Modifier.weight(1f),
                        KeyboardType.Number,
                    )
                    PhononField(
                        draft.threads,
                        { draft.threads = it },
                        "Threads",
                        Modifier.weight(1f),
                        KeyboardType.Number,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhononField(
                        draft.bindAddress,
                        { draft.bindAddress = it },
                        "Bind address",
                        Modifier.weight(1f),
                    )
                    PhononField(
                        draft.port,
                        { draft.port = it },
                        "API port",
                        Modifier.weight(1f),
                        KeyboardType.Number,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhononField(
                        draft.backendPort,
                        { draft.backendPort = it },
                        "Backend port",
                        Modifier.weight(1f),
                        KeyboardType.Number,
                    )
                    PhononField(
                        draft.alias,
                        { draft.alias = it },
                        "Node name / alias",
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                PhononField(
                    draft.downloadUrl,
                    { draft.downloadUrl = it },
                    "Default download URL",
                    placeholder = "Optional — https://…/model.gguf",
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Optional. Empty leaves the download screen blank.",
                    color = PhononColors.TextDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text("Load mode", color = PhononColors.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.LOAD_MODES.forEach { mode ->
                        ChoiceChip(
                            text = mode,
                            selected = draft.loadMode == mode,
                            onClick = { draft.loadMode = mode },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Reasoning", draft.reasoning) { draft.reasoning = it }
                ToggleRow("Start on boot", draft.autoStart) { draft.autoStart = it }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = PhononColors.Border)
                TextButton(onClick = onShowLog) {
                    Text("View server log", color = PhononColors.Cyan, fontSize = 13.sp)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onShowLog) {
                    Text("View server log", color = PhononColors.Cyan, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        saveMessage?.let {
            Text(it, color = PhononColors.Danger, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val error = save()
                saveMessage = error
                if (error == null) toast(context, "Settings saved")
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PhononColors.Cyan,
                contentColor = Color(0xFF001318),
            ),
        ) {
            Text("Save changes", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Every value above maps to the same settings store the HTTP API reads. Saving a server argument restarts llama-server; thermal and keep-warm changes apply on the next tick.",
            color = PhononColors.TextDim.copy(alpha = 0.75f),
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(24.dp))
    }

    showKey?.let { key ->
        AlertDialog(
            onDismissRequest = { showKey = null },
            containerColor = PhononColors.Surface,
            title = { Text("New API key", color = PhononColors.Text) },
            text = {
                Column {
                    Text(
                        "Store it now. It is not shown again on this screen, and it is never written to the log.",
                        color = PhononColors.TextDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        key,
                        color = PhononColors.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Phonon API key", key))
                    toast(context, "Key copied")
                    showKey = null
                }) { Text("Copy", color = PhononColors.Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showKey = null }) { Text("Done", color = PhononColors.TextDim) }
            },
        )
    }
}

@Composable
private fun KioskCard(activity: Activity) {
    val context = LocalContext.current
    var owner by remember { mutableStateOf(Kiosk.isDeviceOwner(context)) }
    var locked by remember { mutableStateOf(Kiosk.isLockTaskActive(context)) }
    var exempt by remember { mutableStateOf(Kiosk.isBatteryExempt(context)) }
    var provisioningAllowed by remember { mutableStateOf(Kiosk.isProvisioningAllowed(context)) }
    var restrictions by remember { mutableStateOf(Kiosk.restrictionSummary(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            owner = Kiosk.isDeviceOwner(context)
            locked = Kiosk.isLockTaskActive(context)
            exempt = Kiosk.isBatteryExempt(context)
            provisioningAllowed = Kiosk.isProvisioningAllowed(context)
            restrictions = Kiosk.restrictionSummary(context)
            delay(1_500)
        }
    }

    PhononCard {
        Text("Kiosk / appliance mode", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Device Owner policy pins this app and keeps the screen on while serving. It does not touch the HTTP API.",
            color = PhononColors.TextDim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        KioskStatusRow("Device Owner", if (owner) "yes" else "no", owner)
        if (owner) {
            KioskStatusRow("Lock task", if (locked) "pinned" else "not pinned", locked)
            KioskStatusRow(
                "Battery optimisation",
                if (exempt) "exempt" else "not exempt",
                exempt,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                restrictions,
                color = PhononColors.TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!locked) {
                    Button(
                        onClick = {
                            message = Kiosk.enter(activity)
                            locked = Kiosk.isLockTaskActive(context)
                            exempt = Kiosk.isBatteryExempt(context)
                            restrictions = Kiosk.restrictionSummary(context)
                        },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PhononColors.Cyan,
                            contentColor = Color(0xFF001318),
                        ),
                    ) {
                        Text("Enter kiosk", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            message = Kiosk.leave(activity)
                            locked = Kiosk.isLockTaskActive(context)
                        },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PhononColors.SurfaceHigh,
                            contentColor = PhononColors.Text,
                        ),
                    ) {
                        Text("Leave kiosk", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                if (!exempt) {
                    TextButton(onClick = { Kiosk.requestBatteryExemption(activity) }) {
                        Text("Allow in background", color = PhononColors.Cyan, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter kiosk sets DISALLOW_SAFE_BOOT and DISALLOW_FACTORY_RESET, pins this activity and asks for the battery exemption. Leave kiosk unpins; the restrictions stay until the admin is removed.",
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            KioskStatusRow(
                "Can provision now",
                if (provisioningAllowed) "yes" else "no",
                provisioningAllowed,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Run this on the host with the phone connected by USB. The package is already installed; no root is needed.",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF060B10))
                    .border(1.dp, PhononColors.Border, RoundedCornerShape(14.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Kiosk.PROVISION_COMMAND,
                    color = PhononColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Phonon provisioning", Kiosk.PROVISION_COMMAND))
                    toast(context, "Command copied")
                }) {
                    Text("Copy", color = PhononColors.Cyan, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PhononColors.Warn.copy(alpha = 0.05f))
                    .border(1.dp, PhononColors.Warn.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "The phone must have no accounts and no secondary users. If \"Can provision now\" is no, remove all accounts and extra users, or factory-reset it and skip account sign-in before running the command.",
                    color = Color(0xFFF3D9A0),
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = PhononColors.Danger, fontSize = 12.sp)
        }
    }
}

@Composable
private fun KioskStatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PhononColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = if (ok) PhononColors.Ok else PhononColors.Warn,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PhononColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        PhononSwitch(checked, onChange)
    }
}

@Composable
private fun PhononSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF001318),
            checkedTrackColor = PhononColors.Cyan,
            uncheckedThumbColor = PhononColors.TextDim,
            uncheckedTrackColor = PhononColors.SurfaceHigh,
            uncheckedBorderColor = PhononColors.Border,
        ),
    )
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

@Composable
private fun SetupScreen(
    settings: Settings,
    hasModel: Boolean,
    onDownloadFirst: () -> Unit,
) {
    val context = LocalContext.current
    var lan by rememberSaveable { mutableStateOf(true) }
    var nodeName by rememberSaveable { mutableStateOf(settings.alias) }
    var revealKey by rememberSaveable { mutableStateOf(false) }
    val generatedKey = remember { generateApiKey() }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(14.dp))
            Text(
                "SET UP THIS PHONE",
                color = PhononColors.Cyan,
                fontSize = 11.sp,
                letterSpacing = 2.4.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Turn this phone into a model server.",
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Phonon serves a local model to your agents over Wi-Fi. Nothing leaves your network.",
                color = PhononColors.TextDim,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(22.dp))

            PhononCard {
                Text("How will it be reached?", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Choose where the endpoint listens. LAN is what agent hosts need.",
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceChip("Wi-Fi / LAN", lan, { lan = true }, Modifier.weight(1f))
                    ChoiceChip("This phone only", !lan, { lan = false }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))

            PhononCard {
                Text("Name this node", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                PhononField(
                    value = nodeName,
                    onValueChange = { nodeName = it },
                    label = "Node name",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Shown in the gateway and in logs.",
                    color = PhononColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (lan) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(PhononColors.Warn.copy(alpha = 0.05f))
                        .border(1.dp, PhononColors.Warn.copy(alpha = 0.32f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "Binding to the LAN requires an API key. Phonon generated one and shows it once — store it with your gateway.",
                        color = Color(0xFFF3D9A0),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF060B10))
                        .border(1.dp, PhononColors.Border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (revealKey) generatedKey else "sk-phonon-••••••••••••••••••••",
                        color = PhononColors.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { revealKey = !revealKey }) {
                            Text(if (revealKey) "Hide" else "Reveal", color = PhononColors.Cyan, fontSize = 13.sp)
                        }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("Phonon API key", generatedKey))
                            toast(context, "Key copied")
                        }) {
                            Text("Copy", color = PhononColors.Cyan, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Anyone with this key can use this phone's model.",
                    color = PhononColors.TextDim,
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    "Loopback only: no key required, and nothing is reachable from the LAN.",
                    color = PhononColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            PhononCard {
                Text(
                    if (hasModel) "Model ready" else "No model installed",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (hasModel) {
                        "The active GGUF is ready to serve."
                    } else {
                        "Download a GGUF before starting the server. The download resumes if it is interrupted."
                    },
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        Button(
            onClick = {
                val service = AppState.service
                if (service == null) {
                    toast(context, "Service is starting")
                } else if (!hasModel) {
                    toast(context, "Download a model first")
                } else {
                    val body = JSONObject().apply {
                        put("bind_address", if (lan) Settings.BIND_ANY else Settings.DEFAULT_BIND_ADDRESS)
                        put("alias", nodeName.trim().ifBlank { Settings.DEFAULT_ALIAS })
                        put("auto_start", true)
                        if (lan) put("api_key", generatedKey)
                    }
                    val result = service.updateSettings(body.toString())
                    if (result.contains("\"error\"")) {
                        toast(context, JSONObject(result).optString("error", "Could not save setup"))
                    } else {
                        sendServiceAction(context, PhononService.ACTION_START_SERVER)
                        toast(context, "Starting Phonon")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PhononColors.Cyan,
                contentColor = Color(0xFF001318),
            ),
        ) {
            Text("Start serving", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, PhononColors.Border, RoundedCornerShape(18.dp))
                .clickable(onClick = onDownloadFirst),
            contentAlignment = Alignment.Center,
        ) {
            Text("Download a model first", color = PhononColors.TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

@Composable
private fun PhononCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun PhononField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = placeholder?.let {
            { Text(it, color = PhononColors.TextDim.copy(alpha = 0.65f), fontSize = 13.sp) }
        },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp),
        textStyle = TextStyle(fontSize = 14.sp, color = PhononColors.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PhononColors.Cyan,
            unfocusedBorderColor = PhononColors.Border,
            focusedLabelColor = PhononColors.Cyan,
            unfocusedLabelColor = PhononColors.TextDim,
            cursorColor = PhononColors.Cyan,
            focusedContainerColor = Color(0xFF060B10),
            unfocusedContainerColor = Color(0xFF060B10),
        ),
    )
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) PhononColors.Cyan.copy(alpha = 0.08f) else PhononColors.Surface)
            .border(
                1.dp,
                if (selected) PhononColors.Cyan else PhononColors.Border,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) PhononColors.Text else PhononColors.TextDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        lines = AppState.service?.uiLogs()?.lines()?.takeLast(400) ?: listOf("Service is starting")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PhononColors.Surface,
        title = { Text("Server log", color = PhononColors.Text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(lines) { line ->
                    Text(
                        line,
                        color = if (line.contains("error", true) || line.contains("fail", true)) {
                            PhononColors.Danger
                        } else {
                            PhononColors.TextDim
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = PhononColors.Cyan) }
        },
    )
}

private fun restartThreshold(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

private fun ageLabel(epochMs: Long): String {
    val seconds = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 5L -> "just now"
        seconds < 60L -> "${seconds}s ago"
        seconds < 3_600L -> "${seconds / 60L}m ago"
        else -> "${seconds / 3_600L}h ago"
    }
}

private fun sendServiceAction(context: Context, action: String) {
    context.startForegroundService(Intent(context, PhononService::class.java).setAction(action))
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
