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


package com.kingsrook.qbits.collaboration.app;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;


/*******************************************************************************
 ** The Setup card on the Collaboration home: which record features are on, for
 ** which tables, and whether that comes from Feature Settings rows or from the
 ** code defaults. Uses the same resolution the runtime uses
 ** (RecordFeaturesResolver: code declarations, then the committed rows), so the
 ** card never reports something the runtime would not do.
 *******************************************************************************/
public final class CollaborationSetupStatus
{
   private static final int NAMED_TABLES = 5;



   /*******************************************************************************
    ** One line of the card.
    *******************************************************************************/
   public record Item(String feature, String state, String where, String source, String nextStep)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationSetupStatus()
   {
   }



   /*******************************************************************************
    ** One line for Feature Settings itself, then one per record feature.
    *******************************************************************************/
   public static List<Item> load(QInstance instance) throws QException
   {
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.FEATURE_SETTING, new QQueryFilter().withLimit(1000));
      Set<String> featuresWithRows = new HashSet<>();
      rows.forEach(row -> featuresWithRows.add(row.getValueString("feature")));

      List<Item> items = new ArrayList<>();
      items.add(rows.isEmpty()
         ? new Item("Feature settings", "Using code defaults", "-", "Configuration", "Run Create rows from configuration on Feature Settings to see and edit every setting.")
         : new Item("Feature settings", "Set up", rows.size() + " row" + (rows.size() == 1 ? "" : "s"), "Feature Settings", null));

      List<QTableMetaData> hostTables = CollectionUtils.nonNullMap(instance.getTables()).values().stream()
         .filter(table -> !table.getName().startsWith("collaboration_") && !Boolean.TRUE.equals(table.getIsHidden()) && table.getPrimaryKeyField() != null)
         .sorted((a, b) -> a.getName().compareTo(b.getName())).toList();
      for(RecordFeature feature : RecordFeature.values())
      {
         List<String> on = new ArrayList<>();
         for(QTableMetaData table : hostTables)
         {
            if(RecordFeaturesResolver.resolve(instance, table).getFeatures().get(feature).getEnabled())
            {
               on.add(table.getLabel() == null ? table.getName() : table.getLabel());
            }
         }
         String source = featuresWithRows.contains(feature.name()) ? "Feature Settings" : "Configuration";
         String name = friendly(feature);
         if(on.isEmpty())
         {
            items.add(new Item(name, "Off", "No table", source, "Add a Feature Settings row (scope INSTANCE, or TABLE for one table) and turn it on."));
         }
         else
         {
            boolean all = on.size() == hostTables.size();
            String where = all ? "Every table" : String.join(", ", on.subList(0, Math.min(NAMED_TABLES, on.size()))) + (on.size() > NAMED_TABLES ? " and " + (on.size() - NAMED_TABLES) + " more" : "");
            items.add(new Item(name, all ? "On" : "On for " + on.size() + " of " + hostTables.size() + " tables", where, source, null));
         }
      }
      return (items);
   }



   /*******************************************************************************
    ** TAGS becomes Tags, CUSTOM_FIELDS becomes Custom fields.
    *******************************************************************************/
   static String friendly(RecordFeature feature)
   {
      String lower = feature.name().toLowerCase(Locale.ROOT).replace('_', ' ');
      return (Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
   }

}
