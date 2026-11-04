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


import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.MemoryUploadJournal;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.interfaces.QueryInterface;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.module.rdbms.RDBMSBackendModule;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSQueryAction;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Real native H2 attachment queries; host permissions remain the fixture's
 ** actual memory-backed parent policy. No content is stored or signed here.
 *******************************************************************************/
class AttachmentPaginationJdbcTest extends PersonalServiceFixture
{
   private AttachmentsService service;

   /*******************************************************************************
    ** Retain the complete owner table shape, including required fields. Observe
    ** the bound at the native backend and execute the real RDBMS query unchanged.
    *******************************************************************************/
   @BeforeEach
   void database() throws Exception
   {
      ConnectionManager.closeConnectionProviders();
      var backend = new RDBMSBackendMetaData().withName("page_database").withVendor("h2").withUsername("sa")
         .withJdbcUrl("jdbc:h2:mem:attachment_pages_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE");
      QBackendModuleDispatcher.registerBackendModule(new RecordingBackend());
      backend.setBackendType(RecordingBackend.class);
      instance.addBackend(backend);
      var table = instance.getTable(CollaborationTables.ATTACHMENT);
      table.setBackendName(backend.getName());
      try(var connection = DriverManager.getConnection(backend.getJdbcUrl(), "sa", ""); var statement = connection.createStatement())
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
               default -> throw new IllegalStateException("Unhandled fixture field " + field.getName());
            };
            columns.add(field.getBackendName() + " " + type + (field.getName().equals("id") ? " PRIMARY KEY" : "")
               + (Boolean.TRUE.equals(field.getIsRequired()) ? " NOT NULL" : ""));
         }
         statement.execute("CREATE TABLE collaboration_attachment (" + String.join(",", columns) + ")");
         for(int id = 1; id <= 8; id++)
         {
            statement.execute("INSERT INTO collaboration_attachment (id,table_name,record_id,file_name,content_type,size_bytes,sha256,bucket_name,storage_key,created_by_user_id,parent_deleted,retained,create_date) VALUES ("
               + id + ",'document','" + (id == 8 ? "2" : "1") + "','file-" + id + "','text/plain',1,'" + "a".repeat(64)
               + "','test','key-" + id + "','alice'," + (id == 7) + ",TRUE,TIMESTAMP '2026-10-31 00:00:00')");
         }
      }
      service = new AttachmentsService(config, new AttachmentStorage(), new AttachmentLifecyclePolicy(), new MemoryUploadJournal());
      RecordingBackend.bounds.clear();
   }

   /*******************************************************************************
    ** Close only fixture native providers after the query assertions.
    *******************************************************************************/
   @AfterEach
   void closeDatabase()
   {
      ConnectionManager.closeConnectionProviders();
   }

   /*******************************************************************************
    ** SQL paging preserves deterministic ties, exact source scope and retained
    ** metadata while returning only the requested page plus its lookahead.
    *******************************************************************************/
   @Test
   void boundedSqlPagesKeepTiesSourceIdentityAndRetainedMetadata() throws Exception
   {
      var first = service.listPage("document", "01", 0, 2);
      assertThat(first.items()).extracting(AttachmentEntry::id).containsExactly(6, 5);
      assertThat(first.hasMore()).isTrue();
      assertThat(first.items()).allSatisfy(item -> assertThat(item.canDelete()).isFalse());
      var second = service.listPage("document", 1, 2, 2);
      assertThat(second.items()).extracting(AttachmentEntry::id).containsExactly(4, 3);
      var last = service.listPage("document", 1, 4, 2);
      assertThat(last.items()).extracting(AttachmentEntry::id).containsExactly(2, 1);
      assertThat(last.hasMore()).isFalse();
      assertThat(service.listPage("document", 1, 6, 2).items()).isEmpty();
      assertThat(RecordingBackend.bounds).containsExactly("0:3:3", "2:3:3", "4:3:2", "6:3:0");
      assertThat(service.listPage("document", 2, 0, 100).items()).extracting(AttachmentEntry::id).containsExactly(8);
      assertThat(RecordingBackend.bounds.getLast()).isEqualTo("0:101:1");
   }

   /*******************************************************************************
    ** Current parent revocation and invalid bounds fail before attachment SQL.
    *******************************************************************************/
   @Test
   void revocationAndInvalidBoundsNeverReachAttachmentQuery() throws Exception
   {
      service.listPage("document", 1, 0, 1);
      RecordingBackend.bounds.clear();
      QContext.getQSession().getSecurityKeyValues().put("tenant", List.of("b"));
      assertThatThrownBy(() -> service.listPage("document", 1, 1, 1)).hasMessage("Not found");
      assertThatThrownBy(() -> service.listPage("document", 1, -1, 1)).hasMessageContaining("Offset");
      assertThatThrownBy(() -> service.listPage("document", 1, 0, 101)).hasMessageContaining("limit");
      assertThat(RecordingBackend.bounds).isEmpty();
   }

   /*******************************************************************************
    ** Instrument only attachment query inputs/results; delegate actual SQL to QQQ.
    *******************************************************************************/
   public static class RecordingBackend extends RDBMSBackendModule
   {
      static final List<String> bounds = new ArrayList<>();

      /*******************************************************************************
       ** Isolated dispatcher name; never replace the production RDBMS module.
       *******************************************************************************/
      @Override
      public String getBackendType()
      {
         return ("attachment_pagination_test");
      }

      /*******************************************************************************
       ** Real RDBMS execution must return no more than the requested lookahead.
       *******************************************************************************/
      @Override
      public QueryInterface getQueryInterface()
      {
         return (input ->
         {
            var output = new RDBMSQueryAction().execute(input);
            bounds.add(input.getFilter().getSkip() + ":" + input.getFilter().getLimit() + ":" + output.getRecords().size());
            return (output);
         });
      }
   }
}
