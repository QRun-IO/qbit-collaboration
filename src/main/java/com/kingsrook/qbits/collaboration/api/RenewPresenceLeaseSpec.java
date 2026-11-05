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


package com.kingsrook.qbits.collaboration.api;

import java.util.List;
import com.kingsrook.qbits.collaboration.presence.PresenceService;
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.Parameter;
import com.kingsrook.qqq.openapi.model.Schema;

/*******************************************************************************
 ** Renew presence with fresh parent access checks.
 *******************************************************************************/
public class RenewPresenceLeaseSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** Expiry is distinct from permissions, invalid input and service outages.
    *******************************************************************************/
   @Override
   public List<com.kingsrook.qqq.middleware.javalin.specs.BasicResponse> defineAdditionalBasicResponses()
   {
      var responses = new java.util.ArrayList<>(super.defineAdditionalBasicResponses());
      responses.add(new com.kingsrook.qqq.middleware.javalin.specs.BasicResponse(io.javalin.http.HttpStatus.CONFLICT,
         "Presence lease expired; open a new lease.", "CollaborationPresenceExpiredV1"));
      return (responses);
   }

   /*******************************************************************************
    ** Preserve standard framework mapping for every other exception.
    *******************************************************************************/
   @Override
   protected void handleException(io.javalin.http.Context context, Exception exception)
   {
      if(com.kingsrook.qqq.backend.core.utils.ExceptionUtils.findClassInRootChain(exception,
         com.kingsrook.qbits.collaboration.presence.PresenceLeaseExpiredException.class) != null)
      {
         context.status(409).contentType("application/json").result(com.kingsrook.qqq.backend.core.utils.JsonUtils.toJson(
            new CollaborationPresenceSchemas.CollaborationPresenceExpiredV1("PRESENCE_LEASE_EXPIRED")));
      }
      else
      {
         super.handleException(context, exception);
      }
   }

   /*******************************************************************************
    ** Describe the extension operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.POST, "/presence/leases/renew", "Renew issued presence lease"));
   }

   /*******************************************************************************
    ** Explicit query parameters for polling.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (parameters());
   }

   /*******************************************************************************
    ** Name the versioned response schema.
    *******************************************************************************/
   @Override
   protected String getResponseSchemaName()
   {
      return ("CollaborationPresenceSnapshotV1");
   }

   /*******************************************************************************
    ** Declare a typed JSON request body.
    *******************************************************************************/
   @Override
   protected Schema defineRequestBodySchema()
   {
      return (new Schema().withRefToSchema("CollaborationPresenceRequestV1"));
   }

   /*******************************************************************************
    ** Services derive identity from QContext and enforce visibility.
    *******************************************************************************/
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      var body = body(input, CollaborationPresenceSchemas.CollaborationPresenceRequestV1.class);
      return (new CollaborationPresenceSchemas.CollaborationPresenceSnapshotV1(body.table(), body.pk(),
         new PresenceService().renewLease(body.connectionId(), required(body.table(), "table"), body.pk(), body.mode(), body.fieldName(), body.sequence())));
   }
}
