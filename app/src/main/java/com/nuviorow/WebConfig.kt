package com.nuviorows

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Mini server web sulla TV: permette di incollare URL e impostazioni dal browser del telefono
 * (stessa rete Wi-Fi), senza usare la tastiera della TV.
 */
class WebConfig(
    private val store: Store,
    private val onAddAddon: (String) -> String,
    private val onImportList: (String) -> String,
    private val onSettingsSaved: () -> Unit
) {

    companion object {
        const val PORT = 8765
    }

    @Volatile
    private var running = false

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                while (running) {
                    val s = ss.accept()
                    Thread { handle(s) }.start()
                }
            } catch (e: Exception) {
                // chiuso o porta occupata
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // ignora
        }
        server = null
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 60000
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")

            var contentLength = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("content-length:", ignoreCase = true)) {
                    contentLength = h.substring("content-length:".length).trim().toIntOrNull() ?: 0
                }
            }

            var form: Map<String, String> = emptyMap()
            if (method == "POST") {
                val buf = ByteArray(contentLength.coerceIn(0, 65536))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                form = parseForm(String(buf, 0, read, Charsets.UTF_8))
            }

            var message = ""
            if (method == "POST") {
                message = when (path) {
                    "/addon" -> onAddAddon(form["url"] ?: "")
                    "/import" -> onImportList(form["url"] ?: "")
                    "/settings" -> {
                        store.appPackage = form["pkg"] ?: ""
                        store.deepLink = form["deep"] ?: ""
                        store.maxItems = (form["max"]?.trim()?.toIntOrNull() ?: 20).coerceIn(5, 50)
                        onSettingsSaved()
                        "Impostazioni salvate."
                    }
                    else -> ""
                }
            }

            val bytes = page(message).toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            // connessione chiusa dal client
        } finally {
            try {
                s.close()
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun parseForm(body: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in body.split("&")) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore("=")
            val v = pair.substringAfter("=", "")
            try {
                map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: Exception) {
                // ignora coppia non valida
            }
        }
        return map
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(msg: String): String {
        val note = if (msg.isNotEmpty()) "<p class=\"msg\">" + esc(msg) + "</p>" else ""
        return """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio Rows</title>
<style>
body{font-family:sans-serif;margin:16px;background:#121212;color:#fff}
input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;margin:6px 0 10px;border-radius:6px;border:1px solid #555;background:#222;color:#fff}
button{padding:12px 18px;font-size:16px;border:0;border-radius:6px;background:#7C4DFF;color:#fff}
h2{margin-top:28px}
.msg{background:#1b5e20;padding:10px;border-radius:6px}
small{color:#aaa}
</style></head><body>
<h1>Nuvio Rows</h1>
$note
<h2>Aggiungi addon</h2>
<form method="post" action="/addon">
<input name="url" placeholder="https://.../manifest.json">
<button>Aggiungi</button>
</form>
<small>Dopo averlo aggiunto, spunta i cataloghi che vuoi sulla TV.</small>
<h2>Importa lista</h2>
<form method="post" action="/import">
<input name="url" placeholder="URL di un file di testo (un manifest per riga)">
<button>Importa</button>
</form>
<h2>Impostazioni</h2>
<form method="post" action="/settings">
<small>Pacchetto app Nuvio (può restare vuoto)</small>
<input name="pkg" value="${esc(store.appPackage)}">
<small>Deep link, es. stremio:///detail/{type}/{id}</small>
<input name="deep" value="${esc(store.deepLink)}">
<small>Titoli per riga (5-50)</small>
<input name="max" value="${store.maxItems}">
<button>Salva</button>
</form>
</body></html>"""
    }
}
