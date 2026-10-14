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
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfigValidationException;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Phase-a metadata, real data, security and schema acceptance tests.
 *******************************************************************************/
class CollaborationTest
{
   private QInstance instance;
   private CollaborationQBitConfig config;



   /*******************************************************************************
    ** Fresh memory-backed instance for each test.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      MemoryRecordStore.getInstance().reset();
      instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.setEnforcePermissionsInCoreActions(true);
      config = new CollaborationQBitConfig().withBackendName("memory");
      new CollaborationQBitProducer().withQBitConfig(config).produce(instance);
      QContext.init(instance, new QSession().withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference("admin")).withPermissions(CollaborationPermissions.ADMIN));
   }



   /*******************************************************************************
    ** Release context and memory data.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      MemoryRecordStore.getInstance().reset();
      QContext.clear();
   }



   /*******************************************************************************
    ** Complete metadata validates and publishes the intended app and tables.
    *******************************************************************************/
   @Test
   void metadataValidates() throws Exception
   {
      new QInstanceValidator().validate(instance);
      assertThat(instance.getTables()).containsKeys(CollaborationTables.FEATURE_SETTING, CollaborationTables.USER);
      assertThat(instance.getPossibleValueSources()).containsKeys(CollaborationTables.FEATURE_SETTING, CollaborationTables.USER,
         CollaborationTables.SCOPE_TYPE, CollaborationTables.FEATURE, CollaborationTables.PERMISSION_MODE);
      assertThat(instance.getPossibleValueSource(CollaborationTables.FEATURE).getEnumValues()).hasSize(12);
      assertThat(instance.getApp("collaboration").getChildren()).extracting(child -> child.getName())
         .containsExactlyInAnyOrder(CollaborationTables.COMMENT, CollaborationTables.ATTACHMENT, CollaborationTables.TAG, CollaborationTables.FIELD_DEFINITION, CollaborationTables.TASK, CollaborationTables.SHARE, CollaborationTables.ACCESS_REQUEST, CollaborationTables.RECORD_ASSIGNMENT, CollaborationTables.ACTIVITY, CollaborationTables.FEATURE_SETTING, CollaborationTables.USER);
      assertThat(instance.getApp("collaboration").getWidgets()).containsExactlyElementsOf(CollaborationHome.HOME_WIDGETS);
      for(String widget : CollaborationHome.HOME_WIDGETS)
      {
         assertThat(instance.getWidget(widget)).as(widget).isNotNull();
         assertThat(instance.getWidget(widget).getGridColumns()).isEqualTo(12);
         assertThat(instance.getWidget(widget).getIsCard()).isFalse();
      }
      assertThat(instance.getWidget(CollaborationHome.KPI_WIDGET).getType()).isEqualTo("multiStatistics");
      assertThat(instance.getWidget(CollaborationHome.TAGS_WIDGET).getType()).isEqualTo("table");
      assertThat(instance.getWidget(CollaborationHome.FIELDS_WIDGET).getType()).isEqualTo("table");
      assertThat(instance.getWidget(CollaborationHome.PRESENCE_WIDGET).getType()).isEqualTo("table");
      assertThat(instance.getTable(CollaborationTables.FEATURE_SETTING).getAuditRules().getAuditLevel()).isEqualTo(AuditLevel.FIELD);
      assertThat(instance.getTable(CollaborationTables.FEATURE_SETTING).getUniqueKeys().getFirst().getFieldNames()).containsExactly("scopeType", "scopeName", "feature");
      assertThat(instance.getTable(CollaborationTables.USER).getUniqueKeys().getFirst().getFieldNames()).containsExactly("userId");
      assertThat(instance.getTable(CollaborationTables.USER).getSections()).extracting(section -> section.getName()).contains("identity", "details", "dates");
      assertThat(instance.getQBits()).hasSize(1);
   }



   /*******************************************************************************
    ** Required backend, defaults, setters, identity and optional placement.
    *******************************************************************************/
   @Test
   void configAndProducer() throws Exception
   {
      assertThat(new CollaborationQBitProducer().isEnabled()).isFalse();
      CollaborationQBitProducer producer = new CollaborationQBitProducer().withQBitConfig(config).withNamespace("collaboration");
      assertThat(producer.isEnabled()).isTrue();
      assertThat(producer.getQBitConfig()).isSameAs(config);
      assertThat(producer.getNamespace()).isEqualTo("collaboration");
      assertThat(producer.getQBitMetaData().getVersion()).isEqualTo("0.1.0");
      assertThat(producer.getQBitMetaData().getArtifactId()).isEqualTo("qbit-collaboration");
      assertThat(config.getDefaultBackendNameForTables()).isEqualTo("memory");
      assertThat(config.getAdminApp()).isTrue();
      assertThat(config.getSchedulerName()).isNull();
      assertThatThrownBy(() -> new CollaborationQBitConfig().validate(instance)).isInstanceOf(QBitConfigValidationException.class);
      assertThatThrownBy(() -> config.withBackendName("missing").validate(instance)).isInstanceOf(QBitConfigValidationException.class).hasMessageContaining("Unknown Collaboration backend");
      config.setBackendName("memory");
      config.setSchedulerName("scheduler");
      assertThat(config.getSchedulerName()).isEqualTo("scheduler");
      config.withSchedulerName(null);
      config.setAppName("teamwork");
      config.setParentAppName("admin");
      assertThatThrownBy(() -> config.validate(instance)).isInstanceOf(QBitConfigValidationException.class).hasMessageContaining("parent app");
      instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.addApp(new QAppMetaData().withName("admin").withLabel("Admin").withIcon(new QIcon("settings")));
      producer.produce(instance);
      new QInstanceValidator().validate(instance);
      assertThat(instance.getApp("teamwork").getParentAppName()).isEqualTo("admin");
      assertThat(instance.getApp("admin").getChildren()).extracting(child -> child.getName()).contains("teamwork");
      assertThatThrownBy(() -> config.withAppName(" ").validate(instance)).isInstanceOf(QBitConfigValidationException.class);
      config.withAppName("collaboration").withParentAppName(null);
      config.setAdminApp(false);
      assertThat(config.getAdminApp()).isFalse();
      config.withAdminApp(null).validate(instance);
   }



   /*******************************************************************************
    ** Without a parent app, Collaboration is a top-level node of the frontend app tree.
    *******************************************************************************/
   @Test
   void appTreeTopLevelWithoutParent() throws Exception
   {
      new QInstanceValidator().validate(instance);
      var output = new com.kingsrook.qqq.backend.core.actions.metadata.MetaDataAction().execute(new com.kingsrook.qqq.backend.core.model.actions.metadata.MetaDataInput());
      assertThat(output.getApps()).containsKey("collaboration");
      assertThat(output.getAppTree()).extracting(node -> node.getName()).contains("collaboration");
   }



   /*******************************************************************************
    ** With a parent app, Collaboration is nested under it and not duplicated at the top.
    *******************************************************************************/
   @Test
   void appTreeNestedUnderParent() throws Exception
   {
      instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.setEnforcePermissionsInCoreActions(true);
      instance.addApp(new QAppMetaData().withName("admin").withLabel("Admin").withIcon(new QIcon("settings")));
      new CollaborationQBitProducer().withQBitConfig(new CollaborationQBitConfig().withBackendName("memory").withParentAppName("admin")).produce(instance);
      QContext.init(instance, new QSession().withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference("admin")).withPermissions(CollaborationPermissions.ADMIN));
      new QInstanceValidator().validate(instance);
      var output = new com.kingsrook.qqq.backend.core.actions.metadata.MetaDataAction().execute(new com.kingsrook.qqq.backend.core.model.actions.metadata.MetaDataInput());
      assertThat(output.getAppTree()).extracting(node -> node.getName()).containsExactly("admin");
      assertThat(output.getAppTree().getFirst().getChildren()).extracting(node -> node.getName()).contains("collaboration");
   }



   /*******************************************************************************
    ** Headless hosts keep the tables without app or widgets.
    *******************************************************************************/
   @Test
   void headless() throws Exception
   {
      QInstance headless = new QInstance();
      headless.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      new CollaborationQBitProducer().withQBitConfig(config.withAdminApp(false)).produce(headless);
      assertThat(headless.getTables()).hasSize(19);
      assertThat(headless.getApps()).isEmpty();
      assertThat(headless.getWidgets()).hasSize(5);
   }



   /*******************************************************************************
    ** Home tables leave column widths to the frontend, so narrow cards wrap
    ** instead of scrolling sideways (issue #21).
    *******************************************************************************/
   @Test
   void homeTablesHaveNoFixedColumnWidths() throws Exception
   {
      for(TableData table : java.util.List.of(CollaborationHome.tags(), CollaborationHome.fields(), CollaborationHome.presence()))
      {
         assertThat(table.getColumns()).isNotEmpty().allSatisfy(column -> assertThat(column.getWidth()).isNull());
      }
   }



   /*******************************************************************************
    ** Empty states explain what has not been populated.
    *******************************************************************************/
   @Test
   void emptyHome() throws Exception
   {
      assertThat(HomeWidgetData.stats(Instant.now())).containsExactly(
         java.util.Map.entry("Comments this week", 0), java.util.Map.entry("Open threads", 0), java.util.Map.entry("Open tasks", 0),
         java.util.Map.entry("Overdue tasks", 0), java.util.Map.entry("Open access requests", 0), java.util.Map.entry("Attachments", 0),
         java.util.Map.entry("Active custom fields", 0));
      assertThat(CollaborationHome.tags().getRows()).isEmpty();
      assertThat(CollaborationHome.tags().getNoRowsFoundHTML()).isEqualTo(CollaborationHome.NO_TAGS);
      assertThat(CollaborationHome.fields().getRows()).isEmpty();
      assertThat(CollaborationHome.fields().getNoRowsFoundHTML()).isEqualTo(CollaborationHome.NO_FIELDS);
      assertThat(CollaborationHome.presence().getRows()).isEmpty();
      assertThat(CollaborationHome.presence().getNoRowsFoundHTML()).isEqualTo("Nobody else is here right now.");
      assertThat(((TableData) new CollaborationHome.PresenceRenderer().render(new RenderWidgetInput()).getWidgetData()).getNoRowsFoundHTML()).isEqualTo(CollaborationHome.NOBODY_HERE);
      assertThat(new CollaborationHome.KpiRenderer().render(new RenderWidgetInput()).getWidgetData()).isInstanceOf(MultiStatisticsData.class);
      assertThat(new CollaborationHome.TagsRenderer().render(new RenderWidgetInput()).getWidgetData()).isInstanceOf(TableData.class);
      assertThat(new CollaborationHome.FieldsRenderer().render(new RenderWidgetInput()).getWidgetData()).isInstanceOf(TableData.class);
      assertThat(HomeWidgetData.subheader(CollaborationHome.kpis(Instant.now()), "Work")).isEqualTo("Nothing waiting");
   }



   /*******************************************************************************
    ** Display names resolve in one query; unknown, blank and null ids stay readable.
    *******************************************************************************/
   @Test
   void displayNamesResolveInBatches() throws Exception
   {
      seed(CollaborationTables.USER, new QRecord().withValue("userId", "ann").withValue("displayName", "Ann Lee").withValue("active", true),
         new QRecord().withValue("userId", "nameless").withValue("displayName", " ").withValue("active", true));
      var names = new com.kingsrook.qbits.collaboration.people.PeopleService().displayNames(java.util.Arrays.asList("ann", "nameless", "ghost", null, "", "ann"));
      assertThat(names).containsExactly(java.util.Map.entry("ann", "Ann Lee"), java.util.Map.entry("nameless", "nameless"), java.util.Map.entry("ghost", "ghost"));
      assertThat(new com.kingsrook.qbits.collaboration.people.PeopleService().displayNames(List.of())).isEmpty();
      assertThat(instance.getTable(CollaborationTables.TASK).getField("assigneeUserId").getPossibleValueSourceName()).isEqualTo(CollaborationTables.USER);
      assertThat(instance.getTable(CollaborationTables.COMMENT).getField("authorUserId").getPossibleValueSourceName()).isEqualTo(CollaborationTables.USER);
      assertThat(instance.getTable(CollaborationTables.ATTACHMENT).getField("createdByUserId").getPossibleValueSourceName()).isEqualTo(CollaborationTables.USER);
      assertThat(instance.getTable(CollaborationTables.USER).getField("userId").getPossibleValueSourceName()).isNull();
      assertThat(instance.getPossibleValueSource(CollaborationTables.USER).getOverrideIdField()).isEqualTo("userId");
   }



   /*******************************************************************************
    ** Tags and custom fields list real rows with table labels, All tables for global ones.
    *******************************************************************************/
   @Test
   void tablesListRealRows() throws Exception
   {
      seed(CollaborationTables.TAG,
         new QRecord().withValue("name", "global").withValue("label", "Global").withValue("approved", true).withValue("createdByUserId", "admin").withValue("usageCount", 3),
         new QRecord().withValue("name", "popular").withValue("label", "Popular").withValue("approved", true).withValue("createdByUserId", "admin").withValue("usageCount", 5));
      var tags = CollaborationHome.tags();
      assertThat(tags.getColumns()).extracting(TableData.Column::getHeader).containsExactly("Label", "Table", "Records");
      assertThat(tags.getRows()).hasSize(2);
      assertThat(tags.getRows().get(0)).containsEntry("label", "Popular").containsEntry("table", "All tables").containsEntry("records", 5);
      assertThat(tags.getRows().get(1)).containsEntry("label", "Global").containsEntry("table", "All tables").containsEntry("records", 3);
   }



   /*******************************************************************************
    ** Enforced core actions use full custom permissions for reads and writes.
    *******************************************************************************/
   @Test
   void permissions() throws Exception
   {
      QContext.getQSession().setPermissions(new java.util.HashSet<>());
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(CollaborationTables.USER))).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> CollaborationHome.kpis(Instant.now())).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().withPermissions(CollaborationPermissions.VIEW);
      new QueryAction().execute(new QueryInput(CollaborationTables.FEATURE_SETTING));
      new QueryAction().execute(new QueryInput(CollaborationTables.USER));
      assertThatThrownBy(() -> new InsertAction().execute(new InsertInput(CollaborationTables.FEATURE_SETTING).withRecords(List.of(setting("INSTANCE", "instance", "TAGS", true)))))
         .isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(CollaborationTables.FEATURE_SETTING).withRecords(List.of(new QRecord().withValue("id", 1)))))
         .isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> new DeleteAction().execute(new DeleteInput(CollaborationTables.FEATURE_SETTING).withPrimaryKeys(List.of(1))))
         .isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().withPermissions(CollaborationPermissions.ADMIN);
      new InsertAction().execute(new InsertInput(CollaborationTables.FEATURE_SETTING).withRecords(List.of(setting("INSTANCE", "instance", "TAGS", true))));
      assertThatThrownBy(() -> new InsertAction().execute(new InsertInput(CollaborationTables.USER).withRecords(List.of(person("denied", Instant.now())))))
         .isInstanceOf(QPermissionDeniedException.class);
      assertThat(CollaborationPermissions.FIELDS_MANAGE).isEqualTo("collaboration.fields.manage");
      assertThat(CollaborationPermissions.TAGS_MANAGE).isEqualTo("collaboration.tags.manage");
   }



   /*******************************************************************************
    ** Widget counts honor host-added row locks and enforce even with core default off.
    *******************************************************************************/
   @Test
   void homeUsesCurrentSession() throws Exception
   {
      Instant now = Instant.now();
      seed(CollaborationTables.TAG, new QRecord().withValue("name", "visible").withValue("label", "Visible").withValue("approved", true).withValue("createdByUserId", "admin"),
         new QRecord().withValue("name", "hidden").withValue("label", "Hidden").withValue("approved", true).withValue("createdByUserId", "admin"));
      instance.addSecurityKeyType(new QSecurityKeyType().withName("person").withAllAccessKeyName("allPeople"));
      instance.getTable(CollaborationTables.TAG).withRecordSecurityLock(new RecordSecurityLock().withSecurityKeyType("person").withFieldName("name"));
      QContext.init(instance, new QSession().withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference("viewer")).withPermissions(CollaborationPermissions.VIEW).withSecurityKeyValue("person", "visible"));
      assertThat(CollaborationHome.tags().getRows()).singleElement().satisfies(row -> assertThat(row).containsEntry("label", "Visible"));
      assertThat(CollaborationHome.presence().getNoRowsFoundHTML()).isEqualTo(CollaborationHome.PRESENCE_ADMIN_ONLY);
      instance.setEnforcePermissionsInCoreActions(false);
      QContext.getQSession().setPermissions(new java.util.HashSet<>());
      assertThatThrownBy(() -> new CollaborationHome.KpiRenderer().render(new RenderWidgetInput())).isInstanceOf(QPermissionDeniedException.class);
   }



   /*******************************************************************************
    ** DDL uses metadata names, field lengths, identity, timestamps and unique keys.
    *******************************************************************************/
   @Test
   void schema()
   {
      String ddl = CollaborationSchema.postgresScript(config);
      assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS collaboration_feature_setting", "CREATE TABLE IF NOT EXISTS collaboration_user",
         "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY", "scope_name VARCHAR(128) NOT NULL", "settings TEXT", "enabled BOOLEAN NOT NULL",
         "last_seen_at TIMESTAMPTZ", "create_date TIMESTAMPTZ", "modify_date TIMESTAMPTZ", "avatar_url VARCHAR(2048)",
         "CREATE UNIQUE INDEX IF NOT EXISTS uq_collaboration_feature_setting_1 ON collaboration_feature_setting (scope_type, scope_name, feature)",
         "CREATE UNIQUE INDEX IF NOT EXISTS uq_collaboration_user_1 ON collaboration_user (user_id)");
   }



   /*******************************************************************************
    ** Insert fixture rows as trusted service work, retaining query enforcement.
    *******************************************************************************/
   private void seed(String table, QRecord... records) throws Exception
   {
      InsertInput input = new InsertInput(table).withRecords(List.of(records));
      input.withPermissionBypass("Test fixture simulates the future directory service");
      assertThat(new InsertAction().execute(input).getRecords()).allSatisfy(record -> assertThat(record.getErrors()).isNullOrEmpty());
   }



   /*******************************************************************************
    ** One feature setting.
    *******************************************************************************/
   private QRecord setting(String scope, String name, String feature, boolean enabled)
   {
      return (new QRecord().withValue("scopeType", scope).withValue("scopeName", name).withValue("feature", feature).withValue("enabled", enabled));
   }



   /*******************************************************************************
    ** One directory person.
    *******************************************************************************/
   private QRecord person(String id, Instant seen)
   {
      return (new QRecord().withValue("userId", id).withValue("displayName", id).withValue("lastSeenAt", seen).withValue("active", true));
   }
}
