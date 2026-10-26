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


import java.time.Duration;
import com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException;
import com.kingsrook.qbits.collaboration.presence.PresenceRecoveringException;
import com.kingsrook.qbits.collaboration.presence.RedisPresenceStore;
import com.kingsrook.qbits.collaboration.presence.RedisPresenceAudience;
import com.kingsrook.qbits.collaboration.presence.RedisPresenceConfig;
import com.kingsrook.qbits.collaboration.presence.PresenceScopeProvider;
import com.kingsrook.qbits.redis.RedisConnection;
import com.kingsrook.qbits.redis.api.RedisNamespace;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Loss recovery must be shared across independent clients, not node-local memory.
 *******************************************************************************/
class RedisPresenceRecoveryTest extends PersonalServiceFixture
{
   /***************************************************************************
    ** Peer reads cannot prolong cold recovery; normal expiry stays known-empty.
    ***************************************************************************/
   @Test
   void coldRecoveryDeadlineAndIdleKnowledgeAreShared() throws Exception
   {
      try(var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379))
      {
         redis.start();
         var address = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var config = DefaultJedisClientConfig.builder().build();
         try(var first = new RedisConnection(address, config, 2, Duration.ofMillis(500));
             var second = new RedisConnection(address, config, 2, Duration.ofMillis(500)))
         {
            var namespace = new RedisNamespace("test", "cold-idle", "tenant", "presence");
            var a = new RedisPresenceStore(first.scripts(), namespace, Duration.ofSeconds(2), 10);
            var b = new RedisPresenceStore(second.scripts(), namespace, Duration.ofSeconds(2), 10);
            assertThatThrownBy(() -> a.snapshot("document", "1")).isInstanceOf(PresenceRecoveringException.class);
            String guardKey = first.client().keys("*:recovery").iterator().next();
            String coldGuard = first.client().get(guardKey);
            assertThatThrownBy(() -> b.snapshot("document", null)).isInstanceOf(PresenceRecoveringException.class);
            assertThat(first.client().get(guardKey)).isEqualTo(coldGuard);
            long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
            while(true)
            {
               try
               {
                  assertThat(b.snapshot("document", "1")).isEmpty();
                  break;
               }
               catch(PresenceRecoveringException recovering)
               {
                  assertThat(System.nanoTime()).isLessThan(deadline);
                  assertThat(first.client().get(guardKey)).isEqualTo(coldGuard);
                  Thread.sleep(50);
               }
            }
            assertThat(a.snapshot("document", null)).isEmpty();
            assertThat(first.client().get(guardKey)).isEqualTo(coldGuard);
            assertThat(first.client().pttl(guardKey)).isEqualTo(-1L);

            String lease = a.openLease("alice", "Alice", "document", "1");
            awaitVisible(b, lease);
            String activeGuard = first.client().get(guardKey);
            deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
            while(first.client().keys("*").size() > 1)
            {
               assertThat(System.nanoTime()).isLessThan(deadline);
               Thread.sleep(50);
            }
            assertThat(first.client().keys("*")).containsExactly(guardKey);
            assertThat(a.snapshot("document", "1")).isEmpty();
            assertThat(b.snapshot("document", null)).isEmpty();
            assertThat(first.client().get(guardKey)).isEqualTo(activeGuard);
            assertThatThrownBy(() -> b.renewLease("alice", "Alice", lease, "document", "1", "VIEWING", null, 100L))
               .isInstanceOf(PresenceLeaseExpiredException.class);
         }
      }
   }


   /***************************************************************************
    ** Keep a real lease alive while waiting for bounded initial recovery.
    ***************************************************************************/
   private void awaitVisible(RedisPresenceStore store, String lease) throws Exception
   {
      long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      long sequence = 1;
      while(System.nanoTime() < deadline)
      {
         store.renewLease("alice", "Alice", lease, "document", "1", "EDITING", null, sequence++);
         try
         {
            assertThat(store.snapshot("document", "1")).extracting("userId").containsExactly("alice");
            return;
         }
         catch(IllegalStateException recovering)
         {
            assertThat(recovering).hasMessageContaining("recover");
            Thread.sleep(50);
         }
      }
      throw (new AssertionError("Presence did not finish bounded recovery"));
   }


   /***************************************************************************
    ** A cleared dataset is unknown, not known-empty; live clients can repopulate.
    ***************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = { false, true })
   void totalLossRequiresSharedRecoveryBeforeKnownEmpty(boolean retainGuard) throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379))
      {
         redis.start();
         var address = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var clientConfig = DefaultJedisClientConfig.builder().build();
         try(var first = new RedisConnection(address, clientConfig, 2, Duration.ofMillis(500));
             var second = new RedisConnection(address, clientConfig, 2, Duration.ofMillis(500)))
         {
            var namespace = new RedisNamespace("test", "app", "tenant-a", "presence");
            var a = new RedisPresenceStore(first.scripts(), namespace, Duration.ofSeconds(2), 10);
            var b = new RedisPresenceStore(second.scripts(), namespace, Duration.ofSeconds(2), 10);
            String oldLease = a.openLease("alice", "Alice", "document", "1");
            awaitVisible(b, oldLease);
            if(retainGuard)
            {
               var projections = first.client().keys("*").stream().filter(key -> !key.endsWith(":recovery")).toArray(String[]::new);
               first.client().del(projections);
            }
            else
            {
               first.client().flushDB();
            }
            assertThatThrownBy(() -> b.snapshot("document", "1"))
               .isInstanceOf(IllegalStateException.class).hasMessageContaining("recover");
            assertThatThrownBy(() -> a.snapshot("document", null))
               .isInstanceOf(IllegalStateException.class).hasMessageContaining("recover");
            assertThatThrownBy(() -> a.renewLease("alice", "Alice", oldLease, "document", "1", "EDITING", null, 999L))
               .isInstanceOf(PresenceLeaseExpiredException.class);
            String replacement = b.openLease("alice", "Alice", "document", "1");
            assertThat(replacement).isNotNull().isNotEqualTo(oldLease);
            awaitVisible(a, replacement);
            assertThat(b.snapshot("document", null)).extracting("userId").containsExactly("alice");
            assertThat(b.snapshot("document", "unoccupied")).isEmpty();
         }
      }
   }


   /***************************************************************************
    ** Guard loss cleans the table once and untouched record rooms on demand.
    ***************************************************************************/
   @Test
   void missingGuardRetiresUnvisitedRecordsWithoutRemovingNewLeases() throws Exception
   {
      try(var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379))
      {
         redis.start();
         try(var connection = new RedisConnection(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().build(), 2, Duration.ofMillis(500)))
         {
            var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "guard-loss", "tenant", "presence"), Duration.ofSeconds(2), 2);
            String oldAlice = store.openLease("alice", "Alice", "document", "1");
            String oldBob = store.openLease("bob", "Bob", "document", "2");
            connection.client().del(connection.client().keys("*:recovery").toArray(String[]::new));
            assertThatThrownBy(() -> store.snapshot("document", null)).isInstanceOf(PresenceRecoveringException.class);
            String alice = store.openLease("alice", "Alice", "document", "1");
            String bob = store.openLease("bob", "Bob", "document", "2");
            assertThatThrownBy(() -> store.renewLease("alice", "Alice", oldAlice, "document", "1", "VIEWING", null, 99L))
               .isInstanceOf(PresenceLeaseExpiredException.class);
            assertThatThrownBy(() -> store.renewLease("bob", "Bob", oldBob, "document", "2", "VIEWING", null, 99L))
               .isInstanceOf(PresenceLeaseExpiredException.class);
            assertThat(store.renewLease("bob", "Bob", bob, "document", "2", "VIEWING", null, 1L)).isTrue();
            awaitVisible(store, alice);
            assertThat(store.snapshot("document", "1")).extracting("userId").containsExactly("alice");
         }
      }
   }


   /***************************************************************************
    ** Restore must happen in maintenance, followed by a fresh host-owned generation.
    ** Replaying both old guard and lease bytes cannot authorize the new deployment.
    ***************************************************************************/
   @Test
   void restoredImageCannotAuthorizeRotatedHostGeneration() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379))
      {
         redis.start();
         var address = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var clientConfig = DefaultJedisClientConfig.builder().build();
         try(var first = new RedisConnection(address, clientConfig, 2, Duration.ofMillis(500));
             var second = new RedisConnection(address, clientConfig, 2, Duration.ofMillis(500)))
         {
            var oldNamespace = RedisPresenceAudience.namespace(recoveryConfig("before-restore"));
            var oldStore = new RedisPresenceStore(first.scripts(), oldNamespace, Duration.ofSeconds(2), 10);
            String oldLease = oldStore.openLease("alice", "Alice", "document", "1");
            awaitVisible(oldStore, oldLease);
            var image = new java.util.HashMap<String, byte[]>();
            for(String key : first.client().keys("*"))
            {
               image.put(key, first.client().dump(key));
            }
            oldStore.leave("alice", oldLease, "document", "1", 999L);
            first.client().flushDB();
            for(var entry : image.entrySet())
            {
               first.client().restore(entry.getKey(), 0, entry.getValue());
            }
            var restoredNamespace = RedisPresenceAudience.namespace(recoveryConfig("after-restore"));
            var a = new RedisPresenceStore(first.scripts(), restoredNamespace, Duration.ofSeconds(2), 10);
            var b = new RedisPresenceStore(second.scripts(), restoredNamespace, Duration.ofSeconds(2), 10);
            assertThatThrownBy(() -> a.renewLease("alice", "Alice", oldLease, "document", "1", "EDITING", null, 1000L))
               .isInstanceOf(PresenceLeaseExpiredException.class);
            assertThatThrownBy(() -> b.snapshot("document", "1"))
               .isInstanceOf(IllegalStateException.class).hasMessageContaining("recover");
            String fresh = b.openLease("alice", "Alice", "document", "1");
            awaitVisible(a, fresh);
            assertThat(b.snapshot("document", "1")).extracting("userId").containsExactly("alice");
            assertThatThrownBy(() -> b.renewLease("alice", "Alice", oldLease, "document", "1", "EDITING", null, 1001L))
               .isInstanceOf(PresenceLeaseExpiredException.class);
         }
      }
   }


   /***************************************************************************
    ** Generation is bounded deployment configuration, never browser input.
    ***************************************************************************/
   @Test
   void validatesHostGeneration()
   {
      var errors = new java.util.ArrayList<String>();
      recoveryConfig("valid-20261026").validate(errors);
      assertThat(errors).isEmpty();
      for(String invalid : java.util.Arrays.asList(null, "", " ", "x".repeat(65), "invalid:namespace"))
      {
         errors.clear();
         recoveryConfig(invalid).validate(errors);
         assertThat(errors).anySatisfy(error -> assertThat(error).contains("recovery generation"));
      }
   }


   /***************************************************************************
    ** Recovery readiness must not depend on which node's lease policy is shorter.
    ***************************************************************************/
   @Test
   void refusesInconsistentNodeLeaseLifetimes() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379))
      {
         redis.start();
         try(var connection = new RedisConnection(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().build(), 2, Duration.ofMillis(500)))
         {
            var namespace = new RedisNamespace("test", "app", "ttl-policy", "presence");
            var first = new RedisPresenceStore(connection.scripts(), namespace, Duration.ofSeconds(2), 10);
            var second = new RedisPresenceStore(connection.scripts(), namespace, Duration.ofSeconds(3), 10);
            assertThatThrownBy(() -> first.snapshot("document", null))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceRecoveringException.class);
            assertThatThrownBy(() -> second.snapshot("document", null))
               .isInstanceOf(redis.clients.jedis.exceptions.JedisException.class).hasMessageContaining("lifetime differs");
         }
      }
   }


   /***************************************************************************
    ** Use the real host-configured storage/audience namespace derivation.
    ***************************************************************************/
   private RedisPresenceConfig recoveryConfig(String generation)
   {
      return (new RedisPresenceConfig().withConnectionName("presence").withDeployment("test").withApplication("app")
         .withScopeProvider(new QCodeReference(FixtureScope.class)).withRecoveryGeneration(generation));
   }


   /***************************************************************************
    ** Tenant scope belongs to the host; this test uses one fixed authenticated scope.
    ***************************************************************************/
   public static class FixtureScope implements PresenceScopeProvider
   {
      /***************************************************************************
       ** Return the fixture's host-selected tenant, without client-supplied fields.
       ***************************************************************************/
      @Override
      public String tenantScope()
      {
         return ("tenant-a");
      }
   }
}
