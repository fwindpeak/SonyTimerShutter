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
import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.LiveViewConnectionState
import com.sonytimer.shutter.controller.PtpEvent
import com.sonytimer.shutter.controller.PtpTransportMode
import com.sonytimer.shutter.controller.WifiCameraConfig
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 端索尼相机 Wi-Fi PTP/IP 实时预览与快门控制器。
 *
 * 特性：
 * 1. 全自动局域网/热点/Wi-Fi Direct 发现：优先探测缓存 IP、网关、192.168.122.1，
 *    再结合 SSDP 与 /24 私网段并发扫描 TCP 15740，免去手动输入 IP。
 * 2. 持久化 16 字节客户端 GUID：首次在相机屏幕点击确认后，后续重连相机可自动识别。
 * 3. 不限先后顺序的持续搜索与 60 秒确认等待窗口：无论先开相机还是先开 App，
 *    都能自动匹配并在相机弹出确认框时耐心等待用户操作。
 */
class AndroidSonyLiveViewController(
    context: Context,
    private val clientGuidProvider: () -> ByteArray = {
        ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
) : CameraLiveViewController, AndroidPtpTransport {

    private data class LocalSubnet(
        val localIp: String,
        val prefix24: String,
        val network: Network?
    )

    private data class ProbeTarget(
        val host: String,
        val network: Network?
    )

    private data class DiscoveredEndpoint(
        val host: String,
        val network: Network?,
        val commandSocket: Socket
    )

    private class CameraRejectedException : IOException(
        "相机拒绝了连接，请在相机屏幕重新确认配对（若开着 Creators' App 请先关闭）"
    )

    private class ConfirmTimeoutException : IOException(
        "等待相机屏幕确认超时（60秒），请点击下方按钮重试"
    )

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val sessionGeneration = AtomicInteger(0)
    private val liveViewPaused = AtomicBoolean(false)
    private val sdramAvailable = AtomicBoolean(false)

    override val mode: PtpTransportMode = PtpTransportMode.WIFI_PTP_IP
    override val isConnected: Boolean
        get() = _connectionState.value is LiveViewConnectionState.Connected

    private val _events = MutableSharedFlow<PtpEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<PtpEvent> = _events.asSharedFlow()

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

    override fun setLiveViewPaused(paused: Boolean) {
        liveViewPaused.set(paused)
        if (paused) {
            _fps.value = 0
        }
    }

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

    override fun autoConnect(preferredHost: String?) {
        val gen = sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = scope.launch {
            lifecycleMutex.withLock {
                closeActiveResources()
            }
            if (!isActive || sessionGeneration.get() != gen) return@launch

            val startMs = System.currentTimeMillis()
            val deadlineMs = startMs + AUTO_DISCOVERY_TIMEOUT_MS
            var currentHint = "正在自动搜索相机..."

            val tickerJob = launch {
                while (isActive && sessionGeneration.get() == gen) {
                    val state = _connectionState.value
                    if (state is LiveViewConnectionState.Searching || state is LiveViewConnectionState.Disconnected) {
                        val elapsed = ((System.currentTimeMillis() - startMs) / 1_000L).toInt()
                        _connectionState.value = LiveViewConnectionState.Searching(
                            hint = currentHint,
                            elapsedSeconds = elapsed
                        )
                    }
                    delay(1_000L)
                }
            }

            try {
                _connectionState.value = LiveViewConnectionState.Searching(
                    hint = currentHint,
                    elapsedSeconds = 0
                )

                while (isActive && sessionGeneration.get() == gen && System.currentTimeMillis() < deadlineMs) {
                    val wifiNetwork = connectivityManager?.let(::findWifiNetwork)
                    val subnets = collectLocalSubnets(wifiNetwork)
                    val gatewayIp = detectWifiGatewayIp()

                    if (subnets.isEmpty() && wifiNetwork == null) {
                        currentHint = "未检测到 Wi-Fi 或手机热点，请先让手机与相机连入同一网络..."
                        _connectionState.value = LiveViewConnectionState.Searching(
                            hint = currentHint,
                            elapsedSeconds = ((System.currentTimeMillis() - startMs) / 1_000L).toInt()
                        )
                        delay(DISCOVERY_RETRY_INTERVAL_MS)
                        continue
                    }

                    currentHint = "正在自动搜索相机，请打开「电脑遥控」并进入配对界面..."
                    val endpoint = discoverCameraEndpoint(
                        preferredHost = preferredHost,
                        gatewayIp = gatewayIp,
                        wifiNetwork = wifiNetwork,
                        subnets = subnets
                    )

                    if (endpoint == null) {
                        delay(DISCOVERY_RETRY_INTERVAL_MS)
                        continue
                    }

                    if (!isActive || sessionGeneration.get() != gen) {
                        runCatching { endpoint.commandSocket.close() }
                        return@launch
                    }

                    tickerJob.cancel()
                    try {
                        establishPtpIpSession(
                            host = endpoint.host,
                            port = SonyPtpIpProtocol.DEFAULT_PORT,
                            socketNetwork = endpoint.network,
                            preConnectedCmdSocket = endpoint.commandSocket,
                            viaSsh = false,
                            gen = gen
                        )
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CameraRejectedException) {
                        if (sessionGeneration.get() == gen) {
                            lifecycleMutex.withLock { closeActiveResources() }
                            _connectionState.value = LiveViewConnectionState.Error(e.message ?: "相机拒绝连接")
                        }
                        return@launch
                    } catch (e: ConfirmTimeoutException) {
                        if (sessionGeneration.get() == gen) {
                            lifecycleMutex.withLock { closeActiveResources() }
                            _connectionState.value = LiveViewConnectionState.Error(e.message ?: "等待相机确认超时")
                        }
                        return@launch
                    } catch (e: Exception) {
                        if (sessionGeneration.get() != gen) return@launch
                        lifecycleMutex.withLock { closeActiveResources() }
                        if (System.currentTimeMillis() + 3_000L >= deadlineMs) {
                            _connectionState.value = LiveViewConnectionState.Error(
                                mapConnectionError(e, usedSsh = false)
                            )
                            return@launch
                        }
                        currentHint = "相机尚未就绪，正在自动重试连接..."
                        _connectionState.value = LiveViewConnectionState.Searching(
                            hint = currentHint,
                            elapsedSeconds = ((System.currentTimeMillis() - startMs) / 1_000L).toInt()
                        )
                        delay(DISCOVERY_RETRY_INTERVAL_MS)
                    }
                }

                if (isActive && sessionGeneration.get() == gen) {
                    lifecycleMutex.withLock { closeActiveResources() }
                    _connectionState.value = LiveViewConnectionState.Error(
                        "未自动搜索到相机。\n请确认手机与相机在同一 Wi-Fi（或手机热点 / 相机热点），已打开「电脑遥控」并进入配对界面。"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                tickerJob.cancel()
            }
        }
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
                establishManualConnection(config.copy(host = trimmedHost), gen)
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

    override suspend fun listPhotos(maxItems: Int): Result<List<CameraPhotoItem>> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext Result.failure(IllegalStateException("Wi-Fi 相机未连接"))
        }
        runCatching {
            commandMutex.withLock {
                val (storageRc, storageBytes) = executeOperation(SonyPtpIpProtocol.OP_GET_STORAGE_IDS)
                val storageIds = if (storageRc == SonyPtpIpProtocol.RC_OK) {
                    SonyPtpIpProtocol.parseUInt32Array(storageBytes)
                } else {
                    intArrayOf()
                }
                val storagesToQuery = if (storageIds.isNotEmpty()) {
                    storageIds
                } else {
                    intArrayOf(SonyPtpIpProtocol.STORAGE_ALL)
                }

                val collectedHandles = LinkedHashSet<Int>()
                for (storageId in storagesToQuery) {
                    val (jpgRc, jpgBytes) = executeOperation(
                        SonyPtpIpProtocol.OP_GET_OBJECT_HANDLES,
                        intArrayOf(storageId, SonyPtpIpProtocol.FORMAT_EXIF_JPEG, 0)
                    )
                    val jpgHandles = if (jpgRc == SonyPtpIpProtocol.RC_OK) {
                        SonyPtpIpProtocol.parseUInt32Array(jpgBytes)
                    } else {
                        intArrayOf()
                    }
                    if (jpgHandles.isNotEmpty()) {
                        for (h in jpgHandles) {
                            if (h != SonyPtpIpProtocol.LIVEVIEW_HANDLE &&
                                h != SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE
                            ) {
                                collectedHandles.add(h)
                            }
                        }
                    } else {
                        val (allRc, allBytes) = executeOperation(
                            SonyPtpIpProtocol.OP_GET_OBJECT_HANDLES,
                            intArrayOf(storageId, SonyPtpIpProtocol.FORMAT_ALL, 0)
                        )
                        if (allRc == SonyPtpIpProtocol.RC_OK) {
                            val allHandles = SonyPtpIpProtocol.parseUInt32Array(allBytes)
                            for (h in allHandles) {
                                if (h != SonyPtpIpProtocol.LIVEVIEW_HANDLE &&
                                    h != SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE
                                ) {
                                    collectedHandles.add(h)
                                }
                            }
                        }
                    }
                }

                val items = ArrayList<CameraPhotoItem>()
                // 若相机在内存直传模式下有最新拍的照片，优先置顶展示
                if (sdramAvailable.get()) {
                    val (sdramInfoRc, sdramInfoBytes) = executeOperation(
                        SonyPtpIpProtocol.OP_GET_OBJECT_INFO,
                        intArrayOf(SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE)
                    )
                    if (sdramInfoRc == SonyPtpIpProtocol.RC_OK) {
                        val info = SonyPtpIpProtocol.parseObjectInfo(sdramInfoBytes)
                        items.add(
                            CameraPhotoItem(
                                handle = SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE,
                                transport = PtpTransportMode.WIFI_PTP_IP,
                                filename = info?.filename.orEmpty(),
                                formatCode = info?.objectFormat ?: SonyPtpIpProtocol.FORMAT_EXIF_JPEG,
                                sizeBytes = info?.compressedSize ?: 0L,
                                width = info?.imagePixWidth ?: 0,
                                height = info?.imagePixHeight ?: 0,
                                captureDate = info?.captureDate?.let(SonyPtpIpProtocol::formatCaptureDate).orEmpty(),
                                isRaw = info != null && SonyPtpIpProtocol.isRawFormat(info.objectFormat, info.filename),
                                isSdram = true,
                                metadataLoaded = info != null
                            )
                        )
                    }
                }

                // 句柄倒序（最新拍摄排在最前面），并延迟加载 ObjectInfo 与缩略图以实现毫秒级首屏
                val reversed = collectedHandles.toList().asReversed().take(maxItems)
                for (handle in reversed) {
                    items.add(
                        CameraPhotoItem(
                            handle = handle,
                            transport = PtpTransportMode.WIFI_PTP_IP,
                            formatCode = SonyPtpIpProtocol.FORMAT_EXIF_JPEG,
                            metadataLoaded = false
                        )
                    )
                }
                items
            }
        }
    }

    override suspend fun getObjectInfo(item: CameraPhotoItem): Result<CameraPhotoItem> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext Result.failure(IllegalStateException("Wi-Fi 相机未连接"))
        }
        runCatching {
            commandMutex.withLock {
                val (rc, bytes) = executeOperation(
                    SonyPtpIpProtocol.OP_GET_OBJECT_INFO,
                    intArrayOf(item.handle)
                )
                if (rc != SonyPtpIpProtocol.RC_OK) {
                    throw IOException("获取照片信息失败 (0x${rc.toString(16).uppercase()})")
                }
                val info = SonyPtpIpProtocol.parseObjectInfo(bytes)
                    ?: throw IOException("无法解析照片信息")
                item.copy(
                    filename = info.filename.ifBlank { item.filename },
                    formatCode = info.objectFormat,
                    sizeBytes = info.compressedSize,
                    width = info.imagePixWidth,
                    height = info.imagePixHeight,
                    captureDate = SonyPtpIpProtocol.formatCaptureDate(info.captureDate),
                    isRaw = SonyPtpIpProtocol.isRawFormat(info.objectFormat, info.filename),
                    metadataLoaded = true
                )
            }
        }
    }

    override suspend fun getThumbnailBytes(item: CameraPhotoItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext Result.failure(IllegalStateException("Wi-Fi 相机未连接"))
        }
        runCatching {
            commandMutex.withLock {
                val (rc, bytes) = executeOperation(
                    SonyPtpIpProtocol.OP_GET_THUMB,
                    intArrayOf(item.handle)
                )
                if (rc != SonyPtpIpProtocol.RC_OK || bytes.isEmpty()) {
                    throw IOException("获取缩略图失败 (0x${rc.toString(16).uppercase()})")
                }
                bytes
            }
        }
    }

    override suspend fun exportOriginalToFile(
        item: CameraPhotoItem,
        destFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext Result.failure(IllegalStateException("Wi-Fi 相机未连接"))
        }
        runCatching {
            commandMutex.withLock {
                val sock = commandSocket
                val prevTimeout = sock?.soTimeout ?: SOCKET_READ_TIMEOUT_MS
                try {
                    sock?.soTimeout = ORIGINAL_DOWNLOAD_READ_TIMEOUT_MS
                    FileOutputStream(destFile).use { fos ->
                        BufferedOutputStream(fos, STREAM_BUFFER_SIZE).use { bos ->
                            val (rc, totalWritten) = executeOperationToStream(
                                opCode = SonyPtpIpProtocol.OP_GET_OBJECT,
                                params = intArrayOf(item.handle),
                                outputStream = bos,
                                expectedTotalHint = item.sizeBytes,
                                onProgress = onProgress
                            )
                            bos.flush()
                            if (rc != SonyPtpIpProtocol.RC_OK) {
                                throw IOException("下载原图失败 (0x${rc.toString(16).uppercase()})")
                            }
                            if (totalWritten <= 0L) {
                                throw IOException("相机返回了空图像数据")
                            }
                            totalWritten
                        }
                    }
                } finally {
                    runCatching { sock?.soTimeout = prevTimeout }
                }
            }
        }.onFailure {
            runCatching { if (destFile.exists()) destFile.delete() }
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

    private suspend fun discoverCameraEndpoint(
        preferredHost: String?,
        gatewayIp: String?,
        wifiNetwork: Network?,
        subnets: List<LocalSubnet>
    ): DiscoveredEndpoint? {
        val priorityHosts = SonyWifiDiscoveryPolicy.buildPriorityHosts(
            preferredHost = preferredHost,
            gatewayIp = gatewayIp
        )
        val priorityTargets = priorityHosts.map { host ->
            ProbeTarget(host = host, network = resolveNetworkForHost(host, subnets, wifiNetwork))
        }

        // 第 1 阶段：优先探测上次成功 IP / 网关 / 192.168.122.1（通常 50~400ms 完成）
        probeFirstReachable(
            targets = priorityTargets,
            timeoutMs = PRIORITY_PROBE_TIMEOUT_MS,
            concurrency = priorityTargets.size.coerceAtLeast(1)
        )?.let { return it }

        val alreadyProbed = priorityHosts.toMutableSet()

        // 第 2 阶段：SSDP 组播发现 + 局域网 / 热点 /24 子网并发探测 TCP 15740
        val ssdpHosts = discoverViaSsdp().filter { it !in alreadyProbed }
        if (ssdpHosts.isNotEmpty()) {
            alreadyProbed.addAll(ssdpHosts)
            val ssdpTargets = ssdpHosts.map { host ->
                ProbeTarget(host = host, network = resolveNetworkForHost(host, subnets, wifiNetwork))
            }
            probeFirstReachable(
                targets = ssdpTargets,
                timeoutMs = PRIORITY_PROBE_TIMEOUT_MS,
                concurrency = ssdpTargets.size.coerceAtLeast(1)
            )?.let { return it }
        }

        val subnetTargets = ArrayList<ProbeTarget>()
        for (subnet in subnets) {
            val hosts = SonyWifiDiscoveryPolicy.enumerateSubnetHosts(
                localIp = subnet.localIp,
                excludeHosts = alreadyProbed
            )
            for (host in hosts) {
                if (alreadyProbed.add(host)) {
                    subnetTargets.add(ProbeTarget(host = host, network = subnet.network))
                }
            }
        }

        if (subnetTargets.isEmpty()) return null
        return probeFirstReachable(
            targets = subnetTargets,
            timeoutMs = SUBNET_PROBE_TIMEOUT_MS,
            concurrency = SUBNET_SCAN_CONCURRENCY
        )
    }

    private fun discoverViaSsdp(): List<String> {
        val found = LinkedHashSet<String>()
        val queries = listOf(
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: ssdp:all\r\n\r\n",
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n"
        )

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = SSDP_LISTEN_WINDOW_MS
                broadcast = true
            }
            val group = InetAddress.getByName("239.255.255.250")
            for (q in queries) {
                val data = q.toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(DatagramPacket(data, data.size, group, 1900))
                }
            }

            val deadline = System.currentTimeMillis() + SSDP_LISTEN_WINDOW_MS
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                socket.soTimeout = remaining
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val senderIp = packet.address?.hostAddress
                if (SonyWifiDiscoveryPolicy.isValidIpv4(senderIp) &&
                    SonyWifiDiscoveryPolicy.isPrivateLanIpv4(senderIp!!)
                ) {
                    found.add(senderIp)
                }
                SonyWifiDiscoveryPolicy.extractIpFromSsdpResponse(text)?.let { ip ->
                    if (SonyWifiDiscoveryPolicy.isPrivateLanIpv4(ip)) {
                        found.add(ip)
                    }
                }
            }
        } catch (_: Exception) {
            // SSDP 仅作加速手段，失败直接回退到子网扫描
        } finally {
            runCatching { socket?.close() }
        }
        return found.toList()
    }

    private suspend fun probeFirstReachable(
        targets: List<ProbeTarget>,
        timeoutMs: Int,
        concurrency: Int
    ): DiscoveredEndpoint? = coroutineScope {
        if (targets.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<DiscoveredEndpoint?>()
        val claimed = AtomicBoolean(false)
        val semaphore = Semaphore(concurrency)

        val jobs = targets.map { target ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    if (claimed.get() || !isActive) return@withPermit
                    val sock = tryConnectTcp(
                        host = target.host,
                        port = SonyPtpIpProtocol.DEFAULT_PORT,
                        network = target.network,
                        timeoutMs = timeoutMs
                    ) ?: return@withPermit

                    if (claimed.compareAndSet(false, true)) {
                        winner.complete(DiscoveredEndpoint(target.host, target.network, sock))
                    } else {
                        runCatching { sock.close() }
                    }
                }
            }
        }

        val completionWatcher = launch {
            jobs.joinAll()
            if (claimed.compareAndSet(false, true)) {
                winner.complete(null)
            }
        }

        try {
            winner.await()
        } finally {
            completionWatcher.cancel()
            jobs.forEach { it.cancel() }
        }
    }

    private fun tryConnectTcp(
        host: String,
        port: Int,
        network: Network?,
        timeoutMs: Int
    ): Socket? {
        val socket = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: Exception) {
            return null
        }
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket
        } catch (_: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    private fun collectLocalSubnets(wifiNetwork: Network?): List<LocalSubnet> {
        val result = ArrayList<LocalSubnet>()
        val seenPrefixes = HashSet<String>()
        val wifiIps = HashSet<String>()

        val cm = connectivityManager
        if (cm != null && wifiNetwork != null) {
            val linkProps = cm.getLinkProperties(wifiNetwork)
            linkProps?.linkAddresses.orEmpty().forEach { linkAddr ->
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: return@forEach
                    if (SonyWifiDiscoveryPolicy.isPrivateLanIpv4(ip)) {
                        wifiIps.add(ip)
                        val prefix = SonyWifiDiscoveryPolicy.subnetPrefix24(ip) ?: return@forEach
                        if (seenPrefixes.add(prefix)) {
                            result.add(LocalSubnet(localIp = ip, prefix24 = prefix, network = wifiNetwork))
                        }
                    }
                }
            }
        }

        // 同时枚举手机热点 (ap0 / wlan1 / swlan0 / softap0) 等本地局域网接口
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                if (SonyWifiDiscoveryPolicy.isIgnoredInterfaceName(nif.name)) continue

                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!SonyWifiDiscoveryPolicy.isPrivateLanIpv4(ip)) continue
                        val prefix = SonyWifiDiscoveryPolicy.subnetPrefix24(ip) ?: continue
                        if (seenPrefixes.add(prefix)) {
                            val network = if (ip in wifiIps) wifiNetwork else null
                            result.add(LocalSubnet(localIp = ip, prefix24 = prefix, network = network))
                        }
                    }
                }
            }
        }

        return result
    }

    private fun resolveNetworkForHost(
        host: String,
        subnets: List<LocalSubnet>,
        wifiNetwork: Network?
    ): Network? {
        val prefix = SonyWifiDiscoveryPolicy.subnetPrefix24(host)
        if (prefix != null) {
            val matched = subnets.firstOrNull { it.prefix24 == prefix }
            if (matched != null) return matched.network
        }
        return wifiNetwork
    }

    private suspend fun establishManualConnection(config: WifiCameraConfig, gen: Int) {
        val wifiNetwork = connectivityManager?.let(::findWifiNetwork)
        val subnets = collectLocalSubnets(wifiNetwork)
        val targetHost: String
        val targetPort: Int
        val socketNetwork: Network?

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
            socketNetwork = null
        } else {
            targetHost = config.host
            targetPort = SonyPtpIpProtocol.DEFAULT_PORT
            socketNetwork = resolveNetworkForHost(config.host, subnets, wifiNetwork)
        }

        // 手动指定 IP 时也容忍相机尚未进入电脑遥控界面，最多自动等待 30 秒
        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + MANUAL_CONNECT_RETRY_TIMEOUT_MS
        var cmdSock: Socket? = null
        while (sessionGeneration.get() == gen && System.currentTimeMillis() < deadlineMs) {
            val elapsed = ((System.currentTimeMillis() - startMs) / 1_000L).toInt()
            _connectionState.value = LiveViewConnectionState.Searching(
                hint = "正在连接 $targetHost，请确认相机已进入「电脑遥控功能」...",
                elapsedSeconds = elapsed
            )
            cmdSock = tryConnectTcp(targetHost, targetPort, socketNetwork, SOCKET_CONNECT_TIMEOUT_MS)
            if (cmdSock != null) break
            delay(1_000L)
        }

        val connectedSocket = cmdSock
            ?: throw IOException("连接 $targetHost 超时，请确认相机与手机在同一网络并进入「电脑遥控功能」")

        establishPtpIpSession(
            host = config.host,
            port = targetPort,
            socketNetwork = socketNetwork,
            preConnectedCmdSocket = connectedSocket,
            viaSsh = config.useSsh,
            gen = gen,
            connectHostOverride = if (config.useSsh) "127.0.0.1" else config.host
        )
    }

    private suspend fun establishPtpIpSession(
        host: String,
        port: Int,
        socketNetwork: Network?,
        preConnectedCmdSocket: Socket,
        viaSsh: Boolean,
        gen: Int,
        connectHostOverride: String = host
    ) {
        _connectionState.value = LiveViewConnectionState.Connecting("已找到相机 ($host)，正在握手...")
        val cmdSock = preConnectedCmdSocket.apply {
            tcpNoDelay = true
            soTimeout = PAIRING_CONFIRM_TIMEOUT_MS
        }
        commandSocket = cmdSock
        val cIn = DataInputStream(BufferedInputStream(cmdSock.getInputStream()))
        val cOut = BufferedOutputStream(cmdSock.getOutputStream())
        cmdIn = cIn
        cmdOut = cOut

        transactionId = 1
        liveviewInitialized = false

        // 1. 发送 INIT_COMMAND_REQUEST（使用持久化 GUID，避免每次重复配对）
        val guid = clientGuidProvider()
        cOut.write(SonyPtpIpProtocol.buildInitCommandRequest(guid))
        cOut.flush()

        // 若相机在 350ms 内未直接 ACK，说明相机屏幕正在弹出确认框 -> 切换到耐心等待 60 秒的提示状态
        val confirmPromptJob = scope.launch {
            delay(CONFIRM_PROMPT_DELAY_MS)
            val totalSec = (PAIRING_CONFIRM_TIMEOUT_MS / 1_000)
            for (remaining in totalSec downTo 1) {
                if (sessionGeneration.get() != gen) break
                _connectionState.value = LiveViewConnectionState.WaitingForConfirm(
                    host = host,
                    remainingSeconds = remaining
                )
                delay(1_000L)
            }
        }

        val cmdAckPkt = try {
            readPacket(cIn)
        } catch (_: SocketTimeoutException) {
            throw ConfirmTimeoutException()
        } finally {
            confirmPromptJob.cancel()
        }

        if (cmdAckPkt.type == SonyPtpIpProtocol.PTPIP_INIT_FAIL) {
            throw CameraRejectedException()
        }
        if (cmdAckPkt.type != SonyPtpIpProtocol.PTPIP_INIT_COMMAND_ACK) {
            throw IOException("握手响应异常: type=0x${cmdAckPkt.type.toString(16)}")
        }
        val ack = SonyPtpIpProtocol.parseInitCommandAck(cmdAckPkt.body)
            ?: throw IOException("无法解析相机握手响应")
        val cameraName = ack.cameraName ?: "Sony Camera"

        // 握手确认完成后恢复常规读超时
        cmdSock.soTimeout = SOCKET_READ_TIMEOUT_MS

        if (sessionGeneration.get() != gen) return

        // 2. 按 PTP/IP 规范在收到 INIT_COMMAND_ACK 后再建立事件通道，避免等待确认期间事件连接超时断开
        _connectionState.value = LiveViewConnectionState.Connecting("已确认，正在开启实时预览...")
        val evtSock = createConnectedSocket(connectHostOverride, port, socketNetwork)
        evtSock.soTimeout = EVENT_SOCKET_READ_TIMEOUT_MS
        eventSocket = evtSock
        val eIn = DataInputStream(BufferedInputStream(evtSock.getInputStream()))
        val eOut = BufferedOutputStream(evtSock.getOutputStream())
        eventIn = eIn
        eventOut = eOut

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
        _connectionState.value = LiveViewConnectionState.Connecting("正在初始化相机画面...")
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
            host = host,
            viaSsh = viaSsh
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
                            SonyPtpIpProtocol.PTPIP_EVENT -> {
                                handleIncomingPtpEvent(pkt.body)
                            }
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

    private fun handleIncomingPtpEvent(body: ByteArray) {
        val evt = SonyPtpIpProtocol.parseEventPacket(body) ?: return
        when (evt.eventCode) {
            SonyPtpIpProtocol.EVENT_OBJECT_ADDED -> {
                val handle = evt.param1 ?: return
                if (handle != SonyPtpIpProtocol.LIVEVIEW_HANDLE) {
                    _events.tryEmit(
                        PtpEvent.ObjectAdded(
                            handle = handle,
                            transport = PtpTransportMode.WIFI_PTP_IP,
                            isSdram = handle == SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE
                        )
                    )
                }
            }
            SonyPtpIpProtocol.EVENT_SONY_OBJECT_ADDED_IN_SDRAM -> {
                sdramAvailable.set(true)
                _events.tryEmit(
                    PtpEvent.ObjectAdded(
                        handle = SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE,
                        transport = PtpTransportMode.WIFI_PTP_IP,
                        isSdram = true
                    )
                )
            }
            SonyPtpIpProtocol.EVENT_SONY_OBJECT_REMOVED_IN_SDRAM -> {
                sdramAvailable.set(false)
                _events.tryEmit(
                    PtpEvent.ObjectRemoved(
                        handle = SonyPtpIpProtocol.SDRAM_IMAGE_HANDLE,
                        transport = PtpTransportMode.WIFI_PTP_IP
                    )
                )
            }
            SonyPtpIpProtocol.EVENT_OBJECT_REMOVED -> {
                val handle = evt.param1 ?: return
                _events.tryEmit(
                    PtpEvent.ObjectRemoved(
                        handle = handle,
                        transport = PtpTransportMode.WIFI_PTP_IP
                    )
                )
            }
            SonyPtpIpProtocol.EVENT_STORE_ADDED,
            SonyPtpIpProtocol.EVENT_STORE_REMOVED -> {
                _events.tryEmit(PtpEvent.StorageChanged(PtpTransportMode.WIFI_PTP_IP))
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
                if (liveViewPaused.get()) {
                    delay(120L)
                    continue
                }
                try {
                    val (rc, payload) = commandMutex.withLock {
                        if (liveViewPaused.get()) {
                            return@withLock SonyPtpIpProtocol.RC_DEVICE_BUSY to ByteArray(0)
                        }
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
                    _events.tryEmit(
                        PtpEvent.TransportDisconnected(PtpTransportMode.WIFI_PTP_IP, message)
                    )
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

    /**
     * 流式执行 PTP/IP 读取指令（用于下载 10~80MB 原图，不将整图一次性读入堆内存）。
     */
    private fun executeOperationToStream(
        opCode: Int,
        params: IntArray,
        outputStream: OutputStream,
        expectedTotalHint: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Pair<Int, Long> {
        val input = cmdIn ?: throw IOException("Command channel closed")
        val output = cmdOut ?: throw IOException("Command channel closed")
        val txId = transactionId++

        output.write(SonyPtpIpProtocol.buildOperationRequest(opCode, txId, params))
        output.flush()

        val header = ByteArray(8)
        val chunkBuf = ByteArray(STREAM_BUFFER_SIZE)
        var totalExpected = expectedTotalHint.coerceAtLeast(0L)
        var totalWritten = 0L

        while (true) {
            input.readFully(header)
            val (length, type) = SonyPtpIpProtocol.parsePacketHeader(header)
            if (length < 8 || length > MAX_STREAM_PACKET_SIZE_BYTES) {
                throw IOException("无效的 PTP/IP 数据包长度: $length")
            }
            val bodyLen = length - 8

            when (type) {
                SonyPtpIpProtocol.PTPIP_DATA,
                SonyPtpIpProtocol.PTPIP_END_DATA -> {
                    if (bodyLen >= 4) {
                        val txBuf = ByteArray(4)
                        input.readFully(txBuf)
                        val pktTxId = SonyPtpIpProtocol.readInt32Le(txBuf, 0)
                        var remaining = bodyLen - 4
                        while (remaining > 0) {
                            val toRead = remaining.coerceAtMost(chunkBuf.size)
                            input.readFully(chunkBuf, 0, toRead)
                            if (pktTxId == txId) {
                                outputStream.write(chunkBuf, 0, toRead)
                                totalWritten += toRead
                                onProgress(totalWritten, totalExpected)
                            }
                            remaining -= toRead
                        }
                    } else if (bodyLen > 0) {
                        input.skipBytes(bodyLen)
                    }
                }
                else -> {
                    val body = ByteArray(bodyLen)
                    if (bodyLen > 0) {
                        input.readFully(body)
                    }
                    when (type) {
                        SonyPtpIpProtocol.PTPIP_PROBE_REQ -> {
                            output.write(SonyPtpIpProtocol.buildProbeResponse())
                            output.flush()
                        }
                        SonyPtpIpProtocol.PTPIP_PROBE_RESP -> Unit
                        SonyPtpIpProtocol.PTPIP_START_DATA -> {
                            if (body.size >= 12 && SonyPtpIpProtocol.readInt32Le(body, 0) == txId) {
                                val declaredLow = SonyPtpIpProtocol.readUInt32Le(body, 4)
                                val declaredHigh = SonyPtpIpProtocol.readUInt32Le(body, 8)
                                val declared = declaredLow or (declaredHigh shl 32)
                                if (declared > 0L) {
                                    totalExpected = declared
                                    onProgress(0L, totalExpected)
                                }
                            }
                        }
                        SonyPtpIpProtocol.PTPIP_CANCEL -> {
                            if (body.size >= 4 && SonyPtpIpProtocol.readInt32Le(body, 0) == txId) {
                                throw IOException("相机取消了传输")
                            }
                        }
                        SonyPtpIpProtocol.PTPIP_OPER_RESP -> {
                            val resp = SonyPtpIpProtocol.parseOperationResponse(body)
                            if (resp != null && resp.transactionId == txId) {
                                return resp.responseCode to totalWritten
                            }
                        }
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
        sdramAvailable.set(false)
        liveViewPaused.set(false)
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
            error is CameraRejectedException || error is ConfirmTimeoutException -> raw
            error is JSchException && raw.contains("Auth fail", ignoreCase = true) ->
                "SSH 用户名或密码错误，请核对相机「访问认证信息」"
            error is JSchException && raw.contains("timeout", ignoreCase = true) ->
                "SSH 连接超时，请确认手机已连接相机 Wi-Fi"
            raw.contains("ECONNREFUSED", ignoreCase = true) ->
                if (usedSsh) {
                    "相机拒绝 SSH (22) 连接，请确认已开启「PC 遥控」"
                } else {
                    "相机尚未开放连接，请先在相机进入「电脑遥控功能」界面后重试"
                }
            raw.contains("ETIMEDOUT", ignoreCase = true) || error is SocketTimeoutException ->
                "连接相机超时，请确认手机与相机在同一 Wi-Fi（或热点）"
            raw.isNotBlank() -> raw
            else -> "Wi-Fi 连接失败"
        }
    }

    private companion object {
        const val AUTO_DISCOVERY_TIMEOUT_MS = 90_000L
        const val MANUAL_CONNECT_RETRY_TIMEOUT_MS = 30_000L
        const val DISCOVERY_RETRY_INTERVAL_MS = 1_000L
        const val PRIORITY_PROBE_TIMEOUT_MS = 450
        const val SUBNET_PROBE_TIMEOUT_MS = 350
        const val SUBNET_SCAN_CONCURRENCY = 48
        const val SSDP_LISTEN_WINDOW_MS = 650
        const val CONFIRM_PROMPT_DELAY_MS = 350L
        const val PAIRING_CONFIRM_TIMEOUT_MS = 60_000
        const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
        const val SOCKET_READ_TIMEOUT_MS = 5_000
        const val ORIGINAL_DOWNLOAD_READ_TIMEOUT_MS = 30_000
        const val EVENT_SOCKET_READ_TIMEOUT_MS = 10_000
        const val SSH_CONNECT_TIMEOUT_MS = 10_000
        const val KEEPALIVE_INTERVAL_MS = 20_000L
        const val FRAME_INTERVAL_DELAY_MS = 15L
        const val STREAM_BUFFER_SIZE = 32 * 1024
        const val MAX_PACKET_SIZE_BYTES = 10_000_000
        const val MAX_STREAM_PACKET_SIZE_BYTES = 256 * 1024 * 1024
    }
}
