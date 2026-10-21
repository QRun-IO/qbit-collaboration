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


import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.MiddlewareSpecExtension;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.MiddlewareSpecExtensionsMetaData;


/*******************************************************************************
 ** Register the collaboration UI API extension on a host instance.
 *******************************************************************************/
public final class CollaborationApi
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationApi()
   {
   }

   /*******************************************************************************
    ** Install the extension; the producer calls this when uiApi is enabled.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      MiddlewareSpecExtensionsMetaData.ofOrWithNew(instance).withExtension(extension());
   }

   /*******************************************************************************
    ** The standalone descriptor used by registration and contract generation.
    *******************************************************************************/
   public static MiddlewareSpecExtension extension()
   {
      return (new MiddlewareSpecExtension().withName("collaboration").withSemanticVersion("1.3.0")
         .withDescription("Record collaboration: favorites, recents, attachments, tags, conversations, following, timeline, people and presence")
         .withSpec(PostPresenceSpec.class).withSpec(DeletePresenceSpec.class).withSpec(GetPresenceSpec.class)
         .withSpec(OpenPresenceLeaseSpec.class).withSpec(RenewPresenceLeaseSpec.class)
         .withSpec(ListFavoritesSpec.class)
         .withSpec(StarFavoriteSpec.class)
         .withSpec(UnstarFavoriteSpec.class)
         .withSpec(RecordRecentSpec.class)
         .withSpec(OrderFavoritesSpec.class)
         .withSpec(ListRecentsSpec.class)
         .withSpec(ListAttachmentsSpec.class)
         .withSpec(DownloadAttachmentSpec.class)
         .withSpec(PreviewAttachmentSpec.class)
         .withSpec(PatchAttachmentSpec.class)
         .withSpec(DeleteAttachmentSpec.class)
         .withSpec(ListTagsSpec.class)
         .withSpec(CreateTagSpec.class)
         .withSpec(ListRecordTagsSpec.class)
         .withSpec(ApplyTagsSpec.class)
         .withSpec(ListCommentsSpec.class)
         .withSpec(PostCommentSpec.class)
         .withSpec(EditCommentSpec.class)
         .withSpec(DeleteCommentSpec.class)
         .withSpec(ResolveCommentSpec.class)
         .withSpec(ReopenCommentSpec.class)
         .withSpec(CommentHistorySpec.class)
         .withSpec(MentionCandidatesSpec.class)
         .withSpec(GetFollowSpec.class)
         .withSpec(PutFollowSpec.class)
         .withSpec(DeleteFollowSpec.class)
         .withSpec(ListFollowersSpec.class)
         .withSpec(GetTimelineSpec.class)
         .withSpec(ListPeopleSpec.class)
         .withSpec(GetPersonSpec.class)
         .withSpec(GetRecordLinkSpec.class)
         .withSpec(PreviewSharesSpec.class)
         .withSpec(CreateSharesSpec.class)
         .withSpec(CreateAccessRequestSpec.class)
         .withSpec(ListMyAccessRequestsSpec.class)
         .withSpec(ListTasksSpec.class)
         .withSpec(CreateTaskSpec.class)
         .withSpec(PatchTaskSpec.class)
         .withSpec(ListMyTasksSpec.class)
         .withSpec(PutAssignmentSpec.class)
         .withSpec(GetMyWorkSpec.class)
         .withSpec(MarkMentionsReadSpec.class)
         .withSpec(UploadAttachmentSpec.class));
   }
}
