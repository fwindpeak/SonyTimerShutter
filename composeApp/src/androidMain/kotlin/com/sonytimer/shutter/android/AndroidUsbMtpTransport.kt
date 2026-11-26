package com.sonytimer.shutter.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpEvent
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.core.content.ContextCompat
import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.PtpEvent
import com.sonytimer.shutter.controller.PtpTransportMode
import com.sonytimer.shutter.controller.UsbConnectionState
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 室外有线模式：纯 Kotlin 零依赖 USB MTP/PTP 传输实现（基于 Android 原生 android.mtp.MtpDevice）。
 *
 * 特性：
 * 1. 自动识别索尼相机（VID = 0x054C / 1356），插线即连并申请 USB 权限。
 * 2. 毫秒级拉取全卡 JPG 句柄列表，按需调用 MtpDevice.getThumbnail() 零原图渲染缩略图。
 * 3. 原图下载直接走原生 MtpDevice.importFile() 底层流式导出，不占 JVM 堆内存。
 * 4. 后台协程监听 MtpDevice.readEvent()，新拍照片自动通过统一 Flow<PtpEvent> 推送给图库。
 */
class AndroidUsbMtpTransport(
    context: Context
) : AndroidPtpTransport {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mtpMutex = Mutex()
    private val lifecycleMutex = Mutex()

    override val mode: PtpTransportMode = PtpTransportMode.USB_MTP

    private val _usbState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val usbState: StateFlow<UsbConnectionState> = _usbState.asStateFlow()

    override val isConnected: Boolean
        get() = _usbState.value is UsbConnectionState.Connected && mtpDevice != null

    private val _events = MutableSharedFlow<PtpEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<PtpEvent> = _events.asSharedFlow()

    @Volatile
    private var activeDevice: UsbDevice? = null
    @Volatile
    private var usbConnection: UsbDeviceConnection? = null
    @Volatile
    private var mtpDevice: MtpDevice? = null
    @Volatile
    private var eventSignal: CancellationSignal? = null

    private var eventLoopJob: Job? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = extractUsbDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) {
                        openDeviceAsync(device)
                    } else if (device != null) {
                        _usbState.value = UsbConnectionState.Error("未授予 USB 访问权限，请重新插拔或在弹窗中点击允许")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = extractUsbDevice(intent)
                    if (device != null && isSonyCameraDevice(device)) {
                        requestOrOpenDevice(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extractUsbDevice(intent)
                    val current = activeDevice
                    if (device != null && (current == null || device.deviceId == current.deviceId)) {
                        scope.launch {
                            lifecycleMutex.withLock {
                                closeSessionLocked(emitDisconnected = true, reason = "USB 数据线已断开")
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        registerReceiver()
        // 启动时静默检查是否已经插着索尼相机（不主动弹权限框打扰，仅在已授权时自动建立会话）
        autoConnectIfAlreadyPermitted()
    }

    fun handleUsbAttachedIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = extractUsbDevice(intent) ?: return
        if (isSonyCameraDevice(device)) {
            requestOrOpenDevice(device)
        }
    }

    fun scanAndConnect() {
        val manager = usbManager
        if (manager == null) {
            _usbState.value = UsbConnectionState.Error("当前设备不支持 USB Host (OTG)")
            return
        }
        val sonyDevice = manager.deviceList.values.firstOrNull(::isSonyCameraDevice)
        if (sonyDevice == null) {
            _usbState.value = UsbConnectionState.Error(
                "未检测到 USB 连接的索尼相机，请用 Type-C 线直连并将相机 USB 模式设为「MTP」或「电脑遥控」"
            )
            return
        }
        requestOrOpenDevice(sonyDevice)
    }

    fun disconnect() {
        scope.launch {
            lifecycleMutex.withLock {
                closeSessionLocked(emitDisconnected = false, reason = null)
            }
        }
    }

    override suspend fun listPhotos(maxItems: Int): Result<List<CameraPhotoItem>> = withContext(Dispatchers.IO) {
        val device = mtpDevice ?: return@withContext Result.failure(IllegalStateException("USB 相机未连接"))
        runCatching {
            mtpMutex.withLock {
                val storageIds = device.storageIds ?: intArrayOf()
                if (storageIds.isEmpty()) {
                    return@withLock emptyList()
                }
                val collectedHandles = LinkedHashSet<Int>()
                for (storageId in storageIds) {
                    val jpegHandles = device.getObjectHandles(
                        storageId,
                        MtpConstants.FORMAT_EXIF_JPEG,
                        0
                    )
                    if (jpegHandles != null && jpegHandles.isNotEmpty()) {
                        for (h in jpegHandles) {
                            collectedHandles.add(h)
                        }
                    } else {
                        val allHandles = device.getObjectHandles(storageId, 0, 0)
                        if (allHandles != null) {
                            for (h in allHandles) {
                                collectedHandles.add(h)
                            }
                        }
                    }
                }

                // 句柄倒序（最新拍摄排在最前面），延迟按需拉取缩略图
                val newest = collectedHandles.toList().asReversed().take(maxItems)
                newest.map { handle ->
                    CameraPhotoItem(
                        handle = handle,
                        transport = PtpTransportMode.USB_MTP,
                        formatCode = MtpConstants.FORMAT_EXIF_JPEG,
                        metadataLoaded = false
                    )
                }
            }
        }
    }

    override suspend fun getObjectInfo(item: CameraPhotoItem): Result<CameraPhotoItem> = withContext(Dispatchers.IO) {
        val device = mtpDevice ?: return@withContext Result.failure(IllegalStateException("USB 相机未连接"))
        runCatching {
            mtpMutex.withLock {
                val info = device.getObjectInfo(item.handle)
                    ?: throw IOException("无法读取照片信息 (#${item.handle})")
                val name = info.name.orEmpty()
                val size = info.compressedSizeLong.takeIf { it > 0L } ?: info.compressedSize.toLong()
                val captureDate = if (info.dateCreated > 0L) {
                    formatEpochMillis(info.dateCreated)
                } else if (info.dateModified > 0L) {
                    formatEpochMillis(info.dateModified)
                } else {
                    ""
                }
                item.copy(
                    filename = name.ifBlank { item.filename },
                    formatCode = info.format,
                    sizeBytes = size.coerceAtLeast(0L),
                    width = info.imagePixWidth,
                    height = info.imagePixHeight,
                    captureDate = captureDate,
                    isRaw = SonyPtpIpProtocol.isRawFormat(info.format, name),
                    metadataLoaded = true
                )
            }
        }
    }

    override suspend fun getThumbnailBytes(item: CameraPhotoItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        val device = mtpDevice ?: return@withContext Result.failure(IllegalStateException("USB 相机未连接"))
        runCatching {
            mtpMutex.withLock {
                val bytes = device.getThumbnail(item.handle)
                if (bytes == null || bytes.isEmpty()) {
                    throw IOException("该照片无内嵌缩略图")
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
        val device = mtpDevice ?: return@withContext Result.failure(IllegalStateException("USB 相机未连接"))
        runCatching {
            mtpMutex.withLock {
                val expectedSize = item.sizeBytes.coerceAtLeast(0L)
                onProgress(0L, expectedSize)

                val imported = device.importFile(item.handle, destFile.absolutePath)
                if (imported && destFile.exists() && destFile.length() > 0L) {
                    val actualSize = destFile.length()
                    onProgress(actualSize, actualSize)
                    return@withLock actualSize
                }

                // 回退：部分机身/格式若 importFile 返回 false，则通过 getObject 读取
                val objInfo = device.getObjectInfo(item.handle)
                val objSize = objInfo?.compressedSize ?: item.sizeBytes.toInt()
                if (objSize <= 0 || objSize > MAX_FALLBACK_IN_MEMORY_BYTES) {
                    throw IOException("USB 导入原图失败，请确认相机未进入休眠")
                }
                val bytes = device.getObject(item.handle, objSize)
                    ?: throw IOException("USB 读取原图失败")
                FileOutputStream(destFile).use { it.write(bytes) }
                val actualSize = bytes.size.toLong()
                onProgress(actualSize, actualSize)
                actualSize
            }
        }.onFailure {
            runCatching { if (destFile.exists()) destFile.delete() }
        }
    }

    fun release() {
        unregisterReceiver()
        runCatching { eventSignal?.cancel() }
        eventLoopJob?.cancel()
        runCatching { mtpDevice?.close() }
        runCatching { usbConnection?.close() }
        mtpDevice = null
        usbConnection = null
        activeDevice = null
        scope.cancel()
    }

    private fun autoConnectIfAlreadyPermitted() {
        val manager = usbManager ?: return
        val sonyDevice = manager.deviceList.values.firstOrNull(::isSonyCameraDevice) ?: return
        if (manager.hasPermission(sonyDevice)) {
            openDeviceAsync(sonyDevice)
        } else {
            val label = sonyDevice.productName?.takeIf { it.isNotBlank() } ?: "Sony Camera"
            _usbState.value = UsbConnectionState.PermissionRequired(label)
        }
    }

    private fun requestOrOpenDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        val label = device.productName?.takeIf { it.isNotBlank() } ?: "Sony Camera"
        if (manager.hasPermission(device)) {
            openDeviceAsync(device)
        } else {
            _usbState.value = UsbConnectionState.PermissionRequired(label)
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
            manager.requestPermission(device, pendingIntent)
        }
    }

    private fun openDeviceAsync(device: UsbDevice) {
        scope.launch {
            lifecycleMutex.withLock {
                val manager = usbManager ?: return@withLock
                val label = device.productName?.takeIf { it.isNotBlank() } ?: "Sony Camera"
                if (activeDevice?.deviceId == device.deviceId && mtpDevice != null) {
                    return@withLock
                }
                closeSessionLocked(emitDisconnected = false, reason = null)
                _usbState.value = UsbConnectionState.Connecting(label)

                val connection = manager.openDevice(device)
                if (connection == null) {
                    _usbState.value = UsbConnectionState.Error("无法打开 USB 设备连接，请重新插拔数据线")
                    return@withLock
                }

                val mtp = MtpDevice(device)
                val opened = runCatching { mtp.open(connection) }.getOrDefault(false)
                if (!opened) {
                    runCatching { mtp.close() }
                    runCatching { connection.close() }
                    _usbState.value = UsbConnectionState.Error(
                        "MTP 会话打开失败，请确认相机 USB 连接模式为「MTP」或「电脑遥控」且未被其他 App 占用"
                    )
                    return@withLock
                }

                activeDevice = device
                usbConnection = connection
                mtpDevice = mtp
                val productName = runCatching { mtp.deviceInfo?.model }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: label
                _usbState.value = UsbConnectionState.Connected(
                    deviceName = device.deviceName,
                    productName = productName
                )
                startUsbEventLoop(mtp)
            }
        }
    }

    private fun startUsbEventLoop(device: MtpDevice) {
        eventLoopJob?.cancel()
        val signal = CancellationSignal()
        eventSignal = signal
        eventLoopJob = scope.launch {
            try {
                while (isActive && mtpDevice === device) {
                    val event = try {
                        device.readEvent(signal)
                    } catch (_: OperationCanceledException) {
                        break
                    } catch (_: IOException) {
                        break
                    } ?: continue

                    when (event.eventCode) {
                        MtpEvent.EVENT_OBJECT_ADDED -> {
                            val handle = runCatching { event.objectHandle }.getOrNull() ?: continue
                            _events.tryEmit(
                                PtpEvent.ObjectAdded(
                                    handle = handle,
                                    transport = PtpTransportMode.USB_MTP,
                                    isSdram = false
                                )
                            )
                        }
                        MtpEvent.EVENT_OBJECT_REMOVED -> {
                            val handle = runCatching { event.objectHandle }.getOrNull() ?: continue
                            _events.tryEmit(
                                PtpEvent.ObjectRemoved(
                                    handle = handle,
                                    transport = PtpTransportMode.USB_MTP
                                )
                            )
                        }
                        MtpEvent.EVENT_STORE_ADDED,
                        MtpEvent.EVENT_STORE_REMOVED -> {
                            _events.tryEmit(PtpEvent.StorageChanged(PtpTransportMode.USB_MTP))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun closeSessionLocked(emitDisconnected: Boolean, reason: String?) {
        runCatching { eventSignal?.cancel() }
        eventSignal = null
        eventLoopJob?.cancel()
        eventLoopJob = null

        runCatching { mtpDevice?.close() }
        runCatching { usbConnection?.close() }
        mtpDevice = null
        usbConnection = null
        activeDevice = null
        _usbState.value = UsbConnectionState.Disconnected

        if (emitDisconnected) {
            _events.tryEmit(PtpEvent.TransportDisconnected(PtpTransportMode.USB_MTP, reason))
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(usbReceiver) }
        receiverRegistered = false
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun isSonyCameraDevice(device: UsbDevice): Boolean {
        return device.vendorId == SONY_USB_VENDOR_ID
    }

    private fun formatEpochMillis(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    companion object {
        const val SONY_USB_VENDOR_ID: Int = 0x054C // 1356
        private const val ACTION_USB_PERMISSION = "com.sonytimer.shutter.USB_PERMISSION"
        private const val MAX_FALLBACK_IN_MEMORY_BYTES = 64 * 1024 * 1024
    }
}
