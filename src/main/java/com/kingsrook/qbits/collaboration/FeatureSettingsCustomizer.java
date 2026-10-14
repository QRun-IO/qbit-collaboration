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


package com.kingsrook.qbits.collaboration;

import java.util.List;
import java.util.Optional;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Publish committed policy changes through the core overlay.
 *******************************************************************************/
public class FeatureSettingsCustomizer implements TableCustomizerInterface
{
   /*******************************************************************************
    ** Refresh after successful insert.
    *******************************************************************************/
   @Override
   public List<QRecord> postInsert(InsertInput input, List<QRecord> records) throws QException
   {
      refresh(input.getTransaction());
      return (records);
   }

   /*******************************************************************************
    ** Refresh after successful update.
    *******************************************************************************/
   @Override
   public List<QRecord> postUpdate(UpdateInput input, List<QRecord> records, Optional<List<QRecord>> oldRecords) throws QException
   {
      refresh(input.getTransaction());
      return (records);
   }

   /*******************************************************************************
    ** Refresh after successful delete.
    *******************************************************************************/
   @Override
   public List<QRecord> postDelete(DeleteInput input, List<QRecord> records) throws QException
   {
      refresh(input.getTransaction());
      return (records);
   }

   /*******************************************************************************
    ** Never retain the completed transaction in a refresh callback.
    *******************************************************************************/
   private void refresh(QBackendTransaction transaction) throws QException
   {
      var instance = QContext.getQInstance();
      if(transaction == null)
      {
         RuntimeMetaDataOverlay.refresh(instance);
      }
      else
      {
         var context = new CapturedContext(instance, QContext.getQSession());
         transaction.addAfterCommitCallback(() ->
         {
            try
            {
               QContext.withTemporaryContext(context, () -> RuntimeMetaDataOverlay.refresh(instance));
            }
            catch(QException e)
            {
               throw (new IllegalStateException("Could not refresh collaboration policy", e));
            }
         });
      }
   }
}
