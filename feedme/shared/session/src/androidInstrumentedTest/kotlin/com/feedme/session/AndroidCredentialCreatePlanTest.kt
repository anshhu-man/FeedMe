package com.feedme.session

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Keystore/files, exact private test namespaces, controlled interruption rather than process death. */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialCreatePlanTest {
    private val sandboxes = mutableListOf<AndroidCredentialTestSandbox>()
    private val stores = mutableListOf<AndroidCredentialStore>()
    private val recoveries = mutableListOf<CredentialCreateRecoveryHandle>()

    @After fun tearDown() = runBlocking {
        try {
            recoveries.asReversed().forEach { it.close().credentialValue() }
            stores.asReversed().forEach { it.close().credentialValue() }
        } finally {
            recoveries.clear(); stores.clear()
            sandboxes.asReversed().forEach(AndroidCredentialTestSandbox::close)
            sandboxes.clear()
        }
    }

    @Test fun planningUsesOnlyExistingInstallKeysAndDoesNotWriteCredentialMaterial() = runBlocking {
        for (credentials in listOf(account(), guest())) {
            val box = sandbox(); val store = open(box)
            val before = files(box); val aliases = box.aliases()
            val first = store.planCreate(1, credentials).credentialValue()
            val second = store.planCreate(1, credentials).credentialValue()
            val record = decode(first)
            assertEquals(1L, record.expectedSlotRevision)
            assertNotEquals(record.incarnation, decode(second).incarnation)
            assertNotEquals(record.target, decode(second).target)
            assertEquals(aliases, box.aliases()); assertFiles(before, files(box))
            assertTrue(box.credentialAliases().isEmpty())
            assertEquals(1L, store.state().credentialValue().revision)
            val raw = first.copyForStorage().copyForCodec().decodeToString()
            for (secret in listOf(ACCOUNT.actorId, ACCESS, REFRESH, GUEST_TOKEN, DEVICE)) assertFalse(raw.contains(secret))
            assertEquals("CredentialCreatePlan(<redacted>)", first.toString())
            close(store)
            val recovery = recovery(box, first)
            assertEquals(CredentialCreateRecoveryStatus.PREPARED, recovery.inspect().credentialValue())
            assertEquals(aliases, box.aliases()); assertFiles(before, files(box))
            close(recovery)
        }
    }

    @Test fun committedAccountAndGuestReplayExactSnapshotAcrossCloseWithoutNewKeyOrWrite() = runBlocking {
        for (credentials in listOf(account(), guest())) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, credentials).credentialValue()
            val created = store.commitPlannedCreate(plan, credentials).credentialValue()
            assertEquals(decode(plan).incarnation, created.incarnation)
            assertEquals(2L, created.revision)
            assertCredentials(credentials, created.credentials)
            assertEquals(1, box.credentialAliases().size)
            for (alias in box.aliases()) assertNull(box.keyStore().getKey(alias, null).encoded)
            val before = files(box); val aliases = box.aliases()
            close(store)
            val reopened = open(box)
            val replay = reopened.commitPlannedCreate(plan, credentials).credentialValue()
            assertEquals(created.incarnation, replay.incarnation)
            assertCredentials(credentials, replay.credentials)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            close(reopened)
            val handle = recovery(box, plan)
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, handle.inspect().credentialValue())
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            close(handle)
            for ((_, bytes) in before) for (secret in listOf(ACCESS, REFRESH, GUEST_TOKEN, ACCOUNT.actorId))
                assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(secret))
        }
    }

    @Test fun payloadChangesAndStructurallyValidForgedPlansCannotWriteOrDelete() = runBlocking {
        val box = sandbox(); val store = open(box)
        val original = account(); val plan = store.planCreate(1, original).credentialValue()
        val before = files(box); val aliases = box.aliases(); val record = decode(plan)
        for (changed in listOf(account(access = "changed-access"), account(refresh = "changed-refresh"),
            original.copy(expiresAtMillis = 8), original.copy(deviceSessionId = null),
            original.copy(scope = ACCOUNT.copy(actorId = "another-owner")), guest()))
            failure(store.commitPlannedCreate(plan, changed), FailureReason.INVALID_DATA)
        val altered = listOf(record.copy(expectedSlotRevision = 2), record.copy(incarnation = OTHER_ID),
            record.copy(target = flip(record.target)), record.copy(payloadMac = flip(record.payloadMac)),
            record.copy(authenticationMac = flip(record.authenticationMac)))
        for (candidate in altered) {
            val forged = CredentialCreatePlan.fromStorage(CredentialCreatePlanCodec.encode(candidate)).credentialValue()
            failure(store.commitPlannedCreate(forged, original), FailureReason.INVALID_DATA)
        }
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        close(store)
        for (candidate in altered) {
            val forged = CredentialCreatePlan.create(candidate)
            failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, forged), FailureReason.INVALID_DATA)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
        val foreignBox = sandbox(); val foreign = open(foreignBox)
        val foreignBefore = files(foreignBox); val foreignAliases = foreignBox.aliases()
        failure(foreign.commitPlannedCreate(plan, original), FailureReason.INVALID_DATA)
        close(foreign)
        failure(AndroidCredentialStore.openCreateRecoveryForTests(foreignBox.directory, foreignBox.keyPrefix, plan), FailureReason.INVALID_DATA)
        assertFiles(foreignBefore, files(foreignBox)); assertEquals(foreignAliases, foreignBox.aliases())
    }

    @Test fun invalidPlanningAndCompetingCreatesPreserveTheWinningSelection() = runBlocking {
        val box = sandbox(); val store = open(box)
        val before = files(box); val aliases = box.aliases()
        for (revision in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE - 1, Long.MAX_VALUE))
            failure(store.planCreate(revision, account()), FailureReason.INVALID_DATA)
        failure(store.planCreate(2, account()), FailureReason.CONFLICT)
        failure(store.planCreate(1, account().copy(deviceSessionId = SecretText("not-a-device-uuid"))), FailureReason.INVALID_DATA)
        failure(store.planCreate(1, account(access = "bad-${0xD800.toChar()}")), FailureReason.INVALID_DATA)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        val plans = List(2) { store.planCreate(1, account()).credentialValue() }
        val results = plans.map { plan -> async { store.commitPlannedCreate(plan, account()) } }.awaitAll()
        assertEquals(1, results.count { it is PortResult.Value })
        assertEquals(1, results.count { it is PortResult.Failure && it.reason == FailureReason.CONFLICT })
        val winner = results.filterIsInstance<PortResult.Value<CredentialSnapshot>>().single().value
        val selectedFiles = files(box); val selectedAliases = box.aliases()
        failure(store.planCreate(2, account()), FailureReason.CONFLICT)
        close(store)
        val losingPlan = plans.single { decode(it).incarnation != winner.incarnation }
        failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, losingPlan), FailureReason.CONFLICT)
        assertFiles(selectedFiles, files(box)); assertEquals(selectedAliases, box.aliases())
    }

    @Test fun everyCreateInterruptionRetainsExactPlanArtifactsUntilExplicitAbort() = runBlocking {
        for (point in listOf(CredentialFileFaultPoint.AFTER_KEY_CREATE, CredentialFileFaultPoint.AFTER_BLOB_TEMP_CREATE,
            CredentialFileFaultPoint.AFTER_BLOB_TEMP_FORCE, CredentialFileFaultPoint.AFTER_BLOB_RENAME,
            CredentialFileFaultPoint.AFTER_CREATE_MANIFEST_TEMP_FORCE, CredentialFileFaultPoint.AFTER_MANIFEST_RENAME)) {
            val box = sandbox(); val plan = prepared(box)
            val store = open(box, failAt(point))
            failure(store.commitPlannedCreate(plan, account()), if (point == CredentialFileFaultPoint.AFTER_BLOB_RENAME ||
                point == CredentialFileFaultPoint.AFTER_MANIFEST_RENAME) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            failure(store.state(), FailureReason.STORAGE_FAILURE)
            close(store)
            val before = files(box); val aliases = box.aliases()
            assertEquals(setOf(alias(box, plan)), box.credentialAliases())
            val record = decode(plan)
            val expectedNames = buildSet {
                add("credentials.lock"); add("manifest.bin")
                when (point) {
                    CredentialFileFaultPoint.AFTER_BLOB_TEMP_CREATE, CredentialFileFaultPoint.AFTER_BLOB_TEMP_FORCE -> add(blob(plan) + ".pending")
                    CredentialFileFaultPoint.AFTER_BLOB_RENAME, CredentialFileFaultPoint.AFTER_MANIFEST_RENAME -> add(blob(plan))
                    CredentialFileFaultPoint.AFTER_CREATE_MANIFEST_TEMP_FORCE -> {
                        add(blob(plan)); add(AndroidCredentialFiles.createManifestPending(record.incarnation))
                    }
                    else -> Unit
                }
            }
            assertEquals(expectedNames, before.keys)
            if (point == CredentialFileFaultPoint.AFTER_BLOB_TEMP_CREATE) assertEquals(0L, File(box.directory, blob(plan) + ".pending").length())
            if (point != CredentialFileFaultPoint.AFTER_MANIFEST_RENAME)
                failure(AndroidCredentialStore.openForTests(box.directory, box.keyPrefix), FailureReason.STORAGE_FAILURE)
            val handle = recovery(box, plan)
            assertEquals(if (point == CredentialFileFaultPoint.AFTER_MANIFEST_RENAME) CredentialCreateRecoveryStatus.SELECTED
                else CredentialCreateRecoveryStatus.PARTIAL, handle.inspect().credentialValue())
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            handle.abort().credentialValue(); assertEquals(CredentialCreateRecoveryStatus.ABORTED, handle.inspect().credentialValue())
            close(handle); assertAborted(box, plan)
        }
    }

    @Test fun interruptedUnselectedAbortConsumesRevisionBeforeErasingItsIncarnationKey() = runBlocking {
        for (point in listOf(CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE,
            CredentialFileFaultPoint.AFTER_MANIFEST_RENAME, CredentialFileFaultPoint.AFTER_ABORT_CONSUMED,
            CredentialFileFaultPoint.AFTER_KEY_DELETE, CredentialFileFaultPoint.AFTER_BLOB_DELETE)) {
            val box = sandbox(); val plan = interrupted(box, CredentialFileFaultPoint.AFTER_BLOB_RENAME)
            val oldManifest = File(box.directory, "manifest.bin").readBytes()
            val handle = recovery(box, plan, failAt(point))
            failure(handle.abort(), if (point == CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE)
                FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN)
            failure(handle.inspect(), FailureReason.STORAGE_FAILURE); close(handle)
            if (point == CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE)
                assertArrayEquals(oldManifest, File(box.directory, "manifest.bin").readBytes())
            else assertFalse(oldManifest.contentEquals(File(box.directory, "manifest.bin").readBytes()))
            if (point in listOf(CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE,
                    CredentialFileFaultPoint.AFTER_MANIFEST_RENAME, CredentialFileFaultPoint.AFTER_ABORT_CONSUMED))
                assertTrue(box.credentialAliases().contains(alias(box, plan)))
            else assertTrue(box.credentialAliases().isEmpty())
            val retry = recovery(box, plan)
            assertEquals(if (point == CredentialFileFaultPoint.AFTER_BLOB_DELETE) CredentialCreateRecoveryStatus.ABORTED
                else CredentialCreateRecoveryStatus.ABORTING, retry.inspect().credentialValue())
            retry.abort().credentialValue(); close(retry); assertAborted(box, plan)
        }
    }

    @Test fun selectedAbortDeletesKeyFirstAndResumesWithoutReadingLostSelectedSecrets() = runBlocking {
        for (point in listOf(CredentialFileFaultPoint.AFTER_KEY_DELETE, CredentialFileFaultPoint.AFTER_BLOB_DELETE,
            CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE, CredentialFileFaultPoint.AFTER_MANIFEST_RENAME)) {
            val box = sandbox(); val plan = selected(box)
            val selectedManifest = File(box.directory, "manifest.bin").readBytes()
            val handle = recovery(box, plan, failAt(point))
            failure(handle.abort(), if (point == CredentialFileFaultPoint.AFTER_KEY_DELETE ||
                point == CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN)
            assertTrue(box.credentialAliases().isEmpty()); close(handle)
            if (point != CredentialFileFaultPoint.AFTER_MANIFEST_RENAME)
                assertArrayEquals(selectedManifest, File(box.directory, "manifest.bin").readBytes())
            val retry = recovery(box, plan)
            assertEquals(if (point == CredentialFileFaultPoint.AFTER_MANIFEST_RENAME) CredentialCreateRecoveryStatus.ABORTED
                else CredentialCreateRecoveryStatus.SELECTED, retry.inspect().credentialValue())
            retry.abort().credentialValue(); close(retry); assertAborted(box, plan)
        }
    }

    @Test fun selectedReplayAndAbortRequireDurabilityAcknowledgmentBeforeSuccessOrDeletion() = runBlocking {
        for (point in listOf(CredentialFileFaultPoint.BEFORE_DURABILITY_SYNC, CredentialFileFaultPoint.AFTER_DURABILITY_SYNC)) {
            val box = sandbox(); val plan = interrupted(box, CredentialFileFaultPoint.AFTER_MANIFEST_RENAME)
            val before = files(box); val aliases = box.aliases()
            val store = open(box, failAt(point))
            failure(store.commitPlannedCreate(plan, account()), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases()); close(store)
            val handle = recovery(box, plan, failAt(point))
            failure(handle.abort(), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases()); close(handle)
            val retry = recovery(box, plan); retry.abort().credentialValue(); close(retry); assertAborted(box, plan)
        }
    }

    @Test fun consumedEmptyReplayMustReacknowledgeDurabilityBeforeRemainingKeyDeletion() = runBlocking {
        val box = sandbox(); val plan = interrupted(box, CredentialFileFaultPoint.AFTER_BLOB_RENAME)
        val first = recovery(box, plan, failAt(CredentialFileFaultPoint.AFTER_ABORT_CONSUMED))
        failure(first.abort(), FailureReason.OUTCOME_UNKNOWN); close(first)
        val before = files(box); val aliases = box.aliases()
        val retry = recovery(box, plan, failAt(CredentialFileFaultPoint.BEFORE_DURABILITY_SYNC))
        assertEquals(CredentialCreateRecoveryStatus.ABORTING, retry.inspect().credentialValue())
        failure(retry.abort(), FailureReason.STORAGE_FAILURE)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases()); close(retry)
        val final = recovery(box, plan); final.abort().credentialValue(); close(final); assertAborted(box, plan)
    }

    @Test fun exactOwnedZeroLengthOrCorruptPartialCiphertextCanBeAbortedWithoutDecryption() = runBlocking {
        for (point in listOf(CredentialFileFaultPoint.AFTER_BLOB_TEMP_CREATE, CredentialFileFaultPoint.AFTER_BLOB_TEMP_FORCE,
            CredentialFileFaultPoint.AFTER_BLOB_RENAME, CredentialFileFaultPoint.AFTER_CREATE_MANIFEST_TEMP_FORCE)) {
            for (damage in listOf(byteArrayOf(), ByteArray(29) { 0x5a })) {
                val box = sandbox(); val plan = interrupted(box, point)
                for (name in files(box).keys - setOf("manifest.bin", "credentials.lock")) writePrivate(File(box.directory, name), damage)
                val handle = recovery(box, plan)
                assertEquals(CredentialCreateRecoveryStatus.PARTIAL, handle.inspect().credentialValue())
                handle.abort().credentialValue(); close(handle); assertAborted(box, plan)
            }
        }
    }

    @Test fun selectedMissingOrCorruptMaterialNeverRegeneratesAKeyDuringExactReplay() = runBlocking {
        for (damage in listOf("key", "blob", "corrupt", "empty")) {
            val box = sandbox(); val plan = selected(box)
            when (damage) {
                "key" -> box.keyStore().deleteEntry(alias(box, plan))
                "blob" -> assertTrue(File(box.directory, blob(plan)).delete())
                "corrupt" -> writePrivate(File(box.directory, blob(plan)), ByteArray(29))
                "empty" -> writePrivate(File(box.directory, blob(plan)), byteArrayOf())
            }
            val before = files(box); val aliases = box.aliases()
            val store = open(box)
            failure(store.commitPlannedCreate(plan, account()), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases()); close(store)
            val handle = recovery(box, plan)
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, handle.inspect().credentialValue())
            handle.abort().credentialValue(); close(handle); assertAborted(box, plan)
        }
    }

    @Test fun unknownArtifactsAndOtherIncarnationKeysAreNeverAdoptedOrErasedByAPlan() = runBlocking {
        for (damage in listOf("file", "pending", "key")) {
            val box = sandbox(); val plan = interrupted(box, CredentialFileFaultPoint.AFTER_KEY_CREATE)
            when (damage) {
                "file" -> writePrivate(File(box.directory, "f".repeat(64) + ".9.bin"), ByteArray(29))
                "pending" -> writePrivate(File(box.directory, "manifest.create.$OTHER_ID.pending"), byteArrayOf())
                "key" -> AndroidCredentialVault.open(box.keyPrefix, initialize = false).createIncarnationKey("f".repeat(64))
            }
            val before = files(box); val aliases = box.aliases()
            failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, plan), FailureReason.STORAGE_FAILURE)
            failure(AndroidCredentialStore.openForTests(box.directory, box.keyPrefix), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun unsafeModesAndSymlinkTargetsFailClosedWithoutFollowingOrDeletingForeignData() = runBlocking {
        for (damage in listOf("file-mode", "directory-mode", "symlink")) {
            val box = sandbox(); val plan = interrupted(box, CredentialFileFaultPoint.AFTER_BLOB_RENAME)
            val owned = File(box.directory, blob(plan)); val original = owned.readBytes()
            val foreignBox = sandbox(); open(foreignBox).let { close(it) }
            val foreign = File(foreignBox.directory, "manifest.bin"); val foreignBytes = foreign.readBytes()
            when (damage) {
                "file-mode" -> Os.chmod(owned.path, 420)
                "directory-mode" -> Os.chmod(box.directory.path, 493)
                "symlink" -> { assertTrue(owned.delete()); Files.createSymbolicLink(owned.toPath(), foreign.toPath()) }
            }
            val aliases = box.aliases()
            failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, plan), FailureReason.STORAGE_FAILURE)
            assertEquals(aliases, box.aliases()); assertArrayEquals(foreignBytes, foreign.readBytes())
            when (damage) {
                "file-mode" -> Os.chmod(owned.path, 384)
                "directory-mode" -> Os.chmod(box.directory.path, 448)
                "symlink" -> { Files.delete(owned.toPath()); writePrivate(owned, original) }
            }
            val handle = recovery(box, plan); handle.abort().credentialValue(); close(handle); assertAborted(box, plan)
        }
    }

    @Test fun recoveryIsExistingOnlyAndNeverInitializesMissingLockManifestOrInstallKeys() = runBlocking {
        for (damage in listOf("absent", "lock", "manifest", "index", "manifest-key", "corrupt-manifest")) {
            val box = sandbox(); val plan = prepared(box)
            when (damage) {
                "absent" -> assertTrue(box.directory.deleteRecursively())
                "lock" -> assertTrue(File(box.directory, "credentials.lock").delete())
                "manifest" -> assertTrue(File(box.directory, "manifest.bin").delete())
                "index" -> box.keyStore().deleteEntry(box.aliases().single { it.endsWith(".index") })
                "manifest-key" -> box.keyStore().deleteEntry(box.aliases().single { it.endsWith(".manifest") })
                "corrupt-manifest" -> writePrivate(File(box.directory, "manifest.bin"), ByteArray(29))
            }
            val before = files(box); val aliases = box.aliases(); val existed = box.directory.exists()
            failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, plan), FailureReason.STORAGE_FAILURE)
            assertEquals(existed, box.directory.exists()); assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun refreshedOrNewerIncarnationCannotBeReadOrErasedThroughAnOldCreatePlan() = runBlocking {
        for (newer in listOf("refresh", "new-incarnation")) {
            val box = sandbox(); val store = open(box)
            val oldPlan = store.planCreate(1, account()).credentialValue()
            val created = store.commitPlannedCreate(oldPlan, account()).credentialValue()
            val current = if (newer == "refresh") store.replace(created, account(access = "newer-access")).credentialValue()
                else {
                    store.retire(ACCOUNT, created.incarnation).credentialValue()
                    val next = store.planCreate(3, account(access = "newer-access")).credentialValue()
                    store.commitPlannedCreate(next, account(access = "newer-access")).credentialValue()
                }
            val before = files(box); val aliases = box.aliases()
            failure(store.commitPlannedCreate(oldPlan, account()), FailureReason.CONFLICT); close(store)
            failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, oldPlan), FailureReason.CONFLICT)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            val reopened = open(box); assertEquals(current.incarnation, reopened.read(ACCOUNT).credentialValue()!!.incarnation)
            assertCredentials(current.credentials, reopened.read(ACCOUNT).credentialValue()!!.credentials); close(reopened)
        }
    }

    @Test fun abortingPreparedPlanConsumesItPermanentlyAndRepeatedAbortDoesNotAdvanceRevision() = runBlocking {
        val box = sandbox(); val plan = prepared(box)
        val handle = recovery(box, plan)
        handle.abort().credentialValue(); val after = files(box); val aliases = box.aliases()
        repeat(3) {
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, handle.inspect().credentialValue())
            handle.abort().credentialValue(); assertFiles(after, files(box)); assertEquals(aliases, box.aliases())
        }
        close(handle); assertAborted(box, plan)
        val store = open(box); val next = store.planCreate(3, account()).credentialValue()
        val selected = store.commitPlannedCreate(next, account()).credentialValue()
        assertEquals(4L, selected.revision); assertNotEquals(decode(plan).incarnation, selected.incarnation)
        failure(store.commitPlannedCreate(plan, account()), FailureReason.CONFLICT); close(store)
        failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, plan), FailureReason.CONFLICT)
    }

    @Test fun recoveryHandlesAreExclusiveReadOnlyToInspectAndReleaseOwnedLockDescriptors() = runBlocking {
        val box = sandbox(); val plan = prepared(box)
        val before = files(box); val aliases = box.aliases()
        assertEquals(0, lockDescriptors(box))
        repeat(3) {
            val handle = recovery(box, plan)
            assertEquals(1, lockDescriptors(box))
            repeat(3) { assertEquals(CredentialCreateRecoveryStatus.PREPARED, handle.inspect().credentialValue()) }
            failure(AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, plan), FailureReason.STORAGE_FAILURE)
            failure(AndroidCredentialStore.openForTests(box.directory, box.keyPrefix), FailureReason.STORAGE_FAILURE)
            assertEquals(1, lockDescriptors(box)); assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            assertEquals("CredentialCreateRecoveryHandle(<redacted>)", handle.toString())
            close(handle); handle.close().credentialValue()
            failure(handle.inspect(), FailureReason.STORAGE_FAILURE); failure(handle.abort(), FailureReason.STORAGE_FAILURE)
            assertEquals(0, lockDescriptors(box))
        }
    }

    @Test fun cancellationAtNativeWriteAndAbortCheckpointsPreservesExactRecoverableIntent() = runBlocking {
        val box = sandbox(); val plan = prepared(box)
        val store = open(box) { point -> if (point == CredentialFileFaultPoint.AFTER_BLOB_TEMP_CREATE) throw CancellationException("test-only-cancel") }
        cancelled { store.commitPlannedCreate(plan, account()) }
        failure(store.state(), FailureReason.STORAGE_FAILURE); close(store)
        assertEquals(0, lockDescriptors(box)); assertEquals(0L, File(box.directory, blob(plan) + ".pending").length())
        val handle = recovery(box, plan) { point -> if (point == CredentialFileFaultPoint.AFTER_ABORT_CONSUMED) throw CancellationException("test-only-cancel") }
        cancelled { handle.abort() }
        failure(handle.inspect(), FailureReason.STORAGE_FAILURE); close(handle)
        assertEquals(0, lockDescriptors(box)); assertEquals(setOf(alias(box, plan)), box.credentialAliases())
        val retry = recovery(box, plan)
        assertEquals(CredentialCreateRecoveryStatus.ABORTING, retry.inspect().credentialValue())
        retry.abort().credentialValue(); close(retry); assertAborted(box, plan)
    }

    private fun sandbox() = AndroidCredentialTestSandbox().also(sandboxes::add)
    private suspend fun open(box: AndroidCredentialTestSandbox, fault: (CredentialFileFaultPoint) -> Unit = {}) =
        AndroidCredentialStore.openForTests(box.directory, box.keyPrefix, fault).credentialValue().also(stores::add)
    private suspend fun recovery(box: AndroidCredentialTestSandbox, plan: CredentialCreatePlan, fault: (CredentialFileFaultPoint) -> Unit = {}) =
        AndroidCredentialStore.openCreateRecoveryForTests(box.directory, box.keyPrefix, plan, fault).credentialValue().also(recoveries::add)
    private suspend fun close(store: AndroidCredentialStore) { store.close().credentialValue(); stores.remove(store) }
    private suspend fun close(handle: CredentialCreateRecoveryHandle) { handle.close().credentialValue(); recoveries.remove(handle) }
    private suspend fun prepared(box: AndroidCredentialTestSandbox): CredentialCreatePlan {
        val store = open(box); val plan = store.planCreate(1, account()).credentialValue(); close(store); return plan
    }
    private suspend fun selected(box: AndroidCredentialTestSandbox): CredentialCreatePlan {
        val store = open(box); val plan = store.planCreate(1, account()).credentialValue()
        store.commitPlannedCreate(plan, account()).credentialValue(); close(store); return plan
    }
    private suspend fun interrupted(box: AndroidCredentialTestSandbox, point: CredentialFileFaultPoint): CredentialCreatePlan {
        val plan = prepared(box); val store = open(box, failAt(point))
        failure(store.commitPlannedCreate(plan, account())); close(store); return plan
    }
    private suspend fun assertAborted(box: AndroidCredentialTestSandbox, plan: CredentialCreatePlan) {
        assertTrue(box.credentialAliases().isEmpty())
        assertEquals(setOf("credentials.lock", "manifest.bin"), files(box).keys)
        val store = open(box); val state = store.state().credentialValue()
        assertEquals(decode(plan).abortedRevision, state.revision); assertNull(state.owner); assertNull(state.incarnation)
        assertNull(store.read(ACCOUNT).credentialValue())
        val before = files(box); val aliases = box.aliases()
        failure(store.commitPlannedCreate(plan, account()), FailureReason.CONFLICT)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases()); close(store)
    }
    private fun decode(plan: CredentialCreatePlan) = CredentialCreatePlanCodec.decode(plan.copyForStorage())
    private fun blob(plan: CredentialCreatePlan) = decode(plan).let { AndroidCredentialFiles.blobName(it.target, it.snapshotRevision) }
    private fun alias(box: AndroidCredentialTestSandbox, plan: CredentialCreatePlan) = "${box.keyPrefix}.credential.${decode(plan).target}"
    private fun flip(value: String) = (if (value.first() == '0') "1" else "0") + value.drop(1)
    private fun failAt(point: CredentialFileFaultPoint): (CredentialFileFaultPoint) -> Unit = { if (it == point) throw IOException("private-fault-canary") }
    private fun files(box: AndroidCredentialTestSandbox): Map<String, ByteArray> =
        box.directory.listFiles()?.associate {
            // A second fd closed for a POSIX lock inode can release the process's lifetime lock.
            // Its fixed empty contents are proved by stat without opening another descriptor.
            it.name to if (it.name == "credentials.lock") {
                assertEquals(0L, it.length()); byteArrayOf()
            } else it.readBytes()
        } ?: emptyMap()
    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys); expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }
    private fun writePrivate(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun failure(result: PortResult<*>, reason: FailureReason? = null) {
        assertTrue("Expected a sanitized typed failure", result is PortResult.Failure)
        val actual = result as PortResult.Failure
        if (reason != null) assertEquals(reason, actual.reason)
        assertFalse(actual.toString().contains("private-fault-canary"))
    }
    private suspend fun cancelled(block: suspend () -> Any?) {
        var caught = false
        try { block() } catch (_: CancellationException) { caught = true }
        assertTrue("Cancellation must propagate", caught)
    }
    private fun lockDescriptors(box: AndroidCredentialTestSandbox): Int {
        // Android's context path can use /data/user/0 while the descriptor resolves /data/data.
        // Match the exact canonical owned inode path, as the native factory and baseline test do.
        val exactPath = File(box.directory.canonicalFile, "credentials.lock").path
        return checkNotNull(File("/proc/self/fd").listFiles()).count {
            runCatching { Os.readlink(it.path) == exactPath }.getOrDefault(false)
        }
    }
    private fun assertCredentials(expected: StoredCredentials, actual: StoredCredentials) {
        assertArrayEquals(CredentialCodec.encodeSnapshot(CredentialSnapshot(OTHER_ID, 2, expected)).copyForCodec(),
            CredentialCodec.encodeSnapshot(CredentialSnapshot(OTHER_ID, 2, actual)).copyForCodec())
    }

    companion object {
        private val ACCOUNT = StorageScope("native-create-test", ActorKind.ACCOUNT, "private-create-account")
        private val GUEST = StorageScope("native-create-test", ActorKind.GUEST, "private-create-guest")
        private const val ACCESS = "private-planned-access"
        private const val REFRESH = "private-planned-refresh"
        private const val GUEST_TOKEN = "private-planned-guest-token"
        private const val DEVICE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val OTHER_ID = "11111111-2222-4333-8444-555555555555"
        private fun account(access: String = ACCESS, refresh: String = REFRESH) = StoredCredentials.Account(
            ACCOUNT, SecretText(access), SecretText(refresh), Long.MAX_VALUE, SecretText(DEVICE))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText("opaque-private-guest-session"), SecretText(GUEST_TOKEN), Long.MAX_VALUE)
    }
}
