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
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.audits.QAuditRules;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** Visible Collaboration tables. Enum ids are phase-a stand-ins for the core
 ** collaboration enums; replace them when those contracts are released.
 *******************************************************************************/
public final class CollaborationTables
{
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
         enumSource(TARGET_TYPE, "TABLE", "APP", "RECORD", "PROCESS", "REPORT", "SAVED_VIEW"),
         QPossibleValueSource.newForTable(FAVORITE), QPossibleValueSource.newForTable(RECENT),
         enumSource(SCOPE_TYPE, "INSTANCE", "APP", "TABLE"),
         enumSource(FEATURE, "ATTACHMENTS", "TAGS", "CUSTOM_FIELDS", "COMMENTS", "PRESENCE", "SHARING",
            "FAVORITES", "FOLLOW", "TIMELINE", "RECENTS", "TASKS", "ASSIGNMENT"),
         enumSource(PERMISSION_MODE, "EDIT_OR_GRANTED", "GRANTED_ONLY"),
         QPossibleValueSource.newForTable(FEATURE_SETTING), QPossibleValueSource.newForTable(USER)));
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
         .withField(string("scopeName", "Scope Name", 128).withIsRequired(true))
         .withField(string("feature", "Feature", 32).withIsRequired(true).withPossibleValueSourceName(FEATURE))
         .withField(new QFieldMetaData("enabled", QFieldType.BOOLEAN).withLabel("Enabled").withIsRequired(true).withDefaultValue(false))
         .withField(new QFieldMetaData("settings", QFieldType.TEXT).withLabel("Settings (JSON)"))
         .withField(string("permissionMode", "Permission Mode", 32).withPossibleValueSourceName(PERMISSION_MODE).withDefaultValue("EDIT_OR_GRANTED"))
         .withUniqueKey(new UniqueKey("scopeType", "scopeName", "feature"))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD))
         .withSection(new QFieldSection("identity", new QIcon("badge"), Tier.T1, List.of("id", "scopeType", "scopeName", "feature")).withLabel("Identity"))
         .withSection(new QFieldSection("details", new QIcon("settings"), Tier.T2, List.of("enabled", "settings", "permissionMode")).withLabel("Details"));
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
      List<QTableMetaData> tables = List.of(settings, people, favorites, recents);
      for(QTableMetaData table : tables)
      {
         table.withSection(new QFieldSection("dates", new QIcon("calendar_today"), Tier.T3, List.of("createDate", "modifyDate")).withLabel("Dates"));
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
         .withField(string("userId", "User ID", 255).withIsRequired(true))
         .withField(string("targetType", "Target Type", 20).withIsRequired(true).withPossibleValueSourceName(TARGET_TYPE))
         .withField(string("targetName", "Target Name", 255).withIsRequired(true))
         .withField(string("recordId", "Record ID", 255))
         .withField(string("label", "Label", 1024))
         .withUniqueKey(new UniqueKey("userId", "targetType", "targetName", "recordId"))
         .withSection(new QFieldSection("identity", new QIcon("badge"), Tier.T1,
            List.of("id", "userId", "targetType", "targetName", "recordId")).withLabel("Identity")));
   }



   /*******************************************************************************
    ** A bounded string field.
    *******************************************************************************/
   private static QFieldMetaData string(String name, String label, Integer length)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label).withMaxLength(length));
   }
}
