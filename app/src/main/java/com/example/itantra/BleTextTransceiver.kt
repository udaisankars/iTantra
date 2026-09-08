package com.example.itantra

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BleReceiver(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice,
    val lastSeenAt: Long
) {
    val id: String
        get() = address
}

class BleTextTransceiver(
    context: Context,
    deviceName: String
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(
            "8f1d0001-6f57-4f34-9c9d-6a5d7c6e1001"
        )

        private val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString(
            "8f1d0002-6f57-4f34-9c9d-6a5d7c6e1001"
        )

        // 0xFFFF is used only as a demo marker for the short iTantra name
        // placed in the BLE scan response. It is not a registered company ID.
        private const val MANUFACTURER_ID = 0xFFFF

        private const val FRAME_MAGIC = 0x4954
        private const val FRAME_VERSION = 1
        private const val FRAME_HEADER_BYTES = 15
        private const val ENVELOPE_VERSION = 1
        private const val GROUP_HASH_BYTES = 8
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val DEFAULT_ATT_MTU = 23
        private const val REQUESTED_ATT_MTU = 247
        private const val ATT_OVERHEAD_BYTES = 3
        private const val MAX_TEXT_BYTES = 8 * 1024
        private const val MAX_ENCRYPTED_BYTES = 16 * 1024
        private const val MAX_TOTAL_CHUNKS = 4_096
        private const val REASSEMBLY_EXPIRY_MS = 30_000L
        private const val CONNECTION_TIMEOUT_MS = 45_000L
        private const val MTU_FALLBACK_DELAY_MS = 1_500L
        private const val MAX_COMPLETED_IDS = 100
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(
        BluetoothManager::class.java
    )
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val secureRandom = SecureRandom()

    private val advertisedName = deviceName
        .filter { it.code in 32..126 && it != '|' }
        .trim()
        .ifBlank { "iTantra Receiver" }
        .take(18)

    private val receiverActive = AtomicBoolean(false)
    private val scanActive = AtomicBoolean(false)

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var advertiseCallback: AdvertiseCallback? = null

    @Volatile
    private var scanCallback: ScanCallback? = null

    @Volatile
    private var receiverGroupId: String = ""

    @Volatile
    private var receiverSharedSecret: String = ""

    @Volatile
    private var receiverStatusCallback: ((String) -> Unit)? = null

    @Volatile
    private var receiverMessageCallback: ((String) -> Unit)? = null

    private val reassemblyLock = Any()
    private val reassemblies = mutableMapOf<String, Reassembly>()
    private val completedMessageIds = linkedSetOf<String>()

    private val scanLock = Any()
    private val discoveredReceivers = linkedMapOf<String, BleReceiver>()

    private val sendLock = Any()
    private var activeSend: SendSession? = null

    fun isBleSupported(): Boolean = bluetoothAdapter != null

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun canAdvertise(): Boolean =
        bluetoothAdapter?.bluetoothLeAdvertiser != null

    /**
     * Receiver mode: starts an unpaired BLE GATT server and advertises the
     * iTantra service. The writable characteristic deliberately does not ask
     * Android for link-level pairing; message privacy is provided by AES-GCM.
     */
    @SuppressLint("MissingPermission")
    fun startReceiver(
        groupId: String,
        sharedSecret: String,
        onStatusChange: (String) -> Unit,
        onMessageReceived: (String) -> Unit
    ) {
        val cleanGroupId = groupId.trim()
        val cleanSecret = sharedSecret.trim()

        if (cleanGroupId.isEmpty() || cleanSecret.length < 16) {
            onStatusChange("Invalid iTantra group configuration")
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null) {
            onStatusChange("Bluetooth LE is not supported on this phone")
            return
        }

        if (!adapter.isEnabled) {
            onStatusChange("Turn on Bluetooth, then select Receiver again")
            return
        }

        if (adapter.bluetoothLeAdvertiser == null) {
            onStatusChange("This phone cannot advertise as a BLE receiver")
            return
        }

        stopScan()
        stopReceiver()

        receiverGroupId = cleanGroupId
        receiverSharedSecret = cleanSecret
        receiverStatusCallback = onStatusChange
        receiverMessageCallback = onMessageReceived
        receiverActive.set(true)

        synchronized(reassemblyLock) {
            reassemblies.clear()
            completedMessageIds.clear()
        }

        val server = bluetoothManager?.openGattServer(
            appContext,
            gattServerCallback
        )

        if (server == null) {
            receiverActive.set(false)
            onStatusChange("Unable to open Bluetooth receiver")
            return
        }

        gattServer = server

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)

        onStatusChange("Starting Bluetooth receiver...")

        if (!server.addService(service)) {
            stopReceiver()
            onStatusChange("Unable to register the iTantra BLE service")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopReceiver() {
        receiverActive.set(false)

        val adapter = bluetoothAdapter
        val callback = advertiseCallback

        if (adapter != null && callback != null) {
            try {
                adapter.bluetoothLeAdvertiser?.stopAdvertising(callback)
            } catch (_: Throwable) {
            }
        }

        advertiseCallback = null

        try {
            gattServer?.clearServices()
        } catch (_: Throwable) {
        }

        try {
            gattServer?.close()
        } catch (_: Throwable) {
        }

        gattServer = null
        receiverStatusCallback = null
        receiverMessageCallback = null

        synchronized(reassemblyLock) {
            reassemblies.clear()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(
        onStatusChange: (String) -> Unit,
        onReceiversChanged: (List<BleReceiver>) -> Unit
    ) {
        val adapter = bluetoothAdapter

        if (adapter == null) {
            onStatusChange("Bluetooth LE is not supported on this phone")
            return
        }

        if (!adapter.isEnabled) {
            onStatusChange("Turn on Bluetooth, then tap Search Again")
            return
        }

        val scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            onStatusChange("Bluetooth scanner is unavailable")
            return
        }

        stopReceiver()
        stopScan()

        synchronized(scanLock) {
            discoveredReceivers.clear()
        }
        onReceiversChanged(emptyList())

        val callback = object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                publishScanResult(result, onReceiversChanged)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach {
                    publishScanResult(it, onReceiversChanged)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanActive.set(false)
                postStatus(
                    onStatusChange,
                    "Bluetooth search failed: code $errorCode"
                )
            }
        }

        scanCallback = callback
        scanActive.set(true)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, callback)
            onStatusChange("Searching for nearby iTantra Bluetooth receivers...")
        } catch (error: Throwable) {
            scanActive.set(false)
            scanCallback = null
            onStatusChange(
                "Bluetooth search error: ${error.message ?: "Unknown error"}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanActive.set(false)

        val callback = scanCallback
        scanCallback = null

        if (callback != null) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Connects without Bluetooth bonding, encrypts the UTF-8 text with the
     * shared iTantra group secret, divides it according to the negotiated ATT
     * MTU, and performs acknowledged GATT writes in strict sequence.
     */
    @SuppressLint("MissingPermission")
    fun sendMessage(
        receiver: BleReceiver,
        groupId: String,
        sharedSecret: String,
        message: String,
        onComplete: (success: Boolean, status: String) -> Unit
    ) {
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) {
            onComplete(false, "There is no message to send")
            return
        }

        if (cleanMessage.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            onComplete(false, "Message is too large for iTantra BLE")
            return
        }

        if (groupId.isBlank() || sharedSecret.trim().length < 16) {
            onComplete(false, "Invalid iTantra group configuration")
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null || !adapter.isEnabled) {
            onComplete(false, "Turn on Bluetooth before sending")
            return
        }

        synchronized(sendLock) {
            if (activeSend != null) {
                onComplete(false, "Another Bluetooth message is being sent")
                return
            }
        }

        stopScan()

        val session = SendSession(
            receiver = receiver,
            groupId = groupId.trim(),
            sharedSecret = sharedSecret.trim(),
            message = cleanMessage,
            messageId = secureRandom.nextLong(),
            callback = onComplete
        )

        synchronized(sendLock) {
            activeSend = session
        }

        session.timeoutRunnable = Runnable {
            completeSend(
                session,
                false,
                "Bluetooth send timed out"
            )
        }
        mainHandler.postDelayed(
            session.timeoutRunnable!!,
            CONNECTION_TIMEOUT_MS
        )

        try {
            val gatt = receiver.device.connectGatt(
                appContext,
                false,
                gattClientCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (gatt == null) {
                completeSend(
                    session,
                    false,
                    "Unable to connect to ${receiver.name}"
                )
            } else {
                session.gatt = gatt
            }
        } catch (error: Throwable) {
            completeSend(
                session,
                false,
                "Bluetooth connection error: ${error.message ?: "Unknown error"}"
            )
        }
    }

    fun release() {
        stopScan()
        stopReceiver()

        val session = synchronized(sendLock) {
            activeSend
        }

        if (session != null) {
            completeSend(session, false, "Bluetooth transport closed")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(
            status: Int,
            service: BluetoothGattService
        ) {
            if (!receiverActive.get()) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                postReceiverStatus("Unable to add iTantra BLE service: $status")
                return
            }

            startAdvertising()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val prefix = "${device.address}:"

                synchronized(reassemblyLock) {
                    reassemblies.keys.removeAll { it.startsWith(prefix) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer ?: return

            val result = when {
                !receiverActive.get() -> ChunkResult(false, null)
                characteristic.uuid != MESSAGE_CHARACTERISTIC_UUID ->
                    ChunkResult(false, null)
                preparedWrite -> ChunkResult(false, null)
                offset != 0 -> ChunkResult(false, null)
                else -> handleIncomingChunk(device.address, value)
            }

            if (responseNeeded) {
                val status = when {
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    result.accepted -> BluetoothGatt.GATT_SUCCESS
                    else -> BluetoothGatt.GATT_FAILURE
                }

                try {
                    server.sendResponse(
                        device,
                        requestId,
                        status,
                        offset,
                        null
                    )
                } catch (_: Throwable) {
                }
            }

            result.completeMessage?.let { completeMessage ->
                mainHandler.post {
                    receiverMessageCallback?.invoke(completeMessage)
                }
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            val session = currentSessionFor(gatt) ?: return

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                if (!gatt.discoverServices()) {
                    completeSend(
                        session,
                        false,
                        "Unable to discover the iTantra BLE service"
                    )
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                completeSend(
                    session,
                    false,
                    "Bluetooth receiver disconnected"
                )
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                completeSend(
                    session,
                    false,
                    "Bluetooth connection failed: $status"
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            val session = currentSessionFor(gatt) ?: return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                completeSend(
                    session,
                    false,
                    "iTantra service discovery failed: $status"
                )
                return
            }

            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                completeSend(
                    session,
                    false,
                    "The selected phone is not an iTantra receiver"
                )
                return
            }

            session.characteristic = characteristic

            try {
                gatt.requestConnectionPriority(
                    BluetoothGatt.CONNECTION_PRIORITY_HIGH
                )
            } catch (_: Throwable) {
            }

            session.mtuFallbackRunnable = Runnable {
                startChunkTransmission(session, DEFAULT_ATT_MTU)
            }
            mainHandler.postDelayed(
                session.mtuFallbackRunnable!!,
                MTU_FALLBACK_DELAY_MS
            )

            if (!gatt.requestMtu(REQUESTED_ATT_MTU)) {
                mainHandler.removeCallbacks(session.mtuFallbackRunnable!!)
                startChunkTransmission(session, DEFAULT_ATT_MTU)
            }
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            val session = currentSessionFor(gatt) ?: return

            session.mtuFallbackRunnable?.let(mainHandler::removeCallbacks)
            startChunkTransmission(
                session,
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtu
                } else {
                    DEFAULT_ATT_MTU
                }
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val session = currentSessionFor(gatt) ?: return

            if (
                characteristic.uuid != MESSAGE_CHARACTERISTIC_UUID ||
                status != BluetoothGatt.GATT_SUCCESS
            ) {
                completeSend(
                    session,
                    false,
                    "Bluetooth packet failed: $status"
                )
                return
            }

            session.nextChunkIndex += 1

            if (session.nextChunkIndex >= (session.chunks?.size ?: 0)) {
                completeSend(
                    session,
                    true,
                    "Message sent to ${session.receiver.name}"
                )
            } else {
                writeNextChunk(session)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adapter = bluetoothAdapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            postReceiverStatus("BLE advertising is unavailable")
            return
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                postReceiverStatus(
                    "Bluetooth auto receive is active as $advertisedName"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                postReceiverStatus(
                    "Bluetooth advertising failed: code $errorCode"
                )
            }
        }

        advertiseCallback = callback

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(
                MANUFACTURER_ID,
                advertisedName.toByteArray(Charsets.UTF_8)
            )
            .build()

        try {
            advertiser.startAdvertising(
                settings,
                advertiseData,
                scanResponse,
                callback
            )
        } catch (error: Throwable) {
            advertiseCallback = null
            postReceiverStatus(
                "BLE advertising error: ${error.message ?: "Unknown error"}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishScanResult(
        result: ScanResult,
        callback: (List<BleReceiver>) -> Unit
    ) {
        if (!scanActive.get()) return

        val device = result.device
        val address = try {
            device.address
        } catch (_: Throwable) {
            return
        }
        val advertisedBytes = result.scanRecord
            ?.getManufacturerSpecificData(MANUFACTURER_ID)
        val advertisedLabel = advertisedBytes
            ?.toString(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
        val label = advertisedLabel.ifBlank {
            try {
                device.name
            } catch (_: Throwable) {
                null
            } ?: "iTantra Receiver"
        }
        val receiver = BleReceiver(
            name = label,
            address = address,
            rssi = result.rssi,
            device = device,
            lastSeenAt = System.currentTimeMillis()
        )

        val snapshot = synchronized(scanLock) {
            discoveredReceivers[address] = receiver
            discoveredReceivers.values
                .sortedWith(
                    compareByDescending<BleReceiver> { it.rssi }
                        .thenBy { it.name.lowercase() }
                )
        }

        mainHandler.post {
            callback(snapshot)
        }
    }

    private fun handleIncomingChunk(
        deviceAddress: String,
        frame: ByteArray
    ): ChunkResult {
        if (frame.size <= FRAME_HEADER_BYTES) {
            return ChunkResult(false, null)
        }

        val header = ByteBuffer.wrap(frame)
        val magic = header.short.toInt() and 0xFFFF
        val version = header.get().toInt() and 0xFF
        val messageId = header.long
        val chunkIndex = header.short.toInt() and 0xFFFF
        val totalChunks = header.short.toInt() and 0xFFFF

        if (
            magic != FRAME_MAGIC ||
            version != FRAME_VERSION ||
            totalChunks !in 1..MAX_TOTAL_CHUNKS ||
            chunkIndex !in 0 until totalChunks
        ) {
            return ChunkResult(false, null)
        }

        val chunkBytes = ByteArray(header.remaining())
        header.get(chunkBytes)
        val key = "$deviceAddress:$messageId"

        synchronized(reassemblyLock) {
            pruneExpiredReassemblies()

            if (completedMessageIds.contains(key)) {
                return ChunkResult(true, null)
            }

            var assembly = reassemblies[key]

            if (assembly == null) {
                if (chunkIndex != 0) return ChunkResult(false, null)

                assembly = Reassembly(
                    totalChunks = totalChunks,
                    nextChunkIndex = 0,
                    createdAt = System.currentTimeMillis(),
                    output = ByteArrayOutputStream()
                )
                reassemblies[key] = assembly
            }

            if (
                assembly.totalChunks != totalChunks ||
                assembly.nextChunkIndex != chunkIndex ||
                assembly.output.size() + chunkBytes.size > MAX_ENCRYPTED_BYTES
            ) {
                reassemblies.remove(key)
                return ChunkResult(false, null)
            }

            assembly.output.write(chunkBytes)
            assembly.nextChunkIndex += 1

            if (assembly.nextChunkIndex < assembly.totalChunks) {
                return ChunkResult(true, null)
            }

            reassemblies.remove(key)

            return try {
                val text = decryptEnvelope(
                    envelope = assembly.output.toByteArray(),
                    messageId = messageId,
                    groupId = receiverGroupId,
                    sharedSecret = receiverSharedSecret
                )

                completedMessageIds.add(key)
                while (completedMessageIds.size > MAX_COMPLETED_IDS) {
                    val iterator = completedMessageIds.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }

                ChunkResult(true, text)
            } catch (_: Throwable) {
                ChunkResult(false, null)
            }
        }
    }

    private fun pruneExpiredReassemblies() {
        val expiry = System.currentTimeMillis() - REASSEMBLY_EXPIRY_MS
        reassemblies.entries.removeAll { it.value.createdAt < expiry }
    }

    private fun startChunkTransmission(
        session: SendSession,
        mtu: Int
    ) {
        val shouldStart = synchronized(sendLock) {
            if (
                activeSend !== session ||
                session.completed ||
                session.chunks != null
            ) {
                false
            } else {
                session.chunks = try {
                    buildFrames(
                        messageId = session.messageId,
                        groupId = session.groupId,
                        sharedSecret = session.sharedSecret,
                        message = session.message,
                        mtu = mtu
                    )
                } catch (error: Throwable) {
                    session.buildError = error
                    emptyList()
                }
                true
            }
        }

        if (!shouldStart) return

        val buildError = session.buildError

        if (buildError != null || session.chunks.isNullOrEmpty()) {
            completeSend(
                session,
                false,
                "Unable to prepare Bluetooth message: ${
                    buildError?.message ?: "No packets"
                }"
            )
            return
        }

        writeNextChunk(session)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextChunk(session: SendSession) {
        val gatt = session.gatt ?: run {
            completeSend(session, false, "Bluetooth connection is unavailable")
            return
        }
        val characteristic = session.characteristic ?: run {
            completeSend(session, false, "iTantra BLE channel is unavailable")
            return
        }
        val frame = session.chunks?.getOrNull(session.nextChunkIndex) ?: run {
            completeSend(session, false, "Bluetooth packet queue is invalid")
            return
        }

        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: Throwable) {
            false
        }

        if (!started) {
            completeSend(
                session,
                false,
                "Unable to start Bluetooth packet ${session.nextChunkIndex + 1}"
            )
        }
    }

    private fun buildFrames(
        messageId: Long,
        groupId: String,
        sharedSecret: String,
        message: String,
        mtu: Int
    ): List<ByteArray> {
        val envelope = encryptEnvelope(
            message = message,
            messageId = messageId,
            groupId = groupId,
            sharedSecret = sharedSecret
        )
        val maximumValueBytes = (mtu - ATT_OVERHEAD_BYTES)
            .coerceIn(FRAME_HEADER_BYTES + 1, 512)
        val dataBytesPerFrame = maximumValueBytes - FRAME_HEADER_BYTES
        val totalChunks = (
                (envelope.size + dataBytesPerFrame - 1) / dataBytesPerFrame
                ).coerceAtLeast(1)

        require(totalChunks <= MAX_TOTAL_CHUNKS) {
            "Too many Bluetooth packets"
        }

        return List(totalChunks) { chunkIndex ->
            val start = chunkIndex * dataBytesPerFrame
            val end = minOf(start + dataBytesPerFrame, envelope.size)
            val frameData = envelope.copyOfRange(start, end)

            ByteBuffer.allocate(FRAME_HEADER_BYTES + frameData.size)
                .putShort(FRAME_MAGIC.toShort())
                .put(FRAME_VERSION.toByte())
                .putLong(messageId)
                .putShort(chunkIndex.toShort())
                .putShort(totalChunks.toShort())
                .put(frameData)
                .array()
        }
    }

    private fun encryptEnvelope(
        message: String,
        messageId: Long,
        groupId: String,
        sharedSecret: String
    ): ByteArray {
        val plaintext = message.toByteArray(Charsets.UTF_8)

        require(plaintext.isNotEmpty() && plaintext.size <= MAX_TEXT_BYTES)

        val groupHash = groupHash(groupId)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(sharedSecret),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad(groupHash, messageId))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteBuffer.allocate(
            1 + GROUP_HASH_BYTES + GCM_IV_BYTES + 4 + ciphertext.size
        )
            .put(ENVELOPE_VERSION.toByte())
            .put(groupHash)
            .put(iv)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    private fun decryptEnvelope(
        envelope: ByteArray,
        messageId: Long,
        groupId: String,
        sharedSecret: String
    ): String {
        require(
            envelope.size >= 1 + GROUP_HASH_BYTES + GCM_IV_BYTES + 4 + 16
        )

        val buffer = ByteBuffer.wrap(envelope)
        require((buffer.get().toInt() and 0xFF) == ENVELOPE_VERSION)

        val receivedGroupHash = ByteArray(GROUP_HASH_BYTES)
        buffer.get(receivedGroupHash)
        val expectedGroupHash = groupHash(groupId)

        require(MessageDigest.isEqual(receivedGroupHash, expectedGroupHash))

        val iv = ByteArray(GCM_IV_BYTES)
        buffer.get(iv)
        val ciphertextLength = buffer.int

        require(
            ciphertextLength in 16..MAX_ENCRYPTED_BYTES &&
                    ciphertextLength == buffer.remaining()
        )

        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(sharedSecret),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad(expectedGroupHash, messageId))
        val plaintext = cipher.doFinal(ciphertext)

        require(plaintext.size <= MAX_TEXT_BYTES)
        return plaintext.toString(Charsets.UTF_8).trim()
            .also { require(it.isNotEmpty()) }
    }

    private fun deriveKey(sharedSecret: String): SecretKeySpec {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("iTantra-BLE-v1|$sharedSecret".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun groupHash(groupId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(groupId.trim().toByteArray(Charsets.UTF_8))
            .copyOf(GROUP_HASH_BYTES)

    private fun aad(groupHash: ByteArray, messageId: Long): ByteArray =
        ByteBuffer.allocate(GROUP_HASH_BYTES + 8)
            .put(groupHash)
            .putLong(messageId)
            .array()

    @SuppressLint("MissingPermission")
    private fun currentSessionFor(gatt: BluetoothGatt): SendSession? =
        synchronized(sendLock) {
            val session = activeSend ?: return@synchronized null

            if (session.completed) return@synchronized null

            if (session.gatt === gatt) {
                session
            } else if (
                session.gatt == null &&
                session.receiver.address == gatt.device.address
            ) {
                // A very fast stack may report connection state before
                // connectGatt() has returned its BluetoothGatt instance.
                session.gatt = gatt
                session
            } else {
                null
            }
        }

    @SuppressLint("MissingPermission")
    private fun completeSend(
        session: SendSession,
        success: Boolean,
        status: String
    ) {
        val shouldComplete = synchronized(sendLock) {
            if (activeSend !== session || session.completed) {
                false
            } else {
                session.completed = true
                activeSend = null
                true
            }
        }

        if (!shouldComplete) return

        session.timeoutRunnable?.let(mainHandler::removeCallbacks)
        session.mtuFallbackRunnable?.let(mainHandler::removeCallbacks)

        try {
            session.gatt?.disconnect()
        } catch (_: Throwable) {
        }

        try {
            session.gatt?.close()
        } catch (_: Throwable) {
        }

        mainHandler.post {
            session.callback(success, status)
        }
    }

    private fun postReceiverStatus(status: String) {
        mainHandler.post {
            receiverStatusCallback?.invoke(status)
        }
    }

    private fun postStatus(
        callback: (String) -> Unit,
        status: String
    ) {
        mainHandler.post {
            callback(status)
        }
    }

    private data class ChunkResult(
        val accepted: Boolean,
        val completeMessage: String?
    )

    private data class Reassembly(
        val totalChunks: Int,
        var nextChunkIndex: Int,
        val createdAt: Long,
        val output: ByteArrayOutputStream
    )

    private data class SendSession(
        val receiver: BleReceiver,
        val groupId: String,
        val sharedSecret: String,
        val message: String,
        val messageId: Long,
        val callback: (Boolean, String) -> Unit,
        var gatt: BluetoothGatt? = null,
        var characteristic: BluetoothGattCharacteristic? = null,
        var chunks: List<ByteArray>? = null,
        var nextChunkIndex: Int = 0,
        var completed: Boolean = false,
        var timeoutRunnable: Runnable? = null,
        var mtuFallbackRunnable: Runnable? = null,
        var buildError: Throwable? = null
    )
}
