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
 ** Core other-user access checker. Unknown is never a grant.
 *******************************************************************************/
public class CollaborationRecordAccessChecker implements com.kingsrook.qqq.backend.core.actions.collaboration.RecordAccessCheckerInterface
{
   /*******************************************************************************
    ** Core reference-based access contract.
    *******************************************************************************/
   @Override
   public Optional<Boolean> canRead(String userId, com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReference ref) throws QException
   {
      return (canRead(userId, ref.tableName(), ref.primaryKey()));
   }

   /*******************************************************************************
    ** The temporary context has no inherited permission bypass or caller transaction.
    *******************************************************************************/
   public Optional<Boolean> canRead(String userId, String table, Serializable recordId) throws QException
   {
      CollaborationQBitConfig config = CollaborationRecords.config();
      if(config.getUserSessionProvider() == null)
      {
         return (Optional.empty());
      }
      com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface supplier = QCodeLoader.getAdHoc(com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface.class, config.getUserSessionProvider());
      Optional<QSession> session = supplier.sessionForUser(userId);
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
