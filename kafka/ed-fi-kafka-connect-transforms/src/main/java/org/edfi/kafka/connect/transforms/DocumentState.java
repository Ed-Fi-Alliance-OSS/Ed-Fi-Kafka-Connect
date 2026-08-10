// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

public class DocumentState<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String PROVIDER_CONFIG = "provider";
    public static final String TARGET_TOPIC_CONFIG = "target.topic";
    public static final String PROGRESS_TOPIC_CONFIG = "progress.topic";

    public static final String POSTGRESQL_PROVIDER = "postgresql";
    public static final String SQLSERVER_PROVIDER = "sqlserver";

    private static final String PROGRESS_TOPIC_SUFFIX = ".cdc-progress";
    private static final String NATIVE_HEARTBEAT_TOPIC_PREFIX = "__debezium-heartbeat.";
    private static final String PROGRESS_KEY = "cdc-progress";

    private static final String SOURCE_FIELD = "source";
    private static final String SOURCE_SCHEMA_FIELD = "schema";
    private static final String SOURCE_TABLE_FIELD = "table";
    private static final String OPERATION_FIELD = "op";
    private static final String BEFORE_FIELD = "before";
    private static final String AFTER_FIELD = "after";
    private static final String DOCUMENT_UUID_FIELD = "DocumentUuid";
    private static final String PROJECT_NAME_FIELD = "ProjectName";
    private static final String RESOURCE_NAME_FIELD = "ResourceName";
    private static final String RESOURCE_VERSION_FIELD = "ResourceVersion";
    private static final String CONTENT_VERSION_FIELD = "ContentVersion";
    private static final String STREAM_ETAG_FIELD = "StreamEtag";
    private static final String LAST_MODIFIED_AT_FIELD = "LastModifiedAt";
    private static final String DOCUMENT_JSON_FIELD = "DocumentJson";
    private static final String RELATIONAL_SCHEMA = "dms";
    private static final String DOCUMENT_CACHE_TABLE = "DocumentCache";
    private static final String DOCUMENT_TABLE = "Document";
    private static final String HEARTBEAT_TABLE = "CdcHeartbeat";
    private static final String PROJECTION_WORK_TABLE = "DocumentProjectionWork";
    private static final String POSTGRESQL_SOURCE_SCHEMA_NAME = "io.debezium.connector.postgresql.Source";
    private static final String SQLSERVER_SOURCE_SCHEMA_NAME = "io.debezium.connector.sqlserver.Source";
    private static final String POSTGRESQL_UUID_SCHEMA_NAME = "io.debezium.data.Uuid";
    private static final String POSTGRESQL_JSON_SCHEMA_NAME = "io.debezium.data.Json";
    private static final String POSTGRESQL_TIMESTAMP_SCHEMA_NAME = "io.debezium.time.ZonedTimestamp";
    private static final String SQLSERVER_TIMESTAMP_SCHEMA_NAME = "io.debezium.time.IsoTimestamp";
    private static final String SQLSERVER_UNAVAILABLE_VALUE = "__debezium_unavailable_value";
    private static final int MAX_METADATA_VALUE_LENGTH = 128;

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(PROVIDER_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    DocumentState::validateProviderConfig, ConfigDef.Importance.HIGH,
                    "Relational source provider. Must be exactly 'postgresql' or 'sqlserver'.")
            .define(TARGET_TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    DocumentState::validateNonEmptyStringConfig, ConfigDef.Importance.HIGH,
                    "Public document topic.")
            .define(PROGRESS_TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    DocumentState::validateNonEmptyStringConfig, ConfigDef.Importance.HIGH,
                    "Progress topic, derived as target.topic plus '.cdc-progress'.");

    private Settings settings;

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void configure(final Map<String, ?> configs) {
        final Provider provider = providerFrom(configs.get(PROVIDER_CONFIG));
        final String targetTopic = requiredStringConfig(configs, TARGET_TOPIC_CONFIG);
        final String progressTopic = requiredStringConfig(configs, PROGRESS_TOPIC_CONFIG);

        if (!progressTopic.equals(targetTopic + PROGRESS_TOPIC_SUFFIX)) {
            throw new ConfigException(PROGRESS_TOPIC_CONFIG, progressTopic,
                    "must equal target.topic plus '" + PROGRESS_TOPIC_SUFFIX + "'");
        }

        this.settings = new Settings(provider, targetTopic, progressTopic);
    }

    @Override
    public R apply(final R record) {
        final ClassifiedRecord classifiedRecord = classify(record);
        if (classifiedRecord.outputKind() == OutputKind.DROP) {
            return null;
        }
        if (classifiedRecord.outputKind() == OutputKind.PUBLIC_UPSERT
                || classifiedRecord.outputKind() == OutputKind.PUBLIC_TOMBSTONE) {
            final ValidatedDocumentKey documentKey = validatePublicDocumentKey(record, classifiedRecord);
            if (classifiedRecord.outputKind() == OutputKind.PUBLIC_UPSERT) {
                return publicUpsert(record, classifiedRecord, documentKey);
            }
            return publicTombstone(record, classifiedRecord, documentKey);
        }
        if (classifiedRecord.outputKind() == OutputKind.PROGRESS) {
            return progress(record);
        }
        throw transformationFailure(
                FailureReason.OUTPUT_NOT_IMPLEMENTED, settings.provider(), record, classifiedRecord);
    }

    @Override
    public void close() {
    }

    Settings settings() {
        return settings;
    }

    ClassifiedRecord classify(final R record) {
        if (settings == null) {
            throw transformationFailure(FailureReason.NOT_CONFIGURED, null, record, null, null);
        }
        if (isNativeHeartbeat(record)) {
            return ClassifiedRecord.nativeHeartbeat();
        }

        final SourceMetadata sourceMetadata = settings.sourceAdapter().sourceMetadata(record);
        final SourceOperation sourceOperation = sourceOperation(record, sourceMetadata);
        return ClassifiedRecord.relational(
                sourceMetadata, sourceOperation, outputKind(sourceMetadata, sourceOperation));
    }

    ValidatedDocumentKey validatePublicDocumentKey(final R record, final ClassifiedRecord classifiedRecord) {
        return settings.sourceAdapter().documentKey(record, classifiedRecord);
    }

    private R publicUpsert(
            final R record,
            final ClassifiedRecord classifiedRecord,
            final ValidatedDocumentKey documentKey) {
        final var row = settings.sourceAdapter().cacheRow(record, classifiedRecord, documentKey);
        final DocumentStateJson.ByteBackedValue value =
                DocumentStateJson.publicUpsertValue(row, documentKey, record, classifiedRecord);
        return DocumentStateJson.publicUpsertRecord(record, settings.targetTopic(), documentKey, value);
    }

    private R publicTombstone(
            final R record,
            final ClassifiedRecord classifiedRecord,
            final ValidatedDocumentKey documentKey) {
        settings.sourceAdapter().validateDeleteBeforeDocumentUuid(record, classifiedRecord, documentKey);
        return DocumentStateJson.publicTombstoneRecord(record, settings.targetTopic(), documentKey);
    }

    private R progress(final R record) {
        return DocumentStateJson.progressRecord(record, settings.progressTopic(), PROGRESS_KEY);
    }

    private static void validateProviderConfig(final String name, final Object value) {
        if (!(POSTGRESQL_PROVIDER.equals(value) || SQLSERVER_PROVIDER.equals(value))) {
            throw new ConfigException(name, value, "must be exactly 'postgresql' or 'sqlserver'");
        }
    }

    private static void validateNonEmptyStringConfig(final String name, final Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new ConfigException(name, value, "must be a non-empty string");
        }
    }

    private static String requiredStringConfig(final Map<String, ?> configs, final String name) {
        final Object value = configs.get(name);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new ConfigException(name, value, "must be a non-empty string");
        }
        return (String) value;
    }

    private static Provider providerFrom(final Object provider) {
        if (POSTGRESQL_PROVIDER.equals(provider)) {
            return Provider.POSTGRESQL;
        }
        if (SQLSERVER_PROVIDER.equals(provider)) {
            return Provider.SQLSERVER;
        }
        throw new ConfigException(PROVIDER_CONFIG, provider, "must be exactly 'postgresql' or 'sqlserver'");
    }

    private static boolean isNativeHeartbeat(final ConnectRecord<?> record) {
        final String topic = record.topic();
        if (topic == null || !topic.startsWith(NATIVE_HEARTBEAT_TOPIC_PREFIX)) {
            return false;
        }

        final String sourceServer = DocumentStateJson.sourcePartitionServer(record);
        if (sourceServer == null || sourceServer.isEmpty()) {
            throw transformationFailure(FailureReason.MALFORMED_NATIVE_HEARTBEAT, null, record, null, null);
        }
        return topic.equals(NATIVE_HEARTBEAT_TOPIC_PREFIX + sourceServer);
    }

    private static SourceOperation sourceOperation(
            final ConnectRecord<?> record, final SourceMetadata sourceMetadata) {
        final Schema valueSchema = requireValueSchema(record);
        final var operationField = valueSchema.field(OPERATION_FIELD);
        if (operationField == null) {
            throw transformationFailure(FailureReason.MISSING_OPERATION_METADATA, record, sourceMetadata, null);
        }
        if (operationField.schema().type() != Schema.Type.STRING || operationField.schema().isOptional()) {
            throw transformationFailure(
                    FailureReason.UNSUPPORTED_OPERATION_METADATA_SHAPE, record, sourceMetadata, null);
        }

        final Object operation = requireStructValue(record).getWithoutDefault(OPERATION_FIELD);
        if (!(operation instanceof String)) {
            throw transformationFailure(FailureReason.MISSING_OPERATION_METADATA, record, sourceMetadata, operation);
        }
        return SourceOperation.fromCode((String) operation, record, sourceMetadata);
    }

    private static Schema requireValueSchema(final ConnectRecord<?> record) {
        return requireValueSchema(record, null);
    }

    private static Schema requireValueSchema(final ConnectRecord<?> record, final Provider provider) {
        final Schema valueSchema = record.valueSchema();
        if (valueSchema == null) {
            throw transformationFailure(FailureReason.MISSING_SOURCE_METADATA, provider, record, null, null);
        }
        return valueSchema;
    }

    private static Struct requireStructValue(final ConnectRecord<?> record) {
        return requireStructValue(record, null);
    }

    private static Struct requireStructValue(final ConnectRecord<?> record, final Provider provider) {
        final Object value = record.value();
        if (!(value instanceof Struct)) {
            throw transformationFailure(FailureReason.MISSING_SOURCE_METADATA, provider, record, null, null);
        }
        return (Struct) value;
    }

    private static OutputKind outputKind(final SourceMetadata sourceMetadata, final SourceOperation sourceOperation) {
        final SourceTable sourceTable = sourceMetadata.sourceTable();
        switch (sourceTable) {
            case DOCUMENT_CACHE:
                return documentCacheOutputKind(sourceOperation);
            case DOCUMENT:
                return documentOutputKind(sourceOperation);
            case HEARTBEAT:
                return heartbeatOutputKind(sourceMetadata, sourceOperation);
            default:
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_TABLE, null, sourceMetadata, sourceOperation.code());
        }
    }

    private static OutputKind documentCacheOutputKind(final SourceOperation sourceOperation) {
        switch (sourceOperation) {
            case CREATE:
            case UPDATE:
            case READ:
                return OutputKind.PUBLIC_UPSERT;
            case DELETE:
            case TRUNCATE:
                return OutputKind.DROP;
            default:
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_OPERATION, null, null, sourceOperation.code());
        }
    }

    private static OutputKind documentOutputKind(final SourceOperation sourceOperation) {
        switch (sourceOperation) {
            case DELETE:
                return OutputKind.PUBLIC_TOMBSTONE;
            case CREATE:
            case UPDATE:
            case READ:
            case TRUNCATE:
                return OutputKind.DROP;
            default:
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_OPERATION, null, null, sourceOperation.code());
        }
    }

    private static OutputKind heartbeatOutputKind(
            final SourceMetadata sourceMetadata, final SourceOperation sourceOperation) {
        switch (sourceOperation) {
            case CREATE:
            case UPDATE:
            case READ:
            case DELETE:
            case TRUNCATE:
                return OutputKind.PROGRESS;
            default:
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_OPERATION, null, sourceMetadata, sourceOperation.code());
        }
    }

    private static TransformationFailureException transformationFailure(
            final FailureReason reason,
            final ConnectRecord<?> record,
            final SourceMetadata sourceMetadata,
            final Object operation) {
        final Provider provider = sourceMetadata == null ? null : sourceMetadata.provider();
        return transformationFailure(reason, provider, record, sourceMetadata, operation);
    }

    private static TransformationFailureException transformationFailure(
            final FailureReason reason,
            final Provider provider,
            final ConnectRecord<?> record,
            final SourceMetadata sourceMetadata,
            final Object operation) {
        final Map<String, String> metadata = failureMetadata(provider, record);
        if (sourceMetadata != null) {
            appendMetadata(metadata, "sourceCategory", sourceMetadata.sourceCategory());
            appendMetadata(metadata, "sourceSchema", sourceMetadata.sourceSchema());
            appendMetadata(metadata, "sourceTable", sourceMetadata.sourceTableName());
        }
        appendMetadata(metadata, "operation", operation);
        return new TransformationFailureException(reason, metadata);
    }

    private static TransformationFailureException transformationFailure(
            final FailureReason reason,
            final Provider provider,
            final ConnectRecord<?> record,
            final ClassifiedRecord classifiedRecord) {
        final Map<String, String> metadata = failureMetadata(provider, record);
        if (classifiedRecord != null) {
            appendMetadata(metadata, "sourceCategory", classifiedRecord.sourceCategory());
            if (classifiedRecord.sourceMetadata() != null) {
                appendMetadata(metadata, "sourceSchema", classifiedRecord.sourceMetadata().sourceSchema());
            }
            if (classifiedRecord.sourceTable() != null) {
                appendMetadata(metadata, "sourceTable", classifiedRecord.sourceTable().tableName());
            }
            if (classifiedRecord.sourceOperation() != null) {
                appendMetadata(metadata, "operation", classifiedRecord.sourceOperation().code());
            }
        }
        return new TransformationFailureException(reason, metadata);
    }

    static TransformationFailureException classifiedFailure(
            final FailureReason reason,
            final ConnectRecord<?> record,
            final ClassifiedRecord classifiedRecord) {
        final Object operation = classifiedRecord == null || classifiedRecord.sourceOperation() == null
                ? null : classifiedRecord.sourceOperation().code();
        final SourceMetadata sourceMetadata = classifiedRecord == null ? null : classifiedRecord.sourceMetadata();
        if (sourceMetadata != null) {
            return transformationFailure(reason, record, sourceMetadata, operation);
        }
        return transformationFailure(reason, null, record, null, operation);
    }

    private static void validateRetainedRowDocumentUuid(
            final ValidatedDocumentKey documentKey,
            final String rowDocumentUuid,
            final ConnectRecord<?> record,
            final ClassifiedRecord classifiedRecord) {
        if (!documentKey.value().equals(rowDocumentUuid)) {
            throw classifiedFailure(FailureReason.DOCUMENT_UUID_MISMATCH, record, classifiedRecord);
        }
    }

    private static Map<String, String> failureMetadata(final Provider provider, final ConnectRecord<?> record) {
        final Map<String, String> metadata = new LinkedHashMap<>();
        if (provider != null) {
            appendMetadata(metadata, "provider", provider.configValue());
        }
        if (record != null) {
            appendMetadata(metadata, "sourceTopic", record.topic());
        }
        return metadata;
    }

    private static void appendMetadata(final Map<String, String> metadata, final String name, final Object value) {
        if (value != null) {
            metadata.put(name, sanitizeMetadataValue(value));
        }
    }

    private static String sanitizeMetadataValue(final Object value) {
        final String rawValue = String.valueOf(value);
        final StringBuilder sanitized = new StringBuilder(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            final char character = rawValue.charAt(i);
            sanitized.append(Character.isISOControl(character) ? '?' : character);
        }
        if (sanitized.length() <= MAX_METADATA_VALUE_LENGTH) {
            return sanitized.toString();
        }
        return sanitized.substring(0, MAX_METADATA_VALUE_LENGTH - 3) + "...";
    }

    enum Provider {
        POSTGRESQL(POSTGRESQL_PROVIDER),
        SQLSERVER(SQLSERVER_PROVIDER);

        private final String configValue;

        Provider(final String configValue) {
            this.configValue = configValue;
        }

        String configValue() {
            return configValue;
        }
    }

    public enum FailureReason {
        NOT_CONFIGURED("not configured"),
        MISSING_SOURCE_METADATA("missing source metadata"),
        UNSUPPORTED_SOURCE_METADATA_SHAPE("unsupported source metadata shape"),
        MISSING_OPERATION_METADATA("missing operation metadata"),
        UNSUPPORTED_OPERATION_METADATA_SHAPE("unsupported operation metadata shape"),
        UNKNOWN_OPERATION_CODE("unknown operation code"),
        MALFORMED_NATIVE_HEARTBEAT("malformed native heartbeat"),
        UNSUPPORTED_SOURCE_SCHEMA("unsupported source schema"),
        UNSUPPORTED_SOURCE_TABLE("unsupported source table"),
        UNEXPECTED_RETAINED_SOURCE_TABLE("unexpected retained source table"),
        UNSUPPORTED_SOURCE_OPERATION("unsupported source operation"),
        MISSING_DOCUMENT_KEY("missing public document key"),
        UNSUPPORTED_DOCUMENT_KEY_SHAPE("unsupported public document key shape"),
        MISSING_DOCUMENT_UUID("missing DocumentUuid"),
        INVALID_DOCUMENT_UUID("invalid DocumentUuid"),
        MISSING_RETAINED_ROW("missing retained row"),
        UNSUPPORTED_RETAINED_ROW_SHAPE("unsupported retained row shape"),
        UNSUPPORTED_DOCUMENT_UUID_SHAPE("unsupported DocumentUuid shape"),
        DOCUMENT_UUID_MISMATCH("DocumentUuid mismatch"),
        MISSING_REQUIRED_FIELD("missing required retained row field"),
        UNSUPPORTED_REQUIRED_FIELD_SHAPE("unsupported required retained row field shape"),
        INVALID_DOCUMENT_JSON("invalid DocumentJson"),
        UNAVAILABLE_DOCUMENT_JSON("unavailable DocumentJson"),
        DOCUMENT_JSON_HAS_ETAG("DocumentJson contains _etag"),
        INVALID_LAST_MODIFIED_AT("invalid LastModifiedAt"),
        PUBLIC_DOCUMENT_INVARIANT_MISMATCH("public document invariant mismatch"),
        OUTPUT_NOT_IMPLEMENTED("output transformation is not implemented");

        private final String description;

        FailureReason(final String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    public static final class TransformationFailureException extends DataException {
        private final FailureReason reason;
        private final Map<String, String> metadata;

        private TransformationFailureException(final FailureReason reason, final Map<String, String> metadata) {
            super(message(reason, metadata));
            this.reason = reason;
            this.metadata = new LinkedHashMap<>(metadata);
        }

        public FailureReason reason() {
            return reason;
        }

        public Map<String, String> metadata() {
            return new LinkedHashMap<>(metadata);
        }

        private static String message(final FailureReason reason, final Map<String, String> metadata) {
            final StringBuilder message = new StringBuilder("DocumentState transformation failure: ");
            message.append(reason.description()).append("; reasonCode=").append(reason);
            for (final Map.Entry<String, String> entry : metadata.entrySet()) {
                message.append("; ").append(entry.getKey()).append('=').append(entry.getValue());
            }
            return message.toString();
        }
    }

    enum SourceCategory {
        NATIVE_HEARTBEAT,
        RELATIONAL
    }

    enum SourceTable {
        DOCUMENT_CACHE(DOCUMENT_CACHE_TABLE),
        DOCUMENT(DOCUMENT_TABLE),
        HEARTBEAT(HEARTBEAT_TABLE);

        private final String tableName;

        SourceTable(final String tableName) {
            this.tableName = tableName;
        }

        String tableName() {
            return tableName;
        }

        static SourceTable fromName(
                final String tableName, final ConnectRecord<?> record, final SourceMetadata sourceMetadata) {
            if (DOCUMENT_CACHE_TABLE.equals(tableName)) {
                return DOCUMENT_CACHE;
            }
            if (DOCUMENT_TABLE.equals(tableName)) {
                return DOCUMENT;
            }
            if (HEARTBEAT_TABLE.equals(tableName)) {
                return HEARTBEAT;
            }
            if (PROJECTION_WORK_TABLE.equals(tableName)) {
                throw transformationFailure(
                        FailureReason.UNEXPECTED_RETAINED_SOURCE_TABLE, record, sourceMetadata, null);
            }
            throw transformationFailure(FailureReason.UNSUPPORTED_SOURCE_TABLE, record, sourceMetadata, null);
        }
    }

    enum SourceOperation {
        CREATE("c"),
        UPDATE("u"),
        DELETE("d"),
        READ("r"),
        TRUNCATE("t");

        private final String code;

        SourceOperation(final String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        static SourceOperation fromCode(
                final String code, final ConnectRecord<?> record, final SourceMetadata sourceMetadata) {
            for (final SourceOperation sourceOperation : values()) {
                if (sourceOperation.code().equals(code)) {
                    return sourceOperation;
                }
            }
            throw transformationFailure(FailureReason.UNKNOWN_OPERATION_CODE, record, sourceMetadata, code);
        }
    }

    enum OutputKind {
        PUBLIC_UPSERT,
        PUBLIC_TOMBSTONE,
        PROGRESS,
        DROP
    }

    private enum FieldKind {
        PLAIN_STRING,
        DOCUMENT_JSON
    }

    static final class ClassifiedRecord {
        private final SourceCategory sourceCategory;
        private final SourceMetadata sourceMetadata;
        private final SourceTable sourceTable;
        private final SourceOperation sourceOperation;
        private final OutputKind outputKind;

        private ClassifiedRecord(
                final SourceCategory sourceCategory,
                final SourceMetadata sourceMetadata,
                final SourceTable sourceTable,
                final SourceOperation sourceOperation,
                final OutputKind outputKind) {
            this.sourceCategory = sourceCategory;
            this.sourceMetadata = sourceMetadata;
            this.sourceTable = sourceTable;
            this.sourceOperation = sourceOperation;
            this.outputKind = outputKind;
        }

        static ClassifiedRecord nativeHeartbeat() {
            return new ClassifiedRecord(SourceCategory.NATIVE_HEARTBEAT, null, null, null, OutputKind.PROGRESS);
        }

        static ClassifiedRecord relational(
                final SourceMetadata sourceMetadata,
                final SourceOperation sourceOperation,
                final OutputKind outputKind) {
            return new ClassifiedRecord(
                    sourceMetadata.sourceCategory(), sourceMetadata, sourceMetadata.sourceTable(), sourceOperation,
                    outputKind);
        }

        SourceCategory sourceCategory() {
            return sourceCategory;
        }

        SourceMetadata sourceMetadata() {
            return sourceMetadata;
        }

        SourceTable sourceTable() {
            return sourceTable;
        }

        SourceOperation sourceOperation() {
            return sourceOperation;
        }

        OutputKind outputKind() {
            return outputKind;
        }
    }

    static final class ValidatedDocumentKey {
        private final Schema schema;
        private final String value;

        ValidatedDocumentKey(final String value) {
            this.schema = Schema.STRING_SCHEMA;
            this.value = value;
        }

        Schema schema() {
            return schema;
        }

        String value() {
            return value;
        }
    }

    static final class RetainedCacheRow {
        private final String projectName;
        private final String resourceName;
        private final String resourceVersion;
        private final long contentVersion;
        private final String streamEtag;
        private final String lastModifiedAt;
        private final String documentJson;

        RetainedCacheRow(
                final String projectName,
                final String resourceName,
                final String resourceVersion,
                final long contentVersion,
                final String streamEtag,
                final String lastModifiedAt,
                final String documentJson) {
            this.projectName = projectName;
            this.resourceName = resourceName;
            this.resourceVersion = resourceVersion;
            this.contentVersion = contentVersion;
            this.streamEtag = streamEtag;
            this.lastModifiedAt = lastModifiedAt;
            this.documentJson = documentJson;
        }

        String projectName() {
            return projectName;
        }

        String resourceName() {
            return resourceName;
        }

        String resourceVersion() {
            return resourceVersion;
        }

        long contentVersion() {
            return contentVersion;
        }

        String streamEtag() {
            return streamEtag;
        }

        String lastModifiedAt() {
            return lastModifiedAt;
        }

        String documentJson() {
            return documentJson;
        }
    }

    static final class SourceMetadata {
        private final SourceCategory sourceCategory;
        private final SourceTable sourceTable;
        private final String sourceSchema;
        private final String sourceTableName;
        private final Provider provider;

        SourceMetadata(
                final SourceCategory sourceCategory,
                final SourceTable sourceTable,
                final String sourceSchema,
                final String sourceTableName,
                final Provider provider) {
            this.sourceCategory = sourceCategory;
            this.sourceTable = sourceTable;
            this.sourceSchema = sourceSchema;
            this.sourceTableName = sourceTableName;
            this.provider = provider;
        }

        SourceCategory sourceCategory() {
            return sourceCategory;
        }

        SourceTable sourceTable() {
            return sourceTable;
        }

        String sourceSchema() {
            return sourceSchema;
        }

        String sourceTableName() {
            return sourceTableName;
        }

        Provider provider() {
            return provider;
        }
    }

    private static final class DebeziumSourceAdapter {
        private final Provider provider;
        private final String sourceSchemaName;

        DebeziumSourceAdapter(final Provider provider, final String sourceSchemaName) {
            this.provider = provider;
            this.sourceSchemaName = sourceSchemaName;
        }

        SourceMetadata sourceMetadata(final ConnectRecord<?> record) {
            final Struct value = requireStructValue(record, provider);
            final Schema valueSchema = requireValueSchema(record, provider);
            final var sourceField = valueSchema.field(SOURCE_FIELD);
            if (sourceField == null) {
                throw transformationFailure(FailureReason.MISSING_SOURCE_METADATA, provider, record, null, null);
            }
            if (sourceField.schema().type() != Schema.Type.STRUCT
                    || !sourceSchemaName.equals(sourceField.schema().name())
                    || sourceField.schema().isOptional()) {
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_METADATA_SHAPE, provider, record, null, null);
            }

            final Object source = value.getWithoutDefault(SOURCE_FIELD);
            if (!(source instanceof Struct)) {
                throw transformationFailure(FailureReason.MISSING_SOURCE_METADATA, provider, record, null, null);
            }
            final Struct sourceStruct = (Struct) source;
            if (!sourceSchemaName.equals(sourceStruct.schema().name())) {
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_METADATA_SHAPE, provider, record, null, null);
            }

            final String sourceSchema = sourceString(sourceStruct, SOURCE_SCHEMA_FIELD, record, null);
            final String sourceTableName = sourceString(sourceStruct, SOURCE_TABLE_FIELD, record, null);
            final SourceMetadata unclassified = new SourceMetadata(
                    SourceCategory.RELATIONAL, null, sourceSchema, sourceTableName, provider);
            if (!RELATIONAL_SCHEMA.equals(sourceSchema)) {
                throw transformationFailure(FailureReason.UNSUPPORTED_SOURCE_SCHEMA, record, unclassified, null);
            }

            final SourceTable sourceTable = SourceTable.fromName(sourceTableName, record, unclassified);
            return new SourceMetadata(
                    SourceCategory.RELATIONAL, sourceTable, sourceSchema, sourceTable.tableName(), provider);
        }

        ValidatedDocumentKey documentKey(
                final ConnectRecord<?> record, final ClassifiedRecord classifiedRecord) {
            final Object key = record.key();
            if (key == null) {
                throw classifiedFailure(FailureReason.MISSING_DOCUMENT_KEY, record, classifiedRecord);
            }

            final Schema keySchema = record.keySchema();
            if (keySchema == null || keySchema.type() != Schema.Type.STRUCT || keySchema.isOptional()
                    || !(key instanceof Struct)) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE, record, classifiedRecord);
            }

            final String documentUuid = documentUuid((Struct) key, record, classifiedRecord,
                    FailureReason.UNSUPPORTED_DOCUMENT_KEY_SHAPE);
            return new ValidatedDocumentKey(documentUuid);
        }

        RetainedCacheRow cacheRow(
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord,
                final ValidatedDocumentKey documentKey) {
            final Struct row = retainedAfterStruct(record, classifiedRecord);
            final String documentUuid = documentUuid(
                    row, record, classifiedRecord, FailureReason.UNSUPPORTED_DOCUMENT_UUID_SHAPE);
            validateRetainedRowDocumentUuid(documentKey, documentUuid, record, classifiedRecord);
            return cacheRow(row, record, classifiedRecord);
        }

        void validateDeleteBeforeDocumentUuid(
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord,
                final ValidatedDocumentKey documentKey) {
            final Struct beforeStruct = deleteBeforeStruct(record, classifiedRecord);
            if (beforeStruct == null) {
                return;
            }

            final var documentUuidField = beforeStruct.schema().field(DOCUMENT_UUID_FIELD);
            if (documentUuidField == null) {
                return;
            }
            final Object beforeDocumentUuid = beforeStruct.getWithoutDefault(DOCUMENT_UUID_FIELD);
            if (isAbsentDeleteBeforeDocumentUuid(beforeDocumentUuid, documentUuidField.schema())) {
                return;
            }

            final String normalizedBeforeDocumentUuid = documentUuid(
                    beforeStruct, record, classifiedRecord, FailureReason.UNSUPPORTED_DOCUMENT_UUID_SHAPE);
            if (!documentKey.value().equals(normalizedBeforeDocumentUuid)) {
                throw classifiedFailure(FailureReason.DOCUMENT_UUID_MISMATCH, record, classifiedRecord);
            }
        }

        private Struct deleteBeforeStruct(
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord) {
            final Schema valueSchema = requireValueSchema(record, provider);
            final var beforeField = valueSchema.field(BEFORE_FIELD);
            if (beforeField == null) {
                return null;
            }
            if (beforeField.schema().type() != Schema.Type.STRUCT) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_RETAINED_ROW_SHAPE, record, classifiedRecord);
            }

            final Object before = requireStructValue(record, provider).getWithoutDefault(BEFORE_FIELD);
            if (before == null) {
                return null;
            }
            if (!(before instanceof Struct)) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_RETAINED_ROW_SHAPE, record, classifiedRecord);
            }
            return (Struct) before;
        }

        private boolean isAbsentDeleteBeforeDocumentUuid(final Object value, final Schema schema) {
            return value == null
                    || (SQLSERVER_UNAVAILABLE_VALUE.equals(value)
                            && isPinnedSqlServerUnavailableBeforeDocumentUuid(schema));
        }

        private boolean isPinnedSqlServerUnavailableBeforeDocumentUuid(final Schema schema) {
            return provider == Provider.SQLSERVER && isPinnedDocumentUuidSchema(schema);
        }

        private Struct retainedAfterStruct(
                final ConnectRecord<?> record, final ClassifiedRecord classifiedRecord) {
            final Schema valueSchema = requireValueSchema(record, provider);
            final var afterField = valueSchema.field(AFTER_FIELD);
            if (afterField == null) {
                throw classifiedFailure(FailureReason.MISSING_RETAINED_ROW, record, classifiedRecord);
            }
            if (afterField.schema().type() != Schema.Type.STRUCT) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_RETAINED_ROW_SHAPE, record, classifiedRecord);
            }

            final Object after = requireStructValue(record, provider).getWithoutDefault(AFTER_FIELD);
            if (!(after instanceof Struct)) {
                throw classifiedFailure(FailureReason.MISSING_RETAINED_ROW, record, classifiedRecord);
            }
            return (Struct) after;
        }

        private RetainedCacheRow cacheRow(
                final Struct row,
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord) {
            return new RetainedCacheRow(
                    requiredString(row, PROJECT_NAME_FIELD, FieldKind.PLAIN_STRING, record, classifiedRecord),
                    requiredString(row, RESOURCE_NAME_FIELD, FieldKind.PLAIN_STRING, record, classifiedRecord),
                    requiredString(row, RESOURCE_VERSION_FIELD, FieldKind.PLAIN_STRING, record, classifiedRecord),
                    requiredContentVersion(row, record, classifiedRecord),
                    requiredString(row, STREAM_ETAG_FIELD, FieldKind.PLAIN_STRING, record, classifiedRecord),
                    requiredLastModifiedAt(row, record, classifiedRecord),
                    requiredString(row, DOCUMENT_JSON_FIELD, FieldKind.DOCUMENT_JSON, record, classifiedRecord));
        }

        private String requiredString(
                final Struct row,
                final String fieldName,
                final FieldKind fieldKind,
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord) {
            final var field = row.schema().field(fieldName);
            if (field == null) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            if (!isPinnedStringSchema(field.schema(), fieldKind)) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE, record, classifiedRecord);
            }

            final Object value = row.getWithoutDefault(fieldName);
            if (value == null) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            if (!(value instanceof String)) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE, record, classifiedRecord);
            }
            final String stringValue = (String) value;
            if (stringValue.isEmpty()) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            if (fieldKind == FieldKind.DOCUMENT_JSON
                    && provider == Provider.SQLSERVER
                    && SQLSERVER_UNAVAILABLE_VALUE.equals(stringValue)) {
                throw classifiedFailure(FailureReason.UNAVAILABLE_DOCUMENT_JSON, record, classifiedRecord);
            }
            return stringValue;
        }

        private long requiredContentVersion(
                final Struct row,
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord) {
            final var field = row.schema().field(CONTENT_VERSION_FIELD);
            if (field == null) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            final Schema schema = field.schema();
            if (schema.type() != Schema.Type.INT64 || schema.isOptional() || schema.name() != null) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE, record, classifiedRecord);
            }

            final Object value = row.getWithoutDefault(CONTENT_VERSION_FIELD);
            if (value == null) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            if (!(value instanceof Long)) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE, record, classifiedRecord);
            }
            return (Long) value;
        }

        private String requiredLastModifiedAt(
                final Struct row,
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord) {
            final var field = row.schema().field(LAST_MODIFIED_AT_FIELD);
            if (field == null) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            if (!isPinnedLastModifiedAtSchema(field.schema())) {
                throw classifiedFailure(FailureReason.UNSUPPORTED_REQUIRED_FIELD_SHAPE, record, classifiedRecord);
            }

            final Object value = row.getWithoutDefault(LAST_MODIFIED_AT_FIELD);
            if (value == null) {
                throw classifiedFailure(FailureReason.MISSING_REQUIRED_FIELD, record, classifiedRecord);
            }
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                throw classifiedFailure(FailureReason.INVALID_LAST_MODIFIED_AT, record, classifiedRecord);
            }
            return DocumentStateJson.normalizeLastModifiedAt((String) value, record, classifiedRecord);
        }

        private boolean isPinnedStringSchema(final Schema schema, final FieldKind fieldKind) {
            if (schema.type() != Schema.Type.STRING || schema.isOptional()) {
                return false;
            }
            if (fieldKind == FieldKind.DOCUMENT_JSON && provider == Provider.POSTGRESQL) {
                return POSTGRESQL_JSON_SCHEMA_NAME.equals(schema.name());
            }
            return schema.name() == null;
        }

        private boolean isPinnedLastModifiedAtSchema(final Schema schema) {
            if (schema.type() != Schema.Type.STRING || schema.isOptional()) {
                return false;
            }
            if (provider == Provider.POSTGRESQL) {
                return POSTGRESQL_TIMESTAMP_SCHEMA_NAME.equals(schema.name());
            }
            if (provider == Provider.SQLSERVER) {
                return SQLSERVER_TIMESTAMP_SCHEMA_NAME.equals(schema.name());
            }
            return false;
        }

        private String documentUuid(
                final Struct struct,
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord,
                final FailureReason unsupportedShapeReason) {
            final var documentUuidField = struct.schema().field(DOCUMENT_UUID_FIELD);
            if (documentUuidField == null) {
                throw classifiedFailure(FailureReason.MISSING_DOCUMENT_UUID, record, classifiedRecord);
            }
            if (!isPinnedDocumentUuidSchema(documentUuidField.schema())) {
                throw classifiedFailure(unsupportedShapeReason, record, classifiedRecord);
            }

            final Object value = struct.getWithoutDefault(DOCUMENT_UUID_FIELD);
            if (value == null) {
                throw classifiedFailure(FailureReason.MISSING_DOCUMENT_UUID, record, classifiedRecord);
            }
            if (!(value instanceof String)) {
                throw classifiedFailure(unsupportedShapeReason, record, classifiedRecord);
            }
            return normalizeDocumentUuid((String) value, record, classifiedRecord);
        }

        private boolean isPinnedDocumentUuidSchema(final Schema schema) {
            if (schema.type() != Schema.Type.STRING || schema.isOptional()) {
                return false;
            }
            if (provider == Provider.POSTGRESQL) {
                return POSTGRESQL_UUID_SCHEMA_NAME.equals(schema.name());
            }
            if (provider == Provider.SQLSERVER) {
                return schema.name() == null;
            }
            return false;
        }

        private String normalizeDocumentUuid(
                final String value,
                final ConnectRecord<?> record,
                final ClassifiedRecord classifiedRecord) {
            if (!isUuidDFormat(value)) {
                throw classifiedFailure(FailureReason.INVALID_DOCUMENT_UUID, record, classifiedRecord);
            }
            final StringBuilder normalized = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                normalized.append(Character.toLowerCase(value.charAt(i)));
            }
            return normalized.toString();
        }

        private boolean isUuidDFormat(final String value) {
            if (value.length() != 36) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                final char character = value.charAt(i);
                if (isUuidHyphenIndex(i)) {
                    if (character != '-') {
                        return false;
                    }
                } else if (!isHexDigit(character)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isUuidHyphenIndex(final int index) {
            return index == 8 || index == 13 || index == 18 || index == 23;
        }

        private boolean isHexDigit(final char character) {
            if ('0' <= character && character <= '9') {
                return true;
            }
            if ('a' <= character && character <= 'f') {
                return true;
            }
            return 'A' <= character && character <= 'F';
        }

        private String sourceString(
                final Struct sourceStruct,
                final String fieldName,
                final ConnectRecord<?> record,
                final SourceMetadata sourceMetadata) {
            final var field = sourceStruct.schema().field(fieldName);
            if (field == null || field.schema().type() != Schema.Type.STRING || field.schema().isOptional()) {
                throw transformationFailure(
                        FailureReason.UNSUPPORTED_SOURCE_METADATA_SHAPE, provider, record, sourceMetadata, null);
            }
            final Object value = sourceStruct.getWithoutDefault(fieldName);
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                throw transformationFailure(FailureReason.MISSING_SOURCE_METADATA, provider, record, sourceMetadata,
                        null);
            }
            return (String) value;
        }
    }

    static final class Settings {
        private final Provider provider;
        private final String targetTopic;
        private final String progressTopic;
        private final DebeziumSourceAdapter sourceAdapter;

        Settings(final Provider provider, final String targetTopic, final String progressTopic) {
            this.provider = provider;
            this.targetTopic = targetTopic;
            this.progressTopic = progressTopic;
            this.sourceAdapter = sourceAdapter(provider);
        }

        Provider provider() {
            return provider;
        }

        String targetTopic() {
            return targetTopic;
        }

        String progressTopic() {
            return progressTopic;
        }

        DebeziumSourceAdapter sourceAdapter() {
            return sourceAdapter;
        }

        private static DebeziumSourceAdapter sourceAdapter(final Provider provider) {
            if (provider == Provider.POSTGRESQL) {
                return new DebeziumSourceAdapter(provider, POSTGRESQL_SOURCE_SCHEMA_NAME);
            }
            if (provider == Provider.SQLSERVER) {
                return new DebeziumSourceAdapter(provider, SQLSERVER_SOURCE_SCHEMA_NAME);
            }
            throw new ConfigException(PROVIDER_CONFIG, provider, "must be exactly 'postgresql' or 'sqlserver'");
        }
    }
}
