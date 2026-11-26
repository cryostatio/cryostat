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
package io.cryostat;

import java.util.Map;

import io.cryostat.util.SemVer;

import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.Version;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.type.MapType;

@Singleton
public class ObjectMapperCustomization implements JsonMapperBuilderCustomizer {

    @Inject SemVer version;

    @Override
    public void customize(JsonMapper.Builder builder) {
        SimpleModule mapModule =
                new SimpleModule(
                        "MapSerialization",
                        new Version(
                                version.major(),
                                version.minor(),
                                version.patch(),
                                version.prerelease(),
                                "io.cryostat",
                                "cryostat"));

        mapModule.setSerializerModifier(new MapSerializerModifier());

        builder.addModule(mapModule);
    }

    static class MapSerializerModifier extends ValueSerializerModifier {
        @Override
        public ValueSerializer<?> modifyMapSerializer(
                SerializationConfig config,
                MapType valueType,
                BeanDescription.Supplier beanDesc,
                ValueSerializer<?> serializer) {
            if (valueType.getKeyType().getRawClass().equals(String.class)
                    && valueType.getContentType().getRawClass().equals(String.class)) {
                return new MapSerializer();
            }
            return serializer;
        }
    }

    static class MapSerializer extends ValueSerializer<Map<?, ?>> {

        @Override
        public void serialize(Map<?, ?> map, JsonGenerator gen, SerializationContext serializers) {
            gen.writeStartArray();

            for (var entry : map.entrySet()) {
                gen.writeStartObject();

                gen.writePOJOProperty("key", entry.getKey());
                gen.writePOJOProperty("value", entry.getValue());

                gen.writeEndObject();
            }

            gen.writeEndArray();
        }
    }
}
