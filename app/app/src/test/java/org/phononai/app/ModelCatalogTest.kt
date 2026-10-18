package org.phononai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog now ships as `assets/catalog.json`, so the invariants the picker
 * relies on are checked here rather than at runtime on a phone: the document
 * parses, ids are unique, and every entry is both sized and hashed. A catalog
 * that fails any of these is a build defect — the download screen cannot verify
 * a file without a hash, and cannot show a size it does not have.
 *
 * These run on the JVM: the parser [ModelCatalog.loadFrom] takes a string, so
 * nothing here needs Android.
 */
class ModelCatalogTest {

    private val json: String by lazy {
        val candidates = listOf(
            "src/main/assets/catalog.json",        // Gradle unit tests run in the module dir
            "app/app/src/main/assets/catalog.json", // repository root, just in case
        )
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("catalog.json not found from ${File(".").absolutePath}; looked in $candidates")
        file.readText()
    }

    private fun loaded(): List<ModelEntry> {
        ModelCatalog.loadFrom(json)
        return ModelCatalog.entries
    }

    @Test
    fun catalogParsesAndIsNotEmpty() {
        val entries = loaded()
        assertTrue("catalog parsed but is empty", entries.isNotEmpty())
    }

    @Test
    fun idsAreUnique() {
        val entries = loaded()
        val ids = entries.map { it.id }
        assertEquals("duplicate ids in catalog: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun everyEntryHasASizeAndASha256() {
        for (entry in loaded()) {
            assertTrue("${entry.id}: sizeBytes must be positive", entry.sizeBytes > 0)
            assertEquals(
                "${entry.id}: sha256 must be 64 hex characters",
                64,
                entry.sha256.length,
            )
            assertTrue(
                "${entry.id}: sha256 must be hex",
                entry.sha256.all { it in "0123456789abcdef" },
            )
        }
    }

    @Test
    fun defaultIdIsInTheCatalog() {
        val entries = loaded()
        assertNotNull(
            "default_id ${ModelCatalog.DEFAULT_ID} names no entry",
            entries.firstOrNull { it.id == ModelCatalog.DEFAULT_ID },
        )
    }

    @Test
    fun everyEntryNamesAKnownFamily() {
        // loadFrom throws on an unknown family key; this asserts the parse
        // reached every entry rather than stopping at the first.
        val entries = loaded()
        for (entry in entries) {
            assertNotNull("${entry.id}: family is null", entry.family)
        }
    }

    @Test
    fun multimodalEntriesCarryAProjector() {
        // A multimodal entry with no projector is a catalog mistake: the app
        // tells the operator images will work and then starts the server
        // without --mmproj.
        for (entry in loaded()) {
            if (entry.multimodal) {
                assertTrue(
                    "${entry.id}: multimodal but no mmprojFileName",
                    entry.mmprojFileName.isNotBlank(),
                )
                assertTrue(
                    "${entry.id}: multimodal but no mmprojSha256",
                    entry.mmprojSha256.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun unmeasuredEntriesSaySoRatherThanGuessing() {
        for (entry in loaded()) {
            if (entry.decodeRuns.isEmpty()) {
                assertTrue("${entry.id}: unmeasured but claims a device", entry.measuredOn.isBlank())
                assertEquals("${entry.id}: unmeasured but has a decode label", "", entry.decodeLabel)
            }
        }
    }
}
