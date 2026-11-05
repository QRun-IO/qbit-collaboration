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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.attachments.AttachmentCleanup;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.tags.TagEntry;

import com.kingsrook.qbits.collaboration.tags.TagProcesses;
import com.kingsrook.qbits.collaboration.tags.TagsService;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Real QQQ actions exercise tag permissions, persistence, processes and metadata.
 *******************************************************************************/
public class TagsServiceTest extends PersonalServiceFixture
{
   private final TagsService tags = new TagsService();


   /*******************************************************************************
    ** Tag labels are selectable while the ID-based filter picker remains intact.
    *******************************************************************************/
   @Test
   void selectsTagLabelsWithoutTranslatingThemAsIds() throws Exception
   {
      TagEntry beta = tags.create("document", "Beta", null);
      TagEntry alpha = tags.create("document", "Alpha", null);
      tags.apply("document", 1, List.of(beta.id(), alpha.id()), List.of());
      com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay.refresh(instance);
      var field = instance.getTable("document").getVirtualField("collaborationTags");
      assertThat(field.getIsQuerySelectable()).isTrue();
      assertThat(field.getPossibleValueSourceName()).isEqualTo(CollaborationTables.TAG);
      var rows = new QueryAction().execute(new QueryInput("document").withFieldNamesToInclude(java.util.Set.of("collaborationTags"))
         .withShouldTranslatePossibleValues(true).withShouldGenerateDisplayValues(true)).getRecords();
      assertThat(rows).hasSize(2);
      assertThat(rows).allSatisfy(row -> assertThat(row.getValues()).containsOnlyKeys("collaborationTags"));
      assertThat(rows).extracting(row -> row.getValueString("collaborationTags")).containsExactlyInAnyOrder("Alpha, Beta", null);
      assertThat(rows).filteredOn(row -> row.getValue("collaborationTags") != null)
         .allSatisfy(row -> assertThat(row.getDisplayValue("collaborationTags")).isEqualTo("Alpha, Beta"));
      assertThat(new QueryAction().execute(new QueryInput("document").withFilter(new QQueryFilter()
         .withCriteria("collaborationTags", QCriteriaOperator.EQUALS, alpha.id()))).getRecords())
         .extracting(row -> row.getValueInteger("id")).containsExactly(1);
   }

   /*******************************************************************************
    ** Exported labels obey parent visibility and vocabulary approval, with CSV quoting.
    *******************************************************************************/
   @Test
   void tagLabelsRespectPrivacyAndExportQuoting() throws Exception
   {
      TagEntry visible = tags.create("document", "Needs, review", null);
      TagEntry secret = tags.create("document", "Secret label", null);
      tags.apply("document", 1, List.of(visible.id()), List.of());
      QContext.getQSession().withSecurityKeyValue("allTenants", true);
      tags.apply("document", 3, List.of(secret.id()), List.of());
      config.withDefaultTagMode("APPROVED_ONLY");
      TagEntry pending = tags.create("document", "Pending label", null);
      assertThat(pending.approved()).isFalse();
      // A stale assignment must not turn a pending suggestion into a visible label.
      CollaborationRecords.insert(CollaborationTables.RECORD_TAG, new QRecord().withValue("tableName", "document")
         .withValue("recordId", "1").withValue("tagId", pending.id()).withValue("createdByUserId", "alice").withValue("parentDeleted", false));
      login("alice");
      com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay.refresh(instance);
      var input = new QueryInput("document").withFieldNamesToInclude(java.util.Set.of("collaborationTags"));
      assertThat(new QueryAction().execute(input).getRecords()).extracting(row -> row.getValueString("collaborationTags"))
         .containsExactlyInAnyOrder("Needs, review", null);

      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      var export = new com.kingsrook.qqq.backend.core.model.actions.reporting.ExportInput();
      export.setTableName("document");
      export.setFieldNames(List.of("collaborationTags"));
      export.setReportDestination(new com.kingsrook.qqq.backend.core.model.actions.reporting.ReportDestination()
         .withReportFormat(com.kingsrook.qqq.backend.core.model.actions.reporting.ReportFormat.CSV).withReportOutputStream(output));
      new com.kingsrook.qqq.backend.core.actions.reporting.ExportAction().execute(export);
      String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);
      assertThat(csv).doesNotContain("Secret label", "Pending label");
      try(var parser = org.apache.commons.csv.CSVParser.parse(csv, org.apache.commons.csv.CSVFormat.DEFAULT.withFirstRecordAsHeader()))
      {
         assertThat(parser.getHeaderNames()).containsExactly("Tags");
         assertThat(parser.getRecords()).extracting(row -> row.get("Tags")).containsExactlyInAnyOrder("Needs, review", "");
      }
      QContext.getQSession().getSecurityKeyValues().put("tenant", List.of("b"));
      assertThat(new QueryAction().execute(input).getRecords()).extracting(row -> row.getValueString("collaborationTags")).containsExactly("Secret label");
      login("alice");
      assertThat(new QueryAction().execute(input).getRecords()).extracting(row -> row.getValueString("collaborationTags"))
         .containsExactlyInAnyOrder("Needs, review", null);
   }

   /*******************************************************************************
    ** A page spanning the provider's batch boundary retains its last parent values.
    *******************************************************************************/
   @Test
   void tagLabelsSpanMultipleParentBatches() throws Exception
   {
      java.util.List<QRecord> parents = new java.util.ArrayList<>();
      for(int id = 4; id <= 504; id++)
      {
         parents.add(new QRecord().withValue("id", id).withValue("title", "Document " + id).withValue("tenant", "a"));
      }
      assertThat(new InsertAction().execute(new InsertInput("document").withRecords(parents)).getRecords())
         .allSatisfy(row -> assertThat(row.getErrors()).isNullOrEmpty());
      TagEntry tag = tags.create("document", "Boundary", null);
      tags.apply("document", 1, List.of(tag.id()), List.of());
      tags.apply("document", 504, List.of(tag.id()), List.of());
      com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay.refresh(instance);
      var rows = new QueryAction().execute(new QueryInput("document").withFieldNamesToInclude(java.util.Set.of("id", "collaborationTags"))).getRecords();
      assertThat(rows).hasSize(503);
      assertThat(rows).filteredOn(row -> row.getValue("collaborationTags") != null).extracting(row -> row.getValueInteger("id"))
         .containsExactlyInAnyOrder(1, 504);
      assertThat(rows).filteredOn(row -> row.getValue("collaborationTags") != null)
         .allSatisfy(row -> assertThat(row.getValueString("collaborationTags")).isEqualTo("Boundary"));
   }

   /*******************************************************************************
    ** Stale links cannot expose merged vocabulary, other-table tags, or deleted links.
    *******************************************************************************/
   @Test
   void tagLabelsExcludeStaleAndOutOfScopeAssignments() throws Exception
   {
      login("admin", TagsService.MANAGE);
      TagEntry global = tags.create(null, "Global", null);
      TagEntry current = tags.create("document", "Current", null);
      TagEntry merged = tags.create("document", "Merged old label", null);
      TagEntry deleted = tags.create("document", "Deleted link label", null);
      TagEntry other = tags.create("document", "Other table label", null);
      CollaborationRecords.update(CollaborationTables.TAG, new QRecord().withValue("id", merged.id()).withValue("mergedIntoTagId", current.id()));
      CollaborationRecords.update(CollaborationTables.TAG, new QRecord().withValue("id", other.id()).withValue("tableName", "different_table"));
      for(TagEntry tag : List.of(global, current, merged, deleted, other))
      {
         CollaborationRecords.insert(CollaborationTables.RECORD_TAG, new QRecord().withValue("tableName", "document").withValue("recordId", "1")
            .withValue("tagId", tag.id()).withValue("createdByUserId", "admin").withValue("parentDeleted", tag.id().equals(deleted.id())));
      }
      login("alice");
      com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay.refresh(instance);
      var rows = new QueryAction().execute(new QueryInput("document").withFieldNamesToInclude(java.util.Set.of("collaborationTags"))).getRecords();
      assertThat(rows).extracting(row -> row.getValueString("collaborationTags")).containsExactlyInAnyOrder("Current, Global", null);
      assertThat(com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceTableMetaData.of(instance.getTable(CollaborationTables.RECORD_TAG))).isNotNull();
   }

   /*******************************************************************************
    ** Natural keys are locale-independent and validate after collapsing whitespace.
    *******************************************************************************/
   @Test
   void normalizationAndFreeCreate() throws Exception
   {
      assertThat(TagsService.normalize("  Priority:HIGH   today  ")).isEqualTo("priority:high today");
      assertThat(TagsService.normalize("a".repeat(60))).hasSize(60);
      assertThatThrownBy(() -> TagsService.normalize("a".repeat(61))).hasMessageContaining("60");
      assertThatThrownBy(() -> TagsService.normalize(null)).hasMessageContaining("60");
      assertThatThrownBy(() -> TagsService.normalize("   ")).hasMessageContaining("60");
      TagEntry tag = tags.create("document", "Urgent", "red");
      assertThat(tag.name()).isEqualTo("urgent");
      assertThat(tag.label()).isEqualTo("Urgent");
      assertThat(tag.color()).isEqualTo("red");
      assertThat(tag.tableName()).isEqualTo("document");
      assertThat(tag.approved()).isTrue();
      assertThat(tag.usageCount()).isZero();
      assertThat(tags.create("document", " urgent ", null).id()).isEqualTo(tag.id());
   }

   /*******************************************************************************
    ** Approval-only never permits applying a pending tag, including its author.
    *******************************************************************************/
   @Test
   void suggestionsAndUnknownLabels() throws Exception
   {
      config.withDefaultTagMode("APPROVED_ONLY");
      TagEntry pending = tags.create("document", "Suggested", null);
      assertThat(pending.approved()).isFalse();
      assertThatThrownBy(() -> tags.apply("document", 1, List.of(pending.id()), List.of())).hasMessageContaining("Pick an approved tag");
      assertThatThrownBy(() -> tags.apply("document", 1, List.of("Unknown"), List.of())).hasMessageContaining("Pick an approved tag");
      assertThat(rows(CollaborationTables.TAG)).hasSize(1);
      login("bob");
      assertThat(tags.vocabulary("document", null, 20)).isEmpty();
      assertThatThrownBy(() -> tags.create("document", "Suggested", null)).hasMessageContaining("reserved");
      login("alice", TagsService.MANAGE);
      assertThat(tags.create("document", "Managed", null).approved()).isTrue();
      tags.approve(pending.id());
      tags.apply("document", 1, List.of(pending.id()), List.of());
      assertThat(tags.tagsFor("document", 1)).extracting(TagEntry::id).containsExactly(pending.id());
      config.withDefaultTagMode("FREE");
      tags.apply("document", 2, List.of("New on the fly"), List.of());
      assertThat(tags.tagsFor("document", 2)).extracting(TagEntry::name).containsExactly("new on the fly");
   }

   /*******************************************************************************
    ** Global vocabulary and management demand the explicit permission.
    *******************************************************************************/
   @Test
   void globalsAndManagementPermissions() throws Exception
   {
      assertThatThrownBy(() -> tags.create(null, "Global", null)).isInstanceOf(QPermissionDeniedException.class);
      TagEntry own = tags.create("document", "Local", null);
      assertThatThrownBy(() -> tags.approve(own.id())).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> tags.delete(own.id())).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> tags.merge(own.id(), own.id())).isInstanceOf(QPermissionDeniedException.class);
      login("manager", TagsService.MANAGE);
      TagEntry global = tags.create(null, "Global", null);
      assertThat(global.tableName()).isNull();
      assertThat(global.approved()).isTrue();
      assertThat(tags.vocabulary("document", "glo", null)).extracting(TagEntry::id).containsExactly(global.id());
   }

   /*******************************************************************************
    ** Invisible parents return Not found; read-only users need an explicit grant.
    *******************************************************************************/
   @Test
   void parentAndFeatureSecurity() throws Exception
   {
      TagEntry tag = tags.create("document", "A", null);
      assertThatThrownBy(() -> tags.tagsFor("document", 3)).isInstanceOf(QNotFoundException.class).hasMessage("Not found");
      assertThatThrownBy(() -> tags.apply("document", 3, List.of(tag.id()), List.of())).isInstanceOf(QNotFoundException.class);
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("reader")).withPermissions("document.read").withSecurityKeyValue("tenant", "a"));
      assertThatThrownBy(() -> tags.create("document", "No", null)).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> tags.apply("document", 1, List.of(tag.id()), List.of())).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().withPermissions("document.tag");
      tags.create("document", "Granted", null);
      tags.apply("document", 1, List.of(tag.id()), List.of());
      assertThat(tags.tagsFor("document", 1)).hasSize(1);
      assertThatThrownBy(() -> tags.vocabulary("missing", null, 1)).isInstanceOf(QNotFoundException.class);
      assertThatThrownBy(() -> tags.create("missing", "No", null)).isInstanceOf(QNotFoundException.class);
   }

   /*******************************************************************************
    ** Repeated add/remove does not inflate counts or produce duplicate activity.
    *******************************************************************************/
   @Test
   void idempotentAssignmentsCountsAndActivity() throws Exception
   {
      TagEntry tag = tags.create("document", "A", null);
      tags.apply("document", "01", List.of(tag.id(), tag.id()), List.of());
      tags.apply("document", 1, List.of("A"), null);
      tags.apply("document", 2, List.of(tag.id()), List.of());
      assertThat(rows(CollaborationTables.RECORD_TAG)).hasSize(2);
      assertThat(tags.vocabulary("document", null, 20).getFirst().usageCount()).isEqualTo(2);
      assertThat(rows(CollaborationTables.ACTIVITY)).extracting(row -> row.getValueString("type")).containsExactly("TAGGED", "TAGGED");
      assertThat(rows(CollaborationTables.FOLLOW_DIGEST)).isEmpty();
      tags.apply("document", 1, null, List.of(tag.id()));
      tags.apply("document", 1, List.of(), List.of("A"));
      assertThat(tags.tagsFor("document", 1)).isEmpty();
      assertThat(tags.vocabulary("document", null, 20).getFirst().usageCount()).isEqualTo(1);
      assertThat(rows(CollaborationTables.ACTIVITY)).hasSize(3);
   }

   /*******************************************************************************
    ** TABLE wins over APP then INSTANCE then the code default, with caller restored.
    *******************************************************************************/
   @Test
   void settingsHierarchyReadAsSystem() throws Exception
   {
      instance.addApp(new QAppMetaData().withName("work").withLabel("Work").withChild(instance.getTable("document")).withSection(new QAppSection().withName("main").withTables(List.of("document"))));
      assertThat(tags.mode("document")).isEqualTo("FREE");
      setting("INSTANCE", "*", "APPROVED_ONLY");
      assertThat(tags.mode("document")).isEqualTo("APPROVED_ONLY");
      setting("APP", "work", "FREE");
      assertThat(tags.mode("document")).isEqualTo("FREE");
      setting("TABLE", "document", "APPROVED_ONLY");
      assertThat(tags.create("document", "Pending", null).approved()).isFalse();
      assertThat(QContext.getQSession().getUser().getIdReference()).isEqualTo("alice");
      assertThat(QContext.getQSession().hasPermission("collaboration.admin")).isFalse();
   }

   /*******************************************************************************
    ** Vocabulary ordering, scope, limits and private suggestions are stable.
    *******************************************************************************/
   @Test
   void vocabularyOrderingScopeAndSuggestions() throws Exception
   {
      TagEntry z = tags.create("document", "Zulu", null);
      TagEntry b = tags.create("document", "Beta", null);
      TagEntry a = tags.create("document", "Alpha", null);
      tags.apply("document", 1, List.of(z.id()), List.of());
      config.withDefaultTagMode("APPROVED_ONLY");
      TagEntry pending = tags.create("document", "Mine", null);
      login("bob");
      tags.create("document", "Other", null);
      login("alice");
      assertThat(tags.vocabulary("document", null, 1000)).extracting(TagEntry::id).containsExactly(z.id(), a.id(), b.id(), pending.id());
      assertThat(tags.vocabulary("document", " ALP ", 10)).extracting(TagEntry::id).containsExactly(a.id());
      assertThat(tags.vocabulary("document", null, 0)).hasSize(1);
      assertThat(tags.vocabulary("document", null, 2)).hasSize(2);
   }

   /*******************************************************************************
    ** Merge deduplicates assignments, preserves aliases and rejects invalid scope.
    *******************************************************************************/
   @Test
   void mergeDeleteAndParentCleanup() throws Exception
   {
      TagEntry source = tags.create("document", "Source", null);
      TagEntry target = tags.create("document", "Target", null);
      tags.apply("document", 1, List.of(source.id(), target.id()), List.of());
      tags.apply("document", 2, List.of(source.id()), List.of());
      login("manager", TagsService.MANAGE);
      assertThatThrownBy(() -> tags.merge(source.id(), source.id())).hasMessageContaining("different");
      TagEntry global = tags.create(null, "Global", null);
      assertThatThrownBy(() -> tags.merge(global.id(), target.id())).hasMessageContaining("scope");
      tags.merge(source.id(), target.id());
      assertThat(tags.tagsFor("document", 1)).extracting(TagEntry::id).containsExactly(target.id());
      assertThat(tags.tagsFor("document", 2)).extracting(TagEntry::id).containsExactly(target.id());
      assertThat(tags.vocabulary("document", null, 20)).extracting(TagEntry::id).doesNotContain(source.id());
      assertThat(rows(CollaborationTables.TAG)).filteredOn(row -> row.getValueInteger("id").equals(source.id()))
         .singleElement().satisfies(row -> { assertThat(row.getValueInteger("usageCount")).isZero(); assertThat(row.getValueInteger("mergedIntoTagId")).isEqualTo(target.id()); });
      assertThatThrownBy(() -> tags.create("document", "Source", null)).hasMessageContaining("reserved");
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)));
      new AttachmentCleanup().onParentsDeleted("document", List.of(1));
      new AttachmentCleanup().onParentsDeleted("document", List.of(1));
      assertThat(tags.vocabulary("document", null, 20).getFirst().usageCount()).isEqualTo(1);
      tags.delete(target.id());
      assertThat(rows(CollaborationTables.RECORD_TAG)).isEmpty();
   }

   /*******************************************************************************
    ** All translator operators use visible parent ids, with set semantics.
    *******************************************************************************/
   @Test
   void translatorOperators() throws Exception
   {
      TagEntry a = tags.create("document", "A", null);
      TagEntry b = tags.create("document", "B", null);
      tags.apply("document", 1, List.of(a.id(), b.id()), List.of());
      tags.apply("document", 2, List.of(a.id()), List.of());
      assertThat(recordIds("document", "IN", List.of("A"))).containsExactly("1", "2");
      assertThat(recordIds("document", "IN", List.of(b.id()))).containsExactly("1");
      assertThat(recordIds("document", "CONTAINS_ALL", List.of(a.id(), b.id(), a.id()))).containsExactly("1");
      assertThat(recordIds("document", "CONTAINS_ALL", List.of(a.id(), "unknown"))).isEmpty();
      assertThat(recordIds("document", "NOT_IN", List.of(b.id()))).containsExactly("2");
      assertThat(recordIds("document", "IS_NOT_BLANK", null)).containsExactly("1", "2");
      assertThat(recordIds("document", "IS_BLANK", null)).isEmpty();
      tags.apply("document", 2, List.of(), List.of(a.id()));
      assertThat(recordIds("document", "IS_BLANK", List.of())).containsExactly("2");
      assertThat(recordIds("document", "IN", List.of())).isEmpty();
      assertThat(recordIds("document", "NOT_IN", List.of("unknown"))).containsExactly("1", "2");
      assertThat(recordIds("document", "CONTAINS_ALL", List.of())).containsExactly("1", "2");
      assertThatThrownBy(() -> recordIds("document", "LIKE", List.of())).hasMessageContaining("Unsupported");
   }

   /*******************************************************************************
    ** The core bound applies to subquery rows, not the number of matching parents.
    *******************************************************************************/
   @Test
   void translatorCap() throws Exception
   {
      List<QRecord> parents = new ArrayList<>();
      for(int id = 4; id <= 10001; id++)
      {
         parents.add(new QRecord().withValue("id", id).withValue("tenant", "a"));
      }
      new InsertAction().execute(new InsertInput("document").withRecords(parents));
      assertThat(recordIds("document", "IS_BLANK", null)).hasSize(10000);
      new InsertAction().execute(new InsertInput("document").withRecords(List.of(new QRecord().withValue("id", 10002).withValue("tenant", "a"))));
      assertThat(recordIds("document", "IS_BLANK", null)).hasSize(10001);
   }

   /*******************************************************************************
    ** Direct access cannot bypass vocabulary approval or parent lock restrictions.
    *******************************************************************************/
   @Test
   void tableSecurityAndHome() throws Exception
   {
      TagEntry tag = tags.create("document", "<Urgent>", null);
      tags.apply("document", 1, List.of(tag.id()), List.of());
      config.withDefaultTagMode("APPROVED_ONLY");
      TagEntry pending = tags.create("document", "Pending", null);
      login("bob", "collaboration.view");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.TAG)).getRecords()).hasSize(1);
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.TAG)).getCount()).isEqualTo(1);
      assertThatThrownBy(() -> new GetAction().execute(new GetInput(CollaborationTables.TAG).withPrimaryKey(pending.id()))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.TAG).withPrimaryKey(tag.id())).getRecord()).isNotNull();
      assertThatThrownBy(() -> new InsertAction().execute(new InsertInput(CollaborationTables.TAG).withRecords(List.of(new QRecord())))).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(CollaborationTables.TAG).withRecords(List.of(new QRecord().withValue("id", tag.id()))))).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> new DeleteAction().execute(new DeleteInput(CollaborationTables.TAG).withPrimaryKeys(List.of(tag.id())))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.RECORD_TAG)).getRecords()).hasSize(1);
      QContext.getQSession().getSecurityKeyValues().put("tenant", List.of("b"));
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.RECORD_TAG)).getRecords()).isEmpty();
      login("manager", "collaboration.admin", TagsService.MANAGE);
      var home = CollaborationHome.tags();
      assertThat(home.getRows()).hasSize(2);
      assertThat(home.getRows().getFirst()).containsEntry("label", "<Urgent>").containsEntry("table", "Documents").containsEntry("records", 1);
      assertThat(home.getRows().getLast()).containsEntry("records", 0);
      assertThat(home.getNoRowsFoundHTML()).isEqualTo(CollaborationHome.NO_TAGS);
   }

   /*******************************************************************************
    ** Metadata, nullable uniqueness and scoped PVS are validated together.
    *******************************************************************************/
   @Test
   void schemaAndMetadata() throws Exception
   {
      TagProcesses.processesFor("document").forEach(instance::addProcess);
      new QInstanceValidator().validate(instance);
      assertThat(instance.getTable(CollaborationTables.TAG).getSearchFields()).containsExactly("label");
      assertThat(instance.getTable(CollaborationTables.TAG).getSections()).anySatisfy(section -> assertThat(section.getLabel()).isEqualTo("Record Tags"));
      assertThat(instance.getPossibleValueSource(CollaborationTables.TAG).getTableName()).isEqualTo(CollaborationTables.TAG);
      QFrontendStepMetaData pick = (QFrontendStepMetaData) instance.getProcess("collaborationAddTags_document").getStep("tags");
      assertThat(pick.getFormFields().getFirst().getPossibleValueSourceName()).isEqualTo(CollaborationTables.TAG);
      assertThat(pick.getComponents().getFirst().getValues().get("multiSelectFields")).isEqualTo(List.of("tags"));
      assertThat(CollaborationSchema.postgresScript(config)).contains("ON collaboration_tag (table_name, name) NULLS NOT DISTINCT", "ON collaboration_record_tag (table_name, record_id, tag_id)", "usage_count INTEGER NOT NULL", "approved BOOLEAN NOT NULL");
      assertThatThrownBy(() -> config.withDefaultTagMode("INVALID").validate(instance)).hasMessageContaining("defaultTagMode");
      config.withDefaultTagMode("FREE").withTagActionTables(List.of("missing"));
      assertThatThrownBy(() -> config.validate(instance)).hasMessageContaining("host table");
   }

   /*******************************************************************************
    ** Bulk actions execute genuine Review and Result steps with idempotent counts.
    *******************************************************************************/
   @Test
   void bulkActionsReviewAndResult() throws Exception
   {
      TagProcesses.processesFor("document").forEach(instance::addProcess);
      TagEntry a = tags.create("document", "A", null);
      TagEntry b = tags.create("document", "B", null);
      tags.apply("document", 1, List.of(a.id(), b.id()), List.of());
      RunProcessOutput review = review("collaborationAddTags_document", "1,2", Map.of(TagProcesses.TAGS, new ArrayList<>(List.of(a.id(), b.id()))));
      assertThat(lines(review, false)).anySatisfy(line -> { assertThat(line.getCount()).isEqualTo(2); assertThat(line.getPluralFutureMessage()).contains("Add 2 tags"); });
      assertThat(lines(review, false)).anySatisfy(line -> { assertThat(line.getCount()).isEqualTo(1); assertThat(line.getPluralFutureMessage()).contains("already"); });
      assertThat(tags.tagsFor("document", 2)).isEmpty();
      assertThat(lines(finish(review), true)).anySatisfy(line -> assertThat(line.getCount()).isEqualTo(2));
      assertThat(tags.tagsFor("document", 2)).hasSize(2);
      finish(review("collaborationRemoveTags_document", "1,2", Map.of(TagProcesses.TAGS, a.id() + "," + b.id())));
      assertThat(rows(CollaborationTables.RECORD_TAG)).isEmpty();
   }

   /*******************************************************************************
    ** Management Review counts affected records; Result performs each operation.
    *******************************************************************************/
   @Test
   void managementActionsReviewAndResult() throws Exception
   {
      config.withDefaultTagMode("APPROVED_ONLY");
      TagEntry pending = tags.create("document", "Pending", null);
      login("manager", TagsService.MANAGE);
      RunProcessOutput approve = review("collaborationApproveTags", pending.id().toString(), Map.of());
      assertThat(lines(approve, false)).anySatisfy(line -> assertThat(line.getPluralFutureMessage()).contains("Approve"));
      finish(approve);
      tags.apply("document", 1, List.of(pending.id()), List.of());
      TagEntry target = tags.create("document", "Target", null);
      RunProcessOutput merge = review("collaborationMergeTags", pending.id().toString(), Map.of(TagProcesses.TARGET, target.id()));
      finish(merge);
      assertThat(tags.tagsFor("document", 1)).extracting(TagEntry::id).containsExactly(target.id());
      RunProcessOutput delete = review("collaborationDeleteTags", target.id().toString(), Map.of());
      assertThat(lines(delete, false)).anySatisfy(line -> { assertThat(line.getCount()).isEqualTo(1); assertThat(line.getPluralFutureMessage()).contains("lose tag assignments"); });
      assertThat(lines(finish(delete), true)).anySatisfy(line -> assertThat(line.getPluralPastMessage()).contains("processed"));
      assertThat(tags.tagsFor("document", 1)).isEmpty();
   }

   /*******************************************************************************
    ** Persist one scoped feature policy as a trusted fixture setup.
    *******************************************************************************/
   private void setting(String scope, String name, String mode) throws Exception
   {
      CollaborationRecords.insert(CollaborationTables.FEATURE_SETTING, new QRecord().withValue("scopeType", scope).withValue("scopeName", name)
         .withValue("feature", "TAGS").withValue("enabled", true).withValue("settings", "{\"mode\":\"" + mode + "\"}"));
   }

   /*******************************************************************************
    ** Inspect persistence to assert no hidden writes or duplicate activity.
    *******************************************************************************/
   private List<QRecord> rows(String table) throws Exception
   {
      return (CollaborationRecords.query(table, new QQueryFilter()));
   }

   /*******************************************************************************
    ** Supply picker values and stop at the genuine standard Review screen.
    *******************************************************************************/
   private RunProcessOutput review(String name, String records, Map<String, Serializable> values) throws Exception
   {
      new QInstanceValidator().validate(instance);
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(name);
      input.addValue("testProcessName", name);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      input.addValue("recordsParam", "recordIds");
      input.addValue("recordIds", records);
      values.forEach(input::addValue);
      if(instance.getProcess(name).getStep("tags") != null)
      {
         RunProcessOutput picker = new RunProcessAction().execute(input);
         assertThat(picker.getException()).isEmpty();
         input.setProcessUUID(picker.getProcessUUID());
         input.setStartAfterStep("tags");
      }
      RunProcessOutput output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      return (output);
   }

   /*******************************************************************************
    ** Resume the stored process state after Review.
    *******************************************************************************/
   private RunProcessOutput finish(RunProcessOutput review) throws Exception
   {
      RunProcessInput go = new RunProcessInput();
      go.setProcessName((String) review.getValues().get("testProcessName"));
      go.setProcessUUID(review.getProcessUUID());
      go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
      go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput result = new RunProcessAction().execute(go);
      assertThat(result.getException()).isEmpty();
      return (result);
   }

   /*******************************************************************************
    ** Summary values are supplied by the streamed process engine.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private List<ProcessSummaryLine> lines(RunProcessOutput output, boolean result)
   {
      return ((List<ProcessSummaryLine>) output.getValues().get(result ? StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY : StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY));
   }
   /*******************************************************************************
    ** JSON clients send ids as Integer, Long, BigDecimal or String; all select by id.
    *******************************************************************************/
   @Test
   void translatorAcceptsEveryIdRepresentation() throws Exception
   {
      TagEntry a = tags.create("document", "A", null);
      TagEntry b = tags.create("document", "B", null);
      TagEntry numeric = tags.create("document", "77", null);
      tags.apply("document", 1, List.of(a.id(), b.id()), List.of());
      tags.apply("document", 2, List.of(a.id()), List.of());
      for(java.io.Serializable idA : List.<java.io.Serializable>of(a.id(), Long.valueOf(a.id()), new java.math.BigDecimal(a.id()), String.valueOf(a.id())))
      {
         assertThat(recordIds("document", "IN", List.of(idA))).containsExactly("1", "2");
         assertThat(recordIds("document", "EQUALS", List.of(idA))).containsExactly("1", "2");
         assertThat(recordIds("document", "NOT_IN", List.of(idA))).isEmpty();
      }
      for(java.io.Serializable idB : List.<java.io.Serializable>of(b.id(), Long.valueOf(b.id()), new java.math.BigDecimal(b.id()), String.valueOf(b.id())))
      {
         assertThat(recordIds("document", "IN", List.of(idB))).containsExactly("1");
         assertThat(recordIds("document", "NOT_IN", List.of(idB))).containsExactly("2");
         assertThat(recordIds("document", "CONTAINS_ALL", List.of(String.valueOf(a.id()), idB))).containsExactly("1");
      }
      assertThat(recordIds("document", "CONTAINS_ALL", List.of(new java.math.BigDecimal(a.id()), Long.valueOf(b.id())))).containsExactly("1");
      assertThat(recordIds("document", "IN", List.of("9999"))).isEmpty();
      tags.apply("document", 2, List.of(numeric.id()), List.of());
      assertThat(recordIds("document", "IN", List.of(String.valueOf(numeric.id())))).containsExactly("2");
      assertThat(recordIds("document", "IN", List.of("77"))).containsExactly("2");
   }

   /*******************************************************************************
    ** Execute expanded criteria through the real parent query action.
    *******************************************************************************/
   private List<String> recordIds(String table, String operator, java.util.Collection<? extends java.io.Serializable> values) throws Exception
   {
      var metadata = instance.getTable(table);
      com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator op;
      try
      {
         op = com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.valueOf(operator);
      }
      catch(IllegalArgumentException e)
      {
         throw new IllegalArgumentException("Unsupported operator");
      }
      var filter = new com.kingsrook.qbits.collaboration.tags.TagCriteriaExpander().expand(metadata,
         new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria("collaborationTags", op,
            new java.util.ArrayList<java.io.Serializable>(values == null ? List.of() : values)), null);
      return (new QueryAction().execute(new QueryInput(table).withFilter(filter)).getRecords().stream().map(row -> row.getValueString("id")).toList());
   }
}
