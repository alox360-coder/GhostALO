package it.privacycheck

import android.app.Application
import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Traduzioni. Il codice contiene i testi in italiano; se la lingua del telefono non è
 * l'italiano si usano le traduzioni inglesi di assets/en.json. Se manca una traduzione
 * resta il testo italiano, quindi nulla può rompersi.
 */
object T {
    private var map: Map<String, String>? = null
    private var italian = true

    fun init(ctx: Context) {
        italian = Locale.getDefault().language == "it"
        if (italian) return
        try {
            val txt = ctx.assets.open("en.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            val m = HashMap<String, String>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                m[k] = o.getString(k)
            }
            map = m
        } catch (e: Exception) {
            map = null
        }
    }

    fun tr(s: String): String = if (italian) s else (map?.get(s) ?: s)
}

fun tr(s: String): String = T.tr(s)

fun trf(s: String, vararg a: Any?): String = String.format(T.tr(s), *a)

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        T.init(this)
    }
}
