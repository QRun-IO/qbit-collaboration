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

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.WorkSupport;
import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.follow.FollowChangeBatcher;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Record tasks, authorized as the caller and timed through an injectable clock.
 *******************************************************************************/
public class TasksService
{
   private final Clock clock;
   private static final Comparator<QRecord> ORDER = Comparator.comparing((QRecord row) -> row.getValueInstant("dueAt"), Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(row -> row.getValueInteger("id"));

   /*******************************************************************************
    ** Production time source.
    *******************************************************************************/
   public TasksService()
   {
      this(Clock.systemUTC());
   }

   /*******************************************************************************
    ** Deterministic reminder and completion times.
    *******************************************************************************/
   public TasksService(Clock clock)
   {
      this.clock = clock;
   }

   /*******************************************************************************
    ** An omitted assignee defaults to the creator.
    *******************************************************************************/
   public QRecord create(String table, Serializable recordId, String title, String notes, String assigneeUserId, Instant dueAt, Instant remindAt) throws QException
   {
      ParentRecordAccess.requireFeature(table, recordId, "task");
      String id = FollowService.canonicalId(table, recordId);
      String user = PersonalRecords.currentUserId();
      String assignee = assigneeUserId == null ? user : assigneeUserId;
      WorkSupport.text(title, 200, true);
      WorkSupport.requireReader(assignee, table, id);
      QRecord row = CollaborationRecords.insert(CollaborationTables.TASK, new QRecord().withValue("tableName", table).withValue("recordId", id)
         .withValue("title", title).withValue("notes", notes).withValue("assigneeUserId", assignee).withValue("dueAt", dueAt)
         .withValue("remindAt", reminder(dueAt, remindAt)).withValue("status", "OPEN").withValue("createdByUserId", user).withValue("parentDeleted", false));
      activity(row, "TASK_CREATED", "Task created");
      assigned(row);
      return (row);
   }

   /*******************************************************************************
    ** Full editable-field replacement; null dates clear them, null assignee unassigns.
    *******************************************************************************/
   public QRecord update(Integer taskId, String title, String notes, String assigneeUserId, Instant dueAt, Instant remindAt) throws QException
   {
      QRecord old = requireManager(taskId);
      WorkSupport.text(title, 200, true);
      WorkSupport.requireReader(assigneeUserId, old.getValueString("tableName"), old.getValueString("recordId"));
      QRecord change = new QRecord().withValue("id", taskId).withValue("title", title).withValue("notes", notes)
         .withValue("assigneeUserId", assigneeUserId).withValue("dueAt", dueAt).withValue("remindAt", reminder(dueAt, remindAt));
      if(!Objects.equals(old.getValueInstant("remindAt"), change.getValueInstant("remindAt")) || !Objects.equals(old.getValueString("assigneeUserId"), assigneeUserId))
      {
         change.withValue("remindedAt", null);
      }
      if(!Objects.equals(old.getValueInstant("dueAt"), dueAt) || !Objects.equals(old.getValueString("assigneeUserId"), assigneeUserId))
      {
         change.withValue("overdueNotifiedAt", null);
      }
      CollaborationRecords.update(CollaborationTables.TASK, change);
      QRecord row = WorkSupport.visible(CollaborationTables.TASK, taskId);
      if(!Objects.equals(old.getValueString("assigneeUserId"), assigneeUserId))
      {
         assigned(row);
      }
      return (row);
   }

   /*******************************************************************************
    ** Creator, assignee and admins still need current parent and task permission.
    *******************************************************************************/
   public QRecord requireManager(Integer taskId) throws QException
   {
      QRecord row = WorkSupport.visible(CollaborationTables.TASK, taskId);
      String user = PersonalRecords.currentUserId();
      ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "task");
      if(!user.equals(row.getValueString("createdByUserId")) && !user.equals(row.getValueString("assigneeUserId"))
         && !QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN))
      {
         throw (new QPermissionDeniedException("Only the task creator, assignee or collaboration.admin can change this task."));
      }
      return (row);
   }

   /*******************************************************************************
    ** Idempotent completion emits one activity for the transition.
    *******************************************************************************/
   public QRecord complete(Integer taskId) throws QException
   {
      return (transition(taskId, "DONE"));
   }

   /*******************************************************************************
    ** Reopening clears completion fields, retaining delivery history.
    *******************************************************************************/
   public QRecord reopen(Integer taskId) throws QException
   {
      return (transition(taskId, "OPEN"));
   }

   /*******************************************************************************
    ** Cancellation stops scheduled notifications.
    *******************************************************************************/
   public QRecord cancel(Integer taskId) throws QException
   {
      return (transition(taskId, "CANCELLED"));
   }

   /*******************************************************************************
    ** Serialize transitions within the installation, like existing follow services.
    *******************************************************************************/
   private QRecord transition(Integer taskId, String status) throws QException
   {
      synchronized(CollaborationRecords.config())
      {
         QRecord row = requireManager(taskId);
         if(!status.equals(row.getValueString("status")))
         {
            CollaborationRecords.update(CollaborationTables.TASK, new QRecord().withValue("id", taskId).withValue("status", status)
               .withValue("completedAt", status.equals("DONE") ? clock.instant() : null)
               .withValue("completedByUserId", status.equals("DONE") ? PersonalRecords.currentUserId() : null));
            if(status.equals("DONE"))
            {
               activity(row, "TASK_DONE", "Task completed");
            }
         }
         return (WorkSupport.visible(CollaborationTables.TASK, taskId));
      }
   }

   /*******************************************************************************
    ** Parent readers can list open tasks, optionally including terminal tasks.
    *******************************************************************************/
   public List<QRecord> list(String table, Serializable recordId, Boolean includeDone) throws QException
   {
      QQueryFilter filter = CollaborationRecords.parent(table, FollowService.canonicalId(table, recordId));
      if(!Boolean.TRUE.equals(includeDone))
      {
         filter.withCriteria("status", QCriteriaOperator.EQUALS, "OPEN");
      }
      return (CollaborationRecords.query(CollaborationTables.TASK, filter).stream().sorted(ORDER).toList());
   }

   /*******************************************************************************
    ** Due-date keyset paging, null dates last, with stable ID tie-breaking.
    *******************************************************************************/
   public Page mine(String status, Integer limit, String cursor) throws QException
   {
      int size = limit == null ? 20 : limit;
      if(size < 1 || size > 100)
      {
         throw (new QException("Limit must be between 1 and 100."));
      }
      List<QRecord> rows = mineRows(status);
      if(cursor != null)
      {
         QRecord after = decode(cursor);
         rows = rows.stream().filter(row -> ORDER.compare(row, after) > 0).toList();
      }
      List<QRecord> page = rows.stream().limit(size).toList();
      return (new Page(page, rows.size() > size ? encode(page.getLast()) : null));
   }

   /*******************************************************************************
    ** Also supplies exact visible counts for My work; no aggregate lock leakage.
    *******************************************************************************/
   public List<QRecord> mineRows(String status) throws QException
   {
      String selected = status == null ? "OPEN" : status;
      if(!List.of("OPEN", "DONE", "CANCELLED").contains(selected))
      {
         throw (new QException("Unknown task status."));
      }
      List<QRecord> visible = new ArrayList<>();
      for(QRecord row : CollaborationRecords.query(CollaborationTables.TASK, new QQueryFilter()
         .withCriteria("assigneeUserId", QCriteriaOperator.EQUALS, PersonalRecords.currentUserId()).withCriteria("status", QCriteriaOperator.EQUALS, selected)
         .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false)))
      {
         try
         {
            ParentRecordAccess.requireVisibleParent(row.getValueString("tableName"), row.getValueString("recordId"));
            visible.add(row);
         }
         catch(QNotFoundException expected)
         {
            // Removed access removes the task from personal results.
         }
      }
      return (visible.stream().sorted(ORDER).toList());
   }

   /*******************************************************************************
    ** The default never schedules a reminder in the past for a future due date.
    *******************************************************************************/
   private Instant reminder(Instant due, Instant supplied)
   {
      return (supplied != null ? supplied : due == null ? null : due.minusSeconds(86400).isAfter(clock.instant()) ? due.minusSeconds(86400) : due);
   }

   /*******************************************************************************
    ** Assignees become followers; self-assignment avoids an immediate notification.
    *******************************************************************************/
   private void assigned(QRecord row) throws QException
   {
      String user = row.getValueString("assigneeUserId");
      if(user != null)
      {
         String table = row.getValueString("tableName");
         String id = row.getValueString("recordId");
         new FollowService().ensureFollower(user, table, id, "ASSIGNED");
         if(!user.equals(PersonalRecords.currentUserId()))
         {
            Notifications.send(new CollaborationNotification(user, "Task assigned: " + row.getValueString("title"), row.getValueString("notes"),
               CollaborationNotification.Category.TASK, table, id, Notifications.link(table, id)));
         }
      }
   }

   /*******************************************************************************
    ** Task events also enter the existing notify-on-change follower digest.
    *******************************************************************************/
   private void activity(QRecord row, String type, String summary) throws QException
   {
      String table = row.getValueString("tableName");
      String id = row.getValueString("recordId");
      ActivityWriter.write(table, id, type, summary, CollaborationTables.TASK, row.getValueString("id"));
      new FollowChangeBatcher(clock).recordChange(table, id, PersonalRecords.currentUserId(), Set.of(summary));
   }

   /*******************************************************************************
    ** Versioned cursor includes only the sort tuple.
    *******************************************************************************/
   private String encode(QRecord row)
   {
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(("1\n" + row.getValueInstant("dueAt") + "\n" + row.getValueInteger("id")).getBytes(StandardCharsets.UTF_8)));
   }

   /*******************************************************************************
    ** Reject malformed cursor input before interpreting the sort tuple.
    *******************************************************************************/
   private QRecord decode(String cursor) throws QException
   {
      try
      {
         String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n");
         if(parts.length != 3 || !parts[0].equals("1"))
         {
            throw (new IllegalArgumentException("Invalid cursor"));
         }
         return (new QRecord().withValue("dueAt", parts[1].equals("null") ? null : Instant.parse(parts[1])).withValue("id", Integer.valueOf(parts[2])));
      }
      catch(RuntimeException e)
      {
         throw (new QException("Invalid cursor", e));
      }
   }

   /*******************************************************************************
    ** Service page consumed by the future endpoint adapter.
    *******************************************************************************/
   public record Page(List<QRecord> tasks, String nextCursor)
   {
   }
}
