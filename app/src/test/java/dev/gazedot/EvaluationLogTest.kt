package dev.gazedot

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class EvaluationLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val geometry = EvaluationGeometry(1080, 2400, 0, 0, 1080, 2400, 1080 to 2400)
    private val info = EvaluationSessionInfo("samsung", "SM-S918N", 35, "0.1.0", 1, "max_window_metrics")
    private val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 10_000, geometry)
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val failures = mutableListOf<String>()
    private var writerCalls = 0
    private val direct = Executor { writerCalls++; it.run() }

    @After fun cleanup() = EvaluationLog.finish(EvaluationEndReason.BACK, 0)

    private fun session(
        dir: File = File(tmp.root, "evaluation/s1"), writer: Executor = direct, maxPending: Int = 1024,
        r: EvaluationRun = run,
    ) = EvaluationSession("s1", r, dir, info, writer, { d, b -> scheduled += d to b }, { 1_761_200_000_000L },
        { _, e -> failures += e }, maxPending)

    private fun rows(s: EvaluationSession) = s.samplesFile.readLines().drop(1).map { it.split(",") }
    private fun col(row: List<String>, name: String) = row[EvaluationCsv.HEADER.indexOf(name)]
    private fun metadata(s: EvaluationSession) = s.metadataFile.readText()

    @Test fun headerIsExactAndEscapingFollowsCsvRules() {
        assertEquals(
            "schema_version,session_id,attempt_seq,sample_uptime_ms,elapsed_ms,mode,phase,pass_number,trial_index," +
                "index_in_pass,target_id,target_x_norm,target_y_norm,prediction_valid,invalid_reason,model_x_norm," +
                "model_y_norm,cursor_x_norm,cursor_y_norm,inference_duration_ms",
            EvaluationCsv.line(EvaluationCsv.HEADER))
        assertEquals("plain", EvaluationCsv.escape("plain"))
        assertEquals("\"a,b\"", EvaluationCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", EvaluationCsv.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", EvaluationCsv.escape("two\nlines"))
        assertEquals("a,\"b,c\",", EvaluationCsv.line(listOf("a", "b,c", "")))
    }

    @Test fun rowsSerializeValidAndMissingValues() {
        val valid = EvaluationCsv.sample("s1", 7, run, 11_200, null, .41 to .62, .40 to .6, 12_345_678)
        assertEquals(EvaluationCsv.HEADER.size, valid.size)
        assertEquals(listOf("1", "s1", "7", "11200", "1200", "blind_measurement", "measurement", "1", "0", "0", "r0c0",
            "0.2", "0.2", "true", "", "0.41", "0.62", "0.4", "0.6", "12.346"), valid)
        val invalid = EvaluationCsv.sample("s1", 8, run, 11_300, EvaluationInvalidReason.NO_FACE, null, null, null)
        assertEquals("false", col(invalid, "prediction_valid"))
        assertEquals("no_face", col(invalid, "invalid_reason"))
        for (name in listOf("model_x_norm", "model_y_norm", "cursor_x_norm", "cursor_y_norm", "inference_duration_ms"))
            assertEquals("missing $name must be empty, not 0", "", col(invalid, name))
    }

    @Test fun samplesUseTheTargetAndPhaseActiveAtSampleTime() {
        fun at(t: Long) = EvaluationCsv.sample("s1", 0, run, t, null, .5 to .5, .5 to .5, null)
        val expect = mapOf(
            10_000L to listOf("0", "settling", "1", "0", "0", "r0c0"),
            10_999L to listOf("999", "settling", "1", "0", "0", "r0c0"),
            11_000L to listOf("1000", "measurement", "1", "0", "0", "r0c0"),
            12_499L to listOf("2499", "measurement", "1", "0", "0", "r0c0"),
            12_500L to listOf("2500", "settling", "1", "1", "1", "r0c1"),
            49_999L to listOf("39999", "measurement", "1", "15", "15", "r3c0"),
            50_000L to listOf("40000", "settling", "2", "16", "0", "r3c0"),
            89_999L to listOf("79999", "measurement", "2", "31", "15", "r0c0"),
        )
        for ((t, e) in expect) {
            val row = at(t)
            assertEquals("t=$t", e, listOf("elapsed_ms", "phase", "pass_number", "trial_index", "index_in_pass", "target_id").map { col(row, it) })
        }
        val s = session()
        assertFalse(s.covers(9_999)); assertTrue(s.covers(10_000)); assertTrue(s.covers(89_999)); assertFalse(s.covers(90_000))
    }

    @Test fun invalidReasonsOnlyUseDistinguishableStates() {
        assertNull(evaluationInvalidReason(null, true, .5 to .5))
        assertEquals(EvaluationInvalidReason.NO_FACE, evaluationInvalidReason(EvaluationInvalidReason.NO_FACE, true, null))
        assertEquals(EvaluationInvalidReason.FEATURES_UNAVAILABLE, evaluationInvalidReason(EvaluationInvalidReason.FEATURES_UNAVAILABLE, false, null))
        assertEquals(EvaluationInvalidReason.NO_CALIBRATION_MODEL, evaluationInvalidReason(null, false, null))
        assertEquals(EvaluationInvalidReason.MODEL_REJECTED, evaluationInvalidReason(null, true, null))
    }

    @Test fun completedSessionWritesRowsCountersAndFinalMetadata() {
        val s = session().apply { open() }
        assertTrue(metadata(s).contains("\"final_state\": \"incomplete\""))
        assertTrue(metadata(s).contains("\"end_reason\": \"in_progress_or_interrupted\""))
        repeat(3) { s.frameReceived() }
        s.frameSkipped(closing = false)
        s.beginAttempt(11_000)!!.apply { inferenceDurationNanos = 20_000_000 }.record(true, .21 to .19, .22 to .2)
        s.beginAttempt(11_066)!!.apply { workerInvalidReason = EvaluationInvalidReason.NO_FACE }.record(true, null, null)
        s.beginAttempt(11_132)!!.record(true, null, null)
        s.beginAttempt(11_198)!!.recordInvalid(EvaluationInvalidReason.PROCESSING_ERROR)
        s.finish(EvaluationEndReason.COMPLETED, 90_016)

        val r = rows(s)
        assertEquals(4, r.size)
        assertEquals(listOf("true", "false", "false", "false"), r.map { col(it, "prediction_valid") })
        assertEquals(listOf("", "no_face", "model_rejected", "processing_error"), r.map { col(it, "invalid_reason") })
        assertEquals("20.000", col(r[0], "inference_duration_ms"))
        val m = metadata(s)
        for (fragment in listOf(
            "\"final_state\": \"complete\"", "\"end_reason\": \"completed\"", "\"mode\": \"blind_measurement\"",
            "\"presentations_completed\": 32", "\"frames_received\": 3", "\"frames_cadence_skipped\": 1",
            "\"inference_attempts\": 4", "\"valid_predictions\": 1", "\"invalid_predictions\": 3",
            "\"no_face\": 1", "\"model_rejected\": 1", "\"processing_error\": 1", "\"attempts_unrecorded\": 0",
            "\"rows_written\": 4", "\"rows_lost_total\": 0", "\"model\": \"SM-S918N\"", "\"overlay_height_px\": 2400",
            "\"source_revision\": null",
        )) assertTrue("missing $fragment in\n$m", m.contains(fragment))
        assertFalse(File(s.dir, "metadata.json.tmp").exists())
        assertTrue(failures.isEmpty())
    }

    @Test fun finishIsIdempotentAndStopsRecording() {
        val s = session().apply { open() }
        s.beginAttempt(11_000)!!.record(true, .5 to .5, .5 to .5)
        s.finish(EvaluationEndReason.BACK, 20_000)
        val callsAfterFirstFinish = writerCalls
        s.finish(EvaluationEndReason.COMPLETED, 90_000)
        s.finish(EvaluationEndReason.ON_PAUSE, 90_001)
        assertEquals("second finish must not schedule another finalization", callsAfterFirstFinish, writerCalls)
        assertNull(s.beginAttempt(21_000))
        s.frameReceived()
        val m = metadata(s)
        assertTrue(m.contains("\"end_reason\": \"back\"")); assertTrue(m.contains("\"final_state\": \"incomplete\""))
        // Ended 10,000 ms after start: four full 2,500 ms presentations.
        assertTrue(m.contains("\"presentations_completed\": 4")); assertTrue(m.contains("\"frames_received\": 0"))
        assertEquals(1, rows(s).size)
        assertTrue(scheduled.isEmpty())
    }

    @Test fun inFlightAttemptIsWaitedForThenFinalizesOnce() {
        val s = session().apply { open() }
        val pending = s.beginAttempt(12_000)!!
        s.finish(EvaluationEndReason.ON_PAUSE, 12_010)
        assertEquals(EvaluationSession.IN_FLIGHT_GRACE_MS, scheduled.single().first)
        assertTrue("not finalized while an attempt is in flight", metadata(s).contains("in_progress_or_interrupted"))
        pending.record(true, .4 to .4, .4 to .4)
        assertTrue(metadata(s).contains("\"end_reason\": \"background_on_pause\""))
        val calls = writerCalls
        scheduled.single().second()
        assertEquals("grace timeout after finalization is a no-op", calls, writerCalls)
        assertTrue(metadata(s).contains("\"attempts_unrecorded\": 0"))
        assertEquals(1, rows(s).size)
    }

    @Test fun unrecordedInFlightAttemptIsReportedNotSilentlyDropped() {
        val s = session().apply { open() }
        val lost = s.beginAttempt(12_000)!!
        s.finish(EvaluationEndReason.SERVICE_STOPPED, 12_010)
        scheduled.single().second()
        lost.record(true, .4 to .4, .4 to .4)
        val m = metadata(s)
        assertTrue(m.contains("\"end_reason\": \"service_stopped\""))
        assertTrue(m.contains("\"inference_attempts\": 1")); assertTrue(m.contains("\"attempts_unrecorded\": 1"))
        assertTrue(m.contains("\"rows_lost_total\": 1"))
        assertEquals(0, rows(s).size)
    }

    @Test fun eachAttemptIsRecordedOnlyOnce() {
        val s = session().apply { open() }
        val a = s.beginAttempt(11_000)!!
        a.recordInvalid(EvaluationInvalidReason.PROCESSING_ERROR)
        a.record(true, .5 to .5, .5 to .5)
        a.recordInvalid(EvaluationInvalidReason.SERVICE_STOPPING)
        s.finish(EvaluationEndReason.SERVICE_STOPPED, 11_100)
        assertEquals(listOf("processing_error"), rows(s).map { col(it, "invalid_reason") })
    }

    @Test fun backlogBeyondLimitIsCountedAsLoss() {
        val queued = mutableListOf<Runnable>()
        val s = session(writer = Executor { queued += it }, maxPending = 2)
        s.open()
        repeat(3) { s.beginAttempt(11_000L + it)!!.record(true, .5 to .5, .5 to .5) }
        s.finish(EvaluationEndReason.COMPLETED, 90_000)
        queued.forEach { it.run() }
        val m = metadata(s)
        assertTrue(m.contains("\"rows_enqueued\": 2")); assertTrue(m.contains("\"rows_dropped_queue_full\": 1"))
        assertTrue(m.contains("\"rows_written\": 2")); assertTrue(m.contains("\"rows_lost_total\": 1"))
        assertTrue(m.contains("\"valid_predictions\": 3"))
    }

    @Test fun existingSessionDirectoryIsNeverOverwritten() {
        val dir = File(tmp.root, "evaluation/s1").apply { mkdirs() }
        File(dir, "metadata.json").writeText("earlier run")
        val s = session(dir)
        s.open()
        assertEquals(1, failures.size)
        s.beginAttempt(11_000)!!.record(true, .5 to .5, .5 to .5)
        s.finish(EvaluationEndReason.LOGGING_FAILED, 11_100)
        assertEquals("earlier run", File(dir, "metadata.json").readText())
        assertFalse(File(dir, "samples.csv").exists())
    }

    @Test fun nothingIsLoggedWhenEvaluationIsInactive() {
        assertNull(EvaluationLog.current)
        assertNull(EvaluationLog.sessionAt(11_000))
        val s = session()
        EvaluationLog.start(s)
        assertSame(s, EvaluationLog.sessionAt(11_000))
        assertNull("before the run clock", EvaluationLog.sessionAt(9_999))
        assertNull("after the planned end", EvaluationLog.sessionAt(90_000))
        EvaluationLog.finish(EvaluationEndReason.BACK, 12_000)
        assertNull(EvaluationLog.current)
        assertNull(EvaluationLog.sessionAt(11_000))
        EvaluationLog.finish(EvaluationEndReason.COMPLETED, 13_000)
        assertFalse("session directory is only created by open()", s.dir.exists())
    }

    @Test fun sessionIdsAreUniqueAndTimestamped() {
        val ids = List(200) { EvaluationLog.newSessionId(1_761_200_000_000L) }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids[0], ids[0].matches(Regex("eval-20251023T061320Z-[0-9a-f]{8}")))
    }

    @Test fun jsonEscapesStringsAndKeepsNulls() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", EvaluationJson.quote("a\"b\\c\nd\u0001"))
        assertEquals("{\n  \"x\": null,\n  \"y\": [1, 2.5]\n}", EvaluationJson.write(mapOf("x" to null, "y" to listOf(1, 2.5))))
        assertEquals("null", EvaluationJson.write(Double.NaN))
    }
}
