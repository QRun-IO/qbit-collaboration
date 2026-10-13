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


package com.kingsrook.qbits.collaboration.security;

import java.util.List;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrCountInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;

/*******************************************************************************
 ** Shared pre-query parent-table restriction. Record locks require post-filtering.
 *******************************************************************************/
public final class ParentTableRestriction
{
   /*******************************************************************************
    ** Static helper.
    *******************************************************************************/
   private ParentTableRestriction()
   {
   }

   /*******************************************************************************
    ** AND outside caller OR clauses while retaining paging and sort order.
    *******************************************************************************/
   public static void restrict(QueryOrCountInputInterface input)
   {
      List<String> readable = QContext.getQInstance().getTables().keySet().stream().filter(ParentRecordAccess::canReadTable).toList();
      QQueryFilter original = input.getFilter();
      QQueryFilter filter = new QQueryFilter().withCriteria("tableName", QCriteriaOperator.IN, readable)
         .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false);
      if(original != null)
      {
         filter.withSubFilter(original).withLimit(original.getLimit()).withSkip(original.getSkip());
         filter.setOrderBys(original.getOrderBys());
      }
      input.setFilter(filter);
   }
}
