package com.roberto.eliasaitutor.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.roberto.eliasaitutor.model.UserProfile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileSyncPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private fun manager(file: File, scope: CoroutineScope) = DataStoreManager(owner, PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test fun offlineMutationSurvivesRestartAndBlocksStalePull() = runBlocking {
        val file = File(folder.root, "profile.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var store = manager(file, scope)
        store.save(UserProfile(userId = owner, xp = 90, coins = 12))
        val before = store.beginUpload()!!
        assertTrue(before.dirty)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = manager(file, scope)
        val after = store.syncSnapshot()
        assertEquals(before.mutationId, after.mutationId)
        assertEquals(90, after.profile.xp)
        assertFalse(store.acceptRemote(UserProfile(userId = owner, xp = 0), 4, after.localRevision))
        assertEquals(90, store.syncSnapshot().profile.xp)
        store.acknowledgeUpload(store.beginUpload()!!, 1)
        assertFalse(store.syncSnapshot().dirty)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun lostResponseReplaysOldPayloadAfterNewLocalEditAndRestart() = runBlocking {
        val file = File(folder.root, "lost-response.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var store = manager(file, scope)
        store.save(UserProfile(userId = owner, xp = 30))
        val sent = store.beginUpload()!!
        // Server accepted sent, but the HTTP response was lost. User keeps working.
        store.save(UserProfile(userId = owner, xp = 40))
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = manager(file, scope)
        assertEquals(sent, store.beginUpload())
        store.acknowledgeUpload(sent, 1)
        val next = store.beginUpload()!!
        assertEquals(40, next.profile.xp)
        assertEquals(1L, next.remoteRevision)
        assertNotEquals(sent.mutationId, next.mutationId)
        store.acknowledgeUpload(next, 2)
        assertNull(store.beginUpload())
        assertFalse(store.syncSnapshot().dirty)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun wireSerializerUsesVerifiedOwnerField() {
        val text = kotlinx.serialization.json.Json.encodeToString(UserProfile.serializer(), UserProfile(userId = owner, xp = 30))
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals(owner, (parsed["user_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse(parsed.containsKey("userId"))
    }

    @Test fun uploadAckDoesNotClearANewerLocalMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = manager(File(folder.root, "profile.preferences_pb"), scope)
        store.save(UserProfile(userId = owner, xp = 10))
        val sending = store.beginUpload()!!
        store.save(UserProfile(userId = owner, xp = 20))
        store.acknowledgeUpload(sending, 1)
        val current = store.syncSnapshot()
        assertTrue(current.dirty)
        assertEquals(20, current.profile.xp)
        assertEquals(1, current.remoteRevision)
        assertNotEquals(sending.mutationId, current.mutationId)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun newDeviceLoadsRemoteBeforeAnyUploadAndRacingEditIsPreserved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = manager(File(folder.root, "profile.preferences_pb"), scope)
        val initial = store.syncSnapshot()
        assertFalse(initial.dirty)
        assertEquals(owner, initial.profile.userId)
        assertTrue(store.acceptRemote(UserProfile(userId = owner, xp = 400), 7, initial.localRevision))
        val downloaded = store.syncSnapshot()
        assertEquals(400, downloaded.profile.xp)
        assertFalse(downloaded.dirty)
        store.save(downloaded.profile.copy(xp = 410))
        assertFalse(store.acceptRemote(UserProfile(userId = owner, xp = 500), 8, downloaded.localRevision))
        assertEquals(410, store.syncSnapshot().profile.xp)
        try { store.save(UserProfile(userId = "other")); fail("cross-owner save accepted") } catch (_: IllegalArgumentException) {}
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
