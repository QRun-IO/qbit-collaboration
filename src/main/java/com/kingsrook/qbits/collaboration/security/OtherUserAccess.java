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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;

/*******************************************************************************
 ** Stand-in for core RecordAccessCheckerInterface. Unknown is never a grant.
 *******************************************************************************/
public class OtherUserAccess
{
   /*******************************************************************************
    ** The temporary context has no inherited permission bypass or caller transaction.
    *******************************************************************************/
   public Optional<Boolean> canRead(String userId, String table, Serializable recordId) throws QException
   {
      CollaborationQBitConfig config = CollaborationRecords.config();
      if(config.getUserSessionSupplier() == null)
      {
         return (Optional.empty());
      }
      UserSessionSupplier supplier = QCodeLoader.getAdHoc(UserSessionSupplier.class, config.getUserSessionSupplier());
      Optional<QSession> session = supplier.sessionFor(userId);
      if(session.isEmpty())
      {
         return (Optional.empty());
      }
      if(session.get().getUser() == null || !userId.equals(session.get().getUser().getIdReference()))
      {
         return (Optional.empty());
      }
      AtomicReference<Boolean> result = new AtomicReference<>(false);
      QContext.withTemporaryContext(new CapturedContext(QContext.getQInstance(), session.get()), () ->
      {
         try
         {
            ParentRecordAccess.requireVisibleParent(table, recordId);
            result.set(true);
         }
         catch(QNotFoundException expected)
         {
            result.set(false);
         }
      });
      return (Optional.of(result.get()));
   }

   /*******************************************************************************
    ** The record label as the recipient may see it: empty unless a session for the
    ** recipient exists, the record is readable, and every label field is fully
    ** accessible (FieldAccessHelper.isRecordLabelFullAccess, absent in older QQQ
    ** versions, which means no label).
    *******************************************************************************/
   public Optional<String> recordLabel(String userId, String table, Serializable recordId) throws QException
   {
      CollaborationQBitConfig config = CollaborationRecords.config();
      if(config.getUserSessionSupplier() == null || table == null || recordId == null)
      {
         return (Optional.empty());
      }
      Optional<QSession> session = QCodeLoader.getAdHoc(UserSessionSupplier.class, config.getUserSessionSupplier()).sessionFor(userId);
      if(session.isEmpty() || session.get().getUser() == null || !userId.equals(session.get().getUser().getIdReference()))
      {
         return (Optional.empty());
      }
      AtomicReference<String> result = new AtomicReference<>();
      QContext.withTemporaryContext(new CapturedContext(QContext.getQInstance(), session.get()), () ->
      {
         try
         {
            ParentRecordAccess.requireVisibleParent(table, recordId);
            if(labelFullyAccessible(table))
            {
               GetInput input = new GetInput(table).withPrimaryKey(recordId).withShouldGenerateDisplayValues(true);
               QRecord record = new GetAction().executeForRecord(input);
               result.set(record == null ? null : record.getRecordLabel());
            }
         }
         catch(QNotFoundException expected)
         {
            // Not readable by the recipient: no label.
         }
      });
      return (Optional.ofNullable(result.get()).filter(label -> !label.isBlank()));
   }

   /*******************************************************************************
    ** Reflective guard: the helper only exists in QQQ versions with secured data.
    *******************************************************************************/
   private static boolean labelFullyAccessible(String table)
   {
      try
      {
         Class<?> helper = Class.forName("com.kingsrook.qqq.backend.core.actions.permissions.fieldaccess.FieldAccessHelper");
         return (Boolean.TRUE.equals(helper.getMethod("isRecordLabelFullAccess", QTableMetaData.class).invoke(null, QContext.getQInstance().getTable(table))));
      }
      catch(ReflectiveOperationException | LinkageError e)
      {
         return (false);
      }
   }
}
