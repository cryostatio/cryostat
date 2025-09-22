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
package db.migration;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jboss.logging.Logger;

public class V4_1_0__Cryostat extends BaseJavaMigration {

    final Logger logger = Logger.getLogger(getClass());

    @Override
    public void migrate(Context context) throws Exception {
        exec(context, "alter sequence DiscoveryNode_SEQ increment by 50;");

        exec(context, "create index on Target (jvmId);");
        exec(context, "create index on Target (connectUrl);");

        exec(context, "create index on DiscoveryNode (nodeType);");
        exec(context, "create index on DiscoveryNode (nodeType, name);");

        exec(context, "create index on Rule (name);");
        exec(context, "alter table Rule add column metadata jsonb default '{\"labels\":{}}';");

        exec(context, "alter table ActiveRecording add column archiveOnStop boolean default false;");

        decodeTargetAliases(context);
    }

    private void decodeTargetAliases(Context context) throws Exception {
        logger.debug("Decoding Target aliases...");
        try (Statement select = context.getConnection().createStatement()) {
            try (ResultSet rows = select.executeQuery("SELECT id,alias FROM Target ORDER BY id")) {
                while (rows.next()) {
                    long id = rows.getInt(1);
                    String alias = rows.getString(2);
                    String decodedAlias = URLDecoder.decode(alias, StandardCharsets.UTF_8);
                    if (Objects.equals(alias, decodedAlias)) {
                        continue;
                    }
                    logger.debugv("Target[{0}] alias \"{1}\" -> \"{2}\"", id, alias, decodedAlias);
                    exec(
                            context,
                            String.format(
                                    "UPDATE Target SET alias='%s' WHERE id=%d", decodedAlias, id));
                }
            }
        }
    }

    private void exec(Context context, String sql) throws Exception {
        logger.debug(sql);
        try (Statement stmt = context.getConnection().createStatement()) {
            stmt.execute(sql);
        }
    }
}
