package com.nuviorows

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Trampolino invisibile: la card della home apre questa activity, che legge le impostazioni
 * AL MOMENTO del tocco e apre Nuvio (deep link, poi pacchetto come ripiego).
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val type = intent.getStringExtra("type") ?: ""
        val id = intent.getStringExtra("id") ?: ""
        val name = intent.getStringExtra("name") ?: ""

        val store = Store(this)
        val pkg = store.appPackage
        val template = store.deepLink

        var opened = false
        var tried = ""

        if (template.isNotEmpty() && id.isNotEmpty()) {
            val uriStr = template
                .replace("{type}", Uri.encode(type))
                .replace("{id}", Uri.encode(id, ":"))
                .replace("{name}", Uri.encode(name))
            tried = uriStr
            if (pkg.isNotEmpty()) {
                opened = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).setPackage(pkg))
            }
            if (!opened) {
                opened = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
            }
        }

        if (!opened && pkg.isNotEmpty()) {
            val pm = packageManager
            val li = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (li != null) opened = tryStart(li)
        }

        if (!opened) {
            val msg = "Impossibile aprire Nuvio. Link: " + (if (tried.isEmpty()) "(vuoto)" else tried) +
                " | Pacchetto: " + (if (pkg.isEmpty()) "(vuoto)" else pkg)
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun tryStart(i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
