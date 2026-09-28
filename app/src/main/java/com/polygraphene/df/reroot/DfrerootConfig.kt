package com.polygraphene.df.reroot

import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object DfrerootConfig {
    private const val PATH = "/data/system/dfreroot.xml"

    fun isD2Enabled(): Boolean {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(File(PATH).reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "d2fix") {
                    return parser.nextText().trim() == "1"
                }
                event = parser.next()
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    fun setD2Enabled(enabled: Boolean): Boolean {
        return try {
            val value = if (enabled) "1" else "0"
            File(PATH).writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<dfreroot>\n<d2fix>" + value + "</d2fix>\n</dfreroot>\n"
            )
            isD2Enabled() == enabled
        } catch (t: Throwable) {
            false
        }
    }
}
