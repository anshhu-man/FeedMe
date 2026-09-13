package com.feedme.server

import com.feedme.server.db.EventDraft
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class EventDraftTest {
    @Test fun everyBlueprintEventNameIsAcceptedIncludingUnderscores() {
        val source=checkNotNull(javaClass.getResourceAsStream("/canonical-events.md")).bufferedReader().use{it.readText()}
        val types=Regex("^\\| ([a-z][a-z0-9_.]+\\.v[0-9]+) \\|",RegexOption.MULTILINE).findAll(source).map{it.groupValues[1]}.toList()
        assertTrue(types.size>=40)
        assertTrue("identity.account.deletion_requested.v1" in types)
        assertTrue("platform.media.upload_completed.v1" in types)
        assertTrue("social.post.audience_changed.v1" in types)
        for(type in types) EventDraft(UUID.randomUUID(),type,1,"fixture",UUID.randomUUID(),1,"fixture","trace",UUID.randomUUID(),JsonObject(emptyMap()))
    }
}
