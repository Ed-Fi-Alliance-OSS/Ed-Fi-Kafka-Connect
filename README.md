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
and must list at least one non-blank field** — a missing or empty `sourceFields`, or one
containing a blank entry (e.g. `a,,b`), fails fast with a `ConfigException` at startup rather
than silently passing records through unchanged. Entries are trimmed of surrounding whitespace. A
configured field that is absent or null is left unchanged; a field whose value is not a JSON
object (invalid JSON, a JSON array, or a scalar) fails fast with a `DataException`.

The transform operates on **schema-backed (Struct) value records** only — the shape a Debezium
source connector produces. The value schema is rebuilt (preserving the root schema's name,
version, doc, parameters, and optionality) with the expanded fields typed as inferred
structs/arrays. A struct-level default value is not carried over, since it is bound to the
original (pre-expansion) field schemas. Numbers are typed by inference: integral values map to
INT64 and fail fast with a `DataException` if they do not fit a signed 64-bit long; decimal
values (and arrays mixing integral and decimal values) map to FLOAT64, rounding to the nearest
IEEE 754 double, and fail fast with a `DataException` if they fall outside the finite double
range. A property that carries no type evidence in the record being expanded — its value is
JSON `null`, or it is an array with no non-null elements (e.g. `[]`) — is typed as optional
STRING (for such arrays, an array of optional STRING) in that record's schema, and its value
expands to null/empty as usual. Because the schema is inferred per record, a later record where
the same property does carry a value infers the actual type instead.

A record carrying a value without a value schema (a schemaless `Map` record, e.g. one a sink
connector's JSON converter deserialized with `schemas.enable=false`) fails fast with a
`DataException`: it means the transform is deployed against the wrong converter configuration.
Null-value records (tombstones) pass through unchanged. On the Debezium source side,
`value.converter.schemas.enable=false` only controls the serialized output envelope — the
transform still receives schema-backed records and is unaffected by that setting.

Example of this transformation configuration:

```properties
transforms=ExpandJson
transforms.ExpandJson.type=org.edfi.kafka.connect.transforms.ExpandJson$Value
transforms.ExpandJson.sourceFields=DocumentJson
```

> **Migrating from `expandjsonsmt`:** earlier images shipped the RedHat
> [expandjsonsmt](https://github.com/RedHatInsights/expandjsonsmt) SMT, which this transform
> replaces. The image no longer contains `com.redhat.insights.expandjsonsmt.ExpandJSON$Value`,
> so update `transforms.<name>.type` to `org.edfi.kafka.connect.transforms.ExpandJson$Value`.
> The `sourceFields` config key is unchanged. Unlike the RedHat SMT, this transform fails fast
> (as described above) on invalid JSON and non-object values instead of logging a warning and
> passing the record through, and dot-delimited nested paths in `sourceFields` are not
> supported.

### `DocumentState`

This DMS-specific transformation converts raw schema-backed Debezium records for the
relational `dms.DocumentCache`, `dms.Document`, and `dms.CdcHeartbeat` sources into the Ed-Fi
document-state topic contract and its internal CDC progress topic. It is configured with only
the provider and the two binding-scoped target topics:

```properties
transforms=documentState
transforms.documentState.type=org.edfi.kafka.connect.transforms.DocumentState
transforms.documentState.provider=<postgresql|sqlserver>
transforms.documentState.target.topic=<instance document topic>
transforms.documentState.progress.topic=<instance document topic>.cdc-progress
```

Public upserts emitted by `DocumentState` are named logical-byte values: the record has a
required `BYTES` value schema named `org.edfi.kafka.connect.data.DocumentStateJson` at
version `1` and a matching `byte[]` containing the complete final public JSON object. Use
these top-level connector settings for the relational document-state connector:

```properties
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.edfi.kafka.connect.converters.DocumentStateJsonConverter
value.converter.schemas.enable=false
value.converter.decimal.format=NUMERIC
tombstones.on.delete=false
```

`StringConverter` is required for both public document keys and internal progress keys, so
Kafka key bytes are plain UTF-8 strings with no JSON quoting and no Kafka Connect
`schema` or `payload` wrapper. `tombstones.on.delete=false` is required for both
PostgreSQL and SQL Server source connectors: `DocumentState` turns the authoritative
`dms.Document` delete envelope into exactly one public tombstone, while Debezium's
additional automatic tombstone is suppressed and cache deletes publish no public record.

`DocumentStateJsonConverter` passes only that exact public upsert schema/value handshake
through as a defensive byte copy, so the Kafka bytes are a plain JSON object with no
Kafka Connect `schema` or `payload` wrapper, no Base64 encoding, and no second JSON
serialization pass. Public tombstones remain record-level null values. Every other non-null
record, including internal progress records, is delegated to Kafka Connect 4.3
`JsonConverter` with `schemas.enable=false` and `decimal.format=NUMERIC`. Keep
`decimal.format=NUMERIC` as the required defensive delegate setting; public document
upserts bypass the delegate, so public decimal fidelity does not depend on that setting.

The transform builds the final JSON tree itself so collection objects preserve absent
properties instead of gaining synthetic nulls, and valid `DocumentJson` integer and decimal
values publish as exact JSON numbers. Do not replace this with `Double`/`Float`, string
conversion, generic `JsonConverter` public upserts, Avro, Protobuf, or Schema Registry for
the v1 document-state contract.

For SQL Server source connectors, also suppress consumer-facing schema-change records and
set the Debezium source temporal mode explicitly:

```properties
include.schema.changes=false
time.precision.mode=isostring
```

`include.schema.changes=false` keeps SQL Server schema-change records out of the same
connector task that runs `DocumentState`; the required internal schema history settings are
separate and remain enabled. `DocumentState` requires `dms.DocumentCache.LastModifiedAt` to
arrive as a `STRING` with the `io.debezium.time.IsoTimestamp` logical type. Debezium's
default `adaptive` mode emits SQL Server `datetime2(7)` as an `INT64`
`io.debezium.time.NanoTimestamp`, which is rejected as an unsupported retained-row field
shape.

## Running transformations

### Prerequisites

- Install JDK 17. Source/target compatibility and CI both use Java 17, and the
  transforms are built against it. The jar therefore contains Java 17 bytecode and
  **requires a Java 17+ runtime**: the shipped Docker image (built on
  `debezium/connect`, which runs JDK 21) satisfies this, but the jar will not load
  on a Kafka Connect deployment still running Java 11.
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

- `./gradlew build -PedfiDmsMaterializedDocumentFixtureRoot=<DMS checkout>/src/dms/backend/Fixtures/document-cache/materialized-documents`: Compile code

- `./gradlew test -PedfiDmsMaterializedDocumentFixtureRoot=<DMS checkout>/src/dms/backend/Fixtures/document-cache/materialized-documents`: Run unit tests

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
