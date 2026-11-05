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


package com.kingsrook.qbits.collaboration.follow;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
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
 ** Durable five-minute follow digests. RC.5 listeners queue only committed updates.
 ** The scheduler must run once per installation; local synchronization coordinates
 ** listener instances, while unique keys protect subscription/batch identity.
 *******************************************************************************/
public class FollowChangeBatcher implements RecordChangeListenerInterface
{
   private final Clock clock;

   /*******************************************************************************
    ** Core creates a listener instance for each event.
    *******************************************************************************/
   public FollowChangeBatcher()
   {
      this(Clock.systemUTC());
   }

   /*******************************************************************************
    ** Inject time for deterministic batching tests.
    *******************************************************************************/
   public FollowChangeBatcher(Clock clock)
   {
      this.clock = clock;
   }

   /*******************************************************************************
    ** Only configured host-table updates need old records fetched by core.
    *******************************************************************************/
   @Override
   public boolean appliesTo(String tableName, RecordChangeType type)
   {
      return (type == RecordChangeType.UPDATE && CollaborationRecords.config().getFollowChangeTables().contains(tableName));
   }

   /*******************************************************************************
    ** Copy event data before retaining anything in an after-commit callback.
    *******************************************************************************/
   @Override
   public void onRecordsChanged(RecordChangeEvent event) throws QException
   {
      if(!appliesTo(event.getTableName(), event.getType()))
      {
         return;
      }
      String actor = CollaborationSecurity.userId(QContext.getQSession());
      String primaryKey = QContext.getQInstance().getTable(event.getTableName()).getPrimaryKeyField();
      CapturedContext context = new CapturedContext(QContext.getQInstance(), QContext.getQSession());
      for(int i = 0; i < event.getRecords().size(); i++)
      {
         QRecord row = event.getRecords().get(i);
         QRecord old = event.getOldRecords() == null ? null : event.getOldRecords().get(i);
         Set<String> fields = new LinkedHashSet<>();
         for(String field : row.getValues().keySet())
         {
            if(!field.equals(primaryKey) && !field.equals("modifyDate") && (old == null || !Objects.equals(row.getValue(field), old.getValue(field))))
            {
               fields.add(field);
            }
         }
         String id = row.getValueString(primaryKey);
         if(!fields.isEmpty())
         {
            if(event.getTransaction() == null)
            {
               recordChange(event.getTableName(), id, actor, fields);
            }
            else
            {
               event.getTransaction().addAfterCommitCallback(() ->
               {
                  try
                  {
                     QContext.withTemporaryContext(context, () -> recordChange(event.getTableName(), id, actor, fields));
                  }
                  catch(QException e)
                  {
                     throw (new IllegalStateException("Could not queue follow digest", e));
                  }
               });
            }
         }
      }
   }

   /*******************************************************************************
    ** Trusted host-hook helper; invoke after the host update commits. Values are
    ** never copied into digests, and a recipient's own changes are never queued.
    *******************************************************************************/
   public void recordChange(String table, String id, String actor, Set<String> fields) throws QException
   {
      if(fields.isEmpty())
      {
         return;
      }
      synchronized(CollaborationRecords.config())
      {
         Instant now = clock.instant();
         for(QRecord follower : CollaborationRecords.query(CollaborationTables.FOLLOW, CollaborationRecords.parent(table, id)))
         {
            String user = follower.getValueString("userId");
            if(user.equals(actor) || !eligible(follower, now))
            {
               continue;
            }
            List<QRecord> batches = CollaborationRecords.query(CollaborationTables.FOLLOW_DIGEST,
               CollaborationRecords.parent(table, id).withCriteria("userId", QCriteriaOperator.EQUALS, user));
            Set<String> changes = new LinkedHashSet<>();
            if(!batches.isEmpty())
            {
               changes.addAll(Arrays.asList(batches.getFirst().getValueString("changes").split("\n")));
            }
            for(String field : fields)
            {
               changes.add(field + " (by " + (actor == null ? "system" : actor) + ")");
            }
            QRecord batch = new QRecord().withValue("changes", String.join("\n", changes));
            if(batches.isEmpty())
            {
               CollaborationRecords.insert(CollaborationTables.FOLLOW_DIGEST, batch.withValue("tableName", table).withValue("recordId", id)
                  .withValue("userId", user).withValue("dueAt", now.plusSeconds(300)).withValue("parentDeleted", false));
            }
            else
            {
               CollaborationRecords.update(CollaborationTables.FOLLOW_DIGEST, batch.withValue("id", batches.getFirst().getValue("id")));
            }
         }
      }
   }

   /*******************************************************************************
    ** The scheduler invokes outside a transaction so deletion follows delivery.
    ** Re-check current subscription, mute and access; failures retain a retryable row.
    *******************************************************************************/
   public void sendDue() throws QException
   {
      new SendFollowDigestsProcess().checkPermissionsThrowing(null, null);
      if(QContext.getQBackendTransaction() != null)
      {
         throw (new QException("Send follow digests outside a transaction."));
      }
      synchronized(CollaborationRecords.config())
      {
         Instant now = clock.instant();
         for(QRecord batch : CollaborationRecords.query(CollaborationTables.FOLLOW_DIGEST, new QQueryFilter().withCriteria("dueAt", QCriteriaOperator.LESS_THAN_OR_EQUALS, now)))
         {
            String table = batch.getValueString("tableName");
            String id = batch.getValueString("recordId");
            String user = batch.getValueString("userId");
            List<QRecord> follows = CollaborationRecords.query(CollaborationTables.FOLLOW,
               CollaborationRecords.parent(table, id).withCriteria("userId", QCriteriaOperator.EQUALS, user));
            if(!follows.isEmpty() && eligible(follows.getFirst(), now)
               && new com.kingsrook.qbits.collaboration.security.CollaborationRecordAccessChecker().canRead(user, table, id).orElse(false))
            {
               Notifications.send(new CollaborationNotification(user, "Changes to a followed record", batch.getValueString("changes"),
                  CollaborationNotification.Category.FOLLOW, table, id, Notifications.link(table, id)).withEvent(batch.getValue("id"), null),
                  () -> CollaborationRecords.delete(CollaborationTables.FOLLOW_DIGEST, batch.getValue("id")));
            }
            else
            {
               CollaborationRecords.delete(CollaborationTables.FOLLOW_DIGEST, batch.getValue("id"));
            }
         }
      }
   }

   /*******************************************************************************
    ** Mute ends at its timestamp, including exact equality with now.
    *******************************************************************************/
   private boolean eligible(QRecord row, Instant now)
   {
      return (Boolean.TRUE.equals(row.getValueBoolean("notifyOnChange"))
         && (row.getValueInstant("mutedUntil") == null || !row.getValueInstant("mutedUntil").isAfter(now)));
   }
}
