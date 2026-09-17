package com.polygraphene.df.reroot

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

class TerminalActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        output = findViewById(R.id.termOutput)
        scroller = findViewById(R.id.termScroll)
        input = findViewById(R.id.termInput)
        send = findViewById(R.id.termSend)
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        input.text.clear()
        append("$ $cmd")
        Thread {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                val out = StringBuilder()
                val err = StringBuilder()
                val t1 = Thread {
                    try {
                        out.append(p.inputStream.readBytes().toString(Charsets.UTF_8))
                    } catch (_: Exception) {
                    }
                }
                val t2 = Thread {
                    try {
                        err.append(p.errorStream.readBytes().toString(Charsets.UTF_8))
                    } catch (_: Exception) {
                    }
                }
                t1.start()
                t2.start()
                val rc = p.waitFor()
                t1.join()
                t2.join()
                val s = StringBuilder()
                if (out.isNotBlank()) s.append(out.toString().trim()).append("\n")
                if (err.isNotBlank()) s.append("[stderr] ").append(err.toString().trim()).append("\n")
                s.append("[rc=$rc]")
                append(s.toString())
            } catch (t: Throwable) {
                append("[x] exec failed: ${t.message}")
            }
        }.start()
    }

    private fun append(s: String) {
        val line = s + if (s.endsWith("\n")) "" else "\n"
        runOnUiThread {
            output.append(line)
            scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
