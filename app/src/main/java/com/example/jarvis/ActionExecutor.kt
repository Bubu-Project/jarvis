package com.example.jarvis

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioRecorderHelper {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val handler = Handler(Looper.getMainLooper())
    private val SILENCE_THRESHOLD = 600.0
    private val SILENCE_DURATION_MS = 1500L

    fun startRecording(outputFile: File, onSilenceDetected: () -> Unit) {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        audioRecord?.startRecording()
        isRecording = true

        Thread {
            val buffer = ByteArray(bufferSize)
            var silenceStartTime = 0L
            var hasSpoken = false
            val outputStream = FileOutputStream(outputFile)
            val dataSize = ArrayList<Byte>() // Raw data buffer

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    for (i in 0 until read) dataSize.add(buffer[i])
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                        sum += sample.toDouble() * sample.toDouble()
                    }
                    val rms = sqrt(sum / (read / 2))

                    if (rms > SILENCE_THRESHOLD) {
                        hasSpoken = true
                        silenceStartTime = 0
                    } else if (hasSpoken) {
                        if (silenceStartTime == 0L) silenceStartTime = System.currentTimeMillis()
                        else if (System.currentTimeMillis() - silenceStartTime > SILENCE_DURATION_MS) break
                    }
                }
            }
            stopRecording()
            outputStream.write(createWavHeader(dataSize.size)) // Add WAV Header
            outputStream.write(dataSize.toByteArray())
            outputStream.close()
            handler.post { onSilenceDetected() }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // 🔥 WAV Header banane ka function (taaki server audio padh sake)
    private fun createWavHeader(dataLength: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * 2
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        val fileSize = dataLength + 36
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(16)
        ByteBuffer.wrap(header, 20, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(1)
        ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(1)
        ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate)
        ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate)
        ByteBuffer.wrap(header, 32, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(2)
        ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataLength)
        return header
    }
}
