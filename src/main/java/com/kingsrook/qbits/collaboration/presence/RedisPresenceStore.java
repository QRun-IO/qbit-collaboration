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


package com.kingsrook.qbits.collaboration.presence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.kingsrook.qbits.redis.api.RedisNamespace;
import com.kingsrook.qbits.redis.api.RedisScript;
import com.kingsrook.qbits.redis.api.RedisScriptExecutor;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;

/*******************************************************************************
 ** Shared ephemeral presence. Callers must authorize identity, scope and fields.
 ** Scripts atomically update record/table membership using the Redis server clock.
 *******************************************************************************/
public final class RedisPresenceStore
{
   private static final RedisScript MUTATE = new RedisScript(resource("redis-mutate.lua"));
   private static final RedisScript SNAPSHOT = new RedisScript(resource("redis-snapshot.lua"));
   private final RedisScriptExecutor client;
   private final RedisNamespace namespace;
   private final long ttlMillis;
   private final int capacity;

   /***************************************************************************
    ** Limit each indexed room, including departure markers, to bound script work.
    ***************************************************************************/
   public RedisPresenceStore(RedisScriptExecutor client, RedisNamespace namespace, Duration ttl, int capacity)
   {
      this.client = Objects.requireNonNull(client);
      this.namespace = Objects.requireNonNull(namespace);
      this.ttlMillis = Objects.requireNonNull(ttl).toMillis();
      if(ttlMillis < 1 || ttlMillis > Duration.ofMinutes(5).toMillis() || capacity < 1 || capacity > 10000)
      {
         throw (new IllegalArgumentException("Presence requires a positive TTL of at most five minutes and capacity of 1 to 10000"));
      }
      this.capacity = capacity;
   }

   /***************************************************************************
    ** Renew a connection; duplicate or delayed sequences never extend its lease.
    ***************************************************************************/
   public boolean heartbeat(String user, String displayName, String connection, String table, String pk, String mode, String field, Long sequence)
   {
      if(connection != null && connection.startsWith("lease:"))
      {
         throw (new PresenceLeaseExpiredException());
      }
      if(!List.of("VIEWING", "EDITING").contains(mode == null ? "" : mode))
      {
         throw (new IllegalArgumentException("Invalid presence mode"));
      }
      return (mutate(user, displayName, connection, table, pk, mode, field, sequence, false, "legacy"));
   }

   /***************************************************************************
    ** Reserve an invisible server-issued lease. Null means room capacity refused.
    ** Only a subsequent renewal makes it visible; lost responses expire silently.
    ***************************************************************************/
   public String openLease(String user, String displayName, String table, String pk)
   {
      String connection = "lease:" + java.util.UUID.randomUUID();
      return (mutate(user, displayName, connection, table, pk, "VIEWING", null, 0L, false, "open") ? connection : null);
   }

   /***************************************************************************
    ** Activate or renew existing authority, never recreate missing/departed state.
    ** Callers must reauthorize identity, parent visibility and focused fields.
    ***************************************************************************/
   public boolean renewLease(String user, String displayName, String connection, String table, String pk, String mode, String field, Long sequence)
   {
      if(connection == null || !connection.startsWith("lease:") || sequence == null || sequence < 1)
      {
         throw (new IllegalArgumentException("A server-issued lease and positive sequence are required"));
      }
      if(!List.of("VIEWING", "EDITING").contains(mode == null ? "" : mode))
      {
         throw (new IllegalArgumentException("Invalid presence mode"));
      }
      return (mutate(user, displayName, connection, table, pk, mode, field, sequence, false, "renew"));
   }

   /***************************************************************************
    ** Retain a bounded departure marker so older HTTP renewals cannot undo leave.
    ***************************************************************************/
   public boolean leave(String user, String connection, String table, String pk, Long sequence)
   {
      return (mutate(user, user, connection, table, pk, "VIEWING", null, sequence, true, "legacy"));
   }

   /***************************************************************************
    ** Redis failures propagate; they must never be presented as an empty room.
    ***************************************************************************/
   public List<PresenceRegistry.Viewer> snapshot(String table, String pk)
   {
      List<String> keys = roomKeys(table, pk, true);
      Object result = client.execute(SNAPSHOT, keys, List.of(pk == null ? "" : ":" + encode(pk, 1024)));
      Map<String, Stored> users = new TreeMap<>();
      for(Object value : (List<?>) result)
      {
         try
         {
            Stored entry = JsonUtils.toObject((String) value, Stored.class);
            if(!entry.departed() && !entry.pending())
            {
               users.merge(entry.userId(), entry, (old, next) ->
                  (!Objects.equals(old.mode(), next.mode()) ? "EDITING".equals(next.mode()) : next.at() >= old.at()) ? next : old);
            }
         }
         catch(IOException e)
         {
            throw (new IllegalStateException("Invalid stored presence data", e));
         }
      }
      return (users.values().stream().map(entry -> new PresenceRegistry.Viewer(entry.userId(), entry.displayName(), entry.mode(),
         pk == null ? null : entry.fieldName())).toList());
   }

   /***************************************************************************
    ** Validate bounded inputs before sending any Lua command to Redis.
    ***************************************************************************/
   private boolean mutate(String user, String displayName, String connection, String table, String pk, String mode, String field, Long sequence, boolean departed, String operation)
   {
      String member = encode(user, 255) + ":" + encode(connection, 255) + ":" + (pk == null ? "table" : encode(pk, 1024));
      encode(displayName, 1024);
      if(field != null)
      {
         encode(field, 255);
      }
      if(sequence != null && (sequence < 0 || sequence > 9007199254740991L))
      {
         throw (new IllegalArgumentException("Presence sequence must be a nonnegative safe integer"));
      }
      Stored value = new Stored(user, displayName, mode, pk == null ? null : field, 0, sequence, departed, false, false);
      Object result = client.execute(MUTATE, roomKeys(table, pk, true), List.of(member, JsonUtils.toJson(value),
         sequence == null ? "" : sequence.toString(), departed ? "1" : "0", Long.toString(ttlMillis), Integer.toString(capacity), pk == null ? "1" : "0", operation));
      if(((Number) result).longValue() == -1)
      {
         throw (new PresenceLeaseExpiredException());
      }
      return (((Number) result).longValue() == 1);
   }

   /***************************************************************************
    ** One table hash tag keeps record/table updates atomic on Redis Cluster slots.
    ***************************************************************************/
   private List<String> roomKeys(String table, String pk, boolean includeTable)
   {
      String base = "qqq:presence:{" + digest("SHA-256", namespace.key(table)) + "}";
      List<String> keys = new ArrayList<>();
      if(pk != null)
      {
         String record = base + ":record:" + encode(pk, 1024);
         keys.add(record + ":expiry");
         keys.add(record + ":state");
      }
      if(pk == null || includeTable)
      {
         keys.add(base + ":table:expiry");
         keys.add(base + ":table:state");
      }
      return (keys);
   }

   /***************************************************************************
    ** Distinguish arbitrary identifiers without allowing delimiter injection.
    ***************************************************************************/
   private static String encode(String value, int maximumBytes)
   {
      if(value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
      {
         throw (new IllegalArgumentException("Invalid or oversized presence identifier"));
      }
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
   }

   /***************************************************************************
    ** Hash public routing identifiers and scripts using JDK algorithms.
    ***************************************************************************/
   private static String digest(String algorithm, String value)
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8))));
      }
      catch(NoSuchAlgorithmException e)
      {
         throw (new IllegalStateException("Required digest algorithm unavailable", e));
      }
   }

   /***************************************************************************
    ** Load immutable scripts from the application artifact.
    ***************************************************************************/
   private static String resource(String name)
   {
      try(var stream = RedisPresenceStore.class.getResourceAsStream("/presence/" + name))
      {
         if(stream == null)
         {
            throw (new IllegalStateException("Missing presence script"));
         }
         return (new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("Cannot load presence script", e));
      }
   }

   /***************************************************************************
    ** Internal lease data, never returned directly to a browser.
    ***************************************************************************/
   public record Stored(String userId, String displayName, String mode, String fieldName, long at, Long sequence, boolean departed, boolean pending, boolean leased)
   {
   }

}
