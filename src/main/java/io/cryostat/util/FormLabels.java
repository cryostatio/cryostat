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
package io.cryostat.util;

import java.util.HashMap;
import java.util.Map;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

/**
 * Parsing for the {@code labels} form field accepted by the file upload endpoints (archived
 * recordings and heap dumps).
 */
public final class FormLabels {

    private FormLabels() {}

    /**
     * Parse the {@code labels} form field, which arrives as the raw text of a JSON object because
     * it is sent as a urlencoded form value. Values are stringified. A blank or absent field yields
     * no labels.
     *
     * @throws BadRequestException if the field is present but is not a JSON object
     */
    public static Map<String, String> parse(String rawLabels) {
        Map<String, String> labels = new HashMap<>();
        if (StringUtils.isBlank(rawLabels)) {
            return labels;
        }
        try {
            new JsonObject(rawLabels)
                    .getMap()
                    .forEach(
                            (k, v) -> {
                                if (v == null) {
                                    throw new BadRequestException(
                                            String.format("label \"%s\" must not be null", k));
                                }
                                labels.put(k, v.toString());
                            });
        } catch (DecodeException e) {
            throw new BadRequestException("labels must be a JSON object", e);
        }
        return labels;
    }
}
