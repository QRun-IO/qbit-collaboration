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

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.collaboration.tags.TagsService;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeatureSummaryHelper;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Core contract integration, using real action and metadata resolution paths.
 *******************************************************************************/
class CoreContractsTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Instance defaults and table-specific declarations follow core precedence.
    *******************************************************************************/
   @Test
   void defaultsAndTableDeclarations() throws Exception
   {
      var configured = new CollaborationQBitConfig().withTableFeatures("document", RecordFeature.TAGS);
      instance.getSupplementalMetaData().remove(RecordFeaturesMetaData.NAME);
      configured.applyFeatures(instance);
      assertThat(RecordFeaturesMetaData.of(instance).getEnabled()).containsEntry(RecordFeature.FAVORITES, true)
         .containsEntry(RecordFeature.RECENTS, true).containsEntry(RecordFeature.TIMELINE, true).containsEntry(RecordFeature.COMMENTS, false);
      RuntimeMetaDataOverlay.refresh(instance);
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).enabledFeatures()).containsExactly(RecordFeature.TAGS);
   }

   /*******************************************************************************
    ** Committed policy refreshes the overlay while rollback publishes no generation.
    *******************************************************************************/
   @Test
   void settingsRefreshAfterCommitAndDeleteRestoresDefaults() throws Exception
   {
      admin();
      RuntimeMetaDataOverlay.refresh(instance);
      long generation = RuntimeMetaDataOverlay.getGeneration(instance);
      assertThat(instance.getTable("document").getVirtualFields()).containsKey("collaborationTags");
      assertThat(instance.getTable("document").getVirtualField("collaborationTags").getIsQuerySelectable()).isTrue();
      assertThat(instance.getTable("document").getVirtualField("collaborationTags").getValueProvider()).isNotNull();
      QBackendTransaction tx = new QBackendTransaction();
      var row = new InsertAction().execute(new InsertInput(CollaborationTables.FEATURE_SETTING).withTransaction(tx).withRecords(List.of(
         new QRecord().withValue("scopeType", "TABLE").withValue("scopeName", "document").withValue("feature", "TAGS")
            .withValue("enabled", false).withValue("settings", "{\"mode\":\"APPROVED_ONLY\"}")))).getRecords().getFirst();
      assertThat(row.getErrors()).isNullOrEmpty();
      assertThat(RuntimeMetaDataOverlay.getGeneration(instance)).isEqualTo(generation);
      tx.commit();
      assertThat(RuntimeMetaDataOverlay.getGeneration(instance)).isGreaterThan(generation);
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).isEnabled(RecordFeature.TAGS)).isFalse();
      assertThat(instance.getTable("document").getVirtualFields()).doesNotContainKey("collaborationTags");
      new UpdateAction().execute(new UpdateInput(CollaborationTables.FEATURE_SETTING).withRecords(List.of(new QRecord().withValue("id", row.getValue("id")).withValue("enabled", true))));
      assertThat(instance.getTable("document").getVirtualFields()).containsKey("collaborationTags");
      new DeleteAction().execute(new DeleteInput(CollaborationTables.FEATURE_SETTING).withPrimaryKeys(List.of(row.getValue("id"))));
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).isEnabled(RecordFeature.TAGS)).isTrue();
      generation = RuntimeMetaDataOverlay.getGeneration(instance);
      tx = new QBackendTransaction();
      new FeatureSettingsCustomizer().postInsert(new InsertInput(CollaborationTables.FEATURE_SETTING).withTransaction(tx), List.of());
      tx.rollback();
      assertThat(RuntimeMetaDataOverlay.getGeneration(instance)).isEqualTo(generation);
   }

   /*******************************************************************************
    ** Implied grants require WRITE locks; explicit feature grants retain READ checks.
    *******************************************************************************/
   @Test
   void impliedFeatureGrantChecksWriteLocks() throws Exception
   {
      instance.addSecurityKeyType(new QSecurityKeyType().withName("editor"));
      instance.getTable("document").withRecordSecurityLock(new RecordSecurityLock().withFieldName("tenant")
         .withSecurityKeyType("editor").withLockScope(RecordSecurityLock.LockScope.WRITE));
      QContext.getQSession().withSecurityKeyValue("editor", "b");
      assertThat(ParentRecordAccess.requireVisibleParent("document", 1)).isNotNull();
      assertThatThrownBy(() -> ParentRecordAccess.requireFeature("document", 1, "comment")).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().withPermissions("document.comment");
      ParentRecordAccess.requireFeature("document", 1, "comment");
   }

   /*******************************************************************************
    ** Configured mirrors are generated, populated and updated by core.
    *******************************************************************************/
   @Test
   void mirrorsAndSchemaUseConfiguredKeys() throws Exception
   {
      config.withMirroredSecurityKeyTypes(List.of("tenant"));
      var security = com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceSecurityConfig.of(instance);
      security.withMirroredSecurityKeyTypes(List.of("tenant"));
      for(var table : instance.getTables().values())
      {
         CoreCollaboration.reference(table, config);
      }
      var comment = new com.kingsrook.qbits.collaboration.comments.CommentsService().post("document", 1, "Hello", null, null);
      var row = CollaborationRecords.query(CollaborationTables.COMMENT, new QQueryFilter()).getFirst();
      assertThat(row.getValueString("lockKey_tenant")).isEqualTo("a");
      assertThat(CollaborationSchema.postgresScript(config)).contains("lock_key_tenant", "ADD COLUMN IF NOT EXISTS lock_key_tenant");
      QContext.getQSession().withSecurityKeyValue("allTenants", true);
      new UpdateAction().execute(new UpdateInput("document").withRecords(List.of(new QRecord().withValue("id", 1).withValue("tenant", "b"))));
      assertThat(CollaborationRecords.cleanupQuery(CollaborationTables.COMMENT, new QQueryFilter()).getFirst().getValueString("lockKey_tenant")).isEqualTo("b");
      login("alice");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.RECORD_TAG)).getRecords()).isEmpty();
      assertThat(comment.id()).isPositive();
   }

   /*******************************************************************************
    ** Core query expansion and summaries expose the same persisted collaboration state.
    *******************************************************************************/
   @Test
   void virtualTagCriteriaAndSummary() throws Exception
   {
      RuntimeMetaDataOverlay.refresh(instance);
      var tag = new TagsService().create("document", "Urgent", null);
      new TagsService().apply("document", 1, List.of(tag.id()), List.of());
      var matches = new QueryAction().execute(new QueryInput("document").withFilter(new QQueryFilter()
         .withCriteria("collaborationTags", QCriteriaOperator.EQUALS, tag.id()))).getRecords();
      assertThat(matches).extracting(row -> row.getValueInteger("id")).containsExactly(1);
      new com.kingsrook.qbits.collaboration.comments.CommentsService().post("document", 1, "Hello", null, null);
      new com.kingsrook.qbits.collaboration.tasks.TasksService().create("document", 1, "Review", null, null, null, null);
      new com.kingsrook.qbits.collaboration.assignment.AssignmentService().assign("document", 1, "alice", "alice");
      var summary = RecordFeatureSummaryHelper.summarize(instance.getTable("document"), ParentRecordAccess.requireVisibleParent("document", 1));
      assertThat(summary.getCounts()).containsEntry("tags", 1).containsEntry("openComments", 1).containsEntry("openTasks", 1).containsEntry("attachments", 0).containsEntry("followers", 1);
      assertThat(summary.getIsFollowing()).isTrue();
      assertThat(summary.getIsFavorite()).isFalse();
      assertThat(summary.getOwner()).isEqualTo("alice");
      assertThat(summary.getAssignee()).isEqualTo("alice");
   }

   /*******************************************************************************
    ** Realtime hints contain identifiers and obey commit/rollback boundaries.
    *******************************************************************************/
   @Test
   void realtimeCommitsAndRollbackDiscardsHints() throws Exception
   {
      List<RealtimeMessage> messages = new CopyOnWriteArrayList<>();
      try(var subscription = RealtimeBus.get(instance).subscribe(messages::add))
      {
         var tx = new QBackendTransaction();
         QContext.setTransaction(tx);
         var comment = new com.kingsrook.qbits.collaboration.comments.CommentsService().post("document", 1, "Private body", null, null);
         assertThat(messages).noneMatch(message -> message.getEvent().equals("comment.added"));
         tx.commit();
         QContext.clearTransaction();
         assertThat(messages).anyMatch(message -> message.getEvent().equals("comment.added") && message.getChannel().equals("record:document:1"));
         assertThat(messages).allSatisfy(message -> assertThat(message.getData().toString()).doesNotContain("Private body"));
         messages.clear();
         tx = new QBackendTransaction();
         QContext.setTransaction(tx);
         new com.kingsrook.qbits.collaboration.comments.CommentsService().edit(comment.id(), "Changed");
         tx.rollback();
         QContext.clearTransaction();
         assertThat(messages).noneMatch(message -> message.getEvent().equals("comment.updated"));
         new com.kingsrook.qbits.collaboration.comments.CommentsService().edit(comment.id(), "Committed");
         assertThat(messages).anyMatch(message -> message.getEvent().equals("comment.updated"));
      }
      RealtimeBus.close(instance);
   }
}
