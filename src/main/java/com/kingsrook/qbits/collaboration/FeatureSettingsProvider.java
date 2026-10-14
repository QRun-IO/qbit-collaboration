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
}
