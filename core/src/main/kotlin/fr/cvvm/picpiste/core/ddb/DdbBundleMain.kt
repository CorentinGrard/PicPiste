package fr.cvvm.picpiste.core.ddb

import java.io.File
import kotlin.system.exitProcess

/**
 * Downloads the OGN device database into the copy shipped with the app:
 * `./gradlew :core:updateDdb`. CI runs it before each build; when OGN is unreachable the
 * committed copy stays.
 */
fun main(args: Array<String>) {
    val out = File(args.single())
    val db = try {
        DeviceDatabase.parse(httpGet(DdbRepository.DDB_URL, timeoutMs = 60_000), System.currentTimeMillis())
    } catch (e: Exception) {
        System.err.println("OGN device database not downloaded, keeping ${out.path}: $e")
        exitProcess(0)
    }
    if (db.size < 1000) {
        System.err.println("OGN device database suspiciously small (${db.size} devices), keeping ${out.path}")
        exitProcess(0)
    }
    out.parentFile.mkdirs()
    val tmp = File(out.path + ".tmp")
    tmp.outputStream().use(db::writeCompact)
    tmp.renameTo(out)
    println("${db.size} devices → ${out.path} (${out.length() / 1024} KB)")
}
