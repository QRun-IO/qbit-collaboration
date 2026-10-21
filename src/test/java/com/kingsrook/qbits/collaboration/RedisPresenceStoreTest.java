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
import com.kingsrook.qbits.collaboration.presence.RedisPresenceStore;
import com.kingsrook.qbits.redis.RedisConnection;
import com.kingsrook.qbits.redis.api.RedisNamespace;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Independent clients exercise server-side ordering, expiry and shared snapshots.
 *******************************************************************************/
class RedisPresenceStoreTest
{
   /***************************************************************************
    ** Server-issued leases cross nodes but cannot survive departure or data loss.
    ***************************************************************************/
   @Test
   void issuedLeaseRequiresLiveAuthority() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var address = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var config = DefaultJedisClientConfig.builder().build();
         try(var first = new RedisConnection(address, config, 2, Duration.ofMillis(500));
             var second = new RedisConnection(address, config, 2, Duration.ofMillis(500)))
         {
            var scope = new RedisNamespace("test", "app", "issued", "presence");
            var a = new RedisPresenceStore(first.scripts(), scope, Duration.ofSeconds(45), 10);
            var b = new RedisPresenceStore(second.scripts(), scope, Duration.ofSeconds(45), 10);
            String lease = a.openLease("one", "One", "orders", "1");
            assertThat(lease).startsWith("lease:");
            assertThat(b.snapshot("orders", "1")).isEmpty();
            assertThat(b.snapshot("orders", null)).isEmpty();
            assertThatThrownBy(() -> b.renewLease("other", "Other", lease, "orders", "1", "EDITING", null, 1L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThatThrownBy(() -> b.renewLease("one", "One", lease, "orders", "2", "EDITING", null, 1L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThat(b.renewLease("one", "One", lease, "orders", "1", "EDITING", "status", 1L)).isTrue();
            var expiryKey = first.client().keys("*:record:*:expiry").iterator().next();
            var expiryBefore = first.client().zrangeWithScores(expiryKey, 0, -1);
            assertThat(a.renewLease("one", "One", lease, "orders", "1", "VIEWING", null, 1L)).isTrue();
            assertThat(first.client().zrangeWithScores(expiryKey, 0, -1)).isEqualTo(expiryBefore);
            assertThat(a.snapshot("orders", "1")).hasSize(1);
            assertThat(a.snapshot("orders", "1").getFirst().mode()).isEqualTo("EDITING");
            assertThat(a.snapshot("orders", null)).allSatisfy(viewer -> assertThat(viewer.fieldName()).isNull());
            a.leave("one", lease, "orders", "1", 3L);
            assertThatThrownBy(() -> b.renewLease("one", "One", lease, "orders", "1", "EDITING", null, 2L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThatThrownBy(() -> b.renewLease("one", "One", lease, "orders", "1", "EDITING", null, 4L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);

            String replacement = b.openLease("one", "One", "orders", "1");
            assertThat(replacement).isNotEqualTo(lease);
            assertThat(a.renewLease("one", "One", replacement, "orders", "1", "VIEWING", null, 1L)).isTrue();
            first.client().flushDB();
            assertThatThrownBy(() -> b.renewLease("one", "One", replacement, "orders", "1", "VIEWING", null, 2L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThatThrownBy(() -> b.heartbeat("one", "One", replacement, "orders", "1", "VIEWING", null, 2L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThat(first.client().dbSize()).isZero();
         }
      }
   }

   /***************************************************************************
    ** Abandoned reservations consume bounded capacity and expire invisibly.
    ***************************************************************************/
   @Test
   void issuedLeaseExpiryDoesNotRecreateMembership() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         try(var connection = new RedisConnection(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().build(), 2, Duration.ofMillis(500)))
         {
            var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "app", "issued-expiry", "presence"), Duration.ofMillis(150), 1);
            String pending = store.openLease("one", "One", "orders", "1");
            assertThat(store.openLease("two", "Two", "orders", "2")).isNull();
            assertThat(store.snapshot("orders", null)).isEmpty();
            Thread.sleep(250);
            assertThatThrownBy(() -> store.renewLease("one", "One", pending, "orders", "1", "EDITING", null, 1L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            String active = store.openLease("two", "Two", "orders", "2");
            assertThat(store.renewLease("two", "Two", active, "orders", "2", "EDITING", null, 1L)).isTrue();
            Thread.sleep(250);
            assertThatThrownBy(() -> store.renewLease("two", "Two", active, "orders", "2", "EDITING", null, 2L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThat(store.snapshot("orders", null)).isEmpty();
            String departed = store.openLease("two", "Two", "orders", "2");
            assertThat(departed).isNotNull();
            store.renewLease("two", "Two", departed, "orders", "2", "EDITING", null, 1L);
            store.leave("two", departed, "orders", "2", 3L);
            Thread.sleep(250);
            assertThatThrownBy(() -> store.renewLease("two", "Two", departed, "orders", "2", "EDITING", null, 2L))
               .isInstanceOf(com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class);
            assertThat(store.snapshot("orders", null)).isEmpty();
         }
      }
   }

   /***************************************************************************
    ** Characterize the open recovery gap: all projections lost together look new.
    ** This is evidence for #1295, not an assertion of the desired final contract.
    ***************************************************************************/
   @Test
   void completeDataLossCurrentlyAcceptsDelayedHeartbeat() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var address = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var config = DefaultJedisClientConfig.builder().build();
         try(var first = new RedisConnection(address, config, 2, Duration.ofMillis(500));
             var second = new RedisConnection(address, config, 2, Duration.ofMillis(500)))
         {
            var scope = new RedisNamespace("test", "app", "recovery", "presence");
            var a = new RedisPresenceStore(first.scripts(), scope, Duration.ofSeconds(45), 10);
            var b = new RedisPresenceStore(second.scripts(), scope, Duration.ofSeconds(45), 10);
            a.heartbeat("active", "Active", "active-tab", "orders", "1", "VIEWING", null, 1L);
            a.heartbeat("departed", "Departed", "old-tab", "orders", "1", "EDITING", null, 1L);
            b.leave("departed", "old-tab", "orders", "1", 3L);
            assertThat(b.snapshot("orders", "1")).extracting("userId").containsExactly("active");

            // Only this disposable test server is cleared. Both clients stay alive.
            first.client().flushDB();
            assertThat(b.snapshot("orders", "1")).isEmpty();
            assertThat(a.heartbeat("departed", "Departed", "old-tab", "orders", "1", "EDITING", null, 2L)).isTrue();
            assertThat(b.snapshot("orders", "1")).extracting("userId").containsExactly("departed");
            assertThat(b.snapshot("orders", null)).extracting("userId").containsExactly("departed");
         }
      }
   }

   /***************************************************************************
    ** Characterize the separate ordering gap after a departure marker expires.
    ** A newer room member keeps both projections alive throughout this test.
    ***************************************************************************/
   @Test
   void expiredDepartureCurrentlyAcceptsDelayedHeartbeat() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         try(var connection = new RedisConnection(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().build(), 2, Duration.ofMillis(500)))
         {
            var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "app", "late", "presence"), Duration.ofMillis(150), 10);
            store.heartbeat("departed", "Departed", "tab", "orders", "1", "EDITING", null, 1L);
            store.leave("departed", "tab", "orders", "1", 3L);
            store.heartbeat("departed", "Departed", "tab", "orders", "1", "EDITING", null, 2L);
            assertThat(store.snapshot("orders", "1")).isEmpty();

            // Longer-lived peer proves this does not depend on losing entire keys.
            var peer = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "app", "late", "presence"), Duration.ofSeconds(45), 10);
            peer.heartbeat("active", "Active", "peer-tab", "orders", "1", "VIEWING", null, 1L);
            Thread.sleep(250);
            assertThat(peer.snapshot("orders", "1")).extracting("userId").containsExactly("active");
            store.heartbeat("departed", "Departed", "tab", "orders", "1", "EDITING", null, 2L);
            assertThat(peer.snapshot("orders", "1")).extracting("userId").containsExactly("active", "departed");
         }
      }
   }

   /***************************************************************************
    ** Six people share one record, with two editing; tabs deduplicate by user.
    ***************************************************************************/
   @Test
   void sharedRoomOrderingIsolationAndExpiry() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         var address = new HostAndPort(redis.getHost(), redis.getMappedPort(6379));
         var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(500).build();
         try(var first = new RedisConnection(address, config, 4, Duration.ofMillis(500));
             var second = new RedisConnection(address, config, 4, Duration.ofMillis(500)))
         {
            var scope = new RedisNamespace("test", "app", "tenant-a", "presence");
            var a = new RedisPresenceStore(first.scripts(), scope, Duration.ofSeconds(45), 100);
            var b = new RedisPresenceStore(second.scripts(), scope, Duration.ofSeconds(45), 100);
            for(int i = 0; i < 6; i++)
            {
               assertThat((i % 2 == 0 ? a : b).heartbeat("user-" + i, "Person " + i, "tab-" + i, "orders", "123",
                  i < 2 ? "EDITING" : "VIEWING", i < 2 ? "status" : null, 1L)).isTrue();
            }
            assertThat(b.snapshot("orders", "123")).hasSize(6).isEqualTo(a.snapshot("orders", "123"));
            assertThat(b.snapshot("orders", "123").stream().filter(viewer -> viewer.mode().equals("EDITING"))).hasSize(2);
            assertThat(b.snapshot("orders", null)).hasSize(6).allSatisfy(viewer -> assertThat(viewer.fieldName()).isNull());
            assertThat(a.heartbeat("user-0", "Person 0", "extra-tab", "orders", "123", "VIEWING", null, 1L)).isTrue();
            assertThat(b.snapshot("orders", "123")).hasSize(6);
            assertThat(b.snapshot("orders", "123").getFirst().mode()).isEqualTo("EDITING");
            assertThat(b.leave("user-0", "tab-0", "orders", "123", 3L)).isTrue();
            assertThat(a.heartbeat("user-0", "Person 0", "tab-0", "orders", "123", "EDITING", "status", 2L)).isTrue();
            assertThat(a.snapshot("orders", "123").getFirst().mode()).isEqualTo("VIEWING");
            assertThat(a.heartbeat("user-0", "Person 0", "tab-0", "orders", "123", "EDITING", "status", 3L)).isTrue();
            assertThat(a.snapshot("orders", "123").getFirst().mode()).isEqualTo("VIEWING");
            assertThat(a.heartbeat("user-0", "Person 0", "tab-0", "orders", "123", "EDITING", "status", 4L)).isTrue();
            assertThat(b.snapshot("orders", "123").getFirst().mode()).isEqualTo("EDITING");
            var tenantB = new RedisPresenceStore(second.scripts(), new RedisNamespace("test", "app", "tenant-b", "presence"), Duration.ofSeconds(45), 100);
            assertThat(tenantB.snapshot("orders", "123")).isEmpty();
            assertThat(a.snapshot("orders", "124")).isEmpty();

            try(var writers = java.util.concurrent.Executors.newFixedThreadPool(4))
            {
               var writes = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
               for(long sequence = 10; sequence < 50; sequence++)
               {
                  final long requestSequence = sequence;
                  writes.add(writers.submit(() -> (requestSequence % 2 == 0 ? a : b).heartbeat("racing-user", "Racing", "racing-tab",
                     "orders", "124", requestSequence == 49 ? "EDITING" : "VIEWING", null, requestSequence)));
               }
               for(var write : writes)
               {
                  assertThat(write.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
               }
            }
            assertThat(a.snapshot("orders", "124")).hasSize(1);
            assertThat(a.snapshot("orders", "124").getFirst().mode()).isEqualTo("EDITING");
            assertThat(b.leave("racing-user", "racing-tab", "orders", "124", 50L)).isTrue();
            assertThat(a.heartbeat("racing-user", "Racing", "racing-tab", "orders", "124", "EDITING", null, 49L)).isTrue();
            assertThat(a.snapshot("orders", "124")).isEmpty();

            var shortLease = new RedisPresenceStore(first.scripts(), new RedisNamespace("test", "app", "short", "presence"), Duration.ofMillis(150), 10);
            assertThat(shortLease.heartbeat("user", "Person", "tab", "orders", "123", "EDITING", "status", 1L)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while(!shortLease.snapshot("orders", "123").isEmpty() && System.nanoTime() < deadline)
            {
               Thread.sleep(20);
            }
            assertThat(shortLease.snapshot("orders", "123")).isEmpty();
            assertThat(shortLease.snapshot("orders", null)).isEmpty();
            redis.execInContainer("redis-cli", "SCRIPT", "FLUSH");
            assertThat(b.snapshot("orders", "123")).hasSize(6);
            redis.stop();
            assertThatThrownBy(() -> a.snapshot("orders", "123")).isInstanceOf(redis.clients.jedis.exceptions.JedisException.class);
         }
      }
   }

   /***************************************************************************
    ** A full table cannot leave a half-written record membership behind.
    ***************************************************************************/
   @Test
   void capacityChecksAreAtomic() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         try(var connection = new RedisConnection(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().build(), 2, Duration.ofMillis(500)))
         {
            var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "app", "capacity", "presence"), Duration.ofSeconds(45), 1);
            assertThat(store.heartbeat("one", "One", "tab", "orders", "1", "VIEWING", null, 1L)).isTrue();
            assertThat(store.heartbeat("two", "Two", "tab", "orders", "2", "VIEWING", null, 1L)).isFalse();
            assertThat(store.snapshot("orders", "2")).isEmpty();
            assertThat(store.snapshot("orders", null)).hasSize(1);
            assertThat(store.leave("one", "tab", "orders", "1", 2L)).isTrue();
            assertThat(store.snapshot("orders", null)).isEmpty();
            assertThat(store.heartbeat("two", "Two", "tab", "orders", "2", "VIEWING", null, 1L)).isFalse();
            assertThatThrownBy(() -> store.heartbeat("one", "One", "tab", "orders", "1", "INVALID", null, 3L)).isInstanceOf(IllegalArgumentException.class);
         }
      }
   }

   /***************************************************************************
    ** Losing one half of a room must not report empty or partially renew a lease.
    ***************************************************************************/
   @Test
   void partialRoomLossIsUnavailable() throws Exception
   {
      try(GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379))
      {
         redis.start();
         try(var connection = new RedisConnection(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().build(), 2, Duration.ofMillis(500)))
         {
            for(String suffix : java.util.List.of("state", "expiry"))
            {
               var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "app", suffix, "presence"), Duration.ofSeconds(45), 10);
               assertThat(store.heartbeat("one", "One", "tab", "orders", "1", "VIEWING", null, 1L)).isTrue();
               // KEYS is confined to this disposable test server, never the application path.
               String recordKey = connection.client().keys("*:record:*:" + suffix).iterator().next();
               String tableKey = recordKey.substring(0, recordKey.indexOf(":record:")) + ":table:state";
               var before = connection.client().hgetAll(tableKey);
               connection.client().del(recordKey);
               assertThatThrownBy(() -> store.snapshot("orders", "1"))
                  .isInstanceOf(redis.clients.jedis.exceptions.JedisException.class).hasMessageContaining("incomplete");
               assertThatThrownBy(() -> store.heartbeat("one", "One", "tab", "orders", "1", "EDITING", "status", 2L))
                  .isInstanceOf(redis.clients.jedis.exceptions.JedisException.class).hasMessageContaining("incomplete");
               assertThat(connection.client().hgetAll(tableKey)).isEqualTo(before);
               connection.client().flushDB();
            }
            for(String room : java.util.List.of("record", "table"))
            {
               var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("test", "app", room, "presence"), Duration.ofSeconds(45), 10);
               store.heartbeat("one", "One", "tab", "orders", "1", "VIEWING", null, 1L);
               store.leave("one", "tab", "orders", "1", 3L);
               var lostKeys = connection.client().keys(room.equals("record") ? "*:record:*" : "*:table:*");
               connection.client().del(lostKeys.toArray(String[]::new));
               assertThatThrownBy(() -> store.snapshot("orders", "1"))
                  .isInstanceOf(redis.clients.jedis.exceptions.JedisException.class).hasMessageContaining("incomplete");
               assertThatThrownBy(() -> store.heartbeat("one", "One", "tab", "orders", "1", "EDITING", null, 2L))
                  .isInstanceOf(redis.clients.jedis.exceptions.JedisException.class).hasMessageContaining("incomplete");
               assertThat(connection.client().keys(room.equals("record") ? "*:record:*" : "*:table:*")).isEmpty();
               connection.client().flushDB();
            }
         }
      }
   }
}
