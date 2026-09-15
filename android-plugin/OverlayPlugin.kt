package com.aiyerbeyer.aicontext

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * پل ارتباطی بین جاوااسکریپت (index.html) و سرویس نیتیو حباب شناور.
 * تمام منطق (تشخیص تکراری، الحاق چندتکه، شماره‌ی ترن، Undo) داخل جاوااسکریپت باقی می‌ماند؛
 * این پلاگین فقط مجوز overlay، روشن/خاموش کردن سرویس، و رد و بدل کردن متن را انجام می‌دهد.
 */
@CapacitorPlugin(name = "OverlayCapture")
class OverlayPlugin : Plugin() {

    companion object {
        // رفرنس استاتیک تا سرویس بتواند مستقیماً به این پلاگین رویداد بفرستد
        var instance: OverlayPlugin? = null
    }

    override fun load() {
        instance = this
    }

    @PluginMethod
    fun hasOverlayPermission(call: PluginCall) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        val ret = JSObject()
        ret.put("granted", granted)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestOverlayPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
            activity.startActivity(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun startCapture(call: PluginCall) {
        val intent = Intent(context, OverlayService::class.java)
        intent.action = OverlayService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun stopCapture(call: PluginCall) {
        val intent = Intent(context, OverlayService::class.java)
        intent.action = OverlayService.ACTION_STOP
        context.startService(intent)
        call.resolve()
    }

    @PluginMethod
    fun updateLabel(call: PluginCall) {
        val text = call.getString("text") ?: ""
        OverlayService.instance?.updateLabels(text, "")
        call.resolve()
    }

    @PluginMethod
    fun updateLabels(call: PluginCall) {
        val prompt = call.getString("prompt") ?: ""
        val response = call.getString("response") ?: ""
        OverlayService.instance?.updateLabels(prompt, response)
        call.resolve()
    }

    /** نمایش پیام نیتیو — چون پیام‌های داخل WebView وقتی اپ در پس‌زمینه است دیده نمی‌شوند */
    @PluginMethod
    fun showToast(call: PluginCall) {
        val text = call.getString("text") ?: ""
        if (text.isNotEmpty()) OverlayService.instance?.showToast(text)
        call.resolve()
    }

    // این توابع از داخل OverlayService صدا زده می‌شوند (نه از جاوااسکریپت).
    // پارامتر retainUntilConsumed=true باعث می‌شود اگر WebView در آن لحظه در پس‌زمینه
    // معلق شده باشد، رویداد گم نشود و به‌محض برگشتن تحویل داده شود.
    fun emitClipboardCaptured(text: String, target: String) {
        val data = JSObject()
        data.put("text", text)
        data.put("target", target)
        notifyListeners("clipboardCaptured", data, true)
    }

    fun emitSimple(eventName: String) {
        notifyListeners(eventName, JSObject(), true)
    }
}
