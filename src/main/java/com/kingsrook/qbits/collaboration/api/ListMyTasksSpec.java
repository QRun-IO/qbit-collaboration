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
 ** ListMyTasks endpoint.
 *******************************************************************************/
public class ListMyTasksSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** Describe this operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.GET, "/tasks/mine", "List my tasks"));
   }

   /*******************************************************************************
    ** Declare inputs.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (parameters("status", "limit", "cursor"));
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
      var page = new com.kingsrook.qbits.collaboration.tasks.TasksService().mine(input.getQueryParam("status"), limit(input), input.getQueryParam("cursor"));
      return (new CollaborationWorkSchemas.CollaborationTaskListV1(page.tasks().stream().map(CollaborationViews::task).toList(), page.nextCursor()));
   }

}
