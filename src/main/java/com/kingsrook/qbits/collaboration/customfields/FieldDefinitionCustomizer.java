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


package com.kingsrook.qbits.collaboration.customfields;

import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.collaboration.FeatureSettingsCustomizer;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Direct table writes enforce the same designer rules; post hooks refresh metadata.
 *******************************************************************************/
public class FieldDefinitionCustomizer extends FeatureSettingsCustomizer
{
   /*******************************************************************************
    ** New definitions always begin as drafts with server-managed lifecycle fields.
    *******************************************************************************/
   @Override
   public List<QRecord> preInsert(InsertInput input, List<QRecord> records, boolean preview) throws QException
   {
      FieldSecurity.requireManage();
      for(QRecord row : records)
      {
         row.withValue("status", "DRAFT").withValue("indexState", "NONE").withValue("storage", null)
            .withValue("indexDdl", null).withValue("statusMessage", null).withValue("createdByUserId", PersonalRecords.currentUserId());
         FieldDefinitions.validate(row, null);
      }
      return (records);
   }

   /*******************************************************************************
    ** Merge partial updates before validation and protect derived state from clients.
    *******************************************************************************/
   @Override
   public List<QRecord> preUpdate(UpdateInput input, List<QRecord> records, boolean preview, Optional<List<QRecord>> oldRecords) throws QException
   {
      FieldSecurity.requireManage();
      for(QRecord row : records)
      {
         QRecord old = FieldDefinitions.get(row.getValue("id"));
         QRecord merged = new QRecord(old);
         merged.getValues().putAll(row.getValues());
         for(String field : List.of("status", "indexState", "storage", "indexDdl", "statusMessage", "createdByUserId"))
         {
            if(row.getValues().containsKey(field) && !java.util.Objects.equals(row.getValue(field), old.getValue(field)))
            {
               throw (new QException("Use the field actions to change " + field));
            }
         }
         FieldDefinitions.validate(merged, old);
         if(!java.util.Objects.equals(merged.getValue("statusMessage"), old.getValue("statusMessage")))
         {
            row.setValue("statusMessage", merged.getValue("statusMessage"));
         }
      }
      return (records);
   }

   /*******************************************************************************
    ** Published field values must remain addressable; archive instead of deleting.
    *******************************************************************************/
   @Override
   public List<QRecord> preDelete(DeleteInput input, List<QRecord> records, boolean preview) throws QException
   {
      FieldSecurity.requireManage();
      for(QRecord row : records)
      {
         if(!"DRAFT".equals(FieldDefinitions.get(row.getValue("id")).getValueString("status")))
         {
            throw (new QException("Only draft definitions can be deleted; archive published fields."));
         }
      }
      return (records);
   }
}
