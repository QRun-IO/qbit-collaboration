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

import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Filter invisible parents before generating ephemeral preview/download URLs.
 ** Counts are approximate and pages can be short until core mirrors locks.
 *******************************************************************************/
public class AttachmentPreviewCustomizer implements TableCustomizerInterface
{
   private static final QLogger LOG = QLogger.getLogger(AttachmentPreviewCustomizer.class);

   /*******************************************************************************
    ** GetAction expects one result: throw Not found rather than return an empty list.
    *******************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
   {
      return (filter(records, input instanceof GetInput, new AttachmentsService()));
   }

   /*******************************************************************************
    ** Keep filtering every row even when the preview generation cap is reached.
    *******************************************************************************/
   List<QRecord> filter(List<QRecord> records, boolean isGet, AttachmentsService service) throws QException
   {
      List<QRecord> visible = new ArrayList<>();
      for(QRecord record : records)
      {
         try
         {
            if(Boolean.TRUE.equals(record.getValueBoolean("parentDeleted")))
            {
               throw (new QNotFoundException("Not found"));
            }
            ParentRecordAccess.requireVisibleParent(record.getValueString("tableName"), record.getValueString("recordId"));
         }
         catch(QNotFoundException expected)
         {
            if(isGet)
            {
               throw (expected);
            }
            continue;
         }
         // Never retain URLs from a previous read of a memory-backend record.
         QRecord row = new QRecord(record);
         row.setValue("previewUrl", null);
         row.setValue("downloadUrl", null);
         row.withDisplayValue("downloadUrl", null).withDisplayValue("downloadUrl:downloadUrlDynamic", null);
         visible.add(row);
         if(visible.size() <= 200)
         {
            try
            {
               row.setValue("previewUrl", service.url(row, true).toString());
               String downloadUrl = service.url(row, false).toString();
               row.setValue("downloadUrl", downloadUrl);
               row.withDisplayValue("downloadUrl", row.getValueString("fileName"))
                  .withDisplayValue("downloadUrl:downloadUrlDynamic", downloadUrl);
            }
            catch(RuntimeException e)
            {
               LOG.debug("Could not make an attachment preview URL", e);
            }
         }
      }
      return (visible);
   }
}
