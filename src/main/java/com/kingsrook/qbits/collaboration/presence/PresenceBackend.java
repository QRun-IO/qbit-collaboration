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
import com.kingsrook.qqq.backend.core.exceptions.QException;

/*******************************************************************************
 ** Ephemeral storage boundary behind the permission-checking presence service.
 *******************************************************************************/
public interface PresenceBackend
{
   /***************************************************************************
    ** Reserve invisible renewal authority; null means capacity refusal.
    ***************************************************************************/
   String openLease(String user, String name, String table, String pk) throws QException;

   /***************************************************************************
    ** Renew existing authority or throw PresenceLeaseExpiredException.
    ***************************************************************************/
   boolean renewLease(String user, String name, String connection, String table, String pk, String mode, String field, Long sequence) throws QException;

   /***************************************************************************
    ** Renew a connection's ordered lease.
    ***************************************************************************/
   boolean heartbeat(String user, String name, String connection, String table, String pk, String mode, String field, Long sequence) throws QException;

   /***************************************************************************
    ** Release only the authenticated user's connection.
    ***************************************************************************/
   void leave(String user, String connection, String table, String pk, Long sequence) throws QException;

   /***************************************************************************
    ** Read a snapshot for subsequent recipient-specific permission filtering.
    ***************************************************************************/
   List<PresenceRegistry.Viewer> snapshot(String table, String pk) throws QException;
}
