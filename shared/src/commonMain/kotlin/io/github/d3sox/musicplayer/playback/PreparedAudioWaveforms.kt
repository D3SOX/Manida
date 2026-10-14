package io.github.d3sox.musicplayer.playback

import io.github.d3sox.musicplayer.library.TrackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class WaveformPreparation(val total: Int = 0, val prepared: Int = 0, val failed: Int = 0,
    val running: Boolean = false, val currentSourceId: String? = null)

/** Bounded low-priority decoders share their persistent cache with Now Playing. */
class PreparedAudioWaveforms(private val reader: AudioWaveforms, private val idleDelayMs: Long = 1_000,
    private val parallelism: Int = waveformPreparationParallelism()) : AudioWaveforms, AutoCloseable {
    private data class Work(val sources: List<Pair<TrackSource, Long>>, val allowed: Boolean)
    private val work = MutableStateFlow(Work(emptyList(), false))
    private val foreground = MutableStateFlow(0)
    private class ManualRequest
    private val requested = MutableStateFlow<ManualRequest?>(null)
    private val paused = MutableStateFlow(false)
    private val progress = MutableStateFlow(WaveformPreparation())
    val preparation: StateFlow<WaveformPreparation> = progress.asStateFlow()
    init { require(parallelism in 1..8) { "Choose between one and eight waveform workers" } }
    private val thread = WaveformPreparationThread(parallelism)
    // Coordination must remain runnable while a native decoder occupies its dedicated thread.
    private val coordinator = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + coordinator + WaveformPreparationContext(thread.dispatcher))
    private val completed = mutableSetOf<TrackSource>()
    private val failed = mutableSetOf<TrackSource>()
    private data class Decoding(val source: TrackSource, val result: CompletableDeferred<List<Float>>)
    private val decoding = MutableStateFlow<Map<TrackSource, Decoding>>(emptyMap())
    private var lastManualRequest: ManualRequest? = null
    private val worker = scope.launch {
        combine(work, foreground, requested, paused) { pending, requests, manual, stopped ->
            Triple(pending, requests == 0 && !stopped && (pending.allowed || manual != null), manual)
        }.collectLatest { (pending, allowed, manual) ->
            if (manual != null && manual !== lastManualRequest) { failed.clear(); lastManualRequest = manual }
            // Check disk once per pass, then update counts in constant time as workers finish.
            for ((source, _) in pending.sources) {
                if (source !in completed && reader.cached(source)?.isNotEmpty() == true) completed += source
            }
            var preparedCount = pending.sources.count { it.first in completed }
            var failedCount = pending.sources.count { it.first in failed }
            fun publish(running: Boolean) {
                progress.value = WaveformPreparation(pending.sources.size, preparedCount, failedCount, running,
                    decoding.value.keys.firstOrNull()?.id)
            }
            publish(false)
            if (!allowed) return@collectLatest
            if (manual == null) delay(idleDelayMs)
            try {
                val remaining = pending.sources.filter { it.first !in completed && it.first !in failed }
                var next = 0
                coroutineScope {
                    repeat(minOf(parallelism, remaining.size)) {
                        launch {
                            while (true) {
                                ensureActive()
                                if (foreground.value > 0) break
                                // Coordination runs on one dispatcher; blocking decodes run in the pool.
                                val (source, duration) = remaining.getOrNull(next++) ?: break
                                val request = Decoding(source, CompletableDeferred())
                                decoding.value = decoding.value + (source to request)
                                publish(true)
                                try {
                                    val result = try { reader.load(source, duration) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { emptyList() }
                                    if (result.isNotEmpty()) { if (completed.add(source)) preparedCount++ }
                                    else if (failed.add(source)) failedCount++
                                    request.result.complete(result)
                                } finally {
                                    request.result.complete(emptyList())
                                    decoding.value = decoding.value - source
                                }
                                publish(true)
                                yield()
                            }
                        }
                    }
                }
            } finally { publish(false) }
            if (requested.value === manual) requested.value = null
        }
    }
    fun prepare(sources: List<Pair<TrackSource, Long>>, allowed: Boolean) { work.value = Work(sources.distinctBy { it.first }, allowed) }
    fun prepareAll() { paused.value = false; requested.value = ManualRequest() }
    fun pause() { paused.value = true; requested.value = null }
    override suspend fun cached(source: TrackSource) = reader.cached(source)
    override suspend fun load(source: TrackSource, durationMs: Long): List<Float> {
        reader.cached(source)?.let { return it }
        decoding.value[source]?.let { request ->
            request.result.await().takeIf { it.isNotEmpty() }?.let { return it }
        }
        var registered = false
        return try {
            withContext(coordinator) {
                foreground.update { it + 1 }
                registered = true
                // Let collectLatest cancel every native decoder before giving playback priority.
                decoding.first { it.isEmpty() }
            }
            reader.load(source, durationMs)
        } finally {
            if (registered) withContext(NonCancellable + coordinator) { foreground.update { it - 1 } }
        }
    }
    override fun close() { worker.cancel(); scope.cancel(); thread.close() }
}
