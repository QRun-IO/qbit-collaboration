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

import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.query.CriteriaExpansionContext;
import com.kingsrook.qqq.backend.core.actions.tables.query.VirtualFieldCriteriaExpanderInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QSubQuery;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Read-only EAV filters retain the parent's normal query permissions and paging.
 *******************************************************************************/
public class FieldCriteriaExpander implements VirtualFieldCriteriaExpanderInterface
{
   /*******************************************************************************
    ** Missing EAV rows count as blank; negative comparisons complement membership.
    *******************************************************************************/
   @Override
   public QQueryFilter expand(QTableMetaData table, QFilterCriteria criteria, CriteriaExpansionContext context) throws QException
   {
      String name = criteria.getFieldName().substring(3);
      var definitions = CollaborationRecords.query(CollaborationTables.FIELD_DEFINITION, new QQueryFilter()
         .withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName()).withCriteria("name", QCriteriaOperator.EQUALS, name)
         .withCriteria("status", QCriteriaOperator.EQUALS, "ACTIVE"));
      if(definitions.size() != 1)
      {
         throw (new QException("Custom field is unavailable"));
      }
      var operator = criteria.getOperator();
      boolean negate = operator == QCriteriaOperator.IS_BLANK || operator == QCriteriaOperator.NOT_EQUALS || operator == QCriteriaOperator.NOT_IN;
      var positive = switch(operator)
      {
         case IS_BLANK -> QCriteriaOperator.IS_NOT_BLANK;
         case NOT_EQUALS -> QCriteriaOperator.EQUALS;
         case NOT_IN -> QCriteriaOperator.IN;
         default -> operator;
      };
      String column = valueColumn(definitions.getFirst().getValueString("type"));
      var valueType = context.instance().getTable(CollaborationTables.FIELD_VALUE).getField(column).getType();
      var typedValues = criteria.getValues().stream().map(value -> com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsFieldType(valueType, value)).toList();
      QQueryFilter values = new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName())
         .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false).withCriteria("fieldName", QCriteriaOperator.EQUALS, name)
         .withCriteria(new QFilterCriteria(column, positive, typedValues));
      return (new QQueryFilter().withCriteria(table.getPrimaryKeyField(), negate ? QCriteriaOperator.NOT_IN : QCriteriaOperator.IN,
         new QSubQuery(CollaborationTables.FIELD_VALUE, "recordId", values)));
   }

   /*******************************************************************************
    ** Shared typed column mapping for filtering and value counts.
    *******************************************************************************/
   public static String valueColumn(String type)
   {
      return (switch(type)
      {
         case "INTEGER", "DECIMAL" -> "valueNumber";
         case "DATE" -> "valueDate";
         case "DATE_TIME" -> "valueDateTime";
         case "BOOLEAN" -> "valueBoolean";
         default -> "valueText";
      });
   }

   /*******************************************************************************
    ** Core's search callback has no field argument, so combine searchable EAV text.
    *******************************************************************************/
   @Override
   public QQueryFilter expandSearchTerm(QTableMetaData table, String term, com.kingsrook.qqq.backend.core.actions.tables.query.SearchTermKind kind, CriteriaExpansionContext context) throws QException
   {
      QQueryFilter result = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR);
      for(var definition : CollaborationRecords.query(CollaborationTables.FIELD_DEFINITION, new QQueryFilter()
         .withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName()).withCriteria("status", QCriteriaOperator.EQUALS, "ACTIVE")
         .withCriteria("searchable", QCriteriaOperator.EQUALS, true)))
      {
         if("valueText".equals(valueColumn(definition.getValueString("type"))))
         {
            result.withSubFilter(expand(table, new QFilterCriteria("cf_" + definition.getValueString("name"), QCriteriaOperator.CONTAINS, term), context));
         }
      }
      if(result.getSubFilters() == null || result.getSubFilters().isEmpty())
      {
         result.withCriteria(table.getPrimaryKeyField(), QCriteriaOperator.IN, java.util.List.of());
      }
      return (result);
   }
}
