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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import com.kingsrook.qbits.collaboration.presence.PresenceRecoveringException;
import com.kingsrook.qbits.collaboration.presence.RedisPresenceStore;
import com.kingsrook.qbits.redis.RedisConnection;
import com.kingsrook.qbits.redis.api.RedisNamespace;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;

/*******************************************************************************
 ** Explicit local diagnostic, not a CI latency gate or deployment capacity claim.
 *******************************************************************************/
public class RedisPresenceRecoveryCostProbe
{
   /***************************************************************************
    ** Compare issued-lease renewal cost during real recovery and after readiness.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      int count = args.length == 0 ? 1000 : Integer.parseInt(args[0]);
      if(count < 10 || count > 10000 || count % 10 != 0)
      {
         throw (new IllegalArgumentException("Use a multiple of ten between 10 and 10000"));
      }
      try(var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379))
      {
         redis.start();
         String host = "localhost".equals(redis.getHost()) ? "127.0.0.1" : redis.getHost();
         try(var connection = new RedisConnection(new HostAndPort(host, redis.getMappedPort(6379)),
            DefaultJedisClientConfig.builder().socketTimeoutMillis(5000).build(), 1, Duration.ofSeconds(1)))
         {
            var store = new RedisPresenceStore(connection.scripts(), new RedisNamespace("cost", "local", "tenant", "presence"), Duration.ofSeconds(45), count);
            String[] leases = new String[count];
            long[] sequences = new long[count];
            long start = System.nanoTime();
            for(int i = 0; i < count; i++)
            {
               leases[i] = store.openLease("user" + i, "Person " + i, "document", Integer.toString(i / 10));
               renew(store, leases, sequences, i);
            }
            double populationMillis = (System.nanoTime() - start) / 1_000_000.0;
            requireRecovering(store);
            Map<String, Object> cold = sample(store, leases, sequences);
            requireRecovering(store);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while(true)
            {
               for(int i = 0; i < count; i++)
               {
                  renew(store, leases, sequences, i);
               }
               try
               {
                  if(store.snapshot("document", null).size() != count)
                  {
                     throw (new AssertionError("Population did not survive recovery"));
                  }
                  break;
               }
               catch(PresenceRecoveringException expected)
               {
                  if(System.nanoTime() >= deadline)
                  {
                     throw (new AssertionError("Recovery exceeded deadline"));
                  }
                  Thread.sleep(5000);
               }
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("entries", count);
            result.put("samplesPerPhase", 200);
            result.put("populationMillis", populationMillis);
            result.put("recoveryRenewMillis", cold);
            result.put("readyRenewMillis", sample(store, leases, sequences));
            result.put("scope", "One JVM, one connection, loopback Redis, no TLS/HTTP; diagnostic only");
            System.out.println(JsonUtils.toJson(result));
         }
      }
   }

   /***************************************************************************
    ** Sample the same issued leases with increasing sequence numbers.
    ***************************************************************************/
   private static Map<String, Object> sample(RedisPresenceStore store, String[] leases, long[] sequences)
   {
      double[] times = new double[200];
      for(int i = 0; i < times.length; i++)
      {
         long before = System.nanoTime();
         renew(store, leases, sequences, i % leases.length);
         times[i] = (System.nanoTime() - before) / 1_000_000.0;
      }
      Arrays.sort(times);
      return (Map.of("p50", times[99], "p95", times[189], "p99", times[197], "max", times[199]));
   }

   /***************************************************************************
    ** Renewal must not silently fail while measuring its elapsed time.
    ***************************************************************************/
   private static void renew(RedisPresenceStore store, String[] leases, long[] sequences, int i)
   {
      if(!store.renewLease("user" + i, "Person " + i, leases[i], "document", Integer.toString(i / 10), "VIEWING", null, ++sequences[i]))
      {
         throw (new AssertionError("Renewal refused"));
      }
   }

   /***************************************************************************
    ** Do not label a measurement cold if the actual recovery window has ended.
    ***************************************************************************/
   private static void requireRecovering(RedisPresenceStore store)
   {
      try
      {
         store.snapshot("document", "0");
      }
      catch(PresenceRecoveringException expected)
      {
         return;
      }
      throw (new AssertionError("Recovery finished before cold measurement"));
   }
}
