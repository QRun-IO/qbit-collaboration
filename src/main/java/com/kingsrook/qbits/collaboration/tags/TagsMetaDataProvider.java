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

import java.util.List;
import com.kingsrook.qbits.collaboration.FeatureSettingsProvider;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataProviderInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RuntimeMetaDataContribution;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RuntimeTableMetaDataContribution;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;

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
            && FeatureSettingsProvider.enabled(instance, table, RecordFeature.TAGS, overrides))
         {
            contribution.getTables().put(table.getName(), new RuntimeTableMetaDataContribution()
               .withVirtualFields(List.of(new QVirtualFieldMetaData("collaborationTags", QFieldType.STRING).withLabel("Tags")
                  .withPossibleValueSourceName(CollaborationTables.TAG).withIsQueryCriteria(true).withIsQuerySelectable(true)
                  .withValueProvider(new QCodeReference(TagValueProvider.class)).withShouldTranslatePossibleValues(false)
                  .withCriteriaExpander(new QCodeReference(TagCriteriaExpander.class))))
               .withSearchFields(List.of("collaborationTags")));
         }
      }
      return (contribution);
   }

}
