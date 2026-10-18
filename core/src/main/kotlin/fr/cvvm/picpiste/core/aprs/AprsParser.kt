package fr.cvvm.picpiste.core.aprs

/** OGN aircraft type, from bits 2–5 of the `idXX` flags byte. */
enum class AircraftType(val label: String) {
    UNKNOWN("Aéronef"),
    GLIDER("Planeur"),
    TOW_PLANE("Remorqueur"),
    HELICOPTER("Hélico"),
    SKYDIVER("Parachutiste"),
    DROP_PLANE("Avion largueur"),
    HANG_GLIDER("Delta"),
    PARAGLIDER("Parapente"),
    POWERED("Avion"),
    JET("Jet"),
    UFO("Aéronef"),
    BALLOON("Ballon"),
    AIRSHIP("Dirigeable"),
    UAV("Drone"),
    STATIC("Fixe"),
    RESERVED("Aéronef");

    companion object {
        fun fromCode(code: Int) = entries.getOrElse(code) { UNKNOWN }
    }
}

enum class AddressType { RANDOM, ICAO, FLARM, OGN }

/** One position report from an aircraft, as relayed by OGN. */
data class AircraftBeacon(
    /** Hex device address, upper case, 6 digits (e.g. `DD1234`). */
    val address: String,
    val addressType: AddressType,
    val aircraftType: AircraftType,
    val stealth: Boolean,
    val noTrack: Boolean,
    val lat: Double,
    val lon: Double,
    /** GPS altitude above sea level, metres. */
    val altitudeM: Double,
    /** Course over ground in degrees, null when not given. */
    val trackDeg: Double?,
    val groundSpeedKmh: Double,
    /** Vertical speed, m/s (positive = climbing). */
    val climbMs: Double?,
    /** Receiver that heard it (e.g. `LFNL`). */
    val receiver: String?,
    /** `hhmmss` UTC time from the beacon. */
    val timeHms: String,
)

/**
 * Parses OGN APRS aircraft beacons, e.g.
 * `FLRDD1234>OGFLR,qAS,LFNL:/123456h4347.95N/00346.92E'299/054/A=000892 !W28! id06DD1234 -019fpm +0.0rot`.
 *
 * Returns null for server comments, receiver beacons and anything that is not an aircraft position.
 */
object AprsParser {
    private const val FT_TO_M = 0.3048
    private const val KT_TO_KMH = 1.852
    private const val FPM_TO_MS = 0.00508

    private val position = Regex(
        """^([A-Za-z0-9-]{1,9})>([A-Za-z0-9-]+)((?:,[^:,]+)*):[/@](\d{6})h(\d{2})(\d{2}\.\d{2})([NS]).(\d{3})(\d{2}\.\d{2})([EW]).(?:(\d{3})/(\d{3}))?(?:/A=(-?\d{5,6}))?(.*)$""",
    )
    private val precision = Regex("""!W(\d)(\d)!""")
    private val id = Regex("""\bid([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})\b""")
    private val climb = Regex("""([+-]\d+)fpm""")

    fun parse(line: String): AircraftBeacon? {
        if (line.isEmpty() || line[0] == '#') return null
        val m = position.matchEntire(line.trimEnd()) ?: return null
        val g = m.groupValues
        val comment = g[14]
        val idMatch = id.find(comment) ?: return null // receivers and other stations have no id field

        val flags = idMatch.groupValues[1].toInt(16)
        val prec = precision.find(comment)
        val latExtra = prec?.groupValues?.get(1)?.toDouble()?.div(1000) ?: 0.0
        val lonExtra = prec?.groupValues?.get(2)?.toDouble()?.div(1000) ?: 0.0

        var lat = g[5].toDouble() + (g[6].toDouble() + latExtra) / 60
        if (g[7] == "S") lat = -lat
        var lon = g[8].toDouble() + (g[9].toDouble() + lonExtra) / 60
        if (g[10] == "W") lon = -lon

        val course = g[11].takeIf { it.isNotEmpty() }?.toInt()
        val speedKt = g[12].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val altFt = g[13].takeIf { it.isNotEmpty() }?.toInt() ?: return null

        return AircraftBeacon(
            address = idMatch.groupValues[2].uppercase(),
            addressType = AddressType.entries[flags and 0x03],
            aircraftType = AircraftType.fromCode((flags shr 2) and 0x0F),
            stealth = flags and 0x80 != 0,
            noTrack = flags and 0x40 != 0,
            lat = lat,
            lon = lon,
            altitudeM = altFt * FT_TO_M,
            // APRS uses 000 for "unknown" and 360 for north.
            trackDeg = course?.takeIf { it in 1..360 }?.let { (it % 360).toDouble() },
            groundSpeedKmh = speedKt * KT_TO_KMH,
            climbMs = climb.find(comment)?.groupValues?.get(1)?.toInt()?.times(FPM_TO_MS),
            receiver = g[3].split(',').lastOrNull { it.isNotEmpty() && !it.startsWith("q") },
            timeHms = g[4],
        )
    }
}
