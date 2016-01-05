/*
 *  Copyright 2015 esbtools Contributors and/or its affiliates.
 *
 *  This file is part of esbtools.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.esbtools.eventhandler.lightblue;

import com.redhat.lightblue.client.LightblueClient;
import com.redhat.lightblue.client.Locking;
import com.redhat.lightblue.client.request.DataBulkRequest;
import com.redhat.lightblue.client.response.LightblueBulkDataResponse;
import com.redhat.lightblue.client.response.LightblueDataResponse;
import com.redhat.lightblue.client.response.LightblueException;

import org.esbtools.eventhandler.DocumentEvent;
import org.esbtools.eventhandler.EventHandlerException;
import org.esbtools.eventhandler.EventRepository;
import org.esbtools.eventhandler.FailedDocumentEvent;
import org.esbtools.eventhandler.FailedNotification;
import org.esbtools.eventhandler.Notification;
import org.esbtools.eventhandler.NotificationRepository;
import org.esbtools.eventhandler.lightblue.model.DocumentEventEntity;
import org.esbtools.lightbluenotificationhook.NotificationEntity;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

// TODO: Doesn't have to be same class for this
public class LightblueEventRepository implements EventRepository, NotificationRepository {
    private final LightblueClient lightblue;
    private final String[] entities;

    /**
     * The amount of document events to sort through for optimizations at one time.
     *
     * <p>This should be much larger than that actual number of events you want to publish at one
     * time due to optimizations that reduce the actual number of publishable events. For example,
     * in a batch of 100 events, if 50 of them are redundant or able to be merged, you'll end up
     * with only 50 discrete events. If you only grabbed 50, you might miss some of those potential
     * optimizations.
     *
     * <p>Events not included to be published among the batch (not including events that were merged
     * or superseded), should be put back in the document event entity pool to be processed later.
     */
    private final int documentEventBatchSize;
    private final Locking locking;
    private final NotificationFactory notificationFactory;
    private final DocumentEventFactory documentEventFactory;
    private final Clock clock;

    public LightblueEventRepository(LightblueClient lightblue, String[] entities,
            int documentEventBatchSize, String lockingDomain,
            NotificationFactory notificationFactory, DocumentEventFactory documentEventFactory,
            Clock clock) {
        this.lightblue = lightblue;
        this.entities = entities;
        this.documentEventBatchSize = documentEventBatchSize;
        this.notificationFactory = notificationFactory;
        this.documentEventFactory = documentEventFactory;
        this.clock = clock;

        locking = lightblue.getLocking(lockingDomain);
    }

    @Override
    public List<Notification> retrieveOldestNotificationsUpTo(int maxEvents)
            throws LightblueException {
        try {
            // TODO: Either block until lock acquired or throw exception and let caller poll
            // TODO: What happens if app dies before lock release? Do we need TTL and ping?
            blockUntilLockAcquired(Locks.forNotificationsForEntities(entities));

            BulkLightblueRequester requester = new BulkLightblueRequester(lightblue);

            NotificationEntity[] notificationEntities = lightblue
                    .data(FindRequests.newNotificationsForEntitiesUpTo(entities, maxEvents))
                    .parseProcessed(NotificationEntity[].class);

            lightblue.data(UpdateRequests.notificationsAsProcessing(notificationEntities));

            return Arrays.stream(notificationEntities)
                    .map(entity -> notificationFactory.getNotificationForEntity(entity, requester))
                    .collect(Collectors.toList());
        } finally {
            locking.release(Locks.forNotificationsForEntities(entities));
        }
    }

    @Override
    public void markNotificationsProcessedOrFailed(Collection<Notification> notification,
            Collection<FailedNotification> failures) throws Exception {
        List<NotificationEntity> processedNotificationEntities = notification.stream()
                .map(LightblueEventRepository::asEntity)
                .collect(Collectors.toList());

        // TODO: Add field in NotificationEntity for failure messages?
        List<NotificationEntity> failedNotificationEntities = failures.stream()
                .map(FailedNotification::notification)
                .map(LightblueEventRepository::asEntity)
                .collect(Collectors.toList());

        DataBulkRequest markNotifications = new DataBulkRequest();
        markNotifications.add(UpdateRequests.processingNotificationsAsProcessed(processedNotificationEntities));
        markNotifications.add(UpdateRequests.processingNotificationsAsFailed(failedNotificationEntities));

        lightblue.bulkData(markNotifications);
    }

    @Override
    public void addNewDocumentEvents(Collection<? extends DocumentEvent> documentEvents)
            throws LightblueException {
        List<DocumentEventEntity> documentEventEntities = documentEvents.stream()
                .map(LightblueEventRepository::asEntity)
                .collect(Collectors.toList());

        lightblue.data(InsertRequests.documentEventsReturningOnlyIds(documentEventEntities));
    }

    @Override
    public List<LightblueDocumentEvent> retrievePriorityDocumentEventsUpTo(int maxEvents)
            throws LightblueException {
        try {
            blockUntilLockAcquired(Locks.forDocumentEventsForEntities(entities));

            DocumentEventEntity[] documentEventEntities = lightblue
                    .data(FindRequests.priorityDocumentEventsForEntitiesUpTo(entities, documentEventBatchSize))
                    .parseProcessed(DocumentEventEntity[].class);

            List<DocumentEventEntity> entitiesToUpdate = new ArrayList<>();
            BulkLightblueRequester requester = new BulkLightblueRequester(lightblue);

            List<LightblueDocumentEvent> optimized = parseAndOptimizeDocumentEventEntitiesUpTo(
                    maxEvents, documentEventEntities, entitiesToUpdate, requester);

            persistNewEntitiesAndStatusUpdatesToExisting(entitiesToUpdate, optimized);

            return optimized;
        } finally {
            locking.release(Locks.forDocumentEventsForEntities(entities));
        }
    }

    @Override
    public void markDocumentEventsProcessedOrFailed(Collection<? extends DocumentEvent> documentEvents,
            Collection<FailedDocumentEvent> failures) throws Exception {
        List<DocumentEventEntity> processed = documentEvents.stream()
                .map(LightblueEventRepository::asEntity)
                .collect(Collectors.toList());

        // TODO: Add field on document event entity to store error messages?
        List<DocumentEventEntity> failed = failures.stream()
                .map(FailedDocumentEvent::documentEvent)
                .map(LightblueEventRepository::asEntity)
                .collect(Collectors.toList());

        DataBulkRequest markDocumentEvents = new DataBulkRequest();
        markDocumentEvents.add(UpdateRequests.processingDocumentEventsAsProcessed(processed));
        markDocumentEvents.add(UpdateRequests.processingDocumentEventsAsFailed(failed));

        lightblue.data(markDocumentEvents);
    }

    private void blockUntilLockAcquired(String resourceId) throws LightblueException {
        while (!locking.acquire(resourceId)) {
            try {
                // TODO: Parameterize lock polling interval
                // Or can we do lock call that only returns once lock is available?
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                // Just try again...
            }
        }
    }

    /**
     * Creates {@link LightblueDocumentEvent}s from entities using {@link #documentEventFactory},
     * optimizes away superseded and merge-able events, and populates {@code entitiesToUpdate} with
     * entity status updates that should be persisted to the document event entity collection
     * before releasing locks.
     *
     * <p>The returned list may include net-new events as the result of merges. These events do not
     * yet have an associated <em>persisted</em> entity, and therefore have no id's.
     *
     * @param maxEvents The maximum number of parsed document events to return. Not to be confused
     *                  with {@link #documentEventBatchSize}.
     * @param documentEventEntities The priority-first entities from lightblue.
     * @param entitiesToUpdate A mutable collection of entities who's status modifications should be
     *                         persisted back into lightblue.
     * @param requester Used by {@link LightblueDocumentEvent} implementations to lookup their
     *                  corresponding documents.
     * @return An optimized list of parsed or merged document events ready to be published. No more
     * than {@code maxEvents} will be returned. List may include events with newly computed entities
     * that are not yet persisted.
     */
    private List<LightblueDocumentEvent> parseAndOptimizeDocumentEventEntitiesUpTo(int maxEvents,
            DocumentEventEntity[] documentEventEntities, List<DocumentEventEntity> entitiesToUpdate,
            LightblueRequester requester) {
        List<LightblueDocumentEvent> optimized = new ArrayList<>(maxEvents);

        for (DocumentEventEntity newEventEntity : documentEventEntities) {
            LightblueDocumentEvent newEvent = documentEventFactory
                    .getDocumentEventForEntity(newEventEntity, requester);

            Iterator<LightblueDocumentEvent> optimizedIterator = optimized.iterator();

            // We have a new event, let's see if it is superseded by or can be merged with any
            // previous events we parsed or created as a result of a previous merge.
            while (optimizedIterator.hasNext()) {
                LightblueDocumentEvent previouslyOptimizedEvent = optimizedIterator.next();

                if (newEvent.isSupersededBy(previouslyOptimizedEvent)) {
                    // Keep previous event...
                    DocumentEventEntity previousEntity = previouslyOptimizedEvent.wrappedDocumentEventEntity();
                    previousEntity.addSurvivorOfIds(newEventEntity.getSurvivorOfIds());
                    previousEntity.addSurvivorOfIds(newEventEntity.get_id());

                    // ...and throw away this new one.
                    newEventEntity.setStatus(DocumentEventEntity.Status.superseded);
                    newEventEntity.setProcessedDate(ZonedDateTime.now(clock));
                    entitiesToUpdate.add(newEventEntity);

                    newEvent = null;
                    break;
                } else if (newEvent.couldMergeWith(previouslyOptimizedEvent)) {
                    // Previous entity was processing; now it is merged and removed from optimized
                    // result list.
                    DocumentEventEntity previousEntity = previouslyOptimizedEvent.wrappedDocumentEventEntity();
                    previousEntity.setStatus(DocumentEventEntity.Status.merged);
                    previousEntity.setProcessedDate(ZonedDateTime.now(clock));
                    optimizedIterator.remove();

                    // This new event will not be included in result list, but we do have to update
                    // its entity to store that it has been merged.
                    newEventEntity.setStatus(DocumentEventEntity.Status.merged);
                    newEventEntity.setProcessedDate(ZonedDateTime.now(clock));
                    entitiesToUpdate.add(newEventEntity);

                    // We create a new event as a result of the merger, and keep this instead of the
                    // others.
                    LightblueDocumentEvent merger = newEvent.merge(previouslyOptimizedEvent);
                    DocumentEventEntity mergerEntity = merger.wrappedDocumentEventEntity();
                    mergerEntity.addSurvivorOfIds(previousEntity.getSurvivorOfIds());
                    mergerEntity.addSurvivorOfIds(newEventEntity.getSurvivorOfIds());
                    if (previousEntity.get_id() != null) {
                        mergerEntity.addSurvivorOfIds(previousEntity.get_id());
                    }
                    if (newEventEntity.get_id() != null) {
                        mergerEntity.addSurvivorOfIds(newEventEntity.get_id());
                    }

                    newEvent = merger;
                    newEventEntity = mergerEntity;
                }
            }

            // Only add the event if we've not hit our limit. We should keep going however even if
            // we have hit our limit, because some of the remaining events may be superseded or
            // merged as a part of the current optimized batch.
            if (newEvent != null && optimized.size() < maxEvents) {
                newEventEntity.setStatus(DocumentEventEntity.Status.processing);
                entitiesToUpdate.add(newEventEntity);
                optimized.add(newEvent);
            }
        }

        return optimized;
    }

    /**
     * Updates event status, processing date, and survivor ids for given event entities. Persists
     * new event entities among document event list, and updates entities for those events with
     * persisted ids.
     *
     * @param entitiesToUpdate List of existing entities which have status updates.
     * @param maybeNewEvents List of document events which may have yet-to-be-persisted entities. These
     *                  entities will be persisted and ids retrieved to mutate the events in this
     *                  list with those ids.
     * @throws LightblueException
     */
    private void persistNewEntitiesAndStatusUpdatesToExisting(
            List<DocumentEventEntity> entitiesToUpdate,
            List<LightblueDocumentEvent> maybeNewEvents) throws LightblueException {
        DataBulkRequest insertAndUpdateEvents = new DataBulkRequest();
        List<LightblueDocumentEvent> newEvents = new ArrayList<>();

        // Add requests to plop in new entities in order.
        for (LightblueDocumentEvent event : maybeNewEvents) {
            DocumentEventEntity entity = event.wrappedDocumentEventEntity();
            if (entity.get_id() == null) {
                newEvents.add(event);
                insertAndUpdateEvents.add(InsertRequests.documentEventsReturningOnlyIds(entity));
            }
        }

        insertAndUpdateEvents.addAll(UpdateRequests.documentEventsStatusAndProcessedDate(entitiesToUpdate));

        // TODO: Verify these were all successful
        LightblueBulkDataResponse bulkResponse = lightblue.bulkData(insertAndUpdateEvents);
        List<LightblueDataResponse> responses = bulkResponse.getResponses();

        // Read responses for new entities in order, update corresponding events' entities with
        // new ids.
        for (int i = 0; i < newEvents.size(); i++) {
            LightblueDataResponse response = responses.get(i);
            LightblueDocumentEvent newEvent = newEvents.get(i);
            DocumentEventEntity newEntity = response.parseProcessed(DocumentEventEntity.class);
            newEvent.wrappedDocumentEventEntity().set_id(newEntity.get_id());
        }
    }

    private static NotificationEntity asEntity(Notification notification) {
        if (notification instanceof LightblueNotification) {
            return ((LightblueNotification) notification).wrappedNotificationEntity();
        }

        throw new EventHandlerException("Unknown notification type. Only LightblueNotification " +
                "are supported. Event type was: " + notification.getClass());
    }

    private static DocumentEventEntity asEntity(DocumentEvent event) {
        if (event instanceof LightblueDocumentEvent) {
            return ((LightblueDocumentEvent) event).wrappedDocumentEventEntity();
        }

        throw new EventHandlerException("Unknown event type. Only LightblueDocumentEvent are " +
                "supported. Event type was: " + event.getClass());
    }
}
