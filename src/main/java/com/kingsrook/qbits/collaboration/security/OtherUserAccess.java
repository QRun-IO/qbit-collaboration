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
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
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
}
