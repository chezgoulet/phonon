package org.phononai.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * All knobs live in one SharedPreferences file so they can be changed from the
 * activity and from the loopback API without the service holding stale values.
 * Getters read the store every time; there is no cache to invalidate.
 */
class Settings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val filesDir = appContext.filesDir

    /**
     * Serialises settings mutation with the bind-time exposure check, so the
     * operator port cannot be bound off-loopback while a key change is being
     * committed. Reentrant: callers that already hold it may bind or reload.
     */
    val configLock = Any()

    /** The tier this device falls into, recomputed from physical memory. */
    val hardware: DeviceProfile get() = HardwareProfile.detect(appContext)

    fun defaultModelPath(): String =
        File(modelsDir, hardware.tier.modelFile).absolutePath

    /** The one directory a model file may live in; see [isInsideModelsDir]. */
    val modelsDir: File get() = File(filesDir, "models")

    /**
     * True when [path] canonicalises to a file inside `files/models`. The API
     * can set `model_path`, so it must not be able to point the child at
     * `/etc/passwd` or anywhere else outside app storage.
     */
    fun isInsideModelsDir(path: String): Boolean {
        val dir = modelsDir.canonicalFile
        val candidate = try {
            File(path).canonicalFile
        } catch (_: Throwable) {
            return false
        }
        return candidate.parentFile == dir || candidate == dir
    }

    /** The catalog entry for the currently selected file, if it is one we know. */
    fun catalogEntryForCurrentModel(): ModelEntry? =
        ModelCatalog.byFileName(File(modelPath).name)

    /**
     * Point settings at a catalog entry: file, default context and alias move
     * together, so switching family cannot leave a Qwen alias on a Gemma
     * server. Marks the choice as the operator's; auto-config must not undo it.
     */
    fun selectModel(entry: ModelEntry) {
        modelPath = File(File(filesDir, "models"), entry.fileName).absolutePath
        contextSize = entry.contextSize
        alias = entry.id
        modelChosenByUser = true
    }

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, null) ?: defaultModelPath()
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()

    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, hardware.tier.contextSize)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    /**
     * Effective decode thread count. Until the operator overrides it this is
     * computed from the process's allowed cpuset (see [perfPlan]); after an
     * override it is the stored value.
     */
    var threads: Int
        get() = if (perfThreadsSet) {
            prefs.getInt(KEY_THREADS, hardware.tier.threads)
        } else {
            perfPlan().threads
        }
        set(value) {
            prefs.edit()
                .putInt(KEY_THREADS, value)
                .putBoolean(KEY_PERF_THREADS_SET, true)
                .apply()
        }

    /** Set by the Settings screen's Save action. Auto-configuration never
     * replaces a value the operator has chosen, including after a restart. */
    var tunedByUser: Boolean
        get() = prefs.getBoolean(KEY_TUNED_BY_USER, false)
        set(value) = prefs.edit().putBoolean(KEY_TUNED_BY_USER, value).apply()

    /** Set by the Models picker or the Setup choice. Auto-configuration never
     * replaces the model, context or alias after this is set. */
    var modelChosenByUser: Boolean
        get() = prefs.getBoolean(KEY_MODEL_CHOSEN, false)
        set(value) = prefs.edit().putBoolean(KEY_MODEL_CHOSEN, value).apply()

    // --- performance profile overrides -----------------------------------

    /** True when the operator chose a thread count; auto-tuning is off. */
    var perfThreadsSet: Boolean
        get() = prefs.getBoolean(KEY_PERF_THREADS_SET, false)
        private set(value) {
            prefs.edit().putBoolean(KEY_PERF_THREADS_SET, value).apply()
        }

    /** Operator `--cpu-range`, e.g. `4-5`. Blank means auto. */
    var perfCpuRange: String
        get() = prefs.getString(KEY_PERF_CPU_RANGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PERF_CPU_RANGE, value.trim()).apply()

    var perfCpuRangeSet: Boolean
        get() = prefs.getBoolean(KEY_PERF_CPU_RANGE_SET, false)
        private set(value) {
            prefs.edit().putBoolean(KEY_PERF_CPU_RANGE_SET, value).apply()
        }

    /** Operator thread priority (0 normal .. 3 realtime); -1 keeps auto. */
    var perfPrio: Int
        get() = prefs.getInt(KEY_PERF_PRIO, -1)
        set(value) = prefs.edit().putInt(KEY_PERF_PRIO, value).apply()

    var perfPrioBatch: Int
        get() = prefs.getInt(KEY_PERF_PRIO_BATCH, -1)
        set(value) = prefs.edit().putInt(KEY_PERF_PRIO_BATCH, value).apply()

    var perfPrioSet: Boolean
        get() = prefs.getBoolean(KEY_PERF_PRIO_SET, false)
        private set(value) {
            prefs.edit().putBoolean(KEY_PERF_PRIO_SET, value).apply()
        }

    /** Operator prefill thread count (`-tb`); -1 keeps auto. */
    var perfPrefillThreads: Int
        get() = prefs.getInt(KEY_PERF_PREFILL, -1)
        set(value) = prefs.edit().putInt(KEY_PERF_PREFILL, value).apply()

    var perfPrefillThreadsSet: Boolean
        get() = prefs.getBoolean(KEY_PERF_PREFILL_SET, false)
        private set(value) {
            prefs.edit().putBoolean(KEY_PERF_PREFILL_SET, value).apply()
        }

    fun setPrefillThreadsOverride(value: Int) {
        prefs.edit()
            .putInt(KEY_PERF_PREFILL, value)
            .putBoolean(KEY_PERF_PREFILL_SET, true)
            .apply()
    }

    fun clearPrefillThreadsOverride() {
        prefs.edit()
            .putBoolean(KEY_PERF_PREFILL_SET, false)
            .remove(KEY_PERF_PREFILL)
            .apply()
    }

    /** Per-SoC tuning profiles as raw JSON keyed by SOC_MODEL; see SocTuning. */
    var socTuningJson: String
        get() = prefs.getString(KEY_SOC_TUNING, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SOC_TUNING, value).apply()

    /** The SoC key used to look up a measured profile (SOC_MODEL, else HARDWARE). */
    private val socKey: String
        get() = SocTuning.resolveSoc(
            sdkInt = Build.VERSION.SDK_INT,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            hardware = Build.HARDWARE,
        )

    /** The stored profile for this SoC that matches the running engine, or null. */
    fun storedTuning(): TuningProfile? =
        SocTuning.lookup(socTuningJson, socKey, SocTuning.ENGINE_REV)

    /** The engine revision of a stored profile that no longer matches, or null. */
    fun staleTuningEngineRev(): String? =
        SocTuning.staleEngineRev(socTuningJson, socKey, SocTuning.ENGINE_REV)

    /**
     * When the battery first read full while charging, persisted so the
     * four-hour hygiene warning survives a service restart. 0 means not full.
     */
    var batteryFullSinceMs: Long
        get() = prefs.getLong(KEY_BATTERY_FULL_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_BATTERY_FULL_SINCE, value).apply()

    /**
     * The [batteryFullSinceMs] value the operator dismissed; the warning stays
     * hidden until the phone drops off full and the timer resets.
     */
    var chargeWarningDismissedSinceMs: Long
        get() = prefs.getLong(KEY_CHARGE_WARNING_DISMISSED, 0L)
        set(value) = prefs.edit().putLong(KEY_CHARGE_WARNING_DISMISSED, value).apply()

    /**
     * Hourly state-of-charge ring, as JSON. Persisted so "hours above 80% this
     * week" survives a service restart — the metric is about a week, not about
     * how long the app has been up. Corrupt or absent means an empty ring.
     */
    var socHistoryJson: String
        get() = prefs.getString(KEY_SOC_HISTORY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SOC_HISTORY, value).apply()

    /** Optional gateway registration (#351). Off by default. */
    var gatewayEnabled: Boolean
        get() = prefs.getBoolean(KEY_GATEWAY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GATEWAY_ENABLED, value).apply()

    /**
     * The gateway's base URL, e.g. `http://10.0.10.20:4000`. Not a secret.
     * The registration key is NOT here: it lives encrypted in GatewayKeyStore.
     */
    var gatewayUrl: String
        get() = prefs.getString(KEY_GATEWAY_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GATEWAY_URL, value.trim()).apply()

    /**
     * The stable short id in `phone-<shortid>`. Generated once, so a restart or
     * a model switch updates the same gateway entry instead of adding a row.
     */
    val gatewayNodeId: String
        get() {
            val existing = prefs.getString(KEY_GATEWAY_NODE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit().putString(KEY_GATEWAY_NODE_ID, generated).apply()
            return generated
        }


    /**
     * Off by default: while a request is in flight the avatar drops to a
     * cheaper animation (half the motes, 20 fps) so it does not compete with
     * inference. Idle animation is always full.
     */
    var animateAvatarWhileGenerating: Boolean
        get() = prefs.getBoolean(KEY_ANIMATE_GENERATING, false)
        set(value) = prefs.edit().putBoolean(KEY_ANIMATE_GENERATING, value).apply()

    /**
     * Whether the operator wants the server running. Persisted so an explicit
     * Stop survives a service restart; autoStart alone cannot express "stopped".
     */
    var servingRequested: Boolean
        get() = prefs.getBoolean(KEY_SERVING_REQUESTED, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVING_REQUESTED, value).apply()

    /** The idle shape the presence takes. Unknown tokens fall back to "aura". */
    var presenceShape: String
        get() = prefs.getString(KEY_PRESENCE_SHAPE, "aura") ?: "aura"
        set(value) = prefs.edit().putString(KEY_PRESENCE_SHAPE, value).apply()

    fun setThreadsOverride(value: Int) {
        prefs.edit()
            .putInt(KEY_THREADS, value)
            .putBoolean(KEY_PERF_THREADS_SET, true)
            .apply()
    }

    fun setCpuRangeOverride(value: String) {
        prefs.edit()
            .putString(KEY_PERF_CPU_RANGE, value.trim())
            .putBoolean(KEY_PERF_CPU_RANGE_SET, true)
            .apply()
    }

    fun setPrioOverride(prio: Int, prioBatch: Int) {
        prefs.edit()
            .putInt(KEY_PERF_PRIO, prio)
            .putInt(KEY_PERF_PRIO_BATCH, prioBatch)
            .putBoolean(KEY_PERF_PRIO_SET, true)
            .apply()
    }

    fun clearThreadsOverride() {
        prefs.edit().putBoolean(KEY_PERF_THREADS_SET, false).apply()
    }

    fun clearCpuRangeOverride() {
        prefs.edit()
            .putBoolean(KEY_PERF_CPU_RANGE_SET, false)
            .remove(KEY_PERF_CPU_RANGE)
            .apply()
    }

    fun clearPrioOverride() {
        prefs.edit()
            .putBoolean(KEY_PERF_PRIO_SET, false)
            .remove(KEY_PERF_PRIO)
            .remove(KEY_PERF_PRIO_BATCH)
            .apply()
    }

    /** Return every performance field to the runtime-computed default. */
    fun clearPerfOverrides() {
        prefs.edit()
            .putBoolean(KEY_PERF_THREADS_SET, false)
            .putBoolean(KEY_PERF_CPU_RANGE_SET, false)
            .putBoolean(KEY_PERF_PRIO_SET, false)
            .putBoolean(KEY_PERF_PREFILL_SET, false)
            .remove(KEY_PERF_CPU_RANGE)
            .remove(KEY_PERF_PRIO)
            .remove(KEY_PERF_PRIO_BATCH)
            .remove(KEY_PERF_PREFILL)
            .apply()
    }

    /** The complete performance-override state, so a sweep can put it back. */
    data class PerfOverrides(
        val threadsSet: Boolean,
        val threads: Int,
        val rangeSet: Boolean,
        val range: String,
        val prefillSet: Boolean,
        val prefill: Int,
        val prioSet: Boolean,
        val prio: Int,
        val prioBatch: Int,
    )

    /**
     * Snapshot the overrides before the tuning sweep writes its own, so the
     * operator's choices are restored afterwards rather than left as if the
     * app had overridden them.
     */
    fun snapshotPerfOverrides(): PerfOverrides = PerfOverrides(
        threadsSet = perfThreadsSet,
        threads = prefs.getInt(KEY_THREADS, hardware.tier.threads),
        rangeSet = perfCpuRangeSet,
        range = perfCpuRange,
        prefillSet = perfPrefillThreadsSet,
        prefill = perfPrefillThreads,
        prioSet = perfPrioSet,
        prio = perfPrio,
        prioBatch = perfPrioBatch,
    )

    /** Restore a snapshot from [snapshotPerfOverrides]. */
    fun restorePerfOverrides(s: PerfOverrides) {
        prefs.edit().apply {
            putBoolean(KEY_PERF_THREADS_SET, s.threadsSet)
            putInt(KEY_THREADS, s.threads)
            if (s.rangeSet) {
                putString(KEY_PERF_CPU_RANGE, s.range)
                putBoolean(KEY_PERF_CPU_RANGE_SET, true)
            } else {
                remove(KEY_PERF_CPU_RANGE)
                putBoolean(KEY_PERF_CPU_RANGE_SET, false)
            }
            if (s.prefillSet) {
                putInt(KEY_PERF_PREFILL, s.prefill)
                putBoolean(KEY_PERF_PREFILL_SET, true)
            } else {
                remove(KEY_PERF_PREFILL)
                putBoolean(KEY_PERF_PREFILL_SET, false)
            }
            if (s.prioSet) {
                putInt(KEY_PERF_PRIO, s.prio)
                putInt(KEY_PERF_PRIO_BATCH, s.prioBatch)
                putBoolean(KEY_PERF_PRIO_SET, true)
            } else {
                remove(KEY_PERF_PRIO)
                remove(KEY_PERF_PRIO_BATCH)
                putBoolean(KEY_PERF_PRIO_SET, false)
            }
        }.apply()
    }

    /**
     * The profile for the current cpuset. Threads, range and priority are
     * auto unless overridden; an override disables only its own field.
     */
    fun perfPlan(): PerfPlan {
        val detection = PerfProfile.detect()
        val best = detection.best
        val autoRange = best?.range.orEmpty()
        // The fastest cluster can be a single prime core (Pixel 8/9, most
        // Snapdragons). One thread leaves the machine idle, so floor it at two.
        val autoThreads = (best?.size ?: minOf(4, detection.allowed.size)).coerceAtLeast(2)
        // Precedence: an operator override, then a stored per-SoC profile
        // measured against this engine, then the runtime heuristic. The profile
        // path never writes an override, so an engine-revision mismatch falls
        // back to the heuristic on its own and a later rebuild stops applying a
        // stale number without the operator having to clear anything.
        val effective = SocTuning.decideEffective(
            userThreads = if (perfThreadsSet) prefs.getInt(KEY_THREADS, hardware.tier.threads) else null,
            userCpuRange = if (perfCpuRangeSet) perfCpuRange else null,
            userPrefillThreads = if (perfPrefillThreadsSet) perfPrefillThreads else null,
            profile = storedTuning(),
            autoThreads = autoThreads,
            autoCpuRange = autoRange,
        )
        val range = effective.cpuRange
        val prio = if (perfPrio >= 0) perfPrio else PerfPlan.DEFAULT_PRIO
        val prioBatch =
            if (perfPrioBatch >= 0) perfPrioBatch else PerfPlan.DEFAULT_PRIO_BATCH
        val prefillThreads = effective.prefillThreads
        val cluster = PerfProfile.parseCpuList(range)
            .filter { it in detection.allowed }
        return PerfPlan(
            allowed = detection.allowed,
            mode = if (detection.visible) PerfPlan.MODE_VISIBLE else PerfPlan.MODE_BACKGROUND,
            cluster = cluster,
            cpuRange = range,
            threads = effective.threads.coerceIn(1, 16),
            prio = prio.coerceIn(0, 3),
            prioBatch = prioBatch.coerceIn(0, 3),
            threadsSet = perfThreadsSet,
            cpuRangeSet = perfCpuRangeSet,
            prioSet = perfPrioSet,
            prefillThreads = prefillThreads,
            allowedCount = detection.allowed.size,
            clusterCount = detection.clusters.size,
            clusterLabel = detection.clusterLabel,
        )
    }

    /**
     * Address both the app API and llama-server bind to. Safe values are
     * loopback, the wildcard address, or an IPv4 address assigned to a local
     * interface. Anything else is rejected at the settings boundary.
     */
    var bindAddress: String
        get() = prefs.getString(KEY_BIND_ADDRESS, DEFAULT_BIND_ADDRESS) ?: DEFAULT_BIND_ADDRESS
        set(value) = prefs.edit().putString(KEY_BIND_ADDRESS, value).apply()

    /**
     * The one rule that must hold before either listener is bound off-loopback.
     * Null means the configuration is safe to start.
     */
    val exposureError: String?
        get() = if (!isLoopbackBind(bindAddress) && apiKey.isBlank()) {
            "bind_address requires api_key"
        } else {
            null
        }

    val exposedOffHost: Boolean
        get() = !isLoopbackBind(bindAddress)

    /**
     * The backend always listens on loopback (see ServerManager: argv is
     * `--host 127.0.0.1`, because the authenticated proxy is the only LAN
     * listener). [bindAddress] is the *app's* listener, not the engine's, so
     * this must not follow it: doing so sent the proxy to an address where
     * llama-server is not bound.
     */
    fun backendHost(): String = "127.0.0.1"

    /**
     * Where an in-app client should reach the app's own API. A wildcard bind is
     * reached on loopback; a specific bind only answers on that address.
     */
    fun apiHost(): String =
        if (bindAddress == BIND_ANY || isLoopbackBind(bindAddress)) "127.0.0.1" else bindAddress

    /** Port the app's own API listens on (health, /v1 proxy, control routes). */
    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** Internal port llama-server binds to; the app proxies /v1 to it. */
    var backendPort: Int
        get() = prefs.getInt(KEY_BACKEND_PORT, 8081)
        set(value) = prefs.edit().putInt(KEY_BACKEND_PORT, value).apply()

    var reasoning: Boolean
        get() = prefs.getBoolean(KEY_REASONING, false)
        set(value) = prefs.edit().putBoolean(KEY_REASONING, value).apply()

    var thermalStopC: Float
        get() = prefs.getFloat(KEY_THERMAL_STOP, 45f)
        set(value) = prefs.edit().putFloat(KEY_THERMAL_STOP, value).apply()

    var thermalResumeC: Float
        get() = prefs.getFloat(KEY_THERMAL_RESUME, 40f)
        set(value) = prefs.edit().putFloat(KEY_THERMAL_RESUME, value).apply()

    /**
     * True once the operator has set their own battery-temperature thresholds.
     * Only then are those thresholds the operative signal: with no override the
     * app uses PowerManager's thermal headroom/status, and the stored numbers are
     * just the fallback for phones that expose neither. See ThermalPolicy.
     */
    var thermalUserOverride: Boolean
        get() = prefs.getBoolean(KEY_THERMAL_OVERRIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_THERMAL_OVERRIDE, value).apply()

    /** Pause serving when discharging below this level. 0 disables the policy. */
    var batteryPauseBelow: Int
        get() = prefs.getInt(KEY_BATTERY_PAUSE_BELOW, 15)
        set(value) = prefs.edit().putInt(KEY_BATTERY_PAUSE_BELOW, value.coerceIn(0, 90)).apply()

    /** Resume when the level climbs back to this (or the charger is connected). */
    var batteryResumeAbove: Int
        get() = prefs.getInt(KEY_BATTERY_RESUME_ABOVE, 25)
        set(value) = prefs.edit().putInt(KEY_BATTERY_RESUME_ABOVE, value.coerceIn(1, 100)).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * Requests per minute per client address, shared by the control routes and
     * /v1. 0 disables the limiter. The default is deliberately far above what
     * an agent doing real work produces on a phone whose requests take minutes.
     */
    var rateLimitPerMin: Int
        get() = prefs.getInt(KEY_RATE_LIMIT_PER_MIN, DEFAULT_RATE_LIMIT_PER_MIN)
        set(value) = prefs.edit()
            .putInt(KEY_RATE_LIMIT_PER_MIN, value.coerceIn(0, MAX_RATE_LIMIT_PER_MIN))
            .apply()

    var alias: String
        get() = prefs.getString(KEY_ALIAS, DEFAULT_ALIAS) ?: DEFAULT_ALIAS
        set(value) = prefs.edit().putString(KEY_ALIAS, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var downloadUrl: String
        get() {
            val stored = prefs.getString(KEY_DOWNLOAD_URL, null)
            // Devices that ran an older build have the development loopback URL
            // persisted. Read it as "unset" so upgrades get the empty default
            // and the hint, without rewriting storage behind the service.
            if (stored == LEGACY_DEV_MODEL_URL) return DEFAULT_MODEL_URL
            return stored ?: DEFAULT_MODEL_URL
        }
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_URL, value).apply()

    /**
     * Send a tiny completion while idle so the kernel does not evict the
     * model's file-backed pages. See "Keeping the model warm" in docs/app.md.
     */
    var keepWarm: Boolean
        get() = prefs.getBoolean(KEY_KEEP_WARM, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_WARM, value).apply()

    var keepWarmIntervalS: Int
        get() = prefs.getInt(KEY_KEEP_WARM_INTERVAL, 45)
        set(value) = prefs.edit().putInt(KEY_KEEP_WARM_INTERVAL, value).apply()

    /**
     * The last keep-warm interval the adaptation measured, or 0 when none has
     * been measured yet. Read only after [storedTuning]'s own value: the profile
     * is the measured record, this is the fallback that survives a restart when
     * there is no profile yet.
     */
    var keepWarmAdaptedS: Int
        get() = prefs.getInt(KEY_KEEP_WARM_ADAPTED, 0)
        set(value) = prefs.edit().putInt(KEY_KEEP_WARM_ADAPTED, value).apply()

    /**
     * The keep-warm interval in force: the stored profile's measured value when
     * present, else the last adapted value, else the operator's setting. See
     * [KeepWarmAdaptation.start].
     */
    fun effectiveKeepWarmIntervalS(): Int =
        KeepWarmAdaptation.start(
            profileValueS = storedTuning()?.keepWarmIntervalS,
            // The last adapted value wins over the operator's raw setting, but
            // the setting is still the starting point on a phone that has not
            // adapted yet (and still has no profile).
            adaptedValueS = keepWarmAdaptedS.takeIf { it > 0 } ?: keepWarmIntervalS,
        )


    /**
     * Persist an adapted keep-warm interval: remembered in the prefs so it
     * survives a restart, and written into the stored profile when there is one
     * so the profile carries the measured number rather than a default.
     */
    fun persistAdaptedKeepWarm(intervalS: Int) {
        keepWarmAdaptedS = intervalS
        val next = SocTuning.withKeepWarmInterval(socTuningJson, socKey, intervalS)
        if (next != socTuningJson) socTuningJson = next
    }

    /**
     * Record a context the app auto-sized for this phone. Only ever called when
     * the operator has not chosen a context (see [modelChosenByUser]); the label
     * is the fits/tight verdict the Settings card shows.
     */
    fun recordAutoContext(contextSize: Int, label: String) {
        contextAutoSized = true
        contextFitLabel = label
        val next = SocTuning.withContext(socTuningJson, socKey, contextSize)
        if (next != socTuningJson) socTuningJson = next
    }

    /** True once the app has picked a context for this phone on its own. */
    var contextAutoSized: Boolean
        get() = prefs.getBoolean(KEY_CONTEXT_AUTO_SIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTEXT_AUTO_SIZED, value).apply()

    /** The fits / tight / does-not-fit verdict for the current context. */
    var contextFitLabel: String
        get() = prefs.getString(KEY_CONTEXT_FIT_LABEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CONTEXT_FIT_LABEL, value).apply()

    /** llama.cpp -lm: mmap (default), mlock or none. */
    var loadMode: String
        get() = prefs.getString(KEY_LOAD_MODE, LOAD_MODE_MMAP) ?: LOAD_MODE_MMAP
        set(value) = prefs.edit().putString(KEY_LOAD_MODE, value).apply()

    /**
     * Apply the RAM-tier defaults once, on the first start after install or
     * data clear. A value the operator saved from the Settings screen is
     * never overwritten: [tunedByUser] is set by that screen's Save action.
     */
    fun applyAutoConfigIfNeeded() {
        if (prefs.getBoolean(KEY_AUTO_CONFIG_DONE, false)) return
        if (!tunedByUser && !modelChosenByUser) {
            val tier = hardware.tier
            modelPath = File(File(filesDir, "models"), tier.modelFile).absolutePath
            contextSize = tier.contextSize
            // Write the tier value without marking it a performance override:
            // the runtime profile still chooses threads from the cpuset.
            prefs.edit().putInt(KEY_THREADS, tier.threads).apply()
            alias = tier.alias
        }
        prefs.edit().putBoolean(KEY_AUTO_CONFIG_DONE, true).apply()
    }

    /** Newest first, capped at [MAX_BENCHMARK_HISTORY]. */
    val benchmarkHistory: List<JSONObject>
        get() {
            val raw = prefs.getString(KEY_BENCHMARK_HISTORY, null) ?: return emptyList()
            return try {
                val array = org.json.JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    fun appendBenchmark(result: JSONObject) {
        val trimmed = (listOf(result) + benchmarkHistory).take(MAX_BENCHMARK_HISTORY)
        val array = org.json.JSONArray()
        trimmed.forEach { array.put(it) }
        prefs.edit().putString(KEY_BENCHMARK_HISTORY, array.toString()).apply()
    }

    fun asJson(): String = org.json.JSONObject().apply {
        put("model_path", modelPath)
        put("context", contextSize)
        put("threads", threads)
        put("bind_address", bindAddress)
        put("port", port)
        put("backend_port", backendPort)
        put("reasoning", reasoning)
        put("thermal_stop_c", thermalStopC.toDouble())
        put("thermal_resume_c", thermalResumeC.toDouble())
        put("thermal_user_override", thermalUserOverride)
        put("battery_pause_below", batteryPauseBelow)
        put("battery_resume_above", batteryResumeAbove)
        put("api_key_set", apiKey.isNotBlank())
        put("rate_limit_per_min", rateLimitPerMin)
        put("alias", alias)
        put("auto_start", autoStart)
        put("download_url", downloadUrl)
        put("keep_warm", keepWarm)
        put("keep_warm_interval_s", keepWarmIntervalS)
        put("load_mode", loadMode)
    }.toString()

    companion object {
        const val DEFAULT_MODEL_NAME = "Qwen3.5-2B-Q4_K_M.gguf"

        /**
         * No model host is bundled: a development loopback URL read as a broken
         * default. The download screen asks for a URL instead.
         */
        const val DEFAULT_MODEL_URL = ""

        /** Only value written by builds before the default was removed. */
        private const val LEGACY_DEV_MODEL_URL = "http://127.0.0.1:9000/gemma-4-E2B-it-Q4_0.gguf"
        // Not a compile-time constant any more: the default id lives in
        // assets/catalog.json with the rest of the catalog.
        val DEFAULT_ALIAS = ModelCatalog.DEFAULT_ID
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val BIND_ANY = "0.0.0.0"
        const val LOAD_MODE_MMAP = "mmap"
        val LOAD_MODES = setOf(LOAD_MODE_MMAP, "mlock", "none")
        const val MIN_KEEP_WARM_INTERVAL_S = 15
        const val DEFAULT_KEEP_WARM_INTERVAL_S = 45
        const val MAX_CONTEXT = 65_536
        const val MAX_BENCHMARK_HISTORY = 10

        /** Control and /v1 requests per minute per client; 0 disables. */
        const val DEFAULT_RATE_LIMIT_PER_MIN = 600
        const val MAX_RATE_LIMIT_PER_MIN = 60_000
        const val RATE_LIMIT_BURST = 100

        fun isLoopbackBind(value: String): Boolean {
            val v = value.trim()
            return v == DEFAULT_BIND_ADDRESS || v.startsWith("127.")
        }

        /**
         * Validate a proposed bind address. Returns an error message, or null
         * when the value is 127.0.0.1, 0.0.0.0, or an IPv4 address currently
         * assigned to a local interface.
         */
        fun validateBindAddress(value: String): String? {
            val v = value.trim()
            if (!isIPv4(v)) return "bind_address must be an IPv4 address"
            if (v == DEFAULT_BIND_ADDRESS || v == BIND_ANY) return null
            if (!isAssignedToLocalInterface(v)) {
                return "bind_address is not a local IPv4 address: $v"
            }
            return null
        }

        private fun isIPv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    part.toInt() in 0..255
            }
        }

        private fun isAssignedToLocalInterface(value: String): Boolean = try {
            val target = InetAddress.getByName(value)
            if (target !is Inet4Address) {
                false
            } else {
                NetworkInterface.getNetworkInterfaces()?.toList()?.any { nif ->
                    nif.inetAddresses.toList().any { address ->
                        address is Inet4Address && address.hostAddress == target.hostAddress
                    }
                } ?: false
            }
        } catch (_: Throwable) {
            false
        }

        private const val NAME = "phonon"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_BIND_ADDRESS = "bind_address"
        private const val KEY_CONTEXT = "context"
        private const val KEY_THREADS = "threads"
        private const val KEY_PORT = "port"
        private const val KEY_BACKEND_PORT = "backend_port"
        private const val KEY_REASONING = "reasoning"
        private const val KEY_THERMAL_STOP = "thermal_stop_c"
        private const val KEY_THERMAL_RESUME = "thermal_resume_c"
        private const val KEY_THERMAL_OVERRIDE = "thermal_user_override"
        private const val KEY_BATTERY_PAUSE_BELOW = "battery_pause_below"
        private const val KEY_BATTERY_RESUME_ABOVE = "battery_resume_above"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ALIAS = "alias"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_KEEP_WARM = "keep_warm"
        private const val KEY_KEEP_WARM_INTERVAL = "keep_warm_interval_s"
        private const val KEY_KEEP_WARM_ADAPTED = "keep_warm_adapted_s"
        private const val KEY_CONTEXT_AUTO_SIZED = "context_auto_sized"
        private const val KEY_CONTEXT_FIT_LABEL = "context_fit_label"
        private const val KEY_LOAD_MODE = "load_mode"
        private const val KEY_TUNED_BY_USER = "tuned_by_user"
        private const val KEY_MODEL_CHOSEN = "model_chosen_by_user"
        private const val KEY_AUTO_CONFIG_DONE = "auto_config_done"
        private const val KEY_BENCHMARK_HISTORY = "benchmark_history"
        private const val KEY_PERF_THREADS_SET = "perf_threads_set"
        private const val KEY_PERF_CPU_RANGE = "perf_cpu_range"
        private const val KEY_PERF_CPU_RANGE_SET = "perf_cpu_range_set"
        private const val KEY_PERF_PRIO = "perf_prio"
        private const val KEY_PERF_PRIO_BATCH = "perf_prio_batch"
        private const val KEY_PERF_PRIO_SET = "perf_prio_set"
        private const val KEY_PERF_PREFILL = "perf_prefill_threads"
        private const val KEY_PERF_PREFILL_SET = "perf_prefill_threads_set"
        private const val KEY_SOC_TUNING = "soc_tuning_profiles"
        private const val KEY_BATTERY_FULL_SINCE = "battery_full_since_ms"
        private const val KEY_CHARGE_WARNING_DISMISSED = "charge_warning_dismissed_since_ms"
        private const val KEY_SOC_HISTORY = "soc_history"
        private const val KEY_GATEWAY_ENABLED = "gateway_enabled"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_NODE_ID = "gateway_node_id"
        private const val KEY_ANIMATE_GENERATING = "animate_avatar_while_generating"
        private const val KEY_PRESENCE_SHAPE = "visual_shape_presence"
        private const val KEY_SERVING_REQUESTED = "serving_requested"
        private const val KEY_RATE_LIMIT_PER_MIN = "rate_limit_per_min"
    }
}
