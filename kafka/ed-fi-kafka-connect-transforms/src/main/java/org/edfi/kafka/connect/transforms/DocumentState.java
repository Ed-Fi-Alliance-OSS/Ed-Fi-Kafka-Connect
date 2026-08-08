// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

package org.edfi.kafka.connect.transforms;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
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

    private static final String SOURCE_FIELD = "source";
    private static final String SOURCE_SCHEMA_FIELD = "schema";
    private static final String SOURCE_TABLE_FIELD = "table";
    private static final String OPERATION_FIELD = "op";
    private static final String RELATIONAL_SCHEMA = "dms";
    private static final String DOCUMENT_CACHE_TABLE = "DocumentCache";
    private static final String DOCUMENT_TABLE = "Document";
    private static final String HEARTBEAT_TABLE = "CdcHeartbeat";
    private static final String PROJECTION_WORK_TABLE = "DocumentProjectionWork";
    private static final String POSTGRESQL_SOURCE_SCHEMA_NAME = "io.debezium.connector.postgresql.Source";
    private static final String SQLSERVER_SOURCE_SCHEMA_NAME = "io.debezium.connector.sqlserver.Source";
    private static final int MAX_METADATA_VALUE_LENGTH = 128;

    private static final ConfigDef.Validator PROVIDER_VALIDATOR = (name, value) -> {
        if (!(POSTGRESQL_PROVIDER.equals(value) || SQLSERVER_PROVIDER.equals(value))) {
            throw new ConfigException(name, value, "must be exactly 'postgresql' or 'sqlserver'");
        }
    };

    private static final ConfigDef.Validator NON_EMPTY_STRING = (name, value) -> {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new ConfigException(name, value, "must be a non-empty string");
        }
    };

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(PROVIDER_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    PROVIDER_VALIDATOR, ConfigDef.Importance.HIGH,
                    "Relational source provider. Must be exactly 'postgresql' or 'sqlserver'.")
            .define(TARGET_TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    NON_EMPTY_STRING, ConfigDef.Importance.HIGH,
                    "Public document topic.")
            .define(PROGRESS_TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    NON_EMPTY_STRING, ConfigDef.Importance.HIGH,
                    "Progress topic, derived as target.topic plus '.cdc-progress'.");

    private Settings settings;

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void configure(final Map<String, ?> configs) {
        validateRawProvider(configs);

        final AbstractConfig parsed = new AbstractConfig(CONFIG_DEF, configs);
        final String targetTopic = parsed.getString(TARGET_TOPIC_CONFIG);
        final String progressTopic = parsed.getString(PROGRESS_TOPIC_CONFIG);

        if (!progressTopic.equals(targetTopic + PROGRESS_TOPIC_SUFFIX)) {
            throw new ConfigException(PROGRESS_TOPIC_CONFIG, progressTopic,
                    "must equal target.topic plus '" + PROGRESS_TOPIC_SUFFIX + "'");
        }

        this.settings = new Settings(providerFrom(parsed.getString(PROVIDER_CONFIG)), targetTopic, progressTopic);
    }

    @Override
    public R apply(final R record) {
        final ClassifiedRecord classifiedRecord = classify(record);
        if (classifiedRecord.outputKind() == OutputKind.DROP) {
            return null;
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

    private static void validateRawProvider(final Map<String, ?> configs) {
        final Object provider = configs.get(PROVIDER_CONFIG);
        if (!(POSTGRESQL_PROVIDER.equals(provider) || SQLSERVER_PROVIDER.equals(provider))) {
            throw new ConfigException(PROVIDER_CONFIG, provider, "must be exactly 'postgresql' or 'sqlserver'");
        }
    }

    private static Provider providerFrom(final String provider) {
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
        return topic != null
                && topic.startsWith(NATIVE_HEARTBEAT_TOPIC_PREFIX)
                && topic.length() > NATIVE_HEARTBEAT_TOPIC_PREFIX.length();
    }

    private static SourceOperation sourceOperation(
            final ConnectRecord<?> record, final SourceMetadata sourceMetadata) {
        final Schema valueSchema = requireValueSchema(record);
        final Field operationField = valueSchema.field(OPERATION_FIELD);
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
                return OutputKind.PROGRESS;
            case TRUNCATE:
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
            if (classifiedRecord.sourceTable() != null) {
                appendMetadata(metadata, "sourceTable", classifiedRecord.sourceTable().tableName());
            }
            if (classifiedRecord.sourceOperation() != null) {
                appendMetadata(metadata, "operation", classifiedRecord.sourceOperation().code());
            }
        }
        return new TransformationFailureException(reason, metadata);
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
        UNSUPPORTED_SOURCE_SCHEMA("unsupported source schema"),
        UNSUPPORTED_SOURCE_TABLE("unsupported source table"),
        UNEXPECTED_RETAINED_SOURCE_TABLE("unexpected retained source table"),
        UNSUPPORTED_SOURCE_OPERATION("unsupported source operation"),
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
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        public FailureReason reason() {
            return reason;
        }

        public Map<String, String> metadata() {
            return metadata;
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

    static final class ClassifiedRecord {
        private final SourceCategory sourceCategory;
        private final SourceTable sourceTable;
        private final SourceOperation sourceOperation;
        private final OutputKind outputKind;

        private ClassifiedRecord(
                final SourceCategory sourceCategory,
                final SourceTable sourceTable,
                final SourceOperation sourceOperation,
                final OutputKind outputKind) {
            this.sourceCategory = sourceCategory;
            this.sourceTable = sourceTable;
            this.sourceOperation = sourceOperation;
            this.outputKind = outputKind;
        }

        static ClassifiedRecord nativeHeartbeat() {
            return new ClassifiedRecord(SourceCategory.NATIVE_HEARTBEAT, null, null, OutputKind.PROGRESS);
        }

        static ClassifiedRecord relational(
                final SourceMetadata sourceMetadata,
                final SourceOperation sourceOperation,
                final OutputKind outputKind) {
            return new ClassifiedRecord(
                    sourceMetadata.sourceCategory(), sourceMetadata.sourceTable(), sourceOperation, outputKind);
        }

        SourceCategory sourceCategory() {
            return sourceCategory;
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

    interface SourceAdapter {
        SourceMetadata sourceMetadata(ConnectRecord<?> record);
    }

    static final class DebeziumSourceAdapter implements SourceAdapter {
        private final Provider provider;
        private final String sourceSchemaName;

        DebeziumSourceAdapter(final Provider provider, final String sourceSchemaName) {
            this.provider = provider;
            this.sourceSchemaName = sourceSchemaName;
        }

        @Override
        public SourceMetadata sourceMetadata(final ConnectRecord<?> record) {
            final Struct value = requireStructValue(record, provider);
            final Schema valueSchema = requireValueSchema(record, provider);
            final Field sourceField = valueSchema.field(SOURCE_FIELD);
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

        private String sourceString(
                final Struct sourceStruct,
                final String fieldName,
                final ConnectRecord<?> record,
                final SourceMetadata sourceMetadata) {
            final Field field = sourceStruct.schema().field(fieldName);
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
        private final SourceAdapter sourceAdapter;

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

        SourceAdapter sourceAdapter() {
            return sourceAdapter;
        }

        private static SourceAdapter sourceAdapter(final Provider provider) {
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
