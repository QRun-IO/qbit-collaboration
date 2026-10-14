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
import java.util.Map;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationFavoriteListV1;
import com.kingsrook.qbits.collaboration.favorites.FavoritesService;
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.Parameter;
import com.kingsrook.qqq.openapi.model.Schema;


/*******************************************************************************
 ** List personal favorites.
 *******************************************************************************/
public class ListFavoritesSpec extends CollaborationJsonSpec
{
   /*******************************************************************************
    ** The extension's first spec publishes the shared schema catalog exactly once.
    ** QQQ collects components across specs and warns on every duplicate name.
    *******************************************************************************/
   @Override
   public Map<String, Schema> defineComponentSchemas()
   {
      return (CollaborationSchemas.components());
   }

   /*******************************************************************************
    ** Describe the operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (operation(HttpMethod.GET, "/favorites", "List personal favorites"));
   }

   /*******************************************************************************
    ** Describe path and query inputs.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (parameters("limit", "cursor"));
   }

   /*******************************************************************************
    ** Name the versioned response.
    *******************************************************************************/
   @Override
   protected String getResponseSchemaName()
   {
      return ("CollaborationFavoriteListV1");
   }

   /*******************************************************************************
    ** Invoke the service with the authenticated caller's context.
    *******************************************************************************/
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      var page = page(input, FavoritesService.list());
      return (new CollaborationFavoriteListV1(page.items().stream().map(CollaborationViews::favorite).toList(), page.nextCursor()));
   }
}
