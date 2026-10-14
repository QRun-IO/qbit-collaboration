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
 ** PatchTask endpoint.
 *******************************************************************************/
public class PatchTaskSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** Check the stored parent before parsing any submitted body.
    *******************************************************************************/
   @Override
   public JsonInput buildInput(io.javalin.http.Context context) throws Exception
   {
      try
      {
         Integer key = Integer.valueOf(context.pathParam("id"));
         if(key < 1)
         {
            throw (new NumberFormatException());
         }
         new com.kingsrook.qbits.collaboration.tasks.TasksService().requireManager(key);
      }
      catch(NumberFormatException e)
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QBadRequestException("An identifier must be a positive integer"));
      }
      return (super.buildInput(context));
   }

   /*******************************************************************************
    ** Describe this operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.PATCH, "/tasks/{id}", "Update a task"));
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
      return ("CollaborationTaskV1");
   }

   /*******************************************************************************
    ** Name the request.
    *******************************************************************************/
   @Override
   protected Schema defineRequestBodySchema()
   {
      return (new Schema().withRefToSchema("CollaborationTaskPatchRequestV1"));
   }

   /*******************************************************************************
    ** Run the service as the authenticated caller.
    *******************************************************************************/
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      var service = new com.kingsrook.qbits.collaboration.tasks.TasksService();
      var old = service.requireManager(id(input));
      var body = body(input, CollaborationWorkSchemas.CollaborationTaskPatchRequestV1.class);
      String status = body.status();
      if(input.getBody().containsKey("status") && (status == null || !List.of("OPEN", "DONE", "CANCELLED").contains(status)))
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QBadRequestException("Unknown task status"));
      }
      var row = old;
      if(input.getBody().keySet().stream().anyMatch(key -> !key.equals("status")))
      {
         row = service.update(id(input), input.getBody().containsKey("title") ? required(body.title(), "title") : old.getValueString("title"),
            input.getBody().containsKey("notes") ? body.notes() : old.getValueString("notes"),
            input.getBody().containsKey("assigneeUserId") ? body.assigneeUserId() : old.getValueString("assigneeUserId"),
            input.getBody().containsKey("dueAt") ? instant(body.dueAt()) : old.getValueInstant("dueAt"),
            input.getBody().containsKey("remindAt") ? instant(body.remindAt()) : old.getValueInstant("remindAt"));
      }
      if(status != null)
      {
         row = switch(status)
         {
            case "DONE" -> service.complete(id(input));
            case "CANCELLED" -> service.cancel(id(input));
            default -> service.reopen(id(input));
         };
      }
      return (CollaborationViews.task(row));
   }

}
