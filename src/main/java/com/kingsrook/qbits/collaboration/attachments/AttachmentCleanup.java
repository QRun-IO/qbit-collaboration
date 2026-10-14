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

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Trusted after-commit callback. Wire to core RecordReferenceCleanupInterface
 ** when released. Never expose as a user endpoint: the parent is already gone.
 *******************************************************************************/
public class AttachmentCleanup
{
   private final AttachmentsService service;

   /*******************************************************************************
    ** Production cleanup using the installed qbit and object storage.
    *******************************************************************************/
   public AttachmentCleanup()
   {
      this(new AttachmentsService());
   }

   /*******************************************************************************
    ** Use the same storage boundary as normal deletion.
    *******************************************************************************/
   public AttachmentCleanup(AttachmentsService service)
   {
      this.service = service;
   }

   /*******************************************************************************
    ** Repeated calls are safe; provider failures leave the row for a later retry.
    *******************************************************************************/
   public void onParentsDeleted(String table, List<? extends Serializable> ids) throws QException
   {
      if(ids.isEmpty())
      {
         return;
      }
      new com.kingsrook.qbits.collaboration.WorkCleanup().onParentsDeleted(table, ids);
      new com.kingsrook.qbits.collaboration.tags.TagsService().onParentsDeleted(table, ids);
      while(true)
      {
         List<QRecord> rows = com.kingsrook.qbits.collaboration.CollaborationRecords.cleanupQuery(com.kingsrook.qbits.collaboration.model.CollaborationTables.ATTACHMENT, new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table)
            .withCriteria("recordId", QCriteriaOperator.IN, ids.stream().map(Object::toString).toList()).withLimit(200));
         if(rows.isEmpty())
         {
            return;
         }
         for(QRecord row : rows)
         {
            service.deleteStoredRow(row, true);
         }
      }
   }
}
