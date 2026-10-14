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

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qbits.collaboration.attachments.AttachmentCleanup;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordReferenceCleanupInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;

/*******************************************************************************
 ** Core invokes cleanup once the parent deletion commits.
 *******************************************************************************/
public class CollaborationCleanup implements RecordReferenceCleanupInterface
{
   /*******************************************************************************
    ** Existing attachment cleanup includes tag counts, shares, tasks and assignments.
    *******************************************************************************/
   @Override
   public void onParentsDeleted(String tableName, List<Serializable> primaryKeys) throws QException
   {
      new AttachmentCleanup().onParentsDeleted(tableName, primaryKeys);
      for(String table : List.of(CollaborationTables.FIELD_VALUE, CollaborationTables.COMMENT_REVISION, CollaborationTables.MENTION, CollaborationTables.COMMENT,
         CollaborationTables.FOLLOW_DIGEST, CollaborationTables.FOLLOW, CollaborationTables.ACTIVITY))
      {
         while(true)
         {
            var rows = CollaborationRecords.cleanupQuery(table, new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, tableName)
               .withCriteria("recordId", QCriteriaOperator.IN, primaryKeys.stream().map(Object::toString).toList()).withLimit(200));
            if(rows.isEmpty())
            {
               break;
            }
            for(var row : rows)
            {
               CollaborationRecords.cleanupDelete(table, row.getValue("id"));
            }
         }
      }
   }
}
