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


package com.kingsrook.qbits.collaboration.recents;


import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.TargetResolver;
import com.kingsrook.qbits.collaboration.favorites.FavoriteEntry;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Current-user recent targets, with persisted one-minute throttling. No process
 ** cache or unreleased core contracts are needed. Lists drop unavailable targets.
 *******************************************************************************/
public final class RecentsService
{
   static Clock clock = Clock.systemUTC();



   /*******************************************************************************
    ** Static service.
    *******************************************************************************/
   private RecentsService()
   {
   }



   /*******************************************************************************
    ** Validate visibility, then insert or refresh at most once per minute.
    *******************************************************************************/
   public static void recordView(String targetType, String targetName, Serializable recordId) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      String userId = PersonalRecords.currentUserId();
      FavoriteEntry target = TargetResolver.require(targetType, targetName, recordId);
      QQueryFilter filter = PersonalRecords.targetFilter(targetType, targetName, target.recordId());
      List<QRecord> existing = PersonalRecords.query(CollaborationTables.RECENT, filter);
      Instant now = clock.instant();
      if(existing.isEmpty())
      {
         QRecord row = new QRecord().withValue("userId", userId).withValue("targetType", targetType).withValue("targetName", targetName)
            .withValue("recordId", target.recordId()).withValue("label", target.label()).withValue("lastViewedAt", now).withValue("viewCount", 1);
         try
         {
            PersonalRecords.insert(CollaborationTables.RECENT, row);
            return;
         }
         catch(QException e)
         {
            existing = PersonalRecords.query(CollaborationTables.RECENT, filter);
            if(existing.isEmpty())
            {
               throw (e);
            }
            // A concurrent first view inserted this target. Apply normal throttling.
         }
      }
      QRecord row = existing.getFirst();
      if(row.getValueInstant("lastViewedAt").plusSeconds(60).isAfter(now))
      {
         return;
      }
      PersonalRecords.update(CollaborationTables.RECENT, List.of(new QRecord().withValue("id", row.getValue("id"))
         .withValue("lastViewedAt", now).withValue("viewCount", Math.addExact(row.getValueInteger("viewCount"), 1)).withValue("label", target.label())));
   }



   /*******************************************************************************
    ** Return at most 50 visible entries, newest first. Page until enough visible
    ** targets are found, rather than letting unavailable rows consume the limit.
    *******************************************************************************/
   public static List<FavoriteEntry> list(Integer limit) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      if(limit == null || limit < 1 || limit > 50)
      {
         throw (new QException("Limit must be between 1 and 50"));
      }
      List<FavoriteEntry> result = new ArrayList<>();
      int skip = 0;
      while(result.size() < limit)
      {
         List<QRecord> rows = PersonalRecords.query(CollaborationTables.RECENT, PersonalRecords.ownFilter()
            .withOrderBy(new QFilterOrderBy("lastViewedAt", false)).withOrderBy(new QFilterOrderBy("id", false)).withSkip(skip).withLimit(50));
         for(FavoriteEntry entry : TargetResolver.refresh(rows))
         {
            if(entry.available() && result.size() < limit)
            {
               result.add(entry);
            }
         }
         if(rows.size() < 50)
         {
            break;
         }
         skip += rows.size();
      }
      return (result);
   }
}
