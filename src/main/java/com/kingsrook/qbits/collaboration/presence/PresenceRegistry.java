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

import java.io.Serializable;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBusInterface;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;

/*******************************************************************************
 ** Ephemeral per-pod presence replicated over a channel core refuses to clients.
 ** Publish outside the monitor: the in-memory bus waits for listener threads.
 *******************************************************************************/
public final class PresenceRegistry implements PresenceBackend, AutoCloseable
{
   public static final String INTERNAL_CHANNEL = "instance:presence";
   public static final long TTL_MILLIS = 45000;
   public static final int MAX_ENTRIES = 50000;
   private static final QLogger LOG = QLogger.getLogger(PresenceRegistry.class);
   private static final Map<QInstance, PresenceRegistry> REGISTRIES = new WeakHashMap<>();
   private final Map<Key, Entry> entries = new HashMap<>();
   private final RealtimeBusInterface bus;
   private final Clock clock;
   private final int capacity;
   private final AutoCloseable subscription;
   private ScheduledExecutorService expiry;

   /*******************************************************************************
    ** Clock and cap injection makes expiry and overload deterministic in tests.
    *******************************************************************************/
   public PresenceRegistry(RealtimeBusInterface bus, Clock clock, int capacity)
   {
      this.bus = bus;
      this.clock = clock;
      this.capacity = capacity;
      subscription = bus.subscribe(this::receive);
   }

   /*******************************************************************************
    ** Install on every pod, even before its first local heartbeat.
    *******************************************************************************/
   public static synchronized PresenceRegistry get(QInstance instance)
   {
      return (REGISTRIES.computeIfAbsent(instance, key ->
      {
         var registry = new PresenceRegistry(RealtimeBus.get(instance), Clock.systemUTC(), MAX_ENTRIES);
         registry.expiry = Executors.newSingleThreadScheduledExecutor(runnable ->
         {
            Thread thread = new Thread(runnable, "collaboration-presence-expiry");
            thread.setDaemon(true);
            return (thread);
         });
         registry.expiry.scheduleWithFixedDelay(() ->
         {
            try
            {
               registry.expire();
            }
            catch(RuntimeException e)
            {
               LOG.warn("Could not expire presence", e);
            }
         }, 1, 1, TimeUnit.SECONDS);
         return (registry);
      }));
   }

   /*******************************************************************************
    ** Hosts stop the timer and unsubscribe before closing the instance realtime bus.
    *******************************************************************************/
   public static synchronized void close(QInstance instance)
   {
      var registry = REGISTRIES.remove(instance);
      if(registry != null)
      {
         registry.close();
      }
   }

   /*******************************************************************************
    ** Stable identity namespaces client connection ids by the authenticated user.
    *******************************************************************************/
   private record Key(String user, String connection, String table, String pk)
   {
   }

   /*******************************************************************************
    ** Each tab has its own lease; user views merge the live leases.
    *******************************************************************************/
   private record Entry(String displayName, String mode, String fieldName, long at, Long sequence)
   {
   }

   /*******************************************************************************
    ** Transport-independent snapshot entry, never a persisted directory row.
    *******************************************************************************/
   public record Viewer(String userId, String displayName, String mode, String fieldName) implements Serializable
   {
   }

   /*******************************************************************************
    ** Refresh locally even if transport delivery fails; replicate every heartbeat.
    *******************************************************************************/
   public boolean heartbeat(String user, String displayName, String connection, String table, String pk, String mode, String fieldName)
   {
      return (heartbeat(user, displayName, connection, table, pk, mode, fieldName, null));
   }

   /*******************************************************************************
    ** Client sequence orders delayed HTTP requests independently of arrival time.
    *******************************************************************************/
   public boolean heartbeat(String user, String displayName, String connection, String table, String pk, String mode, String fieldName, Long sequence)
   {
      if(connection != null && connection.startsWith("lease:"))
      {
         throw (new PresenceLeaseExpiredException());
      }
      expire();
      var key = new Key(user, connection, table, pk);
      var entry = new Entry(displayName, mode, fieldName, clock.millis(), sequence);
      if(!apply(key, entry, false))
      {
         return (false);
      }
      publish(key, entry, false);
      return (true);
   }

   /***************************************************************************
    ** Local mode issues local authority; HA applications must use shared Redis.
    ***************************************************************************/
   @Override
   public String openLease(String user, String displayName, String table, String pk)
   {
      expire();
      String connection = "lease:" + java.util.UUID.randomUUID();
      return (apply(new Key(user, connection, table, pk), new Entry(displayName, "PENDING", null, clock.millis(), 0L), false) ? connection : null);
   }

   /***************************************************************************
    ** Check renewal and update under one monitor; no late request creates a lease.
    ***************************************************************************/
   @Override
   public boolean renewLease(String user, String displayName, String connection, String table, String pk, String mode, String fieldName, Long sequence)
   {
      if(connection == null || !connection.startsWith("lease:") || sequence == null || sequence < 1 || sequence > 9007199254740991L
         || !List.of("VIEWING", "EDITING").contains(mode == null ? "" : mode))
      {
         throw (new IllegalArgumentException("A server-issued lease, positive safe sequence and valid mode are required"));
      }
      expire();
      return (apply(new Key(user, connection, table, pk), new Entry(displayName, mode, fieldName, clock.millis(), sequence), false, true));
   }

   /*******************************************************************************
    ** Leaves use the same internal channel so every pod removes the lease.
    *******************************************************************************/
   public void leave(String user, String connection, String table, String pk)
   {
      leave(user, connection, table, pk, null);
   }

   /*******************************************************************************
    ** Departure participates in the same ordered client lease.
    *******************************************************************************/
   public void leave(String user, String connection, String table, String pk, Long sequence)
   {
      var key = new Key(user, connection, table, pk);
      var entry = new Entry(null, null, null, clock.millis(), sequence);
      apply(key, entry, true);
      publish(key, entry, true);
   }

   /*******************************************************************************
    ** Internal payloads contain only identity and focus, never record values.
    *******************************************************************************/
   private void publish(Key key, Entry entry, boolean leave)
   {
      if(key.connection().startsWith("lease:"))
      {
         return;
      }
      Map<String, Serializable> data = new LinkedHashMap<>();
      data.put("userId", key.user());
      data.put("connectionId", key.connection());
      data.put("table", key.table());
      data.put("pk", key.pk());
      data.put("displayName", entry.displayName());
      data.put("mode", entry.mode());
      data.put("fieldName", entry.fieldName());
      data.put("at", entry.at());
      data.put("leave", leave);
      data.put("sequence", entry.sequence());
      bus.publish(new RealtimeMessage().withChannel(INTERNAL_CHANNEL).withEvent("presence.heartbeat").withData(data));
   }

   /*******************************************************************************
    ** Client-visible change events cannot enter the replication path.
    *******************************************************************************/
   private void receive(RealtimeMessage message)
   {
      if(!INTERNAL_CHANNEL.equals(message.getChannel()) || !"presence.heartbeat".equals(message.getEvent()))
      {
         return;
      }
      var data = message.getData();
      if(!(data.get("at") instanceof Number at) || !(data.get("userId") instanceof String user)
         || !(data.get("connectionId") instanceof String connection) || !(data.get("table") instanceof String table))
      {
         return;
      }
      expire();
      long receivedAt = Math.min(at.longValue(), clock.millis());
      // Best-effort replication cannot grant or restore issued renewal authority.
      if(connection.startsWith("lease:"))
      {
         return;
      }
      if(receivedAt + TTL_MILLIS <= clock.millis())
      {
         return;
      }
      apply(new Key(user, connection, table, (String) data.get("pk")),
         new Entry((String) data.get("displayName"), (String) data.get("mode"), (String) data.get("fieldName"), receivedAt,
            data.get("sequence") instanceof Number sequence ? sequence.longValue() : null), Boolean.TRUE.equals(data.get("leave")));
   }

   /*******************************************************************************
    ** Unchanged renewals extend TTL without generating client event traffic.
    *******************************************************************************/
   private boolean apply(Key key, Entry entry, boolean leave)
   {
      return (apply(key, entry, leave, false));
   }

   /*******************************************************************************
    ** Local issued authority is checked atomically with the mutation.
    *******************************************************************************/
   private boolean apply(Key key, Entry entry, boolean leave, boolean requireLive)
   {
      boolean changed;
      synchronized(this)
      {
         Entry old = entries.get(key);
         if(requireLive && (old == null || old.mode() == null || old.at() + TTL_MILLIS <= clock.millis()))
         {
            throw (new PresenceLeaseExpiredException());
         }
         if(old != null && old.sequence() != null
            && (entry.sequence() == null || entry.sequence() < old.sequence()
               || (entry.sequence().equals(old.sequence()) && (!leave || old.mode() == null))))
         {
            return (true);
         }
         if(old != null && old.sequence() == null && entry.sequence() == null
            && (old.at() > entry.at() || (old.at() == entry.at() && old.mode() == null && !leave)))
         {
            return (true);
         }
         if(old == null && entries.size() >= capacity)
         {
            LOG.warn("Presence capacity reached; refusing a new target");
            return (false);
         }
         List<Viewer> before = snapshotInternal(key.table(), key.pk());
         // Keep a bounded departure marker until old messages can no longer be fresh.
         entries.put(key, entry);
         changed = !before.equals(snapshotInternal(key.table(), key.pk()));
      }
      if(changed)
      {
         changed(key.table(), key.pk());
      }
      return (true);
   }

   /*******************************************************************************
    ** Expiry is active even without a polling client; expired data is never returned.
    *******************************************************************************/
   public void expire()
   {
      List<Key> removed = new ArrayList<>();
      synchronized(this)
      {
         entries.entrySet().removeIf(entry ->
         {
            if(entry.getValue().at() + TTL_MILLIS <= clock.millis())
            {
               removed.add(entry.getKey());
               return (true);
            }
            return (false);
         });
      }
      removed.stream().map(key -> new Key(null, null, key.table(), key.pk())).distinct().forEach(key -> changed(key.table(), key.pk()));
   }

   /*******************************************************************************
    ** Table snapshots omit record keys and focused fields to avoid lock disclosures.
    *******************************************************************************/
   public List<Viewer> snapshot(String table, String pk)
   {
      expire();
      synchronized(this)
      {
         return (snapshotInternal(table, pk));
      }
   }

   /*******************************************************************************
    ** Editing wins over viewing; within a mode the most recent tab supplies focus.
    *******************************************************************************/
   private List<Viewer> snapshotInternal(String table, String pk)
   {
      Map<String, Entry> users = new java.util.TreeMap<>();
      for(var row : entries.entrySet())
      {
         if(row.getValue().mode() != null && !"PENDING".equals(row.getValue().mode()) && row.getKey().table().equals(table) && (pk == null || Objects.equals(row.getKey().pk(), pk)) && row.getValue().at() + TTL_MILLIS > clock.millis())
         {
            users.merge(row.getKey().user(), row.getValue(), (old, next) ->
               (!Objects.equals(old.mode(), next.mode()) ? "EDITING".equals(next.mode()) : next.at() >= old.at()) ? next : old);
         }
      }
      return (users.entrySet().stream().map(row -> new Viewer(row.getKey(), row.getValue().displayName(), row.getValue().mode(), pk == null ? null : row.getValue().fieldName())).toList());
   }

   /*******************************************************************************
    ** Only counts are exposed to the admin home, with no row contents or record ids.
    *******************************************************************************/
   public Map<String, Integer> counts()
   {
      expire();
      synchronized(this)
      {
         Map<String, Integer> result = new java.util.TreeMap<>();
         entries.keySet().stream().map(Key::table).distinct().forEach(table -> result.put(table, snapshotInternal(table, null).size()));
         return (result);
      }
   }

   /*******************************************************************************
    ** Record focus stays on its record channel; table events contain table snapshots.
    *******************************************************************************/
   private void changed(String table, String pk)
   {
      if(pk != null)
      {
         emit("record:" + table + ":" + pk, table, pk);
      }
      emit("table:" + table, table, null);
   }

   /*******************************************************************************
    ** Maps keep the bus payload portable across in-memory and JSON ESB transports.
    *******************************************************************************/
   private void emit(String channel, String table, String pk)
   {
      Map<String, Serializable> data = new LinkedHashMap<>();
      data.put("table", table);
      if(pk != null)
      {
         data.put("pk", pk);
      }
      ArrayList<LinkedHashMap<String, Serializable>> viewers = new ArrayList<>();
      synchronized(this)
      {
         for(Viewer viewer : snapshotInternal(table, pk))
         {
            LinkedHashMap<String, Serializable> value = new LinkedHashMap<>();
            value.put("userId", viewer.userId());
            value.put("displayName", viewer.displayName());
            value.put("mode", viewer.mode());
            value.put("fieldName", viewer.fieldName());
            viewers.add(value);
         }
      }
      data.put("viewers", viewers);
      bus.publish(new RealtimeMessage().withChannel(channel).withEvent("presence.changed").withData(data));
   }

   /*******************************************************************************
    ** Release listeners and timer on host shutdown or in tests.
    *******************************************************************************/
   @Override
   public void close()
   {
      if(expiry != null)
      {
         expiry.shutdownNow();
      }
      try
      {
         subscription.close();
      }
      catch(Exception e)
      {
         LOG.warn("Could not unsubscribe presence", e);
      }
      synchronized(this)
      {
         entries.clear();
      }
   }
}
