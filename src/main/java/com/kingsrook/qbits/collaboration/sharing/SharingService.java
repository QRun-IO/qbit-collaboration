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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.TargetResolver;
import com.kingsrook.qbits.collaboration.WorkSupport;
import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.security.CollaborationRecordAccessChecker;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Shares communicate intent; they never change permissions.
 *******************************************************************************/
public class SharingService
{
   /*******************************************************************************
    ** Canonical link and a fresh label resolved as the caller.
    *******************************************************************************/
   public Link link(String table, Serializable recordId) throws QException
   {
      ParentRecordAccess.requireVisibleParent(table, recordId);
      var target = TargetResolver.require("RECORD", table, recordId);
      return (new Link(TargetResolver.recordHref(table, target.recordId()), target.label()));
   }

   /*******************************************************************************
    ** Unknown recipient access is represented by null, never converted to true.
    *******************************************************************************/
   public List<Recipient> preview(String table, Serializable recordId, List<String> toUserIds) throws QException
   {
      new PeopleService().touch();
      ParentRecordAccess.requireFeature(table, recordId, "share");
      if(toUserIds == null || toUserIds.isEmpty() || toUserIds.size() > 25 || toUserIds.stream().anyMatch(user -> user == null || user.isBlank()))
      {
         throw (new QException("Choose 1 to 25 recipients."));
      }
      List<Recipient> recipients = new ArrayList<>();
      for(String user : toUserIds.stream().distinct().toList())
      {
         Optional<Boolean> access = user.equals(PersonalRecords.currentUserId()) ? Optional.of(true) : new CollaborationRecordAccessChecker().canRead(user, table, recordId);
         recipients.add(new Recipient(user, new PeopleService().displayName(user), access.orElse(null)));
      }
      return (recipients);
   }

   /*******************************************************************************
    ** Nonreaders get neither the record label nor the sender's untrusted message.
    *******************************************************************************/
   public List<QRecord> share(String table, Serializable recordId, List<String> toUserIds, String message) throws QException
   {
      WorkSupport.text(message, 2000, false);
      List<Recipient> recipients = preview(table, recordId, toUserIds);
      String id = FollowService.canonicalId(table, recordId);
      Link link = link(table, id);
      String from = PersonalRecords.currentUserId();
      String name = new PeopleService().displayName(from);
      List<QRecord> result = new ArrayList<>();
      for(Recipient recipient : recipients)
      {
         QRecord row = CollaborationRecords.insert(CollaborationTables.SHARE, new QRecord().withValue("tableName", table).withValue("recordId", id)
            .withValue("fromUserId", from).withValue("toUserId", recipient.userId()).withValue("message", message)
            .withValue("recipientCouldRead", recipient.canRead()).withValue("sentAt", Instant.now()).withValue("parentDeleted", false));
         if(Boolean.TRUE.equals(recipient.canRead()))
         {
            Notifications.send(new CollaborationNotification(recipient.userId(), name + " shared " + link.label() + " with you", message,
               CollaborationNotification.Category.SHARE, table, id, link.url()).withEvent(row.getValue("id"), from));
            new FollowService().ensureFollower(recipient.userId(), table, id, "SHARED");
         }
         else
         {
            Notifications.sendAccessNotice(new CollaborationNotification(recipient.userId(), name + " wants to share a record in "
               + QContext.getQInstance().getTable(table).getLabel() + " with you", "Request access to open this record.",
               CollaborationNotification.Category.SHARE, null, null, "/app/collaboration_access_request/create?tableName="
                  + TargetResolver.encode(table) + "&recordId=" + TargetResolver.encode(id) + "&shareId=" + row.getValueString("id")).withEvent(row.getValue("id"), from));
         }
         ActivityWriter.write(table, id, "SHARED", "Record shared", CollaborationTables.SHARE, row.getValueString("id"));
         result.add(row);
      }
      return (result);
   }

   /*******************************************************************************
    ** The intended recipient may acknowledge even a share of an invisible parent.
    *******************************************************************************/
   public void markOpened(Integer shareId) throws QException
   {
      String user = PersonalRecords.currentUserId();
      QRecord row = WorkSupport.row(CollaborationTables.SHARE, shareId);
      if(!user.equals(row.getValueString("toUserId")))
      {
         throw (new QNotFoundException("Not found"));
      }
      if(row.getValueInstant("openedAt") == null)
      {
         CollaborationRecords.acknowledgeShare(shareId, Instant.now());
      }
   }

   /*******************************************************************************
    ** Link payload for the later UI API adapter.
    *******************************************************************************/
   public record Link(String url, String label)
   {
   }

   /*******************************************************************************
    ** A nullable Boolean distinguishes unavailable access knowledge from denial.
    *******************************************************************************/
   public record Recipient(String userId, String displayName, Boolean canRead)
   {
   }
}
