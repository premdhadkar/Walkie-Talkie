package com.example.wifidirectwalkietalkie

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiDirectManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val refreshedPeers = peerList.deviceList.toList()
        if (refreshedPeers != _peers.value) {
            _peers.value = refreshedPeers
        }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        _connectionInfo.value = info
        _isConnected.value = info.groupFormed
    }

    @SuppressLint("MissingPermission") // Caller must ensure NEARBY_WIFI_DEVICES is granted
    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Discovery started
            }

            override fun onFailure(reasonCode: Int) {
                // Discovery failed
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Connection initiated
            }

            override fun onFailure(reason: Int) {
                // Connection failed
            }
        })
    }
    
    fun disconnect() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isConnected.value = false
                _connectionInfo.value = null
            }

            override fun onFailure(reason: Int) {
                // Disconnect failed
            }
        })
    }

    fun requestPeers() {
        manager.requestPeers(channel, peerListListener)
    }

    fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel, connectionInfoListener)
    }

    fun setWifiP2pState(enabled: Boolean) {
        if (!enabled) {
            _peers.value = emptyList()
            _isConnected.value = false
        }
    }
}
