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


package com.kingsrook.qbits.collaboration.customfields;

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qbits.collaboration.FeatureSettingsProvider;
import com.kingsrook.qqq.backend.core.actions.values.BasicCustomPossibleValueProvider;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Picker restricted to tables enabled for the custom-field designer.
 *******************************************************************************/
public class CustomFieldTablesProvider extends BasicCustomPossibleValueProvider<QTableMetaData, String>
{
   /*******************************************************************************
    ** Resolve code and persisted policy consistently with the overlay.
    *******************************************************************************/
   @Override
   protected List<QTableMetaData> getAllSourceObjects() throws QException
   {
      var instance = QContext.getQInstance();
      var overrides = new FeatureSettingsProvider().getOverrides(instance);
      return (instance.getTables().values().stream().filter(table -> !table.getName().startsWith("collaboration_") && table.getPrimaryKeyField() != null
         && FeatureSettingsProvider.enabled(instance, table, RecordFeature.CUSTOM_FIELDS, overrides)).toList());
   }

   /*******************************************************************************
    ** Reject stale selections when the feature is disabled.
    *******************************************************************************/
   @Override
   protected QTableMetaData getSourceObject(Serializable id) throws QException
   {
      return (getAllSourceObjects().stream().filter(table -> table.getName().equals(id)).findFirst().orElse(null));
   }

   /*******************************************************************************
    ** Use the metadata label rather than the SQL name.
    *******************************************************************************/
   @Override
   protected QPossibleValue<String> makePossibleValue(QTableMetaData table)
   {
      return (new QPossibleValue<>(table.getName(), table.getLabel()));
   }
}
