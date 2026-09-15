package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Complete schema2 data codec, not a store owner, migration commit or authority. The existing
 * schema1 codec remains unchanged. Exact nested originals, observations, review material and
 * publication links survive read/re-encode; no queue state or live witness is reconstructed.
 */
internal class PostDraftV2Codec(
    private val environment: String,
    private val exactOriginBinding: String,
    private val draftPolicy: PostDraftClientPolicy,
    private val publicationPolicy: PostPublicationClientPolicy,
    private val snapshots: DraftLocalSnapshotCodecV1,
    private val links: PublicationOriginalLinkCodecV1,
) {
    private val origin = uuid(exactOriginBinding)
    private val legacy = PostDraftCodec(origin, draftPolicy)
    private val responseBytes = minOf(draftPolicy.maxResponseBytes, publicationPolicy.maxResponseBytes)
    private val reviewed = ReviewedPostDraftAdapter(responseBytes)
    init {
        if (environment.isBlank() || environment.length > 200 || environment.any(Char::isISOControl)) invalid()
    }

    fun encode(value: PostDraftV2Record): PrivateBytes = guarded {
        validate(value)
        bytes(envelope(value)).also { decode(2, it) }
    }

    fun decode(schemaVersion: Int, payload: PrivateBytes): PostDraftV2Record = guarded {
        if (schemaVersion != 2) invalid()
        val root = parse(payload, draftPolicy.maxRecordBytes)
        keys(root, "schema", "origin", "environment", "clock", "locals", "issued", "command", "completion",
            "localPending", "terminals", "publicationHold", "remainders")
        if (long(root.getValue("schema")) != 2L || string(root.getValue("origin")) != origin ||
            string(root.getValue("environment")) != environment) invalid()
        val locals = array(root, "locals", draftPolicy.maxLocalDrafts).map { snapshots.decode(raw(string(it))) }
        val issued = array(root, "issued", draftPolicy.maxIssuedIds).map { strictId(string(it)) }
        val terminals = array(root, "terminals", draftPolicy.maxTombstones).map { terminal(it.jsonObject) }
        val remainders = array(root, "remainders", publicationPolicy.maxUnsubmittedRemainders).map { item ->
            val r = item.jsonObject
            keys(r, "kind", "originalLinkUtf8", "unsubmittedSnapshotUtf8")
            if (string(r.getValue("kind")) != "unsubmitted-remainder-v1") invalid()
            PostDraftRemainderV2(links.decode(raw(string(r.getValue("originalLinkUtf8")))),
                snapshots.decode(raw(string(r.getValue("unsubmittedSnapshotUtf8")))))
        }
        val hold = objectOrNull(root.getValue("publicationHold"))?.let {
            keys(it, "kind", "originalLinkUtf8", "reservation")
            if (string(it.getValue("kind")) != "publication-hold-v1") invalid()
            val r = it.getValue("reservation").jsonObject
            keys(r, "format", "maxResponseBytes", "publicationReservedBytes", "draftFinalizationReservedBytes", "reservedRemainderSlots")
            if (string(r.getValue("format")) != "publication-capacity-reservation-v1") invalid()
            PostDraftPublicationHoldV2(links.decode(raw(string(it.getValue("originalLinkUtf8")))),
                PublicationCapacityReservationV1(int(r.getValue("maxResponseBytes")), long(r.getValue("publicationReservedBytes")),
                    long(r.getValue("draftFinalizationReservedBytes")), int(r.getValue("reservedRemainderSlots"))))
        }
        val pending = objectOrNull(root.getValue("localPending"))?.let {
            keys(it, "client", "revision"); PostLocalPending(strictId(string(it.getValue("client"))), positive(it.getValue("revision")))
        }
        PostDraftV2Record(long(root.getValue("clock")), locals, issued,
            objectOrNull(root.getValue("command"))?.let(::command),
            objectOrNull(root.getValue("completion"))?.let(::completion), pending, terminals, hold, remainders).also { validate(it) }
    }

    /** Pure historical material construction. The actual Save controller must separately prove
     * explicit review, current row/principal/policy and acknowledged original persistence. */
    fun createReviewedCommand(original: ReviewedDraftOriginalFieldsV2, snapshot: DraftLocalSnapshotV1,
        displayedDisclosure: PublicationDisclosure?): PostDraftCommandV2.ReviewedPatch = guarded {
        preflightReviewed(original)
        val checked = snapshots.decode(snapshots.encode(snapshot))
        val expected = reviewed.expectedFields(original.historicalCall(), original.baseline, original.etag)
        disclosurePreflight(displayedDisclosure)
        val material = bytes(buildJsonObject {
            put("format", "reviewed-draft-patch-material-v1"); put("clientDraftId", original.clientId)
            put("localRevision", original.localRevision); put("exactReviewedLocalSnapshotUtf8", text(checked.exactUtf8))
            put("pathParameters", pathJson(original.path)); put("exactPatchUtf8", text(original.body))
            put("exactBaselineUtf8", text(original.baseline)); put("baselineETag", original.etag)
            put("expectedFieldsUtf8", text(expected)); put("displayedDisclosure", disclosureJson(displayedDisclosure))
        })
        PostDraftCommandV2.ReviewedPatch(original, expected, parseReview(material, original, expected)).also(::validateReviewed)
    }

    /** Recomputes this row's actual finalization reserve after EVERY later edit/history change.
     * The caller supplies a number from the actual publication codec; cross-record validation
     * independently recomputes that number from the actual publication journal. Data only. */
    fun withPublicationReservation(value: PostDraftV2Record, publicationReservedBytes: Long): PostDraftV2Record = guarded {
        val hold = value.publicationHold ?: invalid()
        val provisional = value.copy(publicationHold = PostDraftPublicationHoldV2(hold.link,
            PublicationCapacityReservationV1(publicationPolicy.maxResponseBytes, publicationReservedBytes, 0, 1)))
        validate(provisional, checkReservation = false)
        val final = provisional.copy(publicationHold = PostDraftPublicationHoldV2(hold.link,
            PublicationCapacityReservationV1(publicationPolicy.maxResponseBytes, publicationReservedBytes,
                finalizationBytes(provisional), 1)))
        encode(final)
        final
    }

    /** This is the ONLY codec projection used by joint tests/owners: full row validation,
     * including recomputed draft reserve and record/count bounds, precedes detached mapping. */
    fun publicationProjection(value: PostDraftV2Record): DraftPublicationProjectionV2 {
        val checked = decode(2, encode(value))
        return DraftPublicationProjectionV2(origin, checked.locals, checked.issued,
            checked.terminals.filterIsInstance<PostDraftTerminalV2.LegacyDiscard>().map { it.clientId }.toSet(),
            checked.publicationHold?.let { hold -> PublicationHoldProjectionV1(hold.link,
                checked.locals.single { it.clientDraftId == hold.link.clientDraftId }, hold.reservation) },
            checked.terminals.filterIsInstance<PostDraftTerminalV2.Published>().map {
                PublishedRootProjectionV2(it.clientId, it.link.commandId, it.postId, it.link.reviewedLocalRevision, it.link)
            }, checked.remainders.map {
                PublicationRemainderProjectionV1(it.clientId, it.link.commandId, it.reviewedLocalRevision,
                    it.newerLocalRevision, it.content, it.link)
            }, checked.command?.clientId, checked.completion?.clientId)
    }

    private fun validate(value: PostDraftV2Record, checkReservation: Boolean = true) {
        if (value.clock < 0) invalid()
        bound(value.locals.size, draftPolicy.maxLocalDrafts); bound(value.issued.size, draftPolicy.maxIssuedIds)
        bound(value.terminals.size + if (value.publicationHold == null) 0 else 1, draftPolicy.maxTombstones)
        bound(value.remainders.size + if (value.publicationHold == null) 0 else 1, publicationPolicy.maxUnsubmittedRemainders)
        value.issued.forEach(::strictId)
        if (value.issued.distinct().size != value.issued.size) invalid()
        value.localPending?.let {
            strictId(it.clientId); if (it.revision <= 0) invalid()
        }
        var rawMinimum = 0L
        fun consume(size: Int) { rawMinimum += size; if (rawMinimum > draftPolicy.maxRecordBytes) unavailable() }
        for (local in value.locals) consume(snapshots.encode(local).copyForCodec().size)
        val roots = value.locals.map { it.clientDraftId }
        val terminalRoots = value.terminals.map { it.clientId }
        if (roots.distinct().size != roots.size || terminalRoots.distinct().size != terminalRoots.size ||
            roots.any { it in terminalRoots } || (roots + terminalRoots).any { it !in value.issued }) invalid()
        if (value.command != null && value.completion != null) invalid()
        value.command?.let { c ->
            strictId(c.id); strictId(c.clientId)
            if (c.id !in value.issued || c.clientId !in value.issued || c.created < 0 || c.created > value.clock ||
                value.locals.none { it.clientDraftId == c.clientId && it.localRevision >= c.localRevision }) invalid()
            when (c) {
                is PostDraftCommandV2.TextCreate -> if (c.operation != "createPostDraft") invalid()
                is PostDraftCommandV2.TextPatch -> if (c.operation != "updatePostDraft") invalid()
                is PostDraftCommandV2.Discard -> if (c.operation != "deletePostDraft") invalid()
                is PostDraftCommandV2.ReviewedPatch -> {
                    preflightReviewed(c.original); validateReviewed(c)
                    currentAgainstReviewed(value.locals.single { it.clientDraftId == c.clientId }, c)
                }
            }
            consume(commandRawMinimum(c))
        }
        value.completion?.let { c ->
            strictId(c.commandId); strictId(c.clientId)
            if (c.commandId !in value.issued || c.clientId !in value.issued) invalid()
            when (c) {
                is PostDraftCompletionV2.Legacy -> if (c.operation !in POST_MUTATIONS) invalid()
                is PostDraftCompletionV2.ReviewedApplied -> {
                    validateReviewed(c.original)
                    if (c.original.created > value.clock || roots.none { it == c.clientId }) invalid()
                    bounded(c.actualDraft.encodeUtf8(), responseBytes)
                    reviewed.possibleOriginalResult(c.original.historicalCall(), c.original.original.baseline,
                        c.original.original.etag, c.actualDraft, c.etag)
                    val current = value.locals.single { it.clientDraftId == c.clientId }
                    if (current.localRevision < c.original.localRevision) invalid()
                    currentAgainstReviewed(current, c.original)
                    val association = current.serverAssociation as? DraftServerAssociationV1.Observed ?: invalid()
                    PostDraftAdapter(draftPolicy).monotone(c.actualDraft, association.exactPostDraft)
                    consume(c.actualDraft.encodeUtf8().size); consume(commandRawMinimum(c.original))
                }
                is PostDraftCompletionV2.ReviewedUnsent -> {
                    validateReviewed(c.original)
                    if (c.original.created > value.clock || value.locals.none {
                            it.clientDraftId == c.clientId && it.localRevision >= c.original.localRevision }) invalid()
                    currentAgainstReviewed(value.locals.single { it.clientDraftId == c.clientId }, c.original)
                    consume(commandRawMinimum(c.original))
                }
            }
        }
        val allLinks = mutableListOf<PublicationOriginalLinkV1>()
        for (terminal in value.terminals) when (terminal) {
            is PostDraftTerminalV2.LegacyDiscard -> {
                strictId(terminal.clientId); terminal.exactLegacy.serverId?.let(::strictId); terminal.commandId?.let(::strictId)
            }
            is PostDraftTerminalV2.Published -> {
                linkBinding(terminal.link, value); strictId(terminal.postId)
                consume(links.encode(terminal.link).copyForCodec().size); allLinks += terminal.link
            }
        }
        val published = value.terminals.filterIsInstance<PostDraftTerminalV2.Published>()
        if (published.map { it.postId }.distinct().size != published.size) invalid()
        val remainderRoots = value.remainders.map { it.clientId }
        if (remainderRoots.distinct().size != remainderRoots.size) invalid()
        for (remainder in value.remainders) {
            linkBinding(remainder.link, value)
            val terminal = published.singleOrNull { it.clientId == remainder.clientId } ?: invalid()
            links.requireSameExactLink(terminal.link, remainder.link)
            val snapshot = snapshots.decode(snapshots.encode(remainder.unsubmittedSnapshot))
            if (snapshot.clientDraftId != remainder.clientId || snapshot.localRevision <= remainder.reviewedLocalRevision ||
                snapshot.serverAssociation !== DraftServerAssociationV1.NotObserved) invalid()
            consume(remainder.link.exactUtf8.copyForCodec().size); consume(snapshot.exactUtf8.copyForCodec().size)
            allLinks += remainder.link
        }
        value.publicationHold?.let { hold ->
            linkBinding(hold.link, value); consume(links.encode(hold.link).copyForCodec().size); allLinks += hold.link
            val current = value.locals.singleOrNull { it.clientDraftId == hold.link.clientDraftId } ?: invalid()
            val before = hold.link.historicalReview.exactReviewedLocalSnapshot
            if (current.localRevision < before.localRevision || (current.localRevision == before.localRevision &&
                    !sameContent(current.content, before.content))) invalid()
            if (before.serverAssociation is DraftServerAssociationV1.Observed) {
                val next = current.serverAssociation as? DraftServerAssociationV1.Observed ?: invalid()
                PostDraftAdapter(draftPolicy).monotone(before.serverAssociation.exactPostDraft, next.exactPostDraft)
            }
            if (value.command?.clientId == current.clientDraftId || value.completion?.clientId == current.clientDraftId) invalid()
            val r = hold.reservation
            if (r.maxResponseBytes != publicationPolicy.maxResponseBytes || r.reservedRemainderSlots != 1 || r.publicationReservedBytes <= 0)
                invalid()
            if (r.publicationReservedBytes > publicationPolicy.maxRecordBytes) unavailable()
            if (checkReservation) {
                val actual = finalizationBytes(value)
                if (r.draftFinalizationReservedBytes != actual) invalid()
                if (actual > draftPolicy.maxRecordBytes) unavailable()
            }
        }
        if (allLinks.map { it.originalCanonicalUserId }.distinct().size > 1) invalid()
        for (sameCommand in allLinks.groupBy { it.commandId }.values)
            sameCommand.drop(1).forEach { links.requireSameExactLink(sameCommand.first(), it) }
        val publicationCommands = allLinks.map { it.commandId }.toSet()
        val retainedDraftCommands = listOfNotNull(value.command?.id, value.completion?.commandId) +
            value.terminals.filterIsInstance<PostDraftTerminalV2.LegacyDiscard>().mapNotNull { it.commandId }
        if (publicationCommands.any { it in roots || it in terminalRoots || it in retainedDraftCommands }) invalid()
        // Validation-only legacy projection: it is NEVER serialized as the schema2 payload.
        // This delegates every text-original/discard/completion allowlist to the UNCHANGED v1
        // codec and deliberately preserves valid command + newer localPending combinations.
        val legacyValue = PostRecord(value.clock, value.locals.map(::legacyLocal), value.issued,
            value.terminals.filterIsInstance<PostDraftTerminalV2.LegacyDiscard>().map { it.exactLegacy },
            value.command?.legacyOriginal, (value.completion as? PostDraftCompletionV2.Legacy)?.exactLegacy, value.localPending)
        legacy.encode(legacyValue)
    }

    private fun validateReviewed(command: PostDraftCommandV2.ReviewedPatch) {
        val o = command.original; preflightReviewed(o)
        bounded(command.expectedFields.encodeUtf8(), responseBytes)
        bounded(command.historicalReview.exactUtf8.copyForCodec(), draftPolicy.maxRecordBytes)
        val expected = reviewed.expectedFields(o.historicalCall(), o.baseline, o.etag)
        if (!expected.encodeUtf8().contentEquals(command.expectedFields.encodeUtf8())) invalid()
        val material = parseReview(command.historicalReview.exactUtf8, o, expected)
        if (!snapshots.encode(command.historicalReview.exactReviewedLocalSnapshot).copyForCodec()
                .contentEquals(material.exactReviewedLocalSnapshot.exactUtf8.copyForCodec()) ||
            disclosureJson(command.historicalReview.displayedDisclosure) != disclosureJson(material.displayedDisclosure)) invalid()
    }

    private fun parseReview(materialBytes: PrivateBytes, original: ReviewedDraftOriginalFieldsV2,
        expected: WireDocument): ReviewedDraftReviewMaterialV1 {
        val root = parse(materialBytes, draftPolicy.maxRecordBytes)
        keys(root, "format", "clientDraftId", "localRevision", "exactReviewedLocalSnapshotUtf8", "pathParameters",
            "exactPatchUtf8", "exactBaselineUtf8", "baselineETag", "expectedFieldsUtf8", "displayedDisclosure")
        if (string(root.getValue("format")) != "reviewed-draft-patch-material-v1" ||
            string(root.getValue("clientDraftId")) != original.clientId || positive(root.getValue("localRevision")) != original.localRevision ||
            root.getValue("pathParameters") != pathJson(original.path) ||
            string(root.getValue("exactPatchUtf8")) != text(original.body) ||
            string(root.getValue("exactBaselineUtf8")) != text(original.baseline) ||
            string(root.getValue("baselineETag")) != original.etag || string(root.getValue("expectedFieldsUtf8")) != text(expected)) invalid()
        val snapshot = snapshots.decode(raw(string(root.getValue("exactReviewedLocalSnapshotUtf8"))))
        if (snapshot.clientDraftId != original.clientId || snapshot.localRevision != original.localRevision) invalid()
        val association = snapshot.serverAssociation as? DraftServerAssociationV1.Observed ?: invalid()
        if (association.etag != original.etag || !association.exactPostDraft.encodeUtf8().contentEquals(original.baseline.encodeUtf8())) invalid()
        val fields = expected.json().jsonObject
        when (val content = snapshot.content) {
            is DraftLocalContentV1.TextV1 -> if (fields["caption"] != JsonPrimitive(content.caption) ||
                fields["altText"] != content.altText?.let(::JsonPrimitive)) invalid()
            is DraftLocalContentV1.ComposerV2 -> {
                val selected = JsonObject(fields.filterKeys { it in publicationRecordSelectionFields })
                if (!publicationRecordSame(normalizedDraftChoices(content.exactChoices.json().jsonObject), selected)) invalid()
            }
        }
        val disclosure = objectOrNull(root.getValue("displayedDisclosure"))?.let {
            keys(it, "version", "text")
            val version = string(it.getValue("version")); val display = string(it.getValue("text"))
            if (version.length.toLong() + display.length > publicationPolicy.maxDisclosureBytes ||
                version.encodeToByteArray(throwOnInvalidSequence = true).size.toLong() +
                display.encodeToByteArray(throwOnInvalidSequence = true).size > publicationPolicy.maxDisclosureBytes) unavailable()
            PublicationDisclosure(version, display)
        }
        disclosurePreflight(disclosure)
        val patch = parse(original.body, publicationPolicy.maxOriginalBytes)
        if ("saveDisclosureVersion" in patch && (disclosure == null || patch["saveDisclosureVersion"] != JsonPrimitive(disclosure.version))) invalid()
        if (disclosure != null && fields["saveDisclosureVersion"] != JsonPrimitive(disclosure.version)) invalid()
        (snapshot.content as? DraftLocalContentV1.ComposerV2)?.historicalDisclosureText?.let {
            if (disclosure != null && disclosure.text != it) invalid()
        }
        return ReviewedDraftReviewMaterialV1(materialBytes, snapshot, disclosure)
    }

    private fun currentAgainstReviewed(current: DraftLocalSnapshotV1, command: PostDraftCommandV2.ReviewedPatch) {
        val before = command.historicalReview.exactReviewedLocalSnapshot
        if (current.localRevision < before.localRevision || (current.localRevision == before.localRevision &&
                !sameContent(current.content, before.content))) invalid()
        val prior = before.serverAssociation as? DraftServerAssociationV1.Observed ?: invalid()
        val next = current.serverAssociation as? DraftServerAssociationV1.Observed ?: invalid()
        PostDraftAdapter(draftPolicy).monotone(prior.exactPostDraft, next.exactPostDraft)
    }

    private fun preflightReviewed(o: ReviewedDraftOriginalFieldsV2) {
        strictId(o.id); strictId(o.clientId)
        if (o.id == o.clientId || o.localRevision <= 0 || o.created < 0 || o.path.size != 1 || o.path.keys != setOf("draftId")) invalid()
        val pathId = o.path.getValue("draftId")
        if (pathId.length != 36 || uuid(pathId) != postString(o.baseline, "id")) invalid()
        bounded(o.body.copyForCodec(), publicationPolicy.maxOriginalBytes)
        bounded(o.baseline.encodeUtf8(), responseBytes)
        if (o.etag.length > 2004) unavailable()
        if (postString(o.baseline, "clientDraftId") != o.clientId) invalid()
    }

    private fun linkBinding(link: PublicationOriginalLinkV1, value: PostDraftV2Record) {
        links.encode(link)
        if (link.environment != environment || link.originBinding != exactOriginBinding ||
            link.clientDraftId !in value.issued || link.commandId !in value.issued || link.originalCreatedAtMillis > value.clock) invalid()
    }

    /** Conservative complete-row capacity probe only, never a fabricated actual Post/terminal
     * or contribution. It reserves full current content as one remainder even before a newer
     * edit exists; every later mutation recomputes it. Max decimal widths reserve clock/revision
     * growth. Removing the hold makes the formula non-recursive and independent of its number. */
    private fun finalizationBytes(value: PostDraftV2Record): Long {
        val hold = value.publicationHold ?: invalid()
        val current = value.locals.singleOrNull { it.clientDraftId == hold.link.clientDraftId } ?: invalid()
        val retainedContent = snapshots.create(current.clientDraftId, Long.MAX_VALUE, current.content, DraftServerAssociationV1.NotObserved)
        val root = envelope(value).toMutableMap()
        root["clock"] = JsonPrimitive(Long.MAX_VALUE)
        root["publicationHold"] = JsonNull
        root["locals"] = JsonArray(value.locals.filterNot { it.clientDraftId == current.clientDraftId }.map { JsonPrimitive(text(it.exactUtf8)) })
        root["terminals"] = JsonArray(value.terminals.map(::terminalJson) + buildJsonObject {
            put("kind", "published-root-v1"); put("originalLinkUtf8", text(hold.link.exactUtf8))
            put("postId", "ffffffff-ffff-ffff-ffff-ffffffffffff")
        })
        root["remainders"] = JsonArray(value.remainders.map(::remainderJson) + buildJsonObject {
            put("kind", "unsubmitted-remainder-v1"); put("originalLinkUtf8", text(hold.link.exactUtf8))
            put("unsubmittedSnapshotUtf8", text(retainedContent.exactUtf8))
        })
        // localPending is retained in the byte-only probe as a conservative overhead. The
        // actual finalizer must resolve genuine held local evidence before retiring that field.
        return JsonObject(root).toString().encodeToByteArray().size.toLong()
    }

    private fun envelope(value: PostDraftV2Record) = buildJsonObject {
        put("schema", 2); put("origin", origin); put("environment", environment); put("clock", value.clock)
        put("locals", JsonArray(value.locals.map { JsonPrimitive(text(it.exactUtf8)) }))
        put("issued", JsonArray(value.issued.map(::JsonPrimitive)))
        put("command", value.command?.let(::commandJson) ?: JsonNull)
        put("completion", value.completion?.let(::completionJson) ?: JsonNull)
        put("localPending", value.localPending?.let { buildJsonObject { put("client", it.clientId); put("revision", it.revision) } } ?: JsonNull)
        put("terminals", JsonArray(value.terminals.map(::terminalJson)))
        put("publicationHold", value.publicationHold?.let { hold -> buildJsonObject {
            put("kind", "publication-hold-v1"); put("originalLinkUtf8", text(hold.link.exactUtf8))
            put("reservation", buildJsonObject {
                put("format", "publication-capacity-reservation-v1"); put("maxResponseBytes", hold.reservation.maxResponseBytes)
                put("publicationReservedBytes", hold.reservation.publicationReservedBytes)
                put("draftFinalizationReservedBytes", hold.reservation.draftFinalizationReservedBytes)
                put("reservedRemainderSlots", hold.reservation.reservedRemainderSlots)
            })
        } } ?: JsonNull)
        put("remainders", JsonArray(value.remainders.map(::remainderJson)))
    }
    private fun commandJson(c: PostDraftCommandV2): JsonObject = buildJsonObject {
        put("kind", when (c) {
            is PostDraftCommandV2.TextCreate -> "text-create-v1"
            is PostDraftCommandV2.TextPatch -> "text-patch-v1"
            is PostDraftCommandV2.Discard -> "discard-v1"
            is PostDraftCommandV2.ReviewedPatch -> "reviewed-patch-v2"
        })
        if (c is PostDraftCommandV2.ReviewedPatch) {
            put("original", buildJsonObject {
                put("id", c.id); put("operation", c.operation); put("client", c.clientId); put("revision", c.localRevision)
                put("path", pathJson(c.original.path)); put("query", JsonObject(emptyMap()))
                put("body", text(c.original.body)); put("baseline", text(c.original.baseline)); put("etag", c.original.etag); put("created", c.created)
            })
            put("expectedFieldsUtf8", text(c.expectedFields)); put("historicalReviewUtf8", text(c.historicalReview.exactUtf8))
        } else put("original", legacyOriginalJson(c.legacyOriginal!!))
    }
    private fun command(c: JsonObject): PostDraftCommandV2 {
        val kind = string(c.getValue("kind"))
        if (kind == "reviewed-patch-v2") {
            keys(c, "kind", "original", "expectedFieldsUtf8", "historicalReviewUtf8")
            val o = c.getValue("original").jsonObject
            keys(o, "id", "operation", "client", "revision", "path", "query", "body", "baseline", "etag", "created")
            if (string(o.getValue("operation")) != "updatePostDraft" || o.getValue("query") != JsonObject(emptyMap())) invalid()
            val path = o.getValue("path").jsonObject; keys(path, "draftId")
            val original = ReviewedDraftOriginalFieldsV2(strictId(string(o.getValue("id"))), strictId(string(o.getValue("client"))),
                positive(o.getValue("revision")), path.mapValues { string(it.value) }, raw(string(o.getValue("body"))),
                document(o.getValue("baseline"), responseBytes), string(o.getValue("etag")), long(o.getValue("created")))
            preflightReviewed(original)
            val expected = document(c.getValue("expectedFieldsUtf8"), responseBytes)
            return PostDraftCommandV2.ReviewedPatch(original, expected,
                parseReview(raw(string(c.getValue("historicalReviewUtf8"))), original, expected))
        }
        keys(c, "kind", "original")
        val original = legacyOriginal(c.getValue("original").jsonObject)
        return when (kind) {
            "text-create-v1" -> PostDraftCommandV2.TextCreate(original)
            "text-patch-v1" -> PostDraftCommandV2.TextPatch(original)
            "discard-v1" -> PostDraftCommandV2.Discard(original)
            else -> invalid()
        }
    }
    private fun legacyOriginalJson(c: PostOriginal) = buildJsonObject {
        put("id", c.id); put("operation", c.operation); put("client", c.clientId); put("revision", c.localRevision)
        put("body", nullableText(c.body)); put("baseline", nullableText(c.baseline)); put("etag", c.etag?.let(::JsonPrimitive) ?: JsonNull); put("created", c.created)
    }
    private fun legacyOriginal(c: JsonObject): PostOriginal {
        keys(c, "id", "operation", "client", "revision", "body", "baseline", "etag", "created")
        return PostOriginal(strictId(string(c.getValue("id"))), string(c.getValue("operation")), strictId(string(c.getValue("client"))),
            positive(c.getValue("revision")), nullableDocument(c.getValue("body"), draftPolicy.maxResponseBytes),
            nullableDocument(c.getValue("baseline"), draftPolicy.maxResponseBytes), nullableString(c.getValue("etag")), long(c.getValue("created")))
    }
    private fun completionJson(c: PostDraftCompletionV2): JsonObject = when (c) {
        is PostDraftCompletionV2.Legacy -> buildJsonObject {
            put("kind", "legacy-completion-v1"); put("command", c.commandId); put("operation", c.operation)
            put("client", c.clientId); put("unsent", c.unsent)
        }
        is PostDraftCompletionV2.ReviewedApplied -> buildJsonObject {
            put("kind", "reviewed-applied-v2"); put("original", commandJson(c.original))
            put("actualDraftUtf8", text(c.actualDraft)); put("etag", c.etag)
        }
        is PostDraftCompletionV2.ReviewedUnsent -> buildJsonObject {
            put("kind", "reviewed-unsent-v2"); put("original", commandJson(c.original))
        }
    }
    private fun completion(c: JsonObject): PostDraftCompletionV2 = when (string(c.getValue("kind"))) {
        "legacy-completion-v1" -> {
            keys(c, "kind", "command", "operation", "client", "unsent")
            PostDraftCompletionV2.Legacy(PostCompletion(strictId(string(c.getValue("command"))), string(c.getValue("operation")),
                strictId(string(c.getValue("client"))), boolean(c.getValue("unsent"))))
        }
        "reviewed-applied-v2" -> {
            keys(c, "kind", "original", "actualDraftUtf8", "etag")
            PostDraftCompletionV2.ReviewedApplied(command(c.getValue("original").jsonObject) as? PostDraftCommandV2.ReviewedPatch ?: invalid(),
                document(c.getValue("actualDraftUtf8"), responseBytes), string(c.getValue("etag")))
        }
        "reviewed-unsent-v2" -> {
            keys(c, "kind", "original")
            PostDraftCompletionV2.ReviewedUnsent(command(c.getValue("original").jsonObject) as? PostDraftCommandV2.ReviewedPatch ?: invalid())
        }
        else -> invalid()
    }
    private fun terminalJson(t: PostDraftTerminalV2): JsonObject = when (t) {
        is PostDraftTerminalV2.LegacyDiscard -> buildJsonObject {
            put("kind", "legacy-discard-v1"); put("client", t.clientId)
            put("server", t.exactLegacy.serverId?.let(::JsonPrimitive) ?: JsonNull); put("command", t.commandId?.let(::JsonPrimitive) ?: JsonNull)
        }
        is PostDraftTerminalV2.Published -> buildJsonObject {
            put("kind", "published-root-v1"); put("originalLinkUtf8", text(t.link.exactUtf8)); put("postId", t.postId)
        }
    }
    private fun terminal(t: JsonObject): PostDraftTerminalV2 = when (string(t.getValue("kind"))) {
        "legacy-discard-v1" -> {
            keys(t, "kind", "client", "server", "command")
            PostDraftTerminalV2.LegacyDiscard(PostTerminal(strictId(string(t.getValue("client"))),
                nullableString(t.getValue("server"))?.let(::strictId), nullableString(t.getValue("command"))?.let(::strictId)))
        }
        "published-root-v1" -> {
            keys(t, "kind", "originalLinkUtf8", "postId")
            PostDraftTerminalV2.Published(links.decode(raw(string(t.getValue("originalLinkUtf8")))), strictId(string(t.getValue("postId"))))
        }
        else -> invalid()
    }
    private fun remainderJson(r: PostDraftRemainderV2) = buildJsonObject {
        put("kind", "unsubmitted-remainder-v1"); put("originalLinkUtf8", text(r.link.exactUtf8)); put("unsubmittedSnapshotUtf8", text(r.unsubmittedSnapshot.exactUtf8))
    }
    private fun legacyLocal(s: DraftLocalSnapshotV1): PostLocal {
        val a = s.serverAssociation as? DraftServerAssociationV1.Observed
        return PostLocal(s.clientDraftId, s.localRevision, s.content.caption, s.content.altText, a?.exactPostDraft, a?.etag)
    }
    private fun commandRawMinimum(c: PostDraftCommandV2): Int {
        if (c is PostDraftCommandV2.ReviewedPatch) {
            val sizes = listOf(c.original.body.copyForCodec().size, c.original.baseline.encodeUtf8().size,
                c.expectedFields.encodeUtf8().size, c.historicalReview.exactUtf8.copyForCodec().size)
            if (sizes.sumOf { it.toLong() } > draftPolicy.maxRecordBytes) unavailable()
            return sizes.sum()
        }
        val o = c.legacyOriginal!!
        o.body?.let { bounded(it.encodeUtf8(), draftPolicy.maxResponseBytes) }
        o.baseline?.let { bounded(it.encodeUtf8(), draftPolicy.maxResponseBytes) }
        if ((o.etag?.length ?: 0) > 2004) unavailable()
        return (o.body?.encodeUtf8()?.size ?: 0) + (o.baseline?.encodeUtf8()?.size ?: 0)
    }
    private fun normalizedDraftChoices(value: JsonObject): JsonObject {
        val fields = value.toMutableMap()
        fields["mediaIds"] = JsonArray(value.getValue("mediaIds").jsonArray.map { JsonPrimitive(uuid(string(it))) })
        val audience = value.getValue("audience").jsonObject
        fields["audience"] = JsonObject(audience + ("circleIds" to JsonArray(audience.getValue("circleIds").jsonArray.map { JsonPrimitive(uuid(string(it))) })))
        return JsonObject(fields)
    }
    private fun sameContent(a: DraftLocalContentV1, b: DraftLocalContentV1): Boolean = when {
        a is DraftLocalContentV1.TextV1 && b is DraftLocalContentV1.TextV1 -> a.caption == b.caption && a.altText == b.altText
        a is DraftLocalContentV1.ComposerV2 && b is DraftLocalContentV1.ComposerV2 -> a.historicalDisclosureText == b.historicalDisclosureText &&
            publicationRecordSame(a.exactChoices.json(), b.exactChoices.json())
        else -> false
    }
    private fun disclosurePreflight(value: PublicationDisclosure?) {
        value ?: return
        if (value.version.length.toLong() + value.text.length > publicationPolicy.maxDisclosureBytes) unavailable()
        val count = value.version.encodeToByteArray(throwOnInvalidSequence = true).size.toLong() + value.text.encodeToByteArray(throwOnInvalidSequence = true).size
        if (count > publicationPolicy.maxDisclosureBytes) unavailable()
    }
    private fun disclosureJson(value: PublicationDisclosure?): JsonElement = value?.let {
        buildJsonObject { put("version", it.version); put("text", it.text) }
    } ?: JsonNull
    private fun pathJson(value: Map<String, String>) = JsonObject(value.mapValues { JsonPrimitive(it.value) })
    private fun nullableText(value: WireDocument?): JsonElement = value?.let { JsonPrimitive(text(it)) } ?: JsonNull
    private fun text(value: WireDocument) = value.encodeUtf8().decodeToString(throwOnInvalidSequence = true)
    private fun text(value: PrivateBytes) = value.copyForCodec().decodeToString(throwOnInvalidSequence = true)
    private fun raw(value: String): PrivateBytes {
        if (value.length > draftPolicy.maxRecordBytes) unavailable()
        return PrivateBytes(value.encodeToByteArray(throwOnInvalidSequence = true)).also { bounded(it.copyForCodec(), draftPolicy.maxRecordBytes) }
    }
    private fun bytes(value: JsonObject) = PrivateBytes(value.toString().encodeToByteArray()).also { bounded(it.copyForCodec(), draftPolicy.maxRecordBytes) }
    private fun parse(value: PrivateBytes, limit: Int): JsonObject {
        bounded(value.copyForCodec(), limit)
        return WireDocument.decode(value.copyForCodec(), WireLimits(limit, 32)).json().jsonObject
    }
    private fun document(value: JsonElement, limit: Int): WireDocument {
        val content = raw(string(value)); bounded(content.copyForCodec(), limit)
        return WireDocument.decode(content.copyForCodec(), WireLimits(limit, 24))
    }
    private fun nullableDocument(value: JsonElement, limit: Int) = if (value == JsonNull) null else document(value, limit)
    private fun objectOrNull(value: JsonElement) = if (value == JsonNull) null else value.jsonObject
    private fun array(root: JsonObject, name: String, limit: Int) = root.getValue(name).jsonArray.also { bound(it.size, limit) }
    private fun positive(value: JsonElement) = long(value).also { if (it == 0L) invalid() }
    private fun int(value: JsonElement) = long(value).also { if (it > Int.MAX_VALUE) invalid() }.toInt()
    private fun bound(size: Int, limit: Int) { if (size > limit) unavailable() }
    private fun bounded(value: ByteArray, limit: Int) { if (value.size > limit) unavailable() }
    private fun invalid(): Nothing = mealFail(FailureReason.INVALID_DATA)
    private fun unavailable(): Nothing = mealFail(FailureReason.UNAVAILABLE)
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (failure: MealFailure) { throw failure }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WireDecodingException) {
            if (failure.reason in setOf(WireFailure.BYTE_LIMIT, WireFailure.DEPTH_LIMIT, WireFailure.NUMBER_LIMIT)) unavailable() else invalid()
        }
        catch (_: Exception) { invalid() }
    override fun toString() = "PostDraftV2Codec(<redacted>)"
}
