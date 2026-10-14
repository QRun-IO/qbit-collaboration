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

import java.util.Map;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordReferenceSecurityHelper;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceSecurityConfig;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordReferenceTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Install the qbit implementations of core collaboration contracts.
 *******************************************************************************/
public final class CoreCollaboration
{
   private static final Map<String, RecordFeature> REFERENCES = Map.ofEntries(
      Map.entry(CollaborationTables.FIELD_VALUE, RecordFeature.CUSTOM_FIELDS),
      Map.entry(CollaborationTables.ATTACHMENT, RecordFeature.ATTACHMENTS),
      Map.entry(CollaborationTables.RECORD_TAG, RecordFeature.TAGS),
      Map.entry(CollaborationTables.COMMENT, RecordFeature.COMMENTS),
      Map.entry(CollaborationTables.COMMENT_REVISION, RecordFeature.COMMENTS),
      Map.entry(CollaborationTables.MENTION, RecordFeature.COMMENTS),
      Map.entry(CollaborationTables.FOLLOW, RecordFeature.FOLLOW),
      Map.entry(CollaborationTables.FOLLOW_DIGEST, RecordFeature.FOLLOW),
      Map.entry(CollaborationTables.ACTIVITY, RecordFeature.TIMELINE),
      Map.entry(CollaborationTables.SHARE, RecordFeature.SHARING),
      Map.entry(CollaborationTables.TASK, RecordFeature.TASKS),
      Map.entry(CollaborationTables.RECORD_ASSIGNMENT, RecordFeature.ASSIGNMENT));

   /*******************************************************************************
    ** Static installer.
    *******************************************************************************/
   private CoreCollaboration()
   {
   }

   /*******************************************************************************
    ** Preserve other providers installed by the host.
    *******************************************************************************/
   public static void install(QInstance instance, CollaborationQBitConfig config)
   {
      config.applyFeatures(instance);
      var features = RecordFeaturesMetaData.ofOrWithNew(instance);
      features.withOverrideProvider(new QCodeReference(FeatureSettingsProvider.class));
      features.getRuntimeProviders().add(new QCodeReference(com.kingsrook.qbits.collaboration.tags.TagsMetaDataProvider.class));
      features.getRuntimeProviders().add(new QCodeReference(com.kingsrook.qbits.collaboration.customfields.CustomFieldsProvider.class));
      features.getSummaryProviders().add(new QCodeReference(CollaborationSummaryProvider.class));
      var security = RecordReferenceSecurityConfig.ofOrWithNew(instance).withMirroredSecurityKeyTypes(config.getMirroredSecurityKeyTypes());
      security.getCleanupProviders().add(new QCodeReference(CollaborationCleanup.class));
      RecordReferenceSecurityHelper.register(instance);
   }

   /*******************************************************************************
    ** Children already carry denormalized references in this qbit schema.
    *******************************************************************************/
   public static void reference(QTableMetaData table, CollaborationQBitConfig config)
   {
      RecordFeature feature = REFERENCES.get(table.getName());
      if(feature == null)
      {
         return;
      }
      String author = switch(table.getName())
      {
         case CollaborationTables.ATTACHMENT -> "createdByUserId";
         case CollaborationTables.COMMENT -> "authorUserId";
         case CollaborationTables.SHARE -> "fromUserId";
         default -> null;
      };
      table.withSupplementalMetaData(new RecordReferenceTableMetaData().withTableNameField("tableName").withRecordIdField("recordId")
         .withFeature(feature).withAuthorField(author).withMirroredLocks(true));
      for(String keyType : config.getMirroredSecurityKeyTypes())
      {
         String field = RecordReferenceSecurityConfig.lockFieldName(keyType);
         table.withField(new QFieldMetaData(field, QFieldType.STRING).withLabel(keyType).withIsEditable(false));
         table.withRecordSecurityLock(new RecordSecurityLock().withFieldName(field).withSecurityKeyType(keyType)
            .withNullValueBehavior(RecordSecurityLock.NullValueBehavior.ALLOW));
      }
   }
}
