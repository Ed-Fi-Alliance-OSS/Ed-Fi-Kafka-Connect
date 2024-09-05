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

This transformation renames the DMS topic to be an OpenSearch index based on the document ProjectName and ResourceName

- `org.edfi.kafka.connect.transforms.RenameDmsTopicToOpenSearchIndex`
  - works on values.

Here is an example of this transformation configuration:

```properties
transforms=RenameDmsTopicToOpenSearchIndex
transforms.RenameDmsTopicToOpenSearchIndex.type=org.edfi.kafka.connect.transforms.RenameDmsTopicToOpenSearchIndex
```

### `DebeziumDeletedToTombstone`

This transformation checks for a Debezium _deleted=true flag. If found, it turns it into a tombstone

- `org.edfi.kafka.connect.transforms.DebeziumDeletedToTombstone`
  - works on values.

Here is an example of this transformation configuration:

```properties
transforms=DebeziumDeletedToTombstone
transforms.DebeziumDeletedToTombstone.type=org.edfi.kafka.connect.transforms.DebeziumDeletedToTombstone
```


## Running transformations

### Prerequisites

- Install, if you don't have it, gradle version 7.2.4 according to the
  [installation guide](https://gradle.org/install/)
- To verify your installation, open a console (or a Windows command prompt) and
run gradle -v to run gradle and display the version, e.g.: `> gradle -v` Result:

```none
------------------------------------------------------------
Gradle 7.2.4
------------------------------------------------------------
```

- To run the transforms locally for the first time you need to build the
gradle-wrapper.jar. To generate it, run the following command, this will add the
gradle-wrapper.jar in the gradle\wrapper folder `> gradle wrapper`
  - If you encounter an error message `java.security.NoSuchAlgorithmException: Error constructing implementation (algorithm: Default, provider: SunJSSE, class: sun.security.ssl.SSLContextImpl$DefaultSSLContext)`, gradle could not find your Java cacert trustStore directory. Re-run and specify it explicitly e.g. `gradle wrapper -Djavax.net.ssl.trustStore=/usr/lib/jvm/default-java/lib/security/cacerts`.

### Tasks

This project includes a series of *gradle* tasks:

- `./gradlew build`: Compile code

- `./gradlew test`: Run unit tests

- `./gradlew installDist`: Creates a jar distributable file, located under
  `/build/install/ed-fi-kafka-connect-transforms/ed-fi-kafka-connect-transforms-{version}.jar`

## Build container

To build the container with a dev tag, simply run `docker build -t edfialliance/ed-fi-kafka-connect:dev` from the `kafka` directory.

## Legal Information

Copyright (c) 2024 Ed-Fi Alliance, LLC and contributors.

Licensed under the [Apache License, Version 2.0](./LICENSE) (the "License").

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
