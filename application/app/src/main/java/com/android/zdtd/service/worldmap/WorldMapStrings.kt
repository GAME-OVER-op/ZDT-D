package com.android.zdtd.service.worldmap

import android.content.Context
import com.android.zdtd.service.R
import com.android.zdtd.service.worldmap.model.ClosingSide

class WorldMapStrings(private val context: Context) {
    fun activityLabel(score: Float): String {
        return when {
            score >= 0.82f -> context.getString(R.string.world_map_activity_very_high)
            score >= 0.58f -> context.getString(R.string.world_map_activity_high)
            score >= 0.32f -> context.getString(R.string.world_map_activity_medium)
            else -> context.getString(R.string.world_map_activity_low)
        }
    }

    fun enhancedActivityLabel(score: Float): String {
        return when {
            score >= 0.86f -> context.getString(R.string.world_map_activity_very_high)
            score >= 0.62f -> context.getString(R.string.world_map_activity_high)
            score >= 0.34f -> context.getString(R.string.world_map_activity_medium)
            else -> context.getString(R.string.world_map_activity_low)
        }
    }

    fun geoNoGeo(protocol: String): String =
        context.getString(R.string.world_map_geo_no_geo, protocol.uppercase())

    fun geoSkipped(protocol: String): String =
        context.getString(R.string.world_map_geo_skipped, protocol.uppercase())

    fun geoResolving(): String = context.getString(R.string.world_map_geo_resolving)

    fun unknownState(): String = context.getString(R.string.world_map_unknown_state)

    fun unknownLocation(): String = context.getString(R.string.world_map_unknown_location)

    fun failedConnectionsCommand(): String = context.getString(R.string.world_map_error_execute_connections)

    fun rootUnavailable(): String = context.getString(R.string.world_map_error_root_unavailable)

    fun readConnectionsFailed(): String = context.getString(R.string.world_map_error_read_connections)

    fun closingSide(side: ClosingSide): String {
        return when (side) {
            ClosingSide.LOCAL -> context.getString(R.string.world_map_closing_local)
            ClosingSide.REMOTE -> context.getString(R.string.world_map_closing_remote)
            ClosingSide.UNKNOWN -> context.getString(R.string.world_map_closing_unknown)
            ClosingSide.NONE -> ""
        }
    }

    fun localityLabel(countryCode: String, city: String, region: String): String {
        val locality = when {
            city.isNotBlank() -> city
            region.isNotBlank() -> region
            else -> unknownLocation()
        }
        return context.getString(R.string.world_map_geo_label_format, countryCode, locality)
    }
}
