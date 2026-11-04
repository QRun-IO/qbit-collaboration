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


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.util.List;
import java.util.Set;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Private manifest fields cannot become filter or sorting oracles. This
 ** table-local restriction composes with the host's existing field access policy.
 *******************************************************************************/
public final class UploadJournalReadPolicy
{
   private static final Set<String> PRIVATE = Set.of("bucketName", "storageKey", "manifestHash", "leaseToken");

   /*******************************************************************************
    ** Utility namespace.
    *******************************************************************************/
   private UploadJournalReadPolicy()
   {
   }

   /*******************************************************************************
    ** Native read criteria are checked before SQL, including unique-key Get.
    ** Joins are not an journal browsing contract and are rejected conservatively.
    *******************************************************************************/
   public static void check(AbstractActionInput input, QTableMetaData table) throws QPermissionDeniedException
   {
      if(!UploadJournalTable.NAME.equals(table.getName()))
      {
         return;
      }
      if(input instanceof QueryInput query)
      {
         if(query.getQueryJoins() != null && !query.getQueryJoins().isEmpty())
         {
            deny();
         }
         filter(query.getFilter(), table, 0, new int[]{0});
         if(query.getFieldNamesToInclude() != null)
         {
            for(String name : query.getFieldNamesToInclude())
            {
               field(name, table);
            }
         }
      }
      else if(input instanceof GetInput get)
      {
         if(get.getQueryJoins() != null && !get.getQueryJoins().isEmpty())
         {
            deny();
         }
         if(get.getUniqueKey() != null)
         {
            for(String name : get.getUniqueKey().keySet())
            {
               field(name, table);
            }
         }
      }
   }

   /*******************************************************************************
    ** Bound traversal and accept only literal criteria on public direct fields.
    *******************************************************************************/
   private static void filter(QQueryFilter filter, QTableMetaData table, int depth, int[] visited) throws QPermissionDeniedException
   {
      if(filter == null)
      {
         return;
      }
      if(depth > 64 || ++visited[0] > 4096)
      {
         deny();
      }
      for(var criterion : filter.getCriteria() == null ? List.<com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria>of() : filter.getCriteria())
      {
         field(criterion.getFieldName(), table);
         if(criterion.getOtherFieldName() != null)
         {
            field(criterion.getOtherFieldName(), table);
         }
         if(criterion.getFieldFunction() != null)
         {
            deny();
         }
         for(Object value : criterion.getValues() == null ? List.of() : criterion.getValues())
         {
            if(value instanceof com.kingsrook.qqq.backend.core.model.actions.tables.query.expressions.CurrentUser
               && "createdByUserId".equals(criterion.getFieldName())
               && criterion.getOperator() == com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.EQUALS
               && criterion.getValues().size() == 1)
            {
               continue;
            }
            if(value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)
               && !(value instanceof java.time.temporal.TemporalAccessor) && !(value instanceof java.util.Date))
            {
               deny();
            }
         }
      }
      for(var order : filter.getOrderBys() == null ? List.<com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy>of() : filter.getOrderBys())
      {
         field(order.getFieldName(), table);
      }
      for(QQueryFilter child : filter.getSubFilters() == null ? List.<QQueryFilter>of() : filter.getSubFilters())
      {
         filter(child, table, depth + 1, visited);
      }
   }

   /*******************************************************************************
    ** A public direct field cannot refer to a private projection through an alias.
    *******************************************************************************/
   private static void field(String name, QTableMetaData table) throws QPermissionDeniedException
   {
      if(name == null || PRIVATE.contains(name) || table.getFields().get(name) == null || Boolean.TRUE.equals(table.getFields().get(name).getIsHidden()))
      {
         deny();
      }
   }

   /*******************************************************************************
    ** Uniform denial never echoes security values or a caller's probing predicate.
    *******************************************************************************/
   private static void deny() throws QPermissionDeniedException
   {
      throw (new QPermissionDeniedException("Upload journal queries support public direct fields only."));
   }
}
