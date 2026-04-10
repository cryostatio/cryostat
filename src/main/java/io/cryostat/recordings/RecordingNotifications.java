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

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.recordings.ActiveRecordings.LinkedRecordingDescriptor;
import io.cryostat.targets.Target;

public final class RecordingNotifications {

    private RecordingNotifications() {}

    public record ActiveRecordingNotification(
            ActiveRecordings.RecordingEventCategory category, Payload payload) {
        public ActiveRecordingNotification {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(String target, LinkedRecordingDescriptor recording, String jvmId) {
            public Payload {
                Objects.requireNonNull(target);
                Objects.requireNonNull(recording);
            }

            public static Payload of(RecordingHelper helper, ActiveRecording recording) {
                return new Payload(
                        recording.target.connectUrl.toString(),
                        helper.toExternalForm(recording),
                        recording.target.jvmId);
            }
        }
    }

    public record ArchivedRecordingNotification(
            ActiveRecordings.RecordingEventCategory category, Payload payload) {
        public ArchivedRecordingNotification {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(
                String target, String jvmId, ArchivedRecordings.ArchivedRecording recording) {
            public Payload {
                Objects.requireNonNull(recording);
            }

            public static Payload of(
                    URI connectUrl, ArchivedRecordings.ArchivedRecording recording) {
                return new Payload(
                        Optional.ofNullable(connectUrl).map(URI::toString).orElse(null),
                        Optional.ofNullable(connectUrl)
                                .flatMap(
                                        url ->
                                                Target.find("connectUrl", url)
                                                        .<Target>singleResultOptional())
                                .map(t -> t.jvmId)
                                .orElse(null),
                        recording);
            }
        }
    }
}
