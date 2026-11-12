package com.example.bluetoothtest

import android.content.Intent

/**
 * Launch switches of the CoC and Classic batch (family batch C). Every arm other than the defaults is
 * DEBUG-only (a launch extra from another app must not change a release build), and each launch starts
 * from the defaults, so a stored choice never leaks into a measured case. Register rows live in
 * SWITCHES ("Experimental, alternative and optional behaviour").
 *
 * Defaults (shipped behaviour):
 * - connect bound 12 s on every LE CoC client connect (`test_coc_connect_bound_ms`, 0 = unbounded as
 *   before; registry off-switch `test_device_policy_off=coc-connect-bounded`);
 * - MTU prime ON (`test_coc_mtu_prime`, Q49): one GATT MTU exchange on the fresh CoC ACL, where the registry
 *   entry `coc-mtu-exchange-before-traffic` applies (Android 12-13 fresh-ACL centrals); other stacks log
 *   `skipped reason=control-link-alive`. DEBUG `test_coc_mtu_prime=false` is the control arm; off-switch
 *   `test_device_policy_off=coc-mtu-exchange-before-traffic`;
 * - Classic RFCOMM peer cap: LOG ONLY (a `classic_rfcomm_cap` line when a seventh distinct peer would have been
 *   refused; nothing is refused). Refusal at 6 is the Experimental arm `test_classic_rfcomm_cap=6` (root review: a
 *   refusal without phone evidence, and incoming peers are undercounted until the accept paths call
 *   `ClassicRfcommCap.note`); registry off-switch `classic-rfcomm-peer-cap` removes the log line too.
 *
 * Experimental, default off:
 * - `test_coc_echo_verify=true`: 1 KB echo over the new CoC socket before it is used (Berty pattern);
 *   both phones need the switch, because the listener answers the echo before anything else.
 * - `test_coc_prime_async=true` (join-latency P1): a fresh-ACL CoC client returns the socket right after the connect and
 *   runs the MTU prime and the GATT control reattach on a background thread; the first bulk write waits for them, at most
 *   [CocPrimeGate.WAIT_BOUND_MS] ([CocPrimeGate]). The hop handshake starts 0.7 s earlier; its order is unchanged.
 * - `test_coc_psm_reuse_retry=true` (join-latency P4b): a fresh-ACL CoC connect that fails fast after the PSM was read
 *   is retried once with the same PSM after the ACL-down wait, instead of the caller redoing the GATT connect and PSM
 *   read ([CocPsmReuseRetry]).
 */
object CocSwitches {
    const val EXTRA_MTU_PRIME = "test_coc_mtu_prime"
    const val EXTRA_ECHO_VERIFY = "test_coc_echo_verify"
    const val EXTRA_CONNECT_BOUND_MS = "test_coc_connect_bound_ms"
    const val EXTRA_CLASSIC_RFCOMM_CAP = "test_classic_rfcomm_cap"
    const val EXTRA_PRIME_ASYNC = "test_coc_prime_async"
    const val EXTRA_PSM_REUSE_RETRY = "test_coc_psm_reuse_retry"

    /** Shipped: the MTU prime runs wherever its registry entry applies (fresh-ACL CoC centrals). */
    const val DEFAULT_MTU_PRIME = true

    /** Shipped bound: far above a healthy connect (seconds), below any stall worth waiting for. */
    const val DEFAULT_CONNECT_BOUND_MS = 12_000L
    const val MAX_CONNECT_BOUND_MS = 60_000L

    /** Seven RFCOMM sessions per phone (btif_sock_rfc.cc MAX_RFC_SESSION) minus one for a headset or car: the log-only threshold and the value of the refusal arm. */
    const val DEFAULT_CLASSIC_RFCOMM_CAP = 6

    /** Refusal is off by default; `test_classic_rfcomm_cap=<n>` enables it. */
    const val REFUSAL_OFF = 0
    const val MAX_CLASSIC_RFCOMM_CAP = 30

    /** `test_coc_mtu_prime` (default ON since Q49; DEBUG `=false` is the control arm). */
    @Volatile var mtuPrime: Boolean = DEFAULT_MTU_PRIME
        private set

    /** `test_coc_echo_verify` (Experimental, default off). */
    @Volatile var echoVerify: Boolean = false
        private set

    /** `test_coc_prime_async` (Experimental, default off): MTU prime and control reattach off the authentication path. */
    @Volatile var primeAsync: Boolean = false
        private set

    /** `test_coc_psm_reuse_retry` (Experimental, default off): one same-PSM CoC retry after the ACL-down wait. */
    @Volatile var psmReuseRetry: Boolean = false
        private set

    /** `test_coc_connect_bound_ms` (default [DEFAULT_CONNECT_BOUND_MS]; 0 turns the bound off). */
    @Volatile var connectBoundMs: Long = DEFAULT_CONNECT_BOUND_MS
        private set

    /** `test_classic_rfcomm_cap` (Experimental; default [REFUSAL_OFF] = log only; n > 0 refuses a new peer past n). */
    @Volatile var classicRfcommCap: Int = REFUSAL_OFF
        private set

    /** Reads the batch's `test_*` extras; absent extras mean the defaults. Call once per launch. */
    fun applyLaunchExtras(intent: Intent?) {
        configure(
            mtuPrime = intent?.takeIf { it.hasExtra(EXTRA_MTU_PRIME) }?.getBooleanExtra(EXTRA_MTU_PRIME, false),
            echoVerify = intent?.takeIf { it.hasExtra(EXTRA_ECHO_VERIFY) }?.getBooleanExtra(EXTRA_ECHO_VERIFY, false),
            connectBoundMs = intent?.takeIf { it.hasExtra(EXTRA_CONNECT_BOUND_MS) }
                ?.let { it.getLongExtra(EXTRA_CONNECT_BOUND_MS, it.getIntExtra(EXTRA_CONNECT_BOUND_MS, -1).toLong()) },
            classicRfcommCap = intent?.takeIf { it.hasExtra(EXTRA_CLASSIC_RFCOMM_CAP) }
                ?.getIntExtra(EXTRA_CLASSIC_RFCOMM_CAP, -1),
            primeAsync = intent?.takeIf { it.hasExtra(EXTRA_PRIME_ASYNC) }?.getBooleanExtra(EXTRA_PRIME_ASYNC, false),
            psmReuseRetry = intent?.takeIf { it.hasExtra(EXTRA_PSM_REUSE_RETRY) }?.getBooleanExtra(EXTRA_PSM_REUSE_RETRY, false),
            debugBuild = BuildConfig.DEBUG,
        )
    }

    /** JVM-testable core of [applyLaunchExtras]: starts from the defaults, then applies DEBUG overrides. */
    internal fun configure(
        mtuPrime: Boolean? = null,
        echoVerify: Boolean? = null,
        connectBoundMs: Long? = null,
        classicRfcommCap: Int? = null,
        primeAsync: Boolean? = null,
        psmReuseRetry: Boolean? = null,
        debugBuild: Boolean = BuildConfig.DEBUG,
    ) {
        this.primeAsync = debugBuild && primeAsync == true
        this.psmReuseRetry = debugBuild && psmReuseRetry == true
        this.mtuPrime = if (debugBuild && mtuPrime != null) mtuPrime else DEFAULT_MTU_PRIME
        this.echoVerify = debugBuild && echoVerify == true
        this.connectBoundMs = if (debugBuild && connectBoundMs != null && connectBoundMs >= 0L) {
            connectBoundMs.coerceAtMost(MAX_CONNECT_BOUND_MS)
        } else {
            DEFAULT_CONNECT_BOUND_MS
        }
        this.classicRfcommCap = if (debugBuild && classicRfcommCap != null && classicRfcommCap >= 0) {
            classicRfcommCap.coerceAtMost(MAX_CLASSIC_RFCOMM_CAP)
        } else {
            REFUSAL_OFF
        }
    }

    /** One line for the launch log: which arms this run uses. */
    fun label(): String =
        "mtuPrime=$mtuPrime | echoVerify=$echoVerify | connectBoundMs=$connectBoundMs | classicRfcommCap=$classicRfcommCap" +
            (if (primeAsync) " | primeAsync=true" else "") + if (psmReuseRetry) " | psmReuseRetry=true" else ""
}
