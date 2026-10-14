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


package com.kingsrook.qbits.collaboration.attachments;

import java.util.List;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Package-private raw reads for services and trusted parent cleanup. Callers
 ** must check the parent before exposing any values or performing user writes.
 *******************************************************************************/
final class AttachmentRecords
{
   /*******************************************************************************
    ** Static persistence helpers only.
    *******************************************************************************/
   private AttachmentRecords()
   {
   }

   /*******************************************************************************
    ** Use a per-input clone so shared metadata and session remain unchanged.
    *******************************************************************************/
   static List<QRecord> query(QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(CollaborationTables.ATTACHMENT).withFilter(filter).withShouldFetchHeavyFields(true).withTransaction(QContext.getQBackendTransaction());
      input.setTableMetaData(storageTable());
      input.withPermissionBypass("Attachment service checks parent access; cleanup is a trusted after-delete callback.");
      return (new QueryAction().execute(input).getRecords());
   }

   /*******************************************************************************
    ** Internal persistence must not generate URLs or filter a deleted parent.
    ** Clone only this action's metadata; retain auditing and backend behavior.
    *******************************************************************************/
   static QTableMetaData storageTable()
   {
      return (QContext.getQInstance().getTable(CollaborationTables.ATTACHMENT).clone().withCustomizers(null));
   }

   /*******************************************************************************
    ** QQQ validation errors may be returned on rows rather than thrown.
    *******************************************************************************/
   static void checkErrors(List<QRecord> records) throws QException
   {
      if(records != null)
      {
         for(QRecord record : records)
         {
            if(record.getErrors() != null && !record.getErrors().isEmpty())
            {
               throw (new QException("Could not save attachment: " + record.getErrors()));
            }
         }
      }
   }
}
