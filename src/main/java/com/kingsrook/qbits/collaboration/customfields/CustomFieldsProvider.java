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


package com.kingsrook.qbits.collaboration.customfields;

import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.FeatureSettingsProvider;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.ai.AiFieldMetaData;
import com.kingsrook.qqq.backend.core.actions.ai.DataClassification;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataProviderInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RuntimeMetaDataContribution;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RuntimeTableMetaDataContribution;
import com.kingsrook.qqq.backend.core.model.metadata.fields.JsonPathStorage;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.ValueRangeBehavior;
import com.kingsrook.qqq.backend.core.model.metadata.help.QHelpContent;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;

/*******************************************************************************
 ** ACTIVE definitions become normal fields through the core copy-on-write overlay.
 *******************************************************************************/
public class CustomFieldsProvider implements RuntimeMetaDataProviderInterface
{
   /*******************************************************************************
    ** Build against incoming feature settings, not the previous resolver snapshot.
    *******************************************************************************/
   @Override
   public RuntimeMetaDataContribution getContribution(QInstance instance) throws QException
   {
      if(com.kingsrook.qbits.collaboration.presence.RedisPresenceConfig.forInstance(instance) == null)
      {
         com.kingsrook.qbits.collaboration.presence.PresenceRegistry.get(instance);
      }
      var result = new RuntimeMetaDataContribution();
      var overrides = new FeatureSettingsProvider().getOverrides(instance);
      List<QRecord> definitions = CollaborationRecords.query(CollaborationTables.FIELD_DEFINITION,
         new QQueryFilter().withOrderBy(new QFilterOrderBy("sortOrder")).withOrderBy(new QFilterOrderBy("id")));
      long generation = 1;
      for(QRecord definition : definitions)
      {
         generation = 31 * generation + definition.getValues().hashCode();
         var table = instance.getTable(definition.getValueString("tableName"));
         if(!"ACTIVE".equals(definition.getValueString("status")) || table == null
            || !FeatureSettingsProvider.enabled(instance, table, RecordFeature.CUSTOM_FIELDS, overrides))
         {
            continue;
         }
         var storage = CustomFieldStorage.availability(table);
         String storageName = storage.json() ? "JSONB" : "EAV";
         if(!storageName.equals(definition.getValueString("storage")) || (!storage.json() && !java.util.Objects.equals(storage.reason(), definition.getValueString("statusMessage"))))
         {
            CollaborationRecords.update(CollaborationTables.FIELD_DEFINITION, new QRecord().withValue("id", definition.getValue("id"))
               .withValue("storage", storageName).withValue("statusMessage", storage.reason()));
         }
         QFieldMetaData field = field(instance, definition, storage.json());
         var contribution = result.getTables().computeIfAbsent(table.getName(), key -> new RuntimeTableMetaDataContribution());
         if(storage.json())
         {
            contribution.getFields().add(field);
            contribution.getSectionPlacements().put(field.getName(), definition.getValueString("sectionName") == null || definition.getValueString("sectionName").isBlank() ? "customFields" : definition.getValueString("sectionName"));
         }
         else
         {
            contribution.getVirtualFields().add((QVirtualFieldMetaData) field);
         }
         if(Boolean.TRUE.equals(definition.getValueBoolean("searchable")))
         {
            contribution.getSearchFields().add(field.getName());
         }
      }
      return (result.withGeneration(generation & Long.MAX_VALUE));
   }

   /*******************************************************************************
    ** Core supports scalar STRING storage for multi-select, not array operators.
    *******************************************************************************/
   public static QFieldType type(String type)
   {
      return (switch(type)
      {
         case "SELECT", "MULTI_SELECT", "USER", "RECORD_LINK" -> QFieldType.STRING;
         default -> QFieldType.valueOf(type);
      });
   }

   /*******************************************************************************
    ** Preserve classifications and supported validation on the contributed field.
    *******************************************************************************/
   private QFieldMetaData field(QInstance instance, QRecord definition, boolean json) throws QException
   {
      String name = "cf_" + definition.getValueString("name");
      QFieldType type = type(definition.getValueString("type"));
      QFieldMetaData field = json ? new QFieldMetaData(name, type).withBackendStorage(new JsonPathStorage().withColumnName("qqq_custom_fields").withKey(definition.getValueString("name")))
         : new QVirtualFieldMetaData(name, type).withIsEditable(false).withIsQueryCriteria(true).withIsQuerySelectable(true)
            .withValueProvider(new QCodeReference(CustomFieldValueProvider.class))
            .withCriteriaExpander(new QCodeReference(FieldCriteriaExpander.class));
      field.withLabel(definition.getValueString("label")).withIsRequired(json && Boolean.TRUE.equals(definition.getValueBoolean("required")))
         .withMaxLength(definition.getValueInteger("maxLength"));
      if(definition.getValue("defaultValue") != null && json)
      {
         field.withDefaultValue(com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsFieldType(type, definition.getValue("defaultValue")));
      }
      if(definition.getValueString("helpText") != null)
      {
         field.withHelpContent(new QHelpContent().withContentAsText(definition.getValueString("helpText")));
      }
      if((type == QFieldType.INTEGER || type == QFieldType.DECIMAL) && (definition.getValue("minValue") != null || definition.getValue("maxValue") != null))
      {
         field.withBehavior(new ValueRangeBehavior().withMinValue(definition.getValueBigDecimal("minValue")).withMaxValue(definition.getValueBigDecimal("maxValue")));
      }
      if(definition.getValueString("classification") != null)
      {
         field.withSupplementalMetaData(new AiFieldMetaData().withClassification(DataClassification.valueOf(definition.getValueString("classification"))));
      }
      switch(definition.getValueString("type"))
      {
         case "SELECT", "MULTI_SELECT" ->
         {
            String source = "collaboration_field_options_" + definition.getValueInteger("id");
            instance.getPossibleValueSources().put(source, new QPossibleValueSource().withName(source).withType(QPossibleValueSourceType.ENUM)
               .withEnumValues(new ArrayList<>(FieldDefinitions.options(definition).values())));
            field.withPossibleValueSourceName(source);
            if("MULTI_SELECT".equals(definition.getValueString("type")))
            {
               field.withHelpContent(new QHelpContent().withContentAsText("Stored as a JSON-array STRING. Array membership operators and multi-select editing are not supported by core yet."));
            }
         }
         case "USER" -> field.withPossibleValueSourceName(CollaborationTables.USER);
         case "RECORD_LINK" -> field.withPossibleValueSourceName(definition.getValueString("linkTableName"));
         default ->
         {
         }
      }
      return (field);
   }
}
