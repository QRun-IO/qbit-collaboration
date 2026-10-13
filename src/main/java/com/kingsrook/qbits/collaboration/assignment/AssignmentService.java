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


package com.kingsrook.qbits.collaboration.assignment;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.WorkSupport;
import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Ownership storage for hosts without native RecordOwnership columns.
 *******************************************************************************/
public class AssignmentService
{
   /*******************************************************************************
    ** Full replacement of both roles; null clears a role.
    *******************************************************************************/
   public QRecord assign(String table, Serializable recordId, String ownerUserId, String assigneeUserId) throws QException
   {
      ParentRecordAccess.requireFeature(table, recordId, "assign");
      String id = FollowService.canonicalId(table, recordId);
      WorkSupport.requireReader(ownerUserId, table, id);
      WorkSupport.requireReader(assigneeUserId, table, id);
      synchronized(CollaborationRecords.config())
      {
         Optional<QRecord> old = get(table, id);
         QRecord change = new QRecord().withValue("ownerUserId", ownerUserId).withValue("assigneeUserId", assigneeUserId);
         QRecord row;
         if(old.isEmpty())
         {
            row = CollaborationRecords.insert(CollaborationTables.RECORD_ASSIGNMENT, change.withValue("tableName", table).withValue("recordId", id).withValue("parentDeleted", false));
         }
         else
         {
            CollaborationRecords.update(CollaborationTables.RECORD_ASSIGNMENT, change.withValue("id", old.get().getValue("id")));
            row = get(table, id).orElseThrow();
         }
         ActivityWriter.write(table, id, "ASSIGNED", "Record assignment changed", CollaborationTables.RECORD_ASSIGNMENT, row.getValueString("id"));
         if(assigneeUserId != null && (old.isEmpty() || !Objects.equals(old.get().getValueString("assigneeUserId"), assigneeUserId)))
         {
            new FollowService().ensureFollower(assigneeUserId, table, id, "ASSIGNED");
            if(!assigneeUserId.equals(PersonalRecords.currentUserId()))
            {
               Notifications.send(new CollaborationNotification(assigneeUserId, "A record was assigned to you", null,
                  CollaborationNotification.Category.TASK, table, id, Notifications.link(table, id)));
            }
         }
         return (row);
      }
   }

   /*******************************************************************************
    ** Parent readers can see assignment independently of the admin-only table nav.
    *******************************************************************************/
   public Optional<QRecord> get(String table, Serializable recordId) throws QException
   {
      return (CollaborationRecords.query(CollaborationTables.RECORD_ASSIGNMENT, CollaborationRecords.parent(table, FollowService.canonicalId(table, recordId))).stream().findFirst());
   }

   /*******************************************************************************
    ** Counts cover all visible matches; limit bounds the rows returned per table.
    *******************************************************************************/
   public Map<String, Group> myRecords(String table, Integer limit) throws QException
   {
      int size = limit == null ? 20 : limit;
      if(size < 1 || size > 100)
      {
         throw (new QException("Limit must be between 1 and 100."));
      }
      Map<String, List<QRecord>> groups = new LinkedHashMap<>();
      for(QRecord row : mineRows(table, false))
      {
         groups.computeIfAbsent(row.getValueString("tableName"), key -> new ArrayList<>()).add(row);
      }
      Map<String, Group> result = new LinkedHashMap<>();
      groups.forEach((name, rows) -> result.put(name, new Group((long) rows.size(), rows.stream().limit(size).toList())));
      return (result);
   }

   /*******************************************************************************
    ** Fresh parent checks protect both personal rows and counts after access changes.
    *******************************************************************************/
   public List<QRecord> mineRows(String table, Boolean assignedOnly) throws QException
   {
      String user = PersonalRecords.currentUserId();
      QQueryFilter roles = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR).withCriteria("assigneeUserId", QCriteriaOperator.EQUALS, user);
      if(!Boolean.TRUE.equals(assignedOnly))
      {
         roles.withCriteria("ownerUserId", QCriteriaOperator.EQUALS, user);
      }
      QQueryFilter filter = new QQueryFilter().withSubFilter(roles).withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false)
         .withOrderBy(new QFilterOrderBy("tableName")).withOrderBy(new QFilterOrderBy("id"));
      if(table != null)
      {
         filter.withCriteria("tableName", QCriteriaOperator.EQUALS, table);
      }
      List<QRecord> visible = new ArrayList<>();
      for(QRecord row : CollaborationRecords.query(CollaborationTables.RECORD_ASSIGNMENT, filter))
      {
         try
         {
            ParentRecordAccess.requireVisibleParent(row.getValueString("tableName"), row.getValueString("recordId"));
            visible.add(row);
         }
         catch(QNotFoundException expected)
         {
            // Revoked access must not contribute even to the count.
         }
      }
      return (visible);
   }

   /*******************************************************************************
    ** Total and first page for one host table.
    *******************************************************************************/
   public record Group(Long count, List<QRecord> records)
   {
   }
}
