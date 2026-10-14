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

import java.util.Map;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeEvent;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeListenerInterface;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeType;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;

/*******************************************************************************
 ** Identifier-only hints, published after successful commit.
 *******************************************************************************/
public class CollaborationRealtime implements RecordChangeListenerInterface
{
   private static final QLogger LOG = QLogger.getLogger(CollaborationRealtime.class);

   /*******************************************************************************
    ** Only records with client invalidation contracts.
    *******************************************************************************/
   @Override
   public boolean appliesTo(String table, RecordChangeType type)
   {
      return (java.util.List.of(CollaborationTables.COMMENT, CollaborationTables.ATTACHMENT, CollaborationTables.RECORD_TAG, CollaborationTables.TASK).contains(table));
   }

   /*******************************************************************************
    ** Capture immutable payloads while the write's session and transaction are active.
    *******************************************************************************/
   @Override
   public void onRecordsChanged(RecordChangeEvent change) throws QException
   {
      var instance = QContext.getQInstance();
      for(int index = 0; index < change.getRecords().size(); index++)
      {
         var row = new com.kingsrook.qqq.backend.core.model.data.QRecord(change.getRecords().get(index));
         if(change.getOldRecords() != null && index < change.getOldRecords().size() && change.getOldRecords().get(index) != null)
         {
            var complete = new com.kingsrook.qqq.backend.core.model.data.QRecord(change.getOldRecords().get(index));
            complete.getValues().putAll(row.getValues());
            row = complete;
         }
         String table = row.getValueString("tableName");
         String id = row.getValueString("recordId");
         if(table == null || id == null)
         {
            continue;
         }
         String event = switch(change.getTableName())
         {
            case CollaborationTables.COMMENT -> change.getType() == RecordChangeType.DELETE || row.getValue("deletedAt") != null
               ? "comment.deleted" : change.getType() == RecordChangeType.INSERT ? "comment.added" : "comment.updated";
            case CollaborationTables.ATTACHMENT -> "attachment.changed";
            case CollaborationTables.RECORD_TAG -> "tags.changed";
            default -> "task.changed";
         };
         var message = new RealtimeMessage().withChannel("record:" + table + ":" + id).withEvent(event)
            .withData(Map.of("table", table, "pk", id, "id", row.getValue("id")));
         Runnable publish = () ->
         {
            try
            {
               RealtimeBus.get(instance).publish(message);
            }
            catch(Exception e)
            {
               LOG.warn("Could not publish collaboration invalidation", e);
            }
         };
         if(change.getTransaction() == null)
         {
            publish.run();
         }
         else
         {
            change.getTransaction().addAfterCommitCallback(publish);
         }
      }
   }
}
