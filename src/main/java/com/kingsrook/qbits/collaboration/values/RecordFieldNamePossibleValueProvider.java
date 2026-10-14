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
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.metadata.personalization.TableMetaDataPersonalizerAction;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.values.QCustomPossibleValueProvider;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.metadata.personalization.TableMetaDataPersonalizerInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QInputSource;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Reusable field-name source for forms carrying tableName, with comment fallback.
 *******************************************************************************/
public class RecordFieldNamePossibleValueProvider implements QCustomPossibleValueProvider<String>
{
   /*******************************************************************************
    ** A bare field name cannot be labeled without its table context.
    *******************************************************************************/
   @Override
   public QPossibleValue<String> getPossibleValue(Serializable idValue)
   {
      String name = ValueUtils.getValueAsString(idValue);
      return (name == null ? null : new QPossibleValue<>(name, name));
   }



   /*******************************************************************************
    ** Offer only non-hidden fields in the session's personalized metadata.
    *******************************************************************************/
   @Override
   public List<QPossibleValue<String>> search(SearchPossibleValueSourceInput input) throws QException
   {
      List<QPossibleValue<String>> values = new ArrayList<>();
      QTableMetaData table = resolveTable(input);
      if(table != null)
      {
         for(var field : table.getFields().values())
         {
            if(!field.getIsHidden())
            {
               String label = field.getLabel() == null ? field.getName() : field.getLabel();
               QPossibleValue<String> value = new QPossibleValue<>(field.getName(), label + " (" + field.getName() + ")");
               if(PossibleValueSearch.matches(input, value))
               {
                  values.add(value);
               }
            }
         }
      }
      return (values);
   }



   /*******************************************************************************
    ** Explicit tableName works for any form. commentId (or id on a comment form)
    ** supplies a persisted fallback through the current session's normal read path.
    *******************************************************************************/
   private QTableMetaData resolveTable(SearchPossibleValueSourceInput input) throws QException
   {
      var otherValues = input.getOtherValues();
      if(otherValues == null)
      {
         return (null);
      }
      String tableName = ValueUtils.getValueAsString(otherValues.get("tableName"));
      Serializable commentId = otherValues.getOrDefault("commentId", otherValues.get("id"));
      if((tableName == null || tableName.isBlank()) && commentId != null)
      {
         GetInput get = new GetInput(CollaborationTables.COMMENT).withPrimaryKey(commentId);
         get.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         var row = new GetAction().execute(get).getRecord();
         tableName = row == null ? null : row.getValueString("tableName");
      }
      if(tableName == null || QContext.getQInstance().getTable(tableName) == null)
      {
         return (null);
      }
      TableMetaDataPersonalizerInput personalization = new TableMetaDataPersonalizerInput().withTableName(tableName).withInputSource(QInputSource.USER);
      personalization.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (TableMetaDataPersonalizerAction.execute(personalization));
   }
}
