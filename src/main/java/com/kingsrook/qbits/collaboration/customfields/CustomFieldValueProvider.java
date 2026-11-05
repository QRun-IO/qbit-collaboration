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
import com.kingsrook.qqq.backend.core.utils.ValueUtils;

/*******************************************************************************
 ** Supply EAV values for the parent records selected by the core query pipeline.
 ** Do not re-read parents here: that would recursively execute this provider.
 *******************************************************************************/
public class CustomFieldValueProvider implements VirtualFieldValueProviderInterface
{
   /*******************************************************************************
    ** Bound each lookup and retain the caller's transaction through service storage.
    *******************************************************************************/
   @Override
   public void fill(QTableMetaData table, String fieldName, List<QRecord> records, QueryInput input) throws QException
   {
      String name = fieldName.substring(3);
      var definitions = CollaborationRecords.query(CollaborationTables.FIELD_DEFINITION, new QQueryFilter()
         .withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName()).withCriteria("name", QCriteriaOperator.EQUALS, name)
         .withCriteria("status", QCriteriaOperator.EQUALS, "ACTIVE"), input.getTransaction());
      if(definitions.size() != 1)
      {
         throw (new QException("Custom field is unavailable"));
      }
      String column = FieldCriteriaExpander.valueColumn(definitions.getFirst().getValueString("type"));
      for(List<QRecord> page : CollectionUtils.getPages(records, 500))
      {
         List<String> ids = page.stream().map(record -> String.valueOf(record.getValue(table.getPrimaryKeyField()))).distinct().toList();
         Map<String, QRecord> values = new HashMap<>();
         for(QRecord value : valuesForSelectedParents(new QQueryFilter()
            .withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName()).withCriteria("fieldName", QCriteriaOperator.EQUALS, name)
            .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false).withCriteria("recordId", QCriteriaOperator.IN, ids), input))
         {
            if(values.put(value.getValueString("recordId"), value) != null)
            {
               throw (new QException("Duplicate custom field values"));
            }
         }
         for(QRecord record : page)
         {
            QRecord value = values.get(String.valueOf(record.getValue(table.getPrimaryKeyField())));
            record.setValue(fieldName, value == null ? null : ValueUtils.getValueAsFieldType(table.getVirtualField(fieldName).getType(), value.getValue(column)));
         }
      }
   }

   /*******************************************************************************
    ** The enclosing query already selected these parents under its access policy.
    ** Remove only recursive parent verification on a private child metadata clone;
    ** preserve child locks and never install this metadata on the instance.
    *******************************************************************************/
   private List<QRecord> valuesForSelectedParents(QQueryFilter filter, QueryInput origin) throws QException
   {
      QTableMetaData metadata = QContext.getQInstance().getTable(CollaborationTables.FIELD_VALUE).clone();
      metadata.withCustomizers(null);
      metadata.getSupplementalMetaData().remove(RecordReferenceTableMetaData.NAME);
      QueryInput input = new QueryInput(CollaborationTables.FIELD_VALUE).withFilter(filter)
         .withShouldFetchHeavyFields(true).withTransaction(origin.getTransaction());
      input.setTableMetaData(metadata);
      input.withPermissionBypass("EAV provider reads only parents selected by the enclosing query.");
      return (new QueryAction().execute(input).getRecords());
   }

}
