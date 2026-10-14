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

/*******************************************************************************
 ** Authenticated, permission-checked boundary for presence; nothing is persisted.
 *******************************************************************************/
public class PresenceService
{
   /*******************************************************************************
    ** Recheck visibility on every renewal and remove a refused caller's old lease.
    *******************************************************************************/
   public List<PresenceRegistry.Viewer> heartbeat(String connection, String table, String pk, String mode, String field) throws QException
   {
      String user = PersonalRecords.currentUserId();
      validateConnection(connection);
      pk = canonicalKey(table, pk);
      try
      {
         pk = authorize(table, pk);
      }
      catch(QException e)
      {
         registry().leave(user, connection, table, pk);
         throw (e);
      }
      if(!List.of("VIEWING", "EDITING").contains(mode == null ? "" : mode))
      {
         throw (new QBadRequestException("Mode must be VIEWING or EDITING"));
      }
      if(field != null && (pk == null || QContext.getQInstance().getTable(table).getFields().get(field) == null))
      {
         throw (new QBadRequestException("Unknown focused field"));
      }
      String name = QContext.getQSession().getUser().getFullName();
      if(!registry().heartbeat(user, name == null || name.isBlank() ? user : name, connection, table, pk, mode, field))
      {
         throw (new QBadRequestException("Presence capacity reached; retry later"));
      }
      return (registry().snapshot(table, pk));
   }

   /*******************************************************************************
    ** Namespacing by user prevents one user from removing another user's connection.
    *******************************************************************************/
   public void leave(String connection, String table, String pk) throws QException
   {
      String user = PersonalRecords.currentUserId();
      validateConnection(connection);
      String canonical = authorize(table, pk);
      registry().leave(user, connection, table, canonical);
   }

   /*******************************************************************************
    ** Polling has the same access boundary as heartbeat and SSE record subscriptions.
    *******************************************************************************/
   public List<PresenceRegistry.Viewer> snapshot(String table, String pk) throws QException
   {
      PersonalRecords.currentUserId();
      return (registry().snapshot(table, authorize(table, pk)));
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
      if(pk == null)
      {
         return (null);
      }
      try
      {
         var table = QContext.getQInstance().getTable(tableName);
         if(table == null || table.getPrimaryKeyField() == null)
         {
            throw (new IllegalArgumentException("Unknown table"));
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
    ** All endpoints on a pod share the eagerly installed instance registry.
    *******************************************************************************/
   private PresenceRegistry registry()
   {
      return (PresenceRegistry.get(QContext.getQInstance()));
   }
}
