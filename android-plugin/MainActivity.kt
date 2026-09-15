package com.aiyerbeyer.aicontext

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // پلاگین سفارشی باید قبل از super.onCreate ثبت شود
        registerPlugin(OverlayPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
