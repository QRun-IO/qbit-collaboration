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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFollowersProviderInterface;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.FollowEvent;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFollower;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReference;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;

/*******************************************************************************
 ** Core event subscribers; consumers must check each recipient's record access.
 *******************************************************************************/
public class CollaborationFollowersProvider implements RecordFollowersProviderInterface
{
   /*******************************************************************************
    ** Read only this parent's subscriptions as the system, independent of the caller.
    *******************************************************************************/
   @Override
   public List<RecordFollower> getFollowers(QInstance instance, RecordReference reference, FollowEvent event) throws QException
   {
      List<RecordFollower> followers = new ArrayList<>();
      QContext.withTemporaryContext(new CapturedContext(instance, new QSystemUserSession(instance)), () ->
      {
         Instant now = Instant.now();
         String preference = event == FollowEvent.COMMENT ? "notifyOnComment" : "notifyOnChange";
         for(QRecord row : CollaborationRecords.query(CollaborationTables.FOLLOW,
            CollaborationRecords.parent(reference.tableName(), reference.primaryKey().toString())))
         {
            if(Boolean.TRUE.equals(row.getValueBoolean(preference))
               && (row.getValueInstant("mutedUntil") == null || !row.getValueInstant("mutedUntil").isAfter(now)))
            {
               followers.add(new RecordFollower().withUserId(row.getValueString("userId"))
                  .withNotifyOnChange(row.getValueBoolean("notifyOnChange")).withNotifyOnComment(row.getValueBoolean("notifyOnComment"))
                  .withReason(row.getValueString("reason")));
            }
         }
      });
      return (followers);
   }
}
