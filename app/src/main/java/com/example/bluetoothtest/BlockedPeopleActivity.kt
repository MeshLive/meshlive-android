package com.example.bluetoothtest

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * "Blocked people": everyone blocked on this phone, by the name you gave a contact or their ID, with Unblock.
 * Store access goes through [BlockedPeople] off the main thread.
 */
class BlockedPeopleActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var people: List<BlockedPerson> = emptyList()
    private var names: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_blocked_people)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.blockedRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.blockedBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.blockedEmptyView).apply {
            findViewById<ImageView>(R.id.emptyStateIcon).setImageResource(R.drawable.ic_person)
            findViewById<TextView>(R.id.emptyStateTitle).setText(R.string.blocked_people_empty_title)
            findViewById<TextView>(R.id.emptyStateText).setText(R.string.blocked_people_empty_text)
            findViewById<View>(R.id.emptyStateAction).visibility = View.GONE
        }
        findViewById<ListView>(R.id.blockedList).setOnItemClickListener { _, _, position, _ ->
            people.getOrNull(position)?.let(::confirmUnblock)
        }
        findViewById<View>(R.id.blockedStoreProblem).setOnClickListener { confirmReset() }
        findViewById<View>(R.id.blockedResetButton).setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun nameOf(person: BlockedPerson): String = CallBlockingUi.nameFor(person.prefixHex, names)

    private fun refresh() {
        val context = applicationContext
        worker.execute {
            val loaded = runCatching { BlockedPeople.list(context) }.getOrDefault(emptyList())
            val unreadable = runCatching { BlockedPeople.isUnreadable(context) }.getOrDefault(true)
            val labels = runCatching { SecurityUiBackend.current.contactNames(context) }.getOrDefault(emptyMap())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                names = labels
                people = loaded
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
                findViewById<ListView>(R.id.blockedList).adapter = ListRowAdapter(
                    this,
                    loaded.map { person ->
                        ListRow(
                            title = nameOf(person),
                            subtitle = getString(R.string.blocked_people_row_since, date.format(Date(person.blockedAtMs))),
                            iconRes = R.drawable.ic_person,
                            iconTintRes = R.color.text_primary,
                            contentDescription = getString(R.string.blocked_people_row_description, nameOf(person)),
                        )
                    },
                )
                val view = BlockListView.of(loaded, unreadable)
                findViewById<View>(R.id.blockedEmptyView).visibility = if (view.showEmptyState) View.VISIBLE else View.GONE
                val note = if (view.showUnreadableNote) View.VISIBLE else View.GONE
                findViewById<View>(R.id.blockedStoreProblem).visibility = note
                findViewById<View>(R.id.blockedResetButton).visibility = note
            }
        }
    }

    private fun confirmUnblock(person: BlockedPerson) {
        val name = nameOf(person)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.blocked_people_unblock_title, name))
            .setMessage(R.string.blocked_people_unblock_message)
            .setPositiveButton(R.string.blocked_people_unblock) { _, _ ->
                val context = applicationContext
                worker.execute {
                    BlockedPeople.unblock(context, person.fingerprint)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, getString(R.string.blocked_people_unblocked, name), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** The one Reset, shared with Settings: "Reset the block list?", then everyone is unblocked. */
    private fun confirmReset() {
        CallBlockingUi.confirmResetBlockList(this) { if (!isDestroyed) refresh() }
    }
}
