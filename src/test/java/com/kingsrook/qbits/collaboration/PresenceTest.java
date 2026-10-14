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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.kingsrook.qbits.collaboration.presence.PresenceRegistry;
import com.kingsrook.qbits.collaboration.presence.PresenceService;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.realtime.InMemoryRealtimeBus;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Two independent pods, a real in-memory bus, controlled time and real permissions.
 *******************************************************************************/
class PresenceTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Renewals replicate even without a visible change; tabs merge until all expire.
    *******************************************************************************/
   @Test
   void twoPodsMergeRenewExpireAndLeave() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var first = new PresenceRegistry(bus, clock, PresenceRegistry.MAX_ENTRIES);
          var second = new PresenceRegistry(bus, clock, PresenceRegistry.MAX_ENTRIES))
      {
         List<RealtimeMessage> events = new CopyOnWriteArrayList<>();
         bus.subscribe(events::add);
         first.heartbeat("alice", "Alice", "one", "document", "1", "VIEWING", null);
         assertThat(second.snapshot("document", "1")).hasSize(1);
         clock.advance(1000);
         second.heartbeat("alice", "Alice", "two", "document", "1", "EDITING", "title");
         assertThat(first.snapshot("document", "1")).containsExactly(new PresenceRegistry.Viewer("alice", "Alice", "EDITING", "title"));
         assertThat(first.snapshot("document", null).getFirst().fieldName()).isNull();
         int changes = (int) events.stream().filter(event -> event.getEvent().equals("presence.changed")).count();
         clock.advance(20000);
         first.heartbeat("alice", "Alice", "two", "document", "1", "EDITING", "title");
         assertThat(events.stream().filter(event -> event.getEvent().equals("presence.changed")).count()).isEqualTo(changes);
         clock.advance(25000);
         first.expire();
         second.expire();
         assertThat(first.snapshot("document", "1")).hasSize(1);
         assertThat(second.snapshot("document", "1")).hasSize(1);
         second.leave("alice", "two", "document", "1");
         assertThat(first.snapshot("document", "1")).isEmpty();
         first.heartbeat("bob", "Bob", "three", "document", null, "VIEWING", null);
         clock.advance(45000);
         assertThat(second.snapshot("document", null)).isEmpty();
         assertThat(first.counts()).isEmpty();
         assertThat(events).anySatisfy(event ->
         {
            assertThat(event.getEvent()).isEqualTo("presence.heartbeat");
            assertThat(event.getChannel()).isEqualTo("instance:presence");
         });
         assertThat(events.stream().filter(event -> event.getChannel().equals("table:document"))).allSatisfy(event -> assertThat(event.getData()).doesNotContainKey("pk"));
      }
   }

   /*******************************************************************************
    ** At capacity, existing leases renew while new targets are refused on both pods.
    *******************************************************************************/
   @Test
   void boundedRegistryAndMalformedMessages() throws Exception
   {
      var clock = new MutableClock();
      assertThat(PresenceRegistry.MAX_ENTRIES).isEqualTo(50000);
      try(var bus = new InMemoryRealtimeBus(); var first = new PresenceRegistry(bus, clock, 2); var second = new PresenceRegistry(bus, clock, 2))
      {
         assertThat(first.heartbeat("alice", "Alice", "one", "document", "1", "VIEWING", null)).isTrue();
         assertThat(first.heartbeat("bob", "Bob", "one", "document", "1", "VIEWING", null)).isTrue();
         assertThat(second.heartbeat("carol", "Carol", "one", "document", "1", "VIEWING", null)).isFalse();
         assertThat(first.heartbeat("alice", "Alice", "one", "document", "1", "EDITING", "title")).isTrue();
         assertThat(second.counts()).containsEntry("document", 2);
         bus.publish(new RealtimeMessage().withChannel(PresenceRegistry.INTERNAL_CHANNEL).withEvent("presence.heartbeat").withData(java.util.Map.of()));
         assertThat(second.snapshot("document", "1")).hasSize(2);
         clock.advance(45000);
         assertThat(first.heartbeat("carol", "Carol", "one", "document", "1", "VIEWING", null)).isTrue();
         assertThat(first.snapshot("document", "1")).hasSize(1);
      }
   }

   /*******************************************************************************
    ** Denials are opaque, current sessions are checked, and nothing enters the directory.
    *******************************************************************************/
   @Test
   void accessFeatureAndSnapshot() throws Exception
   {
      var service = new PresenceService();
      assertThat(service.heartbeat("one", "document", "01", "EDITING", "title")).hasSize(1);
      assertThat(service.snapshot("document", "1")).extracting(PresenceRegistry.Viewer::userId).containsExactly("alice");
      assertThat(service.snapshot("document", null)).hasSize(1);
      assertThatThrownBy(() -> service.heartbeat("one", "document", "3", "VIEWING", null)).isInstanceOf(QNotFoundException.class);
      assertThatThrownBy(() -> service.snapshot("missing", null)).isInstanceOf(QNotFoundException.class);
      assertThatThrownBy(() -> service.heartbeat("one", "document", "1", "BAD", null)).hasMessageContaining("Mode");
      assertThatThrownBy(() -> service.heartbeat("one", "document", "1", "VIEWING", "bad")).hasMessageContaining("field");
      assertThatThrownBy(() -> service.heartbeat("", "document", null, "VIEWING", null)).hasMessageContaining("connectionId");
      login("bob");
      service.leave("one", "document", "1");
      assertThat(service.snapshot("document", "1")).hasSize(1);
      login("alice");
      QContext.getQSession().getSecurityKeyValues().clear();
      assertThatThrownBy(() -> service.heartbeat("one", "document", "1", "VIEWING", null)).isInstanceOf(QNotFoundException.class);
      login("alice");
      assertThat(service.snapshot("document", "1")).isEmpty();
      service.heartbeat("table", "document", null, "VIEWING", null);
      admin();
      assertThat(CollaborationHome.presence().getRows()).extracting(row -> row.get("table")).containsExactly("Documents");
      assertThat(CollaborationHome.presence().getRows().getFirst().get("viewers")).isEqualTo(1);
      login("alice");
      assertThat(CollaborationHome.presence().getRows()).isEmpty();
      assertThat(CollaborationHome.presence().getNoRowsFoundHTML()).isEqualTo(CollaborationHome.PRESENCE_ADMIN_ONLY);
      RecordFeaturesMetaData.ofOrWithNew(instance.getTable("document")).withEnabled(RecordFeature.PRESENCE, false);
      RuntimeMetaDataOverlay.refresh(instance);
      assertThatThrownBy(() -> service.snapshot("document", null)).isInstanceOf(QNotFoundException.class);
      assertThatThrownBy(() -> service.heartbeat("table", "document", null, "VIEWING", null)).isInstanceOf(QNotFoundException.class);
      admin();
      assertThat(CollaborationRecords.query(com.kingsrook.qbits.collaboration.model.CollaborationTables.USER, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
      assertThat(CollaborationHome.fields().getNoRowsFoundHTML()).isEqualTo(CollaborationHome.NO_FIELDS);
   }

   /*******************************************************************************
    ** Clock controls the TTL boundary without sleeps.
    *******************************************************************************/
   private static class MutableClock extends Clock
   {
      private Instant now = Instant.parse("2026-10-13T12:00:00Z");
      /*******************************************************************************
       ** Move deterministic time forward.
       *******************************************************************************/
      void advance(long millis)
      {
         now = now.plusMillis(millis);
      }

      /*******************************************************************************
       ** Fixed UTC test zone.
       *******************************************************************************/
      @Override
      public ZoneId getZone()
      {
         return (ZoneId.of("UTC"));
      }

      /*******************************************************************************
       ** Zone does not affect elapsed time.
       *******************************************************************************/
      @Override
      public Clock withZone(ZoneId zone)
      {
         return (this);
      }

      /*******************************************************************************
       ** Current test instant.
       *******************************************************************************/
      @Override
      public Instant instant()
      {
         return (now);
      }
   }
}
