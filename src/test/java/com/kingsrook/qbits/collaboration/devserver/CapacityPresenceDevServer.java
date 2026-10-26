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


package com.kingsrook.qbits.collaboration.devserver;

import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.presence.PresenceRegistry;
import com.kingsrook.qbits.collaboration.presence.PresenceScopeProvider;
import com.kingsrook.qbits.collaboration.presence.RedisPresenceConfig;
import com.kingsrook.qbits.redis.RedisConnectionConfig;
import com.kingsrook.qbits.redis.RedisQBitConfig;
import com.kingsrook.qbits.redis.RedisQBitProducer;
import com.kingsrook.qbits.redis.RedisRealtimeRuntimeService;
import com.kingsrook.qbits.redis.RedisRuntimeService;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;

/*******************************************************************************
 ** Loopback-only browser acceptance node. Run separate JVMs against one disposable
 ** Redis endpoint. Record data is independently seeded, not a shared database.
 *******************************************************************************/
public class CapacityPresenceDevServer
{
   /***************************************************************************
    ** Arguments are HTTP port and disposable local Redis port. Never deploy this
    ** fixture: it uses the demo's mock authentication and plaintext loopback Redis.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      if(args.length != 2)
      {
         throw (new IllegalArgumentException("Expected HTTP port and local Redis port"));
      }
      int port = Integer.parseInt(args[0]);
      int redisPort = Integer.parseInt(args[1]);
      var instance = CollaborationDevServer.buildInstance(port);
      CollaborationDevServer.seed(instance);
      instance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData()
         .withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK)
         .withCustomizer(new QCodeReference(AcceptanceSessions.class)));
      PresenceRegistry.close(instance);
      RealtimeBus.close(instance);
      new RedisQBitProducer().withQBitConfig(new RedisQBitConfig().withConnection("presence",
         new RedisConnectionConfig().withHost(System.getProperty("collab.capacity.redisHost", "127.0.0.1")).withPort(redisPort).withTls(false).withMaximumConnections(8).withMaximumWaitMillis(500))
         .withRealtime("presence", "acceptance", "collaboration")).produce(instance);
      instance.getQBits().values().stream().map(bit -> bit.getConfig())
         .filter(CollaborationQBitConfig.class::isInstance).map(CollaborationQBitConfig.class::cast)
         .findFirst().orElseThrow().withRedisPresence(new RedisPresenceConfig().withConnectionName("presence")
            .withDeployment("acceptance").withApplication("collaboration")
            .withScopeProvider(new QCodeReference(FixtureTenant.class)));
      var redis = new RedisRuntimeService();
      var transport = new RedisRealtimeRuntimeService();
      new com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider().defineAll(instance, "memory", null);
      var health = new com.kingsrook.qqq.backend.core.health.HealthRunner(instance, "acceptance-" + port, java.time.Clock.systemUTC());
      var server = CollaborationDevServer.server(instance, port, false);
      var sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
      try
      {
         redis.start(instance);
         transport.start(instance);
         health.start();
         server.start();
         sampler.scheduleAtFixedRate(() ->
         {
            var pool = RedisRuntimeService.connection(instance, "presence").metrics();
            var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            var values = new java.util.LinkedHashMap<String, Object>();
            values.put("uptimeMillis", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
            values.put("cpuNanos", ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L));
            values.put("heapUsedBytes", heap.getUsed());
            values.put("heapCommittedBytes", heap.getCommitted());
            values.put("poolActive", pool.active());
            values.put("poolIdle", pool.idle());
            values.put("poolWaiting", pool.waiting());
            values.put("poolMaximum", pool.maximum());
            System.out.println("CAPACITY_METRIC " + com.kingsrook.qqq.backend.core.utils.JsonUtils.toJson(values));
         }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
         System.out.println("CAPACITY_PRESENCE_READY " + port);
         Thread.currentThread().join();
      }
      finally
      {
         sampler.shutdownNow();
         server.stop();
         health.stop();
         transport.stop();
         PresenceRegistry.close(instance);
         RealtimeBus.close(instance);
         redis.stop();
         redis.afterApplicationStop();
      }
   }

   /***************************************************************************
    ** Tenant comes from the configured mock identity, never a request tenant parameter.
    ***************************************************************************/
   public static class FixtureTenant implements PresenceScopeProvider
   {
      /************************************************************************
       ** Identical record IDs are isolated by the authenticated fixture identity.
       ************************************************************************/
      @Override
      public String tenantScope()
      {
         String identity = com.kingsrook.qqq.backend.core.context.QContext.getQSession().getUser().getIdReference();
         if(!identity.matches("load-t[0-7]-u[0-9]{1,2}"))
         {
            throw (new IllegalStateException("Capacity presence requires a configured load identity"));
         }
         return (identity.substring(0, identity.indexOf("-u")));
      }
   }

   /***************************************************************************
    ** Grant Redis dashboard access only to the fixture's existing administrator.
    ***************************************************************************/
   public static class AcceptanceSessions extends CollaborationDevServer.DemoSessions
   {
      /************************************************************************
       ** Keep normal demo identity/grants and add the service-view permission.
       ************************************************************************/
      @Override
      public void customizeSession(com.kingsrook.qqq.backend.core.model.metadata.QInstance instance,
         com.kingsrook.qqq.backend.core.model.session.QSession target, java.util.Map<String, Object> context)
      {
         super.customizeSession(instance, target, context);
         if("admin".equals(target.getUser().getIdReference()))
         {
            target.withPermissions("redisView.hasAccess", "health.read");
         }
      }
   }
}
