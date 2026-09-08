package com.example.itantra

import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class NearbyReceiver(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val lastSeenAt: Long
) {
    val id: String
        get() = "$ipAddress:$port"
}

class WifiTextTransceiver(
    deviceName: String
) {

    companion object {
        const val PORT = 50_505
        const val DISCOVERY_PORT = 50_506

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_TIMEOUT_MS = 8_000
        private const val DISCOVERY_TIMEOUT_MS = 700
        private const val DISCOVERY_INTERVAL_MS = 1_500L
        private const val RECEIVER_EXPIRY_MS = 6_000L
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_GROUP_BYTES = 128
        private const val MAX_DISCOVERY_PACKET_BYTES = 512

        private const val MESSAGE_MAGIC = "ITANTRA_MESSAGE_V1"
        private const val DISCOVERY_REQUEST = "ITANTRA_DISCOVER_V1"
        private const val DISCOVERY_RESPONSE = "ITANTRA_RECEIVER_V1"
        private const val ACK_ACCEPTED = 0x06
        private const val ACK_REJECTED = 0x15
    }

    private val receiverName = deviceName
        .replace("|", " ")
        .trim()
        .ifBlank { "iTantra Receiver" }
        .take(60)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val responderExecutor = Executors.newSingleThreadExecutor()
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    private val senderExecutor = Executors.newSingleThreadExecutor()

    private val serverGeneration = AtomicInteger(0)
    private val responderGeneration = AtomicInteger(0)
    private val discoveryGeneration = AtomicInteger(0)
    private val serverRunning = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var responderSocket: DatagramSocket? = null

    @Volatile
    private var discoverySocket: DatagramSocket? = null

    /**
     * Starts automatic receiver mode. Incoming messages with the matching
     * group ID are accepted immediately and delivered to the UI without a
     * receiver-side confirmation dialog.
     */
    fun startServer(
        groupId: String,
        onStatusChange: (String) -> Unit,
        onMessageReceived: (String) -> Unit
    ) {
        val cleanGroupId = requireGroupId(groupId)

        stopServer()
        val generation = serverGeneration.incrementAndGet()

        startDiscoveryResponder(cleanGroupId)

        serverExecutor.execute {
            var localServer: ServerSocket? = null

            try {
                if (serverGeneration.get() != generation) return@execute

                val activeServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                localServer = activeServer

                if (serverGeneration.get() != generation) {
                    activeServer.close()
                    return@execute
                }

                serverSocket = activeServer
                serverRunning.set(true)
                postStatus(
                    onStatusChange,
                    "Auto receive is active on port $PORT"
                )

                while (serverGeneration.get() == generation) {
                    try {
                        activeServer.accept().use { clientSocket ->
                            clientSocket.soTimeout = SOCKET_TIMEOUT_MS

                            val received = readMessage(clientSocket)
                            val accepted = received.groupId == cleanGroupId

                            DataOutputStream(
                                clientSocket.getOutputStream()
                            ).apply {
                                writeByte(
                                    if (accepted) ACK_ACCEPTED else ACK_REJECTED
                                )
                                flush()
                            }

                            if (accepted && received.message.isNotBlank()) {
                                mainHandler.post {
                                    onMessageReceived(received.message)
                                }
                            }
                        }
                    } catch (error: SocketException) {
                        if (serverGeneration.get() == generation) throw error
                    } catch (_: Throwable) {
                        // Ignore malformed or interrupted individual messages
                        // and keep automatic Receiver mode available.
                    }
                }
            } catch (error: Throwable) {
                if (serverGeneration.get() == generation) {
                    postStatus(
                        onStatusChange,
                        "Receiver error: ${error.message ?: "Unknown error"}"
                    )
                }
            } finally {
                try {
                    localServer?.close()
                } catch (_: Throwable) {
                }

                if (serverSocket === localServer) {
                    serverSocket = null
                }

                if (serverGeneration.get() == generation) {
                    serverRunning.set(false)
                }
            }
        }
    }

    fun stopServer(onStopped: (() -> Unit)? = null) {
        serverGeneration.incrementAndGet()
        serverRunning.set(false)

        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }

        serverSocket = null
        stopDiscoveryResponder()

        if (onStopped != null) {
            mainHandler.post(onStopped)
        }
    }

    /**
     * Searches the current Wi-Fi/hotspot network for iTantra receivers.
     * Discovery is connectionless: receivers reply automatically.
     */
    fun startDiscovery(
        groupId: String,
        onStatusChange: (String) -> Unit,
        onReceiversChanged: (List<NearbyReceiver>) -> Unit
    ) {
        val cleanGroupId = requireGroupId(groupId)

        stopDiscovery()
        val generation = discoveryGeneration.incrementAndGet()

        discoveryExecutor.execute {
            var socket: DatagramSocket? = null

            try {
                val activeSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = DISCOVERY_TIMEOUT_MS
                    bind(InetSocketAddress(0))
                }
                socket = activeSocket

                if (discoveryGeneration.get() != generation) {
                    activeSocket.close()
                    return@execute
                }

                discoverySocket = activeSocket
                val receivers = linkedMapOf<String, NearbyReceiver>()
                var lastBroadcastAt = 0L
                var publishedIds = emptyList<String>()

                postStatus(
                    onStatusChange,
                    "Searching for nearby iTantra receivers..."
                )

                while (discoveryGeneration.get() == generation) {
                    val now = System.currentTimeMillis()

                    if (now - lastBroadcastAt >= DISCOVERY_INTERVAL_MS) {
                        sendDiscoveryRequest(activeSocket, cleanGroupId)
                        lastBroadcastAt = now
                    }

                    try {
                        val buffer = ByteArray(MAX_DISCOVERY_PACKET_BYTES)
                        val packet = DatagramPacket(buffer, buffer.size)
                        activeSocket.receive(packet)

                        parseDiscoveryResponse(
                            packet = packet,
                            expectedGroupId = cleanGroupId
                        )?.let { receiver ->
                            receivers[receiver.id] = receiver
                        }
                    } catch (_: SocketTimeoutException) {
                        // Timeout lets the loop broadcast again and remove
                        // receivers that are no longer reachable.
                    } catch (error: SocketException) {
                        if (discoveryGeneration.get() == generation) throw error
                    }

                    val expiryTime = System.currentTimeMillis() - RECEIVER_EXPIRY_MS
                    receivers.entries.removeAll {
                        it.value.lastSeenAt < expiryTime
                    }

                    val snapshot = receivers.values
                        .sortedWith(
                            compareBy<NearbyReceiver> { it.name.lowercase() }
                                .thenBy { it.ipAddress }
                        )
                    val snapshotIds = snapshot.map { "${it.id}|${it.name}" }

                    if (snapshotIds != publishedIds) {
                        publishedIds = snapshotIds
                        mainHandler.post {
                            onReceiversChanged(snapshot)
                            onStatusChange(
                                if (snapshot.isEmpty()) {
                                    "Searching for nearby iTantra receivers..."
                                } else {
                                    "Found ${snapshot.size} nearby receiver(s)"
                                }
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                if (discoveryGeneration.get() == generation) {
                    postStatus(
                        onStatusChange,
                        "Discovery error: ${error.message ?: "Check the hotspot"}"
                    )
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }

                if (discoverySocket === socket) {
                    discoverySocket = null
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryGeneration.incrementAndGet()

        try {
            discoverySocket?.close()
        } catch (_: Throwable) {
        }

        discoverySocket = null
    }

    fun sendMessage(
        receiver: NearbyReceiver,
        groupId: String,
        message: String,
        onComplete: (success: Boolean, status: String) -> Unit
    ) {
        val cleanGroupId = try {
            requireGroupId(groupId)
        } catch (error: IllegalArgumentException) {
            onComplete(false, error.message ?: "Invalid group ID")
            return
        }
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) {
            onComplete(false, "There is no message to send")
            return
        }

        val messageBytes = cleanMessage.toByteArray(Charsets.UTF_8)

        if (messageBytes.size > MAX_MESSAGE_BYTES) {
            onComplete(false, "Message is too large")
            return
        }

        senderExecutor.execute {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(receiver.ipAddress, receiver.port),
                        CONNECT_TIMEOUT_MS
                    )
                    socket.soTimeout = SOCKET_TIMEOUT_MS

                    val output = DataOutputStream(socket.getOutputStream())
                    writeString(output, MESSAGE_MAGIC)
                    writeString(output, cleanGroupId)
                    writeString(output, cleanMessage)
                    output.flush()

                    val acknowledgement = DataInputStream(
                        socket.getInputStream()
                    ).readUnsignedByte()

                    check(acknowledgement == ACK_ACCEPTED) {
                        if (acknowledgement == ACK_REJECTED) {
                            "Receiver belongs to a different group"
                        } else {
                            "Receiver acknowledgement was not received"
                        }
                    }
                }

                postResult(
                    onComplete,
                    true,
                    "Message sent to ${receiver.name}"
                )
            } catch (error: Throwable) {
                postResult(
                    onComplete,
                    false,
                    "Send failed: ${error.message ?: "Receiver is unavailable"}"
                )
            }
        }
    }

    fun getLocalIpv4Addresses(): List<String> {
        val addresses = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return emptyList()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val interfaceAddresses = networkInterface.inetAddresses

                while (interfaceAddresses.hasMoreElements()) {
                    val address = interfaceAddresses.nextElement()

                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        addresses.add(address.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        return addresses
            .distinct()
            .sortedByDescending { it.startsWith("192.168.") }
    }

    fun isServerRunning(): Boolean = serverRunning.get()

    fun release() {
        stopDiscovery()
        stopServer()
        serverExecutor.shutdownNow()
        responderExecutor.shutdownNow()
        discoveryExecutor.shutdownNow()
        senderExecutor.shutdownNow()
    }

    private fun startDiscoveryResponder(groupId: String) {
        stopDiscoveryResponder()
        val generation = responderGeneration.incrementAndGet()

        responderExecutor.execute {
            var socket: DatagramSocket? = null

            try {
                val activeSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = DISCOVERY_TIMEOUT_MS
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                socket = activeSocket

                if (responderGeneration.get() != generation) {
                    activeSocket.close()
                    return@execute
                }

                responderSocket = activeSocket

                while (responderGeneration.get() == generation) {
                    try {
                        val buffer = ByteArray(MAX_DISCOVERY_PACKET_BYTES)
                        val requestPacket = DatagramPacket(buffer, buffer.size)
                        activeSocket.receive(requestPacket)

                        val request = String(
                            requestPacket.data,
                            requestPacket.offset,
                            requestPacket.length,
                            Charsets.UTF_8
                        )
                        val parts = request.split('|', limit = 2)

                        if (
                            parts.size == 2 &&
                            parts[0] == DISCOVERY_REQUEST &&
                            parts[1] == groupId
                        ) {
                            val response = listOf(
                                DISCOVERY_RESPONSE,
                                groupId,
                                receiverName,
                                PORT.toString()
                            ).joinToString("|").toByteArray(Charsets.UTF_8)

                            val responsePacket = DatagramPacket(
                                response,
                                response.size,
                                requestPacket.address,
                                requestPacket.port
                            )
                            activeSocket.send(responsePacket)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue listening while Receiver mode is active.
                    } catch (error: SocketException) {
                        if (responderGeneration.get() == generation) throw error
                    }
                }
            } catch (_: Throwable) {
                // TCP receiver status remains authoritative. Some hotspot
                // implementations may block UDP broadcast discovery.
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }

                if (responderSocket === socket) {
                    responderSocket = null
                }
            }
        }
    }

    private fun stopDiscoveryResponder() {
        responderGeneration.incrementAndGet()

        try {
            responderSocket?.close()
        } catch (_: Throwable) {
        }

        responderSocket = null
    }

    private fun sendDiscoveryRequest(
        socket: DatagramSocket,
        groupId: String
    ) {
        val bytes = "$DISCOVERY_REQUEST|$groupId"
            .toByteArray(Charsets.UTF_8)

        getBroadcastAddresses().forEach { address ->
            try {
                socket.send(
                    DatagramPacket(
                        bytes,
                        bytes.size,
                        address,
                        DISCOVERY_PORT
                    )
                )
            } catch (_: Throwable) {
                // Try the remaining interface broadcast addresses.
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    interfaceAddress.broadcast?.let(addresses::add)
                }
            }
        } catch (_: Throwable) {
        }

        addresses.add(InetAddress.getByName("255.255.255.255"))
        return addresses.toList()
    }

    private fun parseDiscoveryResponse(
        packet: DatagramPacket,
        expectedGroupId: String
    ): NearbyReceiver? {
        val response = String(
            packet.data,
            packet.offset,
            packet.length,
            Charsets.UTF_8
        )
        val parts = response.split('|', limit = 4)

        if (
            parts.size != 4 ||
            parts[0] != DISCOVERY_RESPONSE ||
            parts[1] != expectedGroupId
        ) {
            return null
        }

        val port = parts[3].toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: return null
        val ipAddress = packet.address.hostAddress ?: return null

        return NearbyReceiver(
            name = parts[2].ifBlank { "iTantra Receiver" },
            ipAddress = ipAddress,
            port = port,
            lastSeenAt = System.currentTimeMillis()
        )
    }

    private fun readMessage(socket: Socket): ReceivedMessage {
        val input = DataInputStream(socket.getInputStream())
        val magic = readString(input, MESSAGE_MAGIC.length * 4)

        require(magic == MESSAGE_MAGIC) {
            "Unsupported message format"
        }

        val groupId = readString(input, MAX_GROUP_BYTES)
        val message = readString(input, MAX_MESSAGE_BYTES)

        return ReceivedMessage(groupId = groupId, message = message)
    }

    private fun writeString(
        output: DataOutputStream,
        value: String
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(
        input: DataInputStream,
        maximumBytes: Int
    ): String {
        val length = input.readInt()

        require(length in 1..maximumBytes) {
            "Invalid field length: $length"
        }

        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun requireGroupId(groupId: String): String {
        val cleanGroupId = groupId.trim()

        require(cleanGroupId.isNotEmpty()) {
            "Group ID cannot be empty"
        }

        require(
            cleanGroupId.toByteArray(Charsets.UTF_8).size <= MAX_GROUP_BYTES
        ) {
            "Group ID is too long"
        }

        return cleanGroupId
    }

    private fun postStatus(
        callback: (String) -> Unit,
        status: String
    ) {
        mainHandler.post {
            callback(status)
        }
    }

    private fun postResult(
        callback: (Boolean, String) -> Unit,
        success: Boolean,
        status: String
    ) {
        mainHandler.post {
            callback(success, status)
        }
    }

    private data class ReceivedMessage(
        val groupId: String,
        val message: String
    )
}
