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
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QSupplementalTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Optional owner policy for parents whose dynamic visibility cannot be expressed
 ** by aggregate SQL. Count/aggregate require one currently authorized parent.
 ** Ordinary collaboration policies retain their existing count behavior.
 *******************************************************************************/
public final class AttachmentAggregateGuard extends AttachmentSecurity
{
   private static final String PINNED_POLICY = "collaboration.attachmentPinnedAggregateParent";

   /*******************************************************************************
    ** Hide unscoped list totals in both instance and table metadata. Native count
    ** remains available to callers supplying the required authorized parent pin.
    *******************************************************************************/
   public static QTableMetaData configure(QTableMetaData table, CollaborationQBitConfig config)
   {
      if(policy(config).requiresPinnedAggregateParent())
      {
         table.withSupplementalMetaData(new PinnedParentPolicy());
      }
      if(table.getSupplementalMetaData(PINNED_POLICY) != null)
      {
         table.withoutCapabilities(Capability.TABLE_COUNT, Capability.QUERY_STATS);
      }
      return (table);
   }

   /*******************************************************************************
    ** Preserve ordinary view/write rules, then narrow aggregate inputs only.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      super.checkPermissionsThrowing(input, metadata);
      if(!(input instanceof CountInput) && !(input instanceof AggregateInput))
      {
         return;
      }
      try
      {
         CollaborationQBitConfig config = (CollaborationQBitConfig) QContext.getQInstance().getQBits().values().stream()
            .filter(qbit -> qbit.getConfig() instanceof CollaborationQBitConfig).findFirst().orElseThrow().getConfig();
         QTableMetaData tableMetadata = QContext.getQInstance().getTable(CollaborationTables.ATTACHMENT);
         if(tableMetadata.getSupplementalMetaData(PINNED_POLICY) == null && !policy(config).requiresPinnedAggregateParent() && !hasRetainedOriginal())
         {
            return;
         }
         QQueryFilter filter = input instanceof CountInput count ? count.getFilter() : ((AggregateInput) input).getFilter();
         var joins = input instanceof CountInput count ? count.getQueryJoins() : ((AggregateInput) input).getQueryJoins();
         if(joins != null && !joins.isEmpty() || filter == null || filter.getBooleanOperator() != QQueryFilter.BooleanOperator.AND)
         {
            throw (new QException("A single pinned attachment parent is required for counts and aggregates."));
         }
         rejectSetOperations(filter, 0, new int[]{0});
         Serializable table = literal(filter, "tableName");
         Serializable record = literal(filter, "recordId");
         if(!(table instanceof String name) || name.isBlank() || record == null)
         {
            throw (new QException("A single pinned attachment parent is required for counts and aggregates."));
         }
         ParentRecordAccess.requireVisibleParent(name, record);
      }
      catch(QException | RuntimeException denied)
      {
         throw (new QPermissionDeniedException("Counts and aggregates require one currently readable attachment parent."));
      }
   }

   /*******************************************************************************
    ** A literal top-level AND equality constrains every row, even when other
    ** independent subfilters contain OR. Conflicting or computed pins are refused.
    *******************************************************************************/
   private static Serializable literal(QQueryFilter filter, String field) throws QException
   {
      Serializable result = null;
      for(var criterion : filter.getCriteria() == null ? List.<com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria>of() : filter.getCriteria())
      {
         if(field.equals(criterion.getFieldName()) && criterion.getOperator() == QCriteriaOperator.EQUALS)
         {
            if(criterion.getValues() == null || criterion.getValues().size() != 1)
            {
               throw (new QException("An exact parent equality is required."));
            }
            Serializable value = criterion.getValues().getFirst();
            if(!(value instanceof String) && !(value instanceof Number) || result != null && !result.equals(value))
            {
               throw (new QException("Computed or conflicting parent equality is not supported."));
            }
            result = value;
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** Set-operation filters may combine independent branches; never assume their
    ** SQL obeys an ordinary root AND pin. Bound traversal before SQL execution.
    *******************************************************************************/
   private static void rejectSetOperations(QQueryFilter filter, int depth, int[] visited) throws QException
   {
      if(depth > 64 || ++visited[0] > 4096 || filter.getSubFilterSetOperator() != null)
      {
         throw (new QException("Unsupported aggregate filter shape."));
      }
      for(QQueryFilter child : filter.getSubFilters() == null ? List.<QQueryFilter>of() : filter.getSubFilters())
      {
         rejectSetOperations(child, depth + 1, visited);
      }
   }

   /*******************************************************************************
    ** Capability policy is configuration-only and must not depend on a request.
    *******************************************************************************/
   private static AttachmentLifecyclePolicy policy(CollaborationQBitConfig config)
   {
      return (java.util.Objects.requireNonNull(QCodeLoader.getAdHoc(AttachmentLifecyclePolicy.class, config.getAttachmentLifecycle()), "Attachment lifecycle policy is unavailable."));
   }

   /*******************************************************************************
    ** A fresh instance may have lost its former lifecycle configuration. Inspect
    ** only existence through private owner metadata, never return retained rows.
    ** Failure propagates to aggregate denial; there is no permissive fallback.
    *******************************************************************************/
   private static boolean hasRetainedOriginal() throws QException
   {
      var input = new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput(CollaborationTables.ATTACHMENT)
         .withFilter(new QQueryFilter().withCriteria("retained", QCriteriaOperator.EQUALS, true).withLimit(1))
         .withShouldFetchHeavyFields(false).withShouldGenerateDisplayValues(false);
      input.setTableMetaData(com.kingsrook.qbits.collaboration.CollaborationRecords.cleanupMetadata(CollaborationTables.ATTACHMENT));
      input.withPermissionBypass("Owner aggregate guard checks only whether retained originals require pinned-parent authorization; no row data is exposed.");
      return (!new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(input).getRecords().isEmpty());
   }

   /*******************************************************************************
    ** An installed capability restriction survives subsequent lifecycle changes
    ** and metadata clones. It carries no request or authorization state.
    *******************************************************************************/
   public static final class PinnedParentPolicy extends QSupplementalTableMetaData
   {
      /*******************************************************************************
       ** Stable owner metadata identity.
       *******************************************************************************/
      @Override
      public String getType()
      {
         return (PINNED_POLICY);
      }

      /*******************************************************************************
       ** The marker is immutable and has no nested mutable state.
       *******************************************************************************/
      @Override
      protected QSupplementalTableMetaData finishClone(QSupplementalTableMetaData clone)
      {
         return (clone);
      }
   }
}
