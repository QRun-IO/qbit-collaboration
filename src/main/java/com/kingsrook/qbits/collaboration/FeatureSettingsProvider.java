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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeatureOverrideProviderInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.FeaturePermissionMode;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeatureOverride;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Read persisted feature policy during core overlay refresh.
 *******************************************************************************/
public class FeatureSettingsProvider implements RecordFeatureOverrideProviderInterface
{
   /*******************************************************************************
    ** Malformed policy rejects the contribution, retaining the last core snapshot.
    *******************************************************************************/
   @Override
   public List<RecordFeatureOverride> getOverrides(QInstance instance) throws QException
   {
      List<RecordFeatureOverride> result = new ArrayList<>();
      for(var row : CollaborationRecords.query(CollaborationTables.FEATURE_SETTING, new QQueryFilter()))
      {
         try
         {
            var override = new RecordFeatureOverride().withScopeType(RecordFeatureOverride.ScopeType.valueOf(row.getValueString("scopeType")))
               .withScopeName(row.getValueString("scopeName")).withFeature(RecordFeature.valueOf(row.getValueString("feature")))
               .withEnabled(row.getValueBoolean("enabled"));
            if(row.getValueString("permissionMode") != null)
            {
               override.withPermissionMode(FeaturePermissionMode.valueOf(row.getValueString("permissionMode")));
            }
            if(row.getValueString("settings") != null && !row.getValueString("settings").isBlank())
            {
               override.withSettings(new ObjectMapper().readValue(row.getValueString("settings"), new TypeReference<Map<String, Serializable>>() {}));
            }
            result.add(override);
         }
         catch(Exception e)
         {
            throw (new QException("Invalid collaboration feature setting", e));
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** Resolve a feature against the incoming policy in core's instance/app/table order.
    *******************************************************************************/
   public static boolean enabled(QInstance instance, QTableMetaData table, RecordFeature feature, List<RecordFeatureOverride> overrides)
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
            if(Boolean.FALSE.equals(level.getFeatureLocks().get(feature)))
            {
               return (false);
            }
            enabled = level.getEnabled().getOrDefault(feature, enabled);
         }
      }
      for(var scope : RecordFeatureOverride.ScopeType.values())
      {
         List<String> scopes = scope == RecordFeatureOverride.ScopeType.APP ? apps : java.util.Collections.singletonList(scope == RecordFeatureOverride.ScopeType.TABLE ? table.getName() : null);
         for(String scopeName : scopes)
         {
            for(var override : overrides)
            {
               if(override.getFeature() == feature && override.getScopeType() == scope && override.getEnabled() != null
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
