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


package com.kingsrook.qbits.collaboration.timeline;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.RecordCursor;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qqq.backend.core.actions.metadata.personalization.TableMetaDataPersonalizerAction;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.metadata.personalization.TableMetaDataPersonalizerInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QInputSource;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.audits.AuditsMetaDataProvider;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Merge core audit and collaboration activity using each source's read permissions.
 ** Audit details use the same personalized host fields as core's audit screen.
 *******************************************************************************/
public class TimelineService
{
   /*******************************************************************************
    ** Types accepts AUDIT/ACTIVITY or individual collaboration activity type names.
    *******************************************************************************/
   public TimelinePage timeline(String table, Serializable recordId, String beforeCursor, Integer limit, Set<String> types) throws QException
   {
      String id = FollowService.canonicalId(table, recordId);
      if(limit == null || limit < 1 || limit > 50)
      {
         throw (new QException("Limit must be between 1 and 50"));
      }
      RecordCursor before = beforeCursor == null ? null : RecordCursor.decode(beforeCursor);
      if(before != null && before.kind().equals("COMMENT"))
      {
         throw (new QException("Invalid timeline cursor"));
      }
      List<TimelineItem> items = new ArrayList<>();
      boolean all = types == null || types.isEmpty();
      if(all || types.contains("ACTIVITY") || types.stream().anyMatch(type -> !type.equals("AUDIT")))
      {
         QQueryFilter filter = CollaborationRecords.parent(table, id).withOrderBy(new QFilterOrderBy("at", false))
            .withOrderBy(new QFilterOrderBy("id", false)).withLimit(limit + 1);
         if(before != null)
         {
            filter.withSubFilter(before.beforeFilter("at", "ACTIVITY"));
         }
         if(!all && !types.contains("ACTIVITY"))
         {
            filter.withCriteria("type", QCriteriaOperator.IN, new ArrayList<>(types));
         }
         for(QRecord row : CollaborationRecords.query(CollaborationTables.ACTIVITY, filter))
         {
            items.add(new TimelineItem("ACTIVITY", row.getValueLong("id"), row.getValueInstant("at"), row.getValueString("actorUserId"),
               new PeopleService().displayName(row.getValueString("actorUserId")), row.getValueString("summary"), List.of(), row.getValueString("refTableName"), row.getValueString("refId")));
         }
      }
      if(all || types.contains("AUDIT"))
      {
         items.addAll(audits(table, id, before, limit + 1));
      }
      items.sort(Comparator.comparing(TimelineItem::at).thenComparing(TimelineItem::kind).thenComparing(TimelineItem::id).reversed());
      List<TimelineItem> page = items.stream().limit(limit).toList();
      String next = items.size() > limit ? key(page.getLast()).encode() : null;
      return (new TimelinePage(page, next));
   }

   /*******************************************************************************
    ** Missing audit installation or denied audit permission quietly omits the stream.
    ** Read audit headers separately so a many-detail join never truncates an event.
    *******************************************************************************/
   private List<TimelineItem> audits(String table, String id, RecordCursor before, Integer limit) throws QException
   {
      if(QContext.getQInstance().getTable(AuditsMetaDataProvider.TABLE_NAME_AUDIT) == null)
      {
         return (List.of());
      }
      try
      {
         List<QRecord> tables = query(AuditsMetaDataProvider.TABLE_NAME_AUDIT_TABLE, new QQueryFilter().withCriteria("name", QCriteriaOperator.EQUALS, table));
         if(tables.isEmpty())
         {
            return (List.of());
         }
         QQueryFilter filter = new QQueryFilter().withCriteria("auditTableId", QCriteriaOperator.EQUALS, tables.getFirst().getValue("id"))
            .withCriteria("recordId", QCriteriaOperator.EQUALS, id).withOrderBy(new QFilterOrderBy("timestamp", false))
            .withOrderBy(new QFilterOrderBy("id", false)).withLimit(limit);
         if(before != null)
         {
            filter.withSubFilter(before.beforeFilter("timestamp", "AUDIT"));
         }
         List<QRecord> headers = query(AuditsMetaDataProvider.TABLE_NAME_AUDIT, filter);
         if(headers.isEmpty())
         {
            return (List.of());
         }
         TableMetaDataPersonalizerInput personalization = new TableMetaDataPersonalizerInput().withTableName(table).withInputSource(QInputSource.USER);
         personalization.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         QTableMetaData personalized = TableMetaDataPersonalizerAction.execute(personalization);
         Map<Long, List<TimelineDetail>> details = new HashMap<>();
         for(QRecord detail : query(AuditsMetaDataProvider.TABLE_NAME_AUDIT_DETAIL, new QQueryFilter()
            .withCriteria("auditId", QCriteriaOperator.IN, headers.stream().map(row -> row.getValue("id")).toList())
            .withOrderBy(new QFilterOrderBy("id"))))
         {
            String field = detail.getValueString("fieldName");
            if(field == null || field.isBlank() || (personalized != null && personalized.getFields().containsKey(field)))
            {
               details.computeIfAbsent(detail.getValueLong("auditId"), ignored -> new ArrayList<>())
                  .add(new TimelineDetail(field, detail.getValueString("oldValue"), detail.getValueString("newValue"), detail.getValueString("message")));
            }
         }
         Map<Integer, String> actors = new HashMap<>();
         for(QRecord actor : query(AuditsMetaDataProvider.TABLE_NAME_AUDIT_USER, new QQueryFilter().withCriteria("id", QCriteriaOperator.IN,
            headers.stream().map(row -> row.getValue("auditUserId")).filter(java.util.Objects::nonNull).distinct().toList())))
         {
            actors.put(actor.getValueInteger("id"), actor.getValueString("name"));
         }
         List<TimelineItem> result = new ArrayList<>();
         for(QRecord row : headers)
         {
            result.add(new TimelineItem("AUDIT", row.getValueLong("id"), row.getValueInstant("timestamp"), null, actors.get(row.getValueInteger("auditUserId")),
               row.getValueString("message"), List.copyOf(details.getOrDefault(row.getValueLong("id"), List.of())), AuditsMetaDataProvider.TABLE_NAME_AUDIT, row.getValueString("id")));
         }
         return (result);
      }
      catch(QPermissionDeniedException expected)
      {
         return (List.of());
      }
   }

   /*******************************************************************************
    ** Explicit enforcement applies to every audit table, independent of host defaults.
    *******************************************************************************/
   private List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(table).withFilter(filter);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (new QueryAction().execute(input).getRecords());
   }

   /*******************************************************************************
    ** Stable descending key, including equal timestamps across both streams.
    *******************************************************************************/
   private RecordCursor key(TimelineItem item)
   {
      return (new RecordCursor(item.at(), item.kind(), item.id()));
   }
}
