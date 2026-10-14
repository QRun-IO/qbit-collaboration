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
import com.kingsrook.qbits.collaboration.comments.CommentsService;
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.Parameter;


/*******************************************************************************
 ** Reopen a comment thread.
 *******************************************************************************/
public class ReopenCommentSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** Describe the operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.POST, "/comments/{id}/reopen", "Reopen a comment thread"));
   }

   /*******************************************************************************
    ** Describe path and query inputs.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (parameters());
   }

   /*******************************************************************************
    ** Name the versioned response.
    *******************************************************************************/
   @Override
   protected String getResponseSchemaName()
   {
      return ("CollaborationMutationResultV1");
   }

   /*******************************************************************************
    ** Invoke the service with the authenticated caller's context.
    *******************************************************************************/
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      new CommentsService().reopen(id(input));
      return (success());
   }
}
