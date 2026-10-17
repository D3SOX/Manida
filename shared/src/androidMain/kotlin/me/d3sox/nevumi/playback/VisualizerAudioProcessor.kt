package me.d3sox.nevumi.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import me.d3sox.nevumi.visualizer.VisualizerPcm
import java.nio.ByteBuffer

@UnstableApi
internal class VisualizerAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        VisualizerPcm.write16(inputBuffer, inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }
}
