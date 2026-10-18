package org.phononai.app

/**
 * Choosing a context size that fits *this* phone.
 *
 * The tier default is a RAM-tier guess; this is a measurement of the phone in
 * front of us. It reuses [MemoryFit]'s honest model — the KV cache, the working
 * margin and the dirty share of the mapped weights are the memory the kernel
 * cannot reclaim quickly — and additionally holds back [HEADROOM] of the
 * available memory so the load itself and the UI are not what tips the phone
 * into an lmkd kill.
 *
 * Only ever *picks* a size; it must not run when the operator has chosen one.
 * Pure so the choice is unit tested on the JVM without a device.
 */
object ContextSizing {

    /** Never auto-size below this: a smaller context is not worth a profile. */
    const val MIN_CONTEXT = 4096

    /** Mirrors Settings.MAX_CONTEXT; kept local so this object stays Android-free. */
    const val MAX_CONTEXT = 65_536

    /** Fraction of available memory held back for the system and the UI. */
    const val HEADROOM = 0.20

    /** Above this share of the budget the choice is reported as tight. */
    const val TIGHT_SHARE = 0.85

    /** The context sizes offered, smallest first; the largest that fits wins. */
    val CANDIDATES = listOf(4096, 8192, 16_384, 32_768, 65_536)

    /**
     * A chosen context and why. [fits] is false only when even [MIN_CONTEXT]
     * does not fit — the floor is still reported so the UI can say so plainly
     * instead of hiding the problem.
     */
    data class Choice(
        val context: Int,
        val fits: Boolean,
        val tight: Boolean,
        val cap: Int,
        val requiredBytes: Long,
        val budgetBytes: Long,
    ) {
        /** The word the Settings card shows next to the size. */
        val label: String
            get() = when {
                !fits -> "does not fit"
                tight -> "tight"
                else -> "fits"
            }
    }

    /**
     * The memory that cannot be reclaimed at [context]: KV cache + working
     * margin + the dirty share of the mapped weights. The same model
     * [MemoryFit] uses, so the two verdicts cannot disagree.
     */
    fun requiredBytes(modelBytes: Long, mmprojBytes: Long, context: Int): Long =
        MemoryFit.estimateKvCacheBytes(modelBytes, context) +
            MemoryFit.WORKING_MARGIN_BYTES +
            (modelBytes + mmprojBytes) / 8

    /** The share of [availBytes] a configuration may claim, after headroom. */
    fun budgetBytes(availBytes: Long): Long = (availBytes * (1.0 - HEADROOM)).toLong()

    fun fits(modelBytes: Long, mmprojBytes: Long, context: Int, availBytes: Long): Boolean =
        availBytes <= 0 ||
            requiredBytes(modelBytes, mmprojBytes, context) <= budgetBytes(availBytes)

    /** The sizes to try: the candidate list clamped to [MIN_CONTEXT, cap], plus cap. */
    fun candidates(cap: Int): List<Int> {
        val ceiling = cap.coerceIn(MIN_CONTEXT, MAX_CONTEXT)
        return (CANDIDATES.filter { it in MIN_CONTEXT..ceiling } + ceiling)
            .filter { it >= MIN_CONTEXT }
            .distinct()
            .sorted()
    }

    /**
     * The fits / tight / does-not-fit verdict for one [context] at the given
     * available memory, or "unknown" when neither is known. Used live in
     * /health so the label reflects the memory the phone has *now*, not the
     * moment the size was chosen.
     */
    fun verdictLabel(
        modelBytes: Long,
        mmprojBytes: Long,
        context: Int,
        availBytes: Long,
    ): String {
        if (availBytes <= 0 || modelBytes <= 0 || context <= 0) return "unknown"
        val budget = budgetBytes(availBytes)
        val required = requiredBytes(modelBytes, mmprojBytes, context)
        return when {
            required > budget -> "does not fit"
            required > (budget * TIGHT_SHARE).toLong() -> "tight"
            else -> "fits"
        }
    }

    /**
     * The largest context that fits with headroom, or null when no honest choice
     * can be made — the phone's available memory is unknown (0) or the model is
     * not on disk yet.
     */
    fun choose(
        modelBytes: Long,
        mmprojBytes: Long,
        availBytes: Long,
        modelMaxContext: Int,
    ): Choice? {
        if (availBytes <= 0 || modelBytes <= 0) return null
        if (modelMaxContext < MIN_CONTEXT) return null
        val cap = modelMaxContext.coerceAtMost(MAX_CONTEXT)
        val budget = budgetBytes(availBytes)
        val options = candidates(cap)
        val best = options.lastOrNull {
            requiredBytes(modelBytes, mmprojBytes, it) <= budget
        }
        if (best == null) {
            val floor = options.first()
            return Choice(
                context = floor,
                fits = false,
                tight = true,
                cap = cap,
                requiredBytes = requiredBytes(modelBytes, mmprojBytes, floor),
                budgetBytes = budget,
            )
        }
        val required = requiredBytes(modelBytes, mmprojBytes, best)
        return Choice(
            context = best,
            fits = true,
            tight = required > (budget * TIGHT_SHARE).toLong(),
            cap = cap,
            requiredBytes = required,
            budgetBytes = budget,
        )
    }
}
