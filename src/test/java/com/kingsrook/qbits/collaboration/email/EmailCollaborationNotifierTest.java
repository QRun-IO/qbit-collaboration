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


package com.kingsrook.qbits.collaboration.email;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.NoOpNotifier;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qbits.collaboration.sharing.SharingService;
import com.kingsrook.qbits.collaboration.tasks.TasksService;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailQBitProducer;
import com.kingsrook.qbits.email.api.EmailApi;
import com.kingsrook.qbits.email.api.EmailApiCategory;
import com.kingsrook.qbits.email.api.EmailApiMessage;
import com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Collaboration notifications through a real qbit-email outbox (memory backend).
 *******************************************************************************/
class EmailCollaborationNotifierTest extends PersonalServiceFixture
{
   private static final Map<String, QSession> sessions = new HashMap<>();



   /*******************************************************************************
    ** Sessions for the recipients, so access is re-checked as them.
    *******************************************************************************/
   public static class TestSessions implements UserSessionProviderInterface
   {
      /*******************************************************************************
       ** The recipient own session.
       *******************************************************************************/
      @Override
      public Optional<QSession> sessionForUser(String user)
      {
         return (Optional.ofNullable(sessions.get(user)));
      }
   }



   /*******************************************************************************
    ** Recipients bob and eve can read the document and have addresses.
    *******************************************************************************/
   @BeforeEach
   void configure() throws Exception
   {
      sessions.clear();
      for(String user : List.of("alice", "bob", "eve"))
      {
         sessions.put(user, new QSession().withUser(new QUser().withIdReference(user)).withPermissions("document.read", "document.task").withSecurityKeyValue("tenant", "a"));
      }
      config.withUserSessionProvider(new QCodeReference(TestSessions.class));
      CollaborationRecords.insert(CollaborationTables.USER, new QRecord().withValue("userId", "bob").withValue("displayName", "Bob").withValue("email", "bob@example.org"));
      CollaborationRecords.insert(CollaborationTables.USER, new QRecord().withValue("userId", "eve").withValue("displayName", "Eve").withValue("email", "eve@example.org"));
   }



   /*******************************************************************************
    ** Host fixture wiring only: G1 scans production classes and sources. Create
    ** the provider through standard QQQ actions with the documented permissions.
    *******************************************************************************/
   private void withEmail() throws Exception
   {
      new EmailQBitProducer().withQBitConfig(new EmailQBitConfig().withBackendName("memory").withPublicBaseUrl("https://app.example.org")
         .withSecretResolver(name -> "k")).produce(instance);
      QContext.getQSession().withPermissions("email.view", "email.send", "email.manage", "email.admin", "email.hasAccess");
      QRecord provider = new QRecord().withValue("name", "relay").withValue("label", "Relay").withValue("kind", "SMTP").withValue("host", "127.0.0.1")
         .withValue("port", 2525).withValue("security", "NONE").withValue("fromAddress", "sender@example.com").withValue("enabled", true).withValue("isDefault", true);
      assertThat(new InsertAction().execute(new InsertInput("email_provider").withRecords(List.of(provider))).getRecords())
         .singleElement().satisfies(row -> assertThat(row.getErrors()).isNullOrEmpty());
   }



   /*******************************************************************************
    ** Inspect rendered test content through the standard, permission-checked
    ** table action. The public API deliberately exposes only message metadata.
    *******************************************************************************/
   private List<QRecord> messages() throws Exception
   {
      return (new QueryAction().execute(new QueryInput("email_message").withFilter(new QQueryFilter())
         .withShouldFetchHeavyFields(true).withShouldOmitHiddenFields(false)).getRecords());
   }



   /*******************************************************************************
    ** A notification of a category for a recipient.
    *******************************************************************************/
   private CollaborationNotification notification(String recipient, CollaborationNotification.Category category, String eventId)
   {
      return (new CollaborationNotification(recipient, "You were mentioned in a comment", "secret comment body", category, "document", "1",
         "https://app.example.org/app/document/1", eventId, "alice"));
   }



   /*******************************************************************************
    ** With the email qbit present and no notifier configured, a mention becomes one
    ** NOTIFICATION message with the key, link and record, and no comment body; a
    ** repeat is a duplicate.
    *******************************************************************************/
   @Test
   void mentionQueuesOneMessageAndRepeatIsDuplicate() throws Exception
   {
      withEmail();
      assertThat(config.getNotifier()).isNull();
      Notifications.send(notification("bob", CollaborationNotification.Category.MENTION, "7"));
      Notifications.send(notification("bob", CollaborationNotification.Category.MENTION, "7"));
      assertThat(messages()).singleElement().satisfies(message ->
      {
         assertThat(message.getValueString("category")).isEqualTo("NOTIFICATION");
         assertThat(message.getValueString("toAddresses")).contains("bob@example.org").doesNotContain("eve@example.org");
         assertThat(message.getValueString("idempotencyKey")).isEqualTo("collaboration:mention:7:bob");
         assertThat(message.getValueString("linkedTableName")).isEqualTo("document");
         assertThat(message.getValueString("linkedRecordId")).isEqualTo("1");
         assertThat(message.getValueString("senderQbit")).isEqualTo("qbit-collaboration");
         assertThat(message.getValueString("subject")).contains("alice mentioned you on a record in Documents");
         assertThat(message.getValueString("subject") + message.getValueString("textBody") + message.getValueString("htmlBody")).doesNotContain("secret comment body");
         assertThat(message.getValueString("htmlBody")).contains("https://app.example.org/app/document/1");
      });
      assertThat(EmailApi.getMessageByIdempotencyKey("collaboration:mention:7:bob")).hasValueSatisfying(message ->
      {
         assertThat(message.status()).isEqualTo("QUEUED");
         assertThat(message.category()).isEqualTo(EmailApiCategory.NOTIFICATION);
         assertThat(message.templateName()).isEqualTo("collaboration_mention");
         assertThat(message.linkedTableName()).isEqualTo("document");
         assertThat(message.linkedRecordId()).isEqualTo("1");
      });
   }



   /*******************************************************************************
    ** Each recipient gets their own message; the share and task kinds use their
    ** own templates and key kinds; follow digests send no email.
    *******************************************************************************/
   @Test
   void oneMessagePerRecipientAndKinds() throws Exception
   {
      withEmail();
      Notifications.send(notification("bob", CollaborationNotification.Category.SHARE, "3"));
      Notifications.send(notification("eve", CollaborationNotification.Category.SHARE, "3"));
      Notifications.send(notification("bob", CollaborationNotification.Category.TASK, "9:remindAt"));
      Notifications.send(notification("bob", CollaborationNotification.Category.FOLLOW, "1"));
      assertThat(messages()).extracting(m -> m.getValueString("idempotencyKey"))
         .containsExactlyInAnyOrder("collaboration:share:3:bob", "collaboration:share:3:eve", "collaboration:task:9:remindAt:bob");
      assertThat(messages()).allSatisfy(m -> assertThat(m.getValueString("toAddresses")).doesNotContain(","));
      assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix("collaboration:", 10)).extracting(EmailApiMessage::templateName)
         .contains("collaboration_share", "collaboration_task_reminder");
   }



   /*******************************************************************************
    ** The configured resolver replaces the directory; no address means no email.
    *******************************************************************************/
   @Test
   void resolverAndMissingAddress() throws Exception
   {
      withEmail();
      config.withRecipientEmailResolver(user -> "eve".equals(user) ? "other@example.org" : null);
      Notifications.send(notification("eve", CollaborationNotification.Category.MENTION, "1"));
      Notifications.send(notification("bob", CollaborationNotification.Category.MENTION, "2"));
      assertThat(messages()).singleElement().satisfies(m -> assertThat(m.getValueString("toAddresses")).contains("other@example.org"));
   }



   /*******************************************************************************
    ** Real shares and task assignments carry the event id and actor into the email.
    *******************************************************************************/
   @Test
   void sharesAndTasksThroughTheServices() throws Exception
   {
      withEmail();
      new SharingService().share("document", 1, List.of("bob"), "private message");
      new TasksService().create("document", 1, "Private task title", "private notes", "bob", null, null);
      assertThat(messages()).hasSize(2).allSatisfy(m ->
      {
         assertThat(m.getValueString("subject") + m.getValueString("textBody") + m.getValueString("htmlBody")).doesNotContain("private", "Private");
         assertThat(m.getValueString("idempotencyKey")).matches("collaboration:(share|task):\\d+:bob");
      });
      assertThat(messages().getFirst().getValueString("subject")).startsWith("alice shared");
   }



   /*******************************************************************************
    ** Without the email qbit in the instance nothing is sent and no receipt runs.
    *******************************************************************************/
   @Test
   void withoutEmailQbitNothingHappens() throws Exception
   {
      boolean[] receipt = {false};
      Notifications.send(notification("bob", CollaborationNotification.Category.MENTION, "7"), () -> receipt[0] = true);
      assertThat(receipt[0]).isFalse();
      assertThat(EmailCollaborationNotifier.isAvailable(instance)).isFalse();
   }



   /*******************************************************************************
    ** A configured notifier always wins over the email default.
    *******************************************************************************/
   @Test
   void configuredNotifierWins() throws Exception
   {
      withEmail();
      config.withNotifier(new QCodeReference(NoOpNotifier.class));
      Notifications.send(notification("bob", CollaborationNotification.Category.MENTION, "7"));
      assertThat(messages()).isEmpty();
   }
}
