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

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"templateName", "templateType"})})
public class EventTemplate extends PanacheEntity {

    @NotBlank public String templateName;

    @NotBlank public String templateType;

    @NotNull public Long uploadedAt;

    @Column public String provider;

    @Column public String description;

    public static EventTemplate of(
            String templateName, String templateType, String provider, String description) {
        EventTemplate template = new EventTemplate();
        template.templateName = templateName;
        template.templateType = templateType;
        template.provider = provider;
        template.description = description;
        template.uploadedAt = System.currentTimeMillis();
        return template;
    }
}
