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

import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrCountInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;

/*******************************************************************************
 ** Vocabulary reads support pickers without collaboration.view. Management actions
 ** require the explicit manage permission; direct writes must use the services.
 *******************************************************************************/
public class TagSecurity implements CustomPermissionChecker
{
   /*******************************************************************************
    ** Restrict before query/count/aggregate and protect direct gets as well.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(metadata instanceof QProcessMetaData)
      {
         TagsService.requireManage();
         return;
      }
      if(input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput)
      {
         throw (new QPermissionDeniedException("Use the tag service actions."));
      }
      if(TagsService.canManage())
      {
         return;
      }
      String user = CollaborationSecurity.userId(QContext.getQSession());
      if(user == null)
      {
         throw (new QPermissionDeniedException("Sign in to view tags."));
      }
      if(input instanceof QueryOrCountInputInterface query)
      {
         QQueryFilter original = query.getFilter();
         QQueryFilter filter = new QQueryFilter().withCriteria("mergedIntoTagId", QCriteriaOperator.IS_BLANK)
            .withSubFilter(new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR).withCriteria("tableName", QCriteriaOperator.IS_BLANK)
               .withCriteria("tableName", QCriteriaOperator.IN, QContext.getQInstance().getTables().keySet().stream().filter(ParentRecordAccess::canReadTable).toList()))
            .withSubFilter(new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR).withCriteria("approved", QCriteriaOperator.EQUALS, true)
               .withCriteria("createdByUserId", QCriteriaOperator.EQUALS, user));
         if(original != null)
         {
            filter.withSubFilter(original).withLimit(original.getLimit()).withSkip(original.getSkip());
            filter.setOrderBys(original.getOrderBys());
         }
         query.setFilter(filter);
      }
      if(input instanceof GetInput get)
      {
         try
         {
            QRecord row = TagsService.requireTag(get.getPrimaryKey());
            if((row.getValue("tableName") != null && !ParentRecordAccess.canReadTable(row.getValueString("tableName")))
               || (!Boolean.TRUE.equals(row.getValueBoolean("approved")) && !user.equals(row.getValueString("createdByUserId"))))
            {
               throw (new QPermissionDeniedException("Tag not available."));
            }
         }
         catch(QException expected)
         {
            throw (new QPermissionDeniedException("Tag not available."));
         }
      }
   }
}
