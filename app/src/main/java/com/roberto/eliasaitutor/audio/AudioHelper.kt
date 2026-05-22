package com.roberto.eliasaitutor.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

class AudioHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    var currentRecordFile: File? = null

    fun startRecording() {
        val file = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
        currentRecordFile = file
        
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    fun stopRecording(): File? {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recorder = null
        return currentRecordFile
    }

    fun playAudio(audioBytes: ByteArray, onCompletion: () -> Unit = {}) {
        try {
            // Use a unique file name to avoid collisions
            val file = File(context.cacheDir, "play_${System.currentTimeMillis()}.mp3")
            file.writeBytes(audioBytes)

            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { 
                    it.release()
                    onCompletion()
                    // Delete temp file after playing
                    file.delete()
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlaying() {
        player?.release()
        player = null
    }
}
