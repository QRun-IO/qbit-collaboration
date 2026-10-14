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


package com.kingsrook.qbits.collaboration;

import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.collaboration.comments.CommentsService;
import com.kingsrook.qbits.collaboration.favorites.FavoritesService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotifier;
import com.kingsrook.qbits.collaboration.notify.RecordingNotifier;
import com.kingsrook.qbits.collaboration.recents.RecentsService;
import com.kingsrook.qbits.collaboration.sharing.SharingService;
import com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Cached and delivered labels respect the session that will receive the data.
 *******************************************************************************/
class SecuredLabelsTest extends PersonalServiceFixture
{
   private static RecordingNotifier recorder;

   /*******************************************************************************
    ** Non-FULL labels are never cached; lists always re-read as the current viewer.
    *******************************************************************************/
   @Test
   void cachesOnlyFullAccessLabelsAndRefreshesForViewer() throws Exception
   {
      for(FieldSecurityLock.Behavior behavior : List.of(FieldSecurityLock.Behavior.MASK, FieldSecurityLock.Behavior.DENY, FieldSecurityLock.Behavior.REVEAL))
      {
         restrictLabel(behavior);
         FavoritesService.star("RECORD", "document", 1);
         RecentsService.recordView("RECORD", "document", 1);
         for(String table : List.of(CollaborationTables.FAVORITE, CollaborationTables.RECENT))
         {
            assertThat(PersonalRecords.query(table, PersonalRecords.ownFilter())).singleElement()
               .satisfies(row -> assertThat(row.getValueString("label")).isNull());
         }
         assertThat(FavoritesService.list()).singleElement().satisfies(entry -> assertThat(entry.label()).doesNotContain("First document"));
         assertThat(RecentsService.list(50)).singleElement().satisfies(entry -> assertThat(entry.label()).doesNotContain("First document"));
         FavoritesService.unstar("RECORD", "document", 1);
         PersonalRecords.delete(CollaborationTables.RECENT, PersonalRecords.query(CollaborationTables.RECENT, PersonalRecords.ownFilter()).stream().map(row -> row.getValue("id")).toList());
      }
      QContext.getQSession().withSecurityKeyValue("sensitive", "full");
      FavoritesService.star("RECORD", "document", 1);
      RecentsService.recordView("RECORD", "document", 1);
      for(String table : List.of(CollaborationTables.FAVORITE, CollaborationTables.RECENT))
      {
         assertThat(PersonalRecords.query(table, PersonalRecords.ownFilter())).singleElement()
            .satisfies(row -> assertThat(row.getValueString("label")).isEqualTo("First document"));
      }
      login("alice");
      assertThat(FavoritesService.list()).singleElement().satisfies(entry -> assertThat(entry.label()).doesNotContain("First document"));
      assertThat(RecentsService.list(50)).singleElement().satisfies(entry -> assertThat(entry.label()).doesNotContain("First document"));
      FavoritesService.star("TABLE", "document", null);
      assertThat(PersonalRecords.query(CollaborationTables.FAVORITE, PersonalRecords.targetFilter("TABLE", "document", null)))
         .singleElement().satisfies(row -> assertThat(row.getValueString("label")).isEqualTo("Documents"));
   }

   /*******************************************************************************
    ** The sender's FULL label cannot leak to recipients with MASKED or HIDDEN access.
    *******************************************************************************/
   @Test
   void notificationTitlesResolveLabelsPerRecipient() throws Exception
   {
      for(FieldSecurityLock.Behavior behavior : List.of(FieldSecurityLock.Behavior.MASK, FieldSecurityLock.Behavior.DENY))
      {
         restrictLabel(behavior);
         QContext.getQSession().withSecurityKeyValue("sensitive", "full");
         recorder = new RecordingNotifier();
         config.withNotifier(new QCodeReference(TestNotifier.class)).withUserSessionProvider(new QCodeReference(TestSessions.class));
         new SharingService().share("document", 1, List.of("restricted", "full"), "Shared user content");
         new CommentsService().post("document", 1, "@[Restricted](user:restricted) @[Full](user:full)", null, null);
         assertThat(recorder.notifications()).hasSize(4);
         assertThat(recorder.notifications()).filteredOn(n -> n.recipientUserId().equals("restricted")).hasSize(2)
            .allSatisfy(n -> assertThat(n.title()).contains("Documents #1").doesNotContain("First document"));
         assertThat(recorder.notifications()).filteredOn(n -> n.recipientUserId().equals("full")).hasSize(2)
            .allSatisfy(n -> assertThat(n.title()).contains("First document"));
         assertThat(QContext.getQSession().getUser().getIdReference()).isEqualTo("alice");
      }
   }

   /*******************************************************************************
    ** Field-lock override grants FULL to selected sessions only.
    *******************************************************************************/
   private void restrictLabel(FieldSecurityLock.Behavior behavior)
   {
      if(!instance.getSecurityKeyTypes().containsKey("sensitive"))
      {
         instance.addSecurityKeyType(new QSecurityKeyType().withName("sensitive"));
      }
      instance.getTable("document").getField("title").withFieldSecurityLock(new FieldSecurityLock()
         .withSecurityKeyType("sensitive").withDefaultBehavior(behavior).withOverrideValues(List.of("full")));
   }

   /*******************************************************************************
    ** Recipients share record access but have different label access.
    *******************************************************************************/
   public static class TestSessions implements UserSessionProviderInterface
   {
      /*******************************************************************************
       ** Build independent recipient sessions for real core field checks.
       *******************************************************************************/
      @Override
      public Optional<QSession> sessionForUser(String userId)
      {
         QSession session = new QSession().withUser(new QUser().withIdReference(userId)).withPermissions("document.read")
            .withSecurityKeyValue("tenant", "a");
         if(userId.equals("full"))
         {
            session.withSecurityKeyValue("sensitive", "full");
         }
         return (Optional.of(session));
      }
   }

   /*******************************************************************************
    ** Capture notifications before any external transport.
    *******************************************************************************/
   public static class TestNotifier implements CollaborationNotifier
   {
      /*******************************************************************************
       ** Preserve the payload for assertions.
       *******************************************************************************/
      @Override
      public void notify(CollaborationNotification notification)
      {
         recorder.notify(notification);
      }
   }
}
