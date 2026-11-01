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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.kingsrook.qbits.collaboration.api.CollaborationPresenceSchemas;
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
import com.kingsrook.qqq.backend.core.utils.JsonUtils;
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
         assertThat(first.snapshot("document", "1")).containsExactly(new PresenceRegistry.Viewer("alice", "Alice", "EDITING", "title", 2));
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
    ** The last leave still sends viewers: the SSE writer and the HTTP snapshot must
    ** both carry an empty array, never omit the field (#52).
    *******************************************************************************/
   @Test
   void lastLeaveSendsEmptyViewers() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var registry = new PresenceRegistry(bus, clock, PresenceRegistry.MAX_ENTRIES))
      {
         List<RealtimeMessage> events = new CopyOnWriteArrayList<>();
         bus.subscribe(events::add);
         registry.heartbeat("bob", "Bob", "one", "person", null, "VIEWING", null);
         registry.heartbeat("bob", "Bob", "one", "person", "7", "EDITING", "name");
         registry.leave("bob", "one", "person", null);
         registry.leave("bob", "one", "person", "7");
         assertThat(registry.snapshot("person", null)).isEmpty();

         for(String channel : List.of("table:person", "record:person:7"))
         {
            RealtimeMessage last = events.stream().filter(event -> event.getChannel().equals(channel) && event.getEvent().equals("presence.changed")).reduce((first, second) -> second).orElseThrow();
            assertThat(last.getData()).containsKey("viewers");
            assertThat((List<?>) last.getData().get("viewers")).isEmpty();

            ///////////////////////////////////////////////////////////////////////
            // the realtime hub copies the data map and writes it with toJson,   //
            // whose NON_EMPTY inclusion used to drop the empty viewers list     //
            ///////////////////////////////////////////////////////////////////////
            String json = JsonUtils.toJson(new LinkedHashMap<>(last.getData()));
            assertThat(JsonUtils.toJSONObject(json).getJSONArray("viewers").length()).isZero();
         }

         String occupied = JsonUtils.toJson(events.stream().filter(event -> event.getChannel().equals("record:person:7")).findFirst().orElseThrow().getData());
         assertThat(JsonUtils.toJSONObject(occupied).getJSONArray("viewers").getJSONObject(0).getString("fieldName")).isEqualTo("name");

         var snapshot = new CollaborationPresenceSchemas.CollaborationPresenceSnapshotV1("person", null, registry.snapshot("person", null));
         assertThat(JsonUtils.toJSONObject(JsonUtils.toJson(snapshot)).getJSONArray("viewers").length()).isZero();
      }
   }

   /*******************************************************************************
    ** Two leases of one user merge into one viewer with connections = 2; per
    ** connection they are two viewers with opaque ids, modes, fields and starts.
    ** Counts keep counting users; connectionCounts counts leases (#54).
    *******************************************************************************/
   @Test
   void perConnectionViewersAndCounts() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var first = new PresenceRegistry(bus, clock, PresenceRegistry.MAX_ENTRIES);
          var second = new PresenceRegistry(bus, clock, PresenceRegistry.MAX_ENTRIES))
      {
         first.heartbeat("admin", "Admin", "terminal-1", "session-a", "person", "7", "VIEWING", null);
         clock.advance(1000);
         second.heartbeat("admin", "Admin", "terminal-2", "session-b", "person", "7", "EDITING", "name");
         first.heartbeat("bob", "Bob", "tab", "session-c", "person", "7", "VIEWING", null);

         assertThat(first.snapshot("person", "7")).containsExactly(
            new PresenceRegistry.Viewer("admin", "Admin", "EDITING", "name", 2),
            new PresenceRegistry.Viewer("bob", "Bob", "VIEWING", null, 1));

         List<PresenceRegistry.Viewer> connections = second.connections("person", "7");
         assertThat(connections).hasSize(3);
         assertThat(connections.subList(0, 2)).extracting(PresenceRegistry.Viewer::userId, PresenceRegistry.Viewer::mode, PresenceRegistry.Viewer::fieldName, PresenceRegistry.Viewer::connections)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("admin", "VIEWING", null, 1), org.assertj.core.groups.Tuple.tuple("admin", "EDITING", "name", 1));
         assertThat(connections.getFirst().connectionId()).isEqualTo(PresenceRegistry.opaqueConnectionId("admin", "terminal-1")).startsWith("c").hasSize(17);
         assertThat(connections.getFirst().since()).isEqualTo("2026-10-13T12:00:00Z");
         assertThat(connections.get(1).since()).isEqualTo("2026-10-13T12:00:01Z");

         ////////////////////////////////////////////////////////////////////
         // renewals keep the lease start; the session uuid replicates     //
         ////////////////////////////////////////////////////////////////////
         clock.advance(20000);
         first.heartbeat("admin", "Admin", "terminal-1", "session-a", "person", "7", "VIEWING", null);
         assertThat(second.connections("person", "7").getFirst().since()).isEqualTo("2026-10-13T12:00:00Z");
         assertThat(second.leases("person", "7")).filteredOn(lease -> lease.connectionId().equals("terminal-1"))
            .singleElement().satisfies(lease ->
            {
               assertThat(lease.sessionUuid()).isEqualTo("session-a");
               assertThat(lease.userId()).isEqualTo("admin");
            });

         ////////////////////////////////////////////////////////////////////
         // table snapshots and counts: users vs leases                    //
         ////////////////////////////////////////////////////////////////////
         assertThat(first.snapshot("person", null)).extracting(PresenceRegistry.Viewer::connections).containsExactly(2, 1);
         assertThat(first.connections("person", null)).extracting(PresenceRegistry.Viewer::fieldName).containsOnlyNulls();
         assertThat(first.counts()).containsExactly(java.util.Map.entry("person", 2));
         assertThat(first.connectionCounts()).containsExactly(java.util.Map.entry("person", 3));

         second.leave("admin", "terminal-2", "person", "7");
         assertThat(first.snapshot("person", "7").getFirst()).isEqualTo(new PresenceRegistry.Viewer("admin", "Admin", "VIEWING", null, 1));
      }
   }

   /*******************************************************************************
    ** presence.changed carries connections per merged viewer, a lease count and
    ** one lease entry per connection; a second terminal of one user is a change.
    *******************************************************************************/
   @Test
   void changedEventCarriesConnections() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var registry = new PresenceRegistry(bus, clock, PresenceRegistry.MAX_ENTRIES))
      {
         List<RealtimeMessage> events = new CopyOnWriteArrayList<>();
         bus.subscribe(events::add);
         registry.heartbeat("admin", "Admin", "terminal-1", "person", "7", "VIEWING", null);
         int before = recordEvents(events).size();
         registry.heartbeat("admin", "Admin", "terminal-2", "person", "7", "VIEWING", null);
         List<RealtimeMessage> after = recordEvents(events);
         assertThat(after).hasSize(before + 1);

         var json = JsonUtils.toJSONObject(JsonUtils.toJson(new LinkedHashMap<>(after.getLast().getData())));
         assertThat(json.getJSONArray("viewers").length()).isEqualTo(1);
         assertThat(json.getJSONArray("viewers").getJSONObject(0).getInt("connections")).isEqualTo(2);
         assertThat(json.getJSONArray("viewers").getJSONObject(0).has("connectionId")).isFalse();
         assertThat(json.getInt("leaseCount")).isEqualTo(2);
         assertThat(json.getJSONArray("leases").length()).isEqualTo(2);
         var lease = json.getJSONArray("leases").getJSONObject(1);
         assertThat(lease.getString("userId")).isEqualTo("admin");
         assertThat(lease.getString("mode")).isEqualTo("VIEWING");
         assertThat(lease.getInt("connections")).isEqualTo(1);
         assertThat(lease.getString("connectionId")).startsWith("c");
         assertThat(lease.getString("since")).isEqualTo("2026-10-13T12:00:00Z");
         assertThat(json.toString()).doesNotContain("terminal-");

         registry.leave("admin", "terminal-1", "person", "7");
         registry.leave("admin", "terminal-2", "person", "7");
         var last = JsonUtils.toJSONObject(JsonUtils.toJson(new LinkedHashMap<>(recordEvents(events).getLast().getData())));
         assertThat(last.getInt("leaseCount")).isZero();
         assertThat(last.getJSONArray("leases").length()).isZero();
      }
   }

   /*******************************************************************************
    ** Record-channel presence.changed events so far.
    *******************************************************************************/
   private static List<RealtimeMessage> recordEvents(List<RealtimeMessage> events)
   {
      return (events.stream().filter(event -> event.getChannel().equals("record:person:7") && event.getEvent().equals("presence.changed")).toList());
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
      QContext.getQSession().setUuid("session-uuid-alice");
      assertThat(service.heartbeat("one", "document", "01", "EDITING", "title")).hasSize(1);
      assertThat(PresenceRegistry.get(QContext.getQInstance()).leases("document", "1")).singleElement()
         .satisfies(lease -> assertThat(lease.sessionUuid()).isEqualTo("session-uuid-alice"));
      assertThat(service.connections("document", "1")).singleElement().satisfies(viewer -> assertThat(viewer.connectionId()).isEqualTo(PresenceRegistry.opaqueConnectionId("alice", "one")));
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
      assertThat(CollaborationHome.presence().getRows().getFirst().get("connections")).isEqualTo(1);
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
