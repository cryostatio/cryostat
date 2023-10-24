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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings("EI_EXPOSE_REP")
public record SerializableEventTypeInfo(
        String name,
        String typeId,
        String description,
        String[] category,
        Map<String, SerializableOptionDescriptor> options) {

    public SerializableEventTypeInfo {
        Objects.requireNonNull(name);
        Objects.requireNonNull(typeId);
        if (description == null) {
            description = "";
        }
        if (category == null) {
            category = new String[0];
        }
        if (options == null) {
            options = Collections.emptyMap();
        }
    }

    public static SerializableEventTypeInfo fromEventTypeInfo(IEventTypeInfo info) {
        var name = info.getName();
        var typeId = info.getEventTypeID().getFullKey();
        var description = info.getDescription();
        var category = info.getHierarchicalCategory();

        Map<String, ? extends IOptionDescriptor<?>> origOptions = info.getOptionDescriptors();
        Map<String, SerializableOptionDescriptor> options = new HashMap<>(origOptions.size());
        for (Map.Entry<String, ? extends IOptionDescriptor<?>> entry : origOptions.entrySet()) {
            options.put(
                    entry.getKey(),
                    SerializableOptionDescriptor.fromOptionDescriptor(entry.getValue()));
        }

        return new SerializableEventTypeInfo(name, typeId, description, category, options);
    }
}
