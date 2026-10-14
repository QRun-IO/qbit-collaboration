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


package com.kingsrook.qbits.collaboration.values;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.values.QCustomPossibleValueProvider;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** App and eligible table names for scoped feature settings.
 *******************************************************************************/
public class FeatureScopeNamePossibleValueProvider implements QCustomPossibleValueProvider<String>
{
   /*******************************************************************************
    ** Without scope context, prefer an app label, then a table label.
    *******************************************************************************/
   @Override
   public QPossibleValue<String> getPossibleValue(Serializable idValue)
   {
      String name = ValueUtils.getValueAsString(idValue);
      if(name == null)
      {
         return (null);
      }
      var instance = QContext.getQInstance();
      var app = instance.getApp(name);
      var table = instance.getTable(name);
      String label = app != null ? app.getLabel() : table != null ? table.getLabel() : name;
      return (new QPossibleValue<>(name, label == null ? name : label));
   }



   /*******************************************************************************
    ** INSTANCE ignores scopeName when reading overrides; use a canonical required value.
    *******************************************************************************/
   @Override
   public List<QPossibleValue<String>> search(SearchPossibleValueSourceInput input)
   {
      List<QPossibleValue<String>> values = new ArrayList<>();
      String scope = input.getOtherValues() == null ? null : ValueUtils.getValueAsString(input.getOtherValues().get("scopeType"));
      var instance = QContext.getQInstance();
      if("APP".equals(scope))
      {
         instance.getApps().values().forEach(app -> values.add(new QPossibleValue<>(app.getName(), app.getLabel() == null ? app.getName() : app.getLabel())));
      }
      else if("TABLE".equals(scope))
      {
         instance.getTables().values().stream()
            .filter(table -> !table.getName().startsWith("collaboration_") && !table.getIsHidden()
               && table.getPrimaryKeyField() != null && table.getFields().containsKey(table.getPrimaryKeyField()))
            .forEach(table -> values.add(new QPossibleValue<>(table.getName(), table.getLabel() == null ? table.getName() : table.getLabel())));
      }
      else if("INSTANCE".equals(scope))
      {
         values.add(new QPossibleValue<>("instance", "instance"));
      }
      return (values.stream().filter(value -> PossibleValueSearch.matches(input, value)).toList());
   }
}
