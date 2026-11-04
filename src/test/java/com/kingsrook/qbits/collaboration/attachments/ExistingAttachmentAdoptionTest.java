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


import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.objectstorage.api.ObjectStream;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Owner transaction/control tests with real H2 and fixed existing bytes. The
 ** injected lifecycle is a test policy, not a durability qualification claim.
 *******************************************************************************/
class ExistingAttachmentAdoptionTest extends PersonalServiceFixture
{
   private static final byte[] BYTES = new byte[] {0, 1, 2, 3};
   private RDBMSBackendMetaData database;
   private Policy policy;
   private AttachmentsService service;
   private int reads;

   /***************************************************************************
    ** Native metadata controls physical field mappings; no validation bypass.
    ***************************************************************************/
   @BeforeEach
   void database() throws Exception
   {
      ConnectionManager.closeConnectionProviders();
      database = new RDBMSBackendMetaData().withName("adoption_database").withVendor("h2").withUsername("sa")
         .withJdbcUrl("jdbc:h2:mem:adoption_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE");
      instance.addBackend(database);
      var table = instance.getTable(CollaborationTables.ATTACHMENT);
      table.setBackendName(database.getName());
      try(var connection = DriverManager.getConnection(database.getJdbcUrl(), "sa", ""); var statement = connection.createStatement())
      {
         List<String> columns = new ArrayList<>();
         for(var field : table.getFields().values())
         {
            String type = switch(field.getType())
            {
               case INTEGER -> "INTEGER";
               case LONG -> "BIGINT";
               case BOOLEAN -> "BOOLEAN";
               case DATE_TIME -> "TIMESTAMP";
               case STRING, TEXT -> "VARCHAR(4096)";
               default -> throw new IllegalStateException("Unexpected fixture field");
            };
            columns.add(field.getBackendName() + " " + type + (field.getName().equals("id") ? " PRIMARY KEY" : "")
               + (Boolean.TRUE.equals(field.getIsRequired()) ? " NOT NULL" : ""));
         }
         statement.execute("CREATE TABLE collaboration_attachment (" + String.join(",", columns) + ")");
         String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(BYTES));
         statement.execute("INSERT INTO collaboration_attachment (id,table_name,record_id,file_name,content_type,size_bytes,sha256,bucket_name,storage_key,created_by_user_id,parent_deleted,retained,create_date) VALUES ("
            + "9,'document','1','original.bin','application/octet-stream',4,'" + hash + "','test','original-key','alice',FALSE,FALSE,TIMESTAMP '2026-10-31 00:00:00')");
      }
      policy = new Policy();
      service = new AttachmentsService(config, new AttachmentStorage()
      {
         /*******************************************************************
          ** Fixed existing storage: any put/delete falls through to refusal.
          *******************************************************************/
         @Override
         public ObjectStream open(String bucket, String key)
         {
            assertThat(bucket).isEqualTo("test");
            assertThat(key).isEqualTo("original-key");
            reads++;
            return (new ObjectStream(new ByteArrayInputStream(BYTES), 4L, "application/octet-stream", null, java.time.Instant.now(), null));
         }
      }, policy);
   }

   /***************************************************************************
    ** Replay keeps the same row and does not repeat the retention transition.
    ***************************************************************************/
   @Test
   void sameIdentityAndBytesAreAdoptedWithoutUpload() throws Exception
   {
      assertThat(service.adoptRetainedAttachment(9).id()).isEqualTo(9);
      assertThat(service.adoptRetainedAttachment(9).id()).isEqualTo(9);
      assertThat(reads).isEqualTo(2);
      assertThat(policy.transitions).isEqualTo(1);
      assertThat(policy.registrations).isEqualTo(2);
      assertThat(retained()).isTrue();
   }

   /***************************************************************************
    ** Failure before read cannot transition retention or register anything.
    ***************************************************************************/
   @Test
   void refusedIntentDoesNotReadOrChangeTheOwner() throws Exception
   {
      policy.failIntent = true;
      assertThatThrownBy(() -> service.adoptRetainedAttachment(9)).hasMessage("intent refused");
      assertThat(reads).isZero();
      assertThat(retained()).isFalse();
      assertThat(policy.registrations).isZero();
   }

   /***************************************************************************
    ** The retention result callback is part of the owner's actual transaction.
    ***************************************************************************/
   @Test
   void outcomeFailureRollsBackRetention() throws Exception
   {
      policy.failOutcome = true;
      assertThatThrownBy(() -> service.adoptRetainedAttachment(9)).hasMessage("outcome refused");
      assertThat(reads).isEqualTo(1);
      assertThat(retained()).isFalse();
      assertThat(policy.registrations).isZero();
   }

   /***************************************************************************
    ** Later registration failure cannot pretend the earlier retention rolled back.
    ***************************************************************************/
   @Test
   void registrationFailureRemainsRetainedAndRetriesSameRow() throws Exception
   {
      policy.failRegistration = true;
      assertThatThrownBy(() -> service.adoptRetainedAttachment(9)).hasMessage("registration refused");
      assertThat(retained()).isTrue();
      policy.failRegistration = false;
      assertThat(service.adoptRetainedAttachment(9).id()).isEqualTo(9);
      assertThat(policy.transitions).isEqualTo(1);
   }

   /***************************************************************************
    ** Ordinary/default policy does not silently become an adoption authority.
    ***************************************************************************/
   @Test
   void absentPolicyAndDifferentAuthorRefuse() throws Exception
   {
      assertThatThrownBy(() -> new AttachmentsService(config, new AttachmentStorage(), new AttachmentLifecyclePolicy())
         .adoptRetainedAttachment(9)).hasMessageContaining("not configured");
      login("bob");
      assertThatThrownBy(() -> service.adoptRetainedAttachment(9)).hasMessageContaining("author");
      assertThat(reads).isZero();
   }

   /***************************************************************************
    ** Independent connection proves committed state rather than in-memory values.
    ***************************************************************************/
   private boolean retained() throws Exception
   {
      try(var connection = DriverManager.getConnection(database.getJdbcUrl(), "sa", "");
         var statement = connection.createStatement(); var result = statement.executeQuery("SELECT retained FROM collaboration_attachment WHERE id=9"))
      {
         assertThat(result.next()).isTrue();
         return (result.getBoolean(1));
      }
   }

   /***************************************************************************
    ** Explicit fixture hooks isolate owner transaction behavior from RF auditing.
    ***************************************************************************/
   private static final class Policy extends AttachmentLifecyclePolicy
   {
      private boolean failIntent;
      private boolean failOutcome;
      private boolean failRegistration;
      private int transitions;
      private int registrations;

      /***********************************************************************
       ** Fixture current-authority grant only.
       ***********************************************************************/
      @Override
      public void authorizeAdoption(QRecord attachment) {}

      /***********************************************************************
       ** Fault before the owner opens existing bytes.
       ***********************************************************************/
      @Override
      public void beforeAdoption(QRecord attachment) throws QException
      {
         if(failIntent)
         {
            throw (new QException("intent refused"));
         }
      }

      /***********************************************************************
       ** The fixture admits its real H2 transaction without claiming RF durability.
       ***********************************************************************/
      @Override
      public void validateMetadataTransaction(QRecord attachment, QBackendTransaction transaction) {}

      /***********************************************************************
       ** Failure must roll back the actual retained column.
       ***********************************************************************/
      @Override
      public void afterAdoption(QRecord attachment, QBackendTransaction transaction) throws QException
      {
         if(failOutcome)
         {
            throw (new QException("outcome refused"));
         }
         transitions++;
      }

      /***********************************************************************
       ** Failure occurs after the owner's retention commit.
       ***********************************************************************/
      @Override
      public void afterStored(QRecord attachment) throws QException
      {
         if(failRegistration)
         {
            throw (new QException("registration refused"));
         }
         registrations++;
      }
   }
}
