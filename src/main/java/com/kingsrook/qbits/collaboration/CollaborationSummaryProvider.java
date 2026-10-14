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

import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeatureSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeatureSummary;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReference;

/*******************************************************************************
 ** Caller-scoped values for the core record features endpoint.
 *******************************************************************************/
public class CollaborationSummaryProvider implements RecordFeatureSummaryProviderInterface
{
   /*******************************************************************************
    ** Core has already checked the parent; omit values for disabled features.
    *******************************************************************************/
   @Override
   public void contribute(RecordReference ref, QRecord parent, RecordFeatureSummary summary) throws QException
   {
      var features = RecordFeaturesResolver.resolve(QContext.getQInstance(), QContext.getQInstance().getTable(ref.tableName()));
      String id = parent.getValueString(QContext.getQInstance().getTable(ref.tableName()).getPrimaryKeyField());
      String user = PersonalRecords.currentUserId();
      if(features.isEnabled(RecordFeature.FAVORITES))
      {
         summary.setIsFavorite(!CollaborationRecords.query(CollaborationTables.FAVORITE, new QQueryFilter()
            .withCriteria("targetType", QCriteriaOperator.EQUALS, "RECORD").withCriteria("targetName", QCriteriaOperator.EQUALS, ref.tableName())
            .withCriteria("recordId", QCriteriaOperator.EQUALS, id).withCriteria("userId", QCriteriaOperator.EQUALS, user)).isEmpty());
      }
      if(features.isEnabled(RecordFeature.FOLLOW))
      {
         var rows = CollaborationRecords.query(CollaborationTables.FOLLOW, CollaborationRecords.parent(ref.tableName(), id));
         summary.setIsFollowing(rows.stream().anyMatch(row -> user.equals(row.getValueString("userId"))));
         summary.getCounts().put("followers", rows.size());
      }
      if(features.isEnabled(RecordFeature.COMMENTS))
      {
         summary.getCounts().put("openComments", CollaborationRecords.query(CollaborationTables.COMMENT, CollaborationRecords.parent(ref.tableName(), id)
            .withCriteria("deletedAt", QCriteriaOperator.IS_BLANK).withCriteria("resolvedAt", QCriteriaOperator.IS_BLANK)).size());
      }
      if(features.isEnabled(RecordFeature.ATTACHMENTS))
      {
         summary.getCounts().put("attachments", CollaborationRecords.query(CollaborationTables.ATTACHMENT, CollaborationRecords.parent(ref.tableName(), id)).size());
      }
      if(features.isEnabled(RecordFeature.TAGS))
      {
         summary.getCounts().put("tags", CollaborationRecords.query(CollaborationTables.RECORD_TAG, CollaborationRecords.parent(ref.tableName(), id)).size());
      }
      if(features.isEnabled(RecordFeature.TASKS))
      {
         summary.getCounts().put("openTasks", CollaborationRecords.query(CollaborationTables.TASK, CollaborationRecords.parent(ref.tableName(), id)
            .withCriteria("status", QCriteriaOperator.EQUALS, "OPEN")).size());
      }
      if(features.isEnabled(RecordFeature.ASSIGNMENT))
      {
         var rows = CollaborationRecords.query(CollaborationTables.RECORD_ASSIGNMENT, CollaborationRecords.parent(ref.tableName(), id));
         if(!rows.isEmpty())
         {
            summary.withOwner(rows.getFirst().getValueString("ownerUserId")).withAssignee(rows.getFirst().getValueString("assigneeUserId"));
         }
         if(features.getOwnership() != null)
         {
            var ownership = features.getOwnership();
            if(ownership.getOwnerFieldName() != null)
            {
               summary.setOwner(parent.getValueString(ownership.getOwnerFieldName()));
            }
            if(ownership.getAssigneeFieldName() != null)
            {
               summary.setAssignee(parent.getValueString(ownership.getAssigneeFieldName()));
            }
         }
      }
   }
}
