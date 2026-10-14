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


package com.kingsrook.qbits.collaboration.sharing;

import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;

/*******************************************************************************
 ** Requester/admin visibility is independent of parent visibility and existence.
 *******************************************************************************/
public class AccessRequestSecurity implements CustomPermissionChecker, TableCustomizerInterface
{
   /*******************************************************************************
    ** Service-only writes prevent forging requester IDs and outcomes.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      new CollaborationSecurity().checkPermissionsThrowing(input, metadata);
      if(input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput)
      {
         throw (new QPermissionDeniedException("Use the access request service actions."));
      }
   }

   /*******************************************************************************
    ** Only metadata labels are resolved; never read the referenced parent.
    *******************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
   {
      for(QRecord row : records)
      {
         var table = QContext.getQInstance().getTable(row.getValueString("tableName"));
         row.setDisplayValue("tableName", table == null ? row.getValueString("tableName") : table.getLabel());
      }
      return (records);
   }
}
