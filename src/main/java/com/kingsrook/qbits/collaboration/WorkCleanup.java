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
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeEvent;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeListenerInterface;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeType;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Trusted after-delete hook. Access requests deliberately survive parent deletion.
 *******************************************************************************/
public class WorkCleanup implements RecordChangeListenerInterface
{
   /*******************************************************************************
    ** Host deletions only; extension deletes must not recursively invoke cleanup.
    *******************************************************************************/
   @Override
   public boolean appliesTo(String table, RecordChangeType type)
   {
      return (type == RecordChangeType.DELETE && !table.startsWith(CollaborationQBitConfig.TABLE_NAME_PREFIX));
   }

   /*******************************************************************************
    ** Defer cleanup until the parent deletion commits.
    *******************************************************************************/
   @Override
   public void onRecordsChanged(RecordChangeEvent event) throws QException
   {
      if(!appliesTo(event.getTableName(), event.getType()))
      {
         return;
      }
      String table = event.getTableName();
      String key = QContext.getQInstance().getTable(table).getPrimaryKeyField();
      List<Serializable> ids = event.getRecords().stream().map(row -> row.getValue(key)).toList();
      if(event.getTransaction() == null)
      {
         onParentsDeleted(table, ids);
      }
      else
      {
         CapturedContext context = new CapturedContext(QContext.getQInstance(), QContext.getQSession());
         event.getTransaction().addAfterCommitCallback(() ->
         {
            try
            {
               QContext.withTemporaryContext(context, () -> onParentsDeleted(table, ids));
            }
            catch(QException e)
            {
               throw (new IllegalStateException("Could not clean up collaboration work", e));
            }
         });
      }
   }

   /*******************************************************************************
    ** Bounded batches and idempotent deletion also support explicit host callbacks.
    *******************************************************************************/
   public void onParentsDeleted(String table, List<? extends Serializable> ids) throws QException
   {
      if(ids.isEmpty())
      {
         return;
      }
      for(String child : List.of(CollaborationTables.TASK, CollaborationTables.RECORD_ASSIGNMENT, CollaborationTables.SHARE))
      {
         while(true)
         {
            List<QRecord> rows = CollaborationRecords.query(child, new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table)
               .withCriteria("recordId", QCriteriaOperator.IN, ids.stream().map(Object::toString).toList()).withLimit(200));
            if(rows.isEmpty())
            {
               break;
            }
            for(QRecord row : rows)
            {
               CollaborationRecords.delete(child, row.getValue("id"));
            }
         }
      }
   }
}
