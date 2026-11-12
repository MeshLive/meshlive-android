package com.example.bluetoothtest

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * The JVM harness for user flows that need no screen and no radio: first run, Settings switches, updates, wipe, crash
 * reports. Robolectric is not a dependency (the project pins and checksums every library, Robolectric would add about a
 * hundred artifacts and download an Android runtime jar at test time, and MainActivity cannot start without the camera,
 * Bluetooth and native stacks anyway). Instead the flows run the real production classes against these fakes.
 *
 * A [FakePhone] is the storage that outlives the app: preference files, the no-backup directory and the files directory.
 * [start] gives a [FakeAppContext] over it, which is one process of the app; calling [start] again is a restart.
 * Process-wide singletons the app keeps in memory (FeatureCatalog and similar) are reset by the test at a restart.
 */
internal class FakePhone(val root: File) {
    private val prefs = LinkedHashMap<String, MemoryPreferences>()
    private val services = HashMap<String, Any>()

    val noBackupDir: File = File(root, "no_backup").also { it.mkdirs() }
    val filesDir: File = File(root, "files").also { it.mkdirs() }
    /** The app's data directory: preferences are also written as `shared_prefs/<name>.xml` there, as Android does. */
    val dataDir: File = File(root, "data").also { it.mkdirs() }

    /** Stands in for the phone's non-exportable Keystore key (one per phone, like the real one). */
    val atRestCipher: AtRestCipher = AesGcmAtRestCipher {
        javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it * 7 + root.name.length).toByte() }, "AES")
    }

    init {
        // Stores sealed with the verified-contacts key read and write through this phone's stand-in key.
        RecentCalls.cipher = { atRestCipher }
        SealedRecords.cipher = { atRestCipher }
    }

    fun preferences(name: String): MemoryPreferences =
        synchronized(prefs) { prefs.getOrPut(name) { MemoryPreferences(File(dataDir, "shared_prefs/$name.xml")) } }

    /** As Context.deleteSharedPreferences: the in-memory instance and both files on the disk. */
    fun deletePreferences(name: String): Boolean {
        synchronized(prefs) { prefs.remove(name) }
        val xml = File(dataDir, "shared_prefs/$name.xml")
        File(xml.path + ".bak").delete()
        return !xml.exists() || xml.delete()
    }

    fun preferenceNames(): Set<String> = prefs.keys.toSet()

    fun clearPreferences(name: String) {
        deletePreferences(name)
    }

    fun registerService(name: String, service: Any) {
        services[name] = service
    }

    /** One process of the app on this storage. */
    fun start(packageName: String = "io.github.meshlive.app"): FakeAppContext = FakeAppContext(this, packageName, services)
}

internal class FakeAppContext(
    private val phone: FakePhone,
    private val pkg: String,
    private val services: Map<String, Any>,
) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = phone.preferences(name)

    override fun getNoBackupFilesDir(): File = phone.noBackupDir

    override fun getFilesDir(): File = phone.filesDir

    override fun getDataDir(): File = phone.dataDir

    override fun deleteSharedPreferences(name: String): Boolean = phone.deletePreferences(name)

    override fun getPackageName(): String = pkg

    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any? = services[name]
}

/**
 * An in-memory [SharedPreferences]: an edit is visible as soon as it is applied or committed. With a [file], a successful
 * commit also writes the names and values there as Android's XML does; with [failCommits], a commit changes the memory but
 * not the file and returns false, as a failed disk write does (AOSP SharedPreferencesImpl: memory first, then the file).
 */
internal class MemoryPreferences(private val file: File? = null) : SharedPreferences {
    private val values = java.util.Collections.synchronizedMap(LinkedHashMap<String, Any>())
    @Volatile var failCommits = false

    override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

    private fun writeFile(): Boolean {
        val target = file ?: return true
        if (failCommits) return false
        val entries = synchronized(values) { values.entries.map { it.key to it.value } }
        target.parentFile?.mkdirs()
        target.writeText(
            entries.joinToString("", "<map>", "</map>") { (key, value) -> "<entry name=\"$key\" value=\"$value\" />" },
        )
        return true
    }

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removed += key
            pending.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            synchronized(values) {
                if (clearAll) values.clear()
                removed.forEach { values.remove(it) }
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
            return writeFile()
        }

        override fun apply() {
            commit()
        }
    }
}
