package com.rcj811.podscompanion.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rcj811.podscompanion.AacpStatus
import com.rcj811.podscompanion.PodsRepository

/** Quick-settings tile: tap to cycle ANC → Transparency → Adaptive → Off. */
class NoiseTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        PodsService.start(this, PodsService.ACTION_CYCLE_MODE)
        render()
    }

    private fun render() {
        val s = PodsRepository.state.value
        val tile = qsTile ?: return
        if (s.aacp == AacpStatus.CONNECTED) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = s.listeningMode?.label ?: "降噪模式"
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "AirPods 降噪"
        }
        tile.updateTile()
    }
}
