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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.kingsrook.qqq.middleware.javalin.schemabuilder.ToSchema;
import com.kingsrook.qqq.openapi.model.Schema;
import com.kingsrook.qqq.openapi.model.Type;

/*******************************************************************************
 ** Typed sharing, access request, task and work contracts.
 *******************************************************************************/
public final class CollaborationWorkSchemas
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationWorkSchemas()
   {
   }

   /*******************************************************************************
    ** Versioned Link body.
    *******************************************************************************/
   public record CollaborationLinkV1(String url, String label) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("url", new Schema().withType(Type.STRING).withDescription("url.").withNullable(true)),
            Map.entry("label", new Schema().withType(Type.STRING).withDescription("label.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned ShareRecipient body.
    *******************************************************************************/
   public record CollaborationShareRecipientV1(String userId, String displayName, Boolean canRead) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("userId", new Schema().withType(Type.STRING).withDescription("userId.").withNullable(true)),
            Map.entry("displayName", new Schema().withType(Type.STRING).withDescription("displayName.").withNullable(true)),
            Map.entry("canRead", new Schema().withType(Type.BOOLEAN).withDescription("canRead.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned SharePreview body.
    *******************************************************************************/
   public record CollaborationSharePreviewV1(List<CollaborationShareRecipientV1> recipients) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("recipients", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationShareRecipientV1")).withDescription("recipients.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned Share body.
    *******************************************************************************/
   public record CollaborationShareV1(Integer id, String tableName, String recordId, String fromUserId, String toUserId, String message, Boolean recipientCouldRead, Instant sentAt, String fromDisplayName, String toDisplayName) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("id.").withNullable(true)),
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("tableName.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("recordId.").withNullable(true)),
            Map.entry("fromUserId", new Schema().withType(Type.STRING).withDescription("fromUserId.").withNullable(true)),
            Map.entry("toUserId", new Schema().withType(Type.STRING).withDescription("toUserId.").withNullable(true)),
            Map.entry("message", new Schema().withType(Type.STRING).withDescription("message.").withNullable(true)),
            Map.entry("recipientCouldRead", new Schema().withType(Type.BOOLEAN).withDescription("recipientCouldRead.").withNullable(true)),
            Map.entry("sentAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("sentAt.").withNullable(true)),
            Map.entry("fromDisplayName", new Schema().withType(Type.STRING).withDescription("Sender display name.").withNullable(true)),
            Map.entry("toDisplayName", new Schema().withType(Type.STRING).withDescription("Recipient display name.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned ShareList body.
    *******************************************************************************/
   public record CollaborationShareListV1(List<CollaborationShareV1> items) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationShareV1")).withDescription("items.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned ShareRequest body.
    *******************************************************************************/
   public record CollaborationShareRequestV1(List<String> toUserIds, String message) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("toUserIds", new Schema().withType(Type.ARRAY).withItems(new Schema().withType(Type.STRING)).withDescription("toUserIds.").withNullable(true)),
            Map.entry("message", new Schema().withType(Type.STRING).withDescription("message.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned AccessRequest body.
    *******************************************************************************/
   public record CollaborationAccessRequestV1(Integer id, String tableName, String recordId, String requesterUserId, String message, String status, Instant handledAt, String note, String requesterDisplayName, String handledByDisplayName) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("id.").withNullable(true)),
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("tableName.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("recordId.").withNullable(true)),
            Map.entry("requesterUserId", new Schema().withType(Type.STRING).withDescription("requesterUserId.").withNullable(true)),
            Map.entry("message", new Schema().withType(Type.STRING).withDescription("message.").withNullable(true)),
            Map.entry("status", new Schema().withType(Type.STRING).withDescription("status.").withNullable(true)),
            Map.entry("handledAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("handledAt.").withNullable(true)),
            Map.entry("note", new Schema().withType(Type.STRING).withDescription("note.").withNullable(true)),
            Map.entry("requesterDisplayName", new Schema().withType(Type.STRING).withDescription("Requester display name.").withNullable(true)),
            Map.entry("handledByDisplayName", new Schema().withType(Type.STRING).withDescription("Display name of the person who handled the request.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned AccessRequestCreateRequest body.
    *******************************************************************************/
   public record CollaborationAccessRequestCreateRequestV1(String tableName, String recordId, String message, Integer shareId) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("tableName.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("recordId.").withNullable(true)),
            Map.entry("message", new Schema().withType(Type.STRING).withDescription("message.").withNullable(true)),
            Map.entry("shareId", new Schema().withType(Type.INTEGER).withDescription("shareId.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned AccessRequestList body.
    *******************************************************************************/
   public record CollaborationAccessRequestListV1(List<CollaborationAccessRequestV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationAccessRequestV1")).withDescription("items.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("nextCursor.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned Task body.
    *******************************************************************************/
   public record CollaborationTaskV1(Integer id, String tableName, String recordId, String title, String notes, String assigneeUserId, Instant dueAt, Instant remindAt, String status, String createdByUserId, Instant completedAt, String completedByUserId, String assigneeDisplayName, String createdByDisplayName, String completedByDisplayName) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("id.").withNullable(true)),
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("tableName.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("recordId.").withNullable(true)),
            Map.entry("title", new Schema().withType(Type.STRING).withDescription("title.").withNullable(true)),
            Map.entry("notes", new Schema().withType(Type.STRING).withDescription("notes.").withNullable(true)),
            Map.entry("assigneeUserId", new Schema().withType(Type.STRING).withDescription("assigneeUserId.").withNullable(true)),
            Map.entry("dueAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("dueAt.").withNullable(true)),
            Map.entry("remindAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("remindAt.").withNullable(true)),
            Map.entry("status", new Schema().withType(Type.STRING).withDescription("status.").withNullable(true)),
            Map.entry("createdByUserId", new Schema().withType(Type.STRING).withDescription("createdByUserId.").withNullable(true)),
            Map.entry("completedAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("completedAt.").withNullable(true)),
            Map.entry("completedByUserId", new Schema().withType(Type.STRING).withDescription("completedByUserId.").withNullable(true)),
            Map.entry("assigneeDisplayName", new Schema().withType(Type.STRING).withDescription("Assignee display name.").withNullable(true)),
            Map.entry("createdByDisplayName", new Schema().withType(Type.STRING).withDescription("Creator display name.").withNullable(true)),
            Map.entry("completedByDisplayName", new Schema().withType(Type.STRING).withDescription("Display name of the person who completed the task.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned TaskCreateRequest body.
    *******************************************************************************/
   public record CollaborationTaskCreateRequestV1(String title, String notes, String assigneeUserId, String dueAt, String remindAt) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("title", new Schema().withType(Type.STRING).withDescription("title.").withNullable(true)),
            Map.entry("notes", new Schema().withType(Type.STRING).withDescription("notes.").withNullable(true)),
            Map.entry("assigneeUserId", new Schema().withType(Type.STRING).withDescription("assigneeUserId.").withNullable(true)),
            Map.entry("dueAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("dueAt.").withNullable(true)),
            Map.entry("remindAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("remindAt.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned TaskPatchRequest body.
    *******************************************************************************/
   public record CollaborationTaskPatchRequestV1(String title, String notes, String assigneeUserId, String dueAt, String remindAt, String status) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("title", new Schema().withType(Type.STRING).withDescription("title.").withNullable(true)),
            Map.entry("notes", new Schema().withType(Type.STRING).withDescription("notes.").withNullable(true)),
            Map.entry("assigneeUserId", new Schema().withType(Type.STRING).withDescription("assigneeUserId.").withNullable(true)),
            Map.entry("dueAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("dueAt.").withNullable(true)),
            Map.entry("remindAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("remindAt.").withNullable(true)),
            Map.entry("status", new Schema().withType(Type.STRING).withDescription("status.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned TaskList body.
    *******************************************************************************/
   public record CollaborationTaskListV1(List<CollaborationTaskV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationTaskV1")).withDescription("items.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("nextCursor.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned AssignmentRequest body.
    *******************************************************************************/
   public record CollaborationAssignmentRequestV1(String ownerUserId, String assigneeUserId) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("ownerUserId", new Schema().withType(Type.STRING).withDescription("ownerUserId.").withNullable(true)),
            Map.entry("assigneeUserId", new Schema().withType(Type.STRING).withDescription("assigneeUserId.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned Assignment body.
    *******************************************************************************/
   public record CollaborationAssignmentV1(Integer id, String tableName, String recordId, String ownerUserId, String assigneeUserId, String ownerDisplayName, String assigneeDisplayName) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("id.").withNullable(true)),
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("tableName.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("recordId.").withNullable(true)),
            Map.entry("ownerUserId", new Schema().withType(Type.STRING).withDescription("ownerUserId.").withNullable(true)),
            Map.entry("assigneeUserId", new Schema().withType(Type.STRING).withDescription("assigneeUserId.").withNullable(true)),
            Map.entry("ownerDisplayName", new Schema().withType(Type.STRING).withDescription("Owner display name.").withNullable(true)),
            Map.entry("assigneeDisplayName", new Schema().withType(Type.STRING).withDescription("Assignee display name.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Versioned MyWork body.
    *******************************************************************************/
   public record CollaborationMyWorkV1(Long openTaskCount, List<CollaborationTaskV1> tasks, Map<String, Long> assignedRecordsByTable, Long unreadMentionCount) implements ToSchema
   {
      /*******************************************************************************
       ** Explicit schema for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("openTaskCount", new Schema().withType(Type.INTEGER).withDescription("openTaskCount.").withNullable(true)),
            Map.entry("tasks", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationTaskV1")).withDescription("tasks.").withNullable(true)),
            Map.entry("assignedRecordsByTable", new Schema().withType(Type.OBJECT).withAdditionalProperties(new Schema().withType(Type.INTEGER)).withDescription("assignedRecordsByTable.").withNullable(true)),
            Map.entry("unreadMentionCount", new Schema().withType(Type.INTEGER).withDescription("unreadMentionCount.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Named components used by these endpoints.
    *******************************************************************************/
   public static Map<String, Schema> components()
   {
      Map<String, Schema> schemas = new TreeMap<>();
      schemas.put("CollaborationLinkV1", new CollaborationLinkV1(null, null).toSchema());
      schemas.put("CollaborationShareRecipientV1", new CollaborationShareRecipientV1(null, null, null).toSchema());
      schemas.put("CollaborationSharePreviewV1", new CollaborationSharePreviewV1(null).toSchema());
      schemas.put("CollaborationShareV1", new CollaborationShareV1(null, null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationShareListV1", new CollaborationShareListV1(null).toSchema());
      schemas.put("CollaborationShareRequestV1", new CollaborationShareRequestV1(null, null).toSchema());
      schemas.put("CollaborationAccessRequestV1", new CollaborationAccessRequestV1(null, null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationAccessRequestCreateRequestV1", new CollaborationAccessRequestCreateRequestV1(null, null, null, null).toSchema());
      schemas.put("CollaborationAccessRequestListV1", new CollaborationAccessRequestListV1(null, null).toSchema());
      schemas.put("CollaborationTaskV1", new CollaborationTaskV1(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationTaskCreateRequestV1", new CollaborationTaskCreateRequestV1(null, null, null, null, null).toSchema());
      schemas.put("CollaborationTaskPatchRequestV1", new CollaborationTaskPatchRequestV1(null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationTaskListV1", new CollaborationTaskListV1(null, null).toSchema());
      schemas.put("CollaborationAssignmentRequestV1", new CollaborationAssignmentRequestV1(null, null).toSchema());
      schemas.put("CollaborationAssignmentV1", new CollaborationAssignmentV1(null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationMyWorkV1", new CollaborationMyWorkV1(null, null, null, null).toSchema());
      return (schemas);
   }
}
