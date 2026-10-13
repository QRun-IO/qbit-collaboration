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
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.audits.QAuditRules;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
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
      List<QTableMetaData> tables = List.of(settings, people);
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
    ** A bounded string field.
    *******************************************************************************/
   private static QFieldMetaData string(String name, String label, Integer length)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label).withMaxLength(length));
   }
}
