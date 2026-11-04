/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** The one fixed journal table: native QQQ metadata for visible, read-only
 ** provenance and status, and explicit H2/MySQL DDL. The bucket, storage key,
 ** manifest hash and lease token are physical columns only; they are NOT QQQ
 ** fields, so no UI or API surface can return a raw object locator or lease.
 ** Only the hash of the locator is exposed.
 **
 ** The producer registers this table; hosts apply the appropriate owner DDL
 ** through migration tooling before enabling retained upload intake.
 *******************************************************************************/
public final class UploadJournalTable
{
   public static final String NAME = CollaborationTables.UPLOAD_JOURNAL;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private UploadJournalTable()
   {
   }



   /*******************************************************************************
    ** Table metadata with no editable field; direct writes are denied by
    ** UploadJournalSecurity. Field names line up with the parent-reference
    ** metadata used by attachments (tableName, recordId, createdByUserId).
    *******************************************************************************/
   public static QTableMetaData metaData(CollaborationQBitConfig config)
   {
      return (new QTableMetaData().withName(NAME).withLabel("Retained Upload Journal").withIcon(new QIcon("history_edu"))
         .withBackendName(config.getBackendName()).withPrimaryKeyField("id")
         .withPermissionRules(CollaborationPermissions.permissionRules().withCustomPermissionChecker(new QCodeReference(UploadJournalSecurity.class)))
         .withCustomizer(com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers.POST_QUERY_RECORD, new QCodeReference(UploadJournalSecurity.class))
         .withoutCapabilities(com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_COUNT, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.QUERY_STATS)
         .withRecordLabelFormatAndFields("%s", "fileName")
         .withUniqueKey(new UniqueKey("locatorHash"))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withLabel("ID").withIsEditable(false))
         .withField(new QFieldMetaData("createDate", QFieldType.DATE_TIME).withLabel("Created").withIsEditable(false))
         .withField(new QFieldMetaData("modifyDate", QFieldType.DATE_TIME).withLabel("Modified").withIsEditable(false))
         .withField(string("tableName", "Table", 128))
         .withField(string("recordId", "Record ID", 64))
         .withField(string("createdByUserId", "Original Actor", 255).withPossibleValueSourceName(CollaborationTables.USER))
         .withField(string("fileName", "File Name", 255))
         .withField(string("contentType", "Content Type", 255))
         .withField(new QFieldMetaData("description", QFieldType.TEXT).withLabel("Description").withIsEditable(false))
         .withField(new QFieldMetaData("sizeBytes", QFieldType.LONG).withLabel("Size (bytes)").withIsEditable(false))
         .withField(string("sha256", "SHA-256", 64))
         .withField(new QFieldMetaData("retained", QFieldType.BOOLEAN).withLabel("Retained").withIsEditable(false))
         .withField(string("intakeKey", "Intake Key", 64))
         .withField(string("intakeFingerprint", "Intake Fingerprint", 64))
         .withField(string("locatorHash", "Object Location Hash", 64))
         .withField(string("state", "Registration State", 16))
         .withField(string("storageState", "Original Object", 16))
         .withField(new QFieldMetaData("attachmentId", QFieldType.INTEGER).withLabel("Attachment").withIsEditable(false)
            .withPossibleValueSourceName(CollaborationTables.ATTACHMENT))
         .withField(new QFieldMetaData("leaseUntil", QFieldType.DATE_TIME).withLabel("Lease Until").withIsEditable(false))
         .withField(new QFieldMetaData("fence", QFieldType.LONG).withLabel("Reservation Count").withIsEditable(false))
         .withSection(new QFieldSection("identity", new QIcon("badge"), Tier.T1, List.of("id", "fileName", "state", "storageState", "attachmentId")).withLabel("Status"))
         .withSection(new QFieldSection("source", new QIcon("link"), Tier.T2, List.of("tableName", "recordId", "createdByUserId", "contentType", "sizeBytes", "sha256", "description")).withLabel("Source"))
         .withSection(new QFieldSection("recovery", new QIcon("storage"), Tier.T3, List.of("locatorHash", "intakeKey", "intakeFingerprint", "leaseUntil", "fence", "retained")).withLabel("Recovery")));
   }



   /*******************************************************************************
    ** H2 DDL for the qualified adapter only. Unquoted lowercase identifiers match
    ** QQQ's inferred snake_case backend names. The constraints are database
    ** guards: retained rows only, forward-only states, intake pairing, and no
    ** REGISTERED/COMPLETE row without its attachment id. PostgreSQL has its own
    ** script, postgresScript().
    *******************************************************************************/
   public static String h2Script()
   {
      return ("CREATE TABLE IF NOT EXISTS collaboration_upload_journal (\n"
         + "   id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,\n"
         + "   create_date TIMESTAMP NOT NULL,\n"
         + "   modify_date TIMESTAMP NOT NULL,\n"
         + "   table_name VARCHAR(128) NOT NULL,\n"
         + "   record_id VARCHAR(64) NOT NULL,\n"
         + "   created_by_user_id VARCHAR(255) NOT NULL,\n"
         + "   file_name VARCHAR(255) NOT NULL,\n"
         + "   content_type VARCHAR(255) NOT NULL,\n"
         + "   description VARCHAR(4096),\n"
         + "   size_bytes BIGINT NOT NULL,\n"
         + "   sha256 VARCHAR(64) NOT NULL,\n"
         + "   bucket_name VARCHAR(255) NOT NULL,\n"
         + "   storage_key VARCHAR(1024) NOT NULL,\n"
         + "   retained BOOLEAN NOT NULL,\n"
         + "   intake_key VARCHAR(64),\n"
         + "   intake_fingerprint VARCHAR(64),\n"
         + "   locator_hash VARCHAR(64) NOT NULL,\n"
         + "   manifest_hash VARCHAR(64) NOT NULL,\n"
         + "   state VARCHAR(16) NOT NULL,\n"
         + "   storage_state VARCHAR(16) NOT NULL,\n"
         + "   attachment_id INTEGER,\n"
         + "   lease_token VARCHAR(64),\n"
         + "   lease_until TIMESTAMP,\n"
         + "   fence BIGINT NOT NULL,\n"
         + "   CONSTRAINT ck_upload_journal_retained CHECK (retained = TRUE),\n"
         + "   CONSTRAINT ck_upload_journal_state CHECK (state IN ('PREPARED', 'REGISTERED', 'COMPLETE')),\n"
         + "   CONSTRAINT ck_upload_journal_storage CHECK (storage_state IN ('PENDING', 'UNKNOWN', 'VERIFIED')),\n"
         + "   CONSTRAINT ck_upload_journal_intake CHECK ((intake_key IS NULL) = (intake_fingerprint IS NULL)),\n"
         + "   CONSTRAINT ck_upload_journal_registered CHECK (state = 'PREPARED' OR attachment_id IS NOT NULL),\n"
         + "   CONSTRAINT ck_upload_journal_verified CHECK (state = 'PREPARED' OR storage_state = 'VERIFIED')\n"
         + ");\n"
         + "CREATE UNIQUE INDEX IF NOT EXISTS uq_collaboration_upload_journal_locator ON collaboration_upload_journal (locator_hash);\n"
         + "CREATE UNIQUE INDEX IF NOT EXISTS uq_collaboration_upload_journal_intake ON collaboration_upload_journal (intake_key);\n"
         + "CREATE INDEX IF NOT EXISTS idx_collaboration_upload_journal_open ON collaboration_upload_journal (state, id);\n"
         + "CREATE INDEX IF NOT EXISTS idx_collaboration_upload_journal_parent ON collaboration_upload_journal (table_name, record_id);\n");
   }



   /*******************************************************************************
    ** PostgreSQL 17 schema for the native postgres backend. Same columns, CHECK
    ** guards and unique/ordinary indexes as the H2 script; instants are
    ** TIMESTAMPTZ so a lease fence round-trips independent of the JVM zone.
    ** Default (deterministic) collation keeps identifiers exact. Statements are
    ** idempotent, so the script is safe to re-run.
    *******************************************************************************/
   public static String postgresScript()
   {
      return (h2Script().replace("TIMESTAMP NOT NULL,", "TIMESTAMPTZ NOT NULL,").replace("lease_until TIMESTAMP,", "lease_until TIMESTAMPTZ,"));
   }



   /*******************************************************************************
    ** MySQL 8.4 schema. Binary NO PAD comparison preserves exact identifiers;
    ** millisecond timestamps preserve lease fences across connection round trips.
    *******************************************************************************/
   public static String mysqlScript()
   {
      return ("CREATE TABLE IF NOT EXISTS collaboration_upload_journal (\n"
         + "   id INTEGER AUTO_INCREMENT PRIMARY KEY,\n"
         + "   create_date TIMESTAMP(3) NOT NULL,\n"
         + "   modify_date TIMESTAMP(3) NOT NULL,\n"
         + "   table_name VARCHAR(128) NOT NULL,\n"
         + "   record_id VARCHAR(64) NOT NULL,\n"
         + "   created_by_user_id VARCHAR(255) NOT NULL,\n"
         + "   file_name VARCHAR(255) NOT NULL,\n"
         + "   content_type VARCHAR(255) NOT NULL,\n"
         + "   description VARCHAR(4096),\n"
         + "   size_bytes BIGINT NOT NULL,\n"
         + "   sha256 VARCHAR(64) NOT NULL,\n"
         + "   bucket_name VARCHAR(255) NOT NULL,\n"
         + "   storage_key VARCHAR(1024) NOT NULL,\n"
         + "   retained BOOLEAN NOT NULL,\n"
         + "   intake_key VARCHAR(64),\n"
         + "   intake_fingerprint VARCHAR(64),\n"
         + "   locator_hash VARCHAR(64) NOT NULL,\n"
         + "   manifest_hash VARCHAR(64) NOT NULL,\n"
         + "   state VARCHAR(16) NOT NULL,\n"
         + "   storage_state VARCHAR(16) NOT NULL,\n"
         + "   attachment_id INTEGER,\n"
         + "   lease_token VARCHAR(64),\n"
         + "   lease_until TIMESTAMP(3),\n"
         + "   fence BIGINT NOT NULL,\n"
         + "   CONSTRAINT ck_upload_journal_retained CHECK (retained = TRUE),\n"
         + "   CONSTRAINT ck_upload_journal_state CHECK (state IN ('PREPARED', 'REGISTERED', 'COMPLETE')),\n"
         + "   CONSTRAINT ck_upload_journal_storage CHECK (storage_state IN ('PENDING', 'UNKNOWN', 'VERIFIED')),\n"
         + "   CONSTRAINT ck_upload_journal_intake CHECK ((intake_key IS NULL) = (intake_fingerprint IS NULL)),\n"
         + "   CONSTRAINT ck_upload_journal_registered CHECK (state = 'PREPARED' OR attachment_id IS NOT NULL),\n"
         + "   CONSTRAINT ck_upload_journal_verified CHECK (state = 'PREPARED' OR storage_state = 'VERIFIED'),\n"
         + "   UNIQUE KEY uq_collaboration_upload_journal_locator (locator_hash),\n"
         + "   UNIQUE KEY uq_collaboration_upload_journal_intake (intake_key),\n"
         + "   KEY idx_collaboration_upload_journal_open (state, id),\n"
         + "   KEY idx_collaboration_upload_journal_parent (table_name, record_id)\n"
         + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;\n");
   }



   /*******************************************************************************
    ** A bounded read-only string field.
    *******************************************************************************/
   private static QFieldMetaData string(String name, String label, Integer length)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label).withMaxLength(length).withIsEditable(false));
   }
}
