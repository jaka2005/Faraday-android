/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import com.zhuinden.monarchy.Monarchy
import dagger.Lazy
import io.realm.Realm
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.database.helper.addIfNecessary
import org.matrix.android.sdk.internal.database.helper.addStateEvent
import org.matrix.android.sdk.internal.database.helper.addTimelineEvent
import org.matrix.android.sdk.internal.database.helper.doesNextChunksVerifyCondition
import org.matrix.android.sdk.internal.database.helper.doesPrevChunksVerifyCondition
import org.matrix.android.sdk.internal.database.helper.updateThreadSummaryIfNeeded
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.ChunkEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntityFields
import org.matrix.android.sdk.internal.database.query.copyToRealmOrIgnore
import org.matrix.android.sdk.internal.database.query.create
import org.matrix.android.sdk.internal.database.query.find
import org.matrix.android.sdk.internal.database.query.findAll
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.internal.util.awaitTransaction
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

/**
 * Insert Chunk in DB, and eventually link next and previous chunk in db.
 */
internal class TokenChunkEventPersistor @Inject constructor(
        @SessionDatabase private val monarchy: Monarchy,
        @UserId private val userId: String,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val liveEventManager: Lazy<StreamEventsManager>,
        private val clock: Clock,
) {

    enum class Result {
        SHOULD_FETCH_MORE,
        REACHED_END,
        SUCCESS
    }

    suspend fun insertInDb(receivedChunk: TokenChunkEvent,
                           roomId: String,
                           direction: PaginationDirection): Result {
        if (receivedChunk.events.isEmpty() && receivedChunk.start == receivedChunk.end) {
            Timber.w("Discard empty chunk with identical start/end token ${receivedChunk.start}")

            return if (receivedChunk.hasMore()) {
                Result.SHOULD_FETCH_MORE
            } else {
                Result.REACHED_END
            }
        } else if (receivedChunk.start == receivedChunk.end) {
            // I don't think we have seen this case so far, but let's log it just in case...
            // -> if it happens, we need to address it somehow!
            Timber.e("Non-empty chunk with identical start/end token ${receivedChunk.start}")
        }
        monarchy
                .awaitTransaction { realm ->
                    Timber.i("Start persisting ${receivedChunk.events.size} events in $roomId towards $direction | " +
                            "start ${receivedChunk.start}, end ${receivedChunk.end} | " +
                            "first ${receivedChunk.events.firstOrNull()?.eventId} last ${receivedChunk.events.lastOrNull()?.eventId}")

                    val nextToken: String?
                    val prevToken: String?
                    if (direction == PaginationDirection.FORWARDS) {
                        nextToken = receivedChunk.end
                        prevToken = receivedChunk.start
                    } else {
                        nextToken = receivedChunk.start
                        prevToken = receivedChunk.end
                    }

                    val existingChunk = ChunkEntity.find(realm, roomId, prevToken = prevToken, nextToken = nextToken)
                    if (existingChunk != null) {
                        Timber.v("This chunk is already in the db, checking if this might be caused by broken links")
                        existingChunk.fixChunkLinks(realm, roomId, direction, prevToken, nextToken)
                        return@awaitTransaction
                    }
                    val prevChunk = ChunkEntity.find(realm, roomId, nextToken = prevToken)
                    val nextChunk = ChunkEntity.find(realm, roomId, prevToken = nextToken)
                    val currentChunk = ChunkEntity.create(realm, prevToken = prevToken, nextToken = nextToken).apply {
                        this.nextChunk = nextChunk
                        this.prevChunk = prevChunk
                    }
                    val allNextChunks = ChunkEntity.findAll(realm, roomId, prevToken = nextToken)
                    val allPrevChunks = ChunkEntity.findAll(realm, roomId, nextToken = prevToken)
                    allNextChunks?.forEach {
                        it.prevChunk = currentChunk
                    }
                    allPrevChunks?.forEach {
                        it.nextChunk = currentChunk
                    }
                    if (receivedChunk.events.isEmpty() && !receivedChunk.hasMore()) {
                        handleReachEnd(roomId, direction, currentChunk)
                    } else {
                        handlePagination(realm, roomId, direction, receivedChunk, currentChunk)
                    }
                }

        return if (receivedChunk.events.isEmpty()) {
            if (receivedChunk.hasMore()) {
                Result.SHOULD_FETCH_MORE
            } else {
                Result.REACHED_END
            }
        } else {
            Result.SUCCESS
        }
    }

    private fun ChunkEntity.fixChunkLinks(
            realm: Realm,
            roomId: String,
            direction: PaginationDirection,
            prevToken: String?,
            nextToken: String?,
    ) {
        if (direction == PaginationDirection.FORWARDS) {
            val prevChunks = ChunkEntity.findAll(realm, roomId, nextToken = prevToken)
            Timber.v("Found ${prevChunks?.size} prevChunks")
            prevChunks?.forEach {
                if (it.nextChunk != this) {
                    Timber.i("Set nextChunk for ${it.identifier()} from ${it.nextChunk?.identifier()} to ${identifier()}")
                    it.nextChunk = this
                }
            }
        } else {
            val nextChunks = ChunkEntity.findAll(realm, roomId, prevToken = nextToken)
            Timber.v("Found ${nextChunks?.size} nextChunks")
            nextChunks?.forEach {
                if (it.prevChunk != this) {
                    Timber.i("Set prevChunk for ${it.identifier()} from ${it.prevChunk?.identifier()} to ${identifier()}")
                    it.prevChunk = this
                }
            }
        }
    }

    private fun handleReachEnd(roomId: String, direction: PaginationDirection, currentChunk: ChunkEntity) {
        Timber.v("Reach end of $roomId")
        if (direction == PaginationDirection.FORWARDS) {
            Timber.v("We should keep the lastForward chunk unique, the one from sync")
        } else {
            currentChunk.isLastBackward = true
        }
    }

    private fun handlePagination(
            realm: Realm,
            roomId: String,
            direction: PaginationDirection,
            receivedChunk: TokenChunkEvent,
            currentChunk: ChunkEntity
    ) {
        Timber.v("Add ${receivedChunk.events.size} events in chunk(${currentChunk.nextToken} | ${currentChunk.prevToken}")
        val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()
        val eventList = receivedChunk.events
        val stateEvents = receivedChunk.stateEvents

        val now = clock.epochMillis()

        stateEvents?.forEach { stateEvent ->
            val ageLocalTs = stateEvent.unsignedData?.age?.let { now - it }
            val stateEventEntity = stateEvent.toEntity(roomId, SendState.SYNCED, ageLocalTs).copyToRealmOrIgnore(realm, EventInsertType.PAGINATION)
            currentChunk.addStateEvent(roomId, stateEventEntity, direction)
            if (stateEvent.type == EventType.STATE_ROOM_MEMBER && stateEvent.stateKey != null) {
                roomMemberContentsByUser[stateEvent.stateKey] = stateEvent.content.toModel<RoomMemberContent>()
            }
        }
        val optimizedThreadSummaryMap = hashMapOf<String, EventEntity>()
        var hasNewEvents = false
        var existingChunkToLink: ChunkEntity? = null
        run processTimelineEvents@{
            eventList.forEach { event ->
                if (event.eventId == null || event.senderId == null) {
                    return@forEach
                }
                // We check for the timeline event with this id, but not in the thread chunk
                val eventId = event.eventId
                val existingTimelineEvent = TimelineEventEntity
                        .where(realm, roomId, eventId)
                        .equalTo(TimelineEventEntityFields.OWNED_BY_THREAD_CHUNK, false)
                        .findFirst()
                // If it exists, we want to stop here, just link the prevChunk
                val existingChunk = existingTimelineEvent?.chunk?.firstOrNull()
                if (existingChunk != null) {
                    if (existingChunk == currentChunk) {
                        Timber.w("Avoid double insertion of event $eventId, shouldn't happen in an ideal world | " +
                                "direction: $direction.value" +
                                "room: $roomId" +
                                "chunk: ${existingChunk.identifier()}" +
                                "eventId: $eventId" +
                                "caughtByOldCheck ${((if (direction == PaginationDirection.BACKWARDS) currentChunk.nextChunk else currentChunk.prevChunk) == existingChunk)}" +
                                "caughtByOldBackwardCheck ${(currentChunk.nextChunk == existingChunk)}" +
                                "caughtByOldForwardCheck ${(currentChunk.prevChunk == existingChunk)}"
                        )
                        // No idea why this happens, but if it does, we don't want to throw away all the other events
                        // (or even link chunks to themselves)
                        return@forEach
                    }
                    val alreadyLinkedNext = currentChunk.doesNextChunksVerifyCondition { it == existingChunk }
                    val alreadyLinkedPrev = currentChunk.doesPrevChunksVerifyCondition { it == existingChunk }
                    if (alreadyLinkedNext || alreadyLinkedPrev) {
                        Timber.w("Avoid double link, shouldn't happen in an ideal world | " +
                                "direction: $direction " +
                                "room: $roomId event: $eventId" +
                                "linkedPrev: $alreadyLinkedPrev linkedNext: $alreadyLinkedNext " +
                                "oldChunk: ${existingChunk.identifier()} newChunk: ${existingChunk.identifier()} " +
                                "oldBackwardCheck: ${currentChunk.nextChunk == existingChunk} " +
                                "oldForwardCheck: ${currentChunk.prevChunk == existingChunk}"
                        )
                        // Stop processing here
                        return@processTimelineEvents
                    }
                    // If we haven't found a single new event yet, we don't want to link in the pagination direction, as that might cause a
                    // timeline loop if the other chunk is in the other direction.
                    if (!hasNewEvents) {
                        Timber.i("Skip adding event $eventId, already exists")
                        // Only skip this event, but still process other events.
                        // Remember this chunk, since in case we don't find any new events, we still want to link this in pagination direction
                        // in order to link a chunk to the /sync chunk
                        if (existingChunkToLink == null) {
                            existingChunkToLink = existingChunk
                        }
                        return@forEach
                    }
                    when (direction) {
                        PaginationDirection.BACKWARDS -> {
                            Timber.i("Backwards insert chunk: ${existingChunk.identifier()} -> ${currentChunk.identifier()}")
                            currentChunk.prevChunk = existingChunk
                            existingChunk.nextChunk = currentChunk
                        }
                        PaginationDirection.FORWARDS  -> {
                            Timber.i("Forward insert chunk: ${currentChunk.identifier()} -> ${existingChunk.identifier()}")
                            currentChunk.nextChunk = existingChunk
                            existingChunk.prevChunk = currentChunk
                        }
                    }
                    // Stop processing here
                    return@processTimelineEvents
                }

                // existingChunk == null => this is a new event we haven't seen before
                hasNewEvents = true

                val ageLocalTs = event.unsignedData?.age?.let { now - it }
                var eventEntity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs).copyToRealmOrIgnore(realm, EventInsertType.PAGINATION)
                if (event.type == EventType.STATE_ROOM_MEMBER && event.stateKey != null) {
                    val contentToUse = if (direction == PaginationDirection.BACKWARDS) {
                        event.prevContent
                    } else {
                        event.content
                    }
                    roomMemberContentsByUser[event.stateKey] = contentToUse.toModel<RoomMemberContent>()
                }
                liveEventManager.get().dispatchPaginatedEventReceived(event, roomId)
                currentChunk.addTimelineEvent(
                        roomId = roomId,
                        eventEntity = eventEntity,
                        direction = direction,
                        roomMemberContentsByUser = roomMemberContentsByUser
                )
                if (lightweightSettingsStorage.areThreadMessagesEnabled()) {
                    eventEntity.rootThreadEventId?.let {
                        // This is a thread event
                        optimizedThreadSummaryMap[it] = eventEntity
                    } ?: run {
                        // This is a normal event or a root thread one
                        optimizedThreadSummaryMap[eventEntity.eventId] = eventEntity
                    }
                }
            }
        }
        val existingChunk = existingChunkToLink
        if (!hasNewEvents && existingChunk != null) {
            when (direction) {
                PaginationDirection.BACKWARDS -> {
                    Timber.i("Backwards insert chunk: ${existingChunk.identifier()} -> ${currentChunk.identifier()}")
                    currentChunk.prevChunk = existingChunk
                    existingChunk.nextChunk = currentChunk
                }
                PaginationDirection.FORWARDS  -> {
                    Timber.i("Forward insert chunk: ${currentChunk.identifier()} -> ${existingChunk.identifier()}")
                    currentChunk.nextChunk = existingChunk
                    existingChunk.prevChunk = currentChunk
                }

            }
        }
        if (currentChunk.isValid) {
            RoomEntity.where(realm, roomId).findFirst()?.addIfNecessary(currentChunk)
        }

        if (lightweightSettingsStorage.areThreadMessagesEnabled()) {
            optimizedThreadSummaryMap.updateThreadSummaryIfNeeded(
                    roomId = roomId,
                    realm = realm,
                    currentUserId = userId,
                    chunkEntity = currentChunk
            )
        }
    }
}
