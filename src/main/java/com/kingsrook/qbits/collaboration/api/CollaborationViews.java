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


import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationAttachmentV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationCommentRevisionV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationCommentV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationFavoriteV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationFollowV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationMentionCandidateV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationMentionV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationPersonV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationTagV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationTimelineDetailV1;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationTimelineItemV1;
import com.kingsrook.qbits.collaboration.attachments.AttachmentEntry;
import com.kingsrook.qbits.collaboration.comments.CommentEntry;
import com.kingsrook.qbits.collaboration.comments.CommentRevision;
import com.kingsrook.qbits.collaboration.comments.MentionCandidate;
import com.kingsrook.qbits.collaboration.comments.MentionEntry;
import com.kingsrook.qbits.collaboration.favorites.FavoriteEntry;
import com.kingsrook.qbits.collaboration.follow.FollowEntry;
import com.kingsrook.qbits.collaboration.people.PersonEntry;
import com.kingsrook.qbits.collaboration.tags.TagEntry;
import com.kingsrook.qbits.collaboration.timeline.TimelineDetail;
import com.kingsrook.qbits.collaboration.timeline.TimelineItem;


/*******************************************************************************
 ** Map service results to the stable UI API wire types.
 *******************************************************************************/
final class CollaborationViews
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationViews()
   {
   }

   /*******************************************************************************
    ** Project favorite display data.
    *******************************************************************************/
   static CollaborationFavoriteV1 favorite(FavoriteEntry value)
   {
      return (new CollaborationFavoriteV1(
         value.id(), value.targetType(), value.targetName(), value.recordId(), value.label(), value.folder(), value.sortOrder(), value.available(), value.href()));
   }

   /*******************************************************************************
    ** Project attachment display data.
    *******************************************************************************/
   static CollaborationAttachmentV1 attachment(AttachmentEntry value)
   {
      return (new CollaborationAttachmentV1(
         value.id(), value.tableName(), value.recordId(), value.fileName(), value.contentType(), value.sizeBytes(), value.sha256(), value.description(), value.createdByUserId(), value.createDate(), value.canDelete()));
   }

   /*******************************************************************************
    ** Project tag display data.
    *******************************************************************************/
   static CollaborationTagV1 tag(TagEntry value)
   {
      return (new CollaborationTagV1(
         value.id(), value.name(), value.label(), value.color(), value.tableName(), value.approved(), value.usageCount()));
   }

   /*******************************************************************************
    ** Project person display data.
    *******************************************************************************/
   static CollaborationPersonV1 person(PersonEntry value)
   {
      return (new CollaborationPersonV1(
         value.userId(), value.displayName(), value.email(), value.lastSeenAt()));
   }

   /*******************************************************************************
    ** Project mention display data.
    *******************************************************************************/
   static CollaborationMentionV1 mention(MentionEntry value)
   {
      return (new CollaborationMentionV1(
         value.userId(), value.visible()));
   }

   /*******************************************************************************
    ** Project comment display data.
    *******************************************************************************/
   static CollaborationCommentV1 comment(CommentEntry value)
   {
      return (new CollaborationCommentV1(
         value.id(), value.threadId(), value.parentCommentId(), value.body(), value.fieldName(), value.authorUserId(), value.authorDisplayName(), value.createdAt(), value.editedAt(), value.deleted(), value.resolved(), value.canEdit(), value.canDelete(), value.mentions().stream().map(CollaborationViews::mention).toList(), value.replies().stream().map(CollaborationViews::comment).toList()));
   }

   /*******************************************************************************
    ** Project commentrevision display data.
    *******************************************************************************/
   static CollaborationCommentRevisionV1 commentRevision(CommentRevision value)
   {
      return (new CollaborationCommentRevisionV1(
         value.id(), value.body(), value.editedAt(), value.editedByUserId()));
   }

   /*******************************************************************************
    ** Project follow display data.
    *******************************************************************************/
   static CollaborationFollowV1 follow(FollowEntry value)
   {
      return (new CollaborationFollowV1(
         value.id(), value.userId(), value.reason(), value.notifyOnChange(), value.notifyOnComment(), value.mutedUntil()));
   }

   /*******************************************************************************
    ** Project timelinedetail display data.
    *******************************************************************************/
   static CollaborationTimelineDetailV1 timelineDetail(TimelineDetail value)
   {
      return (new CollaborationTimelineDetailV1(
         value.fieldName(), value.oldValue(), value.newValue(), value.message()));
   }

   /*******************************************************************************
    ** Project timelineitem display data.
    *******************************************************************************/
   static CollaborationTimelineItemV1 timelineItem(TimelineItem value)
   {
      return (new CollaborationTimelineItemV1(
         value.kind(), value.id(), value.at(), value.actorUserId(), value.actorName(), value.summary(), value.details().stream().map(CollaborationViews::timelineDetail).toList(), value.refTable(), value.refId()));
   }

   /*******************************************************************************
    ** Represent access uncertainty explicitly instead of serializing Optional.
    *******************************************************************************/
   static CollaborationMentionCandidateV1 mentionCandidate(MentionCandidate value)
   {
      return (new CollaborationMentionCandidateV1(person(value.person()), value.canRead().map(Object::toString).orElse("unknown")));
   }

   /*******************************************************************************
    ** Project Share public fields.
    *******************************************************************************/
   static CollaborationWorkSchemas.CollaborationShareV1 share(com.kingsrook.qqq.backend.core.model.data.QRecord row)
   {
      return (new CollaborationWorkSchemas.CollaborationShareV1(row.getValueInteger("id"), row.getValueString("tableName"), row.getValueString("recordId"), row.getValueString("fromUserId"), row.getValueString("toUserId"), row.getValueString("message"), row.getValueBoolean("recipientCouldRead"), row.getValueInstant("sentAt")));
   }

   /*******************************************************************************
    ** Project AccessRequest public fields.
    *******************************************************************************/
   static CollaborationWorkSchemas.CollaborationAccessRequestV1 accessRequest(com.kingsrook.qqq.backend.core.model.data.QRecord row)
   {
      return (new CollaborationWorkSchemas.CollaborationAccessRequestV1(row.getValueInteger("id"), row.getValueString("tableName"), row.getValueString("recordId"), row.getValueString("requesterUserId"), row.getValueString("message"), row.getValueString("status"), row.getValueInstant("handledAt"), row.getValueString("note")));
   }

   /*******************************************************************************
    ** Project Task public fields.
    *******************************************************************************/
   static CollaborationWorkSchemas.CollaborationTaskV1 task(com.kingsrook.qqq.backend.core.model.data.QRecord row)
   {
      return (new CollaborationWorkSchemas.CollaborationTaskV1(row.getValueInteger("id"), row.getValueString("tableName"), row.getValueString("recordId"), row.getValueString("title"), row.getValueString("notes"), row.getValueString("assigneeUserId"), row.getValueInstant("dueAt"), row.getValueInstant("remindAt"), row.getValueString("status"), row.getValueString("createdByUserId"), row.getValueInstant("completedAt"), row.getValueString("completedByUserId")));
   }

   /*******************************************************************************
    ** Project Assignment public fields.
    *******************************************************************************/
   static CollaborationWorkSchemas.CollaborationAssignmentV1 assignment(com.kingsrook.qqq.backend.core.model.data.QRecord row)
   {
      return (new CollaborationWorkSchemas.CollaborationAssignmentV1(row.getValueInteger("id"), row.getValueString("tableName"), row.getValueString("recordId"), row.getValueString("ownerUserId"), row.getValueString("assigneeUserId")));
   }

}
