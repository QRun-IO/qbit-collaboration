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


package com.kingsrook.qbits.collaboration.tags;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Materialized tag filter logic independent of the unreleased core hooks in #1140.
 ** Every operator returns matching caller-visible parent ids, including complements.
 *******************************************************************************/
public class TagFilterTranslator
{
   public static final int MAX_RECORD_IDS = 10000;

   /*******************************************************************************
    ** IN means any tag, CONTAINS_ALL means every requested tag, NOT_IN means none.
    ** Empty IN matches none; empty CONTAINS_ALL and NOT_IN match all visible rows.
    *******************************************************************************/
   public List<String> recordIds(String table, String op, Collection<? extends Serializable> tags) throws QException
   {
      TagsService.requireTable(table);
      if(!List.of("IN", "CONTAINS_ALL", "NOT_IN", "IS_BLANK", "IS_NOT_BLANK").contains(op))
      {
         throw (new QException("Unsupported tag operator: " + op));
      }
      Set<Integer> requested = new HashSet<>();
      boolean missing = false;
      if(!op.equals("IS_BLANK") && !op.equals("IS_NOT_BLANK"))
      {
         for(Serializable tag : tags == null ? List.<Serializable>of() : tags)
         {
            QQueryFilter filter = TagsService.vocabularyFilter(table).withCriteria("approved", QCriteriaOperator.EQUALS, true);
            filter.withCriteria(tag instanceof Number ? "id" : "name", QCriteriaOperator.EQUALS,
               tag instanceof Number ? tag : TagsService.normalize(tag == null ? null : tag.toString()));
            List<QRecord> matches = CollaborationRecords.query(CollaborationTables.TAG, filter.withOrderBy(new QFilterOrderBy("id")));
            if(matches.isEmpty())
            {
               missing = true;
            }
            else
            {
               QRecord match = matches.stream().filter(row -> row.getValue("tableName") == null).findFirst().orElse(matches.getFirst());
               requested.add(match.getValueInteger("id"));
            }
         }
      }
      Map<String, Set<Integer>> assignments = new HashMap<>();
      for(QRecord row : CollaborationRecords.query(CollaborationTables.RECORD_TAG, new QQueryFilter()
         .withCriteria("tableName", QCriteriaOperator.EQUALS, table).withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false)))
      {
         assignments.computeIfAbsent(row.getValueString("recordId"), ignored -> new HashSet<>()).add(row.getValueInteger("tagId"));
      }
      List<String> result = new ArrayList<>();
      String key = QContext.getQInstance().getTable(table).getPrimaryKeyField();
      for(int skip = 0; ; skip += 500)
      {
         QueryInput input = new QueryInput(table).withFilter(new QQueryFilter().withOrderBy(new QFilterOrderBy(key)).withSkip(skip).withLimit(500))
            .withTransaction(QContext.getQBackendTransaction());
         input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         List<QRecord> parents = new QueryAction().execute(input).getRecords();
         for(QRecord parent : parents)
         {
            String id = parent.getValueString(key);
            Set<Integer> applied = assignments.getOrDefault(id, Set.of());
            boolean any = requested.stream().anyMatch(applied::contains);
            boolean match = switch(op)
            {
               case "IN" -> any;
               case "NOT_IN" -> !any;
               case "CONTAINS_ALL" -> !missing && applied.containsAll(requested);
               case "IS_BLANK" -> applied.isEmpty();
               default -> !applied.isEmpty();
            };
            if(match)
            {
               result.add(id);
               if(result.size() > MAX_RECORD_IDS)
               {
                  throw (new QException("Tag filter exceeds 10,000 matching record ids."));
               }
            }
         }
         if(parents.size() < 500)
         {
            return (result);
         }
      }
   }
}
