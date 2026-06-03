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

package io.cryostat.recordings.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.RecordingHelper;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Weigher;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.moditect.jfranalytics.JfrSchema;
import org.moditect.jfranalytics.JfrSchemaFactory;

@jakarta.ws.rs.Path("")
public class JfrAnalytics {

    private static final long BYTES_PER_MB = 1024 * 1024;
    private static final String TABLES_COMMAND = "tables";
    private static final String COLUMNS_COMMAND = "columns ";

    @ConfigProperty(name = ConfigProperties.JFR_ANALYTICS_CACHE_MAX_WEIGHT)
    long maxCacheWeight;

    @ConfigProperty(name = ConfigProperties.JFR_ANALYTICS_CACHE_TTL)
    Duration cacheTtl;

    @Inject RecordingHelper recordings;
    @Inject Logger logger;

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JavaTypeFactoryImpl typeFactory = new JavaTypeFactoryImpl();
    private AsyncLoadingCache<RecordingKey, Path> jfrFileCache;

    void onStart(@Observes StartupEvent evt) {
        this.jfrFileCache =
                Caffeine.newBuilder()
                        .executor(executor)
                        .scheduler(Scheduler.systemScheduler())
                        .maximumWeight(maxCacheWeight)
                        .weigher(new FileSizeWeigher())
                        .expireAfterAccess(cacheTtl)
                        .removalListener(this::onCacheRemoval)
                        .buildAsync(new JfrFileLoader());
    }

    @jakarta.ws.rs.Path("/api/beta/recording_analytics/{jvmId}/{filename}")
    @POST
    @Blocking
    @RolesAllowed("read")
    public Uni<List<List<String>>> executeQuery(
            @PathParam("jvmId") String jvmId,
            @PathParam("filename") String filename,
            @FormParam("query") String query) {
        if (StringUtils.isBlank(query)) {
            throw new BadRequestException();
        }
        RecordingKey key = new RecordingKey(jvmId, filename);

        return Uni.createFrom()
                .completionStage(jfrFileCache.get(key))
                .onItem()
                .transform(
                        jfrFile -> {
                            try {
                                return executeQueryOnFile(jfrFile, query);
                            } catch (SQLException e) {
                                logger.errorv(
                                        e,
                                        "SQL query execution failed. Query: {0}, Error Code: {1},"
                                                + " SQL State: {2}, Message: {3}",
                                        query,
                                        e.getErrorCode(),
                                        e.getSQLState(),
                                        e.getMessage());
                                throw new BadRequestException(
                                        String.format(
                                                "Failed to execute query on JFR file. SQL State:"
                                                        + " %s, Error Code: %d, Message: %s",
                                                e.getSQLState(), e.getErrorCode(), e.getMessage()),
                                        e);
                            } catch (Exception e) {
                                logger.errorv(
                                        e,
                                        "Unexpected error executing query. Query: {0}, Exception"
                                                + " Type: {1}, Message: {2}",
                                        query,
                                        e.getClass().getName(),
                                        e.getMessage());
                                throw new BadRequestException(
                                        String.format(
                                                "Failed to execute query on JFR file: %s",
                                                e.getMessage()),
                                        e);
                            }
                        });
    }

    private List<List<String>> executeQueryOnFile(Path jfrFile, String query) throws SQLException {
        String normalizedQuery = normalizeQuery(query);

        if (normalizedQuery.equals(TABLES_COMMAND)) {
            return handleTablesQuery(jfrFile);
        }

        if (normalizedQuery.startsWith(COLUMNS_COMMAND)) {
            return handleColumnsQuery(jfrFile, query);
        }

        return executeSqlQuery(jfrFile, query);
    }

    private String normalizeQuery(String query) {
        return query.strip().toLowerCase();
    }

    private List<List<String>> handleTablesQuery(Path jfrFile) {
        JfrSchema schema = new JfrSchema(jfrFile);
        return List.of(new ArrayList<>(schema.getTableNames()));
    }

    private List<List<String>> handleColumnsQuery(Path jfrFile, String query) {
        String[] parts = query.strip().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid columns query format");
        }

        JfrSchema schema = new JfrSchema(jfrFile);
        List<List<String>> result = new ArrayList<>();

        for (int i = 1; i < parts.length; i++) {
            String tableName = parts[i].replaceAll("['\"]", "");
            var table = schema.getTable(tableName);
            if (table == null) {
                throw new IllegalArgumentException("Table not found: " + tableName);
            }
            result.add(new ArrayList<>(table.getRowType(typeFactory).getFieldNames()));
        }

        return result;
    }

    private List<List<String>> executeSqlQuery(Path jfrFile, String query) throws SQLException {
        Properties properties = new Properties();
        properties.put("model", JfrSchemaFactory.getInlineModel(jfrFile));

        try (Connection connection = DriverManager.getConnection("jdbc:calcite:", properties);
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            List<List<String>> result = new ArrayList<>();

            while (rs.next()) {
                List<String> row = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    row.add(rs.getString(i));
                }
                result.add(row);
            }

            return result;
        }
    }

    private void onCacheRemoval(RecordingKey key, Path tempFile, RemovalCause cause) {
        if (tempFile != null) {
            executor.execute(() -> deleteWithRetry(key, tempFile, cause, 0));
        }
    }

    private void deleteWithRetry(RecordingKey key, Path tempFile, RemovalCause cause, int attempt) {
        int maxRetries = 3;
        try {
            Files.deleteIfExists(tempFile);
            logger.debugv(
                    "Deleted temp file {0} for {1}/{2} due to {3}",
                    tempFile, key.jvmId(), key.filename(), cause);
        } catch (IOException e) {
            if (attempt < maxRetries) {
                long delayMs = (long) Math.pow(2, attempt) * 100;
                logger.warnv(
                        "Failed to delete temp file {0} for {1}/{2}, retrying in {3}ms (attempt"
                                + " {4}/{5})",
                        tempFile, key.jvmId(), key.filename(), delayMs, attempt + 1, maxRetries);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warnv("Interrupted while waiting to retry deletion of {0}", tempFile);
                    return;
                }
                deleteWithRetry(key, tempFile, cause, attempt + 1);
            } else {
                logger.errorv(
                        e,
                        "Failed to delete temp file {0} for {1}/{2} after {3} attempts. File may"
                                + " remain on disk.",
                        tempFile,
                        key.jvmId(),
                        key.filename(),
                        maxRetries);
            }
        }
    }

    record RecordingKey(String jvmId, String filename) {}

    /**
     * Loads JFR files from storage into temporary files for analysis.
     *
     * <p>Note: This loader performs blocking I/O operations (Files.createTempFile, Files.copy,
     * Files.size) within the async context. These operations are executed on virtual threads via
     * the provided executor to minimize performance impact under high load.
     */
    class JfrFileLoader implements AsyncCacheLoader<RecordingKey, Path> {
        @Override
        public CompletableFuture<Path> asyncLoad(RecordingKey key, Executor executor) {
            return CompletableFuture.supplyAsync(
                    () -> {
                        Path tempFile = null;
                        try {
                            logger.debugv(
                                    "Loading JFR file from S3: {0}/{1}",
                                    key.jvmId(), key.filename());

                            tempFile =
                                    Files.createTempFile(
                                            String.format(
                                                    "analytics-%s-%s", key.jvmId(), key.filename()),
                                            ".jfr");

                            try (InputStream inputStream =
                                    recordings.getArchivedRecordingStream(
                                            key.jvmId(), key.filename())) {
                                Files.copy(
                                        inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                            }

                            logger.debugv(
                                    "Cached JFR file {0}/{1} to {2} ({3} MB)",
                                    key.jvmId(),
                                    key.filename(),
                                    tempFile,
                                    Files.size(tempFile) / BYTES_PER_MB);

                            return tempFile;
                        } catch (IOException e) {
                            if (tempFile != null) {
                                try {
                                    Files.deleteIfExists(tempFile);
                                } catch (IOException e2) {
                                    logger.debug(e2);
                                }
                            }
                            throw new RuntimeException(
                                    "Failed to download and cache JFR file: "
                                            + key.jvmId()
                                            + "/"
                                            + key.filename(),
                                    e);
                        }
                    },
                    executor);
        }
    }

    /**
     * Weighs cached JFR files by their size in megabytes for cache eviction policy.
     *
     * <p>Note: This weigher performs blocking I/O (Files.size) to determine file size. The
     * operation is executed on virtual threads to minimize performance impact.
     */
    static class FileSizeWeigher implements Weigher<RecordingKey, Path> {

        private final Logger logger = Logger.getLogger(getClass());

        @Override
        public int weigh(RecordingKey key, Path tempFile) {
            try {
                long sizeInBytes = Files.size(tempFile);
                long sizeInMB = sizeInBytes / BYTES_PER_MB;
                return (int) Math.min(Math.max(1, sizeInMB), Integer.MAX_VALUE);
            } catch (IOException e) {
                logger.error(e);
                return 1;
            }
        }
    }
}
