# Ed-Fi Transformations for Apache Kafka® Connect

[Single Message Transformations
(SMTs)](https://kafka.apache.org/documentation/#connect_transforms) for Apache
Kafka Connect.

## Transformations

See [the Kafka
documentation](https://kafka.apache.org/documentation/#connect_transforms) for
more details about configuring transformations on how to install transforms.

### `GenerateIndexFromDmsResource`

This transformation builds an index based on a group of values contained in the
body of the result, separated by $.

- `org.edfi.kafka.connect.transforms.GenerateIndexFromDmsResource`
  - works on values.

The transformation defines the following configurations:

- `field.name` - Comma separated list of fields to be included into building the
  Index. This fields will be separated by $ and will add `descriptor` at the end
  if resource is marked as such.

Here is an example of this transformation configuration:

```properties
transforms=GenerateIndexFromDmsResource
transforms.GenerateIndexFromDmsResource.type=org.edfi.kafka.connect.transforms.GenerateIndexFromDmsResource
transforms.GenerateIndexFromDmsResource.field.name=projectName,resourceVersion,resourceName
```

## Running transformations

### Prerequisites

- Install, if you don't have it, gradle version 7.2.4 according to the
  [installation guide](https://gradle.org/install/)
- To verify your installation, pen a console (or a Windows command prompt) and
run gradle -v to run gradle and display the version, e.g.: `> gradle -v` Result:

```none
------------------------------------------------------------
Gradle 7.2.4
------------------------------------------------------------
```

- To run the transforms locally for the first time you need to build the
gradle-wrapper.jar. To generate it, run the following command, this will add the
gradle-wrapper.jar in the gradle\wrapper folder `> gradle wrapper`

### Tasks

This project includes a series of *gradle* tasks:

- `./gradlew build`: Compile code

- `./gradlew test`: Run unit tests

- `./gradlew installDist`: Creates a jar distributable file, located under
  `/build/install/ed-fi-kafka-connect-transforms/ed-fi-kafka-connect-transforms-{version}.jar`

## Legal Information

Copyright (c) 2024 Ed-Fi Alliance, LLC and contributors.

Licensed under the [Apache License, Version 2.0](./LICENSE) (the "License").

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
