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
package io.cryostat.ws.notifications;

import java.util.Objects;

/**
 * Notification payload record types for WebSocket notifications. These records represent the
 * message payloads sent in WebSocket notifications.
 */
public final class NotificationPayloads {

    private NotificationPayloads() {}

    public record JobIdPayload(String jobId) {
        public JobIdPayload {
            Objects.requireNonNull(jobId);
        }
    }

    public record HeapDumpSuccessPayload(String jobId, String targetAlias) {
        public HeapDumpSuccessPayload {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(targetAlias);
        }
    }

    public record TemplatePayload(String template) {
        public TemplatePayload {
            Objects.requireNonNull(template);
        }
    }

    public record ProbeTemplatePayload(String probeTemplate) {
        public ProbeTemplatePayload {
            Objects.requireNonNull(probeTemplate);
        }
    }

    public record ThreadDumpFailurePayload(String jobId, long targetId) {
        public ThreadDumpFailurePayload {
            Objects.requireNonNull(jobId);
        }
    }

    public record ReportSuccessPayload(String jobId, String jvmId) {
        public ReportSuccessPayload {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(jvmId);
        }
    }

    public record ArchiveRecordingSuccessPayload(
            String jobId, String recording, String reportUrl, String downloadUrl) {
        public ArchiveRecordingSuccessPayload {
            Objects.requireNonNull(jobId);
        }
    }

    public record ProbeTemplateAppliedPayload(String jvmId, String probeTemplate) {
        public ProbeTemplateAppliedPayload {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(probeTemplate);
        }
    }

    public record ProbesRemovedPayload(String jvmId, String target) {
        public ProbesRemovedPayload {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(target);
        }
    }

    public record ProbeTemplateUploadedPayload(String probeTemplate, String templateContent) {
        public ProbeTemplateUploadedPayload {
            Objects.requireNonNull(probeTemplate);
            Objects.requireNonNull(templateContent);
        }
    }
}
