package com.example.scamdetect

import com.example.scamdetect.logic.Tr
import com.example.scamdetect.logic.Txt
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's string resources for JVM unit tests: reads src/main/res/values/strings.xml (made by
 * tools/i18n/gen_strings.py) and maps R.string ids to them, like Context.getString does on the phone.
 */
object TestStrings {
    /** name -> text (Android escapes undone). */
    val table: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        buildMap {
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                put(n.attributes.getNamedItem("name").nodeValue, unescape(n.textContent))
            }
        }
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) { 'n' -> out.append('\n'); 't' -> out.append('\t'); else -> out.append(n) }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    fun name(id: Int): String = names[id] ?: error("no string resource $id")

    fun get(id: Int, vararg args: Any): String {
        val key = name(id)
        val raw = table[key] ?: error("missing $key")
        return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
    }

    fun txt(): Txt = Txt { id, args -> get(id, *args) }

    /** Points Tr at the strings, as MainActivity does on the phone. */
    fun install() {
        Tr.txt = txt()
    }
}
