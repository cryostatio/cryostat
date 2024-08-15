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
package io.cryostat.discovery;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record KeyValue(String key, String value) implements Comparable<KeyValue> {

    public KeyValue {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
    }

    public KeyValue(KeyValue other) {
        this(other.key(), other.value());
    }

    public KeyValue(Map.Entry<String, String> entry) {
        this(entry.getKey(), entry.getValue());
    }

    public static List<KeyValue> listFromMap(Map<String, String> map) {
        return map.entrySet().stream().map(KeyValue::new).toList();
    }

    public static Map<String, String> mapFromList(List<KeyValue> list) {
        return list.stream().sorted().collect(Collectors.toMap(KeyValue::key, KeyValue::value));
    }

    @Override
    public int compareTo(KeyValue o) {
        return Comparator.comparing(KeyValue::key)
                .thenComparing(Comparator.comparing(KeyValue::value))
                .compare(this, o);
    }
}
