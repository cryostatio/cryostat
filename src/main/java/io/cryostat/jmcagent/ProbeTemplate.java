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
package io.cryostat.jmcagent;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"templateName"})})
public class ProbeTemplate extends PanacheEntity {

    @NotBlank public String templateName;

    @NotNull public Long uploadedAt;

    public static ProbeTemplate of(String templateName) {
        ProbeTemplate template = new ProbeTemplate();
        template.templateName = templateName;
        template.uploadedAt = System.currentTimeMillis();
        return template;
    }
}
