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
package io.cryostat.schema;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class NotificationSchemaGenerator {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println(
                    "Usage: NotificationSchemaGenerator <source-directory> <output-file> <cryostat-version>");
            System.err.println(
                    "Example: NotificationSchemaGenerator src/main/java schema/notifications.yaml 4.2.0");
            System.exit(1);
        }

        Path sourceDir = Paths.get(args[0]);
        Path outputFile = Paths.get(args[1]);
        String cryostatVersion = args[2];

        System.out.println("Scanning source directory: " + sourceDir);
        System.out.println("Output file: " + outputFile);
        System.out.println("Cryostat version: " + cryostatVersion);

        try {
            NotificationSchemaGenerator generator = new NotificationSchemaGenerator();
            generator.generate(sourceDir, outputFile, cryostatVersion);
            System.out.println("Successfully generated notification schema!");
        } catch (Exception e) {
            System.err.println("Error generating notification schema: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void generate(Path sourceDir, Path outputFile, String cryostatVersion)
            throws IOException {
        // Step 1: Scan source code for notification patterns
        SourceCodeScanner scanner = new SourceCodeScanner();
        List<NotificationSite> notificationSites = scanner.scan(sourceDir);

        System.out.println("Found " + notificationSites.size() + " notification sites");

        // Step 2: Resolve category strings and analyze payload types
        CategoryResolver categoryResolver = new CategoryResolver(sourceDir);
        PayloadTypeAnalyzer payloadAnalyzer = new PayloadTypeAnalyzer(sourceDir);

        for (NotificationSite site : notificationSites) {
            String category = categoryResolver.resolve(site);
            Map<String, Object> payloadSchema = payloadAnalyzer.analyze(site);
            site.setResolvedCategory(category);
            site.setPayloadSchema(payloadSchema);
        }

        // Step 3: Build AsyncAPI schema
        AsyncAPISchemaBuilder schemaBuilder = new AsyncAPISchemaBuilder(cryostatVersion);
        Map<String, Object> asyncApiSchema = schemaBuilder.build(notificationSites);

        // Step 4: Write to YAML file
        writeYaml(asyncApiSchema, outputFile);

        System.out.println("Schema written to: " + outputFile);
    }

    private void writeYaml(Map<String, Object> schema, Path outputFile) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);

        Yaml yaml = new Yaml(options);

        try (FileWriter writer = new FileWriter(outputFile.toFile())) {
            yaml.dump(schema, writer);
        }
    }
}
