# Cryostat Notification Schema Generator

A standalone Maven module that performs static analysis on Cryostat's Java source code to automatically generate an AsyncAPI schema documenting all WebSocket notifications.

## Overview

This tool scans the Cryostat codebase for WebSocket notification emission sites and generates a comprehensive AsyncAPI 2.6.0 schema that documents:
- All notification categories
- Notification payload structures
- Source code locations where notifications are emitted
- Complete message schemas

## Building

```bash
cd schema-generator
../mvnw clean package
```

This creates a fat JAR with all dependencies: `target/notification-schema-generator.jar`

## Usage

### Command Line

```bash
java -jar target/notification-schema-generator.jar <source-directory> <output-file>
```

Example:
```bash
java -jar target/notification-schema-generator.jar ../src/main/java ../schema/notifications.yaml
```

### Via Script

From the Cryostat root directory:
```bash
./schema/generate-notifications.bash
```

## How It Works

1. **SourceCodeScanner**: Uses JavaParser to scan all `.java` files and find `eventBus.publish(MessagingServer.class.getName(), new Notification(...))` patterns
2. **CategoryResolver**: Resolves notification category strings from:
   - String literals
   - Static constants
   - Enum values
   - Chained method calls
3. **PayloadTypeAnalyzer**: Analyzes notification payload types and generates JSON Schema representations
4. **AsyncAPISchemaBuilder**: Constructs a valid AsyncAPI 2.6.0 YAML schema

## Dependencies

- **JavaParser 3.26.3**: For Java source code parsing and AST analysis
- **SnakeYAML 2.3**: For YAML generation

## Output

Generates `schema/notifications.yaml` containing:
- AsyncAPI 2.6.0 compliant schema
- WebSocket endpoint information
- All notification message definitions
- Payload schemas for each notification type

## Development

### Project Structure

```
schema-generator/
├── pom.xml
├── README.md
└── src/main/java/io/cryostat/schema/
    ├── NotificationSchemaGenerator.java  # Main entry point
    ├── SourceCodeScanner.java            # Finds notification sites
    ├── CategoryResolver.java             # Resolves category strings
    ├── PayloadTypeAnalyzer.java          # Analyzes payload types
    ├── AsyncAPISchemaBuilder.java        # Builds AsyncAPI schema
    └── NotificationSite.java             # Data class
```

### Adding Features

To extend the tool:
1. Modify the scanner to detect additional patterns
2. Enhance the category resolver for new constant types
3. Improve payload analysis for complex types
4. Update the AsyncAPI builder for additional metadata

## License

Apache License 2.0 - See LICENSE file in the root directory