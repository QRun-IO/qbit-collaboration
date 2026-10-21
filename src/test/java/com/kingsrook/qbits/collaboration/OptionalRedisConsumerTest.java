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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/*******************************************************************************
 ** Exercise a consumer classpath without the optional Redis implementation/driver.
 *******************************************************************************/
class OptionalRedisConsumerTest
{
   /***************************************************************************
    ** An isolated loader must not resolve Redis from the test runner's parent.
    ***************************************************************************/
   @Test
   void memoryPresenceWithoutRedis() throws Exception
   {
      String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      URL[] urls = Arrays.stream(classpath.split(java.io.File.pathSeparator))
         .filter(entry -> !entry.replace('\\', '/').contains("/qbit-redis/")
            && !entry.replace('\\', '/').contains("/qbit-email/")
            && !Path.of(entry).getFileName().toString().startsWith("jedis-"))
         .map(entry ->
         {
            try
            {
               return (Path.of(entry).toUri().toURL());
            }
            catch(Exception e)
            {
               throw (new IllegalArgumentException(e));
            }
         }).toArray(URL[]::new);
      ClassLoader previous = Thread.currentThread().getContextClassLoader();
      try(var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()))
      {
         assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.kingsrook.qbits.redis.api.RedisNamespace"));
         assertThrows(ClassNotFoundException.class, () -> loader.loadClass("redis.clients.jedis.RedisClient"));
         assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.kingsrook.qbits.email.api.EmailApi"));
         Thread.currentThread().setContextClassLoader(loader);
         loader.loadClass(Probe.class.getName()).getMethod("exercise").invoke(null);
      }
      finally
      {
         Thread.currentThread().setContextClassLoader(previous);
      }
   }

   /***************************************************************************
    ** Loaded in the isolated consumer, including metadata enrichment and delivery.
    ***************************************************************************/
   public static class Probe extends PersonalServiceFixture
   {
      /************************************************************************
       ** Real host setup and authorized presence work without Redis artifacts.
       ************************************************************************/
      public static void exercise() throws Exception
      {
         var probe = new Probe();
         try
         {
            probe.setUp();
            var service = new com.kingsrook.qbits.collaboration.presence.PresenceService();
            String lease = service.openLease("document", "1");
            assertTrue(service.snapshot("document", "1").isEmpty());
            assertEquals(1, service.renewLease(lease, "document", "1", "VIEWING", null, 1L).size());
            service.leave(lease, "document", "1", 2L);
            assertTrue(service.snapshot("document", "1").isEmpty());
            assertFalse(new com.kingsrook.qbits.collaboration.presence.RedisPresenceAudience().allows("untrusted"));
            probe.config.withUserSessionProvider(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(RecipientSessions.class));
            assertEquals(java.util.Optional.of(true), new com.kingsrook.qbits.collaboration.security.CollaborationRecordAccessChecker()
               .canRead("alice", "document", "1"));
            var notice = new com.kingsrook.qbits.collaboration.notify.CollaborationNotification("alice", "Test", "Test",
               com.kingsrook.qbits.collaboration.notify.CollaborationNotification.Category.MENTION, "document", "1", "/app/document/1");
            var receipts = new java.util.concurrent.atomic.AtomicInteger();
            com.kingsrook.qbits.collaboration.notify.Notifications.send(notice, () -> receipts.incrementAndGet());
            assertEquals(0, receipts.get(), "Missing email must not acknowledge delivery");
            probe.config.withNotifier(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(AcceptingNotifier.class));
            com.kingsrook.qbits.collaboration.notify.Notifications.send(notice, () -> receipts.incrementAndGet());
            assertEquals(1, receipts.get(), "Explicit host notifier must still work without email");
         }
         finally
         {
            probe.tearDown();
         }
      }
   }

   /***************************************************************************
    ** Host session lookup makes the notification eligible for actual delivery.
    ***************************************************************************/
   public static class RecipientSessions implements com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface
   {
      /************************************************************************
       ** Fixture recipient has access to the parent record in tenant a.
       ************************************************************************/
      @Override
      public java.util.Optional<com.kingsrook.qqq.backend.core.model.session.QSession> sessionForUser(String userId)
      {
         return (java.util.Optional.of(new com.kingsrook.qqq.backend.core.model.session.QSession()
            .withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference(userId))
            .withPermissions("document.read").withSecurityKeyValue("tenant", "a")));
      }
   }

   /***************************************************************************
    ** A host transport that accepts delivery without the optional email adapter.
    ***************************************************************************/
   public static class AcceptingNotifier implements com.kingsrook.qbits.collaboration.notify.CollaborationNotifier
   {
      /************************************************************************
       ** Test transport has no external delivery side effects.
       ************************************************************************/
      @Override
      public void notify(com.kingsrook.qbits.collaboration.notify.CollaborationNotification notification)
      {
         assertEquals("alice", notification.recipientUserId());
      }
   }
}
