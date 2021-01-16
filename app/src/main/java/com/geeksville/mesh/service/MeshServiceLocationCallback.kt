package com.geeksville.mesh.service

import android.location.Location
import android.os.RemoteException
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

val Location.isAccurateForMesh: Boolean get() = !this.hasAccuracy() || this.accuracy < 200

private fun List<Location>.filterAccurateForMesh() = filter { it.isAccurateForMesh }

private fun LocationResult.lastLocationOrBestEffort(): Location? {
    return lastLocation ?: locations.filterAccurateForMesh().lastOrNull()
}

typealias SendPosition = (Double, Double, Int, Int, Boolean) -> Unit // Lat, Lon, alt, destNum, wantResponse
typealias OnSendFailure = () -> Unit
typealias GetNodeNum = () -> Int

class MeshServiceLocationCallback(
    private val onSendPosition: SendPosition,
    private val onSendPositionFailed: OnSendFailure,
    private val getNodeNum: GetNodeNum,
    private val sendRateLimitInSeconds: Int = DEFAULT_SEND_RATE_LIMIT
) : LocationCallback() {

    companion object {
        const val DEFAULT_SEND_RATE_LIMIT = 30
    }

    private var lastSendTimeMs: Long = 0L

    override fun onLocationResult(locationResult: LocationResult) {
        super.onLocationResult(locationResult)

        locationResult.lastLocationOrBestEffort()?.let { location ->
            MeshService.info("got phone location")
            if (location.isAccurateForMesh) { // if within 200 meters, or accuracy is unknown
                val shouldSend = isAllowedToSend()
                val destinationNumber = if (shouldSend) MeshService.NODENUM_BROADCAST else getNodeNum()
                sendPosition(location, destinationNumber, wantResponse = shouldSend)
            } else {
                MeshService.warn("accuracy ${location.accuracy} is too poor to use")
            }
        }
    }

    private fun sendPosition(location: Location, destinationNumber: Int, wantResponse: Boolean) {
        try {
            onSendPosition(
                location.latitude,
                location.longitude,
                location.altitude.toInt(),
                destinationNumber,
                wantResponse // wantResponse?
            )
        } catch (ex: RemoteException) { // Really a RadioNotConnected exception, but it has changed into this type via remoting
            MeshService.warn("Lost connection to radio, stopping location requests")
            onSendPositionFailed()
        } catch (ex: BLEException) { // Really a RadioNotConnected exception, but it has changed into this type via remoting
            MeshService.warn("BLE exception, stopping location requests $ex")
            onSendPositionFailed()
        }
    }

    /**
     * Rate limiting function.
     */
    private fun isAllowedToSend(): Boolean {
        val now = System.currentTimeMillis()
        // we limit our sends onto the lora net to a max one once every FIXME
        val sendLora = (now - lastSendTimeMs >= sendRateLimitInSeconds * 1000)
        if (sendLora) {
            lastSendTimeMs = now
        }
        return sendLora
    }
}
