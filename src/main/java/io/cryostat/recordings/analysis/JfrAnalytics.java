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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.moditect.jfranalytics.JfrSchema;
import org.moditect.jfranalytics.JfrSchemaFactory;

@jakarta.ws.rs.Path("")
public class JfrAnalytics {

    private static final String TABLES_COMMAND = "tables";
    private static final String COLUMNS_COMMAND = "columns ";

    private final JavaTypeFactoryImpl typeFactory = new JavaTypeFactoryImpl();

    @Inject AnalysisCache cache;
    @Inject Logger logger;

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
        return Uni.createFrom()
                .completionStage(cache.get(jvmId, filename))
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
}
