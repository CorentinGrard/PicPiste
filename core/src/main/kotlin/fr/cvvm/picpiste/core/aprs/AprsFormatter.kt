package fr.cvvm.picpiste.core.aprs

import fr.cvvm.picpiste.core.geo.GeoPoint
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Writes OGN-style aircraft beacons. Used by the demo traffic and the tests. */
object AprsFormatter {
    fun aircraft(
        address: String,
        type: AircraftType,
        geo: GeoPoint,
        altitudeM: Double,
        trackDeg: Double?,
        groundSpeedKmh: Double,
        climbMs: Double?,
        timeMs: Long,
        receiver: String = "DEMO",
        noTrack: Boolean = false,
        stealth: Boolean = false,
    ): String {
        val t = Instant.ofEpochMilli(timeMs).atOffset(ZoneOffset.UTC)
        val time = "%02d%02d%02d".format(t.hour, t.minute, t.second)
        val (lat, latExtra) = coord(abs(geo.lat), 2)
        val (lon, lonExtra) = coord(abs(geo.lon), 3)
        val ns = if (geo.lat >= 0) 'N' else 'S'
        val ew = if (geo.lon >= 0) 'E' else 'W'
        val symbol = if (type == AircraftType.TOW_PLANE || type == AircraftType.POWERED) '\'' else '\''
        val course = trackDeg?.roundToInt()?.let { if (it <= 0) 360 else it.coerceAtMost(360) } ?: 0
        val speedKt = (groundSpeedKmh / 1.852).roundToInt().coerceIn(0, 999)
        val altFt = (altitudeM / 0.3048).roundToInt()
        val flags = (if (stealth) 0x80 else 0) or (if (noTrack) 0x40 else 0) or (type.ordinal shl 2) or AddressType.FLARM.ordinal
        val fpm = climbMs?.let { " %+04dfpm".format(Locale.ROOT, (it / 0.00508).roundToInt()) } ?: ""
        return "FLR$address>OGFLR,qAS,$receiver:/${time}h$lat$ns/$lon$ew$symbol" +
            "%03d/%03d/A=%06d".format(course, speedKt, altFt) +
            " !W$latExtra$lonExtra! id%02X$address$fpm +0.0rot".format(flags)
    }

    /** `DDMM.mm` (or `DDDMM.mm`) plus the third decimal digit of the minutes for the `!Wxy!` field. */
    private fun coord(deg: Double, degDigits: Int): Pair<String, Int> {
        var d = deg.toInt()
        var thousandths = ((deg - d) * 60_000).roundToLong()
        if (thousandths >= 60_000) {
            d += 1
            thousandths -= 60_000
        }
        val hundredths = thousandths / 10
        val extra = (thousandths % 10).toInt()
        val text = "%0${degDigits}d%02d.%02d".format(d, hundredths / 100, hundredths % 100)
        return text to extra
    }
}
