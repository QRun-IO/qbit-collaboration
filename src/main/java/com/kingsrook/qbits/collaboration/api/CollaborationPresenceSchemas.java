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
import java.util.TreeMap;
import com.kingsrook.qbits.collaboration.presence.PresenceRegistry;
import com.kingsrook.qqq.middleware.javalin.schemabuilder.ToSchema;
import com.kingsrook.qqq.openapi.model.Schema;
import com.kingsrook.qqq.openapi.model.Type;

/*******************************************************************************
 ** Versioned presence requests, polling snapshots, and realtime event data.
 *******************************************************************************/
public final class CollaborationPresenceSchemas
{
   /*******************************************************************************
    ** Schema namespace.
    *******************************************************************************/
   private CollaborationPresenceSchemas()
   {
   }

   /*******************************************************************************
    ** All identity information is derived from the authenticated session.
    *******************************************************************************/
   public record CollaborationPresenceRequestV1(String connectionId, String table, String pk, String mode, String fieldName) implements ToSchema
   {
      /*******************************************************************************
       ** Clients renew every twenty seconds; the lease expires after forty-five.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Renew a 45-second presence lease; send every 20 seconds.")
            .withProperties(new TreeMap<>(Map.of(
               "connectionId", text("Client connection identity.", false), "table", text("Parent table name.", false),
               "pk", text("Optional parent key; omit for a table view.", true),
               "mode", text("VIEWING or EDITING.", false).withEnumValues(List.of("VIEWING", "EDITING")),
               "fieldName", text("Optional focused field on a record.", true)))));
      }
   }

   /*******************************************************************************
    ** Leave removes only this authenticated user's connection and target.
    *******************************************************************************/
   public record CollaborationPresenceLeaveV1(String connectionId, String table, String pk) implements ToSchema
   {
      /*******************************************************************************
       ** Leave does not need a mode or a field.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Remove the caller's lease on this connection and target.")
            .withProperties(new TreeMap<>(Map.of(
               "connectionId", text("Client connection identity.", false), "table", text("Parent table name.", false),
               "pk", text("Optional parent key.", true)))));
      }
   }

   /*******************************************************************************
    ** The snapshot is also the data shape of presence.changed events.
    *******************************************************************************/
   public record CollaborationPresenceSnapshotV1(String table, String pk, List<PresenceRegistry.Viewer> viewers) implements ToSchema
   {
      /*******************************************************************************
       ** A table snapshot deliberately excludes record ids and focused field names.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Live viewers, merged across tabs; presence.changed uses this payload.")
            .withProperties(new TreeMap<>(Map.of(
               "table", text("Parent table name.", false), "pk", text("Record key; absent for table events.", true),
               "viewers", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationPresenceViewerV1"))))));
      }
   }

   /*******************************************************************************
    ** Only public identity and focus are carried, never field contents.
    *******************************************************************************/
   public record CollaborationPresenceViewerV1(String userId, String displayName, String mode, String fieldName) implements ToSchema
   {
      /*******************************************************************************
       ** Editing takes precedence when one user has several open tabs.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("One user merged across live tabs; EDITING takes precedence.")
            .withProperties(new TreeMap<>(Map.of(
               "userId", text("Stable user identity.", false), "displayName", text("Session display name.", false),
               "mode", text("VIEWING or EDITING.", false).withEnumValues(List.of("VIEWING", "EDITING")), "fieldName", text("Focused field; null on table snapshots.", true)))));
      }
   }

   /*******************************************************************************
    ** Reuse scalar schema descriptions consistently.
    *******************************************************************************/
   private static Schema text(String description, boolean nullable)
   {
      return (new Schema().withType(Type.STRING).withDescription(description).withNullable(nullable));
   }
}
