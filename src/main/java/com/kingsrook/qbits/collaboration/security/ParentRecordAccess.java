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
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Thin service boundary over core parent visibility and record capabilities.
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
      try
      {
         var table = QContext.getQInstance().getTable(tableName);
         Serializable key = com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsFieldType(table.getField(table.getPrimaryKeyField()).getType(), recordId);
         QRecord parent = com.kingsrook.qqq.backend.core.actions.collaboration.RecordReferenceSecurityHelper.visibleParent(tableName, key, QContext.getQBackendTransaction());
         if(parent == null)
         {
            throw (new QNotFoundException("Not found"));
         }
         return (parent);
      }
      catch(com.kingsrook.qqq.backend.core.exceptions.QValueException e)
      {
         throw (new QNotFoundException("Not found"));
      }
   }

   /*******************************************************************************
    ** Core record capability checks include parent WRITE locks for implied grants.
    *******************************************************************************/
   public static void requireFeature(String tableName, Serializable recordId, String feature) throws QException
   {
      QRecord parent = requireVisibleParent(tableName, recordId);
      if(!com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturePermissions.checkRecordCapability(
         QContext.getQInstance().getTable(tableName), feature(feature), parent, QContext.getQBackendTransaction()))
      {
         throw (new QPermissionDeniedException("Permission denied for " + feature + "."));
      }
   }

   /*******************************************************************************
    ** Authorize vocabulary creation without requiring an arbitrary parent record.
    *******************************************************************************/
   public static void requireTableFeature(String tableName, String feature) throws QException
   {
      if(!canReadTable(tableName))
      {
         throw (new QNotFoundException("Not found"));
      }
      com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturePermissions.requireTableCapability(QContext.getQInstance().getTable(tableName), feature(feature));
   }

   /*******************************************************************************
    ** Map service operation suffixes to the core enum.
    *******************************************************************************/
   private static com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature feature(String suffix)
   {
      return (java.util.Arrays.stream(com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature.values())
         .filter(value -> suffix.equals(value.getPermissionSuffix())).findFirst().orElseThrow());
   }
}
