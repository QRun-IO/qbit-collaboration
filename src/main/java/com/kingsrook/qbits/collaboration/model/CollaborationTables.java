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


package com.kingsrook.qbits.collaboration.model;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.attachments.AttachmentPreviewCustomizer;
import com.kingsrook.qbits.collaboration.attachments.AttachmentSecurity;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.values.FeatureScopeNamePossibleValueProvider;
import com.kingsrook.qbits.collaboration.values.RecordFieldNamePossibleValueProvider;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter.BooleanOperator;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.audits.QAuditRules;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.AdornmentType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.FieldAdornment;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSectionAlternativeType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** Visible Collaboration tables. Enum ids use the names of the core
 ** collaboration enums; replace them when those contracts are released.
 *******************************************************************************/
public final class CollaborationTables
{
   public static final String FIELD_DEFINITION = "collaboration_field_definition";
   public static final String FIELD_VALUE = "collaboration_field_value";
   public static final String SCOPE_NAME = "collaboration_scope_name";
   public static final String RECORD_FIELD_NAME = "collaboration_record_field_name";
   public static final String SHARE = "collaboration_share";
   public static final String ACCESS_REQUEST = "collaboration_access_request";
   public static final String TASK = "collaboration_task";
   public static final String RECORD_ASSIGNMENT = "collaboration_record_assignment";
   public static final String TAG = "collaboration_tag";
   public static final String RECORD_TAG = "collaboration_record_tag";
   public static final String COMMENT = "collaboration_comment";
   public static final String COMMENT_REVISION = "collaboration_comment_revision";
   public static final String MENTION = "collaboration_mention";
   public static final String FOLLOW = "collaboration_follow";
   public static final String ACTIVITY = "collaboration_activity";
   public static final String FOLLOW_DIGEST = "collaboration_follow_digest";
   public static final String ATTACHMENT = "collaboration_attachment";
   public static final String FEATURE_SETTING = "collaboration_feature_setting";
   public static final String FAVORITE = "collaboration_favorite";
   public static final String RECENT = "collaboration_recent";
   public static final String TARGET_TYPE = "collaboration_target_type";
   public static final String USER = "collaboration_user";
   public static final String SCOPE_TYPE = "collaboration_scope_type";
   public static final String FEATURE = "collaboration_feature";
   public static final String PERMISSION_MODE = "collaboration_permission_mode";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationTables()
   {
   }



   /*******************************************************************************
    ** Enumerated feature settings and table references.
    *******************************************************************************/
   public static List<QPossibleValueSource> possibleValueSources()
   {
      return (List.of(
         QPossibleValueSource.newForTable(FIELD_DEFINITION), QPossibleValueSource.newForTable(FIELD_VALUE),
         enumSource("collaboration_field_type", "STRING", "TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATE_TIME", "SELECT", "MULTI_SELECT", "USER", "RECORD_LINK"),
         enumSource("collaboration_field_status", "DRAFT", "ACTIVE", "ARCHIVED"),
         enumSource("collaboration_field_index_state", "NONE", "BUILDING", "READY", "FAILED"),
         enumSource("collaboration_field_classification", "PUBLIC", "CONFIDENTIAL", "PII", "PHI"),
         new QPossibleValueSource().withName("collaboration_custom_field_tables").withType(QPossibleValueSourceType.CUSTOM).withIdType(QFieldType.STRING)
            .withCustomCodeReference(new QCodeReference(com.kingsrook.qbits.collaboration.customfields.CustomFieldTablesProvider.class)),



         new QPossibleValueSource().withName(SCOPE_NAME).withType(QPossibleValueSourceType.CUSTOM)
            .withCustomCodeReference(new QCodeReference(FeatureScopeNamePossibleValueProvider.class)),
         new QPossibleValueSource().withName(RECORD_FIELD_NAME).withType(QPossibleValueSourceType.CUSTOM)
            .withCustomCodeReference(new QCodeReference(RecordFieldNamePossibleValueProvider.class)),
         QPossibleValueSource.newForTable(SHARE), QPossibleValueSource.newForTable(ACCESS_REQUEST),
         QPossibleValueSource.newForTable(TASK), QPossibleValueSource.newForTable(RECORD_ASSIGNMENT),
         enumSource("collaboration_access_request_status", "OPEN", "GRANTED", "DECLINED", "WITHDRAWN"),
         enumSource("collaboration_task_status", "OPEN", "DONE", "CANCELLED"),
         QPossibleValueSource.newForTable(TAG), QPossibleValueSource.newForTable(RECORD_TAG),
         QPossibleValueSource.newForTable(COMMENT), QPossibleValueSource.newForTable(COMMENT_REVISION), QPossibleValueSource.newForTable(MENTION), QPossibleValueSource.newForTable(FOLLOW), QPossibleValueSource.newForTable(ACTIVITY), QPossibleValueSource.newForTable(FOLLOW_DIGEST),
         enumSource(TARGET_TYPE, "TABLE", "APP", "RECORD", "PROCESS", "REPORT", "SAVED_VIEW"),
         QPossibleValueSource.newForTable(ATTACHMENT), QPossibleValueSource.newForTable(FAVORITE), QPossibleValueSource.newForTable(RECENT),
         enumSource(SCOPE_TYPE, "INSTANCE", "APP", "TABLE"),
         enumSource(FEATURE, "ATTACHMENTS", "TAGS", "CUSTOM_FIELDS", "COMMENTS", "PRESENCE", "SHARING",
            "FAVORITES", "FOLLOW", "TIMELINE", "RECENTS", "TASKS", "ASSIGNMENT"),
         enumSource(PERMISSION_MODE, "EDIT_OR_GRANTED", "GRANTED_ONLY"),
         QPossibleValueSource.newForTable(FEATURE_SETTING), QPossibleValueSource.newForTable(USER).withOverrideIdField("userId")));
   }



   /*******************************************************************************
    ** String ids preserve the names of the planned core enums.
    *******************************************************************************/
   private static QPossibleValueSource enumSource(String name, String... values)
   {
      List<QPossibleValue<?>> entries = new ArrayList<>();
      for(String value : values)
      {
         entries.add(new QPossibleValue<>(value, value));
      }
      return (new QPossibleValueSource().withName(name).withType(QPossibleValueSourceType.ENUM).withEnumValues(entries));
   }



   /*******************************************************************************
    ** Build the tables and stable SQL column names. QQQ infers the SQL table
    ** name from the already snake_case metadata name for RDBMS backends.
    *******************************************************************************/
   public static List<QTableMetaData> tables(CollaborationQBitConfig config)
   {
      QTableMetaData settings = base(config, FEATURE_SETTING, "Feature Settings", "settings")
         .withRecordLabelFormatAndFields("%s %s: %s", "scopeType", "scopeName", "feature")
         .withField(string("scopeType", "Scope Type", 20).withIsRequired(true).withPossibleValueSourceName(SCOPE_TYPE))
         // TODO: add .withDependsOn("scopeType") when QQQ core ships contingent dependsOn (docs/CONTINGENT-POSSIBLE-VALUES.md)
         .withField(string("scopeName", "Scope Name", 128).withIsRequired(true).withPossibleValueSourceName(SCOPE_NAME))
         .withField(string("feature", "Feature", 32).withIsRequired(true).withPossibleValueSourceName(FEATURE))
         .withField(new QFieldMetaData("enabled", QFieldType.BOOLEAN).withLabel("Enabled").withIsRequired(true).withDefaultValue(false))
         .withField(new QFieldMetaData("settings", QFieldType.TEXT).withLabel("Settings (JSON)"))
         .withField(string("permissionMode", "Permission Mode", 32).withPossibleValueSourceName(PERMISSION_MODE).withDefaultValue("EDIT_OR_GRANTED"))
         .withUniqueKey(new UniqueKey("scopeType", "scopeName", "feature"))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
         .withSection(new QFieldSection("identity", new QIcon("badge"), Tier.T1, List.of("id", "scopeType", "scopeName", "feature")).withLabel("Identity"))
         .withSection(new QFieldSection("details", new QIcon("settings"), Tier.T2, List.of("enabled", "settings", "permissionMode")).withLabel("Details"));
      settings.withCustomizer(TableCustomizers.POST_INSERT_RECORD, new QCodeReference(com.kingsrook.qbits.collaboration.FeatureSettingsCustomizer.class))
         .withCustomizer(TableCustomizers.POST_UPDATE_RECORD, new QCodeReference(com.kingsrook.qbits.collaboration.FeatureSettingsCustomizer.class))
         .withCustomizer(TableCustomizers.POST_DELETE_RECORD, new QCodeReference(com.kingsrook.qbits.collaboration.FeatureSettingsCustomizer.class));
      QTableMetaData people = base(config, USER, "People", "people")
         .withRecordLabelFormatAndFields("%s", "displayName")
         .withField(string("userId", "User ID", 255).withIsRequired(true))
         .withField(string("displayName", "Display Name", 255))
         .withField(string("email", "Email", 255))
         .withField(string("avatarUrl", "Avatar URL", 2048))
         .withField(new QFieldMetaData("lastSeenAt", QFieldType.DATE_TIME).withLabel("Last Seen At"))
         .withField(new QFieldMetaData("active", QFieldType.BOOLEAN).withLabel("Active").withDefaultValue(true))
         .withUniqueKey(new UniqueKey("userId"))
         .withSection(new QFieldSection("identity", new QIcon("badge"), Tier.T1, List.of("id", "userId", "displayName")).withLabel("Identity"))
         .withSection(new QFieldSection("details", new QIcon("people"), Tier.T2, List.of("email", "avatarUrl", "lastSeenAt", "active")).withLabel("Details"));
      for(QFieldMetaData field : people.getFields().values())
      {
         field.setIsEditable(false);
      }
      QTableMetaData favorites = personal(config, FAVORITE, "Favorites", "star")
         .withField(new QFieldMetaData("sortOrder", QFieldType.INTEGER).withLabel("Sort Order").withIsRequired(true).withDefaultValue(0))
         .withField(string("folder", "Folder", 255))
         .withSection(new QFieldSection("details", new QIcon("star"), Tier.T2, List.of("label", "sortOrder", "folder")).withLabel("Details"));
      QTableMetaData recents = personal(config, RECENT, "Recently Viewed", "history")
         .withField(new QFieldMetaData("lastViewedAt", QFieldType.DATE_TIME).withLabel("Last Viewed At").withIsRequired(true))
         .withField(new QFieldMetaData("viewCount", QFieldType.INTEGER).withLabel("View Count").withIsRequired(true).withDefaultValue(1))
         .withSection(new QFieldSection("details", new QIcon("history"), Tier.T2, List.of("label", "lastViewedAt", "viewCount")).withLabel("Details"));
      List<QTableMetaData> tables = new ArrayList<>(List.of(settings, people, favorites, recents, attachment(config)));
      tables.addAll(conversationTables(config));
      tables.addAll(tagTables(config));
      tables.addAll(workTables(config));
      tables.addAll(customFieldTables(config));
      for(QTableMetaData table : tables)
      {
         table.withSection(new QFieldSection("dates", new QIcon("calendar_today"), Tier.T3, List.of("createDate", "modifyDate")).withLabel("Dates"));
         com.kingsrook.qbits.collaboration.CoreCollaboration.reference(table, config);
         QInstanceEnricher.setInferredFieldBackendNames(table);
      }
      return (tables);
   }



   /*******************************************************************************
    ** Common identity, dates, backend and permissions.
    *******************************************************************************/
   private static QTableMetaData base(CollaborationQBitConfig config, String name, String label, String icon)
   {
      return (new QTableMetaData().withName(name).withLabel(label).withIcon(new QIcon(icon))
         .withBackendName(config.getBackendName()).withPrimaryKeyField("id").withPermissionRules(CollaborationPermissions.permissionRules())
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withLabel("ID").withIsEditable(false))
         .withField(new QFieldMetaData("createDate", QFieldType.DATE_TIME).withLabel("Created").withIsEditable(false))
         .withField(new QFieldMetaData("modifyDate", QFieldType.DATE_TIME).withLabel("Modified").withIsEditable(false)));
   }



   /*******************************************************************************
    ** Personal high-volume data: owner locks replace a table-level permission.
    ** Kept out of app navigation; the standard tables remain addressable by admins.
    *******************************************************************************/
   private static QTableMetaData personal(CollaborationQBitConfig config, String name, String label, String icon)
   {
      return (base(config, name, label, icon)
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED)
            .withCustomPermissionChecker(new QCodeReference(CollaborationSecurity.class)))
         .withRecordSecurityLock(CollaborationSecurity.ownerLock("userId"))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.NONE))
         .withRecordLabelFormatAndFields("%s", "label")
         .withField(user("userId", "User ID").withIsRequired(true))
         .withField(string("targetType", "Target Type", 20).withIsRequired(true).withPossibleValueSourceName(TARGET_TYPE))
         .withField(string("targetName", "Target Name", 255).withIsRequired(true))
         .withField(string("recordId", "Record ID", 255))
         .withField(string("label", "Label", 1024))
         .withUniqueKey(new UniqueKey("userId", "targetType", "targetName", "recordId"))
         .withSection(new QFieldSection("identity", new QIcon("badge"), Tier.T1,
            List.of("id", "userId", "targetType", "targetName", "recordId")).withLabel("Identity")));
   }



   /*******************************************************************************
    ** A user-id string shown by display name wherever the table is browsed.
    *******************************************************************************/
   private static QFieldMetaData user(String name, String label)
   {
      return (string(name, label, 255).withPossibleValueSourceName(USER));
   }


   /*******************************************************************************
    ** A bounded string field.
    *******************************************************************************/
   private static QFieldMetaData string(String name, String label, Integer length)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label).withMaxLength(length));
   }

   /*******************************************************************************
    ** Main attachment table; virtual URLs never have backend columns.
    *******************************************************************************/
   private static QTableMetaData attachment(CollaborationQBitConfig config)
   {
      return (base(config, ATTACHMENT, "Attachments", "attach_file")
         .withPermissionRules(CollaborationPermissions.permissionRules()
            .withCustomPermissionChecker(new QCodeReference(AttachmentSecurity.class)))
         .withRecordLabelFormatAndFields("%s", "fileName")
         .withSearchFields(List.of("fileName", "description"))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
         .withField(string("tableName", "Table", 128).withIsRequired(true))
         .withField(string("recordId", "Record ID", 64).withIsRequired(true))
         .withField(string("fileName", "File Name", 255).withIsRequired(true))
         .withField(string("contentType", "Content Type", 255).withIsRequired(true))
         .withField(new QFieldMetaData("sizeBytes", QFieldType.LONG).withLabel("Size (bytes)").withIsRequired(true))
         .withField(string("sha256", "SHA-256", 64).withIsRequired(true))
         .withField(string("bucketName", "Bucket", 255).withIsRequired(true))
         .withField(string("storageKey", "Storage Key", 1024).withIsRequired(true))
         .withField(string("thumbnailKey", "Thumbnail Key", 1024))
         .withField(new QFieldMetaData("description", QFieldType.TEXT).withLabel("Description"))
         .withField(user("createdByUserId", "Created By").withIsRequired(true))
         .withField(new QFieldMetaData("parentDeleted", QFieldType.BOOLEAN).withLabel("Parent Deleted").withIsRequired(true).withDefaultValue(false))
         .withVirtualField(new QVirtualFieldMetaData("previewUrl", QFieldType.STRING).withLabel("Preview").withIsEditable(false).withIsQuerySelectable(true)
            .withFieldAdornment(AdornmentType.FilePreviewValues.newFieldAdornment()
               .withValue(AdornmentType.FilePreviewValues.FILE_NAME_FIELD, "fileName")
               .withValue(AdornmentType.FilePreviewValues.MIME_TYPE_FIELD, "contentType")
               .withValue(AdornmentType.FilePreviewValues.SIZE_BYTES_FIELD, "sizeBytes")
               .withValue(AdornmentType.FilePreviewValues.THUMBNAIL_MAX_SIZE, 48)))
         .withVirtualField(new QVirtualFieldMetaData("downloadUrl", QFieldType.STRING).withLabel("Download").withIsEditable(false).withIsQuerySelectable(true)
            .withFieldAdornment(new FieldAdornment(AdornmentType.FILE_DOWNLOAD)
               .withValue(AdornmentType.FileDownloadValues.FILE_NAME_FIELD, "fileName")
               .withValue(AdornmentType.FileDownloadValues.DOWNLOAD_URL_DYNAMIC, true)))
         .withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(AttachmentPreviewCustomizer.class))
         .withSection(new QFieldSection("file", new QIcon("attach_file"), Tier.T1,
            List.of("fileName", "contentType", "sizeBytes", "description")).withLabel("File")
            .withAlternative(QFieldSectionAlternativeType.RECORD_VIEW, section -> section.setFieldNames(List.of("previewUrl", "downloadUrl", "fileName", "contentType", "sizeBytes", "description"))))
         .withSection(new QFieldSection("parent", new QIcon("link"), Tier.T2,
            List.of("tableName", "recordId", "createdByUserId", "parentDeleted")).withLabel("Parent"))
         .withSection(new QFieldSection("storage", new QIcon("storage"), Tier.T3,
            List.of("id", "bucketName", "storageKey", "thumbnailKey", "sha256")).withLabel("Storage")));
   }


   /*******************************************************************************
    ** Conversation tables share immutable parent references and service-only writes.
    *******************************************************************************/
   private static List<QTableMetaData> conversationTables(CollaborationQBitConfig config)
   {
      QTableMetaData comments = referenced(config, COMMENT, "Comments", "forum")
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
         .withSearchFields(List.of("body"))
         .withField(new QFieldMetaData("threadId", QFieldType.INTEGER).withPossibleValueSourceName(COMMENT))
         // Roots have null threadId; include the root by id as well as its replies by threadId.
         // Keep OR nested: core appends its search criteria to the outer AND filter.
         // TODO: add .withDependsOn("threadId") when QQQ core ships contingent dependsOn (docs/CONTINGENT-POSSIBLE-VALUES.md)
         .withField(new QFieldMetaData("parentCommentId", QFieldType.INTEGER).withPossibleValueSourceName(COMMENT)
            .withPossibleValueSourceFilter(new QQueryFilter().withSubFilter(new QQueryFilter().withBooleanOperator(BooleanOperator.OR)
               .withCriteria("threadId", QCriteriaOperator.EQUALS, "${input.threadId}")
               .withCriteria("id", QCriteriaOperator.EQUALS, "${input.threadId}"))))
         .withField(new QFieldMetaData("body", QFieldType.TEXT).withMaxLength(20000))
         // TODO: add .withDependsOn("tableName") when QQQ core ships contingent dependsOn (docs/CONTINGENT-POSSIBLE-VALUES.md)
         .withField(string("fieldName", "Field", 128).withPossibleValueSourceName(RECORD_FIELD_NAME))
         .withField(user("authorUserId", "Author").withIsRequired(true))
         .withField(new QFieldMetaData("lastActivityAt", QFieldType.DATE_TIME).withIsRequired(true))
         .withField(new QFieldMetaData("editedAt", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("resolvedAt", QFieldType.DATE_TIME))
         .withField(user("resolvedByUserId", "Resolved By"))
         .withField(new QFieldMetaData("deletedAt", QFieldType.DATE_TIME))
         .withField(user("deletedByUserId", "Deleted By"))
         .withRecordLabelFormatAndFields("Comment %s", "id");
      QTableMetaData revisions = referenced(config, COMMENT_REVISION, "Comment History", "history")
         .withField(new QFieldMetaData("commentId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(COMMENT))
         .withField(new QFieldMetaData("body", QFieldType.TEXT))
         .withField(new QFieldMetaData("editedAt", QFieldType.DATE_TIME).withIsRequired(true))
         .withField(user("editedByUserId", "Edited By"));
      QTableMetaData mentions = referenced(config, MENTION, "Mentions", "alternate_email")
         .withField(new QFieldMetaData("commentId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(COMMENT))
         .withField(user("mentionedUserId", "Mentioned User").withIsRequired(true))
         .withField(new QFieldMetaData("visible", QFieldType.BOOLEAN).withIsRequired(true))
         .withField(new QFieldMetaData("readAt", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("notifiedAt", QFieldType.DATE_TIME))
         .withUniqueKey(new UniqueKey("commentId", "mentionedUserId"));
      QTableMetaData follows = referenced(config, FOLLOW, "Followers", "notifications")
         .withField(user("userId", "User").withIsRequired(true))
         .withField(string("reason", "Reason", 32).withIsRequired(true))
         .withField(new QFieldMetaData("notifyOnChange", QFieldType.BOOLEAN).withIsRequired(true))
         .withField(new QFieldMetaData("notifyOnComment", QFieldType.BOOLEAN).withIsRequired(true))
         .withField(new QFieldMetaData("mutedUntil", QFieldType.DATE_TIME))
         .withUniqueKey(new UniqueKey("tableName", "recordId", "userId"));
      QTableMetaData activity = referenced(config, ACTIVITY, "Activity", "history")
         .withoutCapabilities(com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_INSERT,
            com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_UPDATE, com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.TABLE_DELETE)
         .withField(new QFieldMetaData("at", QFieldType.DATE_TIME).withIsRequired(true))
         .withField(user("actorUserId", "Actor"))
         .withField(string("type", "Type", 64).withIsRequired(true))
         .withField(new QFieldMetaData("summary", QFieldType.TEXT))
         .withField(string("refTableName", "Reference Table", 128))
         .withField(string("refId", "Reference ID", 64));
      QTableMetaData digests = referenced(config, FOLLOW_DIGEST, "Follow Digests", "notifications")
         .withField(user("userId", "Recipient").withIsRequired(true))
         .withField(new QFieldMetaData("dueAt", QFieldType.DATE_TIME).withIsRequired(true))
         .withField(new QFieldMetaData("changes", QFieldType.TEXT))
         .withUniqueKey(new UniqueKey("tableName", "recordId", "userId"));
      List<QTableMetaData> tables = List.of(comments, revisions, mentions, follows, activity, digests);
      for(QTableMetaData table : tables)
      {
         table.withSection(new QFieldSection("details", new QIcon("info"), Tier.T1,
            table.getFields().keySet().stream().filter(name -> !List.of("createDate", "modifyDate", "tableName", "recordId", "parentDeleted").contains(name)).toList()).withLabel("Details"));
         for(QFieldMetaData field : table.getFields().values())
         {
            field.setIsEditable(false);
         }
      }
      return (tables);
   }

   /*******************************************************************************
    ** Denormalized references on children permit the same pre-query security.
    *******************************************************************************/
   private static QTableMetaData referenced(CollaborationQBitConfig config, String name, String label, String icon)
   {
      return (base(config, name, label, icon)
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.NONE))
         .withPermissionRules(CollaborationPermissions.permissionRules()
            .withDenyBehavior(com.kingsrook.qqq.backend.core.model.metadata.permissions.DenyBehavior.HIDDEN)
            .withCustomPermissionChecker(new QCodeReference(com.kingsrook.qbits.collaboration.security.CollaborationRowSecurity.class)))
         .withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(com.kingsrook.qbits.collaboration.security.ParentRowCustomizer.class))
         .withField(string("tableName", "Table", 128).withIsRequired(true))
         .withField(string("recordId", "Record ID", 64).withIsRequired(true))
         .withField(new QFieldMetaData("parentDeleted", QFieldType.BOOLEAN).withIsRequired(true).withDefaultValue(false))
         .withRecordLabelFormatAndFields("%s", "id")
         .withSection(new QFieldSection("parent", new QIcon("link"), Tier.T2, List.of("tableName", "recordId", "parentDeleted")).withLabel("Parent")));
   }

   /*******************************************************************************
    ** Vocabulary is visible; assignments inherit parent restrictions and locks.
    *******************************************************************************/
   private static List<QTableMetaData> tagTables(CollaborationQBitConfig config)
   {
      QTableMetaData tags = base(config, TAG, "Tags", "label")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED)
            .withCustomPermissionChecker(new QCodeReference(com.kingsrook.qbits.collaboration.tags.TagSecurity.class)))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
         .withSearchFields(List.of("label"))
         .withRecordLabelFormatAndFields("%s", "label")
         .withField(string("name", "Name", 60).withIsRequired(true))
         .withField(string("label", "Label", 255).withIsRequired(true))
         .withField(string("color", "Color", 64))
         .withField(string("tableName", "Table", 128))
         .withField(new QFieldMetaData("approved", QFieldType.BOOLEAN).withIsRequired(true))
         .withField(new QFieldMetaData("description", QFieldType.TEXT))
         .withField(user("createdByUserId", "Created By").withIsRequired(true))
         .withField(new QFieldMetaData("usageCount", QFieldType.INTEGER).withIsRequired(true).withDefaultValue(0))
         .withField(new QFieldMetaData("mergedIntoTagId", QFieldType.INTEGER).withPossibleValueSourceName(TAG))
         .withUniqueKey(new UniqueKey("tableName", "name"))
         .withSection(new QFieldSection("details", new QIcon("label"), Tier.T1,
            List.of("id", "name", "label", "color", "tableName", "approved", "description", "createdByUserId", "usageCount", "mergedIntoTagId")).withLabel("Details"));
      QTableMetaData links = referenced(config, RECORD_TAG, "Record Tags", "label")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED)
            .withCustomPermissionChecker(new QCodeReference(com.kingsrook.qbits.collaboration.security.CollaborationRowSecurity.class)))
         .withField(new QFieldMetaData("tagId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(TAG))
         .withField(user("createdByUserId", "Created By").withIsRequired(true))
         .withUniqueKey(new UniqueKey("tableName", "recordId", "tagId"))
         .withSection(new QFieldSection("details", new QIcon("label"), Tier.T1, List.of("id", "tagId", "createdByUserId")).withLabel("Details"));
      for(QTableMetaData table : List.of(tags, links))
      {
         table.getFields().values().forEach(field -> field.setIsEditable(false));
      }
      return (List.of(tags, links));
   }

   /*******************************************************************************
    ** Sharing and work tables remain visible, audited and service-managed.
    *******************************************************************************/
   private static List<QTableMetaData> workTables(CollaborationQBitConfig config)
   {
      QTableMetaData shares = referenced(config, SHARE, "Shares", "share")
         .withField(user("fromUserId", "From").withIsRequired(true))
         .withField(user("toUserId", "To").withIsRequired(true))
         .withField(new QFieldMetaData("message", QFieldType.TEXT).withMaxLength(2000))
         .withField(new QFieldMetaData("recipientCouldRead", QFieldType.BOOLEAN))
         .withField(new QFieldMetaData("sentAt", QFieldType.DATE_TIME).withIsRequired(true))
         .withField(new QFieldMetaData("openedAt", QFieldType.DATE_TIME));
      QTableMetaData requests = base(config, ACCESS_REQUEST, "Access Requests", "lock_open")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED)
            .withCustomPermissionChecker(new QCodeReference(com.kingsrook.qbits.collaboration.sharing.AccessRequestSecurity.class)))
         .withRecordSecurityLock(CollaborationSecurity.ownerLock("requesterUserId"))
         .withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(com.kingsrook.qbits.collaboration.sharing.AccessRequestSecurity.class))
         .withRecordLabelFormatAndFields("Access request %s", "id")
         .withField(string("tableName", "Table", 128).withIsRequired(true))
         .withField(string("recordId", "Record ID", 64).withIsRequired(true))
         .withField(user("requesterUserId", "Requester").withIsRequired(true))
         .withField(new QFieldMetaData("shareId", QFieldType.INTEGER))
         .withField(new QFieldMetaData("message", QFieldType.TEXT).withMaxLength(2000))
         .withField(string("status", "Status", 20).withIsRequired(true).withPossibleValueSourceName("collaboration_access_request_status"))
         .withField(user("handledByUserId", "Handled By"))
         .withField(new QFieldMetaData("handledAt", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("note", QFieldType.TEXT).withMaxLength(2000));
      QTableMetaData tasks = referenced(config, TASK, "Tasks", "task_alt")
         .withRecordLabelFormatAndFields("%s", "title").withSearchFields(List.of("title"))
         .withField(string("title", "Title", 200).withIsRequired(true))
         .withField(new QFieldMetaData("notes", QFieldType.TEXT))
         .withField(user("assigneeUserId", "Assignee"))
         .withField(new QFieldMetaData("dueAt", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("remindAt", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("remindedAt", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("overdueNotifiedAt", QFieldType.DATE_TIME))
         .withField(string("status", "Status", 20).withIsRequired(true).withPossibleValueSourceName("collaboration_task_status"))
         .withField(new QFieldMetaData("completedAt", QFieldType.DATE_TIME))
         .withField(user("completedByUserId", "Completed By"))
         .withField(user("createdByUserId", "Created By").withIsRequired(true));
      QTableMetaData assignments = referenced(config, RECORD_ASSIGNMENT, "Record Owners", "assignment_ind")
         .withField(user("ownerUserId", "Owner"))
         .withField(user("assigneeUserId", "Assignee"))
         .withUniqueKey(new UniqueKey("tableName", "recordId"));
      List<QTableMetaData> tables = List.of(shares, requests, tasks, assignments);
      for(QTableMetaData table : tables)
      {
         table.withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
            .withSection(new QFieldSection("details", new QIcon("info"), Tier.T1,
               table.getFields().keySet().stream().filter(name -> !List.of("createDate", "modifyDate", "parentDeleted").contains(name)
                  && (table.getName().equals(ACCESS_REQUEST) || !List.of("tableName", "recordId").contains(name))).toList()).withLabel("Details"));
         table.getFields().values().forEach(field -> field.setIsEditable(false));
      }
      return (tables);
   }

   /*******************************************************************************
    ** The designer is an ordinary audited table; fallback values are children.
    *******************************************************************************/
   private static List<QTableMetaData> customFieldTables(CollaborationQBitConfig config)
   {
      QTableMetaData definitions = base(config, FIELD_DEFINITION, "Custom Fields", "input")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED)
            .withCustomPermissionChecker(new QCodeReference(com.kingsrook.qbits.collaboration.customfields.FieldSecurity.class)))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
         .withRecordLabelFormatAndFields("%s: %s", "tableName", "label")
         .withField(string("tableName", "Table", 128).withIsRequired(true).withPossibleValueSourceName("collaboration_custom_field_tables"))
         .withField(string("name", "Name", 41).withIsRequired(true))
         .withField(string("label", "Label", 255).withIsRequired(true))
         .withField(string("type", "Type", 32).withIsRequired(true).withPossibleValueSourceName("collaboration_field_type"))
         .withField(new QFieldMetaData("options", QFieldType.TEXT).withLabel("Options (JSON)"))
         .withField(string("linkTableName", "Link table", 128))
         .withField(new QFieldMetaData("required", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(new QFieldMetaData("defaultValue", QFieldType.TEXT))
         .withField(new QFieldMetaData("minValue", QFieldType.DECIMAL))
         .withField(new QFieldMetaData("maxValue", QFieldType.DECIMAL))
         .withField(new QFieldMetaData("maxLength", QFieldType.INTEGER))
         .withField(new QFieldMetaData("helpText", QFieldType.TEXT))
         .withField(string("sectionName", "Section", 128))
         .withField(new QFieldMetaData("sortOrder", QFieldType.INTEGER).withDefaultValue(0))
         .withField(new QFieldMetaData("filterable", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(new QFieldMetaData("searchable", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(string("classification", "Classification", 32).withPossibleValueSourceName("collaboration_field_classification"))
         .withField(string("status", "Status", 16).withDefaultValue("DRAFT").withIsRequired(true).withIsEditable(false).withPossibleValueSourceName("collaboration_field_status"))
         .withField(string("indexState", "Index state", 16).withDefaultValue("NONE").withIsEditable(false).withPossibleValueSourceName("collaboration_field_index_state"))
         .withField(string("storage", "Storage", 16).withIsEditable(false))
         .withField(new QFieldMetaData("statusMessage", QFieldType.TEXT).withIsEditable(false))
         .withField(new QFieldMetaData("indexDdl", QFieldType.TEXT).withLabel("Index DDL").withIsEditable(false))
         .withField(user("createdByUserId", "Created by").withIsEditable(false))
         .withUniqueKey(new UniqueKey("tableName", "name"));
      QCodeReference customizer = new QCodeReference(com.kingsrook.qbits.collaboration.customfields.FieldDefinitionCustomizer.class);
      definitions.withCustomizer(TableCustomizers.PRE_INSERT_RECORD, customizer).withCustomizer(TableCustomizers.PRE_UPDATE_RECORD, customizer)
         .withCustomizer(TableCustomizers.PRE_DELETE_RECORD, customizer)
         .withCustomizer(TableCustomizers.POST_INSERT_RECORD, customizer).withCustomizer(TableCustomizers.POST_UPDATE_RECORD, customizer)
         .withCustomizer(TableCustomizers.POST_DELETE_RECORD, customizer)
         .withSection(new QFieldSection("definition", new QIcon("input"), Tier.T1,
            List.of("id", "tableName", "name", "label", "type", "status", "classification")).withLabel("Definition"))
         .withSection(new QFieldSection("settings", new QIcon("settings"), Tier.T2,
            List.of("options", "linkTableName", "required", "defaultValue", "minValue", "maxValue", "maxLength", "helpText", "sectionName", "sortOrder", "filterable", "searchable")).withLabel("Settings"))
         .withSection(new QFieldSection("storage", new QIcon("storage"), Tier.T2,
            List.of("storage", "indexState", "statusMessage", "indexDdl", "createdByUserId")).withLabel("Storage and indexes"));
      QTableMetaData values = referenced(config, FIELD_VALUE, "Custom Field Values", "input")
         .withField(string("fieldName", "Field name", 41).withIsRequired(true))
         .withField(new QFieldMetaData("valueText", QFieldType.TEXT))
         .withField(new QFieldMetaData("valueNumber", QFieldType.DECIMAL))
         .withField(new QFieldMetaData("valueDate", QFieldType.DATE))
         .withField(new QFieldMetaData("valueDateTime", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("valueBoolean", QFieldType.BOOLEAN))
         .withUniqueKey(new UniqueKey("tableName", "recordId", "fieldName"))
         .withSection(new QFieldSection("value", new QIcon("input"), Tier.T1,
            List.of("id", "fieldName", "valueText", "valueNumber", "valueDate", "valueDateTime", "valueBoolean")).withLabel("Value"));
      values.getFields().values().forEach(field -> field.setIsEditable(false));
      return (List.of(definitions, values));
   }
}
