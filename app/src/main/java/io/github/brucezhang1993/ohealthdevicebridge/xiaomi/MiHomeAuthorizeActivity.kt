package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MiHomeAuthorizeActivity : Activity() {
    private var request: MiHomeSync.Request? = null
    private lateinit var layout: LinearLayout
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var rendered = ""
    private val refresh = object : Runnable {
        override fun run() {
            val r = request ?: return
            val state = "${r.active}:${r.state}:${r.busy}:${r.message}:${r.devices.size}"
            if (state != rendered) { rendered = state; render() }
            if (!isFinishing) main.postDelayed(this, 500)
        }
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        @Suppress("DEPRECATION") val proof = intent.getParcelableExtra<android.app.PendingIntent>("caller")
        val hostUid = try { packageManager.getApplicationInfo(io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants.TARGET_PACKAGE, 0).uid } catch (_: Exception) { finish(); return }
        if (proof?.creatorUid != hostUid || proof.creatorPackage != io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants.TARGET_PACKAGE || hostUid / 100000 != applicationInfo.uid / 100000) { finish(); return }
        val id = intent.getStringExtra("request").orEmpty()
        val account = intent.getStringExtra("account").orEmpty()
        val model = intent.getStringExtra("model").orEmpty()
        if (!id.matches(Regex("[0-9a-f-]{36}")) || account.length !in 1..256 || !io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels.needsKey(model)) { finish(); return }
        request = MiHomeSync.find(id)?.takeIf { it.account == account && it.model == model } ?: MiHomeSync.begin(account, model, id)
        // OPPO's manifest cannot declare our provider. A narrow, persistent URI grant gives
        // package visibility without altering the host APK; Provider.call still checks its UID.
        val uri = android.net.Uri.parse("content://${packageName}.mihome")
        grantUriPermission(io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants.TARGET_PACKAGE, uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION") val receiver = intent.getParcelableExtra<android.os.ResultReceiver>("ready")
        receiver?.send(0, Bundle())
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        setContentView(ScrollView(this).apply { addView(layout) })
        render()
    }
    private fun render() {
        if (isFinishing || isDestroyed) return
        val r = request ?: return
        layout.removeAllViews()
        layout.addView(TextView(this).apply {
            textSize = 22f; text = "从米家读取体脂秤"
        })
        layout.addView(TextView(this).apply {
            textSize = 16f; text = "root 权限将授予 OHealth Device Bridge 模块。模块读取本机米家登录状态并联网获取所选秤的凭证。请先在米家登录、选择正确地区并绑定体脂秤。\n\n${r.message}"; setPadding(0, 24, 0, 24)
        })
        if (!r.active) { addButton("返回 OPPO 健康") { finish() }; return }
        if (r.busy) return
        if (r.state == "READY") { finish(); return }
        if (r.state == "LISTED") {
            r.devices.forEach { d -> addButton(d.name) {
                MiHomeSync.select(applicationContext, r, d) { runOnUiThread { render() } }; render()
            } }
        }
        addButton(if (r.state == "WAITING") "授权 root 并读取" else "重新读取") {
            MiHomeSync.list(applicationContext, r) { runOnUiThread { render() } }; render()
        }
        addButton("取消") { MiHomeSync.cancel(r.id); finish() }
    }
    private fun addButton(label: String, action: () -> Unit) { layout.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
    override fun onDestroy() {
        if (isFinishing) request?.takeIf { it.state != "READY" }?.let { MiHomeSync.cancel(it.id) }
        super.onDestroy()
    }
}
