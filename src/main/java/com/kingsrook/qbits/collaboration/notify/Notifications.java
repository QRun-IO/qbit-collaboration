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

import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.email.EmailCollaborationNotifier;
import com.kingsrook.qbits.collaboration.security.OtherUserAccess;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.utils.lambdas.UnsafeVoidVoidMethod;

/*******************************************************************************
 ** Notification delivery uses RC.5 after-commit callbacks and fresh recipient access.
 ** replace with a QAlerts.notify adapter when QRun-IO/qqq#1125 is released
 *******************************************************************************/
public final class Notifications
{
   /*******************************************************************************
    ** Static transport boundary.
    *******************************************************************************/
   private Notifications()
   {
   }

   /*******************************************************************************
    ** Capture instance/session, but never carry a completed transaction into delivery.
    *******************************************************************************/
   public static void send(CollaborationNotification notification) throws QException
   {
      send(notification, null);
   }

   /*******************************************************************************
    ** Optional receipt runs only after actual transport delivery, never on rollback.
    *******************************************************************************/
   public static void send(CollaborationNotification notification, UnsafeVoidVoidMethod<QException> receipt) throws QException
   {
      send(notification, receipt, true);
   }

   /*******************************************************************************
    ** Only for access-request notices containing no parent data or sender message.
    *******************************************************************************/
   public static void sendAccessNotice(CollaborationNotification notification) throws QException
   {
      send(notification, null, false);
   }

   /*******************************************************************************
    ** Both safe notices and parent-data delivery respect transaction completion.
    *******************************************************************************/
   private static void send(CollaborationNotification notification, UnsafeVoidVoidMethod<QException> receipt, boolean checkParent) throws QException
   {
      CapturedContext context = new CapturedContext(QContext.getQInstance(), QContext.getQSession());
      if(QContext.getQBackendTransaction() != null)
      {
         QContext.getQBackendTransaction().addAfterCommitCallback(() ->
         {
            try
            {
               QContext.withTemporaryContext(context, () -> deliver(notification, receipt, checkParent));
            }
            catch(QException e)
            {
               throw (new IllegalStateException("Collaboration notification failed after commit", e));
            }
         });
      }
      else
      {
         deliver(notification, receipt, checkParent);
      }
   }

   /*******************************************************************************
    ** Re-check immediately before exposing record data to the transport.
    *******************************************************************************/
   private static void deliver(CollaborationNotification notification, UnsafeVoidVoidMethod<QException> receipt, boolean checkParent) throws QException
   {
      if(!checkParent || new OtherUserAccess().canRead(notification.recipientUserId(), notification.sourceTableName(), notification.sourceRecordId()).orElse(false))
      {
         CollaborationNotifier notifier = notifier();
         notifier.notify(notification);
         if(receipt != null && !(notifier instanceof NoOpNotifier))
         {
            receipt.run();
         }
      }
   }

   /*******************************************************************************
    ** The configured notifier; with none configured, email through qbit-email when
    ** it is on the classpath and produced in this instance, else no transport.
    *******************************************************************************/
   private static CollaborationNotifier notifier() throws QException
   {
      QCodeReference configured = CollaborationRecords.config().getNotifier();
      if(configured != null)
      {
         return (QCodeLoader.getAdHoc(CollaborationNotifier.class, configured));
      }
      if(emailAvailable())
      {
         return (new EmailCollaborationNotifier());
      }
      return (new NoOpNotifier());
   }

   /*******************************************************************************
    ** qbit-email is optional: the adapter is touched only after the class check
    ** passes and the email qbit is produced in the current instance.
    *******************************************************************************/
   private static boolean emailAvailable()
   {
      try
      {
         Class.forName("com.kingsrook.qbits.email.outbox.EmailService");
         return (EmailCollaborationNotifier.isAvailable(QContext.getQInstance()));
      }
      catch(ClassNotFoundException | LinkageError e)
      {
         return (false);
      }
   }

   /*******************************************************************************
    ** Relative canonical frontend link with separately encoded path segments.
    *******************************************************************************/
   public static String link(String table, String id)
   {
      return (com.kingsrook.qbits.collaboration.TargetResolver.recordHref(table, id));
   }
}
