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

import java.io.Serializable;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.exceptions.QValueException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;

/*******************************************************************************
 ** Stand-in for QQQ core RecordReferenceSecurityHelper (QRun-IO/qqq collaboration phase a); replace when released
 *******************************************************************************/
public final class ParentRecordAccess
{
   /*******************************************************************************
    ** Static operations only.
    *******************************************************************************/
   private ParentRecordAccess()
   {
   }

   /*******************************************************************************
    ** Exclude collaboration tables to avoid recursive references.
    *******************************************************************************/
   public static boolean canReadTable(String tableName)
   {
      QTableMetaData table = tableName == null ? null : QContext.getQInstance().getTable(tableName);
      return (table != null && table.getPrimaryKeyField() != null && !tableName.startsWith(CollaborationQBitConfig.TABLE_NAME_PREFIX)
         && PermissionsHelper.hasTablePermission(new AbstractActionInput(), tableName, TablePermissionSubType.READ));
   }

   /*******************************************************************************
    ** Get as the caller, enforcing table permissions and the parent's read locks.
    *******************************************************************************/
   public static QRecord requireVisibleParent(String tableName, Serializable recordId) throws QException
   {
      if(!canReadTable(tableName) || recordId == null)
      {
         throw (new QNotFoundException("Not found"));
      }
      QTableMetaData table = QContext.getQInstance().getTable(tableName);
      try
      {
         Serializable key = ValueUtils.getValueAsFieldType(table.getField(table.getPrimaryKeyField()).getType(), recordId);
         GetInput input = new GetInput(tableName).withPrimaryKey(key);
         input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         QRecord parent = new GetAction().execute(input).getRecord();
         if(parent == null)
         {
            throw (new QNotFoundException("Not found"));
         }
         return (parent);
      }
      catch(QPermissionDeniedException | QValueException expected)
      {
         throw (new QNotFoundException("Not found"));
      }
   }

   /*******************************************************************************
    ** Temporary approximation: EDIT plus a visible parent, not WRITE lock checks.
    *******************************************************************************/
   public static void requireFeature(String tableName, Serializable recordId, String feature) throws QException
   {
      requireVisibleParent(tableName, recordId);
      QTableMetaData table = QContext.getQInstance().getTable(tableName);
      String base = table.getPermissionRules() == null ? null : table.getPermissionRules().getPermissionBaseName();
      base = base == null || base.isBlank() ? tableName : base;
      if(!QContext.getQSession().hasPermission(base + "." + feature)
         && !PermissionsHelper.hasTablePermission(new AbstractActionInput(), tableName, TablePermissionSubType.EDIT))
      {
         throw (new QPermissionDeniedException("This needs the " + base + "." + feature + " permission or table EDIT."));
      }
   }
}
