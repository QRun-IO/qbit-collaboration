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

import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceTableMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Service activity follows its originating feature without enabling timeline UI.
 *******************************************************************************/
class ActivityWriterFeatureIsolationTest extends PersonalServiceFixture
{
   /***************************************************************************
    ** An authorized attachment mutation must retain its event with timeline off.
    ***************************************************************************/
   @Test
   void attachmentActivityDoesNotRequireTimeline() throws Exception
   {
      features(RecordFeature.ATTACHMENTS);
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).isEnabled(RecordFeature.TIMELINE)).isFalse();
      ActivityWriter.write("document", "1", "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, "41");
      assertThat(RecordReferenceTableMetaData.of(instance.getTable(CollaborationTables.ACTIVITY)).getFeature()).isEqualTo(RecordFeature.TIMELINE);
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).isEnabled(RecordFeature.TIMELINE)).isFalse();

      features(RecordFeature.ATTACHMENTS, RecordFeature.TIMELINE);
      assertThat(CollaborationRecords.query(CollaborationTables.ACTIVITY,
         CollaborationRecords.parent("document", "1"))).singleElement().satisfies(row ->
         {
            assertThat(row.getValueString("type")).isEqualTo("FILE_ADDED");
            assertThat(row.getValueString("actorUserId")).isEqualTo("alice");
         });
   }


   /***************************************************************************
    ** Enabling timeline cannot authorize a disabled attachment operation.
    ***************************************************************************/
   @Test
   void originatingFeatureRemainsRequired() throws Exception
   {
      features(RecordFeature.TIMELINE);
      assertThatThrownBy(() -> ActivityWriter.write("document", "1", "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, "41"))
         .isInstanceOf(QException.class);
      assertThat(CollaborationRecords.query(CollaborationTables.ACTIVITY, new QQueryFilter())).isEmpty();
   }


   /***************************************************************************
    ** Service persistence must preserve native parent tenant authorization.
    ***************************************************************************/
   @Test
   void inaccessibleParentCannotAcquireActivity() throws Exception
   {
      features(RecordFeature.ATTACHMENTS);
      assertThatThrownBy(() -> ActivityWriter.write("document", "3", "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, "41"))
         .isInstanceOf(QException.class);
   }


   /***************************************************************************
    ** Use the supported refresh boundary so effective metadata changes atomically.
    ***************************************************************************/
   private void features(RecordFeature... enabled) throws Exception
   {
      config.withTableFeatures("document", enabled).applyFeatures(instance);
      RuntimeMetaDataOverlay.refresh(instance);
   }
}
