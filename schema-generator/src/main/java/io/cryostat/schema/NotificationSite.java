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
package io.cryostat.schema;

import java.util.Map;

import com.github.javaparser.ast.expr.Expression;

/** Represents a location in the source code where a WebSocket notification is published. */
public class NotificationSite {
    private final String sourceFile;
    private final int lineNumber;
    private final Expression categoryExpression;
    private final Expression payloadExpression;

    private String resolvedCategory;
    private Map<String, Object> payloadSchema;

    public NotificationSite(
            String sourceFile,
            int lineNumber,
            Expression categoryExpression,
            Expression payloadExpression) {
        this.sourceFile = sourceFile;
        this.lineNumber = lineNumber;
        this.categoryExpression = categoryExpression;
        this.payloadExpression = payloadExpression;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public Expression getCategoryExpression() {
        return categoryExpression;
    }

    public Expression getPayloadExpression() {
        return payloadExpression;
    }

    public String getResolvedCategory() {
        return resolvedCategory;
    }

    public void setResolvedCategory(String resolvedCategory) {
        this.resolvedCategory = resolvedCategory;
    }

    public Map<String, Object> getPayloadSchema() {
        return payloadSchema;
    }

    public void setPayloadSchema(Map<String, Object> payloadSchema) {
        this.payloadSchema = payloadSchema;
    }

    @Override
    public String toString() {
        return String.format(
                "NotificationSite{file=%s, line=%d, category=%s}",
                sourceFile, lineNumber, resolvedCategory);
    }
}
