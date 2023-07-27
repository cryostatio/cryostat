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
package io.cryostat.recordings;

import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;

import io.cryostat.core.RecordingOptionsCustomizer;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class RecordingsModule {

    @Produces
    @DefaultBean
    public RecordingOptionsBuilderFactory provideRecordingOptionsBuilderFactory(
            RecordingOptionsCustomizer customizer) {
        return service -> customizer.apply(new RecordingOptionsBuilder(service));
    }

    @Produces
    @DefaultBean
    public EventOptionsBuilder.Factory provideEventOptionsBuilderFactory() {
        Logger log = LoggerFactory.getLogger(EventOptionsBuilder.class);
        return new EventOptionsBuilder.Factory(log::debug);
    }

    @Produces
    @DefaultBean
    public RecordingOptionsCustomizer provideRecordingOptionsCustomizer() {
        Logger log = LoggerFactory.getLogger(RecordingOptionsCustomizer.class);
        return new RecordingOptionsCustomizer(log::debug);
    }
}
