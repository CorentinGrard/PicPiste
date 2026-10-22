package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.ddb.DdbDevice
import fr.cvvm.picpiste.core.ddb.DdbRepository
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceDatabaseTest {
    private val json = """{"devices":[
        {"device_type":"F","device_id":"39AB9B","aircraft_model":"DG-1000","registration":"F-CNDP","cn":"D12","tracked":"Y","identified":"Y"},
        {"device_type":"F","device_id":"DD0A01","aircraft_model":"ASK 21","registration":"F-CXYZ","cn":"","tracked":"Y","identified":"N"}]}"""

    private fun compact(db: DeviceDatabase) = ByteArrayOutputStream().also(db::writeCompact).toByteArray()

    @Test
    fun compactFormRoundTrips() {
        val db = DeviceDatabase.parse(json, updatedAtMs = 1234)
        val back = DeviceDatabase.readCompact(ByteArrayInputStream(compact(db)))
        assertEquals(2, back.size)
        assertEquals(1234, back.updatedAtMs)
        assertEquals("D12", back.lookup("39ab9b")!!.cn)
        assertFalse(back.lookup("DD0A01")!!.isIdentified)
        assertTrue(back.lookup("DD0A01")!!.isTracked)
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<Exception> { DeviceDatabase.readCompact(ByteArrayInputStream(json.toByteArray())) }
    }

    @Test
    fun newestLocalCopyWinsAndDownloadReplacesIt() {
        val dir = Files.createTempDirectory("ddb").toFile()
        val cache = dir.resolve("ogn-ddb.tsv.gz")
        val shipped = compact(DeviceDatabase(listOf(DdbDevice("AAAAAA", registration = "F-OLD")), updatedAtMs = 1_000))
        var now = 1_000 + DdbRepository.MAX_AGE_MS / 2
        val repo = DdbRepository(cache, bundled = { ByteArrayInputStream(shipped) }, fetch = { json }, nowMs = { now })

        // Fresh install: the shipped copy, no network needed.
        val first = repo.loadLocal()
        assertEquals("F-OLD", first.lookup("AAAAAA")!!.registration)
        assertFalse(repo.isStale(first))
        now += DdbRepository.MAX_AGE_MS
        assertTrue(repo.isStale(first))

        val downloaded = repo.download()
        assertEquals(2, downloaded.size)
        // Next start: the downloaded copy is newer than the shipped one.
        assertEquals("D12", repo.loadLocal().lookup("39AB9B")!!.cn)
        dir.deleteRecursively()
    }

    @Test
    fun failedDownloadKeepsTheCopy() {
        val dir = Files.createTempDirectory("ddb").toFile()
        val cache = dir.resolve("ogn-ddb.tsv.gz")
        DdbRepository(cache, fetch = { json }, nowMs = { 5 }).download()
        val offline = DdbRepository(cache, fetch = { error("no network") }, nowMs = { 10 })
        assertFailsWith<IllegalStateException> { offline.download() }
        assertEquals(2, offline.loadLocal().size)
        dir.deleteRecursively()
    }

    @Test
    fun nothingAtAllIsEmptyAndStale() {
        val dir = Files.createTempDirectory("ddb").toFile()
        val repo = DdbRepository(dir.resolve("none.gz"))
        assertEquals(0, repo.loadLocal().size)
        assertTrue(repo.isStale(repo.loadLocal()))
        dir.deleteRecursively()
    }

    @Test
    fun noSecondDownloadRightAfterTheFirst() {
        val dir = Files.createTempDirectory("ddb").toFile()
        val cache = dir.resolve("ogn-ddb.tsv.gz")
        var fetches = 0
        val repo = DdbRepository(cache, fetch = { fetches++; json })
        repo.download()
        assertEquals(2, repo.download().size)
        assertEquals(1, fetches)
        dir.deleteRecursively()
    }
}
