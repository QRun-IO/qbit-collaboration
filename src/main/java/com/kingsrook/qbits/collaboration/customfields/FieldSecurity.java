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


package com.kingsrook.qbits.collaboration.customfields;

import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;

/*******************************************************************************
 ** Definitions are readable with view and writable by field managers or admins.
 *******************************************************************************/
public class FieldSecurity implements CustomPermissionChecker
{
   /*******************************************************************************
    ** Processes and writes require management authority.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput || metadata instanceof QProcessMetaData)
      {
         requireManage();
         return;
      }
      var session = QContext.getQSession();
      if(session == null || (!session.hasPermission(CollaborationPermissions.VIEW) && !session.hasPermission(CollaborationPermissions.ADMIN)))
      {
         throw (new QPermissionDeniedException("This needs collaboration.view."));
      }
   }

   /*******************************************************************************
    ** Services recheck permission, including at the load step.
    *******************************************************************************/
   public static void requireManage() throws QPermissionDeniedException
   {
      var session = QContext.getQSession();
      if(!(session instanceof QSystemUserSession) && (session == null || (!session.hasPermission(CollaborationPermissions.FIELDS_MANAGE) && !session.hasPermission(CollaborationPermissions.ADMIN))))
      {
         throw (new QPermissionDeniedException("This needs collaboration.fields.manage."));
      }
   }
}
