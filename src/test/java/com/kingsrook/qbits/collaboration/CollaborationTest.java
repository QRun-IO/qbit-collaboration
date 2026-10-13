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
import java.time.temporal.ChronoUnit;
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
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
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
         .containsExactlyInAnyOrder(CollaborationTables.COMMENT, CollaborationTables.ATTACHMENT, CollaborationTables.TAG, CollaborationTables.ACTIVITY, CollaborationTables.FEATURE_SETTING, CollaborationTables.USER);
      assertThat(instance.getApp("collaboration").getWidgets()).containsExactly(CollaborationHome.HOME_WIDGET);
      assertThat(instance.getWidget(CollaborationHome.HOME_WIDGET)).isNotNull();
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
    ** Headless hosts keep the tables without app or widgets.
    *******************************************************************************/
   @Test
   void headless() throws Exception
   {
      QInstance headless = new QInstance();
      headless.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      new CollaborationQBitProducer().withQBitConfig(config.withAdminApp(false)).produce(headless);
      assertThat(headless.getTables()).hasSize(13);
      assertThat(headless.getApps()).isEmpty();
      assertThat(headless.getWidgets()).hasSize(4);
   }



   /*******************************************************************************
    ** Empty states explain what has not been populated.
    *******************************************************************************/
   @Test
   void emptyHome() throws Exception
   {
      String html = CollaborationHome.render(Instant.now());
      assertThat(html).contains("No feature settings yet: features follow the code defaults", "People known: 0", "Active in the last 7 days: 0", "No people yet");
      RawHTML widget = (RawHTML) new CollaborationHome.HomeRenderer().render(new RenderWidgetInput()).getWidgetData();
      assertThat(widget.getHtml()).contains("People known: 0");
   }



   /*******************************************************************************
    ** Counts distinguish disabled settings and the inclusive seven-day window.
    *******************************************************************************/
   @Test
   void realCounts() throws Exception
   {
      Instant now = Instant.parse("2026-10-13T12:00:00Z");
      seed(CollaborationTables.FEATURE_SETTING,
         setting("INSTANCE", "instance", "COMMENTS", true), setting("APP", "sales", "TAGS", true),
         setting("TABLE", "orders", "TASKS", true), setting("TABLE", "orders", "COMMENTS", true),
         setting("TABLE", "orders", "PRESENCE", false));
      seed(CollaborationTables.USER, person("one", now), person("boundary", now.minus(7, ChronoUnit.DAYS)),
         person("old", now.minus(7, ChronoUnit.DAYS).minusSeconds(1)), person("never", null), person("future", now.plusSeconds(1)));
      String html = CollaborationHome.render(now);
      assertThat(html).contains("INSTANCE: 1", "APP: 1", "TABLE: 2", "People known: 5", "Active in the last 7 days: 2").doesNotContain("No feature settings yet", "No people yet");
   }



   /*******************************************************************************
    ** Enforced core actions use full custom permissions for reads and writes.
    *******************************************************************************/
   @Test
   void permissions() throws Exception
   {
      QContext.getQSession().setPermissions(new java.util.HashSet<>());
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(CollaborationTables.USER))).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> CollaborationHome.render(Instant.now())).isInstanceOf(QPermissionDeniedException.class);
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
      seed(CollaborationTables.USER, person("visible", now), person("hidden", now));
      instance.addSecurityKeyType(new QSecurityKeyType().withName("person").withAllAccessKeyName("allPeople"));
      instance.getTable(CollaborationTables.USER).withRecordSecurityLock(new RecordSecurityLock().withSecurityKeyType("person").withFieldName("userId"));
      QContext.init(instance, new QSession().withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference("viewer")).withPermissions(CollaborationPermissions.VIEW).withSecurityKeyValue("person", "visible"));
      assertThat(CollaborationHome.render(now)).contains("People known: 1", "Active in the last 7 days: 1");
      instance.setEnforcePermissionsInCoreActions(false);
      QContext.getQSession().setPermissions(new java.util.HashSet<>());
      assertThatThrownBy(() -> new CollaborationHome.HomeRenderer().render(new RenderWidgetInput())).isInstanceOf(QPermissionDeniedException.class);
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
