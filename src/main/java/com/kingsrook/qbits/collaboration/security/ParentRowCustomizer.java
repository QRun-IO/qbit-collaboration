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

import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Parent read locks also protect direct GetAction and unpinned table browsing.
 ** Until core mirrors locks, aggregate counts are approximate and pages may be short.
 *******************************************************************************/
public class ParentRowCustomizer implements TableCustomizerInterface
{
   /*******************************************************************************
    ** Children store the parent's reference so the same filter protects them.
    *******************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
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
            QRecord row = new QRecord(record);
            if(row.getValue("deletedAt") != null)
            {
               row.setValue("body", null);
            }
            visible.add(row);
         }
         catch(QNotFoundException expected)
         {
            if(input instanceof GetInput)
            {
               throw (expected);
            }
         }
      }
      return (visible);
   }
}
