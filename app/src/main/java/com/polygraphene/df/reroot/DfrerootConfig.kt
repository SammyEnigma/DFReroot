package com.polygraphene.df.reroot

import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object DfrerootConfig {
    private const val PATH = "/data/system/dfreroot.xml"

    fun isD2Enabled(): Boolean = readTag("d2fix") == "1"

    fun setD2Enabled(enabled: Boolean): Boolean {
        writeTag("d2fix", if (enabled) "1" else "0")
        return isD2Enabled() == enabled
    }

    fun isAutoRootEnabled(): Boolean = readTag("autoroot") == "1"

    fun setAutoRootEnabled(enabled: Boolean): Boolean {
        writeTag("autoroot", if (enabled) "1" else "0")
        return isAutoRootEnabled() == enabled
    }

    fun getLastBootId(): String? = readTag("last_boot_id")

    fun setLastBootId(id: String) {
        writeTag("last_boot_id", id)
    }

    private fun readTag(name: String): String? {
        return try {
            readAll()[name]
        } catch (t: Throwable) {
            null
        }
    }

    private fun writeTag(name: String, value: String) {
        val tags = try {
            readAll()
        } catch (t: Throwable) {
            mutableMapOf()
        }
        tags[name] = value
        val s = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<dfreroot>\n")
        for ((k, v) in tags) {
            s.append("<").append(k).append(">").append(v).append("</").append(k).append(">\n")
        }
        s.append("</dfreroot>\n")
        File(PATH).writeText(s.toString())
    }

    private fun readAll(): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(File(PATH).reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.depth == 2) {
                out[parser.name] = parser.nextText()
            }
            event = parser.next()
        }
        return out
    }
}
