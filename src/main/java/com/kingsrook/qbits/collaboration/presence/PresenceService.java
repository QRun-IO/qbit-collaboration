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

import java.util.List;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.realtime.PresenceFieldVisibility;

/*******************************************************************************
 ** Authenticated, permission-checked boundary for presence; nothing is persisted.
 *******************************************************************************/
public class PresenceService
{
   /*******************************************************************************
    ** Reserve invisible authority only for an authenticated, visible target.
    *******************************************************************************/
   public String openLease(String table, String pk) throws QException
   {
      String user = PersonalRecords.currentUserId();
      String canonical = authorize(table, pk);
      String name = QContext.getQSession().getUser().getFullName();
      String lease = registry().openLease(user, name == null || name.isBlank() ? user : name, table, canonical);
      if(lease == null)
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QServiceUnavailableException("Presence capacity reached; retry later"));
      }
      return (lease);
   }

   /*******************************************************************************
    ** Issued renewals share the same identity, parent and field access checks.
    *******************************************************************************/
   public List<PresenceRegistry.Viewer> renewLease(String connection, String table, String pk, String mode, String field, Long sequence) throws QException
   {
      requireIssuedLease(connection, sequence);
      return (renew(connection, table, pk, mode, field, sequence, true));
   }

   /*******************************************************************************
    ** Recheck visibility on every renewal and remove a refused caller's old lease.
    *******************************************************************************/
   public List<PresenceRegistry.Viewer> heartbeat(String connection, String table, String pk, String mode, String field) throws QException
   {
      return (heartbeat(connection, table, pk, mode, field, null));
   }

   /*******************************************************************************
    ** Ordered clients supply a monotonically increasing sequence per connection.
    *******************************************************************************/
   public List<PresenceRegistry.Viewer> heartbeat(String connection, String table, String pk, String mode, String field, Long sequence) throws QException
   {
      return (renew(connection, table, pk, mode, field, sequence, false));
   }

   /*******************************************************************************
    ** Keep legacy and issued requests on one permission and projection boundary.
    *******************************************************************************/
   private List<PresenceRegistry.Viewer> renew(String connection, String table, String pk, String mode, String field, Long sequence, boolean issued) throws QException
   {
      String user = PersonalRecords.currentUserId();
      validateConnection(connection);
      validateSequence(sequence);
      pk = canonicalKey(table, pk);
      try
      {
         pk = authorize(table, pk);
      }
      catch(QException e)
      {
         try
         {
            registry().leave(user, connection, table, pk, sequence);
         }
         catch(QException cleanupFailure)
         {
            // Preserve the access refusal; an unreachable store expires the old lease.
         }
         throw (e);
      }
      if(!List.of("VIEWING", "EDITING").contains(mode == null ? "" : mode))
      {
         throw (new QBadRequestException("Mode must be VIEWING or EDITING"));
      }
      if(field != null && (pk == null || !PresenceFieldVisibility.visibleFieldNames(table).contains(field)))
      {
         throw (new QBadRequestException("Unknown focused field"));
      }
      if(!issued && RedisPresenceConfig.forInstance(QContext.getQInstance()) != null)
      {
         throw (new QBadRequestException("Shared presence requires a server-issued lease; use the presence/leases endpoints"));
      }
      String name = QContext.getQSession().getUser().getFullName();
      String displayName = name == null || name.isBlank() ? user : name;
      var backend = registry();
      boolean accepted = issued ? backend.renewLease(user, displayName, connection, table, pk, mode, field, sequence)
         : backend.heartbeat(user, displayName, connection, table, pk, mode, field, sequence);
      if(!accepted)
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QServiceUnavailableException("Presence capacity reached; retry later"));
      }
      return (visibleSnapshot(table, pk));
   }

   /*******************************************************************************
    ** Namespacing by user prevents one user from removing another user's connection.
    *******************************************************************************/
   public void leave(String connection, String table, String pk) throws QException
   {
      leave(connection, table, pk, null);
   }

   /*******************************************************************************
    ** Ordered departure cannot be undone by an earlier renewal arriving late.
    *******************************************************************************/
   public void leave(String connection, String table, String pk, Long sequence) throws QException
   {
      String user = PersonalRecords.currentUserId();
      validateConnection(connection);
      validateSequence(sequence);
      if(connection.startsWith("lease:"))
      {
         requireIssuedLease(connection, sequence);
      }
      // Removal is scoped to the caller; revoked readers must still release their lease.
      String canonical = canonicalKey(table, pk);
      registry().leave(user, connection, table, canonical, sequence);
   }

   /*******************************************************************************
    ** Polling has the same access boundary as heartbeat and SSE record subscriptions.
    *******************************************************************************/
   public List<PresenceRegistry.Viewer> snapshot(String table, String pk) throws QException
   {
      PersonalRecords.currentUserId();
      return (visibleSnapshot(table, authorize(table, pk)));
   }

   /*******************************************************************************
    ** Project a fresh recipient view without mutating shared registry entries.
    *******************************************************************************/
   private List<PresenceRegistry.Viewer> visibleSnapshot(String table, String pk) throws QException
   {
      var visible = PresenceFieldVisibility.visibleFieldNames(table);
      return (registry().snapshot(table, pk).stream().map(viewer -> new PresenceRegistry.Viewer(viewer.userId(), viewer.displayName(), viewer.mode(),
         viewer.fieldName() != null && visible.contains(viewer.fieldName()) ? viewer.fieldName() : null)).toList());
   }

   /*******************************************************************************
    ** A refusal always has the same not-found result, including disabled features.
    *******************************************************************************/
   private String authorize(String table, String pk) throws QException
   {
      if(!ParentRecordAccess.canReadTable(table) || !RecordFeaturesResolver.resolve(QContext.getQInstance(), QContext.getQInstance().getTable(table)).isEnabled(RecordFeature.PRESENCE))
      {
         throw (new QNotFoundException("Not found"));
      }
      if(pk != null)
      {
         var record = ParentRecordAccess.requireVisibleParent(table, pk);
         return (record.getValueString(QContext.getQInstance().getTable(table).getPrimaryKeyField()));
      }
      return (null);
   }

   /*******************************************************************************
    ** Canonicalize before the read check so a refused renewal removes an aliased key.
    *******************************************************************************/
   private String canonicalKey(String tableName, String pk) throws QNotFoundException
   {
      try
      {
         var table = QContext.getQInstance().getTable(tableName);
         if(table == null || table.getPrimaryKeyField() == null)
         {
            throw (new IllegalArgumentException("Unknown table"));
         }
         if(pk == null)
         {
            return (null);
         }
         return (com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsFieldType(table.getField(table.getPrimaryKeyField()).getType(), pk).toString());
      }
      catch(RuntimeException e)
      {
         throw (new QNotFoundException("Not found"));
      }
   }

   /*******************************************************************************
    ** Bound caller-controlled keys independently of the registry entry cap.
    *******************************************************************************/
   private void validateConnection(String connection) throws QBadRequestException
   {
      if(connection == null || connection.isBlank() || connection.length() > 255)
      {
         throw (new QBadRequestException("A connectionId of at most 255 characters is required"));
      }
   }

   /*******************************************************************************
    ** Issued handles require ordered activation and departure requests.
    *******************************************************************************/
   private void requireIssuedLease(String connection, Long sequence) throws QBadRequestException
   {
      validateConnection(connection);
      validateSequence(sequence);
      if(!connection.startsWith("lease:") || sequence == null || sequence < 1)
      {
         throw (new QBadRequestException("A server-issued lease and positive sequence are required"));
      }
   }

   /*******************************************************************************
    ** Restrict ordered leases to exact nonnegative JavaScript integers.
    *******************************************************************************/
   private void validateSequence(Long sequence) throws QBadRequestException
   {
      if(sequence != null && (sequence < 0 || sequence > 9007199254740991L))
      {
         throw (new QBadRequestException("Presence sequence must be a nonnegative safe integer"));
      }
   }

   /*******************************************************************************
    ** Select the configured backend without creating an unused local registry.
    *******************************************************************************/
   private PresenceBackend registry() throws QException
   {
      var config = RedisPresenceConfig.forInstance(QContext.getQInstance());
      if(config != null)
      {
         return (new RedisPresenceBackend(config));
      }
      return (PresenceRegistry.get(QContext.getQInstance()));
   }
}
