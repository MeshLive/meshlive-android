package com.example.bluetoothtest

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Share app nearby" (Help; promoted to Settings > Features on 2026-10-11, entry shown by default
 * only after phone test T9: [ConnectivityFeatureDefaults.SHARE_APP_NEARBY]; DEBUG
 * `test_offline_app_share` is the benchmark switch). Prior art: Briar's offline hotspot share and Bitchat's APK web page (both GPL; re-implemented from the
 * description, no code copied).
 *
 * The sharing phone creates a Wi-Fi Direct group (5 GHz first where the phone supports it, because
 * 2.4 GHz Wi-Fi halved Bluetooth throughput on the M31), runs [OfflineAppShareServer] bound to the
 * group interface address only, and shows two QR codes: join the Wi-Fi, then open the URL. The page
 * shows the APK SHA-256 and signing certificate SHA-256 so the receiver can verify the download.
 * It stops after [OfflineAppSharePolicy.SHARE_TIMEOUT_MS] or when the user leaves the screen, and it
 * refuses to start (and stops) while a live stream, call or relay runs ([OfflineAppShareGate]).
 */
object OfflineAppShareFeature {
    const val EXTRA = "test_offline_app_share"
    const val PREF = "experimental_offline_app_share"

    @Volatile var enabled = false

    /**
     * Whether this build offers outbound sharing. All distributions offer it; AppShare separately refuses split
     * installs because the base APK alone would not install. Sharing never updates this phone (docs/PLAY_RELEASE.md).
     */
    val availableInThisBuild: Boolean get() = BuildConfig.APP_SHARE_AVAILABLE

    /** The shared feature rule ([FeatureCatalog.resolve]); always off where [available] is false. */
    fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
        available: Boolean = availableInThisBuild,
    ): Boolean = available && FeatureCatalog.resolve(ConnectivityFeatures.shareAppNearby, benchmarkLaunch, extra, stored)
}

/**
 * Radio-contention guard. MainActivity owns every stream, call and relay, so it registers a check
 * here; the share screen asks it before starting and every few seconds while sharing.
 */
object OfflineAppShareGate {
    @Volatile var liveSessionCheck: (() -> Boolean)? = null

    fun liveSessionActive(): Boolean = runCatching { liveSessionCheck?.invoke() == true || WifiRadio.activeCount() > 0 }.getOrDefault(true)
}

/**
 * One Wi-Fi Direct group creation attempt, in the order [OfflineAppSharePolicy.bandAttempts] gives. Every attempt passes
 * a WifiP2pConfig with our own random name/passphrase and persistent mode off, so the group is temporary. There is
 * deliberately no createGroup(channel, listener) attempt: that call creates a PERSISTENT group, and a saved persistent
 * group stops Android re-randomising this phone's Wi-Fi Direct MAC address (a lasting linkability leak that apps cannot
 * delete). WifiP2pConfig.Builder exists from API 29 and minSdk is 29, so no older fallback is needed.
 */
internal enum class GroupBandAttempt {
    /** WifiP2pConfig.Builder with GROUP_OWNER_BAND_5GHZ and our own name/passphrase. */
    BAND_5GHZ,

    /** WifiP2pConfig.Builder with GROUP_OWNER_BAND_AUTO (the framework picks; usually 2.4 GHz). */
    BAND_AUTO,
}

/** Pure rules, unit-tested without Android. */
internal object OfflineAppSharePolicy {
    /**
     * Runtime permissions Wi-Fi Direct needs: Nearby devices on Android 13+; fine location before that. From Android 12 a
     * fine-location request must carry coarse location in the same request: on its own it is ignored and logs
     * "ACCESS_FINE_LOCATION must be requested with ACCESS_COARSE_LOCATION" (developer.android.com, runtime location
     * permissions). If the user picks "approximate" only, fine stays denied and sharing stops with the permission message.
     */
    fun permissionsFor(sdkInt: Int): List<String> = when {
        sdkInt >= 33 -> listOf("android.permission.NEARBY_WIFI_DEVICES")
        sdkInt >= 31 -> listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        else -> listOf("android.permission.ACCESS_FINE_LOCATION")
    }

    const val SHARE_TIMEOUT_MS = 10 * 60 * 1000L
    const val BUSY_CHECK_INTERVAL_MS = 2_000L
    const val MAX_BUSY_RETRIES = 4

    /** WifiP2pManager failure reasons (android.net.wifi.p2p.WifiP2pManager constants). */
    const val P2P_ERROR = 0
    const val P2P_UNSUPPORTED = 1
    const val P2P_BUSY = 2

    /** Letters and digits a person can read off a screen without confusing 0/O, 1/l/I. */
    private const val UNAMBIGUOUS = "abcdefghjkmnpqrstuvwxyz23456789"
    private const val UNAMBIGUOUS_MIXED = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    const val TOKEN_LENGTH = 20 // 31^20, about 99 bits.
    const val PASSPHRASE_LENGTH = 12 // 55^12, about 69 bits; WPA2 needs 8..63.

    const val BUSY_MESSAGE =
        "Sharing the app is paused while a live stream, call or relay is running, because Wi-Fi Direct " +
            "shares the radio with Bluetooth and would slow it down. Finish the session, then try again."

    fun randomToken(random: SecureRandom = SecureRandom()): String = randomString(UNAMBIGUOUS, TOKEN_LENGTH, random)

    fun randomPassphrase(random: SecureRandom = SecureRandom()): String =
        randomString(UNAMBIGUOUS_MIXED, PASSPHRASE_LENGTH, random)

    /**
     * WifiP2pConfig.Builder.setNetworkName requires `DIRECT-xy` (x, y letters or digits) and at most 32
     * UTF-8 bytes. The rest is random on purpose: the SSID is broadcast, and naming the app would tell
     * anyone scanning nearby which app this phone carries.
     */
    fun randomNetworkName(random: SecureRandom = SecureRandom()): String =
        "DIRECT-" + randomString(UNAMBIGUOUS, 2, random) + "-" + randomString(UNAMBIGUOUS, 6, random)

    fun validNetworkName(name: String): Boolean =
        Regex("^DIRECT-[A-Za-z0-9]{2}.*").matches(name) && name.toByteArray(Charsets.UTF_8).size <= 32

    fun validPassphrase(passphrase: String): Boolean =
        passphrase.length in 8..63 && passphrase.all { it.code in 0x20..0x7e }

    /** 5 GHz first only where the phone reports 5 GHz support, then auto band. Never a persistent-group attempt. */
    fun bandAttempts(supports5Ghz: Boolean): List<GroupBandAttempt> = buildList {
        if (supports5Ghz) add(GroupBandAttempt.BAND_5GHZ)
        add(GroupBandAttempt.BAND_AUTO)
    }

    sealed class FailureStep {
        /** Retry the same attempt after [delayMs] (framework busy). */
        data class Retry(val delayMs: Long) : FailureStep()

        /** Try the next band attempt now. */
        object NextAttempt : FailureStep()

        /** Give up with a user-facing [reason]. */
        data class GiveUp(val reason: String) : FailureStep()
    }

    /** What to do after createGroup failed with [reason] on attempt [attemptIndex] of [attemptCount]. */
    fun onCreateGroupFailure(reason: Int, attemptIndex: Int, attemptCount: Int, busyRetries: Int): FailureStep = when {
        reason == P2P_UNSUPPORTED -> FailureStep.GiveUp("This phone does not support Wi-Fi Direct.")
        reason == P2P_BUSY && busyRetries < MAX_BUSY_RETRIES -> FailureStep.Retry(1_000L shl busyRetries)
        attemptIndex + 1 < attemptCount -> FailureStep.NextAttempt
        reason == P2P_BUSY -> FailureStep.GiveUp("Wi-Fi Direct stayed busy. Turn Wi-Fi off and on, then try again.")
        else -> FailureStep.GiveUp("Wi-Fi Direct could not start a group (reason $reason). Check that Wi-Fi is on.")
    }

    /** Wi-Fi band label for a group frequency in MHz (0 when unknown). */
    fun bandLabel(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5900 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> "unknown band"
    }

    fun is24Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 2400..2500

    /**
     * The standard Wi-Fi QR payload (ZXing "WIFI:" format, also read by Android 10+ and iOS cameras):
     * `WIFI:T:WPA;S:<ssid>;P:<passphrase>;;`, with `\ ; , : "` escaped by a backslash.
     */
    fun wifiQrPayload(ssid: String, passphrase: String): String =
        "WIFI:T:WPA;S:${escapeWifiQr(ssid)};P:${escapeWifiQr(passphrase)};;"

    fun escapeWifiQr(value: String): String = buildString {
        for (ch in value) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') append('\\')
            append(ch)
        }
    }

    /** `http://192.168.49.1:port/<token>/`; an IPv6 literal is bracketed. */
    fun pageUrl(host: String, port: Int, token: String): String {
        val literal = if (host.contains(':')) "[${host.substringBefore('%')}]" else host
        return "http://$literal:$port/$token/"
    }

    fun minutesSecondsLeft(remainingMs: Long): String {
        val total = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** True when [remote] is inside [local]/[prefixLength] (the Wi-Fi Direct group subnet). */
    fun sameSubnet(local: InetAddress, prefixLength: Int, remote: InetAddress): Boolean {
        val a = local.address
        val b = remote.address
        if (a.size != b.size || prefixLength < 0 || prefixLength > a.size * 8) return false
        var bits = prefixLength
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    private fun randomString(alphabet: String, length: Int, random: SecureRandom): String =
        buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    // --- Page --------------------------------------------------------------------------------------

    data class PageInfo(
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val sizeBytes: Long,
        val apkFileName: String,
        val apkSha256: String,
        val signingCertSha256: List<String>,
        val matchesPublishedReleaseKey: Boolean,
        val debugBuild: Boolean,
    )

    fun htmlEscape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /** A static page: no script, no external resource, a relative link to the APK. */
    fun pageHtml(info: PageInfo): String {
        val e = ::htmlEscape
        val sizeMb = String.format(Locale.US, "%.1f", info.sizeBytes / 1_000_000.0)
        val certs = info.signingCertSha256.joinToString("<br>") { "<code>${e(it)}</code>" }
            .ifEmpty { "<code>unknown</code>" }
        val keyNote = when {
            info.matchesPublishedReleaseKey -> "This matches the project's published release key."
            info.debugBuild -> "This is a test (debug) build. Its key is not the published release key."
            else -> "This is not the project's published release key. Install only if you trust the sender."
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${e(info.appName)}</title>
<style>
body{font-family:sans-serif;margin:16px;max-width:640px;line-height:1.4;color:#111;background:#fff}
a.button{display:block;text-align:center;padding:14px;margin:16px 0;background:#1565c0;color:#fff;border-radius:8px;text-decoration:none;font-size:1.1em}
code{word-break:break-all;font-size:.9em}
li{margin-bottom:8px}
</style></head><body>
<h1>${e(info.appName)}</h1>
<p><b>Android only. This APK cannot be installed on an iPhone or iPad.</b></p>
<p>Version ${e(info.versionName)} (${info.versionCode}), $sizeMb MB.</p>
<a class="button" href="${e(info.apkFileName)}" download="${e(info.apkFileName)}">Download the app</a>
<h2>Install</h2>
<ol>
<li>Tap the button above and wait for the download to finish.</li>
<li>Open the downloaded file. If Android asks, allow your browser or file manager to install unknown apps.</li>
<li>When you are done, you can leave this Wi-Fi network.</li>
</ol>
<h2>Check the file</h2>
<p>File SHA-256:<br><code>${e(info.apkSha256)}</code></p>
<p>Signing certificate SHA-256:<br>$certs</p>
<p>${e(keyNote)} After installing, About in the app shows the signing certificate; it must match the one above.</p>
<p>This page is written by the phone that shares it, so it can say anything. Compare the certificate above with the one on the project page (github.com/MeshLive/meshlive-android) before you install.</p>
<p><b>Already have MeshLive?</b> Do not open the file from here. In MeshLive open Settings &gt; About and updates &gt; Check now and tap Choose the file (if you installed it from an app store, update it there instead): MeshLive checks it first. If Android offers to Install a new app instead of Update, cancel.</p>
</body></html>
"""
    }
}

/**
 * Tiny read-only HTTP/1.1 server for [OfflineAppShareServer]: request routing and Range parsing are
 * pure functions so the security rules are unit-tested without sockets.
 */
internal object OfflineHttp {
    const val MAX_REQUEST_LINE = 2048
    const val MAX_HEADER_BYTES = 8192
    const val MAX_HEADERS = 50

    sealed class Route {
        object Page : Route()

        /** `/<token>` without the slash: redirect so the page's relative APK link resolves. */
        object PageRedirect : Route()
        object Apk : Route()
        object NotFound : Route()
        object MethodNotAllowed : Route()
        object BadRequest : Route()
    }

    /**
     * Only `GET`/`HEAD` of exactly `/<token>/` (`/<token>` redirects there) and `/<token>/<apkFileName>`. No query,
     * no percent-encoding, no other path: everything else, including `/`, is 404, so nothing is listed
     * and nothing reveals whether a token was close. The token is compared in constant time.
     */
    fun route(method: String, target: String, token: String, apkFileName: String): Route {
        if (method != "GET" && method != "HEAD") return Route.MethodNotAllowed
        if (!target.startsWith("/") || target.length > MAX_REQUEST_LINE) return Route.BadRequest
        if (target.any { it == '?' || it == '#' || it == '%' || it == '\\' || it.code < 0x21 || it.code > 0x7e }) {
            return Route.NotFound
        }
        val rest = target.substring(1)
        val slash = rest.indexOf('/')
        val candidate = if (slash < 0) rest else rest.substring(0, slash)
        val tail = if (slash < 0) null else rest.substring(slash + 1)
        if (!constantTimeEquals(candidate, token)) return Route.NotFound
        return when (tail) {
            null -> Route.PageRedirect
            "" -> Route.Page
            apkFileName -> Route.Apk
            else -> Route.NotFound
        }
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    sealed class RangeResult {
        /** No usable Range: send the whole file with 200. */
        object Full : RangeResult()

        /** Send bytes [start]..[endInclusive] with 206. */
        data class Partial(val start: Long, val endInclusive: Long) : RangeResult() {
            val length: Long get() = endInclusive - start + 1
        }

        /** 416 with `Content-Range: bytes * /length`. */
        object Unsatisfiable : RangeResult()
    }

    /**
     * One `bytes=a-b`, `bytes=a-` or `bytes=-n` range (download resume). Several ranges, other units
     * or malformed values are ignored (RFC 9110 allows serving the full representation instead).
     */
    fun parseRange(header: String?, length: Long): RangeResult {
        val value = header?.trim() ?: return RangeResult.Full
        if (!value.startsWith("bytes=", ignoreCase = true)) return RangeResult.Full
        val spec = value.substring(6).trim()
        if (spec.contains(',')) return RangeResult.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (first.any { !it.isDigit() } || last.any { !it.isDigit() }) return RangeResult.Full
        if (first.length > 18 || last.length > 18) return RangeResult.Full
        return if (first.isEmpty()) {
            if (last.isEmpty()) return RangeResult.Full
            val suffix = last.toLong()
            if (suffix == 0L || length == 0L) RangeResult.Unsatisfiable
            else RangeResult.Partial((length - suffix).coerceAtLeast(0L), length - 1)
        } else {
            val start = first.toLong()
            val end = if (last.isEmpty()) length - 1 else last.toLong()
            when {
                start >= length -> RangeResult.Unsatisfiable
                end < start -> RangeResult.Full
                else -> RangeResult.Partial(start, end.coerceAtMost(length - 1))
            }
        }
    }

    data class Request(val method: String, val target: String, val headers: Map<String, String>)

    /** Reads one request head with hard size limits; null when the client sent nothing usable. */
    fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input, MAX_REQUEST_LINE) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_BYTES) ?: return null
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        return Request(parts[0], parts[1], headers)
    }

    /** ASCII line ending in CRLF (or LF); null on EOF or when longer than [limit]. */
    private fun readLine(input: InputStream, limit: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (sb.length >= limit) return null
            sb.append(b.toChar())
        }
        if (sb.endsWith("\r")) sb.setLength(sb.length - 1)
        return sb.toString()
    }
}

/**
 * Serves exactly two resources, read-only: the page and the APK, under a random [token]. Bound to
 * [bindAddress] (the Wi-Fi Direct group address); [allowRemote] rejects any peer outside the group
 * subnet. One request per connection, at most [maxConnections] at once, short read timeouts.
 * [close] stops accepting and closes every open connection.
 */
internal class OfflineAppShareServer(
    private val bindAddress: InetAddress,
    private val token: String,
    private val apkFile: File,
    private val apkFileName: String,
    pageHtml: String,
    private val allowRemote: (InetAddress) -> Boolean = { true },
    private val requestedPort: Int = 0,
    private val maxConnections: Int = 4,
    private val readTimeoutMs: Int = 15_000,
    private val onEvent: (String) -> Unit = {},
) : Closeable {
    private val page = pageHtml.toByteArray(Charsets.UTF_8)
    private val closed = AtomicBoolean(false)
    private val permits = Semaphore(maxConnections)
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var acceptThread: Thread? = null

    val apkRequests = AtomicInteger(0)
    val pageRequests = AtomicInteger(0)

    /** The bound port; valid after [start]. */
    var port: Int = 0
        private set

    init {
        require(token.length >= 16) { "token too short" }
        require(apkFileName.isNotEmpty() && apkFileName.none { it == '/' || it == '\\' || it == '%' || it == '?' })
    }

    @Synchronized
    fun start(): Int {
        check(!closed.get() && serverSocket == null) { "closed or already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(bindAddress, requestedPort), 8)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        serverSocket = socket
        port = socket.localPort
        val pool = Executors.newFixedThreadPool(maxConnections) { r ->
            Thread(r, "app-share-http").apply { isDaemon = true }
        }
        workers = pool
        acceptThread = Thread({ acceptLoop(socket, pool) }, "app-share-accept").apply {
            isDaemon = true
            start()
        }
        return port
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            if (!allowRemote(client.inetAddress)) {
                onEvent("rejected_remote")
                runCatching { client.close() }
                continue
            }
            if (!permits.tryAcquire()) {
                runCatching {
                    client.soTimeout = 2_000
                    writeHead(client.getOutputStream(), 503, "Service Unavailable", "text/plain", 0, emptyList())
                    client.close()
                }
                continue
            }
            val accepted = synchronized(open) { if (closed.get()) false else open.add(client) }
            if (!accepted) {
                runCatching { client.close() }
                permits.release()
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(client)
                    } finally {
                        open.remove(client)
                        runCatching { client.close() }
                        permits.release()
                    }
                }
            } catch (_: RuntimeException) {
                open.remove(client)
                runCatching { client.close() }
                permits.release()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = readTimeoutMs
            val input = client.getInputStream()
            val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
            val request = OfflineHttp.readRequest(input)
            if (request == null) {
                writeHead(out, 400, "Bad Request", "text/plain", 0, emptyList())
                out.flush()
                return
            }
            val head = request.method == "HEAD"
            when (OfflineHttp.route(request.method, request.target, token, apkFileName)) {
                OfflineHttp.Route.Page -> {
                    pageRequests.incrementAndGet()
                    writeHead(
                        out, 200, "OK", "text/html; charset=utf-8", page.size.toLong(),
                        listOf(
                            "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'",
                            "Referrer-Policy" to "no-referrer",
                        ),
                    )
                    if (!head) out.write(page)
                }
                OfflineHttp.Route.PageRedirect ->
                    writeHead(out, 308, "Permanent Redirect", "text/plain", 0, listOf("Location" to "/$token/"))
                OfflineHttp.Route.Apk -> {
                    apkRequests.incrementAndGet()
                    serveApk(out, request.headers["range"], head)
                }
                OfflineHttp.Route.MethodNotAllowed ->
                    writeHead(out, 405, "Method Not Allowed", "text/plain", 0, listOf("Allow" to "GET, HEAD"))
                OfflineHttp.Route.BadRequest -> writeHead(out, 400, "Bad Request", "text/plain", 0, emptyList())
                OfflineHttp.Route.NotFound -> writeHead(out, 404, "Not Found", "text/plain", 0, emptyList())
            }
            out.flush()
        } catch (_: SocketException) {
            // Client went away or close() stopped the server.
        } catch (_: IOException) {
        }
    }

    private fun serveApk(out: OutputStream, rangeHeader: String?, head: Boolean) {
        RandomAccessFile(apkFile, "r").use { file ->
            val length = file.length()
            val disposition = "Content-Disposition" to "attachment; filename=\"$apkFileName\""
            val common = listOf(disposition, "Accept-Ranges" to "bytes")
            when (val range = OfflineHttp.parseRange(rangeHeader, length)) {
                OfflineHttp.RangeResult.Full -> {
                    writeHead(out, 200, "OK", AppSharePolicy.MIME_APK, length, common)
                    if (!head) copy(file, 0L, length, out)
                }
                is OfflineHttp.RangeResult.Partial -> {
                    writeHead(
                        out, 206, "Partial Content", AppSharePolicy.MIME_APK, range.length,
                        common + ("Content-Range" to "bytes ${range.start}-${range.endInclusive}/$length"),
                    )
                    if (!head) copy(file, range.start, range.length, out)
                }
                OfflineHttp.RangeResult.Unsatisfiable ->
                    writeHead(
                        out, 416, "Range Not Satisfiable", "text/plain", 0,
                        listOf("Content-Range" to "bytes */$length"),
                    )
            }
        }
    }

    private fun copy(file: RandomAccessFile, start: Long, count: Long, out: OutputStream) {
        file.seek(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0 && !closed.get()) {
            val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeHead(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        contentLength: Long,
        extra: List<Pair<String, String>>,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            for ((name, value) in extra) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        workers?.shutdownNow()
        acceptThread?.interrupt()
    }
}
