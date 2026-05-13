package com.odmvod.vod

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OdmPluginLoader : Plugin() {
    override fun load(context: Context) {
        // Registra in modo esclusivo lo scraper nativo di OdmVod
        registerMainAPI(OdmVodProvider())
    }
}
