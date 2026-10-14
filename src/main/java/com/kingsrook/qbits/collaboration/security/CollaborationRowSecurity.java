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

import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissionChecker;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrCountInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Standard table/API access is read-only; service calls authorize separately.
 *******************************************************************************/
public class CollaborationRowSecurity implements CustomPermissionChecker
{
   /*******************************************************************************
    ** Apply parent-table restrictions before count, query and aggregate execution.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput)
      {
         throw (new QPermissionDeniedException("Use the collaboration service actions."));
      }
      new CollaborationPermissionChecker().checkPermissionsThrowing(input, metadata);
      if(metadata instanceof QTableMetaData table
         && (table.getName().equals(CollaborationTables.RECORD_ASSIGNMENT) || table.getName().equals(CollaborationTables.FOLLOW) || table.getName().equals(CollaborationTables.ACTIVITY) || table.getName().equals(CollaborationTables.FOLLOW_DIGEST))
         && !QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN))
      {
         throw (new QPermissionDeniedException("This needs collaboration.admin."));
      }
      if(input instanceof QueryOrCountInputInterface query)
      {
         ParentTableRestriction.restrict(query);
      }
   }
}
