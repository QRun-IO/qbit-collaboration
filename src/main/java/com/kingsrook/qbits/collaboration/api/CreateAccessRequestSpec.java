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
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.Parameter;
import com.kingsrook.qqq.openapi.model.Schema;

/*******************************************************************************
 ** CreateAccessRequest endpoint.
 *******************************************************************************/
public class CreateAccessRequestSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** Describe this operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.POST, "/access-requests", "Request access to a record"));
   }

   /*******************************************************************************
    ** Declare inputs.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (parameters());
   }

   /*******************************************************************************
    ** Name the response.
    *******************************************************************************/
   @Override
   protected String getResponseSchemaName()
   {
      return ("CollaborationAccessRequestV1");
   }

   /*******************************************************************************
    ** Name the request.
    *******************************************************************************/
   @Override
   protected Schema defineRequestBodySchema()
   {
      return (new Schema().withRefToSchema("CollaborationAccessRequestCreateRequestV1"));
   }

   /*******************************************************************************
    ** Run the service as the authenticated caller.
    *******************************************************************************/
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      var body = body(input, CollaborationWorkSchemas.CollaborationAccessRequestCreateRequestV1.class);
      if(body.shareId() != null && body.shareId() < 1)
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QBadRequestException("shareId must be positive"));
      }
      return (CollaborationViews.accessRequest(new com.kingsrook.qbits.collaboration.sharing.AccessRequestService()
         .request(required(body.tableName(), "tableName"), required(body.recordId(), "recordId"), body.message(), body.shareId())));
   }

}
