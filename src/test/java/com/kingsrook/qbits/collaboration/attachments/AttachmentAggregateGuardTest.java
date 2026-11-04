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


import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.metadata.MetaDataAction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.metadata.MetaDataInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryJoin;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Actual native Count permission enforcement and metadata, plus aggregate input
 ** guard semantics. Record Files separately tests archived native parent rows.
 *******************************************************************************/
public class AttachmentAggregateGuardTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Ordinary Collaboration keeps its established count and metadata contract.
    *******************************************************************************/
   @Test
   void defaultPolicyPreservesOrdinaryCount() throws Exception
   {
      login("alice", "collaboration.view");
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT)).getCount()).isZero();
      assertThat(instance.getTable(CollaborationTables.ATTACHMENT).getDisabledCapabilities()).doesNotContain(Capability.TABLE_COUNT);
      assertThat(new AttachmentLifecyclePolicy().requiresPinnedAggregateParent()).isFalse();
   }

   /*******************************************************************************
    ** A readable literal pin is accepted; broad or invisible parents are rejected.
    *******************************************************************************/
   @Test
   void nativeCountRequiresCurrentlyVisiblePinnedParent() throws Exception
   {
      enable();
      assertThatThrownBy(() -> new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(pin("1"))).getCount()).isZero();
      assertThatThrownBy(() -> new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(pin("3")))).isInstanceOf(QPermissionDeniedException.class);
      var guard = new AttachmentAggregateGuard();
      assertThatThrownBy(() -> guard.checkPermissionsThrowing(new AggregateInput(CollaborationTables.ATTACHMENT), instance.getTable(CollaborationTables.ATTACHMENT)))
         .isInstanceOf(QPermissionDeniedException.class);
      guard.checkPermissionsThrowing(new AggregateInput(CollaborationTables.ATTACHMENT).withFilter(pin("1")), instance.getTable(CollaborationTables.ATTACHMENT));
   }

   /*******************************************************************************
    ** OR, set branches, conflicting pins and joins cannot escape the exact parent.
    *******************************************************************************/
   @Test
   void rejectsFilterShapesWhichDoNotGuaranteeOneParent() throws Exception
   {
      enable();
      for(QQueryFilter filter : java.util.List.of(pin("1").withBooleanOperator(QQueryFilter.BooleanOperator.OR),
         pin("1").withSubFilterSetOperator(QQueryFilter.SubFilterSetOperator.UNION),
         pin("1").withSubFilter(new QQueryFilter().withSubFilterSetOperator(QQueryFilter.SubFilterSetOperator.UNION_ALL)),
         pin("1").withCriteria("recordId", QCriteriaOperator.EQUALS, "2"),
         new QQueryFilter().withSubFilter(pin("1")), new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, "document")))
      {
         assertThatThrownBy(() -> new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(filter))).isInstanceOf(QPermissionDeniedException.class);
      }
      assertThatThrownBy(() -> new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(pin("1")).withQueryJoin(new QueryJoin())))
         .isInstanceOf(QPermissionDeniedException.class);
   }

   /*******************************************************************************
    ** Generic list clients see no unscoped total capability in instance metadata.
    *******************************************************************************/
   @Test
   void metadataHidesGenericTotalsWithoutDisablingPinnedNativeCount() throws Exception
   {
      enable();
      var table = new MetaDataAction().execute(new MetaDataInput()).getTables().get(CollaborationTables.ATTACHMENT);
      assertThat(table.getCapabilities()).doesNotContain("TABLE_COUNT", "QUERY_STATS").contains("TABLE_QUERY");
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(pin("1"))).getCount()).isZero();
   }

   /*******************************************************************************
    ** Fixture policy is installed before count actions and reflected in metadata.
    *******************************************************************************/
   private void enable()
   {
      login("alice", "collaboration.view");
      config.withAttachmentLifecycle(new QCodeReference(PinnedPolicy.class));
      AttachmentAggregateGuard.configure(instance.getTable(CollaborationTables.ATTACHMENT), config);
   }

   /*******************************************************************************
    ** Removing the runtime lifecycle cannot broaden a previously installed table.
    *******************************************************************************/
   @Test
   void installedRestrictionSurvivesPolicyRemovalAndMetadataClone() throws Exception
   {
      enable();
      var clone = instance.getTable(CollaborationTables.ATTACHMENT).clone();
      config.withAttachmentLifecycle(new QCodeReference(AttachmentLifecyclePolicy.class));
      instance.getTables().put(CollaborationTables.ATTACHMENT, AttachmentAggregateGuard.configure(clone, config));
      assertThatThrownBy(() -> new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(pin("1"))).getCount()).isZero();
      assertThat(instance.getTable(CollaborationTables.ATTACHMENT).getDisabledCapabilities()).contains(Capability.TABLE_COUNT, Capability.QUERY_STATS);
   }

   /*******************************************************************************
    ** Fresh default metadata still detects retained rows of invisible parents.
    *******************************************************************************/
   @Test
   void persistedRetentionRequiresPinsWithoutAnInstalledPolicyMarker() throws Exception
   {
      login("alice", "collaboration.view");
      var input = new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput(CollaborationTables.ATTACHMENT)
         .withRecords(java.util.List.of(new com.kingsrook.qqq.backend.core.model.data.QRecord().withValue("tableName", "document")
            .withValue("recordId", "3").withValue("retained", true).withValue("fileName", "retained.txt")
            .withValue("contentType", "text/plain").withValue("sizeBytes", 1L).withValue("sha256", "a".repeat(64))
            .withValue("bucketName", "fixture").withValue("storageKey", "fixture-retained").withValue("createdByUserId", "alice")));
      input.setTableMetaData(com.kingsrook.qbits.collaboration.CollaborationRecords.cleanupMetadata(CollaborationTables.ATTACHMENT));
      input.withPermissionBypass("Test fixture seeds retained owner metadata without an installed lifecycle.");
      assertThat(new com.kingsrook.qqq.backend.core.actions.tables.InsertAction().execute(input).getRecords()).allSatisfy(row -> assertThat(row.getErrors()).isNullOrEmpty());
      assertThatThrownBy(() -> new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT).withFilter(pin("1"))).getCount()).isZero();
   }

   /*******************************************************************************
    ** Exact equality on both canonical reference fields is required.
    *******************************************************************************/
   private QQueryFilter pin(String id)
   {
      return (new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, "document").withCriteria("recordId", QCriteriaOperator.EQUALS, id));
   }

   /*******************************************************************************
    ** An ordinary generic owner can opt in without depending on Record Files.
    *******************************************************************************/
   public static class PinnedPolicy extends AttachmentLifecyclePolicy
   {
      /*******************************************************************************
       ** Constant metadata capability policy, no request context needed.
       *******************************************************************************/
      @Override
      public boolean requiresPinnedAggregateParent()
      {
         return (true);
      }
   }
}
