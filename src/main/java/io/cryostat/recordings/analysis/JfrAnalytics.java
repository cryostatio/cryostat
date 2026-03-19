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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.moditect.jfranalytics.JfrSchema;
import org.moditect.jfranalytics.JfrSchemaFactory;

@jakarta.ws.rs.Path("")
public class JfrAnalytics {

    @ConfigProperty(name = ConfigProperties.JFR_ANALYTICS_CACHE_MAX_WEIGHT)
    long maxCacheWeight;

    @ConfigProperty(name = ConfigProperties.JFR_ANALYTICS_CACHE_TTL)
    Duration cacheTtl;

    @Inject RecordingHelper recordings;
    @Inject Logger logger;

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
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
        RecordingKey key = new RecordingKey(jvmId, filename);

        return Uni.createFrom()
                .completionStage(jfrFileCache.get(key))
                .onItem()
                .transform(
                        jfrFile -> {
                            try {
                                return executeQueryOnFile(jfrFile, query);
                            } catch (SQLException e) {
                                logger.error(e);
                                throw new BadRequestException(
                                        "Failed to execute query on JFR file", e);
                            }
                        });
    }

    private List<List<String>> executeQueryOnFile(Path jfrFile, String query) throws SQLException {
        if (query.toLowerCase().strip().equals("tables")) {
            return List.of(new ArrayList<>(new JfrSchema(jfrFile).getTableNames()));
        }
        if (query.toLowerCase().strip().startsWith("columns ")) {
            String[] parts = query.strip().split("\\s+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid columns query format");
            }
            JfrSchema schema = new JfrSchema(jfrFile);
            JavaTypeFactoryImpl typeFactory = new JavaTypeFactoryImpl();
            List<List<String>> result = new ArrayList<>();

            for (int i = 1; i < parts.length; i++) {
                String tableName = parts[i].replaceAll("'", "").replaceAll("\"", "");
                var table = schema.getTable(tableName);
                if (table == null) {
                    throw new IllegalArgumentException("Table not found: " + tableName);
                }
                result.add(new ArrayList<>(table.getRowType(typeFactory).getFieldNames()));
            }
            return result;
        }

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
                    String value = rs.getString(i);
                    row.add(value);
                }
                result.add(row);
            }

            return result;
        }
    }

    private void onCacheRemoval(RecordingKey key, Path tempFile, RemovalCause cause) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
                logger.debugv(
                        "Deleted temp file {0} for {1}/{2} due to {3}",
                        tempFile, key.jvmId(), key.filename(), cause);
            } catch (IOException e) {
                logger.warnv(
                        e,
                        "Failed to delete temp file {0} for {1}/{2}",
                        tempFile,
                        key.jvmId(),
                        key.filename());
            }
        }
    }

    record RecordingKey(String jvmId, String filename) {}

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
                                    Files.size(tempFile) / (1024 * 1024));

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

    static class FileSizeWeigher implements Weigher<RecordingKey, Path> {

        private final Logger logger = Logger.getLogger(getClass());

        @Override
        public int weigh(RecordingKey key, Path tempFile) {
            try {
                long sizeInBytes = Files.size(tempFile);
                long sizeInMB = sizeInBytes / (1024 * 1024);
                return (int) Math.max(1, sizeInMB);
            } catch (IOException e) {
                logger.error(e);
                return 1;
            }
        }
    }
}
