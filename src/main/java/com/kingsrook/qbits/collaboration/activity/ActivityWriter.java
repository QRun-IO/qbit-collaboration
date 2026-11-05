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


package com.kingsrook.qbits.collaboration.activity;

import java.time.Instant;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;

/*******************************************************************************
 ** Internal activity writer; the calling service has already checked the parent.
 *******************************************************************************/
public final class ActivityWriter
{
   /*******************************************************************************
    ** Static service boundary.
    *******************************************************************************/
   private ActivityWriter()
   {
   }

   /*******************************************************************************
    ** Store references, never copies of comment bodies or attachment contents.
    *******************************************************************************/
   public static void write(String table, String recordId, String type, String summary, String refTable, String refId) throws QException
   {
      CollaborationRecords.insertActivity(new QRecord().withValue("tableName", table).withValue("recordId", recordId)
         .withValue("at", Instant.now()).withValue("actorUserId", CollaborationSecurity.userId(QContext.getQSession()))
         .withValue("type", type).withValue("summary", summary).withValue("refTableName", refTable).withValue("refId", refId).withValue("parentDeleted", false), sourceFeature(refTable));
   }

   /*******************************************************************************
    ** Source-specific activity requires that feature; generic events retain the
    ** existing timeline authorization contract.
    *******************************************************************************/
   private static RecordFeature sourceFeature(String table)
   {
      return (switch(table == null ? "" : table)
      {
         case CollaborationTables.ATTACHMENT -> RecordFeature.ATTACHMENTS;
         case CollaborationTables.COMMENT, CollaborationTables.COMMENT_REVISION, CollaborationTables.MENTION -> RecordFeature.COMMENTS;
         case CollaborationTables.TAG, CollaborationTables.RECORD_TAG -> RecordFeature.TAGS;
         case CollaborationTables.FIELD_DEFINITION, CollaborationTables.FIELD_VALUE -> RecordFeature.CUSTOM_FIELDS;
         case CollaborationTables.TASK -> RecordFeature.TASKS;
         case CollaborationTables.RECORD_ASSIGNMENT -> RecordFeature.ASSIGNMENT;
         case CollaborationTables.FOLLOW, CollaborationTables.FOLLOW_DIGEST -> RecordFeature.FOLLOW;
         case CollaborationTables.SHARE -> RecordFeature.SHARING;
         default -> RecordFeature.TIMELINE;
      });
   }
}
