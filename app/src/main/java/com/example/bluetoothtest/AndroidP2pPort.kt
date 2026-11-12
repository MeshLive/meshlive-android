package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A9's [P2pPort] over the platform. Android types stay in this file; callers run on the `meshlive-wifi` looper.
 * Root amendment (c): on API 29-32 createGroup/connect need a visible activity or a location-type foreground service,
 * otherwise the attempt is declined as local busy without touching the platform. Every platform callback is delivered
 * through [deliver], so a throw in the state machine never reaches the looper (A9 review P1-3).
 */
internal class AndroidP2pPort(
    private val context: Context,
    private val looper: Looper,
    private val foregroundOk: () -> Boolean = {
        DirectPolicy.p2pForegroundOk(
            Build.VERSION.SDK_INT,
            ActiveVideoSessionService.appVisible(),
            ServiceTypePolicy.hasLocation(ActiveVideoSessionService.heldTypes),
        )
    },
) : P2pPort {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    @Volatile
    private var closing = false

    override fun open(onChannelLost: () -> Unit): Boolean {
        closing = false
        val p2p = context.getSystemService(WifiP2pManager::class.java) ?: return false
        manager = p2p
        val opened = runCatching {
            p2p.initialize(
                context,
                looper,
                object : WifiP2pManager.ChannelListener {
                    override fun onChannelDisconnected() {
                        if (!closing) deliver({ _: Unit -> onChannelLost() }, Unit)
                    }
                },
            )
        }.getOrNull()
        channel = opened
        return opened != null
    }

    override fun createGroup(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) = guarded(done) {
        if (!foregroundOk()) {
            deliver(done, P2pResult.NotVisible)
            return@guarded
        }
        val builder = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(psk).enablePersistentMode(false)
        if (frequencyMhz >= WifiLimits.MIN_5GHZ_MHZ) builder.setGroupOperatingFrequency(frequencyMhz)
        else builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        val p2p = manager ?: run { deliver(done, P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)); return@guarded }
        val ch = channel ?: run { deliver(done, P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)); return@guarded }
        p2p.createGroup(ch, builder.build(), listener(done))
    }

    override fun connect(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) = guarded(done) {
        if (!foregroundOk()) {
            deliver(done, P2pResult.NotVisible)
            return@guarded
        }
        val builder = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(psk).enablePersistentMode(false)
        if (frequencyMhz > 0) builder.setGroupOperatingFrequency(frequencyMhz)
        val p2p = manager ?: run { deliver(done, P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)); return@guarded }
        val ch = channel ?: run { deliver(done, P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)); return@guarded }
        p2p.connect(ch, builder.build(), listener(done))
    }

    /** null = no group OR an unreadable one (location gate, no channel, error): callers use [readGroup]. [P1-2] */
    override fun requestGroup(done: (P2pGroup?) -> Unit) {
        val p2p = manager ?: return deliver(done, null)
        val ch = channel ?: return deliver(done, null)
        val issued = runCatching {
            p2p.requestGroupInfo(ch) { group ->
                deliver(
                    done,
                    group?.let {
                        P2pGroup(it.networkName, it.isGroupOwner, it.`interface`, it.frequency)
                    },
                )
            }
        }.isSuccess
        if (!issued) deliver(done, null)
    }

    /** Not gated by the location check (AOSP WifiP2pServiceImpl REQUEST_CONNECTION_INFO), so groupFormed is reliable. */
    override fun requestConnection(done: (P2pConnection?) -> Unit) {
        val p2p = manager ?: return deliver(done, null)
        val ch = channel ?: return deliver(done, null)
        val issued = runCatching {
            p2p.requestConnectionInfo(ch) { info ->
                deliver(
                    done,
                    info?.let {
                        P2pConnection(it.groupFormed, it.isGroupOwner, it.groupOwnerAddress as? Inet4Address)
                    },
                )
            }
        }.isSuccess
        if (!issued) deliver(done, null)
    }

    override fun removeGroup(done: (P2pResult) -> Unit) = guarded(done) {
        val p2p = manager ?: run { deliver(done, P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)); return@guarded }
        val ch = channel ?: run { deliver(done, P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)); return@guarded }
        p2p.removeGroup(ch, listener(done))
    }

    override fun requestDeviceName(done: (String?) -> Unit) {
        val p2p = manager ?: return deliver(done, null)
        val ch = channel ?: return deliver(done, null)
        val issued = runCatching {
            p2p.requestDeviceInfo(ch) { device -> deliver(done, device?.deviceName) }
        }.isSuccess
        if (!issued) deliver(done, null)
    }

    override fun closeChannel() {
        closing = true
        val ch = channel
        channel = null
        if (ch != null) runCatching { ch.close() }
    }

    override fun localAddresses(): List<DirectAddressRules.LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { network ->
            network.interfaceAddresses.mapNotNull { entry ->
                val address = entry.address as? Inet4Address ?: return@mapNotNull null
                DirectAddressRules.LocalAddress(network.name, address, entry.networkPrefixLength.toInt())
            }
        }.sortedWith(compareBy({ it.interfaceName }, { it.address.hostAddress }))
    }.getOrDefault(emptyList())

    private inline fun guarded(noinline done: (P2pResult) -> Unit, block: () -> Unit) {
        val failure = try {
            block()
            null
        } catch (_: SecurityException) {
            P2pResult.Denied
        } catch (_: IllegalArgumentException) {
            P2pResult.BadConfig
        } catch (_: UnsupportedOperationException) {
            P2pResult.BadConfig
        } catch (_: IllegalStateException) {
            P2pResult.BadConfig
        } catch (_: RuntimeException) {
            P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR)
        }
        if (failure != null) deliver(done, failure)
    }

    private fun listener(done: (P2pResult) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = deliver(done, P2pResult.Success)
        override fun onFailure(reason: Int) = deliver(done, P2pResult.Failure(reason))
    }

    /** A9 review P1-3: a throw in a callback is logged and never reaches the meshlive-wifi looper (process crash). */
    private fun <T> deliver(done: (T) -> Unit, value: T) {
        try {
            done(value)
        } catch (error: RuntimeException) {
            runCatching {
                WifiLog.emit(WifiLog.upgrade("abort", "carrier" to "direct", "reason" to "callback_" + error.javaClass.simpleName))
            }
        }
    }
}

/**
 * The start-up sweep (F-12, X4): on the main thread only the sealed file's presence is read; everything else runs on
 * the wifi thread and every failure is swallowed. A stranded group is cleaned whatever the switches say.
 */
internal object DirectStartupSweep {
    fun run(context: Context) {
        if (!File(context.applicationContext.noBackupFilesDir, "direct-groups.sealed").exists()) return
        WifiRadio.scheduler.post {
            var lease: DiscoveryPauseLease? = null
            var settled = false
            try {
                val record = directGroupRecord(context)
                val names = record.namesOrNull() ?: return@post // unreadable now: keep it for the next start
                if (names.isEmpty()) {
                    record.tryClear() // P3-6: an empty or damaged file would cost a Keystore read at every start
                    return@post
                }
                if (!permissionHeld(context)) return@post
                val quarantine = DirectBearerFactory.quarantine() // P3-5: before the lease, which nothing could resume
                val taken = WifiRadio.pauseDiscoveryLease("direct-sweep")
                lease = taken
                val port = AndroidP2pPort(context, WifiRadio.looper)
                DirectGroup.sweepOnStartup(port, record, WifiRadio.ledger, quarantine, DirectSchedulerAdapter(WifiRadio.scheduler)) { result ->
                    settled = true
                    if (result == DirectGroup.SweepResult.REMOVED) {
                        runCatching { WifiLog.emit(WifiLog.upgrade("sweep", "carrier" to "direct", "removed" to 1)) }
                    }
                    if (quarantine.active) quarantine.holdLease(taken) else taken.resume()
                }
            } catch (error: Exception) {
                if (!settled) lease?.let { runCatching { it.resume() } }
            }
        }
    }

    private fun permissionHeld(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
