package com.nuviorows

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.tvprovider.media.tv.TvContractCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var store: Store
    private val io = Executors.newSingleThreadExecutor()
    private var addons: MutableList<AddonCfg> = mutableListOf()

    private lateinit var etPackage: EditText
    private lateinit var etDeepLink: EditText
    private lateinit var etMax: EditText
    private lateinit var etManifest: EditText
    private lateinit var etImport: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView

    private val gray = 0xFFAAAAAA.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        addons = store.loadAddons()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(48), dp(32), dp(48), dp(32))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF121212.toInt())
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)

        root.addView(tv("Nuvio Rows", 28f, Color.WHITE, true))
        root.addView(
            tv(
                "Mostra i cataloghi dei tuoi addon (Cinemeta e simili) come righe nella home di Projectivy.",
                14f, gray, false
            )
        )

        // ---- Impostazioni ----
        root.addView(tv("1. Impostazioni apertura card", 20f, Color.WHITE, true))
        etPackage = edit("Pacchetto app Nuvio (es. com.esempio.nuvio)", store.appPackage, false)
        etDeepLink = edit("Deep link (opzionale), es. nuvio://{type}/{id}", store.deepLink, false)
        etMax = edit("Titoli per riga (5-50)", store.maxItems.toString(), true)
        root.addView(etPackage)
        root.addView(etDeepLink)
        root.addView(etMax)
        root.addView(
            tv(
                "Segnaposto deep link: {type} = movie/series, {id} = id del titolo, {name} = nome. " +
                    "Se lo lasci vuoto, la card apre l'app indicata sopra.",
                12f, gray, false
            )
        )
        root.addView(btn("Salva impostazioni") {
            saveSettings()
            Scheduler.schedule(this@MainActivity)
            setStatus("Impostazioni salvate.")
        })

        // ---- Aggiunta addon ----
        root.addView(tv("2. Aggiungi addon", 20f, Color.WHITE, true))
        etManifest = edit("URL manifest dell'addon (https://.../manifest.json)", "", false)
        root.addView(etManifest)
        root.addView(btn("Aggiungi addon") { onAddAddon() })

        etImport = edit("URL di una lista (un manifest per riga, es. Gist raw)", "", false)
        root.addView(etImport)
        root.addView(btn("Importa lista di addon") { onImportList() })

        // ---- Cataloghi ----
        root.addView(tv("3. Scegli i cataloghi da mostrare", 20f, Color.WHITE, true))
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)

        // ---- Aggiorna ----
        root.addView(tv("4. Applica", 20f, Color.WHITE, true))
        root.addView(btn("Aggiorna le righe adesso") { onRefresh() })
        status = tv("", 14f, 0xFF80CBC4.toInt(), false)
        root.addView(status)
        root.addView(
            tv(
                "Dopo il primo aggiornamento, in Projectivy abilita le nuove righe dalle impostazioni dei canali. " +
                    "L'app poi le aggiorna da sola ogni 6 ore.",
                12f, gray, false
            )
        )

        renderAddons()
        requestTvPermissions()
        Scheduler.schedule(this)
    }

    // ---------- azioni ----------

    private fun onAddAddon() {
        val url = etManifest.text.toString().trim()
        if (url.isEmpty()) {
            setStatus("Incolla prima l'URL del manifest.")
            return
        }
        setStatus("Carico il manifest...")
        io.execute {
            try {
                val a = Stremio.loadAddon(url)
                runOnUiThread {
                    mergeAddon(a)
                    store.saveAddons(addons)
                    renderAddons()
                    etManifest.setText("")
                    setStatus(
                        "Aggiunto: ${a.name} (${a.catalogs.size} cataloghi utilizzabili). " +
                            "Spunta quelli che vuoi in home."
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Errore: ${e.message}") }
            }
        }
    }

    private fun onImportList() {
        val url = etImport.text.toString().trim()
        if (url.isEmpty()) {
            setStatus("Incolla prima l'URL della lista.")
            return
        }
        setStatus("Scarico la lista...")
        io.execute {
            try {
                val lines = Net.get(url).lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                val loaded = mutableListOf<AddonCfg>()
                var failed = 0
                for (l in lines) {
                    try {
                        loaded.add(Stremio.loadAddon(l))
                    } catch (e: Exception) {
                        failed++
                    }
                }
                runOnUiThread {
                    for (a in loaded) mergeAddon(a)
                    store.saveAddons(addons)
                    renderAddons()
                    setStatus("Importati ${loaded.size} addon" + if (failed > 0) ", $failed non raggiungibili." else ".")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Errore: ${e.message}") }
            }
        }
    }

    private fun onRefresh() {
        saveSettings()
        Scheduler.schedule(this)
        setStatus("Aggiorno le righe...")
        io.execute {
            val r = RefreshEngine.refresh(this@MainActivity)
            runOnUiThread {
                setStatus(r.message)
                for (id in r.newChannelIds) {
                    try {
                        TvContractCompat.requestChannelBrowsable(this@MainActivity, id)
                    } catch (e: Exception) {
                        // alcuni launcher non mostrano la richiesta: va bene lo stesso
                    }
                }
            }
        }
    }

    private fun mergeAddon(new: AddonCfg) {
        val idx = addons.indexOfFirst { it.baseUrl == new.baseUrl }
        if (idx >= 0) {
            val old = addons[idx]
            for (c in new.catalogs) {
                c.enabled = old.catalogs.firstOrNull { it.key == c.key }?.enabled ?: false
            }
            addons[idx] = new
        } else {
            addons.add(new)
        }
    }

    private fun saveSettings() {
        store.appPackage = etPackage.text.toString()
        store.deepLink = etDeepLink.text.toString()
        store.maxItems = (etMax.text.toString().toIntOrNull() ?: 20).coerceIn(5, 50)
    }

    // ---------- UI ----------

    private fun renderAddons() {
        listBox.removeAllViews()
        if (addons.isEmpty()) {
            listBox.addView(tv("Nessun addon aggiunto.", 14f, gray, false))
            return
        }
        for (addon in addons) {
            listBox.addView(tv(addon.name, 18f, Color.WHITE, true))
            listBox.addView(tv(addon.baseUrl, 11f, gray, false))
            if (addon.catalogs.isEmpty()) {
                listBox.addView(tv("Nessun catalogo utilizzabile come riga.", 13f, gray, false))
            }
            for (cat in addon.catalogs) {
                val cb = CheckBox(this)
                cb.text = "${cat.name} (${typeLabel(cat.type)})"
                cb.setTextColor(Color.WHITE)
                cb.isChecked = cat.enabled
                cb.setOnCheckedChangeListener { _, checked ->
                    cat.enabled = checked
                    store.saveAddons(addons)
                }
                listBox.addView(cb)
            }
            listBox.addView(btn("Rimuovi ${addon.name}") {
                addons.remove(addon)
                store.saveAddons(addons)
                renderAddons()
                setStatus("Rimosso. Premi \"Aggiorna le righe adesso\" per toglierlo dalla home.")
            })
        }
    }

    private fun setStatus(msg: String) {
        status.text = msg
    }

    private fun typeLabel(type: String): String = when (type) {
        "movie" -> "Film"
        "series" -> "Serie"
        else -> type
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        v.setPadding(0, dp(12), 0, dp(4))
        return v
    }

    private fun edit(hintText: String, value: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(0xFF777777.toInt())
        e.textSize = 15f
        e.inputType = if (numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        return e
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun requestTvPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val perms = arrayOf(
            "com.android.providers.tv.permission.READ_EPG_DATA",
            "com.android.providers.tv.permission.WRITE_EPG_DATA"
        )
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            try {
                requestPermissions(missing.toTypedArray(), 1)
            } catch (e: Exception) {
                // ignora
            }
        }
    }
}
