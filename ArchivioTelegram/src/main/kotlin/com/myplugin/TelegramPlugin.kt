package com.telegram.vod

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TelegramPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TelegramChannelProvider())
    }
}
