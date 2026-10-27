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
    public enum StorageResourceType {
        ARCHIVED_RECORDINGS("archived-recordings"),
        HEAP_DUMPS("heap-dumps"),
        THREAD_DUMPS("thread-dumps"),
        UNIFIED_LOGS("unified-logs"),
        ASYNC_PROFILER("async-profiler"),
        EVENT_TEMPLATES("event-templates"),
        JMC_PROBE_TEMPLATES("jmc-probe-templates"),
        ARCHIVED_REPORTS("archived-reports"),
        HEAP_DUMP_REPORTS("heap-dump-reports");

        private final String id;

        StorageResourceType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    // Storage configuration prefixes
    public static final String STORAGE_BUCKETS_PREFIX = "storage.buckets";
    public static final String STORAGE_DIRECTORIES_PREFIX = "storage.directories";
    public static final String STORAGE_DIRECTORIES_METADATA_PREFIX = "storage.directories.metadata";

    public static final String EXTERNAL_RECORDINGS_DELAY = "cryostat.external-recordings.delay";
    public static final String EXTERNAL_RECORDINGS_ARCHIVE = "cryostat.external-recordings.archive";
    public static final String EXTERNAL_RECORDINGS_AUTOANALYZE =
            "cryostat.external-recordings.autoanalyze";

    public static final String STORAGE_METADATA_STORAGE_MODE = "storage.metadata.storage-mode";
    public static final String STORAGE_METADATA_ARCHIVES_STORAGE_MODE =
            "storage.metadata.archives.storage-mode";
    public static final String STORAGE_METADATA_EVENT_TEMPLATES_STORAGE_MODE =
            "storage.metadata.event-templates.storage-mode";
    public static final String STORAGE_METADATA_HEAP_DUMPS_STORAGE_MODE =
            "storage.metadata.heap-dumps.storage-mode";
    public static final String STORAGE_METADATA_THREAD_DUMPS_STORAGE_MODE =
            "storage.metadata.thread-dumps.storage-mode";
    public static final String STORAGE_METADATA_UNIFIED_LOGS_STORAGE_MODE =
            "storage.metadata.unified-logs.storage-mode";
    public static final String STORAGE_BUCKETS_ARCHIVED_RECORDINGS_NAME =
            STORAGE_BUCKETS_PREFIX + ".archived-recordings.name";
    public static final String STORAGE_BUCKETS_HEAP_DUMPS_NAME =
            STORAGE_BUCKETS_PREFIX + ".heap-dumps.name";
    public static final String STORAGE_BUCKETS_THREAD_DUMPS_NAME =
            STORAGE_BUCKETS_PREFIX + ".thread-dumps.name";
    public static final String STORAGE_BUCKETS_UNIFIED_LOGS_NAME =
            STORAGE_BUCKETS_PREFIX + ".unified-logs.name";
    public static final String STORAGE_BUCKETS_ASYNC_PROFILER_NAME =
            STORAGE_BUCKETS_PREFIX + ".async-profiler.name";
    public static final String STORAGE_BUCKETS_EVENT_TEMPLATES_NAME =
            STORAGE_BUCKETS_PREFIX + ".event-templates.name";
    public static final String STORAGE_BUCKETS_JMC_PROBE_TEMPLATES_NAME =
            STORAGE_BUCKETS_PREFIX + ".jmc-probe-templates.name";
    public static final String STORAGE_BUCKETS_CONFIG_NAME =
            STORAGE_BUCKETS_PREFIX + ".config.name";
    public static final String STORAGE_BUCKETS_METADATA_NAME =
            STORAGE_BUCKETS_PREFIX + ".metadata.name";
    public static final String STORAGE_BUCKETS_ARCHIVED_REPORTS_NAME =
            STORAGE_BUCKETS_PREFIX + ".archived-reports.name";
    public static final String STORAGE_BUCKETS_HEAP_DUMP_REPORTS_NAME =
            STORAGE_BUCKETS_PREFIX + ".heap-dump-reports.name";

    public static final String STORAGE_DIRECTORIES_ARCHIVED_RECORDINGS_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".archived-recordings.name";
    public static final String STORAGE_DIRECTORIES_HEAP_DUMPS_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".heap-dumps.name";
    public static final String STORAGE_DIRECTORIES_THREAD_DUMPS_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".thread-dumps.name";
    public static final String STORAGE_DIRECTORIES_UNIFIED_LOGS_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".unified-logs.name";
    public static final String STORAGE_DIRECTORIES_ASYNC_PROFILER_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".async-profiler.name";
    public static final String STORAGE_DIRECTORIES_EVENT_TEMPLATES_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".event-templates.name";
    public static final String STORAGE_DIRECTORIES_JMC_PROBE_TEMPLATES_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".jmc-probe-templates.name";

    public static final String STORAGE_DIRECTORIES_METADATA_ARCHIVED_RECORDINGS_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".archived-recordings.name";
    public static final String STORAGE_DIRECTORIES_METADATA_HEAP_DUMPS_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".heap-dumps.name";
    public static final String STORAGE_DIRECTORIES_METADATA_THREAD_DUMPS_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".thread-dumps.name";
    public static final String STORAGE_DIRECTORIES_METADATA_UNIFIED_LOGS_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".unified-logs.name";
    public static final String STORAGE_DIRECTORIES_METADATA_EVENT_TEMPLATES_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".event-templates.name";
    public static final String STORAGE_DIRECTORIES_METADATA_JMC_PROBE_TEMPLATES_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".jmc-probe-templates.name";
    public static final String STORAGE_DIRECTORIES_METADATA_ARCHIVED_REPORTS_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".archived-reports.name";
    public static final String STORAGE_DIRECTORIES_METADATA_HEAP_DUMP_REPORTS_NAME =
            STORAGE_DIRECTORIES_METADATA_PREFIX + ".heap-dump-reports.name";

    public static final String STORAGE_DIRECTORIES_ARCHIVED_REPORTS_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".archived-reports.name";
    public static final String STORAGE_DIRECTORIES_HEAP_DUMP_REPORTS_NAME =
            STORAGE_DIRECTORIES_PREFIX + ".heap-dump-reports.name";

    public static final String DISCOVERY_IPV6_ENABLED = "cryostat.discovery.ipv6-enabled";
    public static final String DISCOVERY_IPV4_DNS_TRANSFORM_ENABLED =
            "cryostat.discovery.kubernetes.ipv4.dns-transform.enabled";
    public static final String CONTAINERS_POLL_PERIOD = "cryostat.discovery.containers.poll-period";
    public static final String CONTAINERS_REQUEST_TIMEOUT =
            "cryostat.discovery.containers.request-timeout";
    public static final String DISCOVERY_PLUGINS_MAX_FAILURES =
            "cryostat.discovery.plugins.max-failures";
    public static final String DISCOVERY_PLUGINS_PING_PERIOD =
            "cryostat.discovery.plugins.ping-period";
    public static final String DISCOVERY_PLUGINS_MAX_BACKOFF_MULTIPLIER =
            "cryostat.discovery.plugins.max-backoff-multiplier";
    public static final String DISCOVERY_PLUGINS_PING_WORKER_POOL_SIZE =
            "cryostat.discovery.plugins.ping.worker-pool-size";
    public static final String DISCOVERY_PLUGINS_PING_DELAY_MS =
            "cryostat.discovery.plugins.ping.delay-ms";
    public static final String DISCOVERY_PLUGINS_PING_TIMEOUT_MS =
            "cryostat.discovery.plugins.ping.timeout-ms";
    public static final String DISCOVERY_PLUGINS_PING_STARTUP_GRACE_PERIOD_MS =
            "cryostat.discovery.plugins.ping.startup-grace-period-ms";

    public static final String CONNECTIONS_TTL = "cryostat.connections.ttl";
    public static final String CONNECTIONS_FAILED_BACKOFF = "cryostat.connections.failed-backoff";
    public static final String CONNECTIONS_FAILED_TIMEOUT = "cryostat.connections.failed-timeout";
    public static final String CONNECTIONS_UPLOAD_TIMEOUT = "cryostat.connections.upload-timeout";

    public static final String CONNECTIONS_HEALTH_TIMEOUT =
            "cryostat.services.health-check.timeout";
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
    public static final String HEAP_DUMP_REPORTS_MEMORY_CACHE_ENABLED =
            "cryostat.services.heap-dump.reports.memory-cache.enabled";
    public static final String HEAP_DUMP_REPORTS_STORAGE_CACHE_ENABLED =
            "cryostat.services.heap-dump.reports.storage-cache.enabled";
    public static final String ARCHIVED_HEAP_DUMP_REPORTS_STORAGE_CACHE_NAME =
            "cryostat.services.heap-dump.reports.storage-cache.name";
    public static final String ARCHIVED_HEAP_DUMP_REPORTS_EXPIRY_DURATION =
            "cryostat.services.heap-dump.reports.storage-cache.expiry-duration";
    public static final String HEAP_DUMP_ANALYSIS_MEMORY_LIMIT =
            "cryostat.services.heap-dump.reports.memory-limit";
    public static final String JFR_DATASOURCE_USE_PRESIGNED_TRANSFER =
            "cryostat.services.jfr-datasource.use-presigned-transfer";
    public static final String JFR_ANALYSIS_CACHE_MAX_WEIGHT =
            "cryostat.jfr-analysis.cache.max-weight";
    public static final String JFR_ANALYSIS_CACHE_TTL = "cryostat.jfr-analysis.cache.ttl";

    public static final String GRAFANA_DASHBOARD_URL = "grafana-dashboard.url";
    public static final String GRAFANA_DASHBOARD_EXT_URL = "grafana-dashboard-ext.url";
    public static final String GRAFANA_DATASOURCE_URL = "quarkus.rest-client.jfr-datasource.url";

    public static final String AGENT_REST_CLIENT_FOLLOW_ALL_REDIRECTS =
            "quarkus.rest-client.agents.follow-all-redirects";

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

    public static final String AGENT_GATEWAY_SECRET = "cryostat.security.agent-gateway.secret";
    public static final String USER_PROXY_SECRET = "cryostat.security.user-proxy.secret";

    public static final String DECLARATIVE_CONFIG_RESOLVE_SYMLINKS =
            "cryostat.declarative-configuration.symlinks.resolve";
}
