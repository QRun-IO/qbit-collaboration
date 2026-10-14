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

import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFollowers;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.FollowEvent;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFollower;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Exercise the installed provider through core's public event-subscriber facade.
 *******************************************************************************/
class FollowersProviderTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Preferences, mutes and parent scope apply even for callers without access.
    *******************************************************************************/
   @Test
   void resolvesEventSubscribersAsSystemWithoutFilteringRecipientAccess() throws Exception
   {
      seed("change", "document", "1", true, false, null, false);
      seed("comment", "document", "1", false, true, null, false);
      seed("both", "document", "1", true, true, Instant.now().minusSeconds(60), false);
      seed("muted", "document", "1", true, true, Instant.now().plusSeconds(3600), false);
      seed("neither", "document", "1", false, false, null, false);
      seed("other-record", "document", "2", true, true, null, false);
      seed("other-table", "other", "1", true, true, null, false);
      seed("deleted", "document", "1", true, true, null, true);
      RecordReference reference = new RecordReference("document", 1);
      for(QSession session : List.of(new QSession(), new QSystemUserSession(instance)))
      {
         QContext.init(instance, session);
         for(FollowEvent event : List.of(FollowEvent.ALERT, FollowEvent.CHANGE))
         {
            assertThat(RecordFollowers.get(instance, reference, event)).extracting(RecordFollower::getUserId)
               .containsExactlyInAnyOrder("change", "both");
         }
         assertThat(RecordFollowers.get(instance, reference, FollowEvent.COMMENT)).extracting(RecordFollower::getUserId)
            .containsExactlyInAnyOrder("comment", "both");
         assertThat(RecordFollowers.get(instance, reference, FollowEvent.CHANGE)).filteredOn(f -> f.getUserId().equals("change"))
            .singleElement().satisfies(f ->
            {
               assertThat(f.getNotifyOnChange()).isTrue();
               assertThat(f.getNotifyOnComment()).isFalse();
               assertThat(f.getReason()).isEqualTo("MANUAL");
            });
         assertThat(QContext.getQSession()).isSameAs(session);
      }
   }

   /*******************************************************************************
    ** Existing subscriptions do not override the host's FOLLOW feature switch.
    *******************************************************************************/
   @Test
   void disabledFollowReturnsEmpty() throws Exception
   {
      seed("follower", "document", "1", true, true, null, false);
      RecordFeaturesMetaData.ofOrWithNew(instance.getTable("document")).withEnabled(RecordFeature.FOLLOW, false);
      RuntimeMetaDataOverlay.refresh(instance);
      for(FollowEvent event : FollowEvent.values())
      {
         assertThat(RecordFollowers.get(instance, new RecordReference("document", 1), event)).isEmpty();
      }
   }

   /*******************************************************************************
    ** Seed persisted preferences directly, including subscriptions lacking access.
    *******************************************************************************/
   private void seed(String user, String table, String id, Boolean change, Boolean comment, Instant muted, Boolean deleted) throws Exception
   {
      MemoryRecordStore.getInstance().insert(new InsertInput(CollaborationTables.FOLLOW).withRecords(List.of(new QRecord()
         .withValue("userId", user).withValue("tableName", table).withValue("recordId", id).withValue("reason", "MANUAL")
         .withValue("notifyOnChange", change).withValue("notifyOnComment", comment).withValue("mutedUntil", muted)
         .withValue("parentDeleted", deleted))), true);
   }
}
