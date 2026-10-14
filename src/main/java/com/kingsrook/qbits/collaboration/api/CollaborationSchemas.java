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
 ** Versioned wire records and explicit schemas, independent of persistence DTOs.
 *******************************************************************************/
public final class CollaborationSchemas
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationSchemas()
   {
   }

   /*******************************************************************************
    ** A saved navigation target, with availability rechecked for the caller.
    *******************************************************************************/
   public record CollaborationFavoriteV1(Integer id, String targetType, String targetName, String recordId, String label, String folder, Integer sortOrder, Boolean available, String href) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A saved navigation target, with availability rechecked for the caller.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Favorite identifier.").withNullable(true)),
            Map.entry("targetType", new Schema().withType(Type.STRING).withEnumValues(List.of("TABLE","APP","RECORD","PROCESS","REPORT","SAVED_VIEW")).withDescription("Kind of navigation target.").withNullable(true)),
            Map.entry("targetName", new Schema().withType(Type.STRING).withDescription("Metadata name of the table, app, process, report or saved view.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("Canonical record key; null for non-record targets.").withNullable(true)),
            Map.entry("label", new Schema().withType(Type.STRING).withDescription("Current display label; Unavailable when access is lost.").withNullable(true)),
            Map.entry("folder", new Schema().withType(Type.STRING).withDescription("Optional personal grouping.").withNullable(true)),
            Map.entry("sortOrder", new Schema().withType(Type.INTEGER).withDescription("Position in the personal list.").withNullable(true)),
            Map.entry("available", new Schema().withType(Type.BOOLEAN).withDescription("Whether the caller can currently open this target.").withNullable(true)),
            Map.entry("href", new Schema().withType(Type.STRING).withDescription("Navigation link, absent for unavailable targets.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** An attachment visible through its parent record; no storage coordinates are exposed.
    *******************************************************************************/
   public record CollaborationAttachmentV1(Integer id, String tableName, String recordId, String fileName, String contentType, Long sizeBytes, String sha256, String description, String createdByUserId, Instant createDate, Boolean canDelete) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("An attachment visible through its parent record; no storage coordinates are exposed.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Attachment identifier.").withNullable(true)),
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("Parent table metadata name.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("Canonical parent record key.").withNullable(true)),
            Map.entry("fileName", new Schema().withType(Type.STRING).withDescription("Safe display filename.").withNullable(true)),
            Map.entry("contentType", new Schema().withType(Type.STRING).withDescription("MIME content type.").withNullable(true)),
            Map.entry("sizeBytes", new Schema().withType(Type.INTEGER).withDescription("Stored byte count.").withNullable(true)),
            Map.entry("sha256", new Schema().withType(Type.STRING).withDescription("SHA-256 content digest.").withNullable(true)),
            Map.entry("description", new Schema().withType(Type.STRING).withDescription("Optional description.").withNullable(true)),
            Map.entry("createdByUserId", new Schema().withType(Type.STRING).withDescription("Uploader identity.").withNullable(true)),
            Map.entry("createDate", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Upload time.").withNullable(true)),
            Map.entry("canDelete", new Schema().withType(Type.BOOLEAN).withDescription("Whether the caller may change or delete this attachment.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** An approved tag or the caller's pending suggestion.
    *******************************************************************************/
   public record CollaborationTagV1(Integer id, String name, String label, String color, String tableName, Boolean approved, Integer usageCount) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("An approved tag or the caller's pending suggestion.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Tag identifier.").withNullable(true)),
            Map.entry("name", new Schema().withType(Type.STRING).withDescription("Normalized vocabulary name.").withNullable(true)),
            Map.entry("label", new Schema().withType(Type.STRING).withDescription("Display label.").withNullable(true)),
            Map.entry("color", new Schema().withType(Type.STRING).withDescription("Optional display color.").withNullable(true)),
            Map.entry("tableName", new Schema().withType(Type.STRING).withDescription("Table scope; null means global.").withNullable(true)),
            Map.entry("approved", new Schema().withType(Type.BOOLEAN).withDescription("Whether anyone may apply this tag.").withNullable(true)),
            Map.entry("usageCount", new Schema().withType(Type.INTEGER).withDescription("Number of record associations.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** An active directory entry.
    *******************************************************************************/
   public record CollaborationPersonV1(String userId, String displayName, String email, Instant lastSeenAt) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("An active directory entry.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("userId", new Schema().withType(Type.STRING).withDescription("Stable user identity.").withNullable(true)),
            Map.entry("displayName", new Schema().withType(Type.STRING).withDescription("Display name.").withNullable(true)),
            Map.entry("email", new Schema().withType(Type.STRING).withDescription("Directory email, if known.").withNullable(true)),
            Map.entry("lastSeenAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Most recent directory refresh time.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** A mention embedded in a comment.
    *******************************************************************************/
   public record CollaborationMentionV1(String userId, Boolean visible) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A mention embedded in a comment.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("userId", new Schema().withType(Type.STRING).withDescription("Mentioned user identity.").withNullable(true)),
            Map.entry("visible", new Schema().withType(Type.BOOLEAN).withDescription("Whether recipient access was confirmed when mentioned.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** A matching person and their known parent access.
    *******************************************************************************/
   public record CollaborationMentionCandidateV1(CollaborationPersonV1 person, String canRead) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A matching person and their known parent access.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("person", new Schema().withRefToSchema("CollaborationPersonV1").withDescription("Directory identity.").withNullable(true)),
            Map.entry("canRead", new Schema().withType(Type.STRING).withEnumValues(List.of("true","false","unknown")).withDescription("Recipient access: string true, false, or unknown when it cannot be established.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** A comment or reply; deleted comments retain their position without body text.
    *******************************************************************************/
   public record CollaborationCommentV1(Integer id, Integer threadId, Integer parentCommentId, String body, String fieldName, String authorUserId, String authorDisplayName, Instant createdAt, Instant editedAt, Boolean deleted, Boolean resolved, Boolean canEdit, Boolean canDelete, List<CollaborationMentionV1> mentions, List<CollaborationCommentV1> replies) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A comment or reply; deleted comments retain their position without body text.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Comment identifier.").withNullable(true)),
            Map.entry("threadId", new Schema().withType(Type.INTEGER).withDescription("Root thread identifier; null on roots.").withNullable(true)),
            Map.entry("parentCommentId", new Schema().withType(Type.INTEGER).withDescription("Root comment for a reply; null on roots.").withNullable(true)),
            Map.entry("body", new Schema().withType(Type.STRING).withDescription("Plain text with mention tokens; null for deleted comments.").withNullable(true)),
            Map.entry("fieldName", new Schema().withType(Type.STRING).withDescription("Optional field being discussed.").withNullable(true)),
            Map.entry("authorUserId", new Schema().withType(Type.STRING).withDescription("Author identity.").withNullable(true)),
            Map.entry("authorDisplayName", new Schema().withType(Type.STRING).withDescription("Author display name.").withNullable(true)),
            Map.entry("createdAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Creation time.").withNullable(true)),
            Map.entry("editedAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Last edit time, if edited.").withNullable(true)),
            Map.entry("deleted", new Schema().withType(Type.BOOLEAN).withDescription("Whether the comment is soft deleted.").withNullable(true)),
            Map.entry("resolved", new Schema().withType(Type.BOOLEAN).withDescription("Whether the thread is resolved.").withNullable(true)),
            Map.entry("canEdit", new Schema().withType(Type.BOOLEAN).withDescription("Caller may edit the body.").withNullable(true)),
            Map.entry("canDelete", new Schema().withType(Type.BOOLEAN).withDescription("Caller may delete this comment.").withNullable(true)),
            Map.entry("mentions", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationMentionV1")).withDescription("Mention recipients.").withNullable(true)),
            Map.entry("replies", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationCommentV1")).withDescription("Replies in oldest-first order; empty on replies.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** A previous comment body.
    *******************************************************************************/
   public record CollaborationCommentRevisionV1(Integer id, String body, Instant editedAt, String editedByUserId) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A previous comment body.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Revision identifier.").withNullable(true)),
            Map.entry("body", new Schema().withType(Type.STRING).withDescription("Previous body text.").withNullable(true)),
            Map.entry("editedAt", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Time the body was replaced.").withNullable(true)),
            Map.entry("editedByUserId", new Schema().withType(Type.STRING).withDescription("Editor identity.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** A subscription to record changes and comments.
    *******************************************************************************/
   public record CollaborationFollowV1(Integer id, String userId, String reason, Boolean notifyOnChange, Boolean notifyOnComment, Instant mutedUntil) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A subscription to record changes and comments.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Subscription identifier.").withNullable(true)),
            Map.entry("userId", new Schema().withType(Type.STRING).withDescription("Follower identity.").withNullable(true)),
            Map.entry("reason", new Schema().withType(Type.STRING).withEnumValues(List.of("MANUAL", "AUTHOR", "COMMENTED", "MENTIONED", "ASSIGNED", "SHARED")).withDescription("How following began.").withNullable(true)),
            Map.entry("notifyOnChange", new Schema().withType(Type.BOOLEAN).withDescription("Receive record change digests.").withNullable(true)),
            Map.entry("notifyOnComment", new Schema().withType(Type.BOOLEAN).withDescription("Receive new comment notifications.").withNullable(true)),
            Map.entry("mutedUntil", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Notifications muted until this time, if set.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** The current caller's subscription state.
    *******************************************************************************/
   public record CollaborationFollowStateV1(Boolean following, CollaborationFollowV1 follow) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("The current caller's subscription state.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("following", new Schema().withType(Type.BOOLEAN).withDescription("Whether the caller follows the record.").withNullable(true)),
            Map.entry("follow", new Schema().withRefToSchema("CollaborationFollowV1").withDescription("Subscription settings; null when not following.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** A permitted field change from a core audit.
    *******************************************************************************/
   public record CollaborationTimelineDetailV1(String fieldName, String oldValue, String newValue, String message) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("A permitted field change from a core audit.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("fieldName", new Schema().withType(Type.STRING).withDescription("Changed field metadata name.").withNullable(true)),
            Map.entry("oldValue", new Schema().withType(Type.STRING).withDescription("Previous display value.").withNullable(true)),
            Map.entry("newValue", new Schema().withType(Type.STRING).withDescription("New display value.").withNullable(true)),
            Map.entry("message", new Schema().withType(Type.STRING).withDescription("Optional audit message.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** One visible event in the merged record timeline.
    *******************************************************************************/
   public record CollaborationTimelineItemV1(String kind, Long id, Instant at, String actorUserId, String actorName, String summary, List<CollaborationTimelineDetailV1> details, String refTable, String refId) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("One visible event in the merged record timeline.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("kind", new Schema().withType(Type.STRING).withEnumValues(List.of("ACTIVITY","AUDIT")).withDescription("Event source.").withNullable(true)),
            Map.entry("id", new Schema().withType(Type.INTEGER).withDescription("Identifier within the source.").withNullable(true)),
            Map.entry("at", new Schema().withType(Type.STRING).withFormat("date-time").withDescription("Event time.").withNullable(true)),
            Map.entry("actorUserId", new Schema().withType(Type.STRING).withDescription("Actor identity, when known.").withNullable(true)),
            Map.entry("actorName", new Schema().withType(Type.STRING).withDescription("Actor display name.").withNullable(true)),
            Map.entry("summary", new Schema().withType(Type.STRING).withDescription("Human-readable event summary.").withNullable(true)),
            Map.entry("details", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationTimelineDetailV1")).withDescription("Field details filtered by caller permissions.").withNullable(true)),
            Map.entry("refTable", new Schema().withType(Type.STRING).withDescription("Related table metadata name.").withNullable(true)),
            Map.entry("refId", new Schema().withType(Type.STRING).withDescription("Related record key.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Ordered personal favorites.
    *******************************************************************************/
   public record CollaborationFavoriteListV1(List<CollaborationFavoriteV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Ordered personal favorites.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationFavoriteV1")).withDescription("Ordered personal favorites.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Most recent visible targets, at most fifty.
    *******************************************************************************/
   public record CollaborationRecentListV1(List<CollaborationFavoriteV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Most recent visible targets, at most fifty.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationFavoriteV1")).withDescription("Most recent visible targets, at most fifty.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Attachments, newest first.
    *******************************************************************************/
   public record CollaborationAttachmentListV1(List<CollaborationAttachmentV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Attachments, newest first.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationAttachmentV1")).withDescription("Attachments, newest first.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Available or applied tags.
    *******************************************************************************/
   public record CollaborationTagListV1(List<CollaborationTagV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Available or applied tags.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationTagV1")).withDescription("Available or applied tags.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Matching active directory entries.
    *******************************************************************************/
   public record CollaborationPersonListV1(List<CollaborationPersonV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Matching active directory entries.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationPersonV1")).withDescription("Matching active directory entries.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Candidates ranked with record participants first.
    *******************************************************************************/
   public record CollaborationMentionCandidateListV1(List<CollaborationMentionCandidateV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Candidates ranked with record participants first.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationMentionCandidateV1")).withDescription("Candidates ranked with record participants first.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Root threads by latest activity, with replies.
    *******************************************************************************/
   public record CollaborationCommentPageV1(List<CollaborationCommentV1> threads, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Root threads by latest activity, with replies.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("threads", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationCommentV1")).withDescription("Root threads by latest activity, with replies.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Previous bodies in edit order.
    *******************************************************************************/
   public record CollaborationCommentHistoryV1(List<CollaborationCommentRevisionV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Previous bodies in edit order.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationCommentRevisionV1")).withDescription("Previous bodies in edit order.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Subscriptions for this record.
    *******************************************************************************/
   public record CollaborationFollowerListV1(List<CollaborationFollowV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Subscriptions for this record.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationFollowV1")).withDescription("Subscriptions for this record.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Merged events, newest first.
    *******************************************************************************/
   public record CollaborationTimelinePageV1(List<CollaborationTimelineItemV1> items, String nextCursor) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Merged events, newest first.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("items", new Schema().withType(Type.ARRAY).withItems(new Schema().withRefToSchema("CollaborationTimelineItemV1")).withDescription("Merged events, newest first.").withNullable(true)),
            Map.entry("nextCursor", new Schema().withType(Type.STRING).withDescription("Opaque continuation token; null when finished or the service has no continuation support.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Acknowledgement of a completed mutation.
    *******************************************************************************/
   public record CollaborationMutationResultV1(Boolean success) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Acknowledgement of a completed mutation.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("success", new Schema().withType(Type.BOOLEAN).withDescription("True after the requested operation completes.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Temporary access to file bytes. Clients follow url.
    *******************************************************************************/
   public record CollaborationDownloadV1(String url) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Temporary access to file bytes. Clients follow url.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("url", new Schema().withType(Type.STRING).withDescription("Presigned URL, valid for five minutes. Clients follow url; never persist it.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Standard QQQ error response.
    *******************************************************************************/
   public record CollaborationErrorV1(String error) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Standard QQQ error response.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("error", new Schema().withType(Type.STRING).withDescription("Human-readable reason the operation failed.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Target to star, unstar, or record as recently viewed.
    *******************************************************************************/
   public record CollaborationTargetRequestV1(String targetType, String targetName, String recordId) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Target to star, unstar, or record as recently viewed.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("targetType", new Schema().withType(Type.STRING).withEnumValues(List.of("TABLE","APP","RECORD","PROCESS","REPORT","SAVED_VIEW")).withDescription("Required target kind.").withNullable(true)),
            Map.entry("targetName", new Schema().withType(Type.STRING).withDescription("Required metadata name.").withNullable(true)),
            Map.entry("recordId", new Schema().withType(Type.STRING).withDescription("Required for RECORD; omitted otherwise.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Move selected favorites first and optionally assign folders.
    *******************************************************************************/
   public record CollaborationFavoriteOrderRequestV1(List<Integer> ids, Map<String, String> folders) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Move selected favorites first and optionally assign folders.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("ids", new Schema().withType(Type.ARRAY).withItems(new Schema().withType(Type.INTEGER)).withDescription("Required ordered favorite identifiers, with no duplicates.").withNullable(true)),
            Map.entry("folders", new Schema().withType(Type.OBJECT).withAdditionalProperties(new Schema().withType(Type.STRING).withNullable(true)).withDescription("Optional map from favorite identifier to folder; null values clear folders.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Change attachment display metadata.
    *******************************************************************************/
   public record CollaborationAttachmentPatchRequestV1(String fileName, String description) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Change attachment display metadata.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("fileName", new Schema().withType(Type.STRING).withDescription("Optional new display filename; null leaves unchanged.").withNullable(true)),
            Map.entry("description", new Schema().withType(Type.STRING).withDescription("Optional description; empty clears, null leaves unchanged.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Multipart upload with exactly one file part named file.
    *******************************************************************************/
   public record CollaborationAttachmentUploadRequestV1(String file, String description) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Multipart upload with exactly one file part named file.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("file", new Schema().withType(Type.STRING).withFormat("binary").withDescription("Required file content.").withNullable(true)),
            Map.entry("description", new Schema().withType(Type.STRING).withDescription("Optional description.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Create a tag or suggest one under the table policy.
    *******************************************************************************/
   public record CollaborationTagCreateRequestV1(String table, String label, String color) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Create a tag or suggest one under the table policy.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("table", new Schema().withType(Type.STRING).withDescription("Required table metadata name.").withNullable(true)),
            Map.entry("label", new Schema().withType(Type.STRING).withDescription("Required display label, 1 to 60 characters.").withNullable(true)),
            Map.entry("color", new Schema().withType(Type.STRING).withDescription("Optional display color.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Add and remove tag associations in one request.
    *******************************************************************************/
   public record CollaborationTagApplyRequestV1(List<Integer> add, List<Integer> remove) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Add and remove tag associations in one request.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("add", new Schema().withType(Type.ARRAY).withItems(new Schema().withType(Type.INTEGER)).withDescription("Tag identifiers to add; omitted means none.").withNullable(true)),
            Map.entry("remove", new Schema().withType(Type.ARRAY).withItems(new Schema().withType(Type.INTEGER)).withDescription("Tag identifiers to remove; omitted means none.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Post a root comment or one-level reply.
    *******************************************************************************/
   public record CollaborationCommentPostRequestV1(String body, Integer parentCommentId, String fieldName) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Post a root comment or one-level reply.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("body", new Schema().withType(Type.STRING).withDescription("Required body, 1 to 20000 characters; mentions use @[Name](user:id).").withNullable(true)),
            Map.entry("parentCommentId", new Schema().withType(Type.INTEGER).withDescription("Optional comment to reply to.").withNullable(true)),
            Map.entry("fieldName", new Schema().withType(Type.STRING).withDescription("Optional existing parent field name.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Replace the caller's comment body.
    *******************************************************************************/
   public record CollaborationCommentEditRequestV1(String body) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Replace the caller's comment body.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("body", new Schema().withType(Type.STRING).withDescription("Required replacement body, 1 to 20000 characters.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** Configure the caller's follow preferences.
    *******************************************************************************/
   public record CollaborationFollowRequestV1(Boolean notifyOnChange, Boolean notifyOnComment) implements ToSchema
   {
      /*******************************************************************************
       ** Describe this versioned body for generated clients.
       *******************************************************************************/
      @Override
      public Schema toSchema()
      {
         return (new Schema().withType(Type.OBJECT).withDescription("Configure the caller's follow preferences.").withProperties(new TreeMap<>(Map.ofEntries(
            Map.entry("notifyOnChange", new Schema().withType(Type.BOOLEAN).withDescription("Optional change notifications preference; omission retains current setting.").withNullable(true)),
            Map.entry("notifyOnComment", new Schema().withType(Type.BOOLEAN).withDescription("Optional comment notifications preference; omission retains current setting.").withNullable(true))))));
      }
   }

   /*******************************************************************************
    ** All named components referenced by the extension.
    *******************************************************************************/
   public static Map<String, Schema> components()
   {
      Map<String, Schema> schemas = new TreeMap<>();
      schemas.put("CollaborationFavoriteV1", new CollaborationFavoriteV1(null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationAttachmentV1", new CollaborationAttachmentV1(null, null, null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationTagV1", new CollaborationTagV1(null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationPersonV1", new CollaborationPersonV1(null, null, null, null).toSchema());
      schemas.put("CollaborationMentionV1", new CollaborationMentionV1(null, null).toSchema());
      schemas.put("CollaborationMentionCandidateV1", new CollaborationMentionCandidateV1(null, null).toSchema());
      schemas.put("CollaborationCommentV1", new CollaborationCommentV1(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationCommentRevisionV1", new CollaborationCommentRevisionV1(null, null, null, null).toSchema());
      schemas.put("CollaborationFollowV1", new CollaborationFollowV1(null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationFollowStateV1", new CollaborationFollowStateV1(null, null).toSchema());
      schemas.put("CollaborationTimelineDetailV1", new CollaborationTimelineDetailV1(null, null, null, null).toSchema());
      schemas.put("CollaborationTimelineItemV1", new CollaborationTimelineItemV1(null, null, null, null, null, null, null, null, null).toSchema());
      schemas.put("CollaborationFavoriteListV1", new CollaborationFavoriteListV1(null, null).toSchema());
      schemas.put("CollaborationRecentListV1", new CollaborationRecentListV1(null, null).toSchema());
      schemas.put("CollaborationAttachmentListV1", new CollaborationAttachmentListV1(null, null).toSchema());
      schemas.put("CollaborationTagListV1", new CollaborationTagListV1(null, null).toSchema());
      schemas.put("CollaborationPersonListV1", new CollaborationPersonListV1(null, null).toSchema());
      schemas.put("CollaborationMentionCandidateListV1", new CollaborationMentionCandidateListV1(null, null).toSchema());
      schemas.put("CollaborationCommentPageV1", new CollaborationCommentPageV1(null, null).toSchema());
      schemas.put("CollaborationCommentHistoryV1", new CollaborationCommentHistoryV1(null, null).toSchema());
      schemas.put("CollaborationFollowerListV1", new CollaborationFollowerListV1(null, null).toSchema());
      schemas.put("CollaborationTimelinePageV1", new CollaborationTimelinePageV1(null, null).toSchema());
      schemas.put("CollaborationMutationResultV1", new CollaborationMutationResultV1(null).toSchema());
      schemas.put("CollaborationDownloadV1", new CollaborationDownloadV1(null).toSchema());
      schemas.put("CollaborationErrorV1", new CollaborationErrorV1(null).toSchema());
      schemas.put("CollaborationTargetRequestV1", new CollaborationTargetRequestV1(null, null, null).toSchema());
      schemas.put("CollaborationFavoriteOrderRequestV1", new CollaborationFavoriteOrderRequestV1(null, null).toSchema());
      schemas.put("CollaborationAttachmentPatchRequestV1", new CollaborationAttachmentPatchRequestV1(null, null).toSchema());
      schemas.put("CollaborationAttachmentUploadRequestV1", new CollaborationAttachmentUploadRequestV1(null, null).toSchema());
      schemas.put("CollaborationTagCreateRequestV1", new CollaborationTagCreateRequestV1(null, null, null).toSchema());
      schemas.put("CollaborationTagApplyRequestV1", new CollaborationTagApplyRequestV1(null, null).toSchema());
      schemas.put("CollaborationCommentPostRequestV1", new CollaborationCommentPostRequestV1(null, null, null).toSchema());
      schemas.put("CollaborationCommentEditRequestV1", new CollaborationCommentEditRequestV1(null).toSchema());
      schemas.put("CollaborationFollowRequestV1", new CollaborationFollowRequestV1(null, null).toSchema());
      schemas.putAll(CollaborationWorkSchemas.components());
      return (schemas);
   }
}
