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
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Memory-backed personal services with a real, independently locked host table.
 *******************************************************************************/
public abstract class PersonalServiceFixture
{
   protected QInstance instance;
   protected CollaborationQBitConfig config;



   /*******************************************************************************
    ** Every test starts with two visible records and one invisible record.
    *******************************************************************************/
   @BeforeEach
   public void setUp() throws Exception
   {
      MemoryRecordStore.getInstance().reset();
      instance = new QInstance().withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.setEnforcePermissionsInCoreActions(true);
      config = new CollaborationQBitConfig().withBackendName("memory");
      new CollaborationQBitProducer().withQBitConfig(config).produce(instance);
      instance.addSecurityKeyType(new QSecurityKeyType().withName("tenant").withAllAccessKeyName("allTenants"));
      instance.addTable(new QTableMetaData().withName("document").withLabel("Documents").withBackendName("memory").withPrimaryKeyField("id")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("title", QFieldType.STRING))
         .withField(new QFieldMetaData("tenant", QFieldType.STRING))
         .withRecordLabelFormatAndFields("%s", "title")
         .withRecordSecurityLock(new RecordSecurityLock().withFieldName("tenant").withSecurityKeyType("tenant")
            .withLockScope(RecordSecurityLock.LockScope.READ_AND_WRITE)));
      login("alice");
      QContext.getQSession().withSecurityKeyValue("allTenants", true);
      InsertInput insert = new InsertInput("document").withRecords(List.of(
         new QRecord().withValue("id", 1).withValue("title", "First document").withValue("tenant", "a"),
         new QRecord().withValue("id", 2).withValue("title", "Second document").withValue("tenant", "a"),
         new QRecord().withValue("id", 3).withValue("title", "Secret document").withValue("tenant", "b")));
      assertThat(new InsertAction().execute(insert).getRecords()).allSatisfy(row -> assertThat(row.getErrors()).isNullOrEmpty());
      login("alice");
   }



   /*******************************************************************************
    ** No collaboration.view grant: personal services must still work.
    *******************************************************************************/
   protected void login(String id, String... extraPermissions)
   {
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference(id)).withPermissions("document.read", "document.write")
         .withPermissions(extraPermissions).withSecurityKeyValue("tenant", "a"));
   }



   /*******************************************************************************
    ** Admin access is independent of ordinary host-table access.
    *******************************************************************************/
   protected void admin()
   {
      login("admin", CollaborationPermissions.ADMIN);
   }



   /*******************************************************************************
    ** No static context or memory records survive a test.
    *******************************************************************************/
   @AfterEach
   public void tearDown()
   {
      QContext.clear();
      MemoryRecordStore.getInstance().reset();
   }
}
