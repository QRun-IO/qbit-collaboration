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


import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.attachments.DeleteAttachmentsProcess;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.recents.TrimRecentsProcess;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
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
   public static final String VERSION = loadVersion();

   private CollaborationQBitConfig qBitConfig;
   private String namespace;



   /*******************************************************************************
    ** Read the artifact version from the Maven-filtered properties resource.
    *******************************************************************************/
   private static String loadVersion()
   {
      try(InputStream inputStream = CollaborationQBitProducer.class.getClassLoader().getResourceAsStream("qbit-collaboration.properties"))
      {
         if(inputStream == null)
         {
            throw new IllegalStateException("Missing qbit-collaboration.properties; build with Maven to generate the artifact version.");
         }
         Properties properties = new Properties();
         properties.load(inputStream);
         String version = properties.getProperty("version");
         if(version == null || version.isBlank() || version.contains("${"))
         {
            throw new IllegalStateException("Missing or unfiltered version in qbit-collaboration.properties; build with Maven resource filtering.");
         }
         return (version);
      }
      catch(IOException e)
      {
         throw new IllegalStateException("Could not read the artifact version from qbit-collaboration.properties.", e);
      }
   }



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
      var realtime = com.kingsrook.qqq.backend.core.realtime.RealtimeMetaData.of(instance);
      realtime.withAudienceProvider(com.kingsrook.qbits.collaboration.presence.RedisPresenceAudience.EVENT,
         new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(com.kingsrook.qbits.collaboration.presence.RedisPresenceAudience.class));
      instance.withSupplementalMetaData(realtime);
      QBitMetaData qBit = getQBitMetaData();
      instance.addQBit(qBit);
      MetaDataProducerMultiOutput output = new MetaDataProducerMultiOutput();
      CollaborationSecurity.buildSecurityKeyTypes().forEach(output::add);
      output.add(TrimRecentsProcess.metadata(qBitConfig));
      output.add(com.kingsrook.qbits.collaboration.customfields.BuildFieldIndexes.metadata(qBitConfig));
      com.kingsrook.qbits.collaboration.customfields.FieldProcesses.metadata().forEach(output::add);
      output.add(com.kingsrook.qbits.collaboration.tasks.SendTaskRemindersProcess.metadata(qBitConfig));
      WorkProcesses.management().forEach(output::add);
      for(String table : qBitConfig.getAssignActionTables())
      {
         WorkProcesses.assignments(table).forEach(output::add);
      }
      output.add(com.kingsrook.qbits.collaboration.follow.SendFollowDigestsProcess.metadata(qBitConfig));
      for(String table : qBitConfig.getFollowActionTables())
      {
         com.kingsrook.qbits.collaboration.follow.FollowProcesses.processesFor(table).forEach(output::add);
      }
      if(!qBitConfig.getFollowChangeTables().isEmpty())
      {
         instance.withRecordChangeListener(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(com.kingsrook.qbits.collaboration.follow.FollowChangeBatcher.class));
      }
      com.kingsrook.qbits.collaboration.tags.TagProcesses.management().forEach(output::add);
      for(String table : qBitConfig.getTagActionTables())
      {
         com.kingsrook.qbits.collaboration.tags.TagProcesses.processesFor(table).forEach(output::add);
      }
      output.add(DeleteAttachmentsProcess.metadata());
      output.add(FeatureSettingsDefaultsProcess.process());
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
         CollaborationHome.widgets().forEach(output::add);
         output.add(CollaborationHome.app(qBitConfig));
      }
      output.addSelfToInstance(instance);
      CoreCollaboration.install(instance, qBitConfig);
      instance.withRecordChangeListener(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(CollaborationRealtime.class));
      if(Boolean.TRUE.equals(qBitConfig.getUiApi()))
      {
         com.kingsrook.qbits.collaboration.api.CollaborationApi.register(instance);
      }
      com.kingsrook.qbits.collaboration.model.ConversationChildren.install(instance);
      if(Boolean.TRUE.equals(qBitConfig.getAdminApp()) && qBitConfig.getParentAppName() != null)
      {
         QAppMetaData parent = instance.getApp(qBitConfig.getParentAppName());
         parent.withChild(instance.getApp(qBitConfig.getAppName()));
      }
      // Registration is metadata-only; core startup sync skips hosts without helpContent.
      HelpBundles.register(instance, "help/qbit-collaboration.yaml");
      return (new MetaDataProducerMultiOutput());
   }
}
