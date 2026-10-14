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

import java.math.BigDecimal;
import java.util.List;
import com.kingsrook.qbits.collaboration.customfields.BuildFieldIndexes;
import com.kingsrook.qbits.collaboration.customfields.CustomFieldTablesProvider;
import com.kingsrook.qbits.collaboration.customfields.FieldDefinitions;
import com.kingsrook.qbits.collaboration.customfields.FieldProcesses;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.ai.AiDataClassifications;
import com.kingsrook.qqq.backend.core.actions.ai.DataClassification;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.JsonPathStorage;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Exercise designer rules and storage through ordinary core actions.
 *******************************************************************************/
public class CustomFieldsTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Refreshing a SELECT must replace its generated vocabulary and allow later fields.
    *******************************************************************************/
   @Test
   void publishedSelectSurvivesRefreshAndOptionChanges() throws Exception
   {
      admin();
      QRecord choice = draft("contact", "SELECT");
      new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(
         new QRecord().withValue("id", choice.getValue("id")).withValue("options", "[{\"id\":\"email\",\"label\":\"Email\"}]"))));
      FieldDefinitions.apply(choice.getValue("id"), "Publish");
      QRecord reference = draft("reference", "STRING");
      FieldDefinitions.apply(reference.getValue("id"), "Publish");
      assertThat(instance.getTable("document").getFields()).containsKeys("cf_contact", "cf_reference");
      new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(
         new QRecord().withValue("id", choice.getValue("id")).withValue("options", "[{\"id\":\"email\",\"label\":\"Email\"},{\"id\":\"phone\",\"label\":\"Phone\"}]"))));
      RuntimeMetaDataOverlay.refresh(instance);
      String source = instance.getTable("document").getField("cf_contact").getPossibleValueSourceName();
      assertThat(instance.getPossibleValueSource(source).getEnumValues()).hasSize(2);
      assertThat(instance.getTable("document").getFields()).containsKeys("cf_contact", "cf_reference");
   }



   /*******************************************************************************
    ** The home lists definitions with table label, status and index state, and counts active ones.
    *******************************************************************************/
   @Test
   void homeListsDefinitionsAndCountsActiveOnes() throws Exception
   {
      admin();
      QRecord definition = draft("region", "STRING");
      var drafts = CollaborationHome.fields();
      assertThat(drafts.getColumns()).extracting(TableData.Column::getHeader).containsExactly("Name", "Table", "Status", "Index state");
      assertThat(drafts.getRows()).singleElement().satisfies(row -> assertThat(row).containsEntry("name", "region").containsEntry("table", "Documents")
         .containsEntry("status", "Draft").containsEntry("indexState", "None"));
      assertThat(HomeWidgetData.stats(java.time.Instant.now())).containsEntry("Active custom fields", 0);
      FieldDefinitions.apply(definition.getValue("id"), "Publish");
      assertThat(CollaborationHome.fields().getRows().getFirst()).containsEntry("status", "Active");
      assertThat(HomeWidgetData.stats(java.time.Instant.now())).containsEntry("Active custom fields", 1);
   }



   /*******************************************************************************
    ** Create a real draft through the permission and pre-insert customizers.
    *******************************************************************************/
   private QRecord draft(String name, String type) throws Exception
   {
      var result = new InsertAction().execute(new InsertInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(
         new QRecord().withValue("tableName", "document").withValue("name", name).withValue("label", name).withValue("type", type)))).getRecords().getFirst();
      assertThat(result.getErrors()).isNullOrEmpty();
      return (result);
   }

   /*******************************************************************************
    ** A published numeric JSON field is writable, filterable, validated and classified.
    *******************************************************************************/
   @Test
   void memoryFieldsUseNormalActionsAndPreserveClassification() throws Exception
   {
      admin();
      QRecord definition = draft("score", "INTEGER");
      var result = new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(
         new QRecord().withValue("id", definition.getValue("id")).withValue("minValue", 1).withValue("maxValue", 20).withValue("classification", "PHI")
            .withValue("sectionName", "scores")))).getRecords().getFirst();
      assertThat(result.getErrors()).isNullOrEmpty();
      FieldDefinitions.apply(definition.getValue("id"), "Publish");
      var field = instance.getTable("document").getField("cf_score");
      assertThat(field.getBackendStorage()).isInstanceOf(JsonPathStorage.class);
      assertThat(AiDataClassifications.ofField(instance.getTable("document"), "cf_score")).isEqualTo(DataClassification.PHI);
      assertThat(instance.getTable("document").getSections()).anySatisfy(section -> assertThat(section.getFieldNames()).contains("cf_score"));
      login("alice");
      var inserted = new InsertAction().execute(new InsertInput("document").withRecords(List.of(new QRecord().withValue("id", 4).withValue("tenant", "a")
         .withValue("title", "Fourth").withValue("cf_score", 7)))).getRecords().getFirst();
      assertThat(inserted.getErrors()).isNullOrEmpty();
      assertThat(query("cf_score", QCriteriaOperator.GREATER_THAN, 5)).extracting(row -> row.getValueInteger("id")).containsExactly(4);
      var updated = new UpdateAction().execute(new UpdateInput("document").withRecords(List.of(new QRecord().withValue("id", 4).withValue("cf_score", 12)))).getRecords().getFirst();
      assertThat(updated.getErrors()).isNullOrEmpty();
      assertThat(query("cf_score", QCriteriaOperator.EQUALS, 12)).hasSize(1);
      var invalid = new UpdateAction().execute(new UpdateInput("document").withRecords(List.of(new QRecord().withValue("id", 4).withValue("cf_score", 30)))).getRecords().getFirst();
      assertThat(invalid.getErrors()).isNotEmpty();
      admin();
      assertThat(FieldDefinitions.valueCount(FieldDefinitions.get(definition.getValue("id")))).isEqualTo(1);
      FieldDefinitions.apply(definition.getValue("id"), "Archive");
      assertThat(instance.getTable("document").getFields()).doesNotContainKey("cf_score");
      assertThat(FieldDefinitions.get(definition.getValue("id")).getValueString("status")).isEqualTo("ARCHIVED");
   }

   /*******************************************************************************
    ** Normal permissions and direct table operations cannot bypass lifecycle rules.
    *******************************************************************************/
   @Test
   void validationPermissionsAndTypeRules() throws Exception
   {
      assertThatThrownBy(() -> draft("denied", "STRING")).hasMessageContaining("collaboration.fields.manage");
      login("manager", "collaboration.fields.manage", "collaboration.view");
      QRecord row = draft("choice", "SELECT");
      var duplicate = new InsertAction().execute(new InsertInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(new QRecord()
         .withValue("tableName", "document").withValue("name", "choice").withValue("label", "Duplicate").withValue("type", "SELECT")))).getRecords().getFirst();
      assertThat(duplicate.getErrors()).isNotEmpty();
      assertThatThrownBy(() -> draft("Bad-name", "STRING")).hasMessageContaining("name");
      instance.getTable("document").withField(new QFieldMetaData("cf_clash", QFieldType.STRING));
      assertThatThrownBy(() -> draft("clash", "STRING")).hasMessageContaining("clash");
      instance.getTable("document").getFields().remove("cf_clash");
      assertThatThrownBy(() -> draft("link", "RECORD_LINK")).hasMessageContaining("possible value");
      assertThat(new CustomFieldTablesProvider().getPossibleValue("document")).isNotNull();
      assertThat(new CustomFieldTablesProvider().getPossibleValue("missing")).isNull();
      var changed = new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(new QRecord()
         .withValue("id", row.getValue("id")).withValue("type", "STRING")))).getRecords().getFirst();
      assertThat(changed.getErrors()).isNullOrEmpty();
      FieldDefinitions.apply(row.getValue("id"), "Publish");
      assertThatThrownBy(() -> FieldDefinitions.apply(row.getValue("id"), "Publish")).hasMessageContaining("DRAFT");
      assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(
         new QRecord().withValue("id", row.getValue("id")).withValue("type", "INTEGER"))))).hasMessageContaining("DRAFT");
      assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withRecords(List.of(
         new QRecord().withValue("id", row.getValue("id")).withValue("status", "ARCHIVED"))))).hasMessageContaining("actions");
      assertThatThrownBy(() -> new DeleteAction().execute(new DeleteInput(CollaborationTables.FIELD_DEFINITION).withPrimaryKeys(List.of(row.getValue("id"))))).hasMessageContaining("archive");
      assertThatThrownBy(() -> FieldDefinitions.validate(new QRecord(row).withValue("name", "renamed"), row)).hasMessageContaining("cannot change");
      assertThatThrownBy(() -> FieldDefinitions.options(new QRecord().withValue("options", "{}"))).hasMessageContaining("options");
      QRecord published = FieldDefinitions.get(row.getValue("id"));
      assertThatThrownBy(() -> FieldDefinitions.validate(new QRecord(published).withValue("classification", "BAD"), published)).hasMessageContaining("classification");
      assertThatThrownBy(() -> FieldDefinitions.validate(new QRecord(published).withValue("minValue", 5).withValue("maxValue", 1), published)).hasMessageContaining("Minimum");
   }

   /*******************************************************************************
    ** Definitions contribute all supported types, options, search and defaults.
    *******************************************************************************/
   @Test
   void contributionMappingsAndGeneration() throws Exception
   {
      admin();
      long before = RuntimeMetaDataOverlay.getGeneration(instance);
      for(String type : List.of("STRING", "TEXT", "DECIMAL", "BOOLEAN", "DATE", "DATE_TIME", "SELECT", "MULTI_SELECT", "USER", "RECORD_LINK"))
      {
         QRecord row = new QRecord().withValue("tableName", "document").withValue("name", type.toLowerCase()).withValue("label", type)
            .withValue("type", type).withValue("status", "ACTIVE").withValue("linkTableName", CollaborationTables.USER)
            .withValue("helpText", "Helpful text").withValue("maxLength", type.equals("STRING") ? 30 : null)
            .withValue("searchable", type.equals("STRING")).withValue("options", "[{\"id\":\"a\",\"label\":\"Alpha\"}]");
         CollaborationRecords.insert(CollaborationTables.FIELD_DEFINITION, row);
      }
      RuntimeMetaDataOverlay.refresh(instance);
      assertThat(RuntimeMetaDataOverlay.getGeneration(instance)).isGreaterThan(before);
      assertThat(instance.getTable("document").getField("cf_decimal").getType()).isEqualTo(QFieldType.DECIMAL);
      assertThat(instance.getTable("document").getField("cf_multi_select").getType()).isEqualTo(QFieldType.STRING);
      assertThat(instance.getTable("document").getSearchFields()).contains("cf_string");
      assertThat(instance.getTable("document").getField("cf_user").getPossibleValueSourceName()).isEqualTo(CollaborationTables.USER);
      assertThat(instance.getTable("document").getField("cf_select").getPossibleValueSourceName()).startsWith("collaboration_field_options_");
      QRecord draft = draft("transaction", "STRING");
      QBackendTransaction tx = new QBackendTransaction();
      before = RuntimeMetaDataOverlay.getGeneration(instance);
      new UpdateAction().execute(new UpdateInput(CollaborationTables.FIELD_DEFINITION).withTransaction(tx).withRecords(List.of(new QRecord()
         .withValue("id", draft.getValue("id")).withValue("label", "Updated"))));
      assertThat(RuntimeMetaDataOverlay.getGeneration(instance)).isEqualTo(before);
      tx.commit();
      assertThat(RuntimeMetaDataOverlay.getGeneration(instance)).isGreaterThan(before);
   }

   /*******************************************************************************
    ** Build migration SQL without opening a PostgreSQL connection or executing DDL.
    *******************************************************************************/
   @Test
   void managedFalseRecordsDdl() throws Exception
   {
      admin();
      QRecord row = draft("amount", "DECIMAL");
      row.withValue("filterable", true).withValue("status", "ACTIVE");
      var backend = new RDBMSBackendMetaData().withVendor("postgres").withName("postgres");
      instance.addBackend(backend);
      instance.getTable("document").setBackendName("postgres");
      config.withManagedIndexes(false);
      assertThat(config.getManagedIndexes()).isFalse();
      BuildFieldIndexes.build(row);
      QRecord stored = FieldDefinitions.get(row.getValue("id"));
      assertThat(stored.getValueString("indexState")).isEqualTo("NONE");
      assertThat(stored.getValueString("indexDdl")).contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS", "qqq_custom_fields->>'amount'", "::numeric");
      assertThat(BuildFieldIndexes.ddl(instance.getTable("document"), new QRecord(row).withValue("type", "STRING").withValue("searchable", true), true)).contains("USING gin", "gin_trgm_ops");
      assertThat(BuildFieldIndexes.ddl(instance.getTable("document"), new QRecord(row).withValue("status", "ARCHIVED"), false)).startsWith("DROP INDEX CONCURRENTLY");
      assertThat(BuildFieldIndexes.ddl(instance.getTable("document"), new QRecord(row).withValue("filterable", false), false)).isNull();
      assertThat(CollaborationSchema.customFieldsDdl("orders")).contains("ADD COLUMN IF NOT EXISTS qqq_custom_fields JSONB", "jsonb_path_ops");
      assertThat(FieldProcesses.metadata()).extracting(process -> process.getLabel()).containsExactly("Publish field", "Archive field", "Rebuild index");
   }

   /*******************************************************************************
    ** An unsupported JSON backend gets typed, read-only subquery filters.
    *******************************************************************************/
   @Test
   void eavFiltersAndSearch() throws Exception
   {
      admin();
      instance.addBackend(new RDBMSBackendMetaData().withName("fallback").withVendor("h2"));
      instance.getTable("document").setBackendName("fallback");
      QRecord definition = draft("rating", "INTEGER");
      FieldDefinitions.apply(definition.getValue("id"), "Publish");
      instance.getTable("document").setBackendName("memory");
      var virtual = instance.getTable("document").getVirtualField("cf_rating");
      assertThat(virtual).isNotNull();
      assertThat(virtual.getIsQuerySelectable()).isFalse();
      assertThat(virtual.getIsEditable()).isFalse();
      assertThat(FieldDefinitions.get(definition.getValue("id")).getValueString("storage")).isEqualTo("EAV");
      CollaborationRecords.insert(CollaborationTables.FIELD_VALUE, new QRecord().withValue("tableName", "document").withValue("recordId", "1")
         .withValue("fieldName", "rating").withValue("valueNumber", new BigDecimal("7")).withValue("parentDeleted", false));
      login("alice");
      assertThat(query("cf_rating", QCriteriaOperator.GREATER_THAN, 5)).extracting(row -> row.getValueInteger("id")).containsExactly(1);
      assertThat(query("cf_rating", QCriteriaOperator.NOT_EQUALS, 7)).extracting(row -> row.getValueInteger("id")).containsExactly(2);
      assertThat(new QueryAction().execute(new QueryInput("document").withFilter(new QQueryFilter().withCriteria("cf_rating", QCriteriaOperator.IS_BLANK))).getRecords())
         .extracting(row -> row.getValueInteger("id")).containsExactly(2);
      admin();
      assertThat(FieldDefinitions.valueCount(FieldDefinitions.get(definition.getValue("id")))).isEqualTo(1);
      var expander = new com.kingsrook.qbits.collaboration.customfields.FieldCriteriaExpander();
      assertThat(expander.expandSearchTerm(instance.getTable("document"), "text", com.kingsrook.qqq.backend.core.actions.tables.query.SearchTermKind.TEXT, null)).isNotNull();
      assertThat(com.kingsrook.qbits.collaboration.customfields.FieldCriteriaExpander.valueColumn("DATE")).isEqualTo("valueDate");
      assertThat(com.kingsrook.qbits.collaboration.customfields.FieldCriteriaExpander.valueColumn("DATE_TIME")).isEqualTo("valueDateTime");
      assertThat(com.kingsrook.qbits.collaboration.customfields.FieldCriteriaExpander.valueColumn("BOOLEAN")).isEqualTo("valueBoolean");
      assertThat(com.kingsrook.qbits.collaboration.customfields.FieldCriteriaExpander.valueColumn("STRING")).isEqualTo("valueText");
   }

   /*******************************************************************************
    ** Review and load execute the real lifecycle and retain failures in their summaries.
    *******************************************************************************/
   @Test
   void streamedActionsAndScheduledJob() throws Exception
   {
      admin();
      var row = draft("action", "STRING");
      var process = instance.getProcess("collaborationPublishField");
      var input = new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput().withProcessName(process.getName()).withRecords(List.of(row));
      var output = new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput();
      var review = new FieldProcesses.Review();
      review.runOnePage(input, output);
      assertThat(output.getRecords()).hasSize(1);
      assertThat(review.getProcessSummary(output, false)).hasSize(1);
      var apply = new FieldProcesses.Apply();
      apply.runOnePage(input, output);
      assertThat(apply.getProcessSummary(output, true)).hasSize(1);
      review.runOnePage(input, output);
      apply.runOnePage(input, output);
      assertThat(review.getProcessSummary(output, false)).hasSize(2);
      input.withProcessName("collaborationArchiveField");
      review.runOnePage(input, output);
      FieldDefinitions.apply(row.getValue("id"), "Rebuild");
      new BuildFieldIndexes().run(input, output);
      assertThat(BuildFieldIndexes.metadata(config.withSchedulerName("scheduler")).getSchedule()).isNotNull();
      config.withSchedulerName(null);
      config.setManagedIndexes(true);
      assertThat(config.getManagedIndexes()).isTrue();
      var draft = draft("delete", "STRING");
      new DeleteAction().execute(new DeleteInput(CollaborationTables.FIELD_DEFINITION).withPrimaryKeys(List.of(draft.getValue("id"))));
      assertThatThrownBy(() -> FieldDefinitions.get(draft.getValue("id"))).hasMessageContaining("Not found");
   }

   /*******************************************************************************
    ** Query paths remain ordinary filters; callers retain their record locks.
    *******************************************************************************/
   private List<QRecord> query(String field, QCriteriaOperator operator, java.io.Serializable value) throws Exception
   {
      return (new QueryAction().execute(new QueryInput("document").withFilter(new QQueryFilter().withCriteria(field, operator, value))).getRecords());
   }
}
