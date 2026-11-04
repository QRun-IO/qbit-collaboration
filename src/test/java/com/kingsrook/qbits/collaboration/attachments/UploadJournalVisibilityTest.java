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


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournalTable;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadRecoveryNavigation;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.expressions.CurrentUser;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Native visibility and predicate tests. Memory is only the ordinary query
 ** backend here; no memory durability or retained upload claim is made.
 *******************************************************************************/
public class UploadJournalVisibilityTest extends PersonalServiceFixture
{
   @Test
   void journalRequiresOriginalActorAndCurrentParentVisibility() throws Exception
   {
      login("alice", "collaboration.view");
      QRecord row = seed();
      assertThat(new QueryAction().execute(new QueryInput(UploadJournalTable.NAME)).getRecords()).hasSize(1);
      assertThat(new GetAction().execute(new GetInput(UploadJournalTable.NAME).withPrimaryKey(row.getValue("id"))).getRecord()).isNotNull();
      login("bob", "collaboration.view");
      assertThat(new QueryAction().execute(new QueryInput(UploadJournalTable.NAME)).getRecords()).isEmpty();
      login("alice", "collaboration.view");
      com.kingsrook.qqq.backend.core.context.QContext.getQSession().getSecurityKeyValues().remove("tenant");
      assertThat(new QueryAction().execute(new QueryInput(UploadJournalTable.NAME)).getRecords()).isEmpty();
   }

   @Test
   void privatePhysicalColumnsCannotBeProbedOrProjected() throws Exception
   {
      login("alice", "collaboration.view");
      seed();
      for(String field : List.of("storageKey", "storage_key", "bucket_name", "manifest_hash", "lease_token"))
      {
         assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(UploadJournalTable.NAME).withFilter(
            new QQueryFilter().withCriteria(field, QCriteriaOperator.EQUALS, "probe")))).isInstanceOf(QPermissionDeniedException.class);
         assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(UploadJournalTable.NAME).withFilter(
            new QQueryFilter().withOrderBy(new QFilterOrderBy(field, true))))).isInstanceOf(QPermissionDeniedException.class);
         assertThatThrownBy(() -> new GetAction().execute(new GetInput(UploadJournalTable.NAME).withUniqueKey(Map.of(field, "probe"))))
            .isInstanceOf(QPermissionDeniedException.class);
      }
      assertThatThrownBy(() -> new CountAction().execute(new CountInput(UploadJournalTable.NAME))).isInstanceOf(QPermissionDeniedException.class);
   }

   @Test
   void recoveryProcessRequiresOneVisibleSelectionAndExplicitConfirmation() throws Exception
   {
      login("alice", "collaboration.view");
      var metadata = RecoverRetainedUploadProcess.metadata();
      var prepare = new RecoverRetainedUploadProcess.Prepare();
      var input = new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput().withProcessName(metadata.getName());
      var output = new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput();
      assertThatThrownBy(() -> prepare.run(input, output)).hasMessageContaining("exactly one");
      input.setRecords(List.of(seed()));
      prepare.run(input, output);
      assertThat(output.getValue("journalId")).isNotNull();
      assertThat(output.getValue("confirmRecovery")).isEqualTo(false);
      assertThatThrownBy(() -> new RecoverRetainedUploadProcess.Recover().run(input, new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput()))
         .hasMessageContaining("Confirm");
   }

   @Test
   void recoveryNavigationUsesCurrentActorAndCanonicalSourcePins() throws Exception
   {
      login("alice", "collaboration.view");
      seed();
      var source = com.kingsrook.qqq.backend.core.context.QContext.getQInstance().getTable("document");
      UploadRecoveryNavigation.configure(source);
      Object descriptor = RecordFeaturesMetaData.of(source).getSettings().get(RecordFeature.ATTACHMENTS).get("recoveryView");
      assertThat(descriptor).isInstanceOf(Map.class);
      Map<?, ?> view = (Map<?, ?>) descriptor;
      assertThat(view.get("tableName")).isEqualTo(UploadJournalTable.NAME);
      assertThat(view.get("label")).isEqualTo("Review interrupted uploads");
      QQueryFilter filter = (QQueryFilter) view.get("filter");
      filter.withCriteria("tableName", QCriteriaOperator.EQUALS, "document").withCriteria("recordId", QCriteriaOperator.EQUALS, "1");
      assertThat(new QueryAction().execute(new QueryInput(UploadJournalTable.NAME).withFilter(filter)).getRecords()).hasSize(1);
      login("bob", "collaboration.view");
      assertThat(new QueryAction().execute(new QueryInput(UploadJournalTable.NAME).withFilter(filter)).getRecords()).isEmpty();
      login("alice", "collaboration.view");
      for(String field : List.of("recordId", "state"))
      {
         assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(UploadJournalTable.NAME).withFilter(
            new QQueryFilter().withCriteria(field, QCriteriaOperator.EQUALS, new CurrentUser())))).isInstanceOf(QPermissionDeniedException.class);
      }
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(UploadJournalTable.NAME).withFilter(
         new QQueryFilter().withCriteria("createdByUserId", QCriteriaOperator.IN, new CurrentUser())))).isInstanceOf(QPermissionDeniedException.class);
   }

   /*******************************************************************************
    ** Public manifest projection only; physical storage secrets are not fields.
    *******************************************************************************/
   private QRecord seed() throws Exception
   {
      return (CollaborationRecords.insert(UploadJournalTable.NAME, new QRecord().withValue("tableName", "document").withValue("recordId", "1")
         .withValue("createdByUserId", "alice").withValue("fileName", "pending.txt").withValue("state", "PREPARED")
         .withValue("storageState", "UNKNOWN").withValue("sha256", "a".repeat(64)).withValue("sizeBytes", 5L).withValue("locatorHash", "b".repeat(64))));
   }
}
