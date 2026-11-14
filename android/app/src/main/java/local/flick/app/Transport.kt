package local.flick.app

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder

data class Mac(val name: String, val host: String, val port: Int) {
    val base: String get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"
}

class NotPairedException : Exception("Not paired")
class DeniedException : Exception("Pairing was declined on the Mac")

/** Talks to the Flick Receiver app on the Mac. Every call blocks; run them off the main thread. */
object Transport {
    const val DEFAULT_PORT = 47531

    fun hello(mac: Mac): String? = try {
        val connection = open(mac, "/hello", "GET", readTimeout = 3000)
        connection.connectTimeout = 2500
        if (connection.responseCode == 200) JSONObject(connection.body()).optString("name") else null
    } catch (e: Exception) {
        null
    }

    /** Asks the Mac to show its Allow dialog and waits for the answer. */
    fun pair(mac: Mac, deviceName: String): String {
        val connection = open(mac, "/pair", "POST", readTimeout = 120_000)
        connection.setRequestProperty("X-Flick-Device", encode(deviceName))
        connection.setFixedLengthStreamingMode(0)
        connection.doOutput = true
        connection.outputStream.close()
        if (connection.responseCode != 200) throw DeniedException()
        return JSONObject(connection.body()).getString("token")
    }

    fun sendFile(
        context: Context, mac: Mac, token: String, item: Item,
        index: Int, count: Int, power: Float, onProgress: (Float) -> Unit,
    ) {
        var temp: File? = null
        try {
            var length = item.size
            val input: InputStream
            if (length < 0) {
                // Some providers do not report a size; spool to cache so the upload has one.
                temp = File.createTempFile("flick", null, context.cacheDir)
                context.contentResolver.openInputStream(item.uri)!!.use { source ->
                    temp.outputStream().use { source.copyTo(it) }
                }
                length = temp.length()
                input = temp.inputStream()
            } else {
                input = context.contentResolver.openInputStream(item.uri)
                    ?: throw IllegalStateException("Cannot open ${item.name}")
            }

            val connection = open(mac, "/send", "POST", readTimeout = 60_000)
            connection.setRequestProperty("X-Flick-Token", token)
            connection.setRequestProperty("X-Flick-Name", encode(item.name))
            connection.setRequestProperty("X-Flick-Index", index.toString())
            connection.setRequestProperty("X-Flick-Count", count.toString())
            connection.setRequestProperty("X-Flick-Power", power.toString())
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(length)
            connection.doOutput = true

            input.use { source ->
                connection.outputStream.use { sink ->
                    val buffer = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                        if (length > 0) onProgress(sent.toFloat() / length)
                    }
                }
            }
            check(connection)
        } finally {
            temp?.delete()
        }
    }

    fun sendText(mac: Mac, token: String, text: String, power: Float) {
        val bytes = text.toByteArray()
        val connection = open(mac, "/text", "POST", readTimeout = 15_000)
        connection.setRequestProperty("X-Flick-Token", token)
        connection.setRequestProperty("X-Flick-Power", power.toString())
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.doOutput = true
        connection.outputStream.use { it.write(bytes) }
        check(connection)
    }

    private fun check(connection: HttpURLConnection) {
        when (val code = connection.responseCode) {
            200 -> Unit
            401 -> throw NotPairedException()
            else -> {
                val reason = runCatching { JSONObject(connection.body()).optString("error") }.getOrNull()
                throw IllegalStateException(if (reason.isNullOrEmpty()) "The Mac answered $code" else "Mac: $reason")
            }
        }
    }

    private fun open(mac: Mac, path: String, method: String, readTimeout: Int): HttpURLConnection {
        val connection = URL(mac.base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = readTimeout
        connection.useCaches = false
        return connection
    }

    private fun HttpURLConnection.body(): String =
        (if (responseCode in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Finds Flick Receiver on the local network using Bonjour. */
class Discovery(context: Context, private val onFound: (Mac) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val created = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolve(service)
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { listener = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        listener = created
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, created) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(service: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host ?: return
                    // IPv6 link-local addresses need a scope the HTTP client cannot carry.
                    if (address !is Inet4Address) return
                    val host = address.hostAddress ?: return
                    main.post { onFound(Mac(info.serviceName, host, info.port)) }
                }
            })
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_flickdrop._tcp."
    }
}

data class Item(val uri: Uri, val name: String, val size: Long, val mime: String)
