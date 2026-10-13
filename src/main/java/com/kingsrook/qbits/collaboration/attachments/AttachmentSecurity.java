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


package com.kingsrook.qbits.collaboration.attachments;

import java.util.List;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissionChecker;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrCountInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;

/*******************************************************************************
 ** RC.5 has no PRE_QUERY customizer. Its permission checker provides the
 ** pre-query restriction for query, count and aggregate; post-query checks
 ** handle parent record locks. Direct writes must use the attachment service.
 *******************************************************************************/
public class AttachmentSecurity implements CustomPermissionChecker
{
   /*******************************************************************************
    ** Browse requires collaboration.view; writes cannot bypass storage lifecycle.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput)
      {
         throw (new QPermissionDeniedException("Use the attachment service or Delete attachments action."));
      }
      new CollaborationPermissionChecker().checkPermissionsThrowing(input, metadata);
      if(input instanceof QueryOrCountInputInterface query)
      {
         restrict(query);
      }
   }

   /*******************************************************************************
    ** AND the restriction outside any user OR clauses; preserve paging and sort.
    ** Counts and aggregates are approximate because parent locks are post-filtered.
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
