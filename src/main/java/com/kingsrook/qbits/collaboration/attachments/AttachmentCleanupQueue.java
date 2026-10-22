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

package com.kingsrook.qbits.collaboration.attachments;


import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** Durable deletion intent, committed with metadata removal before deleting bytes.
 *******************************************************************************/
public class AttachmentCleanupQueue implements CustomPermissionChecker
{
   private final AttachmentStorage storage;


   /*******************************************************************************
    ** Metadata permission checks do not create a storage client.
    *******************************************************************************/
   public AttachmentCleanupQueue()
   {
      this(new AttachmentStorage());
   }


   /*******************************************************************************
    ** Use the same provider boundary as the authorized attachment operation.
    *******************************************************************************/
   AttachmentCleanupQueue(AttachmentStorage storage)
   {
      this.storage = storage;
   }


   /*******************************************************************************
    ** Only admins may inspect recovery locations; no direct mutations are allowed.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(!(input instanceof QueryInput) && !(input instanceof com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput)
         && !(input instanceof com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput))
      {
         throw (new QPermissionDeniedException("Attachment cleanup records are service-managed."));
      }
      requireOperator();
   }


   /*******************************************************************************
    ** The caller has already authorized the parent and attachment owner.
    *******************************************************************************/
   void delete(QRecord row, boolean cleanup) throws QException
   {
      QBackendTransaction outer = QContext.getQBackendTransaction();
      if(outer != null)
      {
         QRecord pending = enqueueAndDelete(row, cleanup);
         CapturedContext context = new CapturedContext(QContext.getQInstance(), QContext.getQSession());
         outer.addAfterCommitCallback(() ->
         {
            try
            {
               QContext.withTemporaryContext(context, () -> finish(pending));
            }
            catch(QException e)
            {
               throw (new IllegalStateException("Attachment cleanup remains durably queued.", e));
            }
         });
         return;
      }
      QRecord pending;
      try(QBackendTransaction transaction = QBackendTransaction.openFor(new DeleteInput(CollaborationTables.ATTACHMENT)))
      {
         QContext.setTransaction(transaction);
         try
         {
            pending = enqueueAndDelete(row, cleanup);
            transaction.commit();
         }
         catch(QException | RuntimeException failure)
         {
            transaction.rollback();
            throw (failure);
         }
         finally
         {
            QContext.clearTransaction();
         }
      }
      finish(pending);
   }


   /*******************************************************************************
    ** Store the recovery locator in the same database transaction as row deletion.
    *******************************************************************************/
   private QRecord enqueueAndDelete(QRecord row, boolean cleanup) throws QException
   {
      QRecord intent = new QRecord().withValue("attachmentId", row.getValue("id"))
         .withValue("bucketName", row.getValue("bucketName")).withValue("storageKey", row.getValue("storageKey"));
      InsertInput insert = new InsertInput(CollaborationTables.ATTACHMENT_CLEANUP).withRecords(List.of(intent))
         .withTransaction(QContext.getQBackendTransaction());
      insert.withPermissionBypass("Authorized attachment deletion records durable storage cleanup.");
      List<QRecord> inserted = new InsertAction().execute(insert).getRecords();
      AttachmentRecords.checkErrors(inserted);
      DeleteInput delete = new DeleteInput(CollaborationTables.ATTACHMENT).withPrimaryKeys(List.of(row.getValue("id")))
         .withTransaction(QContext.getQBackendTransaction());
      delete.setTableMetaData(cleanup ? CollaborationRecords.cleanupMetadata(CollaborationTables.ATTACHMENT) : AttachmentRecords.storageTable());
      delete.withPermissionBypass("Attachment deletion was authorized before recording cleanup.");
      AttachmentRecords.checkErrors(new DeleteAction().execute(delete).getRecordsWithErrors());
      return (inserted.getFirst());
   }


   /*******************************************************************************
    ** Never erase bytes while metadata still exists, including nontransactional hosts.
    ** Provider or queue deletion failure retains the intent for idempotent replay.
    *******************************************************************************/
   private boolean finish(QRecord pending) throws QException
   {
      if(!AttachmentRecords.query(new QQueryFilter()
         .withCriteria("bucketName", QCriteriaOperator.EQUALS, pending.getValue("bucketName"))
         .withCriteria("storageKey", QCriteriaOperator.EQUALS, pending.getValue("storageKey"))).isEmpty())
      {
         return (false);
      }
      storage.delete(pending.getValueString("bucketName"), pending.getValueString("storageKey"));
      DeleteInput delete = new DeleteInput(CollaborationTables.ATTACHMENT_CLEANUP).withPrimaryKeys(List.of(pending.getValue("id")));
      delete.withPermissionBypass("Storage cleanup completed; remove its durable intent.");
      AttachmentRecords.checkErrors(new DeleteAction().execute(delete).getRecordsWithErrors());
      return (true);
   }


   /*******************************************************************************
    ** Rollback compensation must survive a provider outage. Persist independently
    ** of the failed transaction, then attempt cleanup with the original context.
    *******************************************************************************/
   void compensateUpload(String bucket, String key)
   {
      CapturedContext context = new CapturedContext(QContext.getQInstance(), QContext.getQSession());
      try
      {
         QContext.withTemporaryContext(context, () ->
         {
            InsertInput insert = new InsertInput(CollaborationTables.ATTACHMENT_CLEANUP).withRecords(List.of(
               new QRecord().withValue("bucketName", bucket).withValue("storageKey", key)));
            insert.withPermissionBypass("Failed authorized upload retains its immutable storage locator for compensation.");
            List<QRecord> inserted = new InsertAction().execute(insert).getRecords();
            AttachmentRecords.checkErrors(inserted);
            finish(inserted.getFirst());
         });
      }
      catch(QException e)
      {
         throw (new IllegalStateException("Upload compensation could not complete.", e));
      }
   }


   /*******************************************************************************
    ** Bounded recovery from committed intents, independent of the original service.
    *******************************************************************************/
   public int retryPending() throws QException
   {
      requireOperator();
      if(QContext.getQBackendTransaction() != null)
      {
         throw (new QException("Attachment cleanup must run outside a caller transaction."));
      }
      QueryInput query = new QueryInput(CollaborationTables.ATTACHMENT_CLEANUP).withFilter(new QQueryFilter()
         .withBooleanOperator(QQueryFilter.BooleanOperator.OR)
         .withCriteria("nextAttemptAt", QCriteriaOperator.IS_BLANK)
         .withCriteria("nextAttemptAt", QCriteriaOperator.LESS_THAN_OR_EQUALS, Instant.now())
         .withOrderBy(new QFilterOrderBy("id")).withLimit(100));
      query.withPermissionBypass("Authorized operator retries committed storage cleanup.");
      List<QRecord> pending = new QueryAction().execute(query).getRecords();
      int completed = 0;
      QException failure = null;
      for(QRecord row : pending)
      {
         try
         {
            if(finish(row))
            {
               completed++;
            }
            else
            {
               defer(row);
            }
         }
         catch(QException | RuntimeException e)
         {
            try
            {
               defer(row);
            }
            catch(QException | RuntimeException deferFailure)
            {
               e.addSuppressed(deferFailure);
            }
            if(failure == null)
            {
               failure = new QException("Attachment cleanup remains pending; retry after the provider or database recovers.", e);
            }
         }
      }
      if(failure != null)
      {
         throw (failure);
      }
      return (completed);
   }


   /*******************************************************************************
    ** Persist backoff so failing entries do not monopolize the next bounded batch.
    *******************************************************************************/
   private void defer(QRecord pending) throws QException
   {
      UpdateInput update = new UpdateInput(CollaborationTables.ATTACHMENT_CLEANUP).withRecords(List.of(
         new QRecord().withValue("id", pending.getValue("id")).withValue("nextAttemptAt", Instant.now().plusSeconds(60))));
      update.withPermissionBypass("Committed cleanup failed or is not yet safe; retain it for a later attempt.");
      AttachmentRecords.checkErrors(new UpdateAction().execute(update).getRecords());
   }


   /*******************************************************************************
    ** Scheduled system work and explicit admin recovery share one authorization rule.
    *******************************************************************************/
   static void requireOperator() throws QPermissionDeniedException
   {
      var session = QContext.getQSession();
      if(!(session instanceof QSystemUserSession) && (session == null || !session.hasPermission(CollaborationPermissions.ADMIN)))
      {
         throw (new QPermissionDeniedException("This needs the collaboration.admin permission."));
      }
   }
}
