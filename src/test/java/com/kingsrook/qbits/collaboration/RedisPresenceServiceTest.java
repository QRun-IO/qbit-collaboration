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

import com.kingsrook.qbits.collaboration.presence.PresenceScopeProvider;
import com.kingsrook.qbits.collaboration.presence.PresenceService;
import com.kingsrook.qbits.collaboration.presence.RedisPresenceConfig;
import com.kingsrook.qbits.redis.RedisConnectionConfig;
import com.kingsrook.qbits.redis.RedisQBitConfig;
import com.kingsrook.qbits.redis.RedisQBitProducer;
import com.kingsrook.qbits.redis.RedisRuntimeService;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QServiceUnavailableException;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Exercise the authenticated service boundary against a real Redis runtime.
 *******************************************************************************/
class RedisPresenceServiceTest extends PersonalServiceFixture
{
   /***************************************************************************
    ** Separate application runtimes share leases and deliver hints through Redis.
    ***************************************************************************/
   @Test
   void twoNodesShareSixViewersAndTwoEditors() throws Exception
   {
      var second = new PersonalServiceFixture() { };
      second.setUp();
      var firstRuntime = new RedisRuntimeService();
      var secondRuntime = new RedisRuntimeService();
      var firstTransport = new com.kingsrook.qbits.redis.RedisRealtimeRuntimeService();
      var secondTransport = new com.kingsrook.qbits.redis.RedisRealtimeRuntimeService();
      try(var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var nodes = java.util.List.of(instance, second.instance);
         var configs = java.util.List.of(config, second.config);
         for(int i = 0; i < nodes.size(); i++)
         {
            com.kingsrook.qqq.backend.core.realtime.RealtimeBus.close(nodes.get(i));
            new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence",
               new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false))
               .withRealtime("presence", "test", "multi-node")).produce(nodes.get(i));
            configs.get(i).withRedisPresence(new RedisPresenceConfig().withConnectionName("presence").withDeployment("test")
               .withApplication("multi-node").withScopeProvider(new QCodeReference(TenantScope.class)));
         }
         firstRuntime.start(instance);
         secondRuntime.start(second.instance);
         firstTransport.start(instance);
         secondTransport.start(second.instance);
         org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
         {
            for(var node : nodes)
            {
               while(!((com.kingsrook.qbits.redis.RedisRealtimeBus) com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(node).orElseThrow()).isListening())
               {
                  Thread.sleep(10);
               }
            }
         });
         try(var hub = new com.kingsrook.qqq.middleware.javalin.realtime.RealtimeHub(second.instance))
         {
            var observer = session("observer");
            QContext.init(second.instance, observer);
            var stream = hub.connect(observer, null);
            hub.subscriptions(observer, stream.getConnectionId(), java.util.List.of("record:document:1"), java.util.List.of());
            var otherTenant = session("other-tenant");
            otherTenant.clearSecurityKeyValues();
            otherTenant.withSecurityKeyValue("tenant", "b");
            QContext.init(second.instance, otherTenant);
            var otherStream = hub.connect(otherTenant, null);
            hub.subscriptions(otherTenant, otherStream.getConnectionId(), java.util.List.of("table:document"), java.util.List.of());
            var service = new PresenceService();
            var leases = new java.util.ArrayList<String>();
            for(int i = 0; i < 6; i++)
            {
               QContext.init(nodes.get((i + 1) % 2), session("user" + i));
               String lease = service.openLease("document", "1");
               leases.add(lease);
               assertThat(service.snapshot("document", "1")).hasSize(i);
               QContext.init(nodes.get(i % 2), session("user" + i));
               service.renewLease(lease, "document", "1", i < 2 ? "EDITING" : "VIEWING", i < 2 ? "title" : null, 1L);
               if(i == 0)
               {
                  // Node one activates a lease opened on node two; its hint crosses nodes.
                  org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                  {
                     while(true)
                     {
                        var frame = stream.poll(200);
                        if(frame != null && "presence.invalidated".equals(frame.event()))
                        {
                           assertThat(frame.data()).containsOnlyKeys("table", "pk").containsEntry("pk", "1");
                           break;
                        }
                     }
                  });
               }
            }
            for(var node : nodes)
            {
               QContext.init(node, observer);
               var viewers = service.snapshot("document", "1");
               assertThat(viewers).hasSize(6);
               assertThat(viewers.stream().filter(viewer -> "EDITING".equals(viewer.mode())).count()).isEqualTo(2);
               assertThat(service.snapshot("document", null)).hasSize(6);
            }
            // A later unscoped marker proves the other tenant's stream processed
            // the earlier hints, instead of passing on an arbitrary short timeout.
            com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).orElseThrow().publish(
               new com.kingsrook.qqq.backend.core.realtime.RealtimeMessage().withChannel("table:document")
                  .withEvent("test.barrier").withData(java.util.Map.of()));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
            {
               while(true)
               {
                  var frame = otherStream.poll(200);
                  if(frame != null)
                  {
                     assertThat(frame.event()).isNotEqualTo("presence.invalidated");
                     if("test.barrier".equals(frame.event()))
                     {
                        break;
                     }
                  }
               }
            });
            for(var node : nodes)
            {
               QContext.init(node, otherTenant);
               assertThat(service.snapshot("document", null)).isEmpty();
               assertThat(service.snapshot("document", "3")).isEmpty();
               assertThatThrownBy(() -> service.snapshot("document", "1")).isInstanceOf(QNotFoundException.class);
            }
            QContext.init(second.instance, session("user0"));
            service.leave(leases.getFirst(), "document", "1", 2L);
            QContext.init(instance, observer);
            assertThat(service.snapshot("document", "1")).hasSize(5);
            var firstBus = (com.kingsrook.qbits.redis.RedisRealtimeBus) com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(instance).orElseThrow();
            var secondBus = (com.kingsrook.qbits.redis.RedisRealtimeBus) com.kingsrook.qqq.backend.core.realtime.RealtimeBus.getIfStarted(second.instance).orElseThrow();
            firstBus.publish(new com.kingsrook.qqq.backend.core.realtime.RealtimeMessage().withChannel("record:document:1")
               .withEvent("test.drained").withData(java.util.Map.of()));
            awaitEvent(stream, "test.drained");
            long reconnects = secondBus.metrics().reconnects();
            assertThat(redis.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub").getExitCode()).isZero();
            assertThat(awaitEvent(stream, "resync").data()).isEmpty();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
            {
               while(!firstBus.isListening() || !secondBus.isListening() || secondBus.metrics().reconnects() <= reconnects)
               {
                  Thread.sleep(10);
               }
            });
            // The subscription failure does not erase authoritative presence leases.
            QContext.init(second.instance, observer);
            assertThat(service.snapshot("document", "1")).hasSize(5);
            QContext.init(instance, session("user0"));
            assertThatThrownBy(() -> service.renewLease(leases.getFirst(), "document", "1", "EDITING", "title", 3L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            String replacement = service.openLease("document", "1");
            service.renewLease(replacement, "document", "1", "EDITING", "title", 1L);
            assertThat(awaitEvent(stream, "presence.invalidated").data()).containsEntry("pk", "1");
            QContext.init(second.instance, observer);
            assertThat(service.snapshot("document", "1")).hasSize(6);
         }
      }
      finally
      {
         firstTransport.stop();
         secondTransport.stop();
         firstRuntime.afterApplicationStop();
         secondRuntime.afterApplicationStop();
         second.tearDown();
      }
   }

   /***************************************************************************
    ** Wait for a stream event with a bounded deadline, consuming older frames.
    ***************************************************************************/
   private static com.kingsrook.qqq.middleware.javalin.realtime.RealtimeHub.Frame awaitEvent(
      com.kingsrook.qqq.middleware.javalin.realtime.RealtimeHub.Attachment stream, String event)
   {
      return (org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
      {
         while(true)
         {
            var frame = stream.poll(200);
            if(frame != null && event.equals(frame.event()))
            {
               return (frame);
            }
         }
      }));
   }

   /***************************************************************************
    ** Each simulated browser has an independent authenticated host session.
    ***************************************************************************/
   private static com.kingsrook.qqq.backend.core.model.session.QSession session(String user)
   {
      return (new com.kingsrook.qqq.backend.core.model.session.QSession()
         .withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference(user))
         .withPermissions("document.read", "document.write").withSecurityKeyValue("tenant", "a"));
   }

   /***************************************************************************
    ** Redis preserves permission projection and fails explicitly during outages.
    ***************************************************************************/
   @Test
   void permissionsIsolationAndUnavailable() throws Exception
   {
      try(var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence",
            new RedisConnectionConfig().withHost(redis.getHost()).withPort(redis.getMappedPort(6379)).withTls(false)
               .withConnectionTimeoutMillis(200).withSocketTimeoutMillis(200))).produce(instance);
         config.withRedisPresence(new RedisPresenceConfig().withConnectionName("presence").withDeployment("test")
            .withApplication("fixture").withScopeProvider(new QCodeReference(TenantScope.class)));
         var runtime = new RedisRuntimeService();
         try
         {
            runtime.start(instance);
            var service = new PresenceService();
            String aliceLease;
            try(var hub = new com.kingsrook.qqq.middleware.javalin.realtime.RealtimeHub(instance))
            {
               var viewer = hub.connect(QContext.getQSession(), null);
               viewer.poll(100);
               hub.subscriptions(QContext.getQSession(), viewer.getConnectionId(), java.util.List.of("table:document", "record:document:1"), java.util.List.of());
               login("other-tenant");
               QContext.getQSession().clearSecurityKeyValues();
               QContext.getQSession().withSecurityKeyValue("tenant", "b");
               var other = hub.connect(QContext.getQSession(), null);
               other.poll(100);
               hub.subscriptions(QContext.getQSession(), other.getConnectionId(), java.util.List.of("table:document"), java.util.List.of());
               login("alice");
               assertThatThrownBy(() -> service.heartbeat("alice-tab", "document", "01", "EDITING", "title", 1L))
                  .isInstanceOf(QBadRequestException.class).hasMessageContaining("server-issued lease");
               aliceLease = service.openLease("document", "01");
               service.renewLease(aliceLease, "document", "01", "EDITING", "title", 1L);
               var tableHint = viewer.poll(1000);
               var recordHint = viewer.poll(1000);
               assertThat(tableHint.event()).isEqualTo("presence.invalidated");
               assertThat(tableHint.data()).containsOnlyKeys("table");
               assertThat(recordHint.data()).containsOnlyKeys("table", "pk").containsEntry("pk", "1");
               assertThat(other.poll(20)).isNull();
            }
            admin();
            var adminPresence = com.kingsrook.qbits.collaboration.app.CollaborationHome.presence();
            assertThat(adminPresence.getRows()).hasSize(1);
            assertThat(adminPresence.getRows().getFirst()).containsEntry("table", "Documents").containsEntry("viewers", 1);
            QContext.getQSession().clearSecurityKeyValues();
            QContext.getQSession().withSecurityKeyValue("tenant", "b");
            assertThat(com.kingsrook.qbits.collaboration.app.CollaborationHome.presence().getRows()).isEmpty();
            login("bob");
            assertThat(com.kingsrook.qbits.collaboration.app.CollaborationHome.presence().getRows()).isEmpty();
            assertThat(service.snapshot("document", "1")).hasSize(1);
            assertThat(service.snapshot("document", "1").getFirst().userId()).isEqualTo("alice");
            assertThat(service.snapshot("document", "1").getFirst().fieldName()).isEqualTo("title");
            instance.getTable("document").getField("title").setIsHidden(true);
            assertThat(service.snapshot("document", "1").getFirst().fieldName()).isNull();
            String bobLease = service.openLease("document", "1");
            assertThatThrownBy(() -> service.renewLease(bobLease, "document", "1", "EDITING", "title", 1L))
               .isInstanceOf(QBadRequestException.class).hasMessageContaining("focused field");
            assertThatThrownBy(() -> service.snapshot("document", "3")).isInstanceOf(QNotFoundException.class);
            QContext.getQSession().clearSecurityKeyValues();
            QContext.getQSession().withSecurityKeyValue("tenant", "b");
            assertThat(service.snapshot("document", null)).isEmpty();
            login("alice");
            service.leave(aliceLease, "document", "1", 2L);
            assertThat(service.snapshot("document", "1")).isEmpty();
            service.renewLease(service.openLease("document", "1"), "document", "1", "VIEWING", null, 1L);
            var client = com.kingsrook.qbits.redis.RedisRuntimeService.connection(instance, "presence").client();
            // Test-only corruption of the disposable Redis server's record state.
            client.del(client.keys("*:record:*:state").iterator().next());
            assertThatThrownBy(() -> service.snapshot("document", "1"))
               .isInstanceOf(QServiceUnavailableException.class).hasMessage("Presence is temporarily unavailable. Please retry.").hasNoCause();
            redis.stop();
            assertThatThrownBy(() -> service.snapshot("document", "1"))
               .isInstanceOf(QServiceUnavailableException.class).hasMessage("Presence is temporarily unavailable. Please retry.").hasNoCause();
            assertThatThrownBy(() -> service.heartbeat("tab", "document", "3", "VIEWING", null, 1L))
               .isInstanceOf(QNotFoundException.class);
            admin();
            assertThatThrownBy(com.kingsrook.qbits.collaboration.app.CollaborationHome::presence)
               .isInstanceOf(QServiceUnavailableException.class);
         }
         finally
         {
            runtime.stop();
            runtime.afterApplicationStop();
         }
      }
   }

   /***************************************************************************
    ** Tenant identity comes from the authenticated host session, never the request.
    ***************************************************************************/
   public static class TenantScope implements PresenceScopeProvider
   {
      /************************************************************************
       ** Require exactly one authenticated tenant in this fixture.
       ************************************************************************/
      @Override
      public String tenantScope()
      {
         var tenants = QContext.getQSession().getSecurityKeyValues("tenant");
         if(tenants.size() != 1)
         {
            throw (new IllegalStateException("One tenant required"));
         }
         return (tenants.getFirst().toString());
      }
   }
}
