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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBusInterface;
import com.kingsrook.qqq.backend.core.realtime.RealtimeMessage;

/*******************************************************************************
 ** Ephemeral per-pod presence replicated over a channel core refuses to clients.
 ** Publish outside the monitor: the in-memory bus waits for listener threads.
 *******************************************************************************/
public final class PresenceRegistry implements AutoCloseable
{
   public static final String INTERNAL_CHANNEL = "instance:presence";
   public static final long TTL_MILLIS = 45000;
   public static final int MAX_ENTRIES = 50000;
   private static final QLogger LOG = QLogger.getLogger(PresenceRegistry.class);
   private static final byte[] SESSION_SECRET = sessionSecret();
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
    ** Each tab has its own lease; user views merge the live leases. The lease
    ** keeps an opaque id of the server-side session (never the session uuid,
    ** which is a bearer credential) and the time it started (renewals keep it).
    *******************************************************************************/
   private record Entry(String displayName, String mode, String fieldName, long at, String sessionId, long since)
   {
   }

   /*******************************************************************************
    ** Transport-independent snapshot entry, never a persisted directory row.
    ** Merged per user, connections counts the live leases behind it; per
    ** connection (connections = 1), connectionId is an opaque per-lease id,
    ** sessionId an opaque id of the signed-in session (HMAC, never reversible to
    ** the session uuid) and since the lease start (ISO-8601). None of the three
    ** is set on merged viewers.
    *******************************************************************************/
   public record Viewer(String userId, String displayName, String mode, String fieldName, Integer connections, @JsonInclude(JsonInclude.Include.NON_NULL) String connectionId,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String sessionId, @JsonInclude(JsonInclude.Include.NON_NULL) String since) implements Serializable
   {
      /*******************************************************************************
       ** A merged viewer behind a number of live leases.
       *******************************************************************************/
      public Viewer(String userId, String displayName, String mode, String fieldName, Integer connections)
      {
         this(userId, displayName, mode, fieldName, connections, null, null, null);
      }
   }

   /*******************************************************************************
    ** Refresh locally even if transport delivery fails; replicate every heartbeat.
    *******************************************************************************/
   public boolean heartbeat(String user, String displayName, String connection, String table, String pk, String mode, String fieldName)
   {
      return (heartbeat(user, displayName, connection, null, table, pk, mode, fieldName));
   }

   /*******************************************************************************
    ** Renew a lease tied to the caller's server-side session. Only an opaque HMAC
    ** of the session uuid is kept and replicated; the uuid itself is dropped here.
    *******************************************************************************/
   public boolean heartbeat(String user, String displayName, String connection, String sessionUuid, String table, String pk, String mode, String fieldName)
   {
      expire();
      var key = new Key(user, connection, table, pk);
      long now = clock.millis();
      var entry = new Entry(displayName, mode, fieldName, now, opaqueSessionId(sessionUuid), now);
      if(!apply(key, entry, false))
      {
         return (false);
      }
      publish(key, entry, false);
      return (true);
   }

   /*******************************************************************************
    ** Leaves use the same internal channel so every pod removes the lease.
    *******************************************************************************/
   public void leave(String user, String connection, String table, String pk)
   {
      var key = new Key(user, connection, table, pk);
      long now = clock.millis();
      var entry = new Entry(null, null, null, now, null, now);
      apply(key, entry, true);
      publish(key, entry, true);
   }

   /*******************************************************************************
    ** Internal payloads contain only identity and focus, never record values.
    *******************************************************************************/
   private void publish(Key key, Entry entry, boolean leave)
   {
      Map<String, Serializable> data = new LinkedHashMap<>();
      data.put("userId", key.user());
      data.put("connectionId", key.connection());
      data.put("table", key.table());
      data.put("pk", key.pk());
      data.put("displayName", entry.displayName());
      data.put("mode", entry.mode());
      data.put("fieldName", entry.fieldName());
      data.put("at", entry.at());
      data.put("sessionId", entry.sessionId());
      data.put("leave", leave);
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
      if(receivedAt + TTL_MILLIS <= clock.millis())
      {
         return;
      }
      apply(new Key(user, connection, table, (String) data.get("pk")),
         new Entry((String) data.get("displayName"), (String) data.get("mode"), (String) data.get("fieldName"), receivedAt, (String) data.get("sessionId"), receivedAt), Boolean.TRUE.equals(data.get("leave")));
   }

   /*******************************************************************************
    ** Unchanged renewals extend TTL without generating client event traffic.
    *******************************************************************************/
   private boolean apply(Key key, Entry entry, boolean leave)
   {
      boolean changed;
      synchronized(this)
      {
         Entry old = entries.get(key);
         if(old != null && old.at() > entry.at())
         {
            return (true);
         }
         if(!leave && old == null && entries.size() >= capacity)
         {
            LOG.warn("Presence capacity reached; refusing a new target");
            return (false);
         }
         List<Viewer> before = projection(key.table(), key.pk());
         if(leave)
         {
            entries.remove(key);
         }
         else
         {
            /////////////////////////////////////////////////////////////////
            // a renewal keeps the lease start (pods may differ by a little: //
            // the earliest start wins) and the first known session id       //
            /////////////////////////////////////////////////////////////////
            if(old != null)
            {
               entry = new Entry(entry.displayName(), entry.mode(), entry.fieldName(), entry.at(),
                  old.sessionId() != null ? old.sessionId() : entry.sessionId(), Math.min(old.since(), entry.since()));
            }
            entries.put(key, entry);
         }
         changed = !before.equals(projection(key.table(), key.pk()));
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
      Map<String, Entry> users = new TreeMap<>();
      Map<String, Integer> connections = new TreeMap<>();
      for(var row : live(table, pk))
      {
         users.merge(row.getKey().user(), row.getValue(), (old, next) ->
            (!Objects.equals(old.mode(), next.mode()) ? "EDITING".equals(next.mode()) : next.at() >= old.at()) ? next : old);
         connections.merge(row.getKey().user(), 1, Integer::sum);
      }
      return (users.entrySet().stream().map(row -> new Viewer(row.getKey(), row.getValue().displayName(), row.getValue().mode(),
         pk == null ? null : row.getValue().fieldName(), connections.get(row.getKey()))).toList());
   }

   /*******************************************************************************
    ** One viewer per live lease, ordered by user, lease start and opaque id. A
    ** table target counts each connection once per record it holds a lease on.
    *******************************************************************************/
   private List<Viewer> connectionsInternal(String table, String pk)
   {
      return (live(table, pk).stream()
         .map(row -> new Viewer(row.getKey().user(), row.getValue().displayName(), row.getValue().mode(), pk == null ? null : row.getValue().fieldName(), 1,
            opaqueConnectionId(row.getKey().user(), row.getKey().connection()), row.getValue().sessionId(), Instant.ofEpochMilli(row.getValue().since()).toString()))
         .sorted(java.util.Comparator.comparing(Viewer::userId).thenComparing(Viewer::since).thenComparing(Viewer::connectionId))
         .toList());
   }

   /*******************************************************************************
    ** Live leases on a table (any record when pk is null) or on one record.
    *******************************************************************************/
   private List<Map.Entry<Key, Entry>> live(String table, String pk)
   {
      List<Map.Entry<Key, Entry>> result = new ArrayList<>();
      for(var row : entries.entrySet())
      {
         if(row.getKey().table().equals(table) && (pk == null || Objects.equals(row.getKey().pk(), pk)) && row.getValue().at() + TTL_MILLIS > clock.millis())
         {
            result.add(row);
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** What clients can observe: merged viewers and per-connection viewers. Lease
    ** renewals that change neither generate no event.
    *******************************************************************************/
   private List<Viewer> projection(String table, String pk)
   {
      List<Viewer> result = new ArrayList<>(snapshotInternal(table, pk));
      result.addAll(connectionsInternal(table, pk));
      return (result);
   }

   /*******************************************************************************
    ** Opaque, stable per-lease id: "c" and the first 16 hex digits of
    ** SHA-256(userId + "\n" + connectionId). A client can recognise its own
    ** connection; nobody learns another client's connection id.
    *******************************************************************************/
   public static String opaqueConnectionId(String userId, String connectionId)
   {
      try
      {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest((userId + "\n" + connectionId).getBytes(StandardCharsets.UTF_8));
         StringBuilder result = new StringBuilder("c");
         for(int i = 0; i < 8; i++)
         {
            result.append(String.format("%02x", digest[i]));
         }
         return (result.toString());
      }
      catch(NoSuchAlgorithmException e)
      {
         throw (new IllegalStateException("SHA-256 is unavailable", e));
      }
   }

   /*******************************************************************************
    ** Opaque session id: "s" and the first 16 hex digits of
    ** HMAC-SHA256(secret, sessionUuid), or null without a session. The secret is
    ** QQQ_PRESENCE_SESSION_SECRET (system property or environment; share it across
    ** pods for ids that agree between pods) or else random per process. Session
    ** uuids are bearer credentials and must never be stored or published.
    *******************************************************************************/
   public static String opaqueSessionId(String sessionUuid)
   {
      if(sessionUuid == null || sessionUuid.isBlank())
      {
         return (null);
      }
      try
      {
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(SESSION_SECRET, "HmacSHA256"));
         byte[] digest = mac.doFinal(sessionUuid.getBytes(StandardCharsets.UTF_8));
         StringBuilder result = new StringBuilder("s");
         for(int i = 0; i < 8; i++)
         {
            result.append(String.format("%02x", digest[i]));
         }
         return (result.toString());
      }
      catch(NoSuchAlgorithmException | InvalidKeyException e)
      {
         throw (new IllegalStateException("HmacSHA256 is unavailable", e));
      }
   }

   /*******************************************************************************
    ** Configured shared secret, or 32 random bytes for this process.
    *******************************************************************************/
   private static byte[] sessionSecret()
   {
      String configured = System.getProperty("QQQ_PRESENCE_SESSION_SECRET", System.getenv("QQQ_PRESENCE_SESSION_SECRET"));
      if(configured != null && !configured.isBlank())
      {
         return (configured.getBytes(StandardCharsets.UTF_8));
      }
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      return (random);
   }

   /*******************************************************************************
    ** One viewer per live lease (opt-in per-connection presence, #54).
    *******************************************************************************/
   public List<Viewer> connections(String table, String pk)
   {
      expire();
      synchronized(this)
      {
         return (connectionsInternal(table, pk));
      }
   }

   /*******************************************************************************
    ** Only counts are exposed to the admin home, with no row contents or record ids.
    *******************************************************************************/
   public Map<String, Integer> counts()
   {
      expire();
      synchronized(this)
      {
         Map<String, Integer> result = new TreeMap<>();
         entries.keySet().stream().map(Key::table).distinct().forEach(table -> result.put(table, snapshotInternal(table, null).size()));
         return (result);
      }
   }

   /*******************************************************************************
    ** Live lease (connection) counts per table, next to the user counts above.
    *******************************************************************************/
   public Map<String, Integer> connectionCounts()
   {
      expire();
      synchronized(this)
      {
         Map<String, Integer> result = new TreeMap<>();
         entries.keySet().stream().map(Key::table).distinct().forEach(table -> result.put(table, live(table, null).size()));
         result.values().removeIf(count -> count == 0);
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
      Viewers viewers = new Viewers();
      Viewers leases = new Viewers();
      synchronized(this)
      {
         snapshotInternal(table, pk).forEach(viewer -> viewers.add(toMap(viewer)));
         connectionsInternal(table, pk).forEach(viewer -> leases.add(toMap(viewer)));
      }
      data.put("viewers", viewers);
      data.put("leaseCount", leases.size());
      data.put("leases", leases);
      bus.publish(new RealtimeMessage().withChannel(channel).withEvent("presence.changed").withData(data));
   }

   /*******************************************************************************
    ** Event shape of a viewer; per-connection fields only when set.
    *******************************************************************************/
   private static LinkedHashMap<String, Serializable> toMap(Viewer viewer)
   {
      LinkedHashMap<String, Serializable> value = new LinkedHashMap<>();
      value.put("userId", viewer.userId());
      value.put("displayName", viewer.displayName());
      value.put("mode", viewer.mode());
      value.put("fieldName", viewer.fieldName());
      value.put("connections", viewer.connections());
      if(viewer.connectionId() != null)
      {
         value.put("connectionId", viewer.connectionId());
         if(viewer.sessionId() != null)
         {
            value.put("sessionId", viewer.sessionId());
         }
         value.put("since", viewer.since());
      }
      return (value);
   }

   /*******************************************************************************
    ** Event viewers list that is never omitted: the SSE writer serializes event data
    ** with NON_EMPTY inclusion, which would drop an empty list, but the schema
    ** declares viewers a required array, so the last leave must send [] (#52).
    *******************************************************************************/
   @JsonSerialize(using = ViewersSerializer.class)
   public static final class Viewers extends ArrayList<LinkedHashMap<String, Serializable>>
   {
   }



   /*******************************************************************************
    ** Writes each viewer with the caller's mapper settings and reports never empty.
    *******************************************************************************/
   public static final class ViewersSerializer extends StdSerializer<Viewers>
   {
      /*******************************************************************************
       ** Jackson instantiates the serializer reflectively.
       *******************************************************************************/
      public ViewersSerializer()
      {
         super(Viewers.class);
      }



      /*******************************************************************************
       ** Always an array, including the empty array after the last viewer leaves.
       *******************************************************************************/
      @Override
      public void serialize(Viewers viewers, JsonGenerator generator, SerializerProvider provider) throws IOException
      {
         generator.writeStartArray(viewers, viewers.size());
         for(LinkedHashMap<String, Serializable> viewer : viewers)
         {
            provider.defaultSerializeValue(viewer, generator);
         }
         generator.writeEndArray();
      }



      /*******************************************************************************
       ** NON_EMPTY inclusion consults this; an empty viewer list is still a value.
       *******************************************************************************/
      @Override
      public boolean isEmpty(SerializerProvider provider, Viewers viewers)
      {
         return (false);
      }
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
