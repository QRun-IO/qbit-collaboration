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


package com.kingsrook.qbits.collaboration.tags;

import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.FeatureSettingsProvider;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataProviderInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeatureOverride;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RuntimeMetaDataContribution;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RuntimeTableMetaDataContribution;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Contribute the Tags filter and quick-search field without changing host hooks.
 *******************************************************************************/
public class TagsMetaDataProvider implements RuntimeMetaDataProviderInterface
{
   /*******************************************************************************
    ** Overlay providers run before the new feature snapshot is published.
    *******************************************************************************/
   @Override
   public RuntimeMetaDataContribution getContribution(QInstance instance) throws QException
   {
      var contribution = new RuntimeMetaDataContribution();
      var overrides = new FeatureSettingsProvider().getOverrides(instance);
      for(var table : instance.getTables().values())
      {
         if(!table.getName().startsWith("collaboration_") && table.getPrimaryKeyField() != null
            && (table.isCapabilityEnabled(instance.getBackend(table.getBackendName()), Capability.TABLE_GET) || table.isCapabilityEnabled(instance.getBackend(table.getBackendName()), Capability.TABLE_QUERY))
            && enabled(instance, table, overrides))
         {
            contribution.getTables().put(table.getName(), new RuntimeTableMetaDataContribution()
               .withVirtualFields(List.of(new QVirtualFieldMetaData("collaborationTags", QFieldType.STRING).withLabel("Tags")
                  .withPossibleValueSourceName(CollaborationTables.TAG).withIsQueryCriteria(true).withIsQuerySelectable(false)
                  .withCriteriaExpander(new QCodeReference(TagCriteriaExpander.class))))
               .withSearchFields(List.of("collaborationTags")));
         }
      }
      return (contribution);
   }

   /*******************************************************************************
    ** Resolve TAGS against the incoming policy in core's instance/app/table order.
    *******************************************************************************/
   private boolean enabled(QInstance instance, QTableMetaData table, List<RecordFeatureOverride> overrides)
   {
      List<RecordFeaturesMetaData> levels = new ArrayList<>();
      List<String> apps = new ArrayList<>();
      levels.add(RecordFeaturesMetaData.of(instance));
      if(instance.getApps() != null)
      {
         for(var app : instance.getApps().values())
         {
            if(app.getSections() != null && app.getSections().stream().anyMatch(section -> section.getTables() != null && section.getTables().contains(table.getName())))
            {
               levels.add(RecordFeaturesMetaData.of(app));
               apps.add(app.getName());
            }
         }
      }
      levels.add(RecordFeaturesMetaData.of(table));
      boolean enabled = false;
      for(var level : levels)
      {
         if(level != null)
         {
            if(Boolean.FALSE.equals(level.getFeatureLocks().get(RecordFeature.TAGS)))
            {
               return (false);
            }
            enabled = level.getEnabled().getOrDefault(RecordFeature.TAGS, enabled);
         }
      }
      for(var scope : RecordFeatureOverride.ScopeType.values())
      {
         List<String> scopes = scope == RecordFeatureOverride.ScopeType.APP ? apps : java.util.Collections.singletonList(scope == RecordFeatureOverride.ScopeType.TABLE ? table.getName() : null);
         for(String scopeName : scopes)
         {
            for(var override : overrides)
            {
               if(override.getFeature() == RecordFeature.TAGS && override.getScopeType() == scope && override.getEnabled() != null
                  && (scope == RecordFeatureOverride.ScopeType.INSTANCE || (scope == RecordFeatureOverride.ScopeType.APP && scopeName.equals(override.getScopeName()))
                  || (scope == RecordFeatureOverride.ScopeType.TABLE && table.getName().equals(override.getScopeName()))))
               {
                  enabled = override.getEnabled();
               }
            }
         }
      }
      return (enabled);
   }
}
