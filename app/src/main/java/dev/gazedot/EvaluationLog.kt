package dev.gazedot

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Local evaluation logging, schema v1. Records coordinates, timing and validity only:
 * never camera frames, images, landmarks or eye feature vectors. Files stay in app-private storage.
 */
const val EVALUATION_SCHEMA_VERSION = 1

enum class EvaluationInvalidReason(val id: String) {
    /** FaceLandmarker was not initialized yet, so no detection ran. */
    LANDMARKER_NOT_READY("landmarker_not_ready"),
    NO_FACE("no_face"),
    /** LandmarkFeatures.extract returned null: eye closed, eye too small, or too few landmarks. */
    FEATURES_UNAVAILABLE("features_unavailable"),
    NO_CALIBRATION_MODEL("no_calibration_model"),
    /** GazeModel.predict returned null: head position/scale gate or non-finite features. */
    MODEL_REJECTED("model_rejected"),
    /** An exception in the frame path; the service stops itself afterwards. */
    PROCESSING_ERROR("processing_error"),
    /** The result reached the main thread after the service began shutting down. */
    SERVICE_STOPPING("service_stopping"),
}

enum class EvaluationEndReason(val id: String) {
    COMPLETED("completed"), BACK("back"), ON_PAUSE("background_on_pause"),
    SERVICE_STOPPED("service_stopped"), GEOMETRY_REJECTED("geometry_rejected"), LOGGING_FAILED("logging_failed"),
}

/** Resolves why an attempt has no model output, using only states the pipeline can tell apart. */
fun evaluationInvalidReason(
    workerReason: EvaluationInvalidReason?, modelLoaded: Boolean, prediction: Pair<Double, Double>?,
): EvaluationInvalidReason? = workerReason ?: when {
    !modelLoaded -> EvaluationInvalidReason.NO_CALIBRATION_MODEL
    prediction == null -> EvaluationInvalidReason.MODEL_REJECTED
    else -> null
}

data class EvaluationSessionInfo(
    val manufacturer: String, val model: String, val apiLevel: Int,
    val appVersionName: String?, val appVersionCode: Long,
    /** "max_window_metrics" (API 30+) or "evaluation_view" (API <30), matching CalibrationView. */
    val geometryBasis: String,
)

object EvaluationCsv {
    val HEADER = listOf(
        "schema_version", "session_id", "attempt_seq", "sample_uptime_ms", "elapsed_ms", "mode", "phase",
        "pass_number", "trial_index", "index_in_pass", "target_id", "target_x_norm", "target_y_norm",
        "prediction_valid", "invalid_reason", "model_x_norm", "model_y_norm", "cursor_x_norm", "cursor_y_norm",
        "inference_duration_ms",
    )

    fun line(values: List<String>) = values.joinToString(",") { escape(it) }
    fun escape(v: String) =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Missing values are empty cells, never 0. Indices are 0-based. */
    fun sample(
        sessionId: String, attemptSeq: Long, run: EvaluationRun, sampleUptimeMs: Long,
        invalidReason: EvaluationInvalidReason?, model: Pair<Double, Double>?, cursor: Pair<Double, Double>?,
        inferenceDurationNanos: Long?,
    ): List<String> {
        val p = run.presentationAt(sampleUptimeMs)
        return listOf(
            EVALUATION_SCHEMA_VERSION.toString(), sessionId, attemptSeq.toString(), sampleUptimeMs.toString(),
            (sampleUptimeMs - run.startMs).toString(), run.mode.id, run.phaseAt(sampleUptimeMs)?.id ?: "",
            p?.pass?.toString() ?: "", p?.trialIndex?.toString() ?: "", p?.indexInPass?.toString() ?: "",
            p?.target?.id ?: "", number(p?.target?.x), number(p?.target?.y),
            (model != null).toString(), invalidReason?.id ?: "",
            number(model?.first), number(model?.second), number(cursor?.first), number(cursor?.second),
            inferenceDurationNanos?.let { String.format(Locale.ROOT, "%.3f", it / 1e6) } ?: "",
        )
    }
    private fun number(v: Double?) = v?.toString() ?: ""
}

/** Minimal JSON writer: org.json is unavailable in JVM unit tests. */
object EvaluationJson {
    fun write(v: Any?, indent: String = ""): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) ->
            "$indent  ${quote(k.toString())}: ${write(x, "$indent  ")}"
        }
        is List<*> -> v.joinToString(", ", "[", "]") { write(it, indent) }
        else -> quote(v.toString())
    }
    fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}

/**
 * One inference attempt accepted by the cadence limiter during a run. Created on the analyzer
 * thread with the frame's sample time; recorded exactly once, from whichever thread finishes it.
 */
class EvaluationAttempt internal constructor(
    private val session: EvaluationSession, val seq: Long, val sampleUptimeMs: Long,
) {
    @Volatile var inferenceDurationNanos: Long? = null
    @Volatile var workerInvalidReason: EvaluationInvalidReason? = null
    private val recorded = AtomicBoolean(false)

    fun record(modelLoaded: Boolean, prediction: Pair<Double, Double>?, cursor: Pair<Double, Double>?) {
        val reason = evaluationInvalidReason(workerInvalidReason, modelLoaded, prediction)
        if (reason == null && prediction != null && cursor != null) {
            if (recorded.compareAndSet(false, true)) session.record(this, null, prediction, cursor)
        } else recordInvalid(reason ?: EvaluationInvalidReason.MODEL_REJECTED)
    }
    fun recordInvalid(reason: EvaluationInvalidReason) {
        if (recorded.compareAndSet(false, true)) session.record(this, reason, null, null)
    }
}

/**
 * Logs one evaluation run to `dir/samples.csv` and `dir/metadata.json`. Counters are guarded by
 * a short lock; all file I/O runs on [writer]. Metadata is written as incomplete at open and
 * replaced once at finalization, so an interrupted process leaves an incomplete session behind.
 */
class EvaluationSession(
    val id: String,
    val run: EvaluationRun,
    val dir: File,
    private val info: EvaluationSessionInfo,
    private val writer: Executor,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val wallClockMs: () -> Long,
    private val onLoggingFailed: (EvaluationSession, String) -> Unit,
    private val maxPendingRows: Int = 1024,
) {
    val samplesFile get() = File(dir, "samples.csv")
    val metadataFile get() = File(dir, "metadata.json")
    private val startWallMs = wallClockMs()

    private val lock = Any()
    // Guarded by lock.
    private var finishing = false
    private var finalized = false
    private var inFlight = 0
    private var nextSeq = 0L
    private var framesReceived = 0L
    private var framesCadenceSkipped = 0L
    private var framesIgnoredClosing = 0L
    private var validPredictions = 0L
    private val invalidByReason = EvaluationInvalidReason.entries.associateWith { 0L }.toMutableMap()
    private var rowsEnqueued = 0L
    private var rowsDroppedQueueFull = 0L
    private var endReason: EvaluationEndReason? = null
    private var endUptimeMs: Long? = null
    private var endWallMs: Long? = null
    private val pendingWrites = AtomicInteger()

    // Writer thread only.
    private var opened = false
    private var csv: BufferedWriter? = null
    private var broken = false
    private var rowsWritten = 0L
    private var rowsDroppedWriteError = 0L

    /** Creates a new directory; an existing one is never reused or overwritten. */
    fun open() = writer.execute {
        try {
            if (!dir.mkdirs()) throw IOException("cannot create new session directory ${dir.name}")
            opened = true
            csv = samplesFile.bufferedWriter().apply { write(EvaluationCsv.line(EvaluationCsv.HEADER)); write("\n"); flush() }
            writeMetadata(null)
        } catch (e: Exception) {
            broken = true
            onLoggingFailed(this, e.message ?: e.javaClass.simpleName)
        }
    }

    fun covers(now: Long) = now >= run.startMs && now < run.startMs + EvaluationProtocol.TOTAL_MS

    fun frameReceived() { synchronized(lock) { if (!finishing) framesReceived++ } }
    fun frameSkipped(closing: Boolean) {
        synchronized(lock) { if (!finishing) { if (closing) framesIgnoredClosing++ else framesCadenceSkipped++ } }
    }
    fun beginAttempt(sampleUptimeMs: Long): EvaluationAttempt? = synchronized(lock) {
        if (finishing) null else { inFlight++; EvaluationAttempt(this, nextSeq++, sampleUptimeMs) }
    }

    internal fun record(
        a: EvaluationAttempt, reason: EvaluationInvalidReason?, model: Pair<Double, Double>?, cursor: Pair<Double, Double>?,
    ) {
        val line = EvaluationCsv.line(EvaluationCsv.sample(id, a.seq, run, a.sampleUptimeMs, reason, model, cursor, a.inferenceDurationNanos))
        val lastInFlight = synchronized(lock) {
            // Too late: finalization already counted this attempt as unrecorded.
            if (finalized) return
            inFlight--
            if (reason == null) validPredictions++ else invalidByReason[reason] = invalidByReason.getValue(reason) + 1
            if (pendingWrites.get() >= maxPendingRows) rowsDroppedQueueFull++
            else {
                rowsEnqueued++; pendingWrites.incrementAndGet()
                writer.execute { writeRow(line) }
            }
            finishing && inFlight == 0
        }
        if (lastInFlight) finalizeNow()
    }

    /** Idempotent: only the first call sets the end reason. Waits briefly for in-flight attempts. */
    fun finish(reason: EvaluationEndReason, endUptimeMs: Long) {
        val wait = synchronized(lock) {
            if (finishing) return
            finishing = true; endReason = reason; this.endUptimeMs = endUptimeMs; endWallMs = wallClockMs()
            inFlight > 0
        }
        if (wait) schedule(IN_FLIGHT_GRACE_MS) { finalizeNow() } else finalizeNow()
    }

    private fun finalizeNow() {
        val snapshot = synchronized(lock) {
            if (finalized) return
            finalized = true
            snapshot(attemptsUnrecorded = inFlight.toLong())
        }
        writer.execute {
            try { csv?.close() } catch (e: IOException) { broken = true }
            csv = null
            // Never write into a directory this session did not create.
            if (opened) try { writeMetadata(snapshot) } catch (e: Exception) { broken = true }
        }
    }

    private fun writeRow(line: String) {
        pendingWrites.decrementAndGet()
        val out = csv
        if (broken || out == null) { rowsDroppedWriteError++; return }
        try {
            out.write(line); out.write("\n"); out.flush()
            rowsWritten++
        } catch (e: IOException) {
            broken = true; rowsDroppedWriteError++
            onLoggingFailed(this, e.message ?: "write failed")
        }
    }

    private class Snapshot(
        val endReason: EvaluationEndReason?, val endUptimeMs: Long?, val endWallMs: Long?,
        val counters: Map<String, Any?>, val rowsEnqueued: Long, val rowsDroppedQueueFull: Long, val attemptsUnrecorded: Long,
    )

    private fun snapshot(attemptsUnrecorded: Long): Snapshot {
        val invalid = invalidByReason.values.sum()
        return Snapshot(endReason, endUptimeMs, endWallMs, mapOf(
            "frames_received" to framesReceived,
            "frames_cadence_skipped" to framesCadenceSkipped,
            "frames_ignored_service_closing" to framesIgnoredClosing,
            "inference_attempts" to nextSeq,
            "valid_predictions" to validPredictions,
            "invalid_predictions" to invalid,
            "invalid_by_reason" to invalidByReason.entries.associate { (r, n) -> r.id to n },
            "attempts_unrecorded" to attemptsUnrecorded,
        ), rowsEnqueued, rowsDroppedQueueFull, attemptsUnrecorded)
    }

    /** Called on the writer thread. A null snapshot means the run is still in progress. */
    private fun writeMetadata(s: Snapshot?) {
        val reason = s?.endReason
        val endUptime = s?.endUptimeMs
        val plannedEnd = run.startMs + EvaluationProtocol.TOTAL_MS
        val completedPresentations = endUptime?.let {
            ((minOf(it, plannedEnd) - run.startMs) / EvaluationProtocol.TARGET_MS).coerceIn(0L, EvaluationProtocol.presentations.size.toLong())
        }
        val g = run.geometry
        val metadata = mapOf(
            "schema_version" to EVALUATION_SCHEMA_VERSION,
            "session_id" to id,
            "mode" to run.mode.id,
            "final_state" to if (reason == EvaluationEndReason.COMPLETED) "complete" else "incomplete",
            "end_reason" to (reason?.id ?: "in_progress_or_interrupted"),
            "start_wall_time_utc" to Instant.ofEpochMilli(startWallMs).toString(),
            "end_wall_time_utc" to s?.endWallMs?.let { Instant.ofEpochMilli(it).toString() },
            "start_uptime_ms" to run.startMs,
            "end_uptime_ms" to endUptime,
            "planned_end_uptime_ms" to plannedEnd,
            "protocol" to mapOf(
                "grid_norm" to EvaluationProtocol.GRID,
                "target_ms" to EvaluationProtocol.TARGET_MS,
                "settling_ms" to EvaluationProtocol.SETTLING_MS,
                "measurement_ms" to EvaluationProtocol.TARGET_MS - EvaluationProtocol.SETTLING_MS,
                "presentations_planned" to EvaluationProtocol.presentations.size,
                "presentations_completed" to completedPresentations,
                "trial_index_base" to 0,
                "index_in_pass_base" to 0,
                "presentation_target_ids" to EvaluationProtocol.presentations.map { it.target.id },
            ),
            "device" to mapOf("manufacturer" to info.manufacturer, "model" to info.model, "api_level" to info.apiLevel),
            "app" to mapOf(
                "version_name" to info.appVersionName, "version_code" to info.appVersionCode,
                "source_revision" to null, "working_tree_dirty" to null,
                "source_revision_note" to "not recorded on device; supply manually during analysis",
            ),
            "geometry" to mapOf(
                "basis" to info.geometryBasis,
                "basis_width_px" to g.basisWidth, "basis_height_px" to g.basisHeight,
                "evaluation_view_left_px" to g.viewLeft, "evaluation_view_top_px" to g.viewTop,
                "evaluation_view_width_px" to g.viewWidth, "evaluation_view_height_px" to g.viewHeight,
                "overlay_width_px" to g.overlaySize?.first, "overlay_height_px" to g.overlaySize?.second,
            ),
            "coordinates" to mapOf(
                "frame" to "gaze model display-normalized [0,1], same as calibration and evaluation targets",
                "model_output" to "existing GazeModel.predict result, already clamped to [0,1], before smoothing",
                "cursor" to "existing post-smoothing cursor; drawn in live_preview, withheld from the overlay in blind_measurement",
                "sample_time" to "SystemClock.uptimeMillis() when the analyzer accepted the frame for inference",
                "inference_duration" to "wall time of the FaceLandmarker.detectForVideo call",
            ),
            "counters" to s?.counters,
            "logging" to s?.let {
                mapOf(
                    "rows_enqueued" to it.rowsEnqueued,
                    "rows_written" to rowsWritten,
                    "rows_dropped_queue_full" to it.rowsDroppedQueueFull,
                    "rows_dropped_write_error" to rowsDroppedWriteError,
                    "rows_lost_total" to it.rowsDroppedQueueFull + rowsDroppedWriteError + it.attemptsUnrecorded,
                    "write_error" to broken,
                )
            },
        )
        val tmp = File(dir, "metadata.json.tmp")
        tmp.writeText(EvaluationJson.write(metadata) + "\n")
        try {
            Files.move(tmp.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object { const val IN_FLIGHT_GRACE_MS = 1000L }
}

/** The session the analyzer logs into. Written on the main thread, read on the analyzer thread. */
object EvaluationLog {
    @Volatile var current: EvaluationSession? = null
        private set

    /** Null outside an evaluation run, so normal gaze-control sessions are never logged. */
    fun sessionAt(now: Long) = current?.takeIf { it.covers(now) }
    fun start(session: EvaluationSession) { current = session }
    fun finish(reason: EvaluationEndReason, now: Long) {
        val session = current ?: return
        current = null
        session.finish(reason, now)
    }

    /** One low-priority thread for all evaluation file I/O. */
    val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "evaluation-log").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    fun newSessionId(wallMs: Long) = "eval-${ID_TIME.format(Instant.ofEpochMilli(wallMs))}-${UUID.randomUUID().toString().take(8)}"
}
