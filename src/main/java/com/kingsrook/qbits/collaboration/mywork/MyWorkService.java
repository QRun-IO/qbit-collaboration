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


package com.kingsrook.qbits.collaboration.mywork;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.assignment.AssignmentService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.collaboration.tasks.TasksService;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Caller-scoped work summary; parent-invisible rows never contribute counts.
 *******************************************************************************/
public class MyWorkService
{
   /*******************************************************************************
    ** Open task count and first ten, assigned record counts, and unread mentions.
    *******************************************************************************/
   public Summary summary() throws QException
   {
      List<QRecord> tasks = new TasksService().mineRows("OPEN");
      Map<String, Long> assignments = new LinkedHashMap<>();
      for(QRecord row : new AssignmentService().mineRows(null, true))
      {
         assignments.merge(row.getValueString("tableName"), 1L, Long::sum);
      }
      return (new Summary((long) tasks.size(), tasks.stream().limit(10).toList(), assignments, (long) unread().size()));
   }

   /*******************************************************************************
    ** Only delivered, unread mentions of currently visible records are actionable.
    *******************************************************************************/
   private List<QRecord> unread() throws QException
   {
      List<QRecord> result = new ArrayList<>();
      for(QRecord row : CollaborationRecords.query(CollaborationTables.MENTION, new QQueryFilter()
         .withCriteria("mentionedUserId", QCriteriaOperator.EQUALS, PersonalRecords.currentUserId())
         .withCriteria("notifiedAt", QCriteriaOperator.IS_NOT_BLANK).withCriteria("readAt", QCriteriaOperator.IS_BLANK)
         .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false)))
      {
         try
         {
            ParentRecordAccess.requireVisibleParent(row.getValueString("tableName"), row.getValueString("recordId"));
            result.add(row);
         }
         catch(QNotFoundException expected)
         {
            // Do not reveal the existence of mentions on revoked parents.
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** Null selects all visible unread mentions; supplied IDs remain owner-scoped.
    *******************************************************************************/
   public void markMentionsRead(List<Integer> mentionIds) throws QException
   {
      for(QRecord row : unread())
      {
         if(mentionIds == null || mentionIds.contains(row.getValueInteger("id")))
         {
            CollaborationRecords.update(CollaborationTables.MENTION, new QRecord().withValue("id", row.getValue("id")).withValue("readAt", Instant.now()));
         }
      }
   }

   /*******************************************************************************
    ** Stable service contract projected by the My work UI API.
    *******************************************************************************/
   public record Summary(Long openTaskCount, List<QRecord> tasks, Map<String, Long> assignedRecordsByTable, Long unreadMentionCount)
   {
   }
}
