package world.gatepay.sms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var simList: TextView
    private lateinit var logView: TextView
    private val navy = Color.parseColor("#0B1B3A"); private val blue = Color.parseColor("#2563EB")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40); setBackgroundColor(Color.parseColor("#F4F7FB")) }
        root.addView(title("GatePay SMS", 26f))
        status = text("স্ট্যাটাস যাচাই হচ্ছে…"); root.addView(card(status))

        // ১. টোকেন (কপি-পেস্ট)
        val tokenIn = EditText(this).apply { hint = "smsd_… টোকেন পেস্ট করুন"; setText(Prefs.token(this@MainActivity)); setSingleLine() }
        val paste = button("পেস্ট করুন") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.let { tokenIn.setText(it.toString().trim()) } }
        val save = button("সংযুক্ত করুন") {
            val t = tokenIn.text.toString().trim()
            if (!t.startsWith("smsd_")) { toast("টোকেন smsd_ দিয়ে শুরু হতে হবে"); return@button }
            Prefs.setToken(this, t); ForwarderService.start(this); check()
        }
        root.addView(card(col(title("১. অ্যাক্টিভেশন টোকেন", 16f), tokenIn, row(paste, save))))

        // ২. সিম ও অ্যাকাউন্ট ধরন
        val slot = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("SIM 1", "SIM 2")) }
        val number = EditText(this).apply { hint = "01XXXXXXXXX (১১ ডিজিট)"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val types = listOf("personal", "agent", "merchant")
        val type = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Personal", "Agent", "Merchant")) }
        val addSim = button("সিম যোগ করুন") {
            val n = number.text.toString().filter { it.isDigit() }
            if (!Regex("^01[3-9][0-9]{8}$").matches(n)) { toast("সঠিক ১১ ডিজিটের একটি নম্বর দিন"); return@button }
            Prefs.addSim(this, slot.selectedItemPosition, n, types[type.selectedItemPosition]); number.setText(""); refresh()
        }
        simList = text("")
        root.addView(card(col(title("২. সিম ও অ্যাকাউন্ট ধরন", 16f), slot, number, type, addSim, simList)))

        // ৩. ব্যাকগ্রাউন্ড সুরক্ষা
        root.addView(card(col(title("৩. ব্যাকগ্রাউন্ডে চালু রাখুন", 16f),
            text("রিসেন্ট অ্যাপস থেকে সরালেও যাচাই চলবে। নিচের অনুমতিগুলো দিন।"),
            button("এসএমএস ও নোটিফিকেশন অনুমতি") { askPerms() },
            button("ব্যাটারি সীমাবদ্ধতা বন্ধ করুন") { battery() },
            button("অ্যাপ সেটিংস (Autostart)") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })))

        logView = text(""); root.addView(card(col(title("সাম্প্রতিক কার্যক্রম", 16f), logView)))
        setContentView(ScrollView(this).apply { addView(root) })
        askPerms(); if (Prefs.token(this).isNotEmpty()) ForwarderService.start(this)
    }

    override fun onResume() { super.onResume(); refresh(); check() }

    private fun refresh() {
        val a = Prefs.sims(this)
        simList.text = if (a.length() == 0) "কোনো সিম যোগ করা হয়নি" else (0 until a.length()).joinToString("\n") {
            val o = a.getJSONObject(it); "SIM ${o.getInt("slot") + 1}: ${o.getString("number")} • ${o.getString("type").replaceFirstChar { c -> c.uppercase() }}"
        }
        logView.text = Prefs.log(this).ifEmpty { "এখনো কিছু নেই" }
    }

    private fun check() {
        val t = Prefs.token(this); if (t.isEmpty()) { status.text = "● সংযুক্ত নয় — টোকেন দিন"; status.setTextColor(Color.parseColor("#B45309")); return }
        thread { val r = Api.heartbeat(t); runOnUiThread {
            when (r?.first) {
                200 -> { status.text = "● অনলাইন — ব্যাকগ্রাউন্ডে সক্রিয় (অপেক্ষমাণ: ${Prefs.queue(this).length()})"; status.setTextColor(Color.parseColor("#047857")) }
                401 -> { status.text = "● টোকেন অবৈধ বা নিষ্ক্রিয়"; status.setTextColor(Color.parseColor("#B91C1C")) }
                else -> { status.text = "● অফলাইন — ইন্টারনেট এলে স্বয়ংক্রিয়ভাবে পাঠাবে"; status.setTextColor(Color.parseColor("#B45309")) }
            } } }
    }

    private fun askPerms() {
        val p = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun battery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("ইতিমধ্যে চালু আছে"); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ছোট UI সহায়ক
    private fun title(s: String, size: Float) = TextView(this).apply { text = s; textSize = size; typeface = Typeface.DEFAULT_BOLD; setTextColor(navy); setPadding(0, 8, 0, 12) }
    private fun text(s: String) = TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.parseColor("#1F2937")) }
    private fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(blue); cornerRadius = 24f }; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 } }
    private fun col(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }
    private fun row(vararg v: View) = LinearLayout(this).apply { v.forEach { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8 }; addView(it) } }
    private fun card(v: View) = LinearLayout(this).apply { setPadding(32, 28, 32, 28)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 28f; setStroke(2, Color.parseColor("#E2E8F0")) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 }; addView(v, LinearLayout.LayoutParams(-1, -2)) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
