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


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import com.kingsrook.qbits.collaboration.app.CollaborationSetupStatus;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.FeaturePermissionMode;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The "Create rows from configuration" defaults for collaboration_feature_setting.
 ** Main claim: writing the rows changes nothing. For several configurations
 ** (default, table features, app and table declarations, permission modes,
 ** settings, two apps containing one table), every table resolves to exactly the
 ** same enabled flag, permission mode and settings with and without the rows.
 *******************************************************************************/
class FeatureSettingsDefaultsTest
{
   private QInstance instance;
   private CollaborationQBitConfig config;



   /*******************************************************************************
    ** Fresh instance: a backend, three host tables, two apps, then the qbit.
    ** `code` runs after the qbit is produced and declares features on apps/tables.
    *******************************************************************************/
   private void build(Consumer<CollaborationQBitConfig> configure, Consumer<QInstance> code) throws Exception
   {
      MemoryRecordStore.getInstance().reset();
      instance = new QInstance().withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.setEnforcePermissionsInCoreActions(true);
      for(String name : List.of("document", "invoice", "receipt"))
      {
         instance.addTable(new QTableMetaData().withName(name).withLabel(name.substring(0, 1).toUpperCase() + name.substring(1) + "s").withBackendName("memory").withPrimaryKeyField("id")
            .withField(new QFieldMetaData("id", QFieldType.INTEGER)).withField(new QFieldMetaData("title", QFieldType.STRING)).withRecordLabelFormatAndFields("%s", "title"));
      }
      instance.addApp(new QAppMetaData().withName("sales").withLabel("Sales").withIcon(new QIcon("sell")).withSection(new QAppSection().withName("s").withLabel("S").withTables(List.of("invoice", "receipt"))));
      instance.addApp(new QAppMetaData().withName("ops").withLabel("Ops").withIcon(new QIcon("build")).withSection(new QAppSection().withName("o").withLabel("O").withTables(List.of("receipt", "document"))));
      config = new CollaborationQBitConfig().withBackendName("memory");
      configure.accept(config);
      new CollaborationQBitProducer().withQBitConfig(config).produce(instance);
      code.accept(instance);
      new com.kingsrook.qqq.backend.core.instances.QInstanceEnricher(instance).enrich();
      admin();
      RuntimeMetaDataOverlay.refresh(instance);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void admin()
   {
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("admin")).withPermissions(CollaborationPermissions.ADMIN));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      com.kingsrook.qbits.collaboration.presence.PresenceRegistry.close(instance);
      com.kingsrook.qqq.backend.core.realtime.RealtimeBus.close(instance);
      QContext.clear();
      MemoryRecordStore.getInstance().reset();
   }



   /*******************************************************************************
    ** What every host table resolves to for every feature: enabled, permission mode,
    ** settings (with the TAGS mode as TagsService reads it).
    *******************************************************************************/
   private Map<String, String> effective()
   {
      Map<String, String> rs = new TreeMap<>();
      for(String table : List.of("document", "invoice", "receipt"))
      {
         var resolved = RecordFeaturesResolver.resolve(instance, instance.getTable(table));
         for(RecordFeature feature : RecordFeature.values())
         {
            var one = resolved.getFeatures().get(feature);
            Map<String, java.io.Serializable> settings = new TreeMap<>(one.getSettings());
            if(feature == RecordFeature.TAGS)
            {
               ///////////////////////////////////////////////////////////////////////////
               // TagsService reads a missing mode as the configured default tag mode, //
               // so a row that spells the default out is the same effective setting.   //
               ///////////////////////////////////////////////////////////////////////////
               settings.putIfAbsent("mode", config.getDefaultTagMode());
            }
            rs.put(table + "." + feature, one.getEnabled() + " " + one.getPermissionMode() + " " + settings);
         }
      }
      return (rs);
   }



   /*******************************************************************************
    ** Run the real "Create rows from configuration" process end to end.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private List<ProcessSummaryLine> create() throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(FeatureSettingsDefaultsProcess.NAME);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput output = new RunProcessAction().execute(input);
      return ((List<ProcessSummaryLine>) output.getValues().get(com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<QRecord> rows() throws Exception
   {
      return (new QueryAction().execute(new QueryInput(CollaborationTables.FEATURE_SETTING).withFilter(new QQueryFilter())).getRecords());
   }



   /*******************************************************************************
    ** The configurations: defaults; table features with GRANTED_ONLY and a table
    ** TAGS mode; an app that turns attachments on and sets the sharing approver;
    ** a different default tag mode and default features; two apps with one table.
    *******************************************************************************/
   private void scenario(int number) throws Exception
   {
      switch(number)
      {
         case 0 -> build(c -> { }, i -> { });
         case 1 -> build(c -> c.withDefaultFeatures(RecordFeature.COMMENTS, RecordFeature.TAGS).withTableFeatures("document", RecordFeature.TAGS, RecordFeature.TASKS), i ->
         {
            RecordFeaturesMetaData.ofOrWithNew(i.getTable("invoice")).withPermissionMode(RecordFeature.SHARING, FeaturePermissionMode.GRANTED_ONLY)
               .withSetting(RecordFeature.TAGS, "mode", "APPROVED_ONLY");
         });
         case 2 -> build(c -> c.withDefaultTagMode("APPROVED_ONLY"), i ->
         {
            RecordFeaturesMetaData.ofOrWithNew(i.getApp("sales")).withEnabled(RecordFeature.ATTACHMENTS, true).withSetting(RecordFeature.SHARING, "accessApproverPermission", "sales.admin");
            RecordFeaturesMetaData.ofOrWithNew(i.getTable("receipt")).withEnabled(RecordFeature.COMMENTS, true).withEnabled(RecordFeature.FAVORITES, false);
         });
         case 3 -> build(c -> c.withDefaultFeatures(), i ->
         {
            RecordFeaturesMetaData.ofOrWithNew(i.getApp("sales")).withEnabled(RecordFeature.TASKS, true).withPermissionMode(RecordFeature.TASKS, FeaturePermissionMode.GRANTED_ONLY);
            RecordFeaturesMetaData.ofOrWithNew(i.getApp("ops")).withEnabled(RecordFeature.TASKS, false);
            RecordFeaturesMetaData.ofOrWithNew(i.getApp("ops")).withSetting(RecordFeature.TAGS, "mode", "APPROVED_ONLY");
         });
         default -> throw new IllegalArgumentException();
      }
   }



   /*******************************************************************************
    ** The proof: for every scenario the effective feature set is identical with
    ** and without the seeded rows, and rows really were created.
    *******************************************************************************/
   @Test
   void seededRowsChangeNothing() throws Exception
   {
      for(int number = 0; number <= 3; number++)
      {
         scenario(number);
         Map<String, String> without = effective();
         assertThat(rows()).as("scenario " + number + " starts empty").isEmpty();

         List<ProcessSummaryLine> summary = create();
         assertThat(summary).as("scenario " + number).isNotEmpty();
         List<QRecord> rows = rows();
         assertThat(rows).as("scenario " + number + " rows").hasSizeGreaterThanOrEqualTo(RecordFeature.values().length);
         assertThat(rows.stream().filter(row -> "INSTANCE".equals(row.getValueString("scopeType")))).hasSize(RecordFeature.values().length);

         assertThat(effective()).as("scenario " + number + ": effective settings with the seeded rows").containsExactlyInAnyOrderEntriesOf(without);
         tearDown();
      }
   }



   /*******************************************************************************
    ** The default scenario in plain terms: favorites, recents and timeline are
    ** the only features on, TAGS carries the mode, everything else is off.
    *******************************************************************************/
   @Test
   void defaultRowsMirrorTheConfiguredDefaults() throws Exception
   {
      scenario(0);
      create();
      for(QRecord row : rows())
      {
         assertThat(row.getValueString("scopeType")).isEqualTo("INSTANCE");
         assertThat(row.getValueString("scopeName")).isEqualTo("instance");
         RecordFeature feature = RecordFeature.valueOf(row.getValueString("feature"));
         boolean on = List.of(RecordFeature.FAVORITES, RecordFeature.RECENTS, RecordFeature.TIMELINE).contains(feature);
         assertThat(row.getValueBoolean("enabled")).as(feature.name()).isEqualTo(on);
         assertThat(row.getValueString("permissionMode")).isEqualTo("EDIT_OR_GRANTED");
         assertThat(row.getValueString("settings")).isEqualTo(feature == RecordFeature.TAGS ? "{\"mode\":\"FREE\"}" : null);
      }
   }



   /*******************************************************************************
    ** Running it again creates nothing; an edit survives; and the rows are live:
    ** turning COMMENTS on in its row turns comments on for every host table.
    *******************************************************************************/
   @Test
   void insertOnlyAndTheRowsAreLive() throws Exception
   {
      scenario(0);
      create();
      int count = rows().size();
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).isEnabled(RecordFeature.COMMENTS)).isFalse();

      QRecord comments = rows().stream().filter(row -> "COMMENTS".equals(row.getValueString("feature"))).findFirst().orElseThrow();
      new UpdateAction().execute(new UpdateInput(CollaborationTables.FEATURE_SETTING).withRecords(List.of(new QRecord().withValue("id", comments.getValue("id")).withValue("enabled", true))));
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("document")).isEnabled(RecordFeature.COMMENTS)).as("the edited row drives the runtime").isTrue();
      assertThat(RecordFeaturesResolver.resolve(instance, instance.getTable("invoice")).isEnabled(RecordFeature.COMMENTS)).isTrue();

      List<ProcessSummaryLine> second = create();
      assertThat(second).singleElement().satisfies(line -> assertThat(line.getMessage()).contains("already has rows"));
      assertThat(rows()).hasSize(count);
      assertThat(rows().stream().filter(row -> "COMMENTS".equals(row.getValueString("feature"))).findFirst().orElseThrow().getValueBoolean("enabled")).isTrue();
   }



   /*******************************************************************************
    ** A table that already has an administrator's rows gets nothing added.
    *******************************************************************************/
   @Test
   void neverAddsToExistingRows() throws Exception
   {
      scenario(1);
      CollaborationRecords.insert(CollaborationTables.FEATURE_SETTING, new QRecord().withValue("scopeType", "INSTANCE").withValue("scopeName", "instance")
         .withValue("feature", "TASKS").withValue("enabled", true));
      assertThat(FeatureSettingsDefaults.missingRows(instance, config)).isEmpty();
      create();
      assertThat(rows()).hasSize(1);
   }



   /*******************************************************************************
    ** Only collaboration administrators may run it.
    *******************************************************************************/
   @Test
   void adminOnly() throws Exception
   {
      scenario(0);
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("viewer")).withPermissions(CollaborationPermissions.VIEW));
      assertThatThrownBy(() -> new FeatureSettingsDefaultsProcess.AdminOnly().checkPermissionsThrowing(null, null)).isInstanceOf(QPermissionDeniedException.class);
      assertThat(instance.getProcess(FeatureSettingsDefaultsProcess.NAME).getMinInputRecords()).isEqualTo(0);
      assertThat(instance.getProcess(FeatureSettingsDefaultsProcess.NAME).getTableName()).isEqualTo(CollaborationTables.FEATURE_SETTING);
   }



   /*******************************************************************************
    ** The Setup card reports the configuration, then the rows, and what is on where.
    *******************************************************************************/
   @Test
   void setupCardUsesTheRuntimeResolution() throws Exception
   {
      scenario(1);
      List<CollaborationSetupStatus.Item> before = CollaborationSetupStatus.load(instance);
      assertThat(before).hasSize(RecordFeature.values().length + 1);
      assertThat(before.getFirst().state()).isEqualTo("Using code defaults");
      assertThat(before.getFirst().nextStep()).contains("Create rows from configuration");
      assertThat(item(before, "Comments").state()).isEqualTo("On for 2 of 3 tables");
      assertThat(item(before, "Comments").where()).isEqualTo("Invoices, Receipts");
      assertThat(item(before, "Favorites").state()).isEqualTo("Off");
      assertThat(item(before, "Tasks").state()).isEqualTo("On for 1 of 3 tables");
      assertThat(item(before, "Tasks").where()).isEqualTo("Documents");
      assertThat(item(before, "Presence").state()).isEqualTo("Off");
      assertThat(item(before, "Presence").nextStep()).contains("Feature Settings");
      assertThat(item(before, "Tasks").source()).isEqualTo("Configuration");

      create();
      List<CollaborationSetupStatus.Item> after = CollaborationSetupStatus.load(instance);
      assertThat(after.getFirst().state()).isEqualTo("Set up");
      assertThat(item(after, "Tasks").source()).isEqualTo("Feature Settings");
      List<String> beforeStates = new ArrayList<>(before.stream().skip(1).map(CollaborationSetupStatus.Item::state).toList());
      assertThat(after.stream().skip(1).map(CollaborationSetupStatus.Item::state).toList()).as("the card reports the same thing before and after").isEqualTo(beforeStates);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static CollaborationSetupStatus.Item item(List<CollaborationSetupStatus.Item> items, String feature)
   {
      return (items.stream().filter(item -> feature.equals(item.feature())).findFirst().orElseThrow());
   }

}
