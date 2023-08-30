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
package io.cryostat.reports;

import java.io.IOException;
import java.util.Map;
import java.util.function.Predicate;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.recordings.ActiveRecording;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Singleton;

public interface ReportsService {
    Uni<Map<String, RuleEvaluation>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate);

    default Uni<Map<String, RuleEvaluation>> reportFor(ActiveRecording recording) {
        return reportFor(recording, r -> true);
    }

    Uni<Map<String, RuleEvaluation>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate);

    default Uni<Map<String, RuleEvaluation>> reportFor(String jvmId, String filename) {
        return reportFor(jvmId, filename, r -> true);
    }

    static String key(ActiveRecording recording) {
        return String.format("%s/%d", recording.target.jvmId, recording.remoteId);
    }

    static String key(String jvmId, String filename) {
        return String.format("%s/%s", jvmId, filename);
    }

    // FIXME remove this definition, just make the type from -core deserializable by Jackson
    public static record RuleEvaluation(
            double score, String name, String topic, String description) {
        public static RuleEvaluation from(
                io.cryostat.core.reports.InterruptibleReportGenerator.RuleEvaluation evaluation) {
            return new RuleEvaluation(
                    evaluation.getScore(),
                    evaluation.getName(),
                    evaluation.getTopic(),
                    evaluation.getDescription());
        }
    }

    @Singleton
    public static class ObjectMapperCustomization implements ObjectMapperCustomizer {
        @Override
        public void customize(ObjectMapper mapper) {
            var module = new SimpleModule();
            module.addDeserializer(RuleEvaluation.class, new RuleEvaluationDeserializer());
            mapper.registerModule(module);
        }
    }

    static class RuleEvaluationDeserializer extends JsonDeserializer<RuleEvaluation> {
        @Override
        public RuleEvaluation deserialize(JsonParser p, DeserializationContext ctx)
                throws IOException, JacksonException {
            JsonNode node = p.readValueAsTree();
            var score = node.get("score").asDouble(-1);
            var name = node.get("name").asText();
            var topic = node.get("topic").asText();
            var description = node.get("description").asText();
            return new RuleEvaluation(score, name, topic, description);
        }
    }
}
