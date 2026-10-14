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

/*******************************************************************************
 ** ListTasks endpoint.
 *******************************************************************************/
public class ListTasksSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** Describe this operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.GET, "/records/{table}/{pk}/tasks", "List record tasks"));
   }

   /*******************************************************************************
    ** Declare inputs.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (parameters("includeDone", "limit", "cursor"));
   }

   /*******************************************************************************
    ** Name the response.
    *******************************************************************************/
   @Override
   protected String getResponseSchemaName()
   {
      return ("CollaborationTaskListV1");
   }

   /*******************************************************************************
    ** Run the service as the authenticated caller.
    *******************************************************************************/
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      String includeDone = input.getQueryParam("includeDone");
      if(includeDone != null && !List.of("true", "false").contains(includeDone))
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QBadRequestException("includeDone must be true or false"));
      }
      var page = page(input, new com.kingsrook.qbits.collaboration.tasks.TasksService().list(table(input), pk(input), Boolean.valueOf(includeDone)));
      return (new CollaborationWorkSchemas.CollaborationTaskListV1(page.items().stream().map(CollaborationViews::task).toList(), page.nextCursor()));
   }

}
