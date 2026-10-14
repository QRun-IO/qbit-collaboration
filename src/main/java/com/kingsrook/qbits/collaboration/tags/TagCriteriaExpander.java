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

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.query.CriteriaExpansionContext;
import com.kingsrook.qqq.backend.core.actions.tables.query.SearchTermKind;
import com.kingsrook.qqq.backend.core.actions.tables.query.VirtualFieldCriteriaExpanderInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QSubQuery;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Tag membership uses core subqueries, preserving parent query security and paging.
 *******************************************************************************/
public class TagCriteriaExpander implements VirtualFieldCriteriaExpanderInterface
{
   /*******************************************************************************
    ** Complements exclude records with any matching association.
    *******************************************************************************/
   @Override
   public QQueryFilter expand(QTableMetaData table, QFilterCriteria criteria, CriteriaExpansionContext context) throws QException
   {
      var operator = criteria.getOperator();
      if(operator == QCriteriaOperator.CONTAINS_ALL)
      {
         QQueryFilter all = new QQueryFilter();
         for(Serializable value : criteria.getValues())
         {
            if(value == null)
            {
               throw (new QUserFacingException("Tags cannot contain null values"));
            }
            all.withSubFilter(membership(table, List.of(value), false, false));
         }
         return (all);
      }
      return (switch(operator)
      {
         case EQUALS, IN -> membership(table, criteria.getValues(), false, false);
         case NOT_EQUALS, NOT_IN -> membership(table, criteria.getValues(), true, false);
         case IS_BLANK -> membership(table, List.of(), true, true);
         case IS_NOT_BLANK -> membership(table, List.of(), false, true);
         default -> throw new QUserFacingException("Unsupported Tags operator: " + operator);
      });
   }

   /*******************************************************************************
    ** Numeric values are picker IDs; strings are exact normalized vocabulary names.
    *******************************************************************************/
   private QQueryFilter membership(QTableMetaData table, List<Serializable> values, boolean negate, boolean any) throws QException
   {
      QQueryFilter links = links(table);
      if(!any)
      {
         QQueryFilter tags = TagsService.vocabularyFilter(table.getName()).withCriteria("approved", QCriteriaOperator.EQUALS, true);
         QQueryFilter choices = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR);
         if(values.isEmpty())
         {
            choices.withCriteria("id", QCriteriaOperator.IN, List.of());
         }
         for(Serializable value : values)
         {
            if(value == null)
            {
               throw (new QUserFacingException("Tags cannot contain null values"));
            }
            choices.withCriteria(value instanceof Number ? "id" : "name", QCriteriaOperator.EQUALS,
               value instanceof Number ? value : TagsService.normalize(value.toString()));
         }
         tags.withSubFilter(choices);
         links.withCriteria("tagId", QCriteriaOperator.IN, new QSubQuery(CollaborationTables.TAG, "id", tags));
      }
      return (new QQueryFilter().withCriteria(table.getPrimaryKeyField(), negate ? QCriteriaOperator.NOT_IN : QCriteriaOperator.IN,
         new QSubQuery(CollaborationTables.RECORD_TAG, "recordId", links)));
   }

   /*******************************************************************************
    ** Only live associations on this parent table participate.
    *******************************************************************************/
   private QQueryFilter links(QTableMetaData table)
   {
      return (new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table.getName())
         .withCriteria("parentDeleted", QCriteriaOperator.EQUALS, false));
   }

   /*******************************************************************************
    ** Ordinary search matches labels; hash search matches an exact normalized name.
    *******************************************************************************/
   @Override
   public QQueryFilter expandSearchTerm(QTableMetaData table, String term, SearchTermKind kind, CriteriaExpansionContext context) throws QException
   {
      QQueryFilter tags = TagsService.vocabularyFilter(table.getName()).withCriteria("approved", QCriteriaOperator.EQUALS, true)
         .withCriteria(kind == SearchTermKind.TAG ? "name" : "label", kind == SearchTermKind.TAG ? QCriteriaOperator.EQUALS : QCriteriaOperator.CONTAINS,
            kind == SearchTermKind.TAG ? TagsService.normalize(term) : term);
      return (new QQueryFilter().withCriteria(table.getPrimaryKeyField(), QCriteriaOperator.IN,
         new QSubQuery(CollaborationTables.RECORD_TAG, "recordId", links(table)
            .withCriteria("tagId", QCriteriaOperator.IN, new QSubQuery(CollaborationTables.TAG, "id", tags)))));
   }
}
