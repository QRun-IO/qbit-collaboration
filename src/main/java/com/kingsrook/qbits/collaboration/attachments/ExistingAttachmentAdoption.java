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


import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.objectstorage.api.ObjectStream;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSTransaction;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;


/*******************************************************************************
 ** Owner-only adoption of existing bytes. A durable intent precedes verification;
 ** retention and its audit commit together, then idempotent registration follows.
 *******************************************************************************/
final class ExistingAttachmentAdoption
{
   private static final long MAX_BYTES = 20L * 1024 * 1024;
   private final AttachmentsService service;
   private final AttachmentStorage storage;
   private final AttachmentLifecyclePolicy lifecycle;

   /***************************************************************************
    ** Dependencies are the actual configured owner boundaries.
    ***************************************************************************/
   ExistingAttachmentAdoption(AttachmentsService service, AttachmentStorage storage, AttachmentLifecyclePolicy lifecycle)
   {
      this.service = service;
      this.storage = storage;
      this.lifecycle = lifecycle;
   }

   /***************************************************************************
    ** No external transaction, caller locator, bytes, receipt or force option.
    ***************************************************************************/
   void adopt(Integer id) throws QException
   {
      var instance = QContext.getQInstance();
      var session = QContext.getQSession();
      String actor = CollaborationSecurity.userId(session);
      Check check = () ->
      {
         if(QContext.getQBackendTransaction() != null || QContext.getQInstance() != instance || QContext.getQSession() != session
            || actor == null || !actor.equals(CollaborationSecurity.userId(session)))
         {
            throw (new QException("Adoption requires the unchanged initiating context and its own transaction."));
         }
      };
      check.run();
      QRecord original = service.requireMutable(id);
      if(original.getValueString("intakeKey") != null)
      {
         throw (new QException("Coordinated intake must recover through its original request."));
      }
      requireIntegrity(original);
      lifecycle.authorizeAdoption(new QRecord(original));
      check.run();
      lifecycle.beforeAdoption(new QRecord(original));
      check.run();
      same(original, service.requireMutable(id));
      QRecord retained = new QRecord(original).withValue("retained", true);
      UpdateInput input = new UpdateInput(CollaborationTables.ATTACHMENT)
         .withRecords(List.of(new QRecord().withValue("id", id).withValue("retained", true)));
      input.setTableMetaData(AttachmentRecords.storageTable());
      input.withPermissionBypass("Attachment owner rechecked current parent, author/admin and audited retention policy.");
      try(QBackendTransaction transaction = QBackendTransaction.openFor(input))
      {
         try
         {
            lifecycle.validateMetadataTransaction(retained, transaction);
            check.run();
            lock(transaction, id);
            same(original, read(transaction, id));
            verify(original);
            check.run();
            same(original, service.requireMutable(id));
            lifecycle.authorizeAdoption(new QRecord(original));
            check.run();
            if(!Boolean.TRUE.equals(original.getValueBoolean("retained")))
            {
               input.setTransaction(transaction);
               AttachmentRecords.checkErrors(new UpdateAction().execute(input).getRecords());
               lifecycle.afterAdoption(retained, transaction);
            }
            check.run();
            service.requireMutable(id);
            lifecycle.authorizeAdoption(new QRecord(retained));
            check.run();
            transaction.commit();
         }
         catch(QException | RuntimeException | Error failure)
         {
            try
            {
               transaction.rollback();
            }
            catch(QException | RuntimeException cleanup)
            {
               if(cleanup != failure)
               {
                  failure.addSuppressed(cleanup);
               }
            }
            throw failure;
         }
      }
      check.run();
      same(retained, service.requireMutable(id));
      lifecycle.authorizeAdoption(new QRecord(retained));
      check.run();
      lifecycle.afterStored(new QRecord(retained));
      check.run();
      same(retained, service.requireMutable(id));
      lifecycle.authorizeAdoption(new QRecord(retained));
      check.run();
   }

   /***************************************************************************
    ** Native metadata supplies identifiers; no caller text becomes SQL.
    ***************************************************************************/
   private void lock(QBackendTransaction transaction, Integer id) throws QException
   {
      if(!(transaction instanceof RDBMSTransaction jdbc))
      {
         throw (new QException("Existing-original adoption requires a real relational transaction."));
      }
      var table = AttachmentRecords.storageTable();
      String tableName = table.getBackendDetails() instanceof RDBMSTableBackendDetails details ? details.getTableName() : table.getName();
      String key = table.getField(table.getPrimaryKeyField()).getBackendName();
      key = key == null ? table.getPrimaryKeyField() : key;
      if(tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*") || !key.matches("[A-Za-z_][A-Za-z0-9_]*"))
      {
         throw (new QException("Unsupported attachment storage mapping."));
      }
      try
      {
         String quote = jdbc.getConnection().getMetaData().getIdentifierQuoteString().trim();
         if(!List.of("\"", "`").contains(quote))
         {
            throw (new QException("Unsupported attachment identifier quoting."));
         }
         try(var statement = jdbc.getConnection().prepareStatement("SELECT " + quote + key + quote + " FROM " + quote + tableName + quote
            + " WHERE " + quote + key + quote + " = ? FOR UPDATE"))
         {
            statement.setQueryTimeout(5);
            statement.setObject(1, id);
            try(var result = statement.executeQuery())
            {
               if(!result.next())
               {
                  throw (new QNotFoundException("Not found"));
               }
            }
         }
      }
      catch(java.sql.SQLException failure)
      {
         throw (new QException("Could not lock the original attachment.", failure));
      }
   }

   /***************************************************************************
    ** Read the locked original in the same transaction without URL customizers.
    ***************************************************************************/
   private QRecord read(QBackendTransaction transaction, Integer id) throws QException
   {
      QueryInput input = new QueryInput(CollaborationTables.ATTACHMENT).withTransaction(transaction).withShouldFetchHeavyFields(true)
         .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id).withLimit(1));
      input.setTableMetaData(AttachmentRecords.storageTable());
      input.withPermissionBypass("Owner adoption already authorized the exact parent and attachment.");
      var rows = new QueryAction().execute(input).getRecords();
      if(rows.size() != 1)
      {
         throw (new QNotFoundException("Not found"));
      }
      return (rows.getFirst());
   }

   /***************************************************************************
    ** Compare persisted metadata, allowing only the native modification timestamp.
    ** The caller supplies the exact expected retention state at each boundary.
    ***************************************************************************/
   private void same(QRecord expected, QRecord actual) throws QException
   {
      for(var field : AttachmentRecords.storageTable().getFields().keySet())
      {
         if(!"modifyDate".equals(field) && !Objects.equals(expected.getValue(field), actual.getValue(field)))
         {
            throw (new QException("Attachment metadata changed during adoption."));
         }
      }
   }

   /***************************************************************************
    ** Reject ambiguous or oversized originals before opening storage.
    ***************************************************************************/
   private void requireIntegrity(QRecord row) throws QException
   {
      Long size = row.getValueLong("sizeBytes");
      if(size == null || size < 0 || size > MAX_BYTES || row.getValueString("sha256") == null
         || !row.getValueString("sha256").matches("[0-9a-f]{64}"))
      {
         throw (new QException("Original integrity metadata is missing or outside the adoption bound."));
      }
   }

   /***************************************************************************
    ** Stream and hash the existing original without releasing or writing bytes.
    ***************************************************************************/
   private void verify(QRecord row) throws QException
   {
      try(ObjectStream object = storage.open(row.getValueString("bucketName"), row.getValueString("storageKey")))
      {
         long expected = row.getValueLong("sizeBytes");
         if(object.getContentLength() != null && object.getContentLength() != expected)
         {
            throw (new AttachmentContentIntegrityException());
         }
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] buffer = new byte[8192];
         long total = 0;
         int count;
         while((count = object.getInputStream().read(buffer, 0, (int) Math.min(buffer.length, expected - total + 1))) != -1)
         {
            total += count;
            if(total > expected)
            {
               throw (new AttachmentContentIntegrityException());
            }
            digest.update(buffer, 0, count);
         }
         if(total != expected || !row.getValueString("sha256").equals(HexFormat.of().formatHex(digest.digest())))
         {
            throw (new AttachmentContentIntegrityException());
         }
      }
      catch(IOException | NoSuchAlgorithmException | RuntimeException failure)
      {
         throw (new QException("Could not verify the existing original.", failure));
      }
   }

   /***************************************************************************
    ** Checked context guard without installing a different session or authority.
    ***************************************************************************/
   @FunctionalInterface
   private interface Check
   {
      /***********************************************************************
       ** Recheck the initiating context after external callbacks.
       ***********************************************************************/
      void run() throws QException;
   }
}
