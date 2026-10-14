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


package com.kingsrook.qbits.collaboration.recents;


import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationQBitProducer;
import com.kingsrook.qbits.collaboration.CollaborationSchema;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.favorites.FavoriteEntry;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.simple.SimpleSchedulerMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Persisted throttling, visible ordering, bounded retention and summary counts.
 *******************************************************************************/
class RecentsServiceTest extends PersonalServiceFixture
{
   private static final Instant START = Instant.parse("2026-10-13T12:00:00Z");



   /*******************************************************************************
    ** Do not leak an injected clock to another test.
    *******************************************************************************/
   @AfterEach
   void resetClock()
   {
      RecentsService.clock = Clock.systemUTC();
   }



   /*******************************************************************************
    ** The exact 60-second boundary updates; 59 seconds does not write anything.
    *******************************************************************************/
   @Test
   void throttlesPerTargetAndUser() throws Exception
   {
      at(START);
      RecentsService.recordView("RECORD", "document", "1");
      QRecord initial = rows().getFirst();
      at(START.plusSeconds(59));
      RecentsService.recordView("RECORD", "document", "1");
      assertThat(rows().getFirst().getValues()).isEqualTo(initial.getValues());
      at(START.plusSeconds(60));
      RecentsService.recordView("RECORD", "document", "1");
      assertThat(rows()).hasSize(1);
      assertThat(rows().getFirst().getValueInteger("viewCount")).isEqualTo(2);
      assertThat(rows().getFirst().getValueInstant("lastViewedAt")).isEqualTo(START.plusSeconds(60));
      RecentsService.recordView("RECORD", "document", "2");
      assertThat(rows()).hasSize(2);
      login("bob");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.RECENT)).getRecords()).isEmpty();
      RecentsService.recordView("RECORD", "document", "1");
      assertThat(rows()).hasSize(1);
      assertThat(rows().getFirst().getValueInteger("viewCount")).isEqualTo(1);
      admin();
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.RECENT)).getRecords()).hasSize(3);
      assertThat(RecentsService.list(50)).isEmpty();
   }



   /*******************************************************************************
    ** Null ids also upsert; visibility is checked even inside the throttle window.
    *******************************************************************************/
   @Test
   void tableUpsertAndInvisibleViews() throws Exception
   {
      at(START);
      RecentsService.recordView("TABLE", "document", null);
      RecentsService.recordView("TABLE", "document", null);
      assertThat(rows()).hasSize(1);
      at(START.plusSeconds(61));
      RecentsService.recordView("TABLE", "document", null);
      assertThat(rows().getFirst().getValueInteger("viewCount")).isEqualTo(2);
      assertThatThrownBy(() -> RecentsService.recordView("RECORD", "document", "3")).hasMessage("Not found");
      RecentsService.recordView("RECORD", "document", "1");
      QContext.getQSession().getPermissions().remove("document.read");
      assertThatThrownBy(() -> RecentsService.recordView("RECORD", "document", "1")).hasMessage("Not found");
      assertThat(RecentsService.list(50)).isEmpty();
   }



   /*******************************************************************************
    ** Newest-first ordering is stable; locked rows are dropped before the limit.
    *******************************************************************************/
   @Test
   void limitOrderAndVisibility() throws Exception
   {
      at(START);
      RecentsService.recordView("TABLE", "document", null);
      at(START.plusSeconds(1));
      RecentsService.recordView("RECORD", "document", "1");
      at(START.plusSeconds(2));
      RecentsService.recordView("RECORD", "document", "2");
      assertThat(RecentsService.list(2)).extracting(FavoriteEntry::recordId).containsExactly("2", "1");
      assertThat(RecentsService.list(50)).hasSize(3);
      QContext.getQSession().getSecurityKeyValues().put("tenant", List.of("b"));
      assertThat(RecentsService.list(1)).extracting(FavoriteEntry::targetType).containsExactly("TABLE");
      assertThatThrownBy(() -> RecentsService.list(51)).hasMessage("Limit must be between 1 and 50");
      assertThatThrownBy(() -> RecentsService.list(0)).hasMessage("Limit must be between 1 and 50");
      assertThatThrownBy(() -> RecentsService.list(null)).hasMessage("Limit must be between 1 and 50");
   }



   /*******************************************************************************
    ** A page full of unavailable rows must not hide older, accessible targets.
    *******************************************************************************/
   @Test
   void refillsAfterUnavailablePage() throws Exception
   {
      at(START);
      RecentsService.recordView("TABLE", "document", null);
      for(int i = 0; i < 51; i++)
      {
         PersonalRecords.insert(CollaborationTables.RECENT, new QRecord().withValue("userId", "alice").withValue("targetType", "TABLE")
            .withValue("targetName", "removed" + i).withValue("label", "Old private label").withValue("lastViewedAt", START.plusSeconds(i + 1)).withValue("viewCount", 1));
      }
      assertThat(RecentsService.list(50)).extracting(FavoriteEntry::label).containsExactly("Documents");
   }



   /*******************************************************************************
    ** A real backend process keeps newest N independently for each owner.
    *******************************************************************************/
   @Test
   void trimProcessAndExecutionPermission() throws Exception
   {
      config.setRecentsPerUser(2);
      for(String user : List.of("alice", "bob"))
      {
         login(user);
         at(START);
         RecentsService.recordView("RECORD", "document", "1");
         at(START.plusSeconds(1));
         RecentsService.recordView("RECORD", "document", "2");
         at(START.plusSeconds(2));
         RecentsService.recordView("TABLE", "document", null);
      }
      assertThatThrownBy(() -> new TrimRecentsProcess().run(new RunBackendStepInput(), new RunBackendStepOutput())).hasMessageContaining("collaboration.admin");
      assertThatThrownBy(() -> new RunProcessAction().execute(new RunProcessInput().withProcessName(TrimRecentsProcess.NAME))).hasMessageContaining("collaboration.admin");
      admin();
      new RunProcessAction().execute(new RunProcessInput().withProcessName(TrimRecentsProcess.NAME));
      assertThat(PersonalRecords.query(CollaborationTables.RECENT, new QQueryFilter())).hasSize(4);
      for(String user : List.of("alice", "bob"))
      {
         login(user);
         assertThat(RecentsService.list(50)).extracting(FavoriteEntry::recordId).containsExactly(null, "2");
      }
      QSystemUserSession system = new QSystemUserSession();
      QContext.init(instance, system);
      var before = new HashMap<>(system.getSecurityKeyValues());
      CollaborationSecurity.applyToSession(instance, system);
      CollaborationSecurity.ensureSessionKeys(instance, system);
      assertThat(system.getSecurityKeyValues()).isEqualTo(before);
      config.setRecentsPerUser(1);
      new RunProcessAction().execute(new RunProcessInput().withProcessName(TrimRecentsProcess.NAME));
      assertThat(PersonalRecords.query(CollaborationTables.RECENT, new QQueryFilter())).hasSize(2);
      assertThat(QContext.getQSession()).isSameAs(system);
   }



   /*******************************************************************************
    ** The daily schedule and metadata remain valid without adding nav processes.
    *******************************************************************************/
   @Test
   void metadataSchemaAndConfiguration() throws Exception
   {
      assertThat(new CollaborationQBitConfig().getRecentsPerUser()).isEqualTo(200);
      assertThat(instance.getProcess(TrimRecentsProcess.NAME).getIsHidden()).isTrue();
      assertThat(instance.getProcess(TrimRecentsProcess.NAME).getSchedule()).isNull();
      assertThatThrownBy(() -> config.withRecentsPerUser(0).validate(instance)).hasMessageContaining("recentsPerUser");
      assertThatThrownBy(() -> config.withRecentsPerUser(null).validate(instance)).hasMessageContaining("recentsPerUser");
      config.withRecentsPerUser(200);
      assertThatThrownBy(() -> config.withSchedulerName("missing").validate(instance)).hasMessageContaining("scheduler");
      instance.addScheduler(new SimpleSchedulerMetaData().withName("daily"));
      config.withSchedulerName("daily");
      config.validate(instance);
      QProcessMetaData scheduled = TrimRecentsProcess.metadata(config);
      assertThat(scheduled.getSchedule().getSchedulerName()).isEqualTo("daily");
      assertThat(scheduled.getSchedule().getRepeatSeconds()).isEqualTo(86400);
      instance.getProcess(TrimRecentsProcess.NAME).setSchedule(scheduled.getSchedule());
      new QInstanceValidator().validate(instance);
      for(String table : List.of(CollaborationTables.FAVORITE, CollaborationTables.RECENT))
      {
         assertThat(instance.getTable(table).getAuditRules().getAuditLevel()).isEqualTo(AuditLevel.NONE);
         assertThat(instance.getTable(table).getPermissionRules().getLevel()).isEqualTo(PermissionLevel.NOT_PROTECTED);
         assertThat(instance.getTable(table).getUniqueKeys().getFirst().getFieldNames()).containsExactly("userId", "targetType", "targetName", "recordId");
         assertThat(instance.getTable(table).getSections()).extracting(section -> section.getName()).containsExactly("identity", "details", "dates");
      }
      assertThat(instance.getApp("collaboration").getChildren()).extracting(child -> child.getName()).doesNotContain(CollaborationTables.FAVORITE, CollaborationTables.RECENT, TrimRecentsProcess.NAME);
      assertThat(instance.getPossibleValueSource(CollaborationTables.TARGET_TYPE).getEnumValues()).hasSize(6);
      assertThat(CollaborationSchema.postgresScript(config)).contains("CREATE TABLE IF NOT EXISTS collaboration_favorite", "CREATE TABLE IF NOT EXISTS collaboration_recent",
         "(user_id, target_type, target_name, record_id) NULLS NOT DISTINCT", "last_viewed_at TIMESTAMPTZ NOT NULL", "view_count INTEGER NOT NULL");
   }



   /*******************************************************************************
    ** Freeze the service clock, including exact boundary cases.
    *******************************************************************************/
   private void at(Instant instant)
   {
      RecentsService.clock = Clock.fixed(instant, ZoneOffset.UTC);
   }



   /*******************************************************************************
    ** Inspect persisted rows for the current owner without altering their values.
    *******************************************************************************/
   private List<QRecord> rows() throws Exception
   {
      return (PersonalRecords.query(CollaborationTables.RECENT, PersonalRecords.ownFilter()));
   }
}
