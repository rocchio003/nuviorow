package com.nuviorows

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Guida TV in una finestra flottante (quasi a tutto schermo) sopra la home.
 */
class GuideActivity : Activity() {

    private lateinit var store: Store
    private lateinit var frame: FrameLayout
    private val io = Executors.newSingleThreadExecutor()
    private var refreshing = false
    private var guideView: GuideView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val dm = resources.displayMetrics
        frame = FrameLayout(this)
        val bg = GradientDrawable()
        bg.setColor(0xFF1C1B22.toInt())
        bg.cornerRadius = 20f * dm.density
        frame.background = bg
        setContentView(frame)
        window.setLayout((dm.widthPixels * 0.93f).toInt(), (dm.heightPixels * 0.90f).toInt())

        showMessage("Carico la guida...")
        io.execute {
            val channels = GuideData.load(this)
            val age = System.currentTimeMillis() - GuideData.updatedAt(this)
            runOnUiThread {
                if (channels != null && channels.isNotEmpty()) {
                    showGuide(channels)
                    if (age > 6L * 3600_000L && store.m3uUrl.isNotEmpty()) refreshGuide(false)
                } else if (store.m3uUrl.isEmpty()) {
                    showMessage("Imposta prima il link M3U in Nuvio Rows (punto 5).")
                } else {
                    refreshGuide(true)
                }
            }
        }
    }

    private fun refreshGuide(showLoading: Boolean) {
        if (refreshing) return
        refreshing = true
        if (showLoading) showMessage("Scarico canali e programmi...\nPuò volerci qualche istante.")
        else Toast.makeText(this, "Aggiorno la guida...", Toast.LENGTH_SHORT).show()
        io.execute {
            val msg = GuideData.refresh(this)
            val channels = GuideData.load(this)
            runOnUiThread {
                refreshing = false
                if (isFinishing) return@runOnUiThread
                if (channels != null && channels.isNotEmpty()) {
                    showGuide(channels)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } else {
                    showMessage(msg)
                }
            }
        }
    }

    private fun showMessage(text: String) {
        guideView = null
        frame.removeAllViews()
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 18f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.setPadding(48, 48, 48, 48)
        frame.addView(
            tv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun showGuide(channels: List<GChannel>) {
        frame.removeAllViews()
        val view = GuideView(
            this,
            channels,
            store.guideGroup,
            { ch -> openSparkle(ch) },
            { g -> store.guideGroup = g }
        )
        guideView = view
        frame.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        view.requestFocus()
    }

    override fun onBackPressed() {
        val v = guideView
        if (v != null && v.handleBack()) return
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            if (store.m3uUrl.isNotEmpty()) refreshGuide(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSparkle(ch: GChannel) {
        val pkg = store.sparklePackage
        var opened = false

        val template = store.sparkleLink
        if (template.isNotEmpty()) {
            val uri = template
                .replace("{name}", Uri.encode(ch.name))
                .replace("{id}", Uri.encode(ch.tvgId))
                .replace("{url}", Uri.encode(ch.url))
            opened = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg))
        }
        if (!opened) {
            val pm = packageManager
            val li = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (li != null) opened = tryStart(li)
        }
        if (opened) {
            finish()
        } else {
            Toast.makeText(this, "Impossibile aprire Sparkle TV ($pkg).", Toast.LENGTH_LONG).show()
        }
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
