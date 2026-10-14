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


package com.kingsrook.qbits.collaboration.notify;


/*******************************************************************************
 ** Transport-neutral notification. Body is markdown; link is a record href.
 ** sourceId identifies the event (mention, share, task or assignment row) so a
 ** transport can de-duplicate; actorUserId is the person who caused it. Both are
 ** optional.
 *******************************************************************************/
public record CollaborationNotification(String recipientUserId, String title, String body, Category category,
   String sourceTableName, String sourceRecordId, String link, String sourceId, String actorUserId)
{
   /*******************************************************************************
    ** Notification without an event id or actor.
    *******************************************************************************/
   public CollaborationNotification(String recipientUserId, String title, String body, Category category,
      String sourceTableName, String sourceRecordId, String link)
   {
      this(recipientUserId, title, body, category, sourceTableName, sourceRecordId, link, null, null);
   }



   /*******************************************************************************
    ** Copy carrying the event id and actor.
    *******************************************************************************/
   public CollaborationNotification withEvent(Object eventId, String actor)
   {
      return (new CollaborationNotification(recipientUserId, title, body, category, sourceTableName, sourceRecordId, link,
         eventId == null ? null : String.valueOf(eventId), actor));
   }



   /*******************************************************************************
    ** Categories supported by the collaboration notification boundary.
    *******************************************************************************/
   public enum Category
   {
      MENTION, SHARE, FOLLOW, TASK
   }
}
