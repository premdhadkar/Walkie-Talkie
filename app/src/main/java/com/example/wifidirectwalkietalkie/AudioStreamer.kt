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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class AudioStreamer(private val context: Context, private val coroutineScope: CoroutineScope) {
    private val sampleRate = 16000
    private val channelConfigRecord = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigPlay = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigRecord, audioFormat)

    private var serverSocket: ServerSocket? = null
    
    // Active connections (can be multiple if this device is Group Owner)
    private val activeConnections = CopyOnWriteArrayList<PeerConnection>()

    private var sendJob: Job? = null
    private var isRecording = false

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    enum class SignalQuality { EXCELLENT, GOOD, POOR, DISCONNECTED }
    private val _signalQuality = MutableStateFlow(SignalQuality.DISCONNECTED)
    val signalQuality: StateFlow<SignalQuality> = _signalQuality.asStateFlow()

    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var currentBufferSize = 0
    private val maxBufferSizeBytes = 16000 * 2 * 2 // ~2 seconds of 16-bit mono 16kHz audio

    // Update global state based on all active connections
    private fun updateGlobalState() {
        if (activeConnections.isEmpty()) {
            _isReceiving.value = false
            _signalQuality.value = SignalQuality.DISCONNECTED
            return
        }
        
        var anyReceiving = false
        var bestQuality = SignalQuality.DISCONNECTED
        
        for (conn in activeConnections) {
            if (conn.isReceiving) anyReceiving = true
            
            val q = conn.quality
            if (q == SignalQuality.EXCELLENT) {
                bestQuality = SignalQuality.EXCELLENT
            } else if (q == SignalQuality.GOOD && bestQuality != SignalQuality.EXCELLENT) {
                bestQuality = SignalQuality.GOOD
            } else if (q == SignalQuality.POOR && bestQuality == SignalQuality.DISCONNECTED) {
                bestQuality = SignalQuality.POOR
            }
        }
        
        _isReceiving.value = anyReceiving
        _signalQuality.value = bestQuality
    }

    inner class PeerConnection(val socket: Socket) {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var receiveJob: Job? = null
        var pingJob: Job? = null
        var isReceiving = false
        var quality = SignalQuality.DISCONNECTED

        init {
            try {
                socket.soTimeout = 1000
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun start() {
            startPingJob(socket.inetAddress)
            startReceivingAudio()
            flushAudioBuffer(this)
        }

        private fun startPingJob(address: InetAddress) {
            pingJob?.cancel()
            pingJob = coroutineScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val start = System.currentTimeMillis()
                        val reachable = address.isReachable(1000)
                        val latency = System.currentTimeMillis() - start
                        
                        if (reachable) {
                            quality = when {
                                latency < 100 -> SignalQuality.EXCELLENT
                                latency < 300 -> SignalQuality.GOOD
                                else -> SignalQuality.POOR
                            }
                        } else {
                            quality = SignalQuality.POOR
                        }
                    } catch (e: Exception) {
                        quality = SignalQuality.POOR
                    }
                    updateGlobalState()
                    delay(1000)
                }
            }
        }

        private fun startReceivingAudio() {
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
                            isReceiving = true
                            updateGlobalState()
                            
                            val currentTime = System.currentTimeMillis()
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
                            lastReceiveTime = System.currentTimeMillis()
                            
                            val chunk = buffer.copyOf(readBytes)
                            // Play locally
                            audioTrack?.write(chunk, 0, chunk.size)
                            // Forward to other clients (Mesh Routing)
                            broadcastAudio(chunk, this@PeerConnection)
                            
                        } else if (readBytes == 0) {
                            if (System.currentTimeMillis() - lastReceiveTime >= 1000) {
                                isReceiving = false
                                updateGlobalState()
                            }
                        } else {
                            isReceiving = false
                            updateGlobalState()
                            // Connection lost
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    isReceiving = false
                    updateGlobalState()
                } finally {
                    disconnect()
                }
            }
        }

        fun disconnect() {
            receiveJob?.cancel()
            pingJob?.cancel()
            try {
                inputStream?.close()
                outputStream?.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            activeConnections.remove(this)
            updateGlobalState()
        }
    }

    private fun initAudioTrack() {
        if (audioTrack == null) {
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
        }
        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.play()
        }
    }

    fun startServer() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(8888)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    initAudioTrack()
                    val connection = PeerConnection(socket)
                    activeConnections.add(connection)
                    connection.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startClient(hostAddress: InetAddress) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(hostAddress, 8888)
                initAudioTrack()
                val connection = PeerConnection(socket)
                activeConnections.add(connection)
                connection.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked in UI
    fun startRecording() {
        if (isRecording) return
        isRecording = true

        if (audioRecord == null) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfigRecord,
                audioFormat,
                bufferSize
            )
        }

        audioRecord?.startRecording()

        sendJob?.cancel()
        sendJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val chunk = buffer.copyOf(readBytes)
                        broadcastAudio(chunk, null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun broadcastAudio(chunk: ByteArray, sender: PeerConnection?) {
        if (activeConnections.isEmpty()) {
            if (sender == null) { // Local recording, no connections
                enqueueAudio(chunk)
            }
            return
        }

        for (conn in activeConnections) {
            if (conn != sender) {
                try {
                    // Send to this connection
                    conn.outputStream?.write(chunk)
                    conn.outputStream?.flush()
                } catch (e: Exception) {
                    // Socket broken, disconnect will be handled in receive loop
                }
            }
        }
    }

    private fun enqueueAudio(chunk: ByteArray) {
        audioBuffer.add(chunk)
        currentBufferSize += chunk.size
        while (currentBufferSize > maxBufferSizeBytes && audioBuffer.isNotEmpty()) {
            val removed = audioBuffer.poll()
            if (removed != null) currentBufferSize -= removed.size
        }
    }

    private fun flushAudioBuffer(connection: PeerConnection) {
        while (audioBuffer.isNotEmpty()) {
            val chunk = audioBuffer.poll()
            if (chunk != null) {
                try {
                    connection.outputStream?.write(chunk)
                } catch (e: Exception) {
                    // Ignore, let receive loop handle it
                }
                currentBufferSize -= chunk.size
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        sendJob?.cancel()
        sendJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioBuffer.clear()
        currentBufferSize = 0
    }

    fun disconnect() {
        isRecording = false
        _signalQuality.value = SignalQuality.DISCONNECTED
        _isReceiving.value = false
        
        sendJob?.cancel()
        sendJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        audioBuffer.clear()
        currentBufferSize = 0

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null

        val connectionsToClose = activeConnections.toList()
        for (conn in connectionsToClose) {
            conn.disconnect()
        }
        activeConnections.clear()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }
}
