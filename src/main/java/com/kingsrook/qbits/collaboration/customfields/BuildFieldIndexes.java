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

import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** PostgreSQL indexes use independent autocommit connections, never a row transaction.
 *******************************************************************************/
public class BuildFieldIndexes implements BackendStep
{
   public static final String NAME = "collaborationBuildFieldIndexes";

   /*******************************************************************************
    ** Always callable as a hidden job; scheduling is explicitly configured by the host.
    *******************************************************************************/
   public static QProcessMetaData metadata(CollaborationQBitConfig config)
   {
      var process = new QProcessMetaData().withName(NAME).withLabel("Build custom field indexes").withIsHidden(true)
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED).withCustomPermissionChecker(new QCodeReference(FieldSecurity.class)))
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(BuildFieldIndexes.class)));
      if(config.getSchedulerName() != null)
      {
         process.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withRepeatSeconds(60).withInitialDelaySeconds(60));
      }
      return (process);
   }

   /*******************************************************************************
    ** Retry pending and failed indexes, leaving completed indexes alone.
    *******************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      FieldSecurity.requireManage();
      for(QRecord definition : CollaborationRecords.query(CollaborationTables.FIELD_DEFINITION,
         new QQueryFilter().withCriteria("status", QCriteriaOperator.IN, List.of("ACTIVE", "ARCHIVED"))
            .withCriteria("indexState", QCriteriaOperator.IN, List.of("NONE", "FAILED", "BUILDING"))))
      {
         build(definition);
      }
   }

   /*******************************************************************************
    ** The published definition must commit before its index can be built.
    *******************************************************************************/
   public static void afterCommit(QRecord definition) throws QException
   {
      if(QContext.getQBackendTransaction() == null)
      {
         build(definition);
         return;
      }
      var context = new CapturedContext(QContext.getQInstance(), QContext.getQSession());
      var id = definition.getValue("id");
      QContext.getQBackendTransaction().addAfterCommitCallback(() ->
      {
         try
         {
            QContext.withTemporaryContext(context, () -> build(FieldDefinitions.get(id)));
         }
         catch(QException e)
         {
            throw (new IllegalStateException("Could not build custom field index", e));
         }
      });
   }

   /*******************************************************************************
    ** Record the statement even when the host reserves DDL for migrations.
    *******************************************************************************/
   public static void build(QRecord definition) throws QException
   {
      FieldSecurity.requireManage();
      QTableMetaData table = QContext.getQInstance().getTable(definition.getValueString("tableName"));
      if(!CustomFieldStorage.postgres(table))
      {
         return;
      }
      boolean managed = Boolean.TRUE.equals(CollaborationRecords.config().getManagedIndexes());
      String ddl = ddl(table, definition, false);
      if(ddl == null)
      {
         state(definition, "NONE", null, null);
         return;
      }
      if(!managed)
      {
         state(definition, "NONE", "Apply indexDdl through the host migration tooling. Text indexes require pg_trgm for substring search.", ddl);
         return;
      }
      try(var connection = CustomFieldStorage.connection(table))
      {
         connection.setAutoCommit(true);
         boolean trigram;
         try(var statement = connection.createStatement(); var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')"))
         {
            result.next();
            trigram = result.getBoolean(1);
         }
         ddl = ddl(table, definition, trigram);
         state(definition, "BUILDING", null, ddl);
         try(var statement = connection.createStatement())
         {
            statement.execute(ddl);
         }
         state(definition, "ARCHIVED".equals(definition.getValueString("status")) ? "NONE" : "READY", null, ddl);
      }
      catch(Exception e)
      {
         state(definition, "FAILED", e.getMessage(), ddl);
      }
   }

   /*******************************************************************************
    ** Generate deterministic safe identifiers; PostgreSQL names are at most 63 bytes.
    *******************************************************************************/
   public static String ddl(QTableMetaData table, QRecord definition, boolean trigram) throws QException
   {
      String key = definition.getValueString("name");
      if(key == null || !key.matches("[a-z][a-z0-9_]{0,40}"))
      {
         throw (new QException("Invalid field name"));
      }
      String index = "cf_" + table.getName() + "_" + key;
      if(index.length() > 63)
      {
         index = index.substring(0, 45) + "_" + Integer.toUnsignedString(index.hashCode(), 16);
      }
      if("ARCHIVED".equals(definition.getValueString("status")))
      {
         return ("DROP INDEX CONCURRENTLY IF EXISTS " + CustomFieldStorage.identifier(index));
      }
      String type = switch(definition.getValueString("type"))
      {
         case "INTEGER" -> "integer";
         case "DECIMAL" -> "numeric";
         case "DATE" -> "date";
         case "DATE_TIME" -> "timestamptz";
         default -> null;
      };
      boolean searchable = Boolean.TRUE.equals(definition.getValueBoolean("searchable"));
      if(!searchable && !(Boolean.TRUE.equals(definition.getValueBoolean("filterable")) && type != null))
      {
         return (null);
      }
      String expression = "(qqq_custom_fields->>'" + key + "')";
      String prefix = "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + CustomFieldStorage.identifier(index) + " ON " + CustomFieldStorage.sqlTable(table);
      if(type == null && searchable && trigram)
      {
         return (prefix + " USING gin (" + expression + " gin_trgm_ops)");
      }
      return (prefix + " ((" + expression + (type == null ? "" : "::" + type) + "))");
   }

   /*******************************************************************************
    ** Internal diagnostic updates do not recursively refresh the overlay.
    *******************************************************************************/
   private static void state(QRecord definition, String state, String message, String ddl) throws QException
   {
      CollaborationRecords.update(CollaborationTables.FIELD_DEFINITION, new QRecord().withValue("id", definition.getValue("id"))
         .withValue("indexState", state).withValue("statusMessage", message).withValue("indexDdl", ddl));
   }
}
