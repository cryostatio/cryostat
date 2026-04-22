/*
 * Copyright The Cryostat Authors.
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
package io.cryostat.events;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.jboss.logging.Logger;

/**
 * Utility class for checking which entity properties have been modified (dirty) in the current
 * Hibernate session. This is used to determine whether entity updates should trigger WebSocket
 * notifications based on which specific fields were changed.
 *
 * <p>This class provides methods to:
 *
 * <ul>
 *   <li>Check if any notification-relevant properties are dirty using Jackson annotation reflection
 *   <li>Get all dirty property names for debugging purposes
 * </ul>
 *
 * <p>The dirty-checking mechanism uses Hibernate's internal APIs to access the entity's dirty state
 * metadata from the persistence context. Fields are considered notification-relevant unless they
 * are marked with {@code @JsonIgnore} or {@code @JsonProperty(access =
 * JsonProperty.Access.WRITE_ONLY)}. This approach automatically adapts to field additions,
 * removals, and renames without requiring manual updates to property name lists.
 */
@ApplicationScoped
public class NotificationDirtyCheckSupport {

    @Inject EntityManager entityManager;
    @Inject Logger logger;

    /**
     * Checks if any notification-relevant properties are dirty (modified) for the given entity. A
     * property is considered notification-relevant unless it is marked with {@code @JsonIgnore} or
     * {@code @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)}.
     *
     * @param entity the entity to check for dirty properties
     * @return true if any notification-relevant property is dirty, false otherwise or if dirty
     *     checking is unavailable
     */
    public boolean hasRelevantDirtyProperties(Object entity) {
        Set<String> dirtyProperties = getDirtyPropertyNames(entity);

        if (dirtyProperties.isEmpty()) {
            return false;
        }

        Set<String> ignoredProperties = getIgnoredPropertyNames(entity.getClass());
        for (String dirtyProperty : dirtyProperties) {
            if (!ignoredProperties.contains(dirtyProperty)) {
                logger.tracev(
                        "Entity {0} has notification-relevant dirty property: {1}",
                        entity.getClass().getSimpleName(), dirtyProperty);
                return true;
            }
        }

        logger.tracev(
                "Entity {0} has dirty properties {1} but none are notification-relevant (all"
                        + " ignored: {2})",
                entity.getClass().getSimpleName(), dirtyProperties, ignoredProperties);
        return false;
    }

    /**
     * Gets the set of property names that should be ignored for notification purposes. Properties
     * are ignored if they have {@code @JsonIgnore} or {@code @JsonProperty(access =
     * JsonProperty.Access.WRITE_ONLY)} annotations.
     *
     * @param entityClass the entity class to inspect
     * @return set of property names to ignore for notifications
     */
    private Set<String> getIgnoredPropertyNames(Class<?> entityClass) {
        Set<String> ignoredProperties = new HashSet<>();

        // Inspect all declared fields in the entity class
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(JsonIgnore.class)) {
                ignoredProperties.add(field.getName());
                continue;
            }

            JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);
            if (jsonProperty != null && jsonProperty.access() == JsonProperty.Access.WRITE_ONLY) {
                ignoredProperties.add(field.getName());
            }
        }

        return ignoredProperties;
    }

    /**
     * Gets all dirty (modified) property names for the given entity. This is useful for debugging
     * and logging purposes.
     *
     * @param entity the entity to check for dirty properties
     * @return set of dirty property names, or empty set if entity is not managed or dirty checking
     *     is unavailable
     */
    public Set<String> getDirtyPropertyNames(Object entity) {
        try {
            SessionImplementor session = entityManager.unwrap(SessionImplementor.class);

            EntityEntry entityEntry = session.getPersistenceContext().getEntry(entity);

            if (entityEntry == null) {
                logger.debugv(
                        "Entity {0} is not managed, cannot determine dirty properties",
                        entity.getClass().getSimpleName());
                return Collections.emptySet();
            }

            EntityPersister persister = entityEntry.getPersister();
            String[] propertyNames = persister.getPropertyNames();

            // Get current and loaded state to determine which properties are dirty
            Object[] currentState = persister.getValues(entity);
            Object[] loadedState = entityEntry.getLoadedState();

            if (loadedState == null) {
                // No loaded state available (e.g., new entity)
                return Collections.emptySet();
            }

            // Build set of dirty property names by comparing current and loaded state
            Set<String> dirtyProperties = new HashSet<>();
            for (int i = 0;
                    i < propertyNames.length && i < currentState.length && i < loadedState.length;
                    i++) {
                Object currentValue = currentState[i];
                Object loadedValue = loadedState[i];

                // Check if the property value has changed
                boolean isDirty =
                        (currentValue == null && loadedValue != null)
                                || (currentValue != null && !currentValue.equals(loadedValue));

                if (isDirty) {
                    dirtyProperties.add(propertyNames[i]);
                }
            }

            return dirtyProperties;

        } catch (Exception e) {
            logger.warnv(
                    e,
                    "Failed to determine dirty properties for entity {0}",
                    entity.getClass().getSimpleName());
            return Collections.emptySet();
        }
    }
}
