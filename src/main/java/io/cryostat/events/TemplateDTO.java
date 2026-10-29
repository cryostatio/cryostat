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

import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record TemplateDTO(
        @NotBlank(message = "Template name must not be blank")
                @Schema(description = "The template name", example = "My_Template")
                String name,
        @NotNull(message = "Template description must not be null")
                @Schema(description = "The template description", example = "A sample template")
                String description,
        @NotBlank(message = "Template provider must not be blank")
                @Schema(description = "The template provider", example = "MyOrg")
                String provider,
        @NotNull @Schema(description = "The template type") TemplateType type) {

    public TemplateDTO(Template template) {
        this(
                template.getName(),
                template.getDescription(),
                template.getProvider(),
                template.getType());
    }

    public Template toTemplate() {
        return new Template(name, description, provider, type);
    }
}
