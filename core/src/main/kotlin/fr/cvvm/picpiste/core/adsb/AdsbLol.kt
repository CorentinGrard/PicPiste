package fr.cvvm.picpiste.core.adsb

import fr.cvvm.picpiste.core.aprs.AddressType
import fr.cvvm.picpiste.core.aprs.AircraftBeacon
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsParser
import java.util.Locale
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * ADS-B traffic from adsb.lol, an open network of receivers (data under ODbL). OGN relays some
 * ADS-B too, but its receivers mostly hear aircraft high up or far away: around a big airport,
 * the traffic low near the runways only comes from here.
 *
 * Its `/v2/point` API answers what its receivers heard around a point (readsb's JSON format).
 */
object AdsbLol {
    const val API = "https://api.adsb.lol/v2/point"

    /** How often to ask: positions are a few seconds old anyway, and the API is free. */
    const val POLL_EVERY_MS = 5_000L

    /** Request for every aircraft within [radiusKm] of a point (the API counts nautical miles). */
    fun url(lat: Double, lon: Double, radiusKm: Int): String =
        String.format(Locale.ROOT, "%s/%.4f/%.4f/%d", API, lat, lon, ceil(radiusKm / 1.852).toInt())

    /**
     * One aircraft. [beacon] as if relayed by OGN; [onGround]: its altitude is meaningless, the
     * caller puts it on the field.
     */
    data class Aircraft(val beacon: AircraftBeacon, val registration: String?, val onGround: Boolean)

    private val HEX = Regex("^[0-9a-fA-F]{6}$")
    private const val FT = 0.3048
    /** Pressure altitude: about 8.3 m per hPa near the ground. */
    private const val M_PER_HPA = 8.3
    private const val STANDARD_HPA = 1013.25

    /** Aircraft in an answer; empty when it holds none or cannot be read. */
    fun parse(text: String): List<Aircraft> {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val nowMs = root.num("now")?.toLong() ?: return emptyList()
        val list = (root["ac"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()

        // The QNH crews nearby have set (the local pressure): for those that do not send theirs.
        val localQnh = list.mapNotNull { a -> a.num("nav_qnh")?.takeIf { it in 950.0..1060.0 } }.sorted()
            .let { q -> if (q.isEmpty()) null else q[q.size / 2] }

        return list.mapNotNull { a ->
            val hex = a.str("hex")?.takeIf { HEX.matches(it) } ?: return@mapNotNull null // "~…": no ICAO address
            val lat = a.num("lat") ?: return@mapNotNull null
            val lon = a.num("lon") ?: return@mapNotNull null
            val seenPos = a.num("seen_pos") ?: 0.0
            if (seenPos > 60) return@mapNotNull null // an old position, not traffic now
            val onGround = a.str("alt_baro") == "ground"
            val altitude = if (onGround) 0.0 else altitudeM(a, localQnh) ?: return@mapNotNull null
            val category = a.str("category")
            val sentMs = nowMs - (seenPos * 1000).toLong()
            val beacon = AircraftBeacon(
                address = hex.uppercase(),
                addressType = AddressType.ICAO,
                aircraftType = AprsParser.typeOfCategory(category, AircraftType.UNKNOWN)
                    .takeUnless { it == AircraftType.UNKNOWN } ?: AircraftType.POWERED,
                stealth = false,
                noTrack = false,
                lat = lat,
                lon = lon,
                altitudeM = altitude,
                trackDeg = a.num("track"),
                groundSpeedKmh = (a.num("gs") ?: 0.0) * 1.852,
                climbMs = (a.num("geom_rate") ?: a.num("baro_rate"))?.let { it * FT / 60 },
                receiver = "adsb.lol",
                timeHms = hms(sentMs),
                callsign = a.str("flight")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            )
            Aircraft(beacon, a.str("r")?.trim()?.takeIf { it.isNotEmpty() }, onGround)
        }
    }

    /**
     * Above mean sea level, as the airfield's elevation: the pressure altitude set to the QNH
     * (the aircraft's own, else the one around), else the GNSS height (off by the geoid, a few
     * tens of metres), else the bare pressure altitude.
     */
    private fun altitudeM(a: JsonObject, localQnh: Double?): Double? {
        val baro = a.num("alt_baro")?.times(FT)
        val qnh = a.num("nav_qnh")?.takeIf { it in 950.0..1060.0 } ?: localQnh
        if (baro != null && qnh != null) return baro + (qnh - STANDARD_HPA) * M_PER_HPA
        return a.num("alt_geom")?.times(FT) ?: baro
    }

    /** "hhmmss" in UTC, as in OGN beacons (the tracker dates the fix from it). */
    private fun hms(ms: Long): String {
        val s = Math.floorMod(ms / 1000, 86_400L)
        return String.format(Locale.ROOT, "%02d%02d%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }
}
