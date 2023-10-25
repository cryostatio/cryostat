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

public class ConfigProperties {
    public static final String AWS_BUCKET_NAME_ARCHIVES = "storage.buckets.archives.name";
    public static final String AWS_EVENT_TEMPLATE_NAME = "storage.buckets.event-templates.name";
    public static final String AWS_OBJECT_EXPIRATION_LABELS =
            "storage.buckets.archives.expiration-label";

    public static final String CONTAINERS_POLL_PERIOD = "cryostat.discovery.containers.poll-period";
    public static final String CONTAINERS_REQUEST_TIMEOUT =
            "cryostat.discovery.containers.request-timeout";

    public static final String CONNECTIONS_MAX_OPEN = "cryostat.connections.max-open";
    public static final String CONNECTIONS_TTL = "cryostat.connections.ttl";
    public static final String CONNECTIONS_FAILED_BACKOFF = "cryostat.connections.failed-backoff";
    public static final String CONNECTIONS_FAILED_TIMEOUT = "cryostat.connections.failed-timeout";

    public static final String REPORTS_SIDECAR_URL = "cryostat.services.reports.url";
    public static final String MEMORY_CACHE_ENABLED =
            "cryostat.services.reports.memory-cache.enabled";
    public static final String STORAGE_CACHE_ENABLED =
            "cryostat.services.reports.storage-cache.enabled";
    public static final String ARCHIVED_REPORTS_STORAGE_CACHE_NAME =
            "cryostat.services.reports.storage-cache.name";
    public static final String ARCHIVED_REPORTS_EXPIRY_DURATION =
            "cryostat.services.reports.storage-cache.expiry-duration";

    public static final String GRAFANA_DASHBOARD_URL = "grafana-dashboard.url";
    public static final String GRAFANA_DASHBOARD_EXT_URL = "grafana-dashboard-ext.url";
    public static final String GRAFANA_DATASOURCE_URL = "grafana-datasource.url";

    public static final String STORAGE_EXT_URL = "storage-ext.url";
    public static final String STORAGE_PRESIGNED_DOWNLOADS_ENABLED =
            "storage.presigned-downloads.enabled";
    public static final String STORAGE_TRANSIENT_ARCHIVES_ENABLED =
            "storage.transient-archives.enabled";
    public static final String STORAGE_TRANSIENT_ARCHIVES_TTL = "storage.transient-archives.ttl";
}
