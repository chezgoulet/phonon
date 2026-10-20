package org.phononai.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.phononai.app.AppState
import org.phononai.app.BuildConfig
import org.phononai.app.ChargeLimit
import org.phononai.app.ChargeMode
import org.phononai.app.ChargeReading
import org.phononai.app.ChargeSettings
import org.phononai.app.DeviceReport
import org.phononai.app.GatewayAddress
import org.phononai.app.GatewayKeyStore
import org.phononai.app.GatewayResult
import org.phononai.app.LiteLlmRegistrar
import org.phononai.app.DeviceReportResult
import org.phononai.app.FieldReportResult
import org.phononai.app.HardwareProfile
import org.phononai.app.KeepWarmAdaptation
import org.phononai.app.Kiosk
import org.phononai.app.MemoryFit
import org.phononai.app.ModelCatalog
import org.phononai.app.ModelEntry
import org.phononai.app.ModelFamily
import org.phononai.app.OemGuidance
import org.phononai.app.R
import org.phononai.app.PerfProfile
import org.phononai.app.PhononService
import org.phononai.app.Settings
import org.phononai.app.SocTuning
import org.phononai.app.ui.avatar.AvatarView
import org.phononai.app.ui.theme.CoreType
import androidx.compose.ui.graphics.toArgb
import org.phononai.app.ui.avatar.MarkPalette
import org.phononai.app.ui.avatar.MarkState
import org.phononai.app.ui.avatar.MarkView
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
    LaunchedEffect(Unit) { AppState.presenceShape = settings.presenceShape }
    var health by remember { mutableStateOf<Health?>(null) }
    var benchmark by remember { mutableStateOf<BenchmarkState?>(null) }
    var download by remember { mutableStateOf<DownloadProgress?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var tpsHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var tabName by rememberSaveable { mutableStateOf(Tab.Serve.name) }
    var bypassSetup by rememberSaveable { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    // The poll reads /health, the benchmark state and the download state. It
    // used to run all three on the composition thread, where the first two
    // touch a sticky receiver and a BatteryManager IPC and the last lists a
    // directory — every second, for hours, on a phone that may be pinned. Read
    // on IO and hand the result back to the composition.
    LaunchedEffect(Unit) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                val service = AppState.service
                Triple(
                    try {
                        service?.let { parseHealth(it.uiHealthJson()) }
                    } catch (_: Throwable) {
                        null
                    },
                    try {
                        service?.let { parseBenchmark(it.uiBenchmarkJson()) }
                    } catch (_: Throwable) {
                        null
                    },
                    try {
                        service?.let { parseDownload(it.uiDownloadJson()) }
                    } catch (_: Throwable) {
                        null
                    },
                )
            }
            val nextHealth = next.first
            health = nextHealth
            benchmark = next.second
            download = next.third
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

    // Listing the models directory is IO too. Refresh it when the active model
    // changes or a download finishes, rather than on every poll tick.
    var models by remember { mutableStateOf<List<InstalledModel>>(emptyList()) }
    var modelsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(settings.modelPath, download?.dest, download?.done) {
        models = withContext(Dispatchers.IO) { installedModels(context, settings.modelPath) }
        modelsLoaded = true
    }
    val showSetup = modelsLoaded && models.isEmpty() && !bypassSetup

    CompositionLocalProvider(LocalContentColor provides PhononColors.Text) {
    Box(Modifier.fillMaxSize().background(PhononColors.Background)) {
        if (showSetup) {
            SetupScreen(
                settings = settings,
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
                        benchmark = benchmark,
                        onRunBenchmark = {
                            if (AppState.service?.uiStartBenchmark() != true) {
                                toast(context, "Benchmark needs a serving model")
                            }
                        },
                        onCancelBenchmark = { AppState.service?.uiCancelBenchmark() },
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
        // Brand identity, not a reading: four equal bands, no gaps, full-bleed.
        // The temperature itself is the labelled meter on the Thermal card.
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
            contentColor = PhononColors.OnCyan,
        )
        ActionTone.Amber -> ButtonDefaults.buttonColors(
            containerColor = PhononColors.SurfaceHigh,
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
                    Modifier.border(1.dp, PhononColors.Warn, RectangleShape)
                } else {
                    Modifier
                },
            ),
        shape = RectangleShape,
        colors = colors,
    ) {
        Text(action.label.uppercase(), style = CoreType.section.copy(fontSize = 16.sp, lineHeight = 19.sp))
    }
}

@Composable
private fun TabBar(selected: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RectangleShape)
            .background(PhononColors.Inset)
            .border(1.dp, PhononColors.Border, RectangleShape),
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
                    tab.label.uppercase(),
                    color = if (on) PhononColors.Cyan else PhononColors.TextDim,
                    style = CoreType.micro.copy(
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    ),
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
    benchmark: BenchmarkState?,
    onRunBenchmark: () -> Unit,
    onCancelBenchmark: () -> Unit,
) {
    val context = LocalContext.current
    val tierLine = remember { HardwareProfile.detect(context).summary }
    val accent = accentFor(kind)
    val stateWord = when (kind) {
        AvatarKind.Stopped -> "Stopped"
        AvatarKind.Loading -> "Starting"
        AvatarKind.Ready -> "Serving"
        AvatarKind.Generating -> "Generating"
        AvatarKind.Paused -> if (health?.pauseReason == "battery") "Paused — battery low" else "Paused — too warm"
        AvatarKind.Error -> "Error"
    }
    val modelName = modelLabel(
        health?.model?.takeIf { it.isNotBlank() }
            ?: File(settings.modelPath).name,
    )
    val contextK = "${settings.contextSize / 1024}K context"
    val stateMeta = when (kind) {
        AvatarKind.Stopped -> "server off · the key stays on this phone"
        AvatarKind.Loading -> "loading $modelName into memory"
        AvatarKind.Ready -> "$modelName · $contextK · top-app"
        AvatarKind.Generating -> "$modelName · ${health?.lastDecodeTps?.let { "%.1f tok/s decode".format(it) } ?: "decode in flight"}"
        AvatarKind.Paused -> if (health?.pauseReason == "battery") {
            "resumes at ${settings.batteryResumeAbove}% or on a charger"
        } else {
            "resumes at ${restartThreshold(settings.thermalResumeC)} °C"
        }
        AvatarKind.Error -> health?.error ?: "the model server did not start"
    }
    // The detected tier is part of the model line, not a hidden field.
    val meta = "$tierLine · $stateMeta"
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

    // Scrollable: the benchmark card adds a result block, and a fixed column
    // clipped the activity row behind the primary action on a 6.1" phone.
    // The avatar's cost control is a user-visible setting: default off means
    // the generating state drops to half the motes at 20 fps. Idle is always
    // the full animation, whatever this says.
    val animateGenerating = settings.animateAvatarWhileGenerating
    val reducedAvatar = kind == AvatarKind.Generating && !animateGenerating
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        BenchLockup()
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // AvatarView draws the particle body at 0.32 × the shorter side.
            // Measured on the Pixel 7: a 140 dp slot gave a 77 dp visible orb,
            // so 156 dp yields a ~84 dp visible cloud with the 1.55 × halo
            // still inside the bounds. The benchmark card below needs the
            // height; the orb stays legible at this size.
            Avatar(
                kind = kind,
                tps = tps,
                animate = settings.animateAvatarWhileGenerating,
                modifier = Modifier.size(156.dp),
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stateWord.uppercase(),
                    color = accent,
                    style = CoreType.headline,
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
                Spacer(Modifier.height(4.dp))
                // The performance mode is not a hidden setting: say what the
                // running child is pinned to, in the operator's words.
                Text(
                    text = health?.perfLine ?: "Detecting the CPU set…",
                    color = if (health?.perfVisible == true) {
                        PhononColors.Cyan.copy(alpha = 0.85f)
                    } else {
                        PhononColors.TextDim.copy(alpha = 0.85f)
                    },
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
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
            health = health,
        )
        Spacer(Modifier.height(14.dp))
        ChargeCard(health = health, context = context)
        Spacer(Modifier.height(14.dp))
        BenchmarkCard(
            state = benchmark,
            onRun = onRunBenchmark,
            onCancel = onCancelBenchmark,
        )
        Spacer(Modifier.height(12.dp))
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
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RectangleShape)
            .padding(16.dp),
    ) {
        Text(
            label.uppercase(),
            color = PhononColors.TextDim,
            style = CoreType.micro,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = valueColor,
                style = CoreType.metric,
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
                .background(accent.copy(alpha = 0.35f), RectangleShape),
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
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Butt),
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
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RectangleShape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "OPENAI-COMPATIBLE",
                color = PhononColors.TextDim,
                style = CoreType.micro,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                url,
                color = PhononColors.Text,
                fontFamily = CoreType.mono,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(RectangleShape)
                .border(1.dp, PhononColors.Border, RectangleShape)
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
        drawRect(
            color = color,
            topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
            size = Size(10.dp.toPx(), 10.dp.toPx()),
            style = stroke,
        )
        drawRect(
            color = color,
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(10.dp.toPx(), 10.dp.toPx()),
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
    health: Health?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RectangleShape)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "THERMALS",
                color = PhononColors.TextDim,
                style = CoreType.micro,
            )
            Text(
                formatTemp(temp),
                color = if (paused) PhononColors.Warn else PhononColors.Text,
                style = CoreType.metric.copy(fontSize = 20.sp, lineHeight = 22.sp),
            )
        }
        Spacer(Modifier.height(14.dp))
        // A meter, not a heat map: one fill colour, a labelled scale, and a
        // marker where the pause threshold sits. The number above carries the
        // reading; the bands at the top of the screen carry the identity.
        ThermalMeter(
            fraction = fraction,
            stopC = settings.thermalStopC,
            paused = paused,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (paused) {
                "Model unloaded while hot · reconnects on resume"
            } else {
                // Say which signal is driving the decision, from the device, not
                // a fixed threshold pair. Overridden thresholds are named as such.
                thermalPolicyLine(health, settings)
            },
            color = if (paused) PhononColors.Warn.copy(alpha = 0.9f) else PhononColors.TextDim,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * The charging-optimisation card.
 *
 * It reports only what the app could establish — read from the platform, or
 * inferred from a 20-minute plateau — and offers exactly the action it can back
 * up: a real switch when WRITE_SECURE_SETTINGS is held, the OEM screen when it
 * is not. There is never a switch that silently writes nothing.
 */
@Composable
private fun ChargeCard(health: Health?, context: Context) {
    val reading = ChargeReading(
        mode = ChargeMode.fromWire(health?.chargeMode),
        source = health?.chargeSource ?: "none",
        confirmed = health?.chargeConfirmed == true,
        detail = health?.chargeDetail.orEmpty(),
    )
    val guidance = remember { OemGuidance.chargingForDevice() }
    var error by remember { mutableStateOf<String?>(null) }
    // Optimistic only between the write and the next /health poll; the platform
    // is the source of truth, so a failed write falls back instead of lying.
    var pending by remember { mutableStateOf<Boolean?>(null) }
    val checked = pending ?: (reading.mode == ChargeMode.LIMIT_80)
    val accent = when (reading.mode) {
        ChargeMode.LIMIT_80, ChargeMode.ADAPTIVE -> PhononColors.Cyan
        ChargeMode.OFF -> PhononColors.Warn
        ChargeMode.UNKNOWN -> PhononColors.TextDim
    }
    val stateWord = when (reading.mode) {
        ChargeMode.LIMIT_80 -> if (reading.confirmed) "LIMIT 80%" else "LIMIT 80%?"
        ChargeMode.ADAPTIVE -> "ADAPTIVE"
        ChargeMode.OFF -> "NO LIMIT"
        ChargeMode.UNKNOWN -> "UNCONFIRMED"
    }
    PhononCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "CHARGING",
                color = PhononColors.TextDim,
                style = CoreType.micro,
            )
            Text(
                stateWord,
                color = accent,
                style = CoreType.micro,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            ChargeLimit.cardLine(reading, health?.chargeHoursAbove80 ?: 0),
            color = PhononColors.TextDim,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        if (reading.mode == ChargeMode.OFF) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Held at 100% on a charger, this cell ages faster than at 80%. " +
                    "The platform owns the charger; this card can only report it.",
                color = PhononColors.Warn.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (health?.chargeCanToggle == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Limit to 80%", color = PhononColors.Text, fontSize = 14.sp)
                    Text(
                        "Writes Settings.Secure.charge_optimization_mode.",
                        color = PhononColors.TextDim.copy(alpha = 0.85f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                    )
                }
                PhononSwitch(checked = checked) { on ->
                    val failure = ChargeSettings.writeLimit80(context, on)
                    error = failure
                    pending = if (failure == null) on else null
                }
            }
        } else {
            Text(
                "Without WRITE_SECURE_SETTINGS the app cannot write this setting, " +
                    "so there is no switch here. Grant it over adb for a real one.",
                color = PhononColors.TextDim.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { openChargingSettings(context, guidance) }) {
            Text("Open charging settings", color = PhononColors.Cyan, fontSize = 13.sp)
        }
        Text(
            guidance.steps,
            color = PhononColors.TextDim.copy(alpha = 0.8f),
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
        )
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = PhononColors.Danger, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

/**
 * Open the vendor's charging screen when one is known, then the platform's
 * battery settings, then the settings root. Each step can fail on a device that
 * changed its activity names, so each is tried in turn rather than assumed.
 */
private fun openChargingSettings(context: Context, guidance: OemGuidance.ChargingGuidance) {
    OemGuidance.componentOf(guidance.settingsComponent)?.let { (pkg, cls) ->
        try {
            context.startActivity(
                Intent().setComponent(ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (_: Throwable) {
            // Fall through to the platform screen.
        }
    }
    try {
        context.startActivity(
            Intent(AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return
    } catch (_: Throwable) {
        // Fall through to the settings root.
    }
    try {
        context.startActivity(
            Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
        toast(context, "Open Settings → Battery")
    }
}

/** One line describing a stored per-SoC profile from its JSON. */
private fun tuningSummary(json: String): String = try {
    val o = JSONObject(json)
    "%.1f tok/s decode at %dt, %.1f tok/s prefill at -tb %d".format(
        o.optDouble("decode_tps"),
        o.optInt("decode_threads"),
        o.optDouble("prefill_tps"),
        o.optInt("prefill_threads"),
    )
} catch (_: Throwable) {
    json
}

/**
 * Open the vendor's own battery/autostart screen when the OEM guidance names
 * one, and otherwise this app's settings page. A vendor component that is not
 * installed throws and falls back the same way, so the button always does
 * something useful.
 */
private fun openOemSettings(context: Context, guidance: OemGuidance.Guidance) {
    OemGuidance.componentOf(guidance.settingsComponent)?.let { (pkg, cls) ->
        try {
            context.startActivity(
                Intent().setComponent(ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (_: Throwable) {
            // Unknown or missing component: fall through to our own page.
        }
    }
    try {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Throwable) {
        toast(context, "Open Settings → Apps → Phonon")
    }
}

/** One line describing the thermal signal actually in use. */
private fun thermalPolicyLine(health: Health?, settings: Settings): String {
    val source = health?.thermalSource.orEmpty()
    val detail = health?.thermalDetail.orEmpty()
    return when {
        health?.thermalUserOverride == true ->
            "Your thresholds: pauses at ${restartThreshold(settings.thermalStopC)} °C, " +
                "resumes at ${restartThreshold(settings.thermalResumeC)} °C"
        source == "headroom" ->
            "Using the SoC thermal headroom${if (detail.isBlank()) "" else " ($detail)"} · " +
                "pauses at the thermal limit"
        source == "status" ->
            "Using Android's thermal status${if (detail.isBlank()) "" else " ($detail)"} · " +
                "pauses at MODERATE"
        source == "battery" ->
            "Battery temperature${if (detail.isBlank()) "" else " ($detail)"} · " +
                "pauses at ${restartThreshold(settings.thermalStopC)} °C"
        else -> "Pauses at ${restartThreshold(settings.thermalStopC)} °C · " +
            "resumes at ${restartThreshold(settings.thermalResumeC)} °C"
    }
}

@Composable
private fun BenchmarkCard(
    state: BenchmarkState?,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val last = state?.last
    val running = state?.running == true
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RectangleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BENCHMARK",
                color = PhononColors.TextDim,
                style = CoreType.micro,
            )
            Spacer(Modifier.weight(1f))
            if (running) {
                Text("RUNNING", color = PhononColors.Cyan, style = CoreType.microSmall)
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = if (running) onCancel else onRun) {
                Text(
                    if (running) "Cancel" else if (last == null) "Run benchmark" else "Run again",
                    color = if (running) PhononColors.Warn else PhononColors.Cyan,
                    fontSize = 13.sp,
                )
            }
        }
        when {
            running -> Text(
                "128 tokens, temperature 0, through this phone's own API. Serving continues.",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            last != null -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.1f".format(last.decodeTps),
                        color = PhononColors.Text,
                        style = CoreType.metric.copy(fontSize = 24.sp, lineHeight = 26.sp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("tok/s decode", color = PhononColors.TextDim, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        last.load,
                        color = if (last.load == "cold") PhononColors.Warn else PhononColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "prefill %.1f tok/s · TTFT %.1f s · %s".format(
                        last.prefillTps,
                        last.ttftMs / 1000.0,
                        formatTemp(last.batteryTempC),
                    ),
                    color = PhononColors.TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "128 tokens · peak %.2f GB RSS · %s".format(
                        last.peakRssMb / 1024.0,
                        last.timestamp.substringAfter('T').take(5),
                    ),
                    color = PhononColors.TextDim.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
            else -> Text(
                "Sends 128 tokens through this phone's own /v1 endpoint.",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
            )
        }
        state?.error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = PhononColors.Danger, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
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
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(accent.copy(alpha = 0.7f)))
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
private fun Avatar(kind: AvatarKind, tps: Float, animate: Boolean, modifier: Modifier) {
    // The mark is the identity: the avatar is the logo behaving, not a cloud.
    val palette = MarkPalette(
        ink = PhononColors.Text.toArgb(),
        dim = PhononColors.TextDim.toArgb(),
        rule = PhononColors.Border.toArgb(),
        primary = PhononColors.Cyan.toArgb(),
        accent = PhononColors.Violet.toArgb(),
        danger = PhononColors.Danger.toArgb(),
        ok = PhononColors.Ok.toArgb(),
        heat = PhononColors.Heat.map { it.toArgb() }.toIntArray(),
    )
    val state = kind.markState()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MarkView(context).apply {
                setPalette(palette)
                setHeld(!animate && state == MarkState.Generating)
                setState(state, tps)
            }
        },
        update = { view ->
            view.setPalette(palette)
            view.setHeld(!animate && state == MarkState.Generating)
            view.setState(state, tps)
        },
    )
}

private fun AvatarKind.markState(): MarkState = when (this) {
    AvatarKind.Stopped -> MarkState.Stopped
    AvatarKind.Loading -> MarkState.Loading
    AvatarKind.Ready -> MarkState.Ready
    AvatarKind.Generating -> MarkState.Generating
    AvatarKind.Paused -> MarkState.Paused
    AvatarKind.Error -> MarkState.Error
}

private class AvatarDriver {
    private var view: AvatarView? = null
    private var lastKind: AvatarKind? = null
    private var lastAmp: Float = -1f
    private var lastReduced: Boolean? = null

    fun attach(view: AvatarView) {
        this.view = view
    }

    fun apply(kind: AvatarKind, amp: Float, reduced: Boolean) {
        val v = view ?: return
        val ampChanged = abs(lastAmp - amp) >= 0.04f
        val reducedChanged = lastReduced != reduced
        if (kind == lastKind && !reducedChanged &&
            !(kind == AvatarKind.Generating && ampChanged)
        ) {
            return
        }
        lastKind = kind
        lastAmp = amp
        lastReduced = reduced
        // Set before the state: switching cost mode never changes the hue or
        // archetype, it only changes how many motes and how often they draw.
        v.setReducedMotion(reduced)
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

@Composable
private fun accentFor(kind: AvatarKind): Color = when (kind) {
    AvatarKind.Stopped -> PhononColors.DimCyan
    AvatarKind.Loading -> PhononColors.Violet
    AvatarKind.Ready -> PhononColors.Ok
    AvatarKind.Generating -> PhononColors.Violet
    AvatarKind.Paused -> PhononColors.Warn
    AvatarKind.Error -> PhononColors.Danger
}

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

/** A non-null `error` field; the JSON always has the key. */
private fun jsonHasError(json: String): Boolean = try {
    val obj = JSONObject(json)
    obj.has("error") && !obj.isNull("error")
} catch (t: Throwable) {
    true
}

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
    // Recomputed on the 1 s health poll, so a switch or a finished download
    // is reflected without leaving the screen.
    val activeEntry = remember(tick, settings.modelPath) {
        settings.catalogEntryForCurrentModel()
    }
    val activeName = remember(tick, settings.modelPath) {
        models.firstOrNull { it.active }?.name
    }
    val installed = remember(models) { models.map { it.file.name }.toSet() }
    val tier = remember { HardwareProfile.detect(context).tier.id }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("MODELS", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Every family, one server. All decode numbers were measured on this phone; unmeasured entries say so.",
            color = PhononColors.TextDim,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .background(PhononColors.Surface)
                .border(1.dp, PhononColors.Border, RectangleShape)
                .padding(16.dp),
        ) {
            Text("STORAGE USED", color = PhononColors.TextDim, style = CoreType.micro)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatBytes(storage),
                    color = PhononColors.Text,
                    style = CoreType.metric,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${models.size} model${if (models.size == 1) "" else "s"}",
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                activeEntry?.let { "Serving ${it.displayName} ${it.quant} as ${it.id}" }
                    ?: "No catalog model is active. The server serves whatever file is selected.",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(22.dp))

        ModelFamily.entries.forEach { family ->
            val recommended = ModelCatalog.forTier(tier, family)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    family.label.uppercase(),
                    color = PhononColors.Text,
                    style = CoreType.headline.copy(fontSize = 18.sp, lineHeight = 20.sp),
                )
                if (family == ModelFamily.QWEN) {
                    Spacer(Modifier.width(8.dp))
                    Chip("DEFAULT", PhononColors.Cyan)
                }
                Spacer(Modifier.weight(1f))
                recommended?.let {
                    Text(
                        "tier $tier pick: ${it.displayName}",
                        color = PhononColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                family.summary,
                color = PhononColors.TextDim,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModelCatalog.ofFamily(family).forEach { entry ->
                    CatalogEntryCard(
                        context = context,
                        entry = entry,
                        installed = entry.fileName in installed,
                        visionReady = entry.mmprojFileName.isNotBlank() &&
                            entry.mmprojFileName in installed,
                        active = activeName == entry.fileName,
                        download = download,
                        recommended = recommended?.id == entry.id,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        val extras = models.filter { ModelCatalog.byFileName(it.name) == null }
        if (extras.isNotEmpty()) {
            Text(
                "OTHER GGUF FILES ON THIS PHONE",
                color = PhononColors.Text,
                style = CoreType.headline.copy(fontSize = 18.sp, lineHeight = 20.sp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Copied in manually; not part of the catalog, so no measured numbers are claimed.",
                color = PhononColors.TextDim,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                extras.forEach { model ->
                    InstalledModelCard(context = context, model = model, onChanged = {})
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        PhononCard {
            Text("DOWNLOAD FROM A URL", style = CoreType.section)
            Spacer(Modifier.height(6.dp))
            Text(
                "For a file that is not in the catalog. The download resumes a partial file and verifies the declared size.",
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
                            if (jsonHasError(result)) {
                                toast(context, JSONObject(result).optString("error", "Download failed"))
                            } else {
                                toast(context, "Download started")
                            }
                        }
                    }
                },
                enabled = !active && downloadUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PhononColors.Cyan,
                    contentColor = PhononColors.OnCyan,
                    disabledContainerColor = PhononColors.SurfaceHigh,
                    disabledContentColor = PhononColors.TextDim,
                ),
            ) {
                Text((if (active) "Downloading…" else "Download").uppercase(), style = CoreType.section)
            }
            if (download != null && (download.active || download.error != null) &&
                download.dest.let { dest -> ModelCatalog.entries.none { it.fileName == File(dest).name } }
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        download.error != null -> "Failed: ${download.error}"
                        download.total > 0 -> "${formatBytes(download.bytes)} of ${formatBytes(download.total)}"
                        else -> "${formatBytes(download.bytes)} downloaded"
                    },
                    color = if (download.error != null) PhononColors.Danger else PhononColors.TextDim,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One catalog row. Size, context, decode and trade-off all come from
 * [ModelEntry]; the card only decides which action is possible right now.
 */
@Composable
private fun CatalogEntryCard(
    context: Context,
    entry: ModelEntry,
    installed: Boolean,
    visionReady: Boolean,
    active: Boolean,
    download: DownloadProgress?,
    recommended: Boolean,
) {
    val downloading = download?.active == true &&
        download.dest.let { File(it).name == entry.fileName }
    val downloadError = download?.error?.takeIf {
        download.dest.let { dest -> File(dest).name == entry.fileName }
    }

    val device = remember { HardwareProfile.detect(context) }
    var confirmDownload by remember { mutableStateOf(false) }
    fun tierRank(id: String): Int = when (id) {
        "S" -> 0
        "M" -> 1
        "L" -> 2
        else -> 0
    }
    fun startDownload() {
        val service = AppState.service
        if (service == null) {
            toast(context, "Service is starting")
            return
        }
        val result = service.uiDownloadModel(entry.id)
        if (jsonHasError(result)) {
            toast(context, JSONObject(result).optString("error", "Download failed"))
        } else {
            toast(context, "Downloading ${entry.fileName}")
        }
    }

    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Needs more RAM") },
            text = {
                Text(
                    "${entry.displayName} is sized for ${entry.tierLabel} phones. " +
                        "This device has ${device.ramGb} GB, so the server is likely to be " +
                        "killed while loading. Downloading is allowed, but expect it not to run.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = false
                    startDownload()
                }) { Text("Download anyway", color = PhononColors.Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = false }) { Text("Cancel") }
            },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(if (active) PhononColors.Cyan.copy(alpha = 0.05f) else PhononColors.Surface)
            .border(
                1.dp,
                if (active) PhononColors.Cyan.copy(alpha = 0.55f) else PhononColors.Border,
                RectangleShape,
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.displayName,
                        color = PhononColors.Text,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(entry.quant, color = PhononColors.TextDim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "alias ${entry.id}",
                    color = PhononColors.TextDim,
                    fontSize = 11.sp,
                    fontFamily = CoreType.mono,
                )
            }
            when {
                active -> Chip("ACTIVE", PhononColors.Cyan)
                recommended -> Chip("TIER ${entry.ramTier}", PhononColors.TextDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(formatBytes(entry.sizeBytes), color = PhononColors.TextDim, fontSize = 12.sp)
            Text("${entry.contextLabel} context", color = PhononColors.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entry.measured) entry.decodeLabel else "UNMEASURED",
                color = if (entry.measured) PhononColors.Text else PhononColors.Warn,
                style = CoreType.metric.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = if (entry.measured) FontWeight.Medium else FontWeight.Normal,
                ),
            )
            if (entry.measured) {
                Spacer(Modifier.width(6.dp))
                // Say whose numbers these are. A reference figure presented as
                // this phone's would be a lie the moment a different phone runs.
                val onThisDevice = entry.measuredOn.equals(device.model, ignoreCase = true)
                Text(
                    if (onThisDevice) {
                        "on this phone, ${ModelCatalog.MEASURED_DATE}"
                    } else {
                        "on ${entry.measuredOn} (reference), ${ModelCatalog.MEASURED_DATE}"
                    },
                    color = PhononColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.tradeOff,
            color = PhononColors.TextDim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        val profile = buildList {
            if (entry.arch.isNotBlank()) add(entry.arch)
            if (entry.license.isNotBlank()) add(entry.license)
            if (!entry.osiApproved) add("not OSI-approved")
            if (entry.jobFit.isNotBlank()) add(entry.jobFit)
        }.joinToString(" · ")
        if (profile.isNotBlank()) {
            Text(profile, color = PhononColors.TextDim, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        if (entry.mmprojFileName.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (visionReady) {
                    "vision ready — this server accepts images"
                } else {
                    "vision: the projector downloads with the model"
                },
                color = if (visionReady) PhononColors.Cyan else PhononColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        if (entry.vendorClaim.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${entry.vendorClaim} — vendor-reported, not measured here",
                color = PhononColors.Warn,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (downloading) {
            val fraction = if (download != null && download.total > 0) {
                (download.bytes.toFloat() / download.total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RectangleShape)
                    .background(PhononColors.Border),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(PhononColors.Cyan, RectangleShape),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (download != null && download.total > 0) {
                    "Downloading ${formatBytes(download.bytes)} of ${formatBytes(download.total)}"
                } else {
                    "Downloading…"
                },
                color = PhononColors.TextDim,
                fontSize = 12.sp,
            )
        } else if (downloadError != null) {
            Text("Failed: $downloadError", color = PhononColors.Danger, fontSize = 12.sp)
        }

        if (!active) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed) {
                    TextButton(
                        onClick = {
                            val service = AppState.service
                            if (service == null) {
                                toast(context, "Service is starting")
                            } else {
                                val error = service.uiSelectModel(entry.id)
                                if (error == null) {
                                    toast(context, "Serving ${entry.displayName} as ${entry.id}")
                                } else {
                                    toast(context, error)
                                }
                            }
                        },
                        enabled = !downloading,
                    ) {
                        Text("Use", color = PhononColors.Cyan, fontSize = 13.sp)
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (tierRank(entry.ramTier) > tierRank(device.tier.id)) {
                                confirmDownload = true
                            } else {
                                startDownload()
                            }
                        },
                        enabled = !downloading && download?.active != true,
                    ) {
                        Text("Download", color = PhononColors.Cyan, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "recommended for ${entry.tierLabel}",
                    color = PhononColors.TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                "The active model. Switching family updates the alias and restarts the server.",
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun InstalledModelCard(context: Context, model: InstalledModel, onChanged: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(
                1.dp,
                if (model.active) PhononColors.Cyan.copy(alpha = 0.45f) else PhononColors.Border,
                RectangleShape,
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
                            if (jsonHasError(result)) {
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
            .clip(RectangleShape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.40f), RectangleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = color, style = CoreType.microSmall)
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

private class SettingsDraft(context: Context) {
    private val s = Settings(context)
    private val plan = s.perfPlan()
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
    var rateLimitPerMin by mutableStateOf(s.rateLimitPerMin.toString())
    var perfThreads by mutableStateOf(plan.threads.toString())
    var perfCpuRange by mutableStateOf(plan.cpuRange)
    var perfPrio by mutableStateOf(plan.prio.toString())
    var perfPrioBatch by mutableStateOf(plan.prioBatch.toString())
    var animateGenerating by mutableStateOf(s.animateAvatarWhileGenerating)

    /**
     * What the fields read when this screen opened. A field still equal to its
     * initial value has not been edited here, so Save must not post it: the
     * service may have changed it in the meantime (a download selecting a
     * model, kiosk changing the auto thread count) and posting the stale copy
     * would silently undo that.
     */
    val bornModelPath = s.modelPath
    val bornContext = s.contextSize.toString()
    val bornAlias = s.alias
    val bornThreads = s.threads.toString()

    /** Re-read the auto values after an override is cleared or applied. */
    fun syncPerf() {
        val next = s.perfPlan()
        threads = next.threads.toString()
        perfThreads = next.threads.toString()
        perfCpuRange = next.cpuRange
        perfPrio = next.prio.toString()
        perfPrioBatch = next.prioBatch.toString()
        animateGenerating = s.animateAvatarWhileGenerating
    }
}

@Composable
private fun PerformanceCard(
    context: Context,
    settings: Settings,
    draft: SettingsDraft,
    health: Health?,
    onMessage: (String?) -> Unit,
) {
    val plan = settings.perfPlan()
    // What the operator sees first is the mode the running child is in. If the
    // app has since moved to another cpuset, say so instead of hiding it.
    val runningVisible = health?.perfVisible ?: plan.visible
    val modeLabel = if (runningVisible) "Visible / kiosk" else "Background"
    // Every core count and frequency below comes from this device's own cpuset
    // and cpufreq nodes. No phone model is named and no figure is hardcoded.
    val detection = remember { PerfProfile.detect() }
    val clusterText = detection.clusterLabel.ifBlank { "no cpufreq nodes" }
    val clusterWord = "${detection.clusters.size} cluster" +
        if (detection.clusters.size == 1) "" else "s"
    val modeDetail = if (runningVisible) {
        "This process may run on all ${detection.allowed.size} cores across $clusterWord: " +
            "$clusterText."
    } else {
        "Background cpuset: ${detection.allowed.size} cores, and the fastest cluster is not in " +
            "the allowed set. Detected: $clusterText."
    }
    val kioskOn = Kiosk.isDeviceOwner(context) || Kiosk.isLockTaskActive(context)
    val running = health?.state == "ready" || health?.state == "loading" ||
        health?.state == "restarting"

    PhononCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PERFORMANCE", style = CoreType.section)
                Spacer(Modifier.height(4.dp))
                Text(modeLabel, color = PhononColors.Cyan, fontSize = 14.sp)
            }
            Text(
                if (plan.auto) "auto-tuned" else "manual",
                color = if (plan.auto) PhononColors.TextDim else PhononColors.Warn,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            modeDetail,
            color = PhononColors.Text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (kioskOn) {
                "Kiosk is on: pinning this app is what unlocks the fastest cores."
            } else if (!runningVisible) {
                "Kiosk — or simply keeping this screen visible — is what unlocks the fastest cores."
            } else {
                "This screen being visible is what unlocks the fastest cores; kiosk keeps it that way."
            },
            color = PhononColors.TextDim,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${plan.modeLabel} · ${plan.threads} threads · CPUs ${plan.cpuRangeLabel} · prio ${plan.prio}/${plan.prioBatch}",
            color = PhononColors.Text,
            fontFamily = CoreType.mono,
            fontSize = 12.sp,
        )
        if (running && health != null &&
            (health.perfThreads != plan.threads ||
                health.perfCpuRange != plan.cpuRange ||
                health.perfMode != plan.mode)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Running child: ${health.perfThreads} threads on ${health.perfCpuRange.ifBlank { "all" }} (${health.perfMode}). " +
                    "Apply or restart to move it.",
                color = PhononColors.Warn,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhononField(
                value = draft.perfThreads,
                onValueChange = { draft.perfThreads = it },
                label = "Threads",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
            PhononField(
                value = draft.perfCpuRange,
                onValueChange = { draft.perfCpuRange = it },
                label = "CPU range",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhononField(
                value = draft.perfPrio,
                onValueChange = { draft.perfPrio = it },
                label = "Prio (0–3)",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
            PhononField(
                value = draft.perfPrioBatch,
                onValueChange = { draft.perfPrioBatch = it },
                label = "Prio batch (0–3)",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val service = AppState.service ?: run {
                    onMessage("Service is starting")
                    return@TextButton
                }
                val threads = draft.perfThreads.toIntOrNull()
                val prio = draft.perfPrio.toIntOrNull()
                val prioBatch = draft.perfPrioBatch.toIntOrNull()
                if (threads == null || prio == null || prioBatch == null) {
                    onMessage("Threads and priorities must be numbers")
                    return@TextButton
                }
                val error = service.uiApplyPerf(
                    threads = threads,
                    cpuRange = draft.perfCpuRange,
                    prio = prio,
                    prioBatch = prioBatch,
                )
                if (error == null) draft.syncPerf()
                onMessage(error)
            }) {
                Text("Apply", color = PhononColors.Cyan, fontSize = 13.sp)
            }
            TextButton(onClick = {
                val service = AppState.service ?: run {
                    onMessage("Service is starting")
                    return@TextButton
                }
                val error = service.uiApplyPerf(reset = true)
                if (error == null) draft.syncPerf()
                onMessage(error)
            }) {
                Text("Reset to auto", color = PhononColors.TextDim, fontSize = 13.sp)
            }
        }
        Text(
            "An override pins that field and stops auto-tuning it. Threads also changes the Advanced field; " +
                "CPU range and priority are applied by restarting the server.",
            color = PhononColors.TextDim.copy(alpha = 0.8f),
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = PhononColors.Border)
        ToggleRow(
            "Animate avatar while generating",
            draft.animateGenerating,
        ) { on ->
            draft.animateGenerating = on
            AppState.service?.uiSetAnimateAvatar(on)
        }
        Text(
            "Off by default. Full animation costs about 2.4% of decode while a request runs; " +
                "the reduced mode costs about 1.5%. Idle stays full either way.",
            color = PhononColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(12.dp))
        Text("Shape", color = PhononColors.Text, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shapes = listOf("Aura" to "aura", "Iris" to "iris", "Vortex" to "vortex",
                "Waveform" to "waveform", "Bloom" to "bloom", "Sphere" to "sphere")
            shapes.forEach { (label, token) ->
                val selected = AppState.presenceShape == token
                Text(
                    label,
                    color = if (selected) PhononColors.Cyan else PhononColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RectangleShape)
                        .border(1.dp, if (selected) PhononColors.Cyan else PhononColors.Border,
                            RectangleShape)
                        .clickable {
                            AppState.presenceShape = token
                            settings.presenceShape = token
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            "The shape the being takes while idle.",
            color = PhononColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
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
    val device = remember { HardwareProfile.detect(context) }
    val scope = rememberCoroutineScope()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var keySet by remember { mutableStateOf(settings.apiKey.isNotBlank()) }
    var showKey by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var perfMessage by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<DeviceReportResult?>(null) }
    var reportPreparing by remember { mutableStateOf(false) }
    var fieldReport by remember { mutableStateOf<FieldReportResult?>(null) }
    var fieldPreparing by remember { mutableStateOf(false) }
    val activeModel = models.firstOrNull { it.active }

    fun save(): String? {
        val service = AppState.service ?: return "Service is starting"
        // Untouched fields come from live settings, not from the snapshot.
        val contextText = if (draft.contextSize == draft.bornContext) {
            settings.contextSize.toString()
        } else {
            draft.contextSize
        }
        val threadsText = if (draft.threads == draft.bornThreads) {
            settings.threads.toString()
        } else {
            draft.threads
        }
        val ctxSize = contextText.toIntOrNull() ?: return "Context must be a number"
        val threadCount = threadsText.toIntOrNull() ?: return "Threads must be a number"
        val publicPort = draft.port.toIntOrNull() ?: return "API port must be a number"
        val internalPort = draft.backendPort.toIntOrNull() ?: return "Backend port must be a number"
        val stop = draft.thermalStop.toFloatOrNull() ?: return "Pause threshold must be a number"
        val resume = draft.thermalResume.toFloatOrNull() ?: return "Resume threshold must be a number"
        val warmInterval = draft.keepWarmInterval.toIntOrNull() ?: return "Keep-warm interval must be a number"
        val rateLimit = draft.rateLimitPerMin.toIntOrNull() ?: return "Rate limit must be a number"
        val body = JSONObject().apply {
            put(
                "model_path",
                if (draft.modelPath == draft.bornModelPath) settings.modelPath else draft.modelPath.trim(),
            )
            put("context", ctxSize)
            put("threads", threadCount)
            put("bind_address", draft.bindAddress.trim())
            put("port", publicPort)
            put("backend_port", internalPort)
            put(
                "alias",
                if (draft.alias == draft.bornAlias) settings.alias else draft.alias.trim(),
            )
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
            put("rate_limit_per_min", rateLimit)
        }
        val result = service.updateSettings(body.toString())
        if (jsonHasError(result)) {
            return try {
                JSONObject(result).optString("error", result)
            } catch (_: Throwable) {
                result
            }
        }
        keySet = settings.apiKey.isNotBlank()
        // This is the Settings save path: from now on auto-config must not
        // replace the operator's model, context or thread count.
        settings.tunedByUser = true
        return null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("SETTINGS", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Defaults follow the detected RAM tier. Tune them here only when you mean to.",
            color = PhononColors.TextDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(22.dp))

        PhononCard {
            Text("DEVICE", style = CoreType.section)
            Spacer(Modifier.height(6.dp))
            Text(
                device.summary,
                color = PhononColors.Text,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${device.soc} · Android ${device.androidRelease} · ${device.abis.joinToString(", ")}",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tier defaults: ${device.tier.modelLabel}, ${device.tier.contextSize / 1024}K context; the performance profile chooses threads from the cpuset.",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            // CPU capabilities and the memory-fit verdict, both device-derived.
            Text(
                "CPU: ${health?.cpuFeatures?.takeIf { it.isNotBlank() } ?: "detecting…"}" +
                    " · dotprod ${if (health?.cpuDotprod == true) "yes" else "no"}" +
                    " · i8mm ${if (health?.cpuI8mm == true) "yes" else "no"}" +
                    " · build requires: " +
                    (health?.nativeRequiredFeatures?.ifBlank { "nothing (all-variant build)" }
                        ?: "…"),
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            // Engine provenance in monospace so a glance at the phone can
            // triage a report. Both values are build-time constants.
            Text(
                "engine ${BuildConfig.ENGINE_REV} · template ${BuildConfig.TEMPLATE_MODE} " +
                    "(${BuildConfig.TEMPLATE_SOURCE})",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = CoreType.mono,
            )
            health?.let { h ->
                Spacer(Modifier.height(4.dp))
                Text(
                    if (h.memFits) {
                        "Memory: fits — needs ~${MemoryFit.gb(h.memRequiredBytes)} GB, " +
                            "${h.memAvailGb} GB available now"
                    } else {
                        "Memory: will not fit — needs ~${MemoryFit.gb(h.memRequiredBytes)} GB, " +
                            "only ${h.memAvailGb} GB available now. Use a smaller model or context."
                    },
                    color = if (h.memFits) PhononColors.TextDim else PhononColors.Danger,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            health?.let { h ->
                if (h.lastExitLowMemory) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Last server exit looked like a low-memory kill: this model is too big " +
                            "for this phone.",
                        color = PhononColors.Danger,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                enabled = !reportPreparing,
                onClick = {
                    if (reportPreparing) return@TextButton
                    reportPreparing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                DeviceReport.build(
                                    context,
                                    settings,
                                    AppState.service?.uiHealthJson(),
                                )
                            } catch (t: Throwable) {
                                null
                            }
                        }
                        reportPreparing = false
                        if (result == null) {
                            toast(context, "Could not build the report")
                        } else {
                            report = result
                        }
                    }
                },
            ) {
                Text(
                    if (reportPreparing) "Preparing report…" else "Export device report",
                    color = if (reportPreparing) PhononColors.TextDim else PhononColors.Cyan,
                    fontSize = 13.sp,
                )
            }
            Text(
                "Previewed before sharing. The API key is never included.",
                color = PhononColors.TextDim.copy(alpha = 0.8f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                enabled = !fieldPreparing,
                onClick = {
                    if (fieldPreparing) return@TextButton
                    val service = AppState.service
                    if (service == null) {
                        toast(context, "Service is starting")
                        return@TextButton
                    }
                    fieldPreparing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                service.uiFieldReportYaml()
                            } catch (t: Throwable) {
                                null
                            }
                        }
                        fieldPreparing = false
                        if (result == null) {
                            toast(context, "Could not build the profile")
                        } else {
                            fieldReport = result
                        }
                    }
                },
            ) {
                Text(
                    if (fieldPreparing) "Preparing profile…" else "Contribute this phone's profile",
                    color = if (fieldPreparing) PhononColors.TextDim else PhononColors.Cyan,
                    fontSize = 13.sp,
                )
            }
            Text(
                "Writes a devices/<soc>.yaml snippet from live detection plus the tuning " +
                    "profile if one exists. Saved to the app's files so it can be pulled.",
                color = PhononColors.TextDim.copy(alpha = 0.8f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // The optional OpenCL prefill backend, if this build carries it.
            Spacer(Modifier.height(4.dp))
            Text(
                health?.gpuPrefillStatus?.takeIf { it.isNotBlank() }
                    ?: org.phononai.app.GpuPrefill.NOT_INCLUDED,
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        PerformanceCard(
            context = context,
            settings = settings,
            draft = draft,
            health = health,
            onMessage = { perfMessage = it },
        )
        perfMessage?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                color = if (it.contains("must") || it.contains("outside")) {
                    PhononColors.Danger
                } else {
                    PhononColors.TextDim
                },
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        // Per-SoC tuning. Manual by design: the sweep restarts the server and
        // takes a couple of minutes, so it never runs on its own.
        PhononCard {
            Text("TUNE THIS PHONE", style = CoreType.section)
            Spacer(Modifier.height(6.dp))
            val profile = health?.socTuningProfile.orEmpty()
            val hasProfile = profile.isNotBlank() && profile != "none"
            Text(
                if (hasProfile) {
                    "tuned on this phone: ${tuningSummary(profile)}"
                } else {
                    "No measured profile for ${device.soc}. Using the heuristic default: " +
                        "${health?.perfThreads ?: 0} threads on " +
                        "${health?.perfCpuRange?.takeIf { it.isNotBlank() } ?: "all allowed"}, " +
                        "-tb ${health?.perfPrefillThreads ?: 0}."
                },
                color = PhononColors.Text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            health?.socTuningStaleRev?.let { stale ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "A profile measured against engine $stale is not applied — this build is " +
                        "${SocTuning.ENGINE_REV}. Run the sweep again to re-measure it.",
                    color = PhononColors.Warn,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            if (health?.socTuningRunning == true) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sweep running: ${health.socTuningState}",
                    color = PhononColors.Cyan,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (health?.socTuningRunning == true) {
                    TextButton(
                        onClick = { AppState.service?.uiCancelSocTuning() },
                    ) {
                        Text("Cancel", color = PhononColors.Danger, fontSize = 13.sp)
                    }
                } else {
                    TextButton(
                        enabled = health?.state == "ready",
                        onClick = {
                            val service = AppState.service
                            if (service == null) {
                                toast(context, "Service is starting")
                            } else if (!service.uiTuneThisPhone()) {
                                toast(context, "Server must be ready and idle to tune")
                            } else {
                                toast(context, "Tuning started — about two minutes")
                            }
                        },
                    ) {
                        Text("Run sweep (~2 min)", color = PhononColors.Cyan, fontSize = 13.sp)
                    }
                }
                TextButton(
                    onClick = {
                        AppState.service?.uiClearSocTuning()
                        toast(context, "Profile cleared; back to auto-tuning")
                    },
                ) {
                    Text("Clear profile", color = PhononColors.TextDim, fontSize = 13.sp)
                }
            }
            Text(
                "Tries two decode configs pinned to the fastest cluster and one unpinned, then " +
                    "two prefill (-tb) values on a long prompt; keeps the fastest of each and " +
                    "stores it per SoC. It never changes the model.",
                color = PhononColors.TextDim.copy(alpha = 0.8f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        // OEM background-restriction guidance. The app cannot change these
        // settings itself; it can only point at the vendor's own screen.
        val oem = remember { OemGuidance.forDevice() }
        PhononCard {
            Text("BACKGROUND RESTRICTIONS", style = CoreType.section)
            Spacer(Modifier.height(6.dp))
            Text(
                "${oem.vendor}: ${oem.steps}",
                color = PhononColors.Text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // When the vendor exposes a battery/autostart screen we deep-link
                // into it; otherwise (and if it is not installed) we fall back to
                // this app's own settings page, which always exists.
                TextButton(onClick = { openOemSettings(context, oem) }) {
                    Text("Open battery settings", color = PhononColors.Cyan, fontSize = 13.sp)
                }
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(oem.dontKillUrl)),
                        )
                    } catch (_: Throwable) {
                        toast(context, oem.dontKillUrl)
                    }
                }) {
                    Text("dontkillmyapp.com", color = PhononColors.Cyan, fontSize = 13.sp)
                }
            }
            if (health?.batteryFullWarning == true) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This phone has been held at 100% for ${health.batteryFullHours} " +
                        "hour${if (health.batteryFullHours == 1) "" else "s"}. Android usually " +
                        "holds charge around 80% for longevity; if it is not doing so, unplug " +
                        "when full.",
                    color = PhononColors.Warn,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                TextButton(onClick = { AppState.service?.uiDismissChargeWarning() }) {
                    Text("Dismiss", color = PhononColors.TextDim, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Text("MODEL", style = CoreType.section)
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
                "${draft.contextSize.toIntOrNull()?.div(1024) ?: "—"}K context (${health?.contextFitLabel?.ifBlank { "unknown" } ?: "unknown"}) · ${draft.threads} threads · reasoning ${if (draft.reasoning) "on" else "off"}",
                color = PhononColors.TextDim,
                fontSize = 12.sp,
            )
            if (health?.contextAutoSized == true) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Context sized to this phone's available memory — change it under Advanced " +
                        "and Save to take it over.",
                    color = PhononColors.TextDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Text("THERMAL POLICY", style = CoreType.section)
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
                    Text("KEEP WARM", style = CoreType.section)
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
            // The interval above is the operator's starting point; the beat
            // adapts it from measured time-to-first-token and reports back here.
            Spacer(Modifier.height(6.dp))
            Text(
                KeepWarmAdaptation.describe(
                    intervalS = health?.keepWarmIntervalS ?: KeepWarmAdaptation.DEFAULT_S,
                    samples = health?.keepWarmSamples ?: 0,
                    adapted = health?.keepWarmAdapted == true,
                ) + (health?.keepWarmLastTtftMs?.let { " · last beat TTFT ${it} ms" } ?: ""),
                color = PhononColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Text("ENDPOINT", style = CoreType.section)
            Spacer(Modifier.height(6.dp))
            Text(
                endpointUrl(health, context),
                color = PhononColors.Text,
                fontFamily = CoreType.mono,
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
                        if (jsonHasError(result)) {
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

        GatewayCard(context = context, settings = settings, health = health)
        Spacer(Modifier.height(12.dp))

        val activity = context.findActivity()
        if (activity != null) {
            KioskCard(activity)
        }
        Spacer(Modifier.height(12.dp))

        PhononCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ADVANCED", style = CoreType.section)
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
                Spacer(Modifier.height(10.dp))
                PhononField(
                    value = draft.rateLimitPerMin,
                    onValueChange = { draft.rateLimitPerMin = it },
                    label = "Rate limit (requests/min per client)",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Shared by the control routes and /v1, per source address. 0 disables the limiter.",
                    color = PhononColors.TextDim,
                    fontSize = 11.sp,
                )
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
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = PhononColors.Cyan,
                contentColor = PhononColors.OnCyan,
            ),
        ) {
            Text("SAVE CHANGES", style = CoreType.section)
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

    report?.let { result ->
        AlertDialog(
            onDismissRequest = { report = null },
            containerColor = PhononColors.Surface,
            title = { Text("Device report", color = PhononColors.Text) },
            text = {
                Column {
                    Text(
                        "This is exactly what leaves the phone. The API key is not in it.",
                        color = PhononColors.TextDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            result.text,
                            color = PhononColors.Text,
                            fontFamily = CoreType.mono,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saved to ${result.textPath}",
                        color = PhononColors.TextDim.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Phonon device report")
                        putExtra(Intent.EXTRA_TEXT, result.text)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, "Share device report"))
                    } catch (_: Throwable) {
                        // The report was already written next to the app's
                        // external files; a phone with no share target still
                        // has a file to pull.
                        toast(context, "No share target. Report saved to ${result.textPath}")
                    }
                    report = null
                }) { Text("Share", color = PhononColors.Cyan) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Phonon device report", result.text))
                        toast(context, "Report copied")
                        report = null
                    }) { Text("Copy", color = PhononColors.Cyan) }
                    TextButton(onClick = { report = null }) {
                        Text("Close", color = PhononColors.TextDim)
                    }
                }
            },
        )
    }

    fieldReport?.let { result ->
        AlertDialog(
            onDismissRequest = { fieldReport = null },
            containerColor = PhononColors.Surface,
            title = { Text("Device profile", color = PhononColors.Text) },
            text = {
                Column {
                    Text(
                        "The devices/<soc>.yaml snippet for this phone. Copy it into the repo " +
                            "as ${result.fileName}.",
                        color = PhononColors.TextDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            result.yaml,
                            color = PhononColors.Text,
                            fontFamily = CoreType.mono,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saved to ${result.path}",
                        color = PhononColors.TextDim.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, result.fileName)
                        putExtra(Intent.EXTRA_TEXT, result.yaml)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, "Share device profile"))
                    } catch (_: Throwable) {
                        toast(context, "No share target. Profile saved to ${result.path}")
                    }
                    fieldReport = null
                }) { Text("Share", color = PhononColors.Cyan) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText(result.fileName, result.yaml))
                        toast(context, "Profile copied")
                        fieldReport = null
                    }) { Text("Copy", color = PhononColors.Cyan) }
                    TextButton(onClick = { fieldReport = null }) {
                        Text("Close", color = PhononColors.TextDim)
                    }
                }
            },
        )
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
                        fontFamily = CoreType.mono,
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
        Text("KIOSK / APPLIANCE MODE", style = CoreType.section)
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
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PhononColors.Cyan,
                            contentColor = PhononColors.OnCyan,
                        ),
                    ) {
                        Text("ENTER KIOSK", style = CoreType.section)
                    }
                } else {
                    Button(
                        onClick = {
                            message = Kiosk.leave(activity)
                            locked = Kiosk.isLockTaskActive(context)
                        },
                        modifier = Modifier.height(48.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PhononColors.SurfaceHigh,
                            contentColor = PhononColors.Text,
                        ),
                    ) {
                        Text("LEAVE KIOSK", style = CoreType.section)
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
                    .clip(RectangleShape)
                    .background(PhononColors.Inset)
                    .border(1.dp, PhononColors.Border, RectangleShape)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Kiosk.PROVISION_COMMAND,
                    color = PhononColors.Text,
                    fontFamily = CoreType.mono,
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
                    .clip(RectangleShape)
                    .background(PhononColors.Warn.copy(alpha = 0.05f))
                    .border(1.dp, PhononColors.Warn.copy(alpha = 0.32f), RectangleShape)
                    .padding(12.dp),
            ) {
                Text(
                    "The phone must have no accounts and no secondary users. If \"Can provision now\" is no, remove all accounts and extra users, or factory-reset it and skip account sign-in before running the command.",
                    color = PhononColors.Warn,
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

/** Square switch: a track with a knob that moves end to end. No radii. */
@Composable
private fun PhononSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track = if (checked) PhononColors.Cyan else PhononColors.SurfaceHigh
    val knob = if (checked) PhononColors.OnCyan else PhononColors.TextDim
    Box(
        Modifier
            .size(width = 54.dp, height = 28.dp)
            .background(track)
            .border(1.dp, if (checked) PhononColors.Cyan else PhononColors.Border, RectangleShape)
            .clickable { onChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(knob))
    }
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

@Composable
private fun SetupScreen(
    settings: Settings,
    onDownloadFirst: () -> Unit,
) {
    val context = LocalContext.current
    var lan by rememberSaveable { mutableStateOf(true) }
    var nodeName by rememberSaveable { mutableStateOf(settings.alias) }
    var aliasEdited by rememberSaveable { mutableStateOf(false) }
    var revealKey by rememberSaveable { mutableStateOf(false) }
    val generatedKey = remember { generateApiKey() }
    val tier = remember { HardwareProfile.detect(context).tier.id }
    // Qwen is pre-selected because it is the faster default on this hardware.
    var familyKey by rememberSaveable { mutableStateOf(ModelFamily.QWEN.key) }
    val family = ModelFamily.entries.firstOrNull { it.key == familyKey } ?: ModelFamily.QWEN
    val chosenModel = remember(familyKey, tier) { ModelCatalog.forTier(tier, family) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(14.dp))
            BenchLockup()
            Spacer(Modifier.height(18.dp))
            Text(
                "SET UP THIS PHONE",
                color = PhononColors.Cyan,
                style = CoreType.micro,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "TURN THIS PHONE\nINTO A MODEL\nSERVER",
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
                Text("HOW WILL IT BE REACHED?", style = CoreType.section)
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
                Text("NAME THIS NODE", style = CoreType.section)
                Spacer(Modifier.height(10.dp))
                PhononField(
                    value = nodeName,
                    onValueChange = { nodeName = it; aliasEdited = true },
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

            PhononCard {
                Text("CHOOSE A MODEL", style = CoreType.section)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Model families differ in speed and capability by device. The Models screen " +
                        "shows a measured figure only for the device it was measured on, and " +
                        "says \"unmeasured\" otherwise. Both Qwen and Gemma ship with a vision " +
                        "projector, so the server accepts images once the model has downloaded.",
                    color = PhononColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModelFamily.entries.forEach { option ->
                        ChoiceChip(
                            option.label,
                            option == family,
                            { familyKey = option.key },
                            Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                chosenModel?.let { entry ->
                    Text(
                        "${entry.displayName} ${entry.quant} · ${entry.contextLabel} context · " +
                            (if (entry.measured) entry.decodeLabel else "unmeasured"),
                        color = PhononColors.Text,
                        fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Recommended for ${entry.tierLabel} RAM (tier $tier). " + entry.tradeOff,
                        color = PhononColors.TextDim,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    val installed = ModelCatalog.path(context, entry).isFile
                    if (!installed) {
                        TextButton(
                            onClick = {
                                val service = AppState.service
                                if (service == null) {
                                    toast(context, "Service is starting")
                                } else {
                                    val result = service.uiDownloadModel(entry.id)
                                    if (jsonHasError(result)) {
                                        toast(context, JSONObject(result).optString("error", "Download failed"))
                                    } else {
                                        toast(context, "Downloading ${entry.fileName}")
                                    }
                                }
                            },
                        ) {
                            Text("Download ${entry.displayName}", color = PhononColors.Cyan, fontSize = 13.sp)
                        }
                    } else {
                        Text("Installed on this phone.", color = PhononColors.Cyan, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (lan) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RectangleShape)
                        .background(PhononColors.Warn.copy(alpha = 0.05f))
                        .border(1.dp, PhononColors.Warn.copy(alpha = 0.32f), RectangleShape)
                        .padding(14.dp),
                ) {
                    Text(
                        "Binding to the LAN requires an API key. Phonon generated one and shows it once — store it with your gateway.",
                        color = PhononColors.Warn,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RectangleShape)
                        .background(PhononColors.Inset)
                        .border(1.dp, PhononColors.Border, RectangleShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (revealKey) generatedKey else "sk-phonon-••••••••••••••••••••",
                        color = PhononColors.Text,
                        fontFamily = CoreType.mono,
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
                val chosenInstalled = chosenModel?.let { ModelCatalog.path(context, it).isFile } == true
                Text(
                    (if (chosenInstalled) "Model ready" else "No model installed").uppercase(),
                    style = CoreType.section,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (chosenInstalled) {
                        "The selected GGUF is ready to serve."
                    } else {
                        chosenModel?.let {
                            "Download ${it.displayName} (${it.fileName}) before starting the server. " +
                                "The download resumes if it is interrupted."
                        } ?: "This RAM tier has no recommended entry in the selected family."
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
                val entry = chosenModel
                val installed = entry != null && ModelCatalog.path(context, entry).isFile
                when {
                    service == null -> toast(context, "Service is starting")
                    !installed -> toast(
                        context,
                        entry?.let { "Download ${it.displayName} first" } ?: "No model for this tier",
                    )
                    else -> {
                        val switchError = service.uiSelectModel(entry!!.id)
                        if (switchError != null) {
                            toast(context, switchError)
                        } else {
                            val body = JSONObject().apply {
                                put("bind_address", if (lan) Settings.BIND_ANY else Settings.DEFAULT_BIND_ADDRESS)
                                // The model picker already set the alias. Only
                                // an operator-edited node name overrides it.
                                if (aliasEdited) {
                                    put("alias", nodeName.trim().ifBlank { Settings.DEFAULT_ALIAS })
                                }
                                put("auto_start", true)
                                if (lan) put("api_key", generatedKey)
                            }
                            val result = service.updateSettings(body.toString())
                            if (jsonHasError(result)) {
                                toast(context, JSONObject(result).optString("error", "Could not save setup"))
                            } else {
                                sendServiceAction(context, PhononService.ACTION_START_SERVER)
                                toast(context, "Starting Phonon")
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = PhononColors.Cyan,
                contentColor = PhononColors.OnCyan,
            ),
        ) {
            Text("START SERVING", style = CoreType.section.copy(fontSize = 16.sp, lineHeight = 19.sp))
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RectangleShape)
                .border(1.dp, PhononColors.Border, RectangleShape)
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

/**
 * The heat band strip: four equal bands, no gaps, full-bleed, 64 dp. It is
 * identity, not a data display — temperature gets its own labelled meter.
 */
@Composable
private fun HeatBandStrip() {
    val bands = PhononColors.Heat
    Row(Modifier.fillMaxWidth().height(64.dp)) {
        bands.forEach { band ->
            Box(Modifier.weight(1f).fillMaxHeight().background(band))
        }
    }
}

/**
 * The BENCH mark, on its plate. The plate is part of the drawable, so the mark
 * can never land directly on the heat bands or on a surface of its own colour.
 */
@Composable
private fun BenchMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_bench_mark),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/** Mark + wordmark lockup. Below 48 dp the compact mark is used instead. */
@Composable
private fun BenchLockup() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                "PHONON AI",
                style = CoreType.display.copy(fontSize = 26.sp, lineHeight = 26.sp, letterSpacing = 1.4.sp),
                color = PhononColors.Text,
            )
            Spacer(Modifier.height(3.dp))
            Text("LOCAL INFERENCE", style = CoreType.microSmall, color = PhononColors.TextDim)
        }
    }
}

/**
 * Temperature meter: square track, one fill colour, a labelled scale and a
 * marker at the pause threshold. The fill shows position; it does not encode
 * heat in colour.
 */
@Composable
private fun ThermalMeter(fraction: Float, stopC: Float, paused: Boolean) {
    val fill = if (paused) PhononColors.Warn else PhononColors.Text
    val marker = PhononColors.Danger
    val stopFraction = (stopC / 60f).coerceIn(0.02f, 0.98f)
    Column {
        Box(Modifier.fillMaxWidth().height(12.dp)) {
            Box(Modifier.fillMaxSize().background(PhononColors.Border))
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(fill),
            )
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(stopFraction))
                Box(Modifier.width(2.dp).fillMaxHeight().background(marker))
                Spacer(Modifier.weight((1f - stopFraction).coerceAtLeast(0.01f)))
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 °C", style = CoreType.microSmall, color = PhononColors.TextDim)
            Text("PAUSE ${restartThreshold(stopC)} °C", style = CoreType.microSmall, color = marker)
            Text("60 °C", style = CoreType.microSmall, color = PhononColors.TextDim)
        }
    }
}

@Composable
private fun PhononCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(PhononColors.Surface)
            .border(1.dp, PhononColors.Border, RectangleShape)
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
        shape = RectangleShape,
        textStyle = TextStyle(fontSize = 14.sp, color = PhononColors.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PhononColors.Cyan,
            unfocusedBorderColor = PhononColors.Border,
            focusedLabelColor = PhononColors.Cyan,
            unfocusedLabelColor = PhononColors.TextDim,
            cursorColor = PhononColors.Cyan,
            focusedContainerColor = PhononColors.Inset,
            unfocusedContainerColor = PhononColors.Inset,
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
            .clip(RectangleShape)
            .background(if (selected) PhononColors.Cyan.copy(alpha = 0.08f) else PhononColors.Surface)
            .border(
                1.dp,
                if (selected) PhononColors.Cyan else PhononColors.Border,
                RectangleShape,
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
                        fontFamily = CoreType.mono,
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

/**
 * Gateway registration, off by default (#351).
 *
 * The key is written straight to the Android Keystore through
 * [GatewayKeyStore], never through the settings JSON API: a secret that travels
 * in a request body can end up in a log, and this one authorises writes to a
 * shared gateway. What reaches the service is only "settings changed".
 */
@Composable
private fun GatewayCard(context: Context, settings: Settings, health: Health?) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.gatewayEnabled) }
    var url by remember { mutableStateOf(settings.gatewayUrl) }
    var keyDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var keyStored by remember { mutableStateOf(GatewayKeyStore.has(context)) }

    fun apply() {
        val trimmed = url.trim()
        if (enabled) {
            val violation = GatewayAddress.violation(trimmed)
            if (violation != null) {
                status = violation
                return
            }
        }
        if (keyDraft.isNotEmpty()) {
            val failure = GatewayKeyStore.store(context, keyDraft)
            if (failure != null) {
                status = failure
                return
            }
            keyDraft = ""
            keyStored = true
        }
        settings.gatewayUrl = trimmed
        settings.gatewayEnabled = enabled
        AppState.service?.uiGatewayChanged()
        status = if (enabled) "Saved; registration will be attempted now" else "Saved; registration off"
    }

    PhononCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GATEWAY", style = CoreType.section)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Register this phone with a LiteLLM proxy so one URL and one key reach it. " +
                        "Optional, and off by default.",
                    color = PhononColors.TextDim,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                )
            }
            PhononSwitch(checked = enabled) {
                enabled = it
                apply()
            }
        }
        Spacer(Modifier.height(10.dp))
        PhononField(
            value = url,
            onValueChange = { url = it },
            label = "Gateway base URL",
            placeholder = "http://10.0.10.20:4000",
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(8.dp))
        PhononField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = if (keyStored) "Registration key (stored — leave blank to keep)" else "Registration key",
            placeholder = if (keyStored) "stored in the Keystore" else "not set",
            keyboardType = KeyboardType.Password,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This key is not the node's API key: it authorises writes to the gateway, " +
                "so use a dedicated proxy key where the gateway supports one.",
            color = PhononColors.TextDim.copy(alpha = 0.85f),
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(8.dp))
        val configured = enabled && url.isNotBlank()
        Text(
            when {
                !configured -> "Not configured"
                health?.gatewayRegistered == true ->
                    "Registered as phone-${settings.gatewayNodeId}"
                !health?.gatewayLastError.isNullOrBlank() ->
                    "Not registered: ${health?.gatewayLastError}"
                else -> "Configured; the node has not reported a registration yet"
            },
            color = PhononColors.TextDim,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "Testing…"
                    val typed = url.trim()
                    val key = keyDraft.ifEmpty { GatewayKeyStore.load(context).orEmpty() }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val violation = GatewayAddress.violation(typed)
                            when {
                                key.isEmpty() ->
                                    GatewayResult.Failure("no registration key to test")
                                violation != null -> GatewayResult.Failure(violation)
                                else -> LiteLlmRegistrar(
                                    baseUrl = typed,
                                    masterKey = key,
                                    modelName = "phone-${settings.gatewayNodeId}",
                                    servedModel = settings.alias,
                                    apiBase = "http://127.0.0.1:${settings.port}/v1",
                                    nodeKey = "",
                                ).check()
                            }
                        }
                        status = when (result) {
                            is GatewayResult.Ok -> "The gateway answered and accepted the key"
                            is GatewayResult.Failure ->
                                listOfNotNull(result.message, result.hint).joinToString(" — ")
                        }
                        busy = false
                    }
                },
            ) {
                Text("Test", color = PhononColors.Cyan, fontSize = 13.sp)
            }
            TextButton(enabled = !busy, onClick = { apply() }) {
                Text("Save", color = PhononColors.Cyan, fontSize = 13.sp)
            }
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = PhononColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
