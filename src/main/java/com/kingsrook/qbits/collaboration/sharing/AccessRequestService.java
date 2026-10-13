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


package com.kingsrook.qbits.collaboration.sharing;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.TargetResolver;
import com.kingsrook.qbits.collaboration.WorkSupport;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;

/*******************************************************************************
 ** Requests never inspect the parent. Their public result reveals no existence data.
 *******************************************************************************/
public class AccessRequestService
{
   private static final QLogger LOG = QLogger.getLogger(AccessRequestService.class);
   private static final AtomicBoolean DIRECTORY_WARNING = new AtomicBoolean();

   /*******************************************************************************
    ** Persist once per open natural key; repeated submissions return the same row.
    *******************************************************************************/
   public QRecord request(String table, Serializable recordId, String message, Integer shareId) throws QException
   {
      String user = PersonalRecords.currentUserId();
      WorkSupport.text(table, 128, true);
      WorkSupport.text(recordId == null ? null : recordId.toString(), 64, true);
      WorkSupport.text(message, 2000, false);
      String id = TargetResolver.recordId("RECORD", table, recordId);
      synchronized(CollaborationRecords.config())
      {
         QQueryFilter filter = new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table)
            .withCriteria("recordId", QCriteriaOperator.EQUALS, id).withCriteria("requesterUserId", QCriteriaOperator.EQUALS, user)
            .withCriteria("status", QCriteriaOperator.EQUALS, "OPEN");
         List<QRecord> existing = CollaborationRecords.query(CollaborationTables.ACCESS_REQUEST, filter);
         if(!existing.isEmpty())
         {
            return (existing.getFirst());
         }
         if(shareId != null)
         {
            QRecord share = WorkSupport.row(CollaborationTables.SHARE, shareId);
            if(!user.equals(share.getValueString("toUserId")) || !table.equals(share.getValueString("tableName")) || !id.equals(share.getValueString("recordId")))
            {
               throw (new QNotFoundException("Not found"));
            }
         }
         QRecord row;
         try
         {
            row = CollaborationRecords.insert(CollaborationTables.ACCESS_REQUEST, new QRecord().withValue("tableName", table).withValue("recordId", id)
               .withValue("requesterUserId", user).withValue("message", message).withValue("shareId", shareId).withValue("status", "OPEN"));
         }
         catch(QException e)
         {
            existing = CollaborationRecords.query(CollaborationTables.ACCESS_REQUEST, filter);
            if(existing.isEmpty())
            {
               throw (e);
            }
            return (existing.getFirst());
         }
         notifyApprovers(row);
         return (row);
      }
   }

   /*******************************************************************************
    ** Resolve table sharing policy without touching any parent row.
    *******************************************************************************/
   public String approverPermission(String table) throws QException
   {
      for(QRecord setting : CollaborationRecords.query(CollaborationTables.FEATURE_SETTING, new QQueryFilter()
         .withCriteria("scopeType", QCriteriaOperator.EQUALS, "TABLE").withCriteria("scopeName", QCriteriaOperator.EQUALS, table)
         .withCriteria("feature", QCriteriaOperator.EQUALS, "SHARING")))
      {
         String json = setting.getValueString("settings");
         if(json != null && !json.isBlank())
         {
            try
            {
               Object permission = JsonUtils.toObject(json, Map.class).get("accessApproverPermission");
               if(permission != null && !permission.toString().isBlank())
               {
                  return (permission.toString());
               }
            }
            catch(Exception e)
            {
               throw (new QException("Invalid SHARING settings JSON", e));
            }
         }
      }
      return (CollaborationPermissions.ADMIN);
   }

   /*******************************************************************************
    ** Owner lookup uses only the assignment directory, never a parent existence check.
    *******************************************************************************/
   private void notifyApprovers(QRecord row) throws QException
   {
      Set<String> users = new LinkedHashSet<>();
      var reference = CollaborationRecords.config().getPermissionHolderDirectory();
      if(reference == null)
      {
         if(DIRECTORY_WARNING.compareAndSet(false, true))
         {
            LOG.warn("Access approvers cannot be resolved without PermissionHolderDirectory; only configured record owners will be notified.");
         }
      }
      else
      {
         PermissionHolderDirectory directory = QCodeLoader.getAdHoc(PermissionHolderDirectory.class, reference);
         users.addAll(directory.userIdsWithPermission(CollaborationPermissions.ADMIN));
         users.addAll(directory.userIdsWithPermission(approverPermission(row.getValueString("tableName"))));
      }
      for(QRecord assignment : CollaborationRecords.query(CollaborationTables.RECORD_ASSIGNMENT,
         CollaborationRecords.parent(row.getValueString("tableName"), row.getValueString("recordId"))))
      {
         if(assignment.getValueString("ownerUserId") != null)
         {
            users.add(assignment.getValueString("ownerUserId"));
         }
      }
      for(String user : users)
      {
         notice(user, "Record access requested", row.getValueInteger("id"));
      }
   }

   /*******************************************************************************
    ** Requesters can only withdraw their own still-open request.
    *******************************************************************************/
   public void withdraw(Integer requestId) throws QException
   {
      QRecord row = WorkSupport.row(CollaborationTables.ACCESS_REQUEST, requestId);
      if(!PersonalRecords.currentUserId().equals(row.getValueString("requesterUserId")))
      {
         throw (new QNotFoundException("Not found"));
      }
      requireOpen(row);
      CollaborationRecords.update(CollaborationTables.ACCESS_REQUEST, new QRecord().withValue("id", requestId).withValue("status", "WITHDRAWN"));
   }

   /*******************************************************************************
    ** Approver permissions authorize only recording an outcome, never an access grant.
    *******************************************************************************/
   public QRecord requireApprover(Integer requestId) throws QException
   {
      PersonalRecords.currentUserId();
      QRecord row = WorkSupport.row(CollaborationTables.ACCESS_REQUEST, requestId);
      if(!QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN)
         && !QContext.getQSession().hasPermission(approverPermission(row.getValueString("tableName"))))
      {
         throw (new QPermissionDeniedException("This needs the access approver permission."));
      }
      requireOpen(row);
      return (row);
   }

   /*******************************************************************************
    ** The host identity system is responsible for any actual permission change.
    *******************************************************************************/
   public void handle(Integer requestId, String status, String note) throws QException
   {
      WorkSupport.text(note, 2000, false);
      if(!List.of("GRANTED", "DECLINED").contains(status))
      {
         throw (new QException("Choose GRANTED or DECLINED."));
      }
      synchronized(CollaborationRecords.config())
      {
         QRecord row = requireApprover(requestId);
         CollaborationRecords.update(CollaborationTables.ACCESS_REQUEST, new QRecord().withValue("id", requestId).withValue("status", status)
            .withValue("note", note).withValue("handledAt", Instant.now()).withValue("handledByUserId", PersonalRecords.currentUserId()));
         notice(row.getValueString("requesterUserId"), "Access request " + status.toLowerCase(java.util.Locale.ROOT), requestId);
      }
   }

   /*******************************************************************************
    ** Terminal outcomes cannot be overwritten accidentally.
    *******************************************************************************/
   private void requireOpen(QRecord row) throws QException
   {
      if(!"OPEN".equals(row.getValueString("status")))
      {
         throw (new QException("This access request is no longer open."));
      }
   }

   /*******************************************************************************
    ** Notices contain no record label, request message or approver note.
    *******************************************************************************/
   private void notice(String user, String title, Integer id) throws QException
   {
      Notifications.sendAccessNotice(new CollaborationNotification(user, title, "Review the access request.",
         CollaborationNotification.Category.SHARE, null, null, "/app/collaboration_access_request/" + id));
   }
}
