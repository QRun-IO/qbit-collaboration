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


package com.kingsrook.qbits.collaboration.tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.query.VirtualFieldValueProviderInterface;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;

/*******************************************************************************
 ** Return approved tag labels for parents selected by the enclosing query.
 *******************************************************************************/
public class TagValueProvider implements VirtualFieldValueProviderInterface
{
   /*******************************************************************************
    ** Batch both assignments and vocabulary; never recursively load parent records.
    *******************************************************************************/
   @Override
   public void fill(QTableMetaData table, String fieldName, List<QRecord> records, QueryInput origin) throws QException
   {
      for(List<QRecord> page : CollectionUtils.getPages(records, 500))
      {
         List<String> ids = page.stream().map(record -> String.valueOf(record.getValue(table.getPrimaryKeyField()))).distinct().toList();
         QTableMetaData metadata = QContext.getQInstance().getTable(CollaborationTables.RECORD_TAG).clone();
         metadata.withCustomizers(null);
         metadata.getSupplementalMetaData().remove(RecordReferenceTableMetaData.NAME);
         QueryInput input = new QueryInput(CollaborationTables.RECORD_TAG).withTransaction(origin.getTransaction()).withFilter(new QQueryFilter()
            .withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName()).withCriteria("recordId", QCriteriaOperator.IN, ids)
            .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false));
         input.setTableMetaData(metadata);
         input.withPermissionBypass("Tag provider reads only parents selected by the enclosing query.");
         List<QRecord> assignments = new QueryAction().execute(input).getRecords();
         Map<Integer, String> labels = new HashMap<>();
         List<Integer> tagIds = assignments.stream().map(row -> row.getValueInteger("tagId")).distinct().toList();
         for(List<Integer> tags : CollectionUtils.getPages(tagIds, 500))
         {
            for(QRecord tag : CollaborationRecords.query(CollaborationTables.TAG, TagsService.vocabularyFilter(table.getName())
               .withCriteria("approved", QCriteriaOperator.EQUALS, true).withCriteria("id", QCriteriaOperator.IN, tags), origin.getTransaction()))
            {
               labels.put(tag.getValueInteger("id"), tag.getValueString("label"));
            }
         }
         Map<String, java.util.SortedSet<String>> byParent = new HashMap<>();
         for(QRecord assignment : assignments)
         {
            String label = labels.get(assignment.getValueInteger("tagId"));
            if(label != null)
            {
               byParent.computeIfAbsent(assignment.getValueString("recordId"), key -> new java.util.TreeSet<>()).add(label);
            }
         }
         for(QRecord record : page)
         {
            var values = byParent.get(String.valueOf(record.getValue(table.getPrimaryKeyField())));
            record.setValue(fieldName, values == null ? null : String.join(", ", values));
         }
      }
   }
}
