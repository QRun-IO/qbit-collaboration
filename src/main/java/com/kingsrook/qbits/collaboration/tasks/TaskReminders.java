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


package com.kingsrook.qbits.collaboration.tasks;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** One scheduler per installation; receipts follow successful delivery only.
 *******************************************************************************/
public class TaskReminders
{
   private final Clock clock;

   /*******************************************************************************
    ** Production clock.
    *******************************************************************************/
   public TaskReminders()
   {
      this(Clock.systemUTC());
   }

   /*******************************************************************************
    ** Clock shared by due selection and successful-delivery receipts.
    *******************************************************************************/
   public TaskReminders(Clock clock)
   {
      this.clock = clock;
   }

   /*******************************************************************************
    ** No open transaction may defer receipts past the scheduler's serialization lock.
    *******************************************************************************/
   public void sendDue() throws QException
   {
      new SendTaskRemindersProcess().checkPermissionsThrowing(null, null);
      if(QContext.getQBackendTransaction() != null)
      {
         throw (new QException("Send task reminders outside a transaction."));
      }
      synchronized(CollaborationRecords.config())
      {
         Instant now = clock.instant();
         for(String dueField : List.of("remindAt", "dueAt"))
         {
            String receipt = dueField.equals("remindAt") ? "remindedAt" : "overdueNotifiedAt";
            QQueryFilter filter = new QQueryFilter().withCriteria("status", QCriteriaOperator.EQUALS, "OPEN")
               .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false).withCriteria("assigneeUserId", QCriteriaOperator.IS_NOT_BLANK)
               .withCriteria(receipt, QCriteriaOperator.IS_BLANK).withCriteria(dueField, QCriteriaOperator.IS_NOT_BLANK)
               .withCriteria(dueField, dueField.equals("dueAt") ? QCriteriaOperator.LESS_THAN : QCriteriaOperator.LESS_THAN_OR_EQUALS, now);
            for(QRecord row : CollaborationRecords.query(CollaborationTables.TASK, filter))
            {
               String table = row.getValueString("tableName");
               String id = row.getValueString("recordId");
               Notifications.send(new CollaborationNotification(row.getValueString("assigneeUserId"),
                  (dueField.equals("remindAt") ? "Task reminder: " : "Task overdue: ") + row.getValueString("title"), row.getValueString("notes"),
                  CollaborationNotification.Category.TASK, table, id, Notifications.link(table, id)),
                  () -> CollaborationRecords.update(CollaborationTables.TASK, new QRecord().withValue("id", row.getValue("id")).withValue(receipt, now)));
            }
         }
      }
   }
}
