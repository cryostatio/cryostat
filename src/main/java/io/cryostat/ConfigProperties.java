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

/** Java constants corresponding to configuration keys set in application.properties. */
public class ConfigProperties {
    public static final String STORAGE_METADATA_STORAGE_MODE = "storage.metadata.storage-mode";
    public static final String STORAGE_METADATA_ARCHIVES_STORAGE_MODE =
            "storage.metadata.archives.storage-mode";
    public static final String STORAGE_METADATA_EVENT_TEMPLATES_STORAGE_MODE =
            "storage.metadata.event-templates.storage-mode";
    public static final String AWS_BUCKET_NAME_ARCHIVES = "storage.buckets.archives.name";
    public static final String AWS_BUCKET_NAME_METADATA = "storage.buckets.metadata.name";
    public static final String AWS_BUCKET_NAME_EVENT_TEMPLATES =
            "storage.buckets.event-templates.name";
    public static final String AWS_BUCKET_NAME_PROBE_TEMPLATES =
            "storage.buckets.probe-templates.name";
    public static final String AWS_METADATA_PREFIX_RECORDINGS =
            "storage.metadata.prefix.recordings";
    public static final String AWS_METADATA_PREFIX_EVENT_TEMPLATES =
            "storage.metadata.prefix.event-templates";

    public static final String CONTAINERS_POLL_PERIOD = "cryostat.discovery.containers.poll-period";
    public static final String CONTAINERS_REQUEST_TIMEOUT =
            "cryostat.discovery.containers.request-timeout";

    public static final String CONNECTIONS_TTL = "cryostat.connections.ttl";
    public static final String CONNECTIONS_FAILED_BACKOFF = "cryostat.connections.failed-backoff";
    public static final String CONNECTIONS_FAILED_TIMEOUT = "cryostat.connections.failed-timeout";
    public static final String CONNECTIONS_UPLOAD_TIMEOUT = "cryostat.connections.upload-timeout";

    public static final String REPORTS_FILTER = "cryostat.services.reports.filter";
    public static final String REPORTS_SIDECAR_URL = "quarkus.rest-client.reports.url";
    public static final String REPORTS_USE_PRESIGNED_TRANSFER =
            "cryostat.services.reports.use-presigned-transfer";
    public static final String REPORTS_MEMORY_CACHE_ENABLED =
            "cryostat.services.reports.memory-cache.enabled";
    public static final String REPORTS_STORAGE_CACHE_ENABLED =
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

    public static final String CUSTOM_TEMPLATES_DIR = "templates-dir";
    public static final String PRESET_TEMPLATES_DIR = "preset-templates-dir";
    public static final String PROBE_TEMPLATES_DIR = "probe-templates-dir";
    public static final String SSL_TRUSTSTORE_DIR = "ssl.truststore.dir";
    public static final String RULES_DIR = "rules-dir";
    public static final String CREDENTIALS_DIR = "credentials-dir";

    public static final String URI_RANGE = "cryostat.target.uri-range";

    public static final String AGENT_TLS_REQUIRED = "cryostat.agent.tls.required";

    public static final String DECLARATIVE_CONFIG_RESOLVE_SYMLINKS =
            "cryostat.declarative-configuration.symlinks.resolve";
}
