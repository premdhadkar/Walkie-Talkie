package com.example.wifidirectwalkietalkie

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class AudioStreamer(private val context: Context, private val coroutineScope: CoroutineScope) {
    private val sampleRate = 16000
    private val channelConfigRecord = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigPlay = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigRecord, audioFormat)

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var receiveJob: Job? = null
    private var sendJob: Job? = null
    private var isRecording = false

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    fun startServer() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(8888)
                socket = serverSocket?.accept()
                setupStreams(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startClient(hostAddress: InetAddress) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                socket = Socket(hostAddress, 8888)
                setupStreams(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupStreams(socket: Socket?) {
        if (socket == null) return
        try {
            socket.soTimeout = 1000 // 1 second timeout to detect end of transmission
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()
            startReceivingAudio()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startReceivingAudio() {
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigPlay)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        receiveJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            var lastReceiveTime = 0L
            try {
                while (isActive) {
                    val readBytes = try {
                        inputStream?.read(buffer) ?: -1
                    } catch (e: java.net.SocketTimeoutException) {
                        0
                    }
                    if (readBytes > 0) {
                        _isReceiving.value = true
                        val currentTime = System.currentTimeMillis()
                        // If it's been more than 1 second since the last audio chunk, 
                        // treat it as a new transmission and play the roger sound.
                        if (currentTime - lastReceiveTime > 1000) {
                            withContext(Dispatchers.Main) {
                                try {
                                    val mediaPlayer = MediaPlayer.create(context, R.raw.roger_sound)
                                    mediaPlayer.setOnCompletionListener { it.release() }
                                    mediaPlayer.start()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        audioTrack?.write(buffer, 0, readBytes)
                        lastReceiveTime = System.currentTimeMillis()
                    } else if (readBytes == 0) {
                        if (System.currentTimeMillis() - lastReceiveTime >= 1000) {
                            _isReceiving.value = false
                        }
                    } else {
                        _isReceiving.value = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isReceiving.value = false
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked in UI
    fun startRecording() {
        if (isRecording || outputStream == null) return
        isRecording = true

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfigRecord,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()

        sendJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        outputStream?.write(buffer, 0, readBytes)
                        outputStream?.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    stopRecording()
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        sendJob?.cancel()
        sendJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    fun disconnect() {
        receiveJob?.cancel()
        sendJob?.cancel()
        stopRecording()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null

        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        socket = null
        serverSocket = null
        inputStream = null
        outputStream = null
    }
}
