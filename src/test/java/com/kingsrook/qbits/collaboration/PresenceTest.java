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
    ** Issued service operations retain identity, authorization and cleanup rules.
    *******************************************************************************/
   @Test
   void issuedServiceRechecksAccessAndDepartureOrder() throws Exception
   {
      var service = new PresenceService();
      String lease = service.openLease("document", "01");
      assertThat(service.snapshot("document", "1")).isEmpty();
      assertThatThrownBy(() -> service.renewLease(lease, "document", "1", "EDITING", "missing", 1L))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QBadRequestException.class);
      assertThat(service.renewLease(lease, "document", "1", "EDITING", "title", 1L)).hasSize(1);
      assertThatThrownBy(() -> service.leave(lease, "document", "1", null))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QBadRequestException.class);
      login("bob");
      assertThatThrownBy(() -> service.renewLease(lease, "document", "1", "EDITING", "title", 2L))
         .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
      login("alice");
      QContext.getQSession().getPermissions().remove("document.read");
      assertThatThrownBy(() -> service.openLease("document", "1")).isInstanceOf(QNotFoundException.class);
      assertThatThrownBy(() -> service.renewLease(lease, "document", "1", "EDITING", "title", 3L))
         .isInstanceOf(QNotFoundException.class);
      assertThat(PresenceRegistry.get(instance).snapshot("document", "1")).isEmpty();
      login("alice");
      assertThatThrownBy(() -> service.renewLease(lease, "document", "1", "EDITING", "title", 4L))
         .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
   }

   /*******************************************************************************
    ** Local issued authority is bounded and cannot be recreated by late requests.
    *******************************************************************************/
   @Test
   void localIssuedLeasesRequireExistingAuthority() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var local = new PresenceRegistry(bus, clock, 1); var other = new PresenceRegistry(bus, clock, 1))
      {
         String lease = local.openLease("alice", "Alice", "document", "1");
         assertThat(lease).startsWith("lease:");
         assertThat(local.snapshot("document", "1")).isEmpty();
         assertThat(local.openLease("bob", "Bob", "document", "2")).isNull();
         assertThatThrownBy(() -> other.renewLease("alice", "Alice", lease, "document", "1", "EDITING", "title", 1L))
            .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
         assertThatThrownBy(() -> local.renewLease("bob", "Bob", lease, "document", "1", "EDITING", "title", 1L))
            .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
         local.renewLease("alice", "Alice", lease, "document", "1", "EDITING", "title", 1L);
         clock.advance(1000);
         local.renewLease("alice", "Alice", lease, "document", "1", "VIEWING", null, 1L);
         assertThat(local.snapshot("document", "1").getFirst().mode()).isEqualTo("EDITING");
         clock.advance(PresenceRegistry.TTL_MILLIS - 1000);
         assertThatThrownBy(() -> local.renewLease("alice", "Alice", lease, "document", "1", "EDITING", "title", 2L))
            .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
         assertThat(local.snapshot("document", "1")).isEmpty();
         String replacement = local.openLease("alice", "Alice", "document", "1");
         local.renewLease("alice", "Alice", replacement, "document", "1", "EDITING", "title", 1L);
         local.leave("alice", replacement, "document", "1", 3L);
         assertThatThrownBy(() -> local.renewLease("alice", "Alice", replacement, "document", "1", "EDITING", "title", 4L))
            .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
         clock.advance(PresenceRegistry.TTL_MILLIS);
         assertThatThrownBy(() -> local.heartbeat("alice", "Alice", replacement, "document", "1", "EDITING", "title", 5L))
            .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
         assertThatThrownBy(() -> local.renewLease("alice", "Alice", replacement, "document", "1", "EDITING", "title", 5L))
            .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
         assertThat(local.snapshot("document", "1")).isEmpty();
      }
   }

   /*******************************************************************************
    ** Delayed HTTP renewals and departures retain client order across replicas.
    *******************************************************************************/
   @Test
   void clientSequenceOrdersDelayedRequests() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var first = new PresenceRegistry(bus, clock, 10); var second = new PresenceRegistry(bus, clock, 10))
      {
         first.heartbeat("alice", "Alice", "tab", "document", "1", "EDITING", "title", 2L);
         clock.advance(10);
         second.heartbeat("alice", "Alice", "tab", "document", "1", "EDITING", "old", 1L);
         assertThat(first.snapshot("document", "1").getFirst().fieldName()).isEqualTo("title");
         second.leave("alice", "tab", "document", "1", 3L);
         clock.advance(10);
         first.heartbeat("alice", "Alice", "tab", "document", "1", "EDITING", "title", 2L);
         assertThat(first.snapshot("document", "1")).isEmpty();
         assertThat(second.snapshot("document", "1")).isEmpty();
         first.heartbeat("alice", "Alice", "tab", "document", "1", "VIEWING", null, 4L);
         second.leave("alice", "tab", "document", "1", 3L);
         assertThat(second.snapshot("document", "1")).hasSize(1);
      }
   }

   /*******************************************************************************
    ** Departure keeps unknown-target errors without preventing revoked cleanup.
    *******************************************************************************/
   @Test
   void departureRejectsUnknownTable() throws Exception
   {
      var service = new PresenceService();
      assertThatThrownBy(() -> service.leave("valid-connection", "nonexistent", null))
         .isInstanceOf(QNotFoundException.class).hasMessage("Not found");
      service.heartbeat("valid-connection", "document", "1", "VIEWING", null);
      QContext.getQSession().getPermissions().remove("document.read");
      service.leave("valid-connection", "document", "1");
      assertThat(PresenceRegistry.get(instance).snapshot("document", "1")).isEmpty();
   }

   /*******************************************************************************
    ** A departure wins over delayed and same-timestamp renewals on every pod.
    *******************************************************************************/
   @Test
   void departedLeaseCannotBeResurrectedByOldHeartbeat() throws Exception
   {
      var clock = new MutableClock();
      try(var bus = new InMemoryRealtimeBus(); var registry = new PresenceRegistry(bus, clock, 2))
      {
         List<RealtimeMessage> events = new CopyOnWriteArrayList<>();
         bus.subscribe(events::add);
         registry.heartbeat("alice", "Alice", "one", "document", "1", "EDITING", "title");
         var old = events.stream().filter(event -> event.getEvent().equals("presence.heartbeat")).findFirst().orElseThrow();
         registry.leave("alice", "one", "document", "1");
         bus.publish(old);
         assertThat(registry.snapshot("document", "1")).isEmpty();
         clock.advance(1);
         registry.heartbeat("alice", "Alice", "one", "document", "1", "VIEWING", null);
         assertThat(registry.snapshot("document", "1")).hasSize(1);
         clock.advance(PresenceRegistry.TTL_MILLIS);
         assertThat(registry.snapshot("document", "1")).isEmpty();
      }
   }

   /*******************************************************************************
    ** Focus is validated for the sender and projected again for each recipient.
    *******************************************************************************/
   @Test
   void focusedFieldsRespectVisibilityOnEveryResponse() throws Exception
   {
      var table = instance.getTable("document");
      table.withField(new com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData("hidden", com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType.STRING).withIsHidden(true));
      table.withField(new com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData("password", com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType.PASSWORD));
      var service = new PresenceService();
      for(String field : List.of("hidden", "password", "missing"))
      {
         assertThatThrownBy(() -> service.heartbeat("alice-tab", "document", "1", "EDITING", field))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QBadRequestException.class);
      }
      service.heartbeat("alice-tab", "document", "1", "EDITING", "title");
      login("bob");
      assertThat(service.snapshot("document", "1").getFirst().fieldName()).isEqualTo("title");
      table.getField("title").setIsHidden(true);
      assertThat(service.snapshot("document", "1").getFirst().fieldName()).isNull();
      assertThat(service.heartbeat("bob-tab", "document", "1", "VIEWING", null))
         .allSatisfy(viewer -> assertThat(viewer.fieldName()).isNull());
      assertThat(PresenceRegistry.get(instance).snapshot("document", "1")).anySatisfy(viewer -> assertThat(viewer.fieldName()).isEqualTo("title"));
      table.getField("title").setIsHidden(false);
      assertThat(service.snapshot("document", "1")).anySatisfy(viewer -> assertThat(viewer.fieldName()).isEqualTo("title"));
   }

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
