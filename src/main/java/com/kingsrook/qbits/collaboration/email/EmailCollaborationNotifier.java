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


import java.util.List;
import java.util.function.Function;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotifier;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.security.OtherUserAccess;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.template.EmailTemplateDefinition;
import com.kingsrook.qbits.email.template.EmailTemplates;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Delivers mention, share and task notifications through qbit-email, one
 ** NOTIFICATION message per recipient. This is the only class that references
 ** qbit-email; Notifications reaches it only after checking that the email qbit
 ** is loadable and produced in the instance.
 **
 ** Emails never carry comment bodies, task titles or notes (they may hold PHI):
 ** they say who did what on which record, with a link. The record label is
 ** included only when the recipient may read the record and every label field is
 ** fully accessible; otherwise a generic label is used.
 *******************************************************************************/
public final class EmailCollaborationNotifier implements CollaborationNotifier
{
   private static final QLogger LOG = QLogger.getLogger(EmailCollaborationNotifier.class);

   public static final String OWNER_QBIT = "qbit-collaboration";
   public static final String MENTION_TEMPLATE = "collaboration_mention";
   public static final String SHARE_TEMPLATE = "collaboration_share";
   public static final String TASK_REMINDER_TEMPLATE = "collaboration_task_reminder";

   private static final String SAMPLE = "{\"actorName\":\"Alex Example\",\"recordLabel\":\"Order 1042\",\"link\":\"https://example.invalid/app/orders/1042\"}";
   private static final String OPEN_LINK = "{{#if link}}[Open the record]({{link}}){.button}{{/if}}";



   /*******************************************************************************
    ** True when the email qbit is produced in the given instance.
    *******************************************************************************/
   public static boolean isAvailable(QInstance qInstance)
   {
      return (EmailRuntime.config(qInstance) != null);
   }



   /*******************************************************************************
    ** Enqueue the email for a mention, share or task notification; follow digests
    ** have no email template. Failures are logged by user id and never thrown, so
    ** a mail problem cannot fail the collaboration action.
    *******************************************************************************/
   @Override
   public void notify(CollaborationNotification n)
   {
      EmailTemplateDefinition template = templateFor(n.category());
      if(template == null)
      {
         return;
      }
      try
      {
         String address = address(n.recipientUserId());
         if(!StringUtils.hasContent(address))
         {
            LOG.info("Collaboration email skipped: no address for recipient", logPair("userId", n.recipientUserId()));
            return;
         }
         EmailTemplates.ensure(template);
         String eventId = n.sourceId() != null ? n.sourceId() : n.sourceRecordId();
         EmailRequest request = new EmailRequest().withTemplateName(template.getName()).withCategory(EmailCategory.NOTIFICATION).withTo(address)
            .withIdempotencyKey("collaboration:" + kind(n.category()) + ":" + eventId + ":" + n.recipientUserId())
            .withSender(OWNER_QBIT, "notify-" + kind(n.category()))
            .withVariable("actorName", actorName(n.actorUserId())).withVariable("recordLabel", recordLabel(n));
         if(n.sourceTableName() != null && n.sourceRecordId() != null)
         {
            request.withLinkedRecord(n.sourceTableName(), n.sourceRecordId());
         }
         if(n.link() != null && n.link().startsWith("http"))
         {
            request.withVariable("link", n.link());
         }
         EmailSendResult result = EmailService.send(request);
         if(result.status() != EmailSendResult.Status.QUEUED)
         {
            LOG.info("Collaboration email not queued", logPair("userId", n.recipientUserId()), logPair("status", result.status()),
               logPair("messageId", result.messageId()));
         }
      }
      catch(QException | RuntimeException e)
      {
         LOG.warn("Collaboration email failed", e, logPair("userId", n.recipientUserId()), logPair("category", n.category()));
      }
   }



   /*******************************************************************************
    ** The recipient address from the configured resolver, else from the
    ** collaboration people directory.
    *******************************************************************************/
   private String address(String userId) throws QException
   {
      Function<String, String> resolver = CollaborationRecords.config().getRecipientEmailResolver();
      if(resolver != null)
      {
         return (resolver.apply(userId));
      }
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.USER, new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, userId));
      return (rows.isEmpty() ? null : rows.getFirst().getValueString("email"));
   }



   /*******************************************************************************
    ** The person's display name, or a generic word when the actor is unknown.
    *******************************************************************************/
   private String actorName(String actorUserId) throws QException
   {
      return (actorUserId == null ? "Someone" : new PeopleService().displayName(actorUserId));
   }



   /*******************************************************************************
    ** The record label when the recipient may see it, else a generic phrase.
    *******************************************************************************/
   private String recordLabel(CollaborationNotification n) throws QException
   {
      if(n.sourceTableName() == null)
      {
         return ("a record");
      }
      return (new OtherUserAccess().recordLabel(n.recipientUserId(), n.sourceTableName(), n.sourceRecordId())
         .orElse("a record in " + QContext.getQInstance().getTable(n.sourceTableName()).getLabel()));
   }



   /*******************************************************************************
    ** Idempotency kind for a category.
    *******************************************************************************/
   private static String kind(CollaborationNotification.Category category)
   {
      return (switch(category)
      {
         case MENTION -> "mention";
         case SHARE -> "share";
         default -> "task";
      });
   }



   /*******************************************************************************
    ** The shipped template for a category, or null when none exists.
    *******************************************************************************/
   static EmailTemplateDefinition templateFor(CollaborationNotification.Category category)
   {
      return (switch(category)
      {
         case MENTION -> mentionTemplate();
         case SHARE -> shareTemplate();
         case TASK -> taskReminderTemplate();
         default -> null;
      });
   }



   /*******************************************************************************
    ** Shared definition: variable schema and ownership. Existing templates, and
    ** people's edits, are never touched. Labels are CONFIDENTIAL, not PII or PHI:
    ** the template needs no PHI approval because labels are passed only when the
    ** record label fields are fully accessible.
    *******************************************************************************/
   private static EmailTemplateDefinition definition(String name, String label, String subject, String body)
   {
      return (new EmailTemplateDefinition().withName(name).withLabel(label).withCategory(EmailCategory.NOTIFICATION).withKind("MARKDOWN")
         .withOwnerQbit(OWNER_QBIT).withSubject(subject).withBody(body)
         .withVariable("actorName", "Person who acted", "STRING", true, "CONFIDENTIAL")
         .withVariable("recordLabel", "Record label", "STRING", true, "CONFIDENTIAL")
         .withVariable("link", "Link to the record", "URL", false, "PUBLIC")
         .withSampleVariables(SAMPLE));
   }



   /*******************************************************************************
    ** Someone mentioned the recipient.
    *******************************************************************************/
   static EmailTemplateDefinition mentionTemplate()
   {
      return (definition(MENTION_TEMPLATE, "Collaboration mention", "{{actorName}} mentioned you on {{recordLabel}}",
         "**{{actorName}}** mentioned you on {{recordLabel}}.\n\n" + OPEN_LINK));
   }



   /*******************************************************************************
    ** Someone shared a record with the recipient.
    *******************************************************************************/
   static EmailTemplateDefinition shareTemplate()
   {
      return (definition(SHARE_TEMPLATE, "Collaboration share", "{{actorName}} shared {{recordLabel}} with you",
         "**{{actorName}}** shared {{recordLabel}} with you.\n\n" + OPEN_LINK));
   }



   /*******************************************************************************
    ** A task assigned to the recipient was assigned, is due, or is overdue.
    *******************************************************************************/
   static EmailTemplateDefinition taskReminderTemplate()
   {
      return (definition(TASK_REMINDER_TEMPLATE, "Collaboration task reminder", "A task on {{recordLabel}} needs your attention",
         "A task assigned to you on {{recordLabel}} needs your attention.\n\n" + OPEN_LINK));
   }
}
