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


import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.attachments.DeleteAttachmentsProcess;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.recents.TrimRecentsProcess;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Registers the Collaboration tables, possible values and optional app home.
 *******************************************************************************/
public class CollaborationQBitProducer implements QBitMetaDataProducer<CollaborationQBitConfig>
{
   public static final String GROUP_ID = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-collaboration";
   public static final String VERSION = "0.1.0";

   private CollaborationQBitConfig qBitConfig;
   private String namespace;



   /*******************************************************************************
    ** Configure the qbit.
    *******************************************************************************/
   public CollaborationQBitProducer withQBitConfig(CollaborationQBitConfig config)
   {
      qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Get the configuration.
    *******************************************************************************/
   @Override
   public CollaborationQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** Set the namespace.
    *******************************************************************************/
   public CollaborationQBitProducer withNamespace(String namespace)
   {
      this.namespace = namespace;
      return (this);
   }



   /*******************************************************************************
    ** Get the namespace.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (namespace);
   }



   /*******************************************************************************
    ** Describe this qbit.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData().withGroupId(GROUP_ID).withArtifactId(ARTIFACT_ID).withVersion(VERSION).withNamespace(namespace).withConfig(qBitConfig));
   }



   /*******************************************************************************
    ** Package-scanned producers remain disabled until configured.
    *******************************************************************************/
   @Override
   public boolean isEnabled()
   {
      return (qBitConfig != null);
   }



   /*******************************************************************************
    ** Validate and register metadata, following the reference qbit convention.
    *******************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance instance) throws QException
   {
      qBitConfig.validate(instance);
      QBitMetaData qBit = getQBitMetaData();
      instance.addQBit(qBit);
      MetaDataProducerMultiOutput output = new MetaDataProducerMultiOutput();
      CollaborationSecurity.buildSecurityKeyTypes().forEach(output::add);
      output.add(TrimRecentsProcess.metadata(qBitConfig));
      output.add(DeleteAttachmentsProcess.metadata());
      for(QPossibleValueSource source : CollaborationTables.possibleValueSources())
      {
         output.add(source);
      }
      for(QTableMetaData table : CollaborationTables.tables(qBitConfig))
      {
         table.setSourceQBitName(qBit.getName());
         output.add(table);
      }
      if(Boolean.TRUE.equals(qBitConfig.getAdminApp()))
      {
         output.add(CollaborationHome.widget());
         output.add(CollaborationHome.app(qBitConfig));
      }
      output.addSelfToInstance(instance);
      if(Boolean.TRUE.equals(qBitConfig.getAdminApp()) && qBitConfig.getParentAppName() != null)
      {
         QAppMetaData parent = instance.getApp(qBitConfig.getParentAppName());
         parent.withChild(instance.getApp(qBitConfig.getAppName()));
      }
      return (new MetaDataProducerMultiOutput());
   }
}
