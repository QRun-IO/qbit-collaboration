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

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.FeatureSettingsCustomizer;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.JsonPathStorage;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;

/*******************************************************************************
 ** Designer lifecycle and validations shared by direct writes and record actions.
 *******************************************************************************/
public final class FieldDefinitions
{
   /*******************************************************************************
    ** Static domain operations.
    *******************************************************************************/
   private FieldDefinitions()
   {
   }

   /*******************************************************************************
    ** Managers may address definitions independently of host record read access.
    *******************************************************************************/
   public static QRecord get(Serializable id) throws QException
   {
      FieldSecurity.requireManage();
      var rows = CollaborationRecords.query(CollaborationTables.FIELD_DEFINITION, new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id));
      if(rows.isEmpty())
      {
         throw (new QNotFoundException("Not found"));
      }
      return (rows.getFirst());
   }

   /*******************************************************************************
    ** Stable option ids survive label changes. JSON is [{"id":"a","label":"A"}].
    *******************************************************************************/
   public static Map<String, QPossibleValue<?>> options(QRecord definition) throws QException
   {
      Map<String, QPossibleValue<?>> result = new LinkedHashMap<>();
      String json = definition.getValueString("options");
      if(json == null || json.isBlank())
      {
         return (result);
      }
      try
      {
         var array = new ObjectMapper().readTree(json);
         if(!array.isArray())
         {
            throw (new IllegalArgumentException("Expected an array"));
         }
         for(var option : array)
         {
            if(!option.path("id").isTextual() || option.path("id").asText().isBlank() || !option.path("label").isTextual()
               || result.putIfAbsent(option.path("id").asText(), new QPossibleValue<>(option.path("id").asText(), option.path("label").asText())) != null)
            {
               throw (new IllegalArgumentException("Options need unique nonblank string ids and labels"));
            }
         }
         return (result);
      }
      catch(Exception e)
      {
         throw (new QException("Invalid field options: expected an array of {id, label} objects", e));
      }
   }

   /*******************************************************************************
    ** Reject unsafe names, invalid types, missing links and immutable identity edits.
    *******************************************************************************/
   public static void validate(QRecord definition, QRecord old) throws QException
   {
      String name = definition.getValueString("name");
      var table = QContext.getQInstance().getTable(definition.getValueString("tableName"));
      if(name == null || !name.matches("[a-z][a-z0-9_]{0,40}") || table == null || table.getName().startsWith("collaboration_") || table.getPrimaryKeyField() == null)
      {
         throw (new QException("Choose a host table and a name matching [a-z][a-z0-9_]{0,40}."));
      }
      if(definition.getValueString("type") == null || !List.of("STRING", "TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATE_TIME", "SELECT", "MULTI_SELECT", "USER", "RECORD_LINK").contains(definition.getValueString("type")))
      {
         throw (new QException("Unsupported custom field type"));
      }
      if(definition.getValueString("label") == null || definition.getValueString("label").isBlank())
      {
         throw (new QException("A field label is required"));
      }
      var existing = table.getFields().get("cf_" + name);
      if(existing != null && (old == null || "DRAFT".equals(old.getValueString("status")) || !(existing.getBackendStorage() instanceof JsonPathStorage storage && name.equals(storage.getKey()))))
      {
         throw (new QException("The field name clashes with a code field"));
      }
      var virtual = table.getVirtualFields() == null ? null : table.getVirtualFields().get("cf_" + name);
      if(virtual != null && (old == null || "DRAFT".equals(old.getValueString("status"))))
      {
         throw (new QException("The field name clashes with a code virtual field"));
      }
      if(old != null && (!Objects.equals(old.getValue("tableName"), definition.getValue("tableName")) || !Objects.equals(old.getValue("name"), definition.getValue("name"))))
      {
         throw (new QException("Table and name cannot change after creation"));
      }
      if(old != null && !"DRAFT".equals(old.getValueString("status")) && !Objects.equals(old.getValue("type"), definition.getValue("type")))
      {
         throw (new QException("Type changes are only allowed in DRAFT"));
      }
      String classification = definition.getValueString("classification");
      if(classification != null && !List.of("PUBLIC", "CONFIDENTIAL", "PII", "PHI").contains(classification))
      {
         throw (new QException("Unknown classification"));
      }
      if(definition.getValueInteger("maxLength") != null && definition.getValueInteger("maxLength") < 1)
      {
         throw (new QException("Maximum length must be positive"));
      }
      if(definition.getValueBigDecimal("minValue") != null && definition.getValueBigDecimal("maxValue") != null
         && definition.getValueBigDecimal("minValue").compareTo(definition.getValueBigDecimal("maxValue")) > 0)
      {
         throw (new QException("Minimum exceeds maximum"));
      }
      if("RECORD_LINK".equals(definition.getValueString("type")) && QContext.getQInstance().getPossibleValueSource(definition.getValueString("linkTableName")) == null)
      {
         throw (new QException("The link table needs a possible value source"));
      }
      if(definition.getValue("defaultValue") != null)
      {
         try
         {
            com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsFieldType(CustomFieldsProvider.type(definition.getValueString("type")), definition.getValue("defaultValue"));
         }
         catch(RuntimeException e)
         {
            throw (new QException("Invalid default value for this field type", e));
         }
      }
      var choices = options(definition);
      if(old != null && "ACTIVE".equals(old.getValueString("status")))
      {
         for(String removed : options(old).keySet())
         {
            if(!choices.containsKey(removed))
            {
               if(CustomFieldStorage.postgres(table) && CustomFieldStorage.availability(table).json())
               {
                  if(optionUses(old, removed) > 0)
                  {
                     throw (new QException("Cannot remove an option that is still used: " + removed));
                  }
               }
               else
               {
                  definition.setValue("statusMessage", "Warning: option removal usage checks are only available on PostgreSQL JSON storage.");
               }
            }
         }
      }
   }

   /*******************************************************************************
    ** Containment values are parameters, including the scalar JSON-array string.
    *******************************************************************************/
   private static long optionUses(QRecord definition, String option) throws QException
   {
      var table = QContext.getQInstance().getTable(definition.getValueString("tableName"));
      String predicate = "qqq_custom_fields @> ?::jsonb";
      boolean multi = "MULTI_SELECT".equals(definition.getValueString("type"));
      if(multi)
      {
         predicate = "(qqq_custom_fields->>?)::jsonb @> ?::jsonb";
      }
      try(var connection = CustomFieldStorage.connection(table); var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + CustomFieldStorage.sqlTable(table) + " WHERE " + predicate))
      {
         if(multi)
         {
            statement.setString(1, definition.getValueString("name"));
            statement.setString(2, new ObjectMapper().writeValueAsString(List.of(option)));
         }
         else
         {
            statement.setString(1, new ObjectMapper().writeValueAsString(Map.of(definition.getValueString("name"), option)));
         }
         try(var result = statement.executeQuery())
         {
            result.next();
            return (result.getLong(1));
         }
      }
      catch(Exception e)
      {
         throw (new QException("Cannot verify option usage", e));
      }
   }

   /*******************************************************************************
    ** Count affected values for the Archive Review, without loading host records.
    *******************************************************************************/
   public static long valueCount(QRecord definition) throws QException
   {
      var table = QContext.getQInstance().getTable(definition.getValueString("tableName"));
      String target = CustomFieldStorage.availability(table).json() ? table.getName() : CollaborationTables.FIELD_VALUE;
      QQueryFilter filter = target.equals(table.getName()) ? new QQueryFilter().withCriteria("cf_" + definition.getValueString("name"), QCriteriaOperator.IS_NOT_BLANK)
         : new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName()).withCriteria("fieldName", QCriteriaOperator.EQUALS, definition.getValueString("name"))
            .withCriteria(FieldCriteriaExpander.valueColumn(definition.getValueString("type")), QCriteriaOperator.IS_NOT_BLANK);
      var input = new com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput(target).withFilter(filter);
      input.withPermissionBypass("Field manager counts affected values for Archive Review.");
      return (new com.kingsrook.qqq.backend.core.actions.tables.CountAction().execute(input).getCount());
   }

   /*******************************************************************************
    ** Revalidate immediately before applying the action; refresh only after commit.
    *******************************************************************************/
   public static void apply(Serializable id, String operation) throws QException
   {
      QRecord definition = get(id);
      review(definition, operation);
      if(!"Rebuild".equals(operation))
      {
         String status = "Publish".equals(operation) ? "ACTIVE" : "ARCHIVED";
         definition.setValue("status", status);
         CollaborationRecords.update(CollaborationTables.FIELD_DEFINITION, new QRecord().withValue("id", id).withValue("status", status).withValue("indexState", "NONE"));
         new FeatureSettingsCustomizer().postUpdate(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withTransaction(QContext.getQBackendTransaction()), List.of(), java.util.Optional.empty());
      }
      BuildFieldIndexes.afterCommit(definition);
   }

   /*******************************************************************************
    ** Review and Apply share precisely the same state transition rules.
    *******************************************************************************/
   public static void review(QRecord definition, String operation) throws QException
   {
      FieldSecurity.requireManage();
      validate(definition, definition);
      if(!List.of("Publish", "Archive", "Rebuild").contains(operation))
      {
         throw (new QException("Unknown field action"));
      }
      if("Publish".equals(operation) && new CustomFieldTablesProvider().getPossibleValue(definition.getValueString("tableName")) == null)
      {
         throw (new QException("CUSTOM_FIELDS must be enabled before publishing"));
      }
      String expected = "Publish".equals(operation) ? "DRAFT" : "ACTIVE";
      if(!expected.equals(definition.getValueString("status")))
      {
         throw (new QException(operation + " requires " + expected));
      }
   }
}
