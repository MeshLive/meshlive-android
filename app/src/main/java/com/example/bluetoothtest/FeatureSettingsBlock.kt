package com.example.bluetoothtest

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * One generated Settings > Features card: a switch and an explanation for every [FeatureSpec] of a
 * group, bound to the user's explicit choice in [prefs] (see [FeatureCatalog]). The card follows the
 * catalog rules, so a feature added to the catalog appears here without layout work:
 *
 * - The switch shows the effective value: the user's explicit choice, else the shipped default.
 * - Touching it stores an explicit choice, which then wins over later changes of the default.
 * - A benchmark launch shows the launch options and cannot be changed (its extras are the only switch).
 * - A feature with [FeatureSpec.dependsOn] is greyed out while one of the features it needs is off.
 * - A constant ([FeatureSpec.constant]) has no row.
 */
internal class FeatureSettingsBlock(
    private val container: ViewGroup,
    private val specs: List<FeatureSpec>,
    private val prefs: SharedPreferences,
    private val host: Host,
    /** UI-W7: a row that depends on another is drawn indented under it (the Wi-Fi card: two options under "Use Wi-Fi when it helps"). */
    private val indentDependents: Boolean = false,
) {
    interface Host {
        /** True in a DEBUG benchmark launch: the switches show the extras and cannot be changed. */
        val benchmarkLaunch: Boolean

        /** A launch extra; null when the launch does not carry it ([FeatureCatalog.resolve]). */
        fun extra(name: String): Boolean?

        /**
         * Called before a feature is switched ON. Return true when the host takes over (shows an
         * explanation) and will call [proceed] once the user agrees; the switch stays off until then.
         * Return false to switch on at once. Switching off is never intercepted.
         */
        fun confirmEnable(spec: FeatureSpec, proceed: () -> Unit): Boolean = false

        /** The explanation text for [spec] when it is not the fixed string resource. Calls [deliver] exactly once, on the main thread, possibly after an asynchronous platform read; null = use the resource. */
        fun describe(spec: FeatureSpec, deliver: (CharSequence?) -> Unit) { deliver(null) }

        /** A short "not active" note shown in place of the row's description while the switch is [on] but cannot work; null = the normal description. [snapshot] is the block's own resolution (other rows' values). */
        fun rowNote(spec: FeatureSpec, on: Boolean, snapshot: FeatureSnapshot): CharSequence? = null

        /** A choice was stored: apply it to the running app. */
        fun onChanged(spec: FeatureSpec, enabled: Boolean)
    }

    private class Row(val spec: FeatureSpec, val switch: MaterialSwitch, val description: TextView) {
        var noted = false
    }

    private val rows = ArrayList<Row>()
    private var binding = false

    /** Builds the rows once and shows the current values. */
    fun bind() {
        if (rows.isEmpty()) {
            val inflater = LayoutInflater.from(container.context)
            // A spec with no title (titleRes 0) has no Settings row: it is still registered and follows its default.
            specs.filter { it.titleRes != 0 && !it.constant }.forEachIndexed { index, spec ->
                val view = inflater.inflate(R.layout.item_feature_switch, container, false)
                val switch = view.findViewById<MaterialSwitch>(R.id.featureRowSwitch)
                // Settings redesign (2026-10-17): icon, then the label (a tap opens the explanation), then the switch
                // alone at the right edge, so every switch in a card lines up.
                view.findViewById<TextView>(R.id.featureRowInfo).setText(spec.titleRes)
                switch.contentDescription = view.context.getString(spec.titleRes)
                if (spec.iconRes != 0) {
                    view.findViewById<android.widget.ImageView>(R.id.featureRowIcon).apply {
                        setImageResource(spec.iconRes)
                        visibility = View.VISIBLE
                    }
                }
                view.findViewById<TextView>(R.id.featureRowDescription).setText(spec.descriptionRes)
                view.findViewById<View>(R.id.featureRowInfo).setOnClickListener {
                    val context = view.context
                    host.describe(spec) { text ->
                        val activity = context as? android.app.Activity
                        if (activity?.isFinishing != true && activity?.isDestroyed != true) {
                            SettingsInfo.show(context, context.getString(spec.titleRes), text ?: context.getString(spec.descriptionRes))
                        }
                    }
                }
                if (indentDependents && spec.dependsOn.isNotEmpty()) {
                    view.setPaddingRelative(view.resources.getDimensionPixelSize(R.dimen.settings_dependent_indent), 0, 0, 0)
                }
                if (index == 0) {
                    (switch.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
                }
                switch.setOnCheckedChangeListener { _, checked -> if (!binding) onUserToggle(spec, switch, checked) }
                container.addView(view)
                rows += Row(spec, switch, view.findViewById(R.id.featureRowDescription))
            }
        }
        refresh()
    }

    /** Re-reads the values (after a change from elsewhere, or when the screen opens). */
    fun refresh() {
        val snapshot = FeatureCatalog.resolveAll(host.benchmarkLaunch, host::extra, ::stored)
        binding = true
        rows.forEach { row ->
            val parentOn = row.spec.dependsOn.all(snapshot::isOn)
            row.switch.isChecked = snapshot.isOn(row.spec)
            val note = host.rowNote(row.spec, snapshot.isOn(row.spec), snapshot)
            if (note != null) { row.description.text = note; row.noted = true }
            else if (row.noted) { row.description.setText(row.spec.descriptionRes); row.noted = false }
            row.switch.isEnabled = !host.benchmarkLaunch && parentOn
        }
        binding = false
    }

    private fun onUserToggle(spec: FeatureSpec, switch: MaterialSwitch, checked: Boolean) {
        if (host.benchmarkLaunch) {
            refresh()
            return
        }
        if (checked) {
            // Shown off until the explanation is agreed to; an agreement re-checks it through commit().
            binding = true
            switch.isChecked = false
            binding = false
            if (host.confirmEnable(spec) { commit(spec, true) }) return
        }
        commit(spec, checked)
    }

    private fun commit(spec: FeatureSpec, enabled: Boolean) {
        prefs.edit().putBoolean(spec.prefKey ?: return, enabled).apply()
        refresh()
        host.onChanged(spec, enabled)
    }

    private fun stored(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
}
