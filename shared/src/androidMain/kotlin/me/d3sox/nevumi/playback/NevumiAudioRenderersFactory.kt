package me.d3sox.nevumi.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

private val flatEffects = AudioEffects()

@UnstableApi
class NevumiAudioRenderersFactory(context: Context, private val outputMonitor: AudioProcessor? = null,
    private val effects: () -> AudioEffects = { flatEffects }, private val energyMonitor: AudioProcessor? = null) :
    DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink {
        val processing = DefaultAudioSink.DefaultAudioProcessorChain(*listOfNotNull(EffectsAudioProcessor(effects), energyMonitor).toTypedArray())
        val visualizer = VisualizerAudioProcessor()
        val chain = object : AudioProcessorChain by processing {
            override fun getAudioProcessors(): Array<AudioProcessor> = processing.audioProcessors + listOfNotNull(outputMonitor, visualizer)
        }
        return DefaultAudioSink.Builder(context).setAudioProcessorChain(chain)
            .setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(false).build()
    }
}
