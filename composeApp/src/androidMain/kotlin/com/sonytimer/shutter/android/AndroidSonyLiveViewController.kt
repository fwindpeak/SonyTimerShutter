package com.sonytimer.shutter.android

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.sonytimer.shutter.controller.CameraLiveViewController
import com.sonytimer.shutter.controller.LiveViewConnectionState
import com.sonytimer.shutter.controller.WifiCameraConfig
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 端索尼相机 Wi-Fi PTP/IP 实时预览与快门控制器。
 *
 * 支持两种连接模式：
 * 1. SSH 隧道转发 (默认)：先连接相机 TCP 22 端口 (keyboard-interactive 认证)，
 *    将本地环回动态端口转发至相机 127.0.0.1:15740。
 * 2. 免密直连：在相机「访问认证 = 关」时直接连接相机 TCP 15740 端口。
 *
 * 针对无外网的相机 Wi-Fi Direct 热点，显式通过 Wi-Fi [Network.getSocketFactory]
 * 发起对外连接，避免系统将流量路由到蜂窝网络。
 */
class AndroidSonyLiveViewController(
    context: Context
) : CameraLiveViewController {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val sessionGeneration = AtomicInteger(0)

    private val _connectionState =
        MutableStateFlow<LiveViewConnectionState>(LiveViewConnectionState.Disconnected)
    override val connectionState: StateFlow<LiveViewConnectionState> = _connectionState.asStateFlow()

    private val _latestFrame = MutableStateFlow<ImageBitmap?>(null)
    override val latestFrame: StateFlow<ImageBitmap?> = _latestFrame.asStateFlow()

    private val _fps = MutableStateFlow(0)
    override val fps: StateFlow<Int> = _fps.asStateFlow()

    private var connectJob: Job? = null
    private var liveViewLoopJob: Job? = null
    private var eventReaderJob: Job? = null

    @Volatile
    private var sshSession: Session? = null
    @Volatile
    private var commandSocket: Socket? = null
    @Volatile
    private var eventSocket: Socket? = null
    @Volatile
    private var cmdIn: DataInputStream? = null
    @Volatile
    private var cmdOut: BufferedOutputStream? = null
    @Volatile
    private var eventIn: DataInputStream? = null
    @Volatile
    private var eventOut: BufferedOutputStream? = null

    private var transactionId = 1
    private var liveviewInitialized = false

    override fun detectWifiGatewayIp(): String? {
        val cm = connectivityManager ?: return null
        val wifiNetwork = findWifiNetwork(cm) ?: return null
        val linkProps = cm.getLinkProperties(wifiNetwork) ?: return null

        for (route in linkProps.routes) {
            val gateway = route.gateway
            if (route.isDefaultRoute && gateway is Inet4Address) {
                return gateway.hostAddress
            }
        }
        for (linkAddress in linkProps.linkAddresses) {
            val addr = linkAddress.address
            if (addr is Inet4Address && addr.hostAddress?.startsWith("192.168.122.") == true) {
                return SonyPtpIpProtocol.DEFAULT_CAMERA_IP
            }
        }
        return null
    }

    override fun connect(config: WifiCameraConfig) {
        val trimmedHost = config.host.trim()
        if (trimmedHost.isEmpty()) {
            _connectionState.value = LiveViewConnectionState.Error("请输入相机 IP 地址")
            return
        }
        if (config.useSsh && (config.sshUsername.isBlank() || config.sshPassword.isBlank())) {
            _connectionState.value = LiveViewConnectionState.Error(
                "请填写相机「访问认证信息」中的用户名与密码"
            )
            return
        }

        val gen = sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = scope.launch {
            lifecycleMutex.withLock {
                closeActiveResources()
            }
            if (!isActive || sessionGeneration.get() != gen) return@launch

            try {
                establishConnection(config.copy(host = trimmedHost), gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionGeneration.get() == gen) {
                    val friendly = mapConnectionError(e, config.useSsh)
                    lifecycleMutex.withLock {
                        closeActiveResources()
                    }
                    _connectionState.value = LiveViewConnectionState.Error(friendly)
                }
            }
        }
    }

    override fun disconnect() {
        sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            lifecycleMutex.withLock {
                closeActiveResources()
                _connectionState.value = LiveViewConnectionState.Disconnected
            }
        }
    }

    override suspend fun triggerShutter(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_connectionState.value !is LiveViewConnectionState.Connected) {
            return@withContext Result.failure(IllegalStateException("Wi-Fi 预览未连接"))
        }

        runCatching {
            commandMutex.withLock {
                val s1DownCode = executeControlDevice(
                    SonyPtpIpProtocol.CONTROL_SHUTTER_HALF_S1,
                    SonyPtpIpProtocol.BUTTON_DOWN
                )
                if (s1DownCode != SonyPtpIpProtocol.RC_OK &&
                    s1DownCode != SonyPtpIpProtocol.RC_DEVICE_BUSY
                ) {
                    throw IOException("半按对焦失败 (0x${s1DownCode.toString(16).uppercase()})")
                }
                delay(180L)

                try {
                    val s2DownCode = executeControlDevice(
                        SonyPtpIpProtocol.CONTROL_SHUTTER_FULL_S2,
                        SonyPtpIpProtocol.BUTTON_DOWN
                    )
                    if (s2DownCode != SonyPtpIpProtocol.RC_OK) {
                        throw IOException("快门释放失败 (0x${s2DownCode.toString(16).uppercase()})")
                    }
                    delay(150L)
                } finally {
                    withContext(NonCancellable) {
                        runCatching {
                            executeControlDevice(
                                SonyPtpIpProtocol.CONTROL_SHUTTER_FULL_S2,
                                SonyPtpIpProtocol.BUTTON_UP
                            )
                        }
                        delay(50L)
                        runCatching {
                            executeControlDevice(
                                SonyPtpIpProtocol.CONTROL_SHUTTER_HALF_S1,
                                SonyPtpIpProtocol.BUTTON_UP
                            )
                        }
                    }
                }
            }
        }
    }

    override fun release() {
        sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        liveViewLoopJob?.cancel()
        eventReaderJob?.cancel()
        closeSocketsAndSshQuietly()
        scope.cancel()
    }

    private suspend fun establishConnection(config: WifiCameraConfig, gen: Int) {
        val wifiNetwork = connectivityManager?.let(::findWifiNetwork)
        val targetHost: String
        val targetPort: Int

        if (config.useSsh) {
            _connectionState.value = LiveViewConnectionState.Connecting("正在建立 SSH 安全隧道...")
            val session = connectSshSession(config, wifiNetwork)
            if (sessionGeneration.get() != gen) {
                runCatching { session.disconnect() }
                return
            }
            sshSession = session
            val localPort = session.setPortForwardingL(
                "127.0.0.1",
                0,
                "127.0.0.1",
                SonyPtpIpProtocol.DEFAULT_PORT
            )
            if (localPort <= 0) {
                throw IOException("SSH 端口转发失败")
            }
            targetHost = "127.0.0.1"
            targetPort = localPort
        } else {
            targetHost = config.host
            targetPort = SonyPtpIpProtocol.DEFAULT_PORT
        }

        _connectionState.value = LiveViewConnectionState.Connecting("正在连接相机 PTP/IP 通道...")
        val socketNetwork = if (config.useSsh) null else wifiNetwork
        val cmdSock = createConnectedSocket(targetHost, targetPort, socketNetwork)
        val evtSock = createConnectedSocket(targetHost, targetPort, socketNetwork)
        evtSock.soTimeout = EVENT_SOCKET_READ_TIMEOUT_MS

        commandSocket = cmdSock
        eventSocket = evtSock
        val cIn = DataInputStream(BufferedInputStream(cmdSock.getInputStream()))
        val cOut = BufferedOutputStream(cmdSock.getOutputStream())
        val eIn = DataInputStream(BufferedInputStream(evtSock.getInputStream()))
        val eOut = BufferedOutputStream(evtSock.getOutputStream())
        cmdIn = cIn
        cmdOut = cOut
        eventIn = eIn
        eventOut = eOut

        transactionId = 1
        liveviewInitialized = false

        // 1. INIT_COMMAND_REQUEST -> INIT_COMMAND_ACK
        val guid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        cOut.write(SonyPtpIpProtocol.buildInitCommandRequest(guid))
        cOut.flush()

        val cmdAckPkt = readPacket(cIn)
        if (cmdAckPkt.type == SonyPtpIpProtocol.PTPIP_INIT_FAIL) {
            throw IOException("相机拒绝连接 (INIT_FAIL)，请确认已关闭 Creators' App 并开启 PC 遥控")
        }
        if (cmdAckPkt.type != SonyPtpIpProtocol.PTPIP_INIT_COMMAND_ACK) {
            throw IOException("握手响应异常: type=0x${cmdAckPkt.type.toString(16)}")
        }
        val ack = SonyPtpIpProtocol.parseInitCommandAck(cmdAckPkt.body)
            ?: throw IOException("无法解析相机握手响应")
        val cameraName = ack.cameraName ?: "Sony Camera"

        // 2. INIT_EVENT_REQUEST -> INIT_EVENT_ACK
        eOut.write(SonyPtpIpProtocol.buildInitEventRequest(ack.connectionId))
        eOut.flush()
        val evtAckPkt = readPacket(eIn)
        if (evtAckPkt.type != SonyPtpIpProtocol.PTPIP_INIT_EVENT_ACK) {
            throw IOException("事件通道握手失败: type=0x${evtAckPkt.type.toString(16)}")
        }

        if (sessionGeneration.get() != gen) return

        // 3. 启动事件通道读取与心跳保活
        startEventReader(gen, eIn, eOut)

        // 4. OpenSession + Sony SDIO 握手序列
        _connectionState.value = LiveViewConnectionState.Connecting("正在初始化相机控制会话...")
        commandMutex.withLock {
            val (openRc, _) = executeOperation(SonyPtpIpProtocol.OP_OPEN_SESSION, intArrayOf(1))
            if (openRc != SonyPtpIpProtocol.RC_OK &&
                openRc != SonyPtpIpProtocol.RC_SESSION_ALREADY_OPEN_SONY &&
                openRc != SonyPtpIpProtocol.RC_SESSION_ALREADY_OPEN_STD
            ) {
                throw IOException("打开相机会话失败 (0x${openRc.toString(16).uppercase()})")
            }

            executeOperation(SonyPtpIpProtocol.OP_GET_DEVICE_INFO)
            executeOperation(SonyPtpIpProtocol.OP_GET_STORAGE_IDS)
            executeOperation(SonyPtpIpProtocol.OP_SDIO_CONNECT, intArrayOf(1, 0, 0))
            executeOperation(SonyPtpIpProtocol.OP_SDIO_CONNECT, intArrayOf(2, 0, 0))
            executeOperation(
                SonyPtpIpProtocol.OP_SDIO_GET_EXT_DEVICE_INFO,
                intArrayOf(SonyPtpIpProtocol.SDIO_PROTOCOL_VERSION, 0, 0)
            )
            executeOperation(SonyPtpIpProtocol.OP_SDIO_CONNECT, intArrayOf(3, 0, 0))
            delay(20L)
            executeOperation(
                SonyPtpIpProtocol.OP_SDIO_GET_EXT_DEVICE_INFO,
                intArrayOf(SonyPtpIpProtocol.SDIO_PROTOCOL_VERSION, 0, 0)
            )

            // 预初始化 LiveView 对象信息
            val (objInfoRc, _) = executeOperation(
                SonyPtpIpProtocol.OP_GET_OBJECT_INFO,
                intArrayOf(SonyPtpIpProtocol.LIVEVIEW_HANDLE)
            )
            liveviewInitialized = (objInfoRc == SonyPtpIpProtocol.RC_OK)
        }

        if (sessionGeneration.get() != gen) return

        _connectionState.value = LiveViewConnectionState.Connected(
            cameraName = cameraName,
            host = config.host,
            viaSsh = config.useSsh
        )
        startLiveViewLoop(gen)
    }

    private fun startEventReader(gen: Int, eIn: DataInputStream, eOut: BufferedOutputStream) {
        eventReaderJob?.cancel()
        eventReaderJob = scope.launch {
            var lastProbeSentAt = System.currentTimeMillis()
            try {
                while (isActive && sessionGeneration.get() == gen) {
                    try {
                        val pkt = readPacket(eIn)
                        when (pkt.type) {
                            SonyPtpIpProtocol.PTPIP_PROBE_REQ -> {
                                eOut.write(SonyPtpIpProtocol.buildProbeResponse())
                                eOut.flush()
                            }
                            SonyPtpIpProtocol.PTPIP_PROBE_RESP -> Unit
                            else -> Unit
                        }
                        lastProbeSentAt = System.currentTimeMillis()
                    } catch (_: SocketTimeoutException) {
                        val now = System.currentTimeMillis()
                        if (now - lastProbeSentAt >= KEEPALIVE_INTERVAL_MS) {
                            eOut.write(SonyPtpIpProtocol.buildProbeRequest())
                            eOut.flush()
                            lastProbeSentAt = now
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (sessionGeneration.get() == gen &&
                    _connectionState.value is LiveViewConnectionState.Connected
                ) {
                    handleConnectionLost(gen, "Wi-Fi 预览连接已断开")
                }
            }
        }
    }

    private fun startLiveViewLoop(gen: Int) {
        liveViewLoopJob?.cancel()
        liveViewLoopJob = scope.launch {
            var frameCount = 0
            var windowStartMs = System.currentTimeMillis()
            var consecutiveErrors = 0

            while (isActive && sessionGeneration.get() == gen) {
                try {
                    val (rc, payload) = commandMutex.withLock {
                        if (!liveviewInitialized) {
                            val (infoRc, _) = executeOperation(
                                SonyPtpIpProtocol.OP_GET_OBJECT_INFO,
                                intArrayOf(SonyPtpIpProtocol.LIVEVIEW_HANDLE)
                            )
                            if (infoRc != SonyPtpIpProtocol.RC_OK) {
                                return@withLock infoRc to ByteArray(0)
                            }
                            liveviewInitialized = true
                        }
                        executeOperation(
                            SonyPtpIpProtocol.OP_GET_OBJECT,
                            intArrayOf(SonyPtpIpProtocol.LIVEVIEW_HANDLE)
                        )
                    }

                    when (rc) {
                        SonyPtpIpProtocol.RC_OK -> {
                            consecutiveErrors = 0
                            // 在锁外解析与解码 JPEG，避免阻塞快门释放指令
                            val jpeg = SonyPtpIpProtocol.extractLiveViewJpeg(payload)
                            if (jpeg != null) {
                                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                if (bitmap != null) {
                                    _latestFrame.value = bitmap.asImageBitmap()
                                    frameCount++
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - windowStartMs
                                    if (elapsed >= 1_000L) {
                                        _fps.value = ((frameCount * 1_000L) / elapsed).toInt()
                                        frameCount = 0
                                        windowStartMs = now
                                    }
                                }
                            }
                            delay(FRAME_INTERVAL_DELAY_MS)
                        }
                        SonyPtpIpProtocol.RC_ACCESS_DENIED,
                        SonyPtpIpProtocol.RC_DEVICE_BUSY -> {
                            consecutiveErrors = 0
                            delay(40L)
                        }
                        SonyPtpIpProtocol.RC_INVALID_OBJECT_HANDLE -> {
                            liveviewInitialized = false
                            consecutiveErrors = 0
                            delay(200L)
                        }
                        else -> {
                            consecutiveErrors++
                            delay(100L)
                        }
                    }

                    if (consecutiveErrors >= 15) {
                        throw IOException("连续获取预览画面失败")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (sessionGeneration.get() == gen &&
                        _connectionState.value is LiveViewConnectionState.Connected
                    ) {
                        handleConnectionLost(gen, "Wi-Fi 实时预览已断开")
                    }
                    break
                }
            }
        }
    }

    private fun handleConnectionLost(gen: Int, message: String) {
        scope.launch {
            lifecycleMutex.withLock {
                if (sessionGeneration.get() == gen) {
                    sessionGeneration.incrementAndGet()
                    closeActiveResources()
                    _connectionState.value = LiveViewConnectionState.Error(message)
                }
            }
        }
    }

    private fun executeOperation(
        opCode: Int,
        params: IntArray = intArrayOf()
    ): Pair<Int, ByteArray> {
        val input = cmdIn ?: throw IOException("Command channel closed")
        val output = cmdOut ?: throw IOException("Command channel closed")
        val txId = transactionId++

        output.write(SonyPtpIpProtocol.buildOperationRequest(opCode, txId, params))
        output.flush()

        var dataBuffer: ByteArrayOutputStream? = null
        while (true) {
            val pkt = readPacket(input)
            when (pkt.type) {
                SonyPtpIpProtocol.PTPIP_PROBE_REQ -> {
                    output.write(SonyPtpIpProtocol.buildProbeResponse())
                    output.flush()
                }
                SonyPtpIpProtocol.PTPIP_PROBE_RESP -> Unit
                SonyPtpIpProtocol.PTPIP_START_DATA -> {
                    if (pkt.body.size >= 4 && SonyPtpIpProtocol.readInt32Le(pkt.body, 0) == txId) {
                        dataBuffer = ByteArrayOutputStream()
                    }
                }
                SonyPtpIpProtocol.PTPIP_DATA -> {
                    if (pkt.body.size > 4 && SonyPtpIpProtocol.readInt32Le(pkt.body, 0) == txId) {
                        if (dataBuffer == null) dataBuffer = ByteArrayOutputStream()
                        dataBuffer.write(pkt.body, 4, pkt.body.size - 4)
                    }
                }
                SonyPtpIpProtocol.PTPIP_END_DATA -> {
                    if (pkt.body.size > 4 && SonyPtpIpProtocol.readInt32Le(pkt.body, 0) == txId) {
                        if (dataBuffer == null) dataBuffer = ByteArrayOutputStream()
                        dataBuffer.write(pkt.body, 4, pkt.body.size - 4)
                    }
                }
                SonyPtpIpProtocol.PTPIP_CANCEL -> {
                    if (pkt.body.size >= 4 && SonyPtpIpProtocol.readInt32Le(pkt.body, 0) == txId) {
                        throw IOException("相机取消了操作")
                    }
                }
                SonyPtpIpProtocol.PTPIP_OPER_RESP -> {
                    val resp = SonyPtpIpProtocol.parseOperationResponse(pkt.body)
                    if (resp != null && resp.transactionId == txId) {
                        return resp.responseCode to (dataBuffer?.toByteArray() ?: ByteArray(0))
                    }
                }
            }
        }
    }

    private fun executeControlDevice(controlCode: Int, buttonValue: Int): Int {
        val input = cmdIn ?: throw IOException("Command channel closed")
        val output = cmdOut ?: throw IOException("Command channel closed")
        val txId = transactionId++

        val packets = SonyPtpIpProtocol.buildControlDevicePackets(
            controlCode = controlCode,
            transactionId = txId,
            buttonValue = buttonValue
        )
        for (pkt in packets) {
            output.write(pkt)
        }
        output.flush()

        while (true) {
            val pkt = readPacket(input)
            when (pkt.type) {
                SonyPtpIpProtocol.PTPIP_PROBE_REQ -> {
                    output.write(SonyPtpIpProtocol.buildProbeResponse())
                    output.flush()
                }
                SonyPtpIpProtocol.PTPIP_OPER_RESP -> {
                    val resp = SonyPtpIpProtocol.parseOperationResponse(pkt.body)
                    if (resp != null && resp.transactionId == txId) {
                        return resp.responseCode
                    }
                }
            }
        }
    }

    private fun readPacket(input: DataInputStream): SonyPtpIpProtocol.PtpIpPacket {
        val header = ByteArray(8)
        input.readFully(header)
        val (length, type) = SonyPtpIpProtocol.parsePacketHeader(header)
        if (length < 8 || length > MAX_PACKET_SIZE_BYTES) {
            throw IOException("无效的 PTP/IP 数据包长度: $length")
        }
        val body = ByteArray(length - 8)
        if (body.isNotEmpty()) {
            input.readFully(body)
        }
        return SonyPtpIpProtocol.PtpIpPacket(type = type, body = body)
    }

    private fun connectSshSession(config: WifiCameraConfig, wifiNetwork: Network?): Session {
        val jsch = JSch()
        val session = jsch.getSession(config.sshUsername.trim(), config.host, SonyPtpIpProtocol.SSH_PORT)
        session.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val socket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), SSH_CONNECT_TIMEOUT_MS)
                return socket
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })

        session.setPassword(config.sshPassword)
        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = config.sshPassword
            override fun promptPassword(message: String?): Boolean = true
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(
                destination: String?,
                name: String?,
                instruction: String?,
                prompt: Array<out String>?,
                echo: BooleanArray?
            ): Array<String> {
                val count = prompt?.size ?: 0
                return Array(count) { config.sshPassword }
            }
        }

        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "keyboard-interactive,password")
        session.setConfig(
            "cipher.c2s",
            "aes128-ctr,aes256-ctr,aes192-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com"
        )
        session.setConfig(
            "cipher.s2c",
            "aes128-ctr,aes256-ctr,aes192-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com"
        )
        session.connect(SSH_CONNECT_TIMEOUT_MS)
        return session
    }

    private fun createConnectedSocket(host: String, port: Int, wifiNetwork: Network?): Socket {
        val socket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        socket.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
        return socket
    }

    private fun findWifiNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return active
            }
        }
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return network
            }
        }
        return null
    }

    private suspend fun closeActiveResources() {
        liveViewLoopJob?.cancel()
        eventReaderJob?.cancel()
        liveViewLoopJob = null
        eventReaderJob = null
        closeSocketsAndSshQuietly()
        liveviewInitialized = false
        _latestFrame.value = null
        _fps.value = 0
    }

    private fun closeSocketsAndSshQuietly() {
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        runCatching { sshSession?.disconnect() }
        commandSocket = null
        eventSocket = null
        cmdIn = null
        cmdOut = null
        eventIn = null
        eventOut = null
        sshSession = null
    }

    private fun mapConnectionError(error: Exception, usedSsh: Boolean): String {
        val raw = error.message.orEmpty()
        return when {
            error is JSchException && raw.contains("Auth fail", ignoreCase = true) ->
                "SSH 用户名或密码错误，请核对相机「访问认证信息」"
            error is JSchException && raw.contains("timeout", ignoreCase = true) ->
                "SSH 连接超时，请确认手机已连接相机 Wi-Fi"
            raw.contains("ECONNREFUSED", ignoreCase = true) ->
                if (usedSsh) {
                    "相机拒绝 SSH (22) 连接，请确认已开启「PC 遥控」"
                } else {
                    "相机拒绝 15740 端口连接，请确认已开启「PC 遥控」且关闭「访问认证」"
                }
            raw.contains("ETIMEDOUT", ignoreCase = true) || error is SocketTimeoutException ->
                "连接相机超时，请检查相机 IP 与 Wi-Fi 连接"
            raw.isNotBlank() -> raw
            else -> "Wi-Fi 连接失败"
        }
    }

    private companion object {
        const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        const val SOCKET_READ_TIMEOUT_MS = 5_000
        const val EVENT_SOCKET_READ_TIMEOUT_MS = 10_000
        const val SSH_CONNECT_TIMEOUT_MS = 10_000
        const val KEEPALIVE_INTERVAL_MS = 20_000L
        const val FRAME_INTERVAL_DELAY_MS = 15L
        const val MAX_PACKET_SIZE_BYTES = 10_000_000
    }
}
