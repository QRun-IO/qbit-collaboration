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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.mywork.MyWorkService;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Measure backend queries while preserving exact, caller-visible summary counts.
 *******************************************************************************/
class MyWorkQueryBudgetTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Counts cross page boundaries without including another user's or hidden work.
    *******************************************************************************/
   @Test
   void summaryPagesAssignmentsAndMentionsWithoutLeakingCounts() throws Exception
   {
      List<QRecord> parents = new ArrayList<>();
      List<QRecord> assignments = new ArrayList<>();
      List<QRecord> mentions = new ArrayList<>();
      for(int id = 1; id <= 208; id++)
      {
         if(id > 3)
         {
            parents.add(new QRecord().withValue("id", id).withValue("title", "Parent " + id).withValue("tenant", id == 208 ? "b" : "a"));
         }
         assignments.add(new QRecord().withValue("id", id).withValue("tableName", "document").withValue("recordId", Integer.toString(id))
            .withValue("assigneeUserId", id == 207 ? "bob" : "alice").withValue("parentDeleted", false));
      }
      for(int id = 1; id <= 601; id++)
      {
         mentions.add(new QRecord().withValue("id", id).withValue("tableName", "document").withValue("recordId", Integer.toString(id % 3 + 1))
            .withValue("mentionedUserId", "alice").withValue("notifiedAt", Instant.now()).withValue("parentDeleted", false));
      }
      mentions.add(new QRecord().withValue("id", 602).withValue("tableName", "document").withValue("recordId", "1")
         .withValue("mentionedUserId", "bob").withValue("notifiedAt", Instant.now()).withValue("parentDeleted", false));
      mentions.add(new QRecord().withValue("id", 603).withValue("tableName", "document").withValue("recordId", "1")
         .withValue("mentionedUserId", "alice").withValue("parentDeleted", false));
      MemoryRecordStore.getInstance().insert(new InsertInput("document").withRecords(parents), true);
      MemoryRecordStore.getInstance().insert(new InsertInput(CollaborationTables.RECORD_ASSIGNMENT).withRecords(assignments), true);
      MemoryRecordStore.getInstance().insert(new InsertInput(CollaborationTables.MENTION).withRecords(mentions), true);
      MemoryRecordStore.resetStatistics();
      MemoryRecordStore.setCollectStatistics(true);
      try
      {
         var summary = new MyWorkService().summary();
         assertThat(summary.assignedRecordsByTable()).containsOnlyKeys("document").containsEntry("document", 205L);
         assertThat(summary.unreadMentionCount()).isEqualTo(401);
         assertThat(summary.openTaskCount()).isZero();
         assertThat(summary.tasks()).isEmpty();
         MemoryRecordStore.getActionInputs().get(QueryInput.class).stream().map(input -> (QueryInput) input)
            .filter(input -> input.getTableName().startsWith("collaboration_"))
            .forEach(input -> assertThat(input.getFilter().getLimit()).isNotNull().isLessThanOrEqualTo(200));
      }
      finally
      {
         MemoryRecordStore.setCollectStatistics(false);
         MemoryRecordStore.resetStatistics();
      }
   }

   /*******************************************************************************
    ** Hundreds of tasks on one parent must not cause hundreds of parent lookups.
    *******************************************************************************/
   @Test
   void summaryBoundsPagesAndRepeatedParentLookups() throws Exception
   {
      List<QRecord> rows = new ArrayList<>();
      for(int id = 1; id <= 601; id++)
      {
         rows.add(new QRecord().withValue("id", id).withValue("tableName", "document")
            .withValue("recordId", id == 601 ? "3" : "1").withValue("title", "Task " + id)
            .withValue("assigneeUserId", "alice").withValue("createdByUserId", "alice")
            .withValue("status", "OPEN").withValue("parentDeleted", false)
            .withValue("dueAt", id % 2 == 0 ? Instant.parse("2026-10-22T00:00:00Z").plusSeconds(601 - id) : null));
      }
      // Populate historical rows directly so current parent authorization is tested on read.
      MemoryRecordStore.getInstance().insert(new InsertInput(CollaborationTables.TASK).withRecords(rows), true);
      MemoryRecordStore.resetStatistics();
      MemoryRecordStore.setCollectStatistics(true);
      try
      {
         var summary = new MyWorkService().summary();
         assertThat(summary.openTaskCount()).isEqualTo(600);
         assertThat(summary.tasks()).extracting(row -> row.getValueInteger("id"))
            .containsExactly(600, 598, 596, 594, 592, 590, 588, 586, 584, 582);
         List<QueryInput> queries = MemoryRecordStore.getActionInputs().get(QueryInput.class).stream()
            .map(input -> (QueryInput) input).toList();
         assertThat(queries.stream().filter(input -> "document".equals(input.getTableName())).count()).isLessThanOrEqualTo(8);
         assertThat(queries.stream().filter(input -> CollaborationTables.TASK.equals(input.getTableName())))
            .allSatisfy(input -> assertThat(input.getFilter().getLimit()).isNotNull().isLessThanOrEqualTo(200));
         // A second request must recheck current permissions rather than reuse cached decisions.
         QContext.getQSession().getSecurityKeyValues().put("tenant", new ArrayList<>(List.of("b")));
         assertThat(new MyWorkService().summary().openTaskCount()).isEqualTo(1);
      }
      finally
      {
         MemoryRecordStore.setCollectStatistics(false);
         MemoryRecordStore.resetStatistics();
      }
   }
}
