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
public class SharedPresenceDevServer
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
         new RedisConnectionConfig().withHost("127.0.0.1").withPort(redisPort).withTls(false))
         .withRealtime("presence", "acceptance", "collaboration")).produce(instance);
      instance.getQBits().values().stream().map(bit -> bit.getConfig())
         .filter(CollaborationQBitConfig.class::isInstance).map(CollaborationQBitConfig.class::cast)
         .findFirst().orElseThrow().withRedisPresence(new RedisPresenceConfig().withConnectionName("presence")
            .withDeployment("acceptance").withApplication("collaboration")
            .withScopeProvider(new QCodeReference(SingleTenant.class)));
      var redis = new RedisRuntimeService();
      var transport = new RedisRealtimeRuntimeService();
      new com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider().defineAll(instance, "memory", null);
      var health = new com.kingsrook.qqq.backend.core.health.HealthRunner(instance, "acceptance-" + port, java.time.Clock.systemUTC());
      var server = CollaborationDevServer.server(instance, port, Boolean.getBoolean("collab.dev.frontend"));
      try
      {
         redis.start(instance);
         transport.start(instance);
         health.start();
         server.start();
         System.out.println("SHARED_PRESENCE_READY " + port);
         Thread.currentThread().join();
      }
      finally
      {
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
    ** Explicit single-tenant fixture; no scope comes from browser request input.
    ***************************************************************************/
   public static class SingleTenant implements PresenceScopeProvider
   {
      /************************************************************************
       ** The two acceptance nodes share this configured tenant.
       ************************************************************************/
      @Override
      public String tenantScope()
      {
         return ("acceptance");
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
