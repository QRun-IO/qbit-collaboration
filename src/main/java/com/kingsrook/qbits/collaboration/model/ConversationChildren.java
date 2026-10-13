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


package com.kingsrook.qbits.collaboration.model;

import java.util.List;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.ChildRecordListRenderer;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;

/*******************************************************************************
 ** Standard child lists keep revision, mention and follower tables out of navigation.
 *******************************************************************************/
public final class ConversationChildren
{
   /*******************************************************************************
    ** Static metadata helper.
    *******************************************************************************/
   private ConversationChildren()
   {
   }

   /*******************************************************************************
    ** Install after tables have been registered.
    *******************************************************************************/
   public static void install(QInstance instance)
   {
      child(instance, CollaborationTables.COMMENT_REVISION, "Comment History", List.of(new JoinOn("id", "commentId")));
      child(instance, CollaborationTables.MENTION, "Mentions", List.of(new JoinOn("id", "commentId")));
      child(instance, CollaborationTables.FOLLOW, "Followers", List.of(new JoinOn("tableName", "tableName"), new JoinOn("recordId", "recordId")));
   }

   /*******************************************************************************
    ** Child records are managed by services, so the add button is disabled.
    *******************************************************************************/
   private static void child(QInstance instance, String table, String label, List<JoinOn> conditions)
   {
      QJoinMetaData join = new QJoinMetaData().withLeftTable(CollaborationTables.COMMENT).withRightTable(table)
         .withInferredName().withType(JoinType.ONE_TO_MANY).withJoinOns(conditions);
      instance.addJoin(join);
      instance.addWidget(ChildRecordListRenderer.widgetMetaDataBuilder(join).withLabel(label).withCanAddChildRecord(false).getWidgetMetaData());
      instance.getTable(CollaborationTables.COMMENT).withSection(new QFieldSection(table, new QIcon("list"), Tier.T2).withLabel(label).withWidgetName(join.getName()));
   }
}
