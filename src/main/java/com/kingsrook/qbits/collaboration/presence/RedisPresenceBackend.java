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

import java.time.Duration;
import java.util.List;
import com.kingsrook.qbits.redis.api.RedisAccess;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QServiceUnavailableException;

/*******************************************************************************
 ** Request-scoped Redis adapter; tenant identity is re-derived for every request.
 *******************************************************************************/
public class RedisPresenceBackend implements PresenceBackend
{
   private final RedisPresenceStore store;
   private final String audience;

   /***************************************************************************
    ** Require a running named pool and an explicit host-derived tenant namespace.
    ***************************************************************************/
   public RedisPresenceBackend(RedisPresenceConfig config) throws QServiceUnavailableException
   {
      try
      {
         var namespace = RedisPresenceAudience.namespace(config);
         audience = namespace.key("audience");
         var client = RedisAccess.scripts(QContext.getQInstance(), config.getConnectionName());
         store = new RedisPresenceStore(client, namespace, Duration.ofMillis(PresenceRegistry.TTL_MILLIS), config.getCapacity());
      }
      catch(Exception failure)
      {
         throw (unavailable());
      }
   }

   /***************************************************************************
    ** Reservations are invisible and do not require a client refresh hint.
    ***************************************************************************/
   @Override
   public String openLease(String user, String name, String table, String pk) throws QServiceUnavailableException
   {
      try
      {
         return (store.openLease(user, name, table, pk));
      }
      catch(RuntimeException failure)
      {
         throw (unavailable());
      }
   }

   /***************************************************************************
    ** Preserve expiry as a rejoin signal, distinct from infrastructure failure.
    ***************************************************************************/
   @Override
   public boolean renewLease(String user, String name, String connection, String table, String pk, String mode, String field, Long sequence) throws QServiceUnavailableException
   {
      try
      {
         boolean accepted = store.renewLease(user, name, connection, table, pk, mode, field, sequence);
         if(accepted)
         {
            changed(table, pk);
         }
         return (accepted);
      }
      catch(PresenceLeaseExpiredException failure)
      {
         throw (failure);
      }
      catch(RuntimeException failure)
      {
         throw (unavailable());
      }
   }

   /***************************************************************************
    ** Do not fall back to isolated local state when a shared store is unavailable.
    ***************************************************************************/
   @Override
   public boolean heartbeat(String user, String name, String connection, String table, String pk, String mode, String field, Long sequence) throws QServiceUnavailableException
   {
      try
      {
         boolean accepted = store.heartbeat(user, name, connection, table, pk, mode, field, sequence);
         if(accepted)
         {
            changed(table, pk);
         }
         return (accepted);
      }
      catch(RuntimeException failure)
      {
         throw (unavailable());
      }
   }

   /***************************************************************************
    ** If unavailable, the existing lease still expires using Redis server time.
    ***************************************************************************/
   @Override
   public void leave(String user, String connection, String table, String pk, Long sequence) throws QServiceUnavailableException
   {
      try
      {
         if(!store.leave(user, connection, table, pk, sequence))
         {
            throw (unavailable());
         }
         changed(table, pk);
      }
      catch(RuntimeException failure)
      {
         throw (unavailable());
      }
   }

   /***************************************************************************
    ** Failure is explicit; an empty result is reserved for a successfully read room.
    ***************************************************************************/
   @Override
   public List<PresenceRegistry.Viewer> snapshot(String table, String pk) throws QServiceUnavailableException
   {
      try
      {
         return (store.snapshot(table, pk));
      }
      catch(RuntimeException failure)
      {
         throw (unavailable());
      }
   }

   /***************************************************************************
    ** Never expose client exceptions, endpoint details or credentials to browsers.
    ***************************************************************************/
   private static QServiceUnavailableException unavailable()
   {
      return (new QServiceUnavailableException("Presence is temporarily unavailable. Please retry."));
   }

   /***************************************************************************
    ** Best-effort hints carry identifiers only; authorized snapshots remain truth.
    ***************************************************************************/
   private void changed(String table, String pk)
   {
      try
      {
         var bus = com.kingsrook.qqq.backend.core.realtime.RealtimeBus.get(QContext.getQInstance());
         bus.publish(new com.kingsrook.qqq.backend.core.realtime.RealtimeMessage()
            .withChannel("table:" + table).withEvent(RedisPresenceAudience.EVENT).withAudience(audience)
            .withData(java.util.Map.of("table", table)));
         if(pk != null)
         {
            bus.publish(new com.kingsrook.qqq.backend.core.realtime.RealtimeMessage()
               .withChannel("record:" + table + ":" + pk).withEvent(RedisPresenceAudience.EVENT).withAudience(audience)
               .withData(java.util.Map.of("table", table, "pk", pk)));
         }
      }
      catch(RuntimeException failure)
      {
         com.kingsrook.qqq.backend.core.logging.QLogger.getLogger(RedisPresenceBackend.class)
            .warn("Presence hint delivery failed; clients must reconcile through authorized reads");
      }
   }
}
