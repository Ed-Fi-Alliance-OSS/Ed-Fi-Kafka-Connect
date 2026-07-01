# Ed-Fi Transformations for Apache Kafka® Connect

[![OpenSSF
Scorecard](https://api.securityscorecards.dev/projects/github.com/Ed-Fi-Alliance-OSS/Ed-Fi-Kafka-Connect/badge)](https://securityscorecards.dev/viewer/?uri=github.com/Ed-Fi-Alliance-OSS/Ed-Fi-Kafka-Connect)

[Single Message Transformations
(SMTs)](https://kafka.apache.org/documentation/#connect_transforms) for Apache
Kafka Connect.

## Transformations

See [the Kafka
documentation](https://kafka.apache.org/documentation/#connect_transforms) for
more details about configuring transformations on how to install transforms.

### `RenameDmsTopicToOpenSearchIndex`

This transformation renames the DMS topic to be an OpenSearch index based on the document ProjectName and ResourceName.

Example of this transformation configuration:

```properties
transforms=RenameDmsTopicToOpenSearchIndex
transforms.RenameDmsTopicToOpenSearchIndex.type=org.edfi.kafka.connect.transforms.RenameDmsTopicToOpenSearchIndex
```

### `DebeziumDeletedToTombstone`

This transformation checks for a Debezium _deleted=true flag. If found, it turns it into a tombstone.

Example of this transformation configuration:

```properties
transforms=DebeziumDeletedToTombstone
transforms.DebeziumDeletedToTombstone.type=org.edfi.kafka.connect.transforms.DebeziumDeletedToTombstone
```

### `ExpandJson`

This transformation expands one or more configured top-level fields whose value is a JSON-object
string into a structured value, so downstream consumers receive real nested JSON instead of an
escaped string. The fields to expand are listed in the `sourceFields` config, which is **required
and must list at least one field** — a missing or empty `sourceFields` fails fast with a
`ConfigException` at startup rather than silently passing records through unchanged. A configured
field that is absent or null is left unchanged; a field whose value is not a JSON object (invalid
JSON, a JSON array, or a scalar) fails fast with a `DataException`.

Both record shapes are supported:

- **Schemaless (Map) records** — the shape produced in the DMS pipeline, where the JSON converter
  runs with `schemas.enable=false`. This is also required by the downstream
  `RenameDmsTopicToOpenSearchIndex` and `DebeziumDeletedToTombstone` transforms, which both operate
  on a Map `record.value()`. Expanded fields become nested `Map`s.
- **Schema-backed (Struct) records** — the value schema is rebuilt (preserving the root schema's
  name, version, doc, parameters, and optionality) with the expanded fields typed as inferred
  structs/arrays. A struct-level default value is not carried over, since it is bound to the
  original (pre-expansion) field schemas.

Example of this transformation configuration:

```properties
transforms=ExpandJson
transforms.ExpandJson.type=org.edfi.kafka.connect.transforms.ExpandJson$Value
transforms.ExpandJson.sourceFields=DocumentJson
```


## Running transformations

### Prerequisites

- Install JDK 17. Source/target compatibility and CI both use Java 17, and the
  transforms are built against it.
- Install, if you don't have it, Gradle 8.10 according to the
  [installation guide](https://gradle.org/install/). CI pins Gradle 8.10; the
  Docker build stage uses Gradle 8.2.1.
- To verify your installation, open a console (or a Windows command prompt) and
run gradle -v to run gradle and display the version, e.g.: `> gradle -v` Result:

```none
------------------------------------------------------------
Gradle 8.10
------------------------------------------------------------
```

- To run the transforms locally for the first time you need to build the
gradle-wrapper.jar. To generate it, run the following command from the `ed-fi-kafka-connect-transforms` directory. This will add the
gradle-wrapper.jar in the gradle\wrapper folder `> gradle wrapper`
  - If you encounter an error message `java.security.NoSuchAlgorithmException: Error constructing implementation (algorithm: Default, provider: SunJSSE, class: sun.security.ssl.SSLContextImpl$DefaultSSLContext)`, gradle could not find your Java cacert trustStore directory. Re-run and specify it explicitly e.g. `gradle wrapper -Djavax.net.ssl.trustStore=/usr/lib/jvm/default-java/lib/security/cacerts`.
  - If you encounter file system watcher errors/warnings and don't care about gradle watching in the background, add the `--no-watch-fs` flag.

### Tasks

This project includes a series of *gradle* tasks:

- `./gradlew build`: Compile code

- `./gradlew test`: Run unit tests

- `./gradlew installDist`: Creates a jar distributable file, located under
  `/build/install/ed-fi-kafka-connect-transforms/ed-fi-kafka-connect-transforms-{version}.jar`

## Build container

To build the container with a dev tag, simply run `docker build -t edfialliance/ed-fi-kafka-connect:dev --build-context parentdir=../  .` from the `kafka` directory.

## Legal Information

Copyright (c) 2024 Ed-Fi Alliance, LLC and contributors.

Licensed under the [Apache License, Version 2.0](./LICENSE) (the "License").

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
