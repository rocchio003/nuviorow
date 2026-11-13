package com.nuviorows

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.tvprovider.media.tv.TvContractCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var web: WebConfig
    private val io = Executors.newSingleThreadExecutor()
    private var addons: MutableList<AddonCfg> = mutableListOf()

    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var webInfo: TextView
    private val fieldRefreshers = mutableListOf<() -> Unit>()

    private val gray = 0xFFAAAAAA.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        addons = store.loadAddons()

        web = WebConfig(
            store,
            onAddAddon = { url -> doAdd(url) },
            onImportList = { url -> doImport(url) },
            onSettingsSaved = {
                runOnUiThread {
                    for (r in fieldRefreshers) r()
                    Scheduler.schedule(this@MainActivity)
                }
            }
        )

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
        webInfo = tv("", 14f, 0xFFFFD54F.toInt(), false)
        root.addView(webInfo)

        // ---- Impostazioni ----
        root.addView(tv("1. Impostazioni apertura card", 20f, Color.WHITE, true))
        root.addView(fieldBtn("Pacchetto app Nuvio", { store.appPackage }, { store.appPackage = it }))
        root.addView(fieldBtn("Deep link", { store.deepLink }, { store.deepLink = it }))
        root.addView(
            fieldBtn(
                "Titoli per riga",
                { store.maxItems.toString() },
                { store.maxItems = (it.toIntOrNull() ?: 20).coerceIn(5, 50) },
                true
            )
        )
        root.addView(
            tv(
                "Deep link consigliato: stremio:///detail/{type}/{id}  ({type} = movie/series, {id} = id del titolo). " +
                    "Il pacchetto può restare vuoto. Premi un campo per modificarlo.",
                12f, gray, false
            )
        )

        // ---- Aggiunta addon ----
        root.addView(tv("2. Aggiungi addon", 20f, Color.WHITE, true))
        root.addView(btn("Aggiungi addon (incolla URL manifest)") {
            askText("URL manifest dell'addon", "", false) { url -> runBg { doAdd(url) } }
        })
        root.addView(btn("Importa lista di addon da URL") {
            askText("URL della lista (un manifest per riga)", "", false) { url -> runBg { doImport(url) } }
        })

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

    override fun onResume() {
        super.onResume()
        web.start()
        val ips = localIps()
        webInfo.text = if (ips.isEmpty()) {
            "Rete non trovata: collega la TV al Wi-Fi per usare l'inserimento dal telefono."
        } else {
            "Per incollare gli URL dal telefono (stessa Wi-Fi) apri nel browser: " +
                ips.joinToString("  oppure  ") { "http://$it:${WebConfig.PORT}" }
        }
    }

    override fun onPause() {
        super.onPause()
        web.stop()
    }

    // ---------- azioni ----------

    // Da chiamare su un thread in background (rete). Aggiorna la UI e restituisce il messaggio.
    private fun doAdd(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return "Incolla prima l'URL del manifest."
        return try {
            val a = Stremio.loadAddon(u)
            runOnUiThread {
                mergeAddon(a)
                store.saveAddons(addons)
                renderAddons()
            }
            "Aggiunto: ${a.name} (${a.catalogs.size} cataloghi utilizzabili). Spunta quelli che vuoi in home, sulla TV."
        } catch (e: Exception) {
            "Errore: ${e.message}"
        }
    }

    private fun doImport(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return "Incolla prima l'URL della lista."
        return try {
            val lines = Net.get(u).lines()
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
            }
            "Importati ${loaded.size} addon" + if (failed > 0) ", $failed non raggiungibili." else "."
        } catch (e: Exception) {
            "Errore: ${e.message}"
        }
    }

    private fun runBg(work: () -> String) {
        setStatus("Attendi...")
        io.execute {
            val msg = work()
            runOnUiThread { setStatus(msg) }
        }
    }

    private fun onRefresh() {
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
        if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    // Bottone che mostra "Titolo: valore"; premendolo si apre una finestra per modificarlo.
    private fun fieldBtn(
        title: String,
        get: () -> String,
        set: (String) -> Unit,
        numeric: Boolean = false
    ): Button {
        val b = Button(this)
        b.isAllCaps = false
        val refresh: () -> Unit = {
            b.text = title + ": " + get().ifEmpty { "(vuoto)" }
        }
        refresh()
        fieldRefreshers.add(refresh)
        b.setOnClickListener {
            askText(title, get(), numeric) { v ->
                set(v)
                refresh()
                Scheduler.schedule(this@MainActivity)
            }
        }
        return b
    }

    // Finestra di inserimento testo: tastiera della TV oppure pulsante "Incolla" dagli appunti.
    private fun askText(title: String, current: String, numeric: Boolean, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setSingleLine(true)
        input.inputType = if (numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        input.setText(current)
        input.setSelection(input.text.length)

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNeutralButton("Incolla", null)
            .setNegativeButton("Annulla", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val t = clip.getItemAt(0).coerceToText(this@MainActivity).toString().trim()
                input.setText(t)
                input.setSelection(input.text.length)
            } else {
                Toast.makeText(this@MainActivity, "Appunti vuoti", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun localIps(): List<String> {
        val out = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // ignora
        }
        return out
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
