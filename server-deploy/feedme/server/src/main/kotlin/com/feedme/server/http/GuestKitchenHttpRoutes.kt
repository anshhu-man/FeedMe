package com.feedme.server.http

import com.feedme.server.contract.ContractBodyValidator
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal val guestKitchenHttpOperations = setOf("getPreferences", "updatePreferences", "listPantry",
    "upsertPantryItem", "removePantryItem")

/** Requested credential profile is selected once from strict headers. Delegated account
 * handlers reparse/authenticate their original request before the body is consumed here.
 * A refusal never retries another authority; token spelling cannot choose account access.
 * No-device requests require the actual guest adapter bound by explicit configuration.
 */
internal suspend fun ApplicationCall.guestKitchenOperation(operation: String,
    configuration: GuestHttpConfiguration, validator: ContractBodyValidator,
    pendingPreferences: PendingPreferencesHttpConfiguration?, accountPreferences: AccountPreferencesHttpConfiguration?,
    kitchen: KitchenHttpConfiguration?, pantry: PantryHttpConfiguration?, accountPantry: AccountPantryHttpConfiguration?) =
    guestBoundary(operation, validator) {
        require(operation in guestKitchenHttpOperations)
        rejectGuestSearchUpgrade(request.headers)
        val input = KitchenHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        if (input.bearer.deviceSessionId != null) {
            when {
                operation !in pantryHttpOperations && pendingPreferences != null ->
                    pendingPreferencesOperation(operation, pendingPreferences, validator)
                operation !in pantryHttpOperations && accountPreferences != null ->
                    accountPreferencesOperation(operation, accountPreferences, validator)
                kitchen != null -> kitchenOperation(operation, kitchen, validator)
                operation in pantryHttpOperations && pantry != null -> pantryOperation(operation, pantry, validator)
                operation in pantryHttpOperations && accountPantry != null -> accountPantryOperation(operation, accountPantry, validator)
                else -> throw GuestHttpFailure(503, "NOT_CONFIGURED")
            }
        } else {
            val store = configuration.kitchen ?: throw GuestHttpFailure(503, "NOT_CONFIGURED")
            val body = input.readBody(receiveChannel(), validator)
            currentCoroutineContext().ensureActive()
            val reply = runInterruptible(configuration.databaseDispatcher) {
                input.bearer.token.use { token -> when (operation) {
                    "getPreferences" -> store.getPreferences(token)
                    "updatePreferences" -> kitchenReply(store.updatePreferences(token, input.key!!, input.ifMatch!!, body!!))
                    "listPantry" -> store.listPantry(token, input.cursor, input.limit)
                    "upsertPantryItem" -> kitchenReply(store.upsertPantryItem(token, input.key!!, body!!))
                    "removePantryItem" -> kitchenReply(store.removePantryItem(token, input.key!!, input.ingredientId!!, input.ifMatch!!))
                    else -> error("Unsupported guest kitchen operation")
                } }
            }
            currentCoroutineContext().ensureActive()
            val text = validateKitchenReply(operation, reply, validator, store.policy.maxResponseBytes)
            reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
            if (text == null) respond(HttpStatusCode.NoContent)
            else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
        }
    }
