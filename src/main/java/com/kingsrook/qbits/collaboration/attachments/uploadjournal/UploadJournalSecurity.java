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


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissionChecker;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;


/*******************************************************************************
 ** Owner journal visibility follows its original actor and its exact attachment's
 ** current parent after a transfer. Immutable manifest source values never change.
 *******************************************************************************/
public class UploadJournalSecurity implements CustomPermissionChecker, TableCustomizerInterface
{
   /*******************************************************************************
    ** No direct writes or counts bypass per-row dynamic parent authorization.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(metadata instanceof com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData table)
      {
         UploadJournalReadPolicy.check(input, table);
      }
      if(input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput || input instanceof CountInput || input instanceof AggregateInput)
      {
         throw (new QPermissionDeniedException("The upload journal is maintained by the attachment service; unscoped totals are unavailable."));
      }
      new CollaborationPermissionChecker().checkPermissionsThrowing(input, metadata);
   }

   /*******************************************************************************
    ** Read authoritative owner identity even if the client selected a projection.
    ** Internal manifest columns are not QQQ fields and cannot be returned here.
    *******************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
   {
      List<QRecord> visible = new ArrayList<>();
      for(QRecord row : records)
      {
         if(row.getValueInteger("id") == null)
         {
            continue;
         }
         try
         {
            List<QRecord> persisted = CollaborationRecords.cleanupQuery(UploadJournalTable.NAME,
               new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, row.getValueInteger("id")).withLimit(1));
            if(persisted.size() != 1 || !PersonalRecords.currentUserId().equals(persisted.getFirst().getValueString("createdByUserId")))
            {
               continue;
            }
            QRecord source = persisted.getFirst();
            if(source.getValueInteger("attachmentId") != null)
            {
               List<QRecord> attachment = CollaborationRecords.cleanupQuery(CollaborationTables.ATTACHMENT,
                  new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, source.getValueInteger("attachmentId")).withLimit(1));
               if(attachment.size() != 1 || !source.getValueString("sha256").equals(attachment.getFirst().getValueString("sha256"))
                  || !source.getValueLong("sizeBytes").equals(attachment.getFirst().getValueLong("sizeBytes"))
                  || !source.getValueString("createdByUserId").equals(attachment.getFirst().getValueString("createdByUserId")))
               {
                  continue;
               }
               source = attachment.getFirst();
            }
            ParentRecordAccess.requireVisibleParent(source.getValueString("tableName"), source.getValueString("recordId"));
            visible.add(row);
         }
         catch(QNotFoundException denied)
         {
            // Invisible and absent owners have identical native query behavior.
         }
      }
      return (visible);
   }
}
