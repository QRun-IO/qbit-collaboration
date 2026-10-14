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


package com.kingsrook.qbits.collaboration;

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Internal persistence for trusted services. Callers must authorize the parent
 ** before reading or writing. Never expose these methods as API operations.
 *******************************************************************************/
public final class CollaborationRecords
{
   /*******************************************************************************
    ** Static persistence boundary.
    *******************************************************************************/
   private CollaborationRecords()
   {
   }

   /*******************************************************************************
    ** Resolve the installed qbit configuration.
    *******************************************************************************/
   public static CollaborationQBitConfig config()
   {
      return ((CollaborationQBitConfig) QContext.getQInstance().getQBits().values().stream()
         .filter(bit -> bit.getConfig() instanceof CollaborationQBitConfig).findFirst().orElseThrow().getConfig());
   }

   /*******************************************************************************
    ** Restrict a service query to one canonical parent.
    *******************************************************************************/
   public static QQueryFilter parent(String table, String id)
   {
      return (new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table)
         .withCriteria("recordId", QCriteriaOperator.EQUALS, id).withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false));
   }

   /*******************************************************************************
    ** Raw reads retain transaction visibility but omit presentation customizers.
    *******************************************************************************/
   public static List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(table).withFilter(filter).withShouldFetchHeavyFields(true).withTransaction(QContext.getQBackendTransaction());
      input.setTableMetaData(serviceMetadata(table));
      input.withPermissionBypass("Trusted collaboration service performs parent/ownership authorization.");
      return (new QueryAction().execute(input).getRecords().stream().map(QRecord::new).toList());
   }

   /*******************************************************************************
    ** Persist generated keys and propagate record validation errors.
    *******************************************************************************/
   public static QRecord insert(String table, QRecord row) throws QException
   {
      InsertInput input = new InsertInput(table).withRecords(List.of(row)).withTransaction(QContext.getQBackendTransaction());
      input.setTableMetaData(serviceMetadata(table));
      input.withPermissionBypass("Trusted collaboration service authorized this write.");
      List<QRecord> result = new InsertAction().execute(input).getRecords();
      check(result);
      return (result.getFirst());
   }

   /*******************************************************************************
    ** Only supplied fields are changed; preserve auditing.
    *******************************************************************************/
   public static void update(String table, QRecord row) throws QException
   {
      UpdateInput input = new UpdateInput(table).withRecords(List.of(row)).withTransaction(QContext.getQBackendTransaction());
      input.setTableMetaData(serviceMetadata(table));
      input.withPermissionBypass("Trusted collaboration service authorized this write.");
      check(new UpdateAction().execute(input).getRecords());
   }

   /*******************************************************************************
    ** Delete a service-selected row.
    *******************************************************************************/
   public static void delete(String table, Serializable id) throws QException
   {
      DeleteInput input = new DeleteInput(table).withPrimaryKeys(List.of(id)).withTransaction(QContext.getQBackendTransaction());
      input.setTableMetaData(serviceMetadata(table));
      input.withPermissionBypass("Trusted collaboration service authorized this deletion.");
      check(new DeleteAction().execute(input).getRecordsWithErrors());
   }

   /*******************************************************************************
    ** Trusted after-delete reads must see children whose parent no longer exists.
    *******************************************************************************/
   public static List<QRecord> cleanupQuery(String table, QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(table).withFilter(filter).withShouldFetchHeavyFields(true);
      input.setTableMetaData(cleanupMetadata(table));
      input.withPermissionBypass("Core after-commit parent cleanup.");
      return (new QueryAction().execute(input).getRecords());
   }

   /*******************************************************************************
    ** Delete only identifiers selected by a trusted cleanup provider.
    *******************************************************************************/
   public static void cleanupDelete(String table, Serializable id) throws QException
   {
      DeleteInput input = new DeleteInput(table).withPrimaryKeys(List.of(id));
      input.setTableMetaData(cleanupMetadata(table));
      input.withPermissionBypass("Core after-commit parent cleanup.");
      check(new DeleteAction().execute(input).getRecordsWithErrors());
   }

   /*******************************************************************************
    ** Private metadata for derived cleanup, never installed on the instance.
    *******************************************************************************/
   public static QTableMetaData cleanupMetadata(String table)
   {
      QTableMetaData metadata = serviceMetadata(table);
      metadata.getSupplementalMetaData().remove(com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceTableMetaData.NAME);
      metadata.setRecordSecurityLocks(null);
      return (metadata);
   }

   /*******************************************************************************
    ** A verified share recipient may acknowledge a share without reading its parent.
    *******************************************************************************/
   public static void acknowledgeShare(Integer id, java.time.Instant openedAt) throws QException
   {
      QRecord row = WorkSupport.row(com.kingsrook.qbits.collaboration.model.CollaborationTables.SHARE, id);
      if(!PersonalRecords.currentUserId().equals(row.getValueString("toUserId")))
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QNotFoundException("Not found"));
      }
      UpdateInput input = new UpdateInput(com.kingsrook.qbits.collaboration.model.CollaborationTables.SHARE)
         .withRecords(List.of(new QRecord().withValue("id", id).withValue("openedAt", openedAt))).withTransaction(QContext.getQBackendTransaction());
      input.setTableMetaData(cleanupMetadata(com.kingsrook.qbits.collaboration.model.CollaborationTables.SHARE));
      input.withPermissionBypass("Verified share recipient acknowledges receipt; no parent data is changed.");
      check(new UpdateAction().execute(input).getRecords());
   }

   /*******************************************************************************
    ** Access-request services enforce requester/approver authority explicitly.
    ** Bypass only that owner lock on a private metadata clone, retaining the caller
    ** for audit attribution. Standard actions always retain the installed lock.
    *******************************************************************************/
   private static QTableMetaData serviceMetadata(String table)
   {
      QTableMetaData metadata = QContext.getQInstance().getTable(table).clone();
      if(!table.equals(com.kingsrook.qbits.collaboration.model.CollaborationTables.FEATURE_SETTING))
      {
         metadata.withCustomizers(null);
      }
      if(table.equals(com.kingsrook.qbits.collaboration.model.CollaborationTables.ACCESS_REQUEST))
      {
         metadata.setRecordSecurityLocks(null);
      }
      return (metadata);
   }

   /*******************************************************************************
    ** Core actions can return validation errors instead of throwing.
    *******************************************************************************/
   private static void check(List<QRecord> rows) throws QException
   {
      if(rows != null)
      {
         for(QRecord row : rows)
         {
            if(row.getErrors() != null && !row.getErrors().isEmpty())
            {
               throw (new QException("Could not save collaboration record: " + row.getErrors()));
            }
         }
      }
   }
}
