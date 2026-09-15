package com.feedme.development.progress

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.kitchen.SavedRecipeAvailability
import com.feedme.mealflow.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run each selector separately after acknowledged host cleanup on the runner's isolated UID.
 * A retains real open native owners after an acknowledged Save and NEVER reports a test pass.
 * The external runner must witness its live PID/checkpoint, force-stop only this owned test app,
 * prove PID disappearance, then launch B. This is process death after acknowledged commits, not
 * physical power loss, an interrupted transaction, production copy rights or provider login.
 * Marker IDs/hash/process fields are evidence only: never inputs granting session/copy authority.
 */
@RunWith(AndroidJUnit4::class)
class AndroidProgressCookbookProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner get() = (context.applicationContext as ProgressApplication).owner
    private fun <T> main(action: suspend () -> T): T = runBlocking {
        withContext(Dispatchers.Main.immediate) { action() }
    }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Acknowledged cookbook process stage required: ${result.reason}")
    }

    @Test fun stageARetainAcknowledgedSavedRecipeThenAwaitExternalProcessStop(): Unit = main {
        assertTrue(Build.VERSION.SDK_INT >= 27)
        assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
        assertEquals(ProgressHostPhase.NEW, owner.states.value.phase)
        // The preceding host suite, not missing data or an arbitrary existing identity, supplies
        // positive ownership. Unknown inventory or a live prior session is never reset/adopted.
        assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
        val directory = evidenceDirectory(create = true)
        assertTrue("Never overwrite an earlier cookbook process attempt", directory.list()!!.isEmpty())
        value(owner.inspectStartup())
        assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
        value(owner.resume())
        assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        value(owner.start())
        val experience = checkNotNull(owner.experience.value)
        value(experience.restore()); value(experience.refreshContext())
        experience.edit { it.copy(mode = MealMode.ASSEMBLE, energy = MealEnergy.ASSEMBLE, servings = "2",
            totalMinutes = "5", ingredientIds = listOf(ProgressCatalog.ingredientId), equipmentIds = listOf("bowl"),
            tasteTags = listOf("crunch")) }
        value(experience.findMeal()); value(experience.recipe())
        val selected = checkNotNull(experience.meals.states.value.plan).plan
        assertEquals("ready", selected.status)
        value(experience.saveSelectedRecipe())
        val state = experience.cookbook.states.value
        val saved = checkNotNull(state.selected)
        val savedRecipe = checkNotNull(saved.savedRecipe)
        val snapshot = (selected.recipeSnapshot as WireField.Value).value
        assertArrayEquals(snapshot.document.encodeUtf8(), savedRecipe.snapshot.document.encodeUtf8())
        assertEquals("ownPlan", savedRecipe.sourceType)
        assertEquals(SavedRecipeAvailability.AVAILABLE, saved.availability)
        assertEquals("\"1\"", saved.etag)
        assertTrue(state.serverAcknowledged); assertFalse(state.historical)
        assertFalse(state.busy); assertNull(state.pending); assertNull(state.deleteConfirmation)
        assertNull(experience.cooking.states.value.cooking) // Save is not a cooking start.
        val localRevision = checkNotNull(saved.localRevision)
        assertTrue(localRevision > 0)
        val observation = Observation(selected.id.value, saved.id, digest(savedRecipe.document.encodeUtf8()),
            localRevision, Process.myUid(), Process.myPid(), Process.getStartElapsedRealtime())
        writeNew(File(directory, "retained.json"), observation.json("retained-unclosed"))
        assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
        assertNotNull(owner.boundary.current()); assertSame(experience, owner.experience.value)
        notice("RETAINED_UNCLOSED", observation)
        // Intentionally no finally-close/reset. If the external runner never witnesses/stops us,
        // timeout fails; starting this method alone is not successful process-recovery evidence.
        withTimeout<Unit>(120_000) { awaitCancellation() }
    }

    @Test fun stageBRestoreExactSavedRecipeAndExistingServiceThenExplicitlyReset(): Unit = main {
        assertTrue(Build.VERSION.SDK_INT >= 27)
        assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
        assertEquals(ProgressHostPhase.NEW, owner.states.value.phase)
        val directory = evidenceDirectory(create = false)
        assertEquals(setOf("retained.json"), directory.list()!!.toSet())
        val marker = File(directory, "retained.json")
        val expected = readObservation(marker)
        val originalMarker = marker.readBytes()
        assertEquals(expected.uid, Process.myUid())
        assertNotEquals(expected.pid, Process.myPid())
        assertTrue(Process.getStartElapsedRealtime() > expected.startElapsed)
        assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
        value(owner.inspectStartup())
        assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        value(owner.resume())
        assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
        val experience = checkNotNull(owner.experience.value)
        value(experience.restore()) // Existing local bundle only: no Save, download or receipt ACK.
        fun exactLocalCopy() {
            val state = experience.cookbook.states.value
            val saved = checkNotNull(state.selected)
            assertEquals(expected.savedRecipeId, saved.id)
            assertEquals(expected.savedLocalRevision, checkNotNull(saved.localRevision))
            assertEquals(SavedRecipeAvailability.AVAILABLE, saved.availability)
            assertEquals("\"1\"", saved.etag)
            val body = checkNotNull(saved.savedRecipe)
            assertEquals(expected.savedContentSha256, digest(body.document.encodeUtf8()))
            val plan = checkNotNull(experience.meals.states.value.plan).plan
            assertEquals(expected.planId, plan.id.value)
            assertArrayEquals((plan.recipeSnapshot as WireField.Value).value.document.encodeUtf8(),
                body.snapshot.document.encodeUtf8())
            assertTrue(state.historical); assertFalse(state.serverAcknowledged)
            assertNull(state.pending); assertNull(state.deleteConfirmation)
            assertNull(experience.cooking.states.value.cooking)
        }
        exactLocalCopy()
        value(experience.openCookbook())
        val local = experience.cookbook.states.value
        assertTrue(local.localOnly); assertTrue(local.historical); assertFalse(local.serverAcknowledged)
        assertEquals(listOf(expected.savedRecipeId), local.items.map { it.id })
        assertEquals(expected.savedLocalRevision, checkNotNull(local.items.single().localRevision))
        value(experience.openSavedRecipe(expected.savedRecipeId)); exactLocalCopy()
        val restoredBytes = checkNotNull(experience.cookbook.states.value.selected?.savedRecipe).document.encodeUtf8()
        value(experience.backFromCookbook()); value(experience.openSavedRecipe(expected.savedRecipeId))
        exactLocalCopy()
        // The newly restored borrower has no lost-apply proof. A read of an old APPLIED receipt
        // must not fabricate acknowledgement or turn Retry into another Save with a new key.
        val retry = experience.retryCookbookOriginal()
        assertTrue(retry is PortResult.Failure)
        assertEquals(FailureReason.CONFLICT, (retry as PortResult.Failure).reason)
        exactLocalCopy()
        // resume() used service.open(false). A fresh replacement synthetic ledger cannot serve
        // this ID. An explicit GET checks retained service bytes but is never a new mutation ACK
        // or a download; reopening local proves its original acknowledged revision survived.
        value(experience.refreshSavedRecipe())
        val remote = experience.cookbook.states.value
        assertFalse(remote.historical); assertFalse(remote.serverAcknowledged); assertNull(remote.pending)
        assertEquals(expected.savedRecipeId, remote.selected!!.id)
        assertNull(remote.selected!!.localRevision)
        assertArrayEquals(restoredBytes, remote.selected!!.savedRecipe!!.document.encodeUtf8())
        value(experience.openSavedRecipe(expected.savedRecipeId)); exactLocalCopy()
        assertArrayEquals(originalMarker, marker.readBytes())
        assertEquals(expected, readObservation(marker))
        owner.requestReset()
        val consent = checkNotNull(owner.states.value.confirmation)
        assertEquals(ProgressConfirmationKind.RESET, consent.kind)
        value(owner.confirmReset(consent))
        assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        assertNull(experience.forms.value.values)
        assertNull(experience.cookbook.states.value.selected)
        assertTrue(experience.cookbook.states.value.items.isEmpty())
        assertNull(experience.cookbook.states.value.pending)
        assertFalse(experience.cookbook.states.value.serverAcknowledged)
        // Inspect actual retained terminal control; file absence never substitutes for SIGNED_OUT.
        value(owner.inspectStartup())
        assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
        value(owner.resume())
        assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        value(owner.close())
        assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
        assertArrayEquals(originalMarker, marker.readBytes())
        assertEquals(expected, readObservation(marker))
        val restored = expected.copy(pid = Process.myPid(), startElapsed = Process.getStartElapsedRealtime())
        writeNew(File(directory, "restored-and-reset.json"), restored.json("restored-and-reset"))
        assertEquals(setOf("retained.json", "restored-and-reset.json"), directory.list()!!.toSet())
        notice("RESTORED_AND_RESET", restored)
    }

    private data class Observation(val planId: String, val savedRecipeId: String, val savedContentSha256: String,
        val savedLocalRevision: Long, val uid: Int, val pid: Int, val startElapsed: Long) {
        fun json(stage: String) = buildJsonObject {
            put("version", 1); put("stage", stage); put("planId", planId); put("savedRecipeId", savedRecipeId)
            put("savedContentSha256", savedContentSha256); put("savedLocalRevision", savedLocalRevision)
            put("uid", uid); put("pid", pid); put("startElapsedRealtime", startElapsed)
        }
    }
    private fun notice(stage: String, observation: Observation) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString("feedme_cookbook_stage", stage)
            putString("feedme_cookbook_pid", observation.pid.toString())
            putString("feedme_cookbook_uid", observation.uid.toString())
            putString("feedme_cookbook_start_elapsed", observation.startElapsed.toString())
        })
    }
    private fun evidenceDirectory(create: Boolean): File {
        check(context.packageName == ProgressNativeInventory.PACKAGE)
        val base = context.cacheDir
        val stat = Os.lstat(base.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
        val directory = File(base.canonicalFile, "progress-cookbook-process-evidence")
        if (create) { check(!directory.exists()); check(directory.mkdir()); Os.chmod(directory.path, 448) }
        val checked = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(checked.st_mode) && checked.st_uid == Process.myUid() && (checked.st_mode and 511) == 448)
        return directory
    }
    private fun writeNew(file: File, document: JsonObject) {
        check(!file.exists() && file.createNewFile()); Os.chmod(file.path, 384)
        val bytes = document.toString().encodeToByteArray(); check(bytes.size <= 512)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun readObservation(file: File): Observation {
        val stat = Os.lstat(file.path)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_nlink == 1L &&
            (stat.st_mode and 511) == 384 && stat.st_size in 1..512)
        val raw = file.readBytes()
        val document = Json.parseToJsonElement(raw.decodeToString()).jsonObject
        check(document.keys == setOf("version", "stage", "planId", "savedRecipeId", "savedContentSha256",
            "savedLocalRevision", "uid", "pid", "startElapsedRealtime"))
        check(document.getValue("version") == JsonPrimitive(1))
        check(document.getValue("stage") == JsonPrimitive("retained-unclosed"))
        val result = Observation(document.getValue("planId").jsonPrimitive.content,
            document.getValue("savedRecipeId").jsonPrimitive.content,
            document.getValue("savedContentSha256").jsonPrimitive.content,
            document.getValue("savedLocalRevision").jsonPrimitive.long,
            document.getValue("uid").jsonPrimitive.int, document.getValue("pid").jsonPrimitive.int,
            document.getValue("startElapsedRealtime").jsonPrimitive.long)
        check(UUID_PATTERN.matches(result.planId) && UUID_PATTERN.matches(result.savedRecipeId))
        check(HASH_PATTERN.matches(result.savedContentSha256) && result.savedLocalRevision > 0)
        check(result.uid == Process.myUid() && result.pid > 0 && result.startElapsed >= 0)
        check(raw.contentEquals(result.json("retained-unclosed").toString().encodeToByteArray()))
        val after = Os.lstat(file.path)
        check(after.st_dev == stat.st_dev && after.st_ino == stat.st_ino && after.st_size == stat.st_size &&
            after.st_uid == stat.st_uid && after.st_mode == stat.st_mode && after.st_nlink == 1L)
        return result
    }
    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
