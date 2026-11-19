package com.example.printer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.UsbDeviceDetails
import com.example.model.UsbPortFlags
import com.example.model.UsbPrinterStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages USB OTG Host discovery, permissions, IEEE 1284 printer telemetry,
 * and bulk-OUT data transfer for Samsung SCX-3200 and USB Printer Class 0x07 devices.
 */
class UsbPrinterManager(private val context: Context) {

    companion object {
        const val SAMSUNG_VENDOR_ID = 0x04E8
        const val SCX_3200_PRODUCT_ID = 0x3441
        const val ACTION_USB_PERMISSION = "com.example.scxprint.USB_PERMISSION"
        private const val USB_TRANSFER_CHUNK_BYTES = 16 * 1024
        private const val USB_TIMEOUT_MS = 15_000
    }

    private val appContext = context.applicationContext
    private val usbManager: UsbManager? =
        appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val transferMutex = Mutex()
    private val _status = MutableStateFlow<UsbPrinterStatus>(UsbPrinterStatus.Disconnected)
    val status: StateFlow<UsbPrinterStatus> = _status.asStateFlow()

    private var activeDevice: UsbDevice? = null
    private var receiversRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device != null && !granted) {
                _status.value = UsbPrinterStatus.PermissionRequired(buildBaseDetails(device))
            } else {
                refreshDevices()
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDevices()
            }
        }
    }

    fun register() {
        if (receiversRegistered) return
        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            permFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbAttachReceiver,
            attachFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiversRegistered = true
        refreshDevices()
    }

    fun unregister() {
        if (!receiversRegistered) return
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(usbAttachReceiver) }
        receiversRegistered = false
    }

    fun isUsbHostSupported(): Boolean {
        return appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) &&
            usbManager != null
    }

    fun refreshDevices() {
        if (!isUsbHostSupported()) {
            activeDevice = null
            _status.value = UsbPrinterStatus.UsbNotSupported
            return
        }

        val manager = usbManager ?: run {
            _status.value = UsbPrinterStatus.UsbNotSupported
            return
        }

        val candidates = manager.deviceList.values.filter { isCompatiblePrinter(it) }
        val selected = candidates.firstOrNull {
            it.vendorId == SAMSUNG_VENDOR_ID && it.productId == SCX_3200_PRODUCT_ID
        } ?: candidates.firstOrNull()

        if (selected == null) {
            activeDevice = null
            _status.value = UsbPrinterStatus.Disconnected
            return
        }

        activeDevice = selected
        if (!manager.hasPermission(selected)) {
            _status.value = UsbPrinterStatus.PermissionRequired(buildBaseDetails(selected))
            return
        }

        val details = queryLiveTelemetry(manager, selected)
        _status.value = UsbPrinterStatus.Connected(details = details, isBusy = false)
    }

    fun requestUsbPermission() {
        val manager = usbManager ?: return
        val device = activeDevice ?: run {
            refreshDevices()
            activeDevice
        } ?: return

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(appContext.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        manager.requestPermission(device, pendingIntent)
    }

    suspend fun sendSoftReset(): Result<Unit> = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            val manager = usbManager ?: return@withLock Result.failure(IllegalStateException("USB Host unavailable"))
            val device = activeDevice ?: return@withLock Result.failure(IllegalStateException("No USB printer connected"))
            if (!manager.hasPermission(device)) {
                return@withLock Result.failure(SecurityException("USB permission not granted"))
            }
            val printerIface = findPrinterInterface(device)
                ?: return@withLock Result.failure(IllegalStateException("USB Printer Class 0x07 interface missing"))
            val conn = manager.openDevice(device)
                ?: return@withLock Result.failure(IllegalStateException("Unable to open USB OTG connection"))

            try {
                if (!conn.claimInterface(printerIface, true)) {
                    return@withLock Result.failure(IllegalStateException("Printer interface busy"))
                }
                val rc = conn.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01,
                    0x02, // SOFT_RESET
                    0,
                    printerIface.id,
                    null,
                    0,
                    5000
                )
                if (rc < 0) {
                    Result.failure(IllegalStateException("Soft reset failed ($rc)"))
                } else {
                    refreshDevices()
                    Result.success(Unit)
                }
            } finally {
                runCatching { conn.releaseInterface(printerIface) }
                runCatching { conn.close() }
            }
        }
    }

    /**
     * Streams encoded QPDL/SPL byte chunks to the printer's Bulk-OUT endpoint.
     */
    suspend fun streamPrintJob(
        chunks: Sequence<ByteArray>,
        estimatedTotalBytes: Long,
        onBytesSent: (bytesSent: Long, speedKbPerSec: Float) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            val manager = usbManager
                ?: return@withLock Result.failure(IllegalStateException("USB Host unavailable on this device"))
            val device = activeDevice
                ?: return@withLock Result.failure(IllegalStateException("Samsung SCX-3200 not connected via OTG"))
            if (!manager.hasPermission(device)) {
                _status.value = UsbPrinterStatus.PermissionRequired(buildBaseDetails(device))
                return@withLock Result.failure(SecurityException("USB OTG permission required"))
            }

            val printerIface = findPrinterInterface(device)
                ?: return@withLock Result.failure(IllegalStateException("No USB Printer Class (0x07) interface found"))
            val bulkOut = findBulkOutEndpoint(printerIface)
                ?: return@withLock Result.failure(IllegalStateException("No USB Bulk-OUT endpoint found"))

            val connection = manager.openDevice(device)
                ?: return@withLock Result.failure(IllegalStateException("Failed to open USB device handle"))

            val baseDetails = queryTelemetryWithConnection(connection, printerIface, device)
            if (baseDetails.portFlags.paperEmpty) {
                runCatching { connection.close() }
                _status.value = UsbPrinterStatus.Connected(baseDetails, isBusy = false)
                return@withLock Result.failure(IllegalStateException("Printer reports Paper Tray Empty"))
            }

            _status.value = UsbPrinterStatus.Connected(baseDetails, isBusy = true)
            val startTimeNs = System.nanoTime()
            var totalSent = 0L

            try {
                if (!connection.claimInterface(printerIface, true)) {
                    return@withLock Result.failure(IllegalStateException("Could not claim USB printer interface"))
                }

                for (chunk in chunks) {
                    currentCoroutineContext().ensureActive()
                    var offset = 0
                    while (offset < chunk.size) {
                        currentCoroutineContext().ensureActive()
                        val toSend = minOf(USB_TRANSFER_CHUNK_BYTES, chunk.size - offset)
                        val slice = if (offset == 0 && toSend == chunk.size) {
                            chunk
                        } else {
                            chunk.copyOfRange(offset, offset + toSend)
                        }

                        val written = connection.bulkTransfer(bulkOut, slice, toSend, USB_TIMEOUT_MS)
                        if (written <= 0) {
                            throw IllegalStateException("USB bulk transfer interrupted (code $written)")
                        }
                        offset += written
                        totalSent += written

                        val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000f).coerceAtLeast(0.05f)
                        val speedKb = (totalSent / 1024f) / elapsedSec
                        onBytesSent(totalSent, speedKb)
                    }
                }
                Result.success(totalSent)
            } catch (ce: CancellationException) {
                // Attempt soft reset on cancellation so partial page does not stall printer
                runCatching {
                    connection.controlTransfer(
                        UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01,
                        0x02,
                        0,
                        printerIface.id,
                        null,
                        0,
                        2000
                    )
                }
                throw ce
            } catch (t: Throwable) {
                _status.value = UsbPrinterStatus.Error(t.message ?: "USB transfer failed", baseDetails)
                Result.failure(t)
            } finally {
                runCatching { connection.releaseInterface(printerIface) }
                runCatching { connection.close() }
                refreshDevices()
            }
        }
    }

    private fun isCompatiblePrinter(device: UsbDevice): Boolean {
        if (device.vendorId == SAMSUNG_VENDOR_ID && device.productId == SCX_3200_PRODUCT_ID) {
            return true
        }
        return findPrinterInterface(device) != null
    }

    private fun findPrinterInterface(device: UsbDevice): UsbInterface? {
        var fallback: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                if (iface.interfaceProtocol == 2) return iface // Bidirectional preferred
                if (fallback == null) fallback = iface
            }
        }
        return fallback
    }

    private fun findBulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
            ) {
                return ep
            }
        }
        return null
    }

    private fun buildBaseDetails(device: UsbDevice): UsbDeviceDetails {
        val isScx = device.vendorId == SAMSUNG_VENDOR_ID && device.productId == SCX_3200_PRODUCT_ID
        return UsbDeviceDetails(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = device.manufacturerName ?: if (isScx) "Samsung Electronics" else "USB Printer",
            productName = device.productName ?: if (isScx) "SCX-3200 Series" else "USB Printer Class 0x07",
            serialNumber = runCatching { device.serialNumber }.getOrNull(),
            ieee1284Id = null,
            isScx3200 = isScx,
            portFlags = UsbPortFlags()
        )
    }

    private fun queryLiveTelemetry(manager: UsbManager, device: UsbDevice): UsbDeviceDetails {
        val printerIface = findPrinterInterface(device) ?: return buildBaseDetails(device)
        val conn = manager.openDevice(device) ?: return buildBaseDetails(device)
        return try {
            if (!conn.claimInterface(printerIface, true)) {
                return buildBaseDetails(device)
            }
            queryTelemetryWithConnection(conn, printerIface, device)
        } finally {
            runCatching { conn.releaseInterface(printerIface) }
            runCatching { conn.close() }
        }
    }

    private fun queryTelemetryWithConnection(
        conn: UsbDeviceConnection,
        printerIface: UsbInterface,
        device: UsbDevice
    ): UsbDeviceDetails {
        val base = buildBaseDetails(device)

        // USB Printer Class GET_DEVICE_ID (request 0x00)
        val idBuf = ByteArray(1024)
        val idRead = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01,
            0x00,
            0,
            printerIface.id,
            idBuf,
            idBuf.size,
            2000
        )
        val ieee1284 = if (idRead > 2) {
            val declaredLen = ((idBuf[0].toInt() and 0xFF) shl 8) or (idBuf[1].toInt() and 0xFF)
            val usableLen = minOf(declaredLen, idRead).coerceAtLeast(2) - 2
            if (usableLen > 0) {
                String(idBuf, 2, usableLen, Charsets.US_ASCII).trim('\u0000', ' ')
            } else null
        } else null

        // USB Printer Class GET_PORT_STATUS (request 0x01)
        val statusBuf = ByteArray(1)
        val statusRead = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01,
            0x01,
            0,
            printerIface.id,
            statusBuf,
            1,
            2000
        )
        val flags = if (statusRead >= 1) {
            val b = statusBuf[0].toInt() and 0xFF
            UsbPortFlags(
                paperEmpty = (b and 0x20) != 0,
                selected = (b and 0x10) != 0,
                noError = (b and 0x08) != 0
            )
        } else {
            UsbPortFlags()
        }

        return base.copy(
            ieee1284Id = ieee1284,
            portFlags = flags
        )
    }
}
