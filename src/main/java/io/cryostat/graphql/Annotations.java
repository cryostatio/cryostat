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
package io.cryostat.graphql;

import java.util.List;
import java.util.Map;

import io.cryostat.discovery.KeyValue;
import io.cryostat.targets.Target;

import io.smallrye.graphql.api.Nullable;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class Annotations {

    public Map<String, String> cryostat(
            @Source Target.Annotations annotations, @Nullable List<String> key) {
        return KeyValue.mapFromList(
                annotations.cryostat().stream()
                        .filter(kv -> key == null || key.contains(kv.key()))
                        .toList());
    }

    public Map<String, String> platform(
            @Source Target.Annotations annotations, @Nullable List<String> key) {
        return KeyValue.mapFromList(
                annotations.platform().stream()
                        .filter(kv -> key == null || key.contains(kv.key()))
                        .toList());
    }
}
