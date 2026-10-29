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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Caller-scoped work summary; parent-invisible rows never contribute counts.
 *******************************************************************************/
public class MyWorkService
{
   private static final int PAGE_SIZE = 200;
   private static final Comparator<QRecord> TASK_ORDER = Comparator.comparing((QRecord row) -> row.getValueInstant("dueAt"), Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(row -> row.getValueInteger("id"));

   /*******************************************************************************
    ** Open task count and first ten, assigned record counts, and unread mentions.
    *******************************************************************************/
   public Summary summary() throws QException
   {
      String user = PersonalRecords.currentUserId();
      List<QRecord> tasks = new ArrayList<>();
      long taskCount = scanVisible(CollaborationTables.TASK, new QQueryFilter()
         .withCriteria("assigneeUserId", QCriteriaOperator.EQUALS, user)
         .withCriteria("status", QCriteriaOperator.EQUALS, "OPEN"), row ->
         {
            tasks.add(row);
            tasks.sort(TASK_ORDER);
            if(tasks.size() > 10)
            {
               tasks.removeLast();
            }
         });
      Map<String, Long> assignments = new LinkedHashMap<>();
      scanVisible(CollaborationTables.RECORD_ASSIGNMENT, new QQueryFilter()
         .withCriteria("assigneeUserId", QCriteriaOperator.EQUALS, user),
         row -> assignments.merge(row.getValueString("tableName"), 1L, Long::sum));
      long mentions = scanVisible(CollaborationTables.MENTION, new QQueryFilter()
         .withCriteria("mentionedUserId", QCriteriaOperator.EQUALS, user)
         .withCriteria("notifiedAt", QCriteriaOperator.IS_NOT_BLANK).withCriteria("readAt", QCriteriaOperator.IS_BLANK), row ->
         {
            // Only the visible count is needed for mentions.
         });
      return (new Summary(taskCount, List.copyOf(tasks), assignments, mentions));
   }

   /*******************************************************************************
    ** Bound retained rows and visibility decisions to one page. A starting high
    ** watermark prevents concurrent inserts from extending this scan indefinitely.
    ** Counts remain exact for visible rows scanned; no cross-request auth cache.
    *******************************************************************************/
   private long scanVisible(String table, QQueryFilter filter, java.util.function.Consumer<QRecord> consumer) throws QException
   {
      filter.withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false);
      List<QRecord> last = CollaborationRecords.query(table, filter.clone().withOrderBy(new QFilterOrderBy("id", false)).withLimit(1));
      if(last.isEmpty())
      {
         return (0);
      }
      int maximum = last.getFirst().getValueInteger("id");
      Integer cursor = null;
      long count = 0;
      while(true)
      {
         QQueryFilter pageFilter = filter.clone().withCriteria("id", QCriteriaOperator.LESS_THAN_OR_EQUALS, maximum)
            .withOrderBy(new QFilterOrderBy("id")).withLimit(PAGE_SIZE);
         if(cursor != null)
         {
            pageFilter.withCriteria("id", QCriteriaOperator.GREATER_THAN, cursor);
         }
         List<QRecord> rows = CollaborationRecords.query(table, pageFilter);
         Map<List<String>, Boolean> parents = new HashMap<>();
         for(QRecord row : rows)
         {
            List<String> parent = java.util.Arrays.asList(row.getValueString("tableName"), row.getValueString("recordId"));
            if(!parents.containsKey(parent))
            {
               try
               {
                  ParentRecordAccess.requireVisibleParent(parent.get(0), parent.get(1));
                  parents.put(parent, true);
               }
               catch(QNotFoundException expected)
               {
                  parents.put(parent, false);
               }
            }
            if(parents.get(parent))
            {
               consumer.accept(row);
               count++;
            }
         }
         if(rows.size() < PAGE_SIZE || rows.getLast().getValueInteger("id") >= maximum)
         {
            return (count);
         }
         cursor = rows.getLast().getValueInteger("id");
      }
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
