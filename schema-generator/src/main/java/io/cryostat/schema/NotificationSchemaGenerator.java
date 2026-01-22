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

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class NotificationSchemaGenerator {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println(
                    "Usage: NotificationSchemaGenerator <source-directory> <output-file>"
                            + " <cryostat-version>");
            System.err.println(
                    "Example: NotificationSchemaGenerator src/main/java schema/notifications.yaml"
                            + " 4.2.0");
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
        // Step 0: Set up JavaParser with symbol resolution
        setupSymbolSolver(sourceDir);

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

    /** Set up JavaParser's symbol solver to enable type resolution across all scanners. */
    private void setupSymbolSolver(Path sourceDir) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();

        // Add reflection type solver for JDK classes
        combinedTypeSolver.add(new ReflectionTypeSolver());

        // Add source directory type solver for project classes
        try {
            combinedTypeSolver.add(new JavaParserTypeSolver(sourceDir.toFile()));
        } catch (Exception e) {
            System.err.println(
                    "Warning: Could not set up source directory type solver: " + e.getMessage());
        }

        // Add compiled classes from target directory using ClassLoader
        // sourceDir is src/main/java, so we need to go up 3 levels to get to project root
        // First convert to absolute path
        Path absoluteSourceDir = sourceDir.toAbsolutePath().normalize();
        Path projectRoot = absoluteSourceDir.getParent(); // src/main
        if (projectRoot != null) {
            projectRoot = projectRoot.getParent(); // src
        }
        if (projectRoot != null) {
            projectRoot = projectRoot.getParent(); // project root
        }

        if (projectRoot == null) {
            System.err.println(
                    "Warning: Could not determine project root from source directory: "
                            + absoluteSourceDir);
            return;
        }

        Path targetClasses = projectRoot.resolve("target/classes");
        System.out.println("Checking for compiled classes at: " + targetClasses);
        if (java.nio.file.Files.exists(targetClasses)) {
            try {
                // Create a custom ClassLoader that includes the compiled classes
                java.net.URL[] urls = new java.net.URL[] {targetClasses.toUri().toURL()};
                java.net.URLClassLoader classLoader =
                        new java.net.URLClassLoader(urls, ClassLoader.getSystemClassLoader());
                combinedTypeSolver.add(new ClassLoaderTypeSolver(classLoader));
                System.out.println("Added compiled classes from " + targetClasses);
            } catch (Exception e) {
                System.err.println(
                        "Warning: Could not set up compiled classes resolver: " + e.getMessage());
            }
        }

        // Add dependency JARs from Maven repository (for full package build)
        Path targetLib = projectRoot.resolve("target/quarkus-app/lib/main");
        System.out.println("Checking for dependency JARs at: " + targetLib);
        if (java.nio.file.Files.exists(targetLib)) {
            try {
                int jarCount = 0;
                for (Path jarPath :
                        (Iterable<Path>)
                                java.nio.file.Files.walk(targetLib)
                                                .filter(p -> p.toString().endsWith(".jar"))
                                        ::iterator) {
                    try {
                        combinedTypeSolver.add(new JarTypeSolver(jarPath.toString()));
                        jarCount++;
                    } catch (Exception e) {
                        // Silently skip JARs that can't be loaded
                    }
                }
                System.out.println("Added " + jarCount + " dependency JARs from " + targetLib);
            } catch (Exception e) {
                System.err.println("Warning: Could not add dependency JARs: " + e.getMessage());
            }
        }

        // Configure JavaParser to use the symbol solver
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        ParserConfiguration config = new ParserConfiguration();
        config.setSymbolResolver(symbolSolver);
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(config);
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
