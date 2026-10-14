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

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.security.CollaborationRecordAccessChecker;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Following requires parent visibility, without a separate feature permission.
 *******************************************************************************/
public class FollowService
{
   /*******************************************************************************
    ** Nullable preferences retain existing settings on repeat calls.
    *******************************************************************************/
   public FollowEntry follow(String table, Serializable recordId, Boolean notifyOnChange, Boolean notifyOnComment) throws QException
   {
      new PeopleService().touch();
      String id = canonicalId(table, recordId);
      QRecord row = ensure(PersonalRecords.currentUserId(), table, id, "MANUAL");
      QRecord update = new QRecord().withValue("id", row.getValue("id"));
      if(notifyOnChange != null)
      {
         update.withValue("notifyOnChange", notifyOnChange);
      }
      if(notifyOnComment != null)
      {
         update.withValue("notifyOnComment", notifyOnComment);
      }
      CollaborationRecords.update(CollaborationTables.FOLLOW, update);
      return (state(table, id).orElseThrow());
   }

   /*******************************************************************************
    ** Remove only the caller's subscription and queued digest.
    *******************************************************************************/
   public void unfollow(String table, Serializable recordId) throws QException
   {
      new PeopleService().touch();
      String id = canonicalId(table, recordId);
      for(String target : List.of(CollaborationTables.FOLLOW, CollaborationTables.FOLLOW_DIGEST))
      {
         for(QRecord row : CollaborationRecords.query(target, mine(table, id)))
         {
            CollaborationRecords.delete(target, row.getValue("id"));
         }
      }
   }

   /*******************************************************************************
    ** Current-user state; absent means not followed.
    *******************************************************************************/
   public Optional<FollowEntry> state(String table, Serializable recordId) throws QException
   {
      new PeopleService().touch();
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.FOLLOW, mine(table, canonicalId(table, recordId)));
      return (rows.stream().findFirst().map(FollowService::entry));
   }

   /*******************************************************************************
    ** Parent readers may see its followers independently of admin table browsing.
    *******************************************************************************/
   public List<FollowEntry> followers(String table, Serializable recordId) throws QException
   {
      new PeopleService().touch();
      return (CollaborationRecords.query(CollaborationTables.FOLLOW, CollaborationRecords.parent(table, canonicalId(table, recordId)))
         .stream().map(FollowService::entry).toList());
   }

   /*******************************************************************************
    ** Internal automatic follow; unknown access never grants a subscription.
    *******************************************************************************/
   public void ensureFollower(String userId, String table, Serializable recordId, String reason) throws QException
   {
      new PeopleService().touch();
      String id = canonicalId(table, recordId);
      if(!userId.equals(PersonalRecords.currentUserId()) && !new CollaborationRecordAccessChecker().canRead(userId, table, id).orElse(false))
      {
         return;
      }
      ensure(userId, table, id, reason);
   }

   /*******************************************************************************
    ** Existing preferences always win over automatic follow defaults.
    *******************************************************************************/
   private QRecord ensure(String user, String table, String id, String reason) throws QException
   {
      if(!List.of("MANUAL", "AUTHOR", "COMMENTED", "MENTIONED", "ASSIGNED", "SHARED").contains(reason))
      {
         throw (new QException("Unknown follow reason"));
      }
      QQueryFilter filter = CollaborationRecords.parent(table, id).withCriteria("userId", QCriteriaOperator.EQUALS, user);
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.FOLLOW, filter);
      if(!rows.isEmpty())
      {
         return (rows.getFirst());
      }
      QRecord row;
      try
      {
         row = CollaborationRecords.insert(CollaborationTables.FOLLOW, new QRecord().withValue("tableName", table).withValue("recordId", id)
            .withValue("userId", user).withValue("reason", reason).withValue("notifyOnChange", reason.equals("MANUAL") || reason.equals("ASSIGNED"))
            .withValue("notifyOnComment", true).withValue("parentDeleted", false));
      }
      catch(QException e)
      {
         rows = CollaborationRecords.query(CollaborationTables.FOLLOW, filter);
         if(rows.isEmpty())
         {
            throw (e);
         }
         return (rows.getFirst());
      }
      ActivityWriter.write(table, id, "FOLLOWED", "Record followed", CollaborationTables.FOLLOW, row.getValueString("id"));
      return (row);
   }

   /*******************************************************************************
    ** Normalize numeric aliases to the parent's actual primary key.
    *******************************************************************************/
   public static String canonicalId(String table, Serializable id) throws QException
   {
      return (ParentRecordAccess.requireVisibleParent(table, id).getValueString(QContext.getQInstance().getTable(table).getPrimaryKeyField()));
   }

   /*******************************************************************************
    ** Ownership filter always accompanies parent selection.
    *******************************************************************************/
   private QQueryFilter mine(String table, String id) throws QPermissionDeniedException
   {
      return (CollaborationRecords.parent(table, id).withCriteria("userId", QCriteriaOperator.EQUALS, PersonalRecords.currentUserId()));
   }

   /*******************************************************************************
    ** Immutable public view.
    *******************************************************************************/
   private static FollowEntry entry(QRecord row)
   {
      return (new FollowEntry(row.getValueInteger("id"), row.getValueString("userId"), row.getValueString("reason"),
         row.getValueBoolean("notifyOnChange"), row.getValueBoolean("notifyOnComment"), row.getValueInstant("mutedUntil")));
   }
}
