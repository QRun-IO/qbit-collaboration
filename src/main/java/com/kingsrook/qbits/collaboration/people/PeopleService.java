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


package com.kingsrook.qbits.collaboration.people;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.CriteriaOption;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Lazy, service-managed directory. Persisted timestamps make throttling survive
 ** service recreation and avoid a cache claiming success after rollback.
 *******************************************************************************/
public class PeopleService
{
   private final Clock clock;

   /*******************************************************************************
    ** Production clock.
    *******************************************************************************/
   public PeopleService()
   {
      this(Clock.systemUTC());
   }

   /*******************************************************************************
    ** Deterministic clock for directory maintenance tests.
    *******************************************************************************/
   public PeopleService(Clock clock)
   {
      this.clock = clock;
   }

   /*******************************************************************************
    ** Upsert only the caller, at most once every ten minutes.
    *******************************************************************************/
   public void touch() throws QException
   {
      String user = PersonalRecords.currentUserId();
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.USER, new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, user));
      Instant now = clock.instant();
      if(!rows.isEmpty() && rows.getFirst().getValueInstant("lastSeenAt") != null
         && rows.getFirst().getValueInstant("lastSeenAt").plusSeconds(600).isAfter(now))
      {
         return;
      }
      String name = QContext.getQSession().getUser().getFullName();
      QRecord row = new QRecord().withValue("userId", user).withValue("displayName", name == null || name.isBlank() ? user : name).withValue("lastSeenAt", now);
      if(rows.isEmpty())
      {
         try
         {
            CollaborationRecords.insert(CollaborationTables.USER, row.withValue("active", true));
         }
         catch(QException e)
         {
            if(CollaborationRecords.query(CollaborationTables.USER, new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, user)).isEmpty())
            {
               throw (e);
            }
            // A concurrent first call populated the same directory identity.
         }
      }
      else
      {
         CollaborationRecords.update(CollaborationTables.USER, row.withValue("id", rows.getFirst().getValue("id")));
      }
   }

   /*******************************************************************************
    ** Directory search requires a signed-in caller and excludes inactive people.
    *******************************************************************************/
   public List<PersonEntry> search(String q, Integer limit) throws QException
   {
      return (search(q, limit, Set.of()));
   }

   /*******************************************************************************
    ** Rank participants before limiting, with bounded backend queries for both groups.
    *******************************************************************************/
   public List<PersonEntry> search(String q, Integer limit, Set<String> preferredUserIds) throws QException
   {
      PersonalRecords.currentUserId();
      if(limit == null || limit < 1 || limit > 50)
      {
         throw (new QException("Limit must be between 1 and 50"));
      }
      List<QRecord> rows = new ArrayList<>();
      if(!preferredUserIds.isEmpty())
      {
         rows.addAll(CollaborationRecords.query(CollaborationTables.USER, searchFilter(q, limit)
            .withCriteria("userId", QCriteriaOperator.IN, new ArrayList<>(preferredUserIds))));
      }
      if(rows.size() < limit)
      {
         QQueryFilter other = searchFilter(q, limit - rows.size());
         if(!preferredUserIds.isEmpty())
         {
            other.withCriteria("userId", QCriteriaOperator.NOT_IN, new ArrayList<>(preferredUserIds));
         }
         rows.addAll(CollaborationRecords.query(CollaborationTables.USER, other));
      }
      return (rows.stream().map(row -> new PersonEntry(row.getValueString("userId"), row.getValueString("displayName"),
         row.getValueString("email"), row.getValueInstant("lastSeenAt"))).toList());
   }

   /*******************************************************************************
    ** Keep active and group restrictions outside the display-name/email OR.
    *******************************************************************************/
   private QQueryFilter searchFilter(String q, Integer limit)
   {
      QQueryFilter filter = new QQueryFilter().withCriteria("active", QCriteriaOperator.EQUALS, true)
         .withOrderBy(new QFilterOrderBy("displayName")).withOrderBy(new QFilterOrderBy("userId")).withLimit(limit);
      if(q != null && !q.isBlank())
      {
         filter.withSubFilter(new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR)
            .withCriteria(new QFilterCriteria("displayName", QCriteriaOperator.CONTAINS, q).withOption(CriteriaOption.CASE_INSENSITIVE))
            .withCriteria(new QFilterCriteria("email", QCriteriaOperator.CONTAINS, q).withOption(CriteriaOption.CASE_INSENSITIVE)));
      }
      return (filter);
   }

   /*******************************************************************************
    ** Resolve display names without publishing the raw directory table.
    *******************************************************************************/
   public String displayName(String userId) throws QException
   {
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.USER, new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, userId));
      return (rows.isEmpty() || rows.getFirst().getValueString("displayName") == null ? userId : rows.getFirst().getValueString("displayName"));
   }
}
