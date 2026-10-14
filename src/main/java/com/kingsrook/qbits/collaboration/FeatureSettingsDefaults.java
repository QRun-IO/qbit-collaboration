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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.FeaturePermissionMode;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;


/*******************************************************************************
 ** The collaboration_feature_setting rows that say, in the database, exactly
 ** what the code configuration already says: one INSTANCE row per feature
 ** (enabled for the configured default features, disabled for the rest, with
 ** the default permission mode and the TAGS mode), plus an APP or TABLE row
 ** wherever the code declares something at that scope.
 **
 ** Why rows computed at run time and not a SQL seed: the right rows depend on
 ** the host's configuration (withDefaultFeatures, withTableFeatures, the
 ** default tag mode, features declared on apps and tables), which SQL cannot
 ** see.
 **
 ** Why every declaring scope gets its own row: runtime overrides are applied
 ** AFTER the code declarations, instance first, then app, then table. A bare
 ** INSTANCE row would therefore overwrite what a table declares in code.
 ** Restating each declaring scope in its own row keeps the resolved result of
 ** every table identical with and without the rows (FeatureSettingsDefaultsTest
 ** proves that for several configurations).
 **
 ** Rows are only ever created when the table is empty, so they can never
 ** disagree with rows an administrator already wrote.
 *******************************************************************************/
public final class FeatureSettingsDefaults
{
   public static final String INSTANCE_SCOPE_NAME = "instance";

   private static final ObjectMapper MAPPER = new ObjectMapper();



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private FeatureSettingsDefaults()
   {
   }



   /*******************************************************************************
    ** The rows to create: the configured rows when the table is empty, none
    ** when an administrator already has rows.
    *******************************************************************************/
   public static List<QRecord> missingRows(QInstance instance, CollaborationQBitConfig config) throws QException
   {
      if(!CollaborationRecords.query(CollaborationTables.FEATURE_SETTING, new QQueryFilter().withLimit(1)).isEmpty())
      {
         return (List.of());
      }
      return (configuredRows(instance, config));
   }



   /*******************************************************************************
    ** Every row the configuration implies (whether or not it exists yet).
    *******************************************************************************/
   public static List<QRecord> configuredRows(QInstance instance, CollaborationQBitConfig config)
   {
      List<QRecord> rows = new ArrayList<>();

      ///////////////////////////////////////////////////////////
      // instance: every feature, so each one has a visible row //
      ///////////////////////////////////////////////////////////
      RecordFeaturesMetaData instanceLevel = RecordFeaturesMetaData.of(instance);
      for(RecordFeature feature : RecordFeature.values())
      {
         Map<String, Serializable> settings = new LinkedHashMap<>(declaredSettings(instanceLevel, feature));
         if(feature == RecordFeature.TAGS)
         {
            settings.putIfAbsent("mode", config.getDefaultTagMode());
         }
         FeaturePermissionMode mode = declaredMode(instanceLevel, feature);
         rows.add(row("INSTANCE", INSTANCE_SCOPE_NAME, feature, enabledThrough(feature, instanceLevel), mode == null ? FeaturePermissionMode.EDIT_OR_GRANTED : mode, settings));
      }

      /////////////////////////////////////////////////////////////////////////////
      // apps and tables: only where the code declares something for the feature //
      /////////////////////////////////////////////////////////////////////////////
      for(QAppMetaData app : CollectionUtils.nonNullMap(instance.getApps()).values())
      {
         RecordFeaturesMetaData level = RecordFeaturesMetaData.of(app);
         for(RecordFeature feature : declared(level))
         {
            rows.add(row("APP", app.getName(), feature, enabledThrough(feature, instanceLevel, level), modeThrough(feature, instanceLevel, level), declaredSettings(level, feature)));
         }
      }
      for(QTableMetaData table : CollectionUtils.nonNullMap(instance.getTables()).values())
      {
         if(table.getName().startsWith("collaboration_"))
         {
            continue;
         }
         RecordFeaturesMetaData level = RecordFeaturesMetaData.of(table);
         List<RecordFeaturesMetaData> apps = new ArrayList<>();
         for(QAppMetaData app : CollectionUtils.nonNullMap(instance.getApps()).values())
         {
            boolean contains = CollectionUtils.nonNullList(app.getSections()).stream().anyMatch(section -> CollectionUtils.nonNullList(section.getTables()).contains(table.getName()));
            if(contains && RecordFeaturesMetaData.of(app) != null)
            {
               apps.add(RecordFeaturesMetaData.of(app));
            }
         }

         ///////////////////////////////////////////////////////////////////////////
         // a table row restates the whole chain (instance, its apps, the table).  //
         // Needed where the table declares something itself, and where several   //
         // apps declare the same feature: one row per app cannot restate the     //
         // combination, but the last override, the table row, can.               //
         ///////////////////////////////////////////////////////////////////////////
         Set<RecordFeature> features = new TreeSet<>(declared(level));
         if(apps.size() > 1)
         {
            apps.forEach(app -> features.addAll(declared(app)));
         }
         List<RecordFeaturesMetaData> chain = new ArrayList<>();
         chain.add(instanceLevel);
         chain.addAll(apps);
         chain.add(level);
         RecordFeaturesMetaData[] levels = chain.toArray(new RecordFeaturesMetaData[0]);
         for(RecordFeature feature : features)
         {
            Map<String, Serializable> settings = new LinkedHashMap<>();
            for(RecordFeaturesMetaData each : levels)
            {
               settings.putAll(declaredSettings(each, feature));
            }
            rows.add(row("TABLE", table.getName(), feature, enabledThrough(feature, levels), modeThrough(feature, levels), settings));
         }
      }
      return (rows);
   }



   /*******************************************************************************
    ** The features a scope declares anything about (enabled, mode or settings).
    *******************************************************************************/
   private static Set<RecordFeature> declared(RecordFeaturesMetaData level)
   {
      if(level == null)
      {
         return (Set.of());
      }
      return (Stream.of(level.getEnabled().keySet(), level.getPermissionModes().keySet(), level.getSettings().keySet())
         .flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new)));
   }



   /*******************************************************************************
    ** Enabled as the code resolves it through the given levels, in order, the
    ** same way core does (a later level that declares the feature wins).
    *******************************************************************************/
   private static boolean enabledThrough(RecordFeature feature, RecordFeaturesMetaData... levels)
   {
      boolean enabled = false;
      for(RecordFeaturesMetaData level : levels)
      {
         if(level != null && level.getEnabled().containsKey(feature))
         {
            enabled = Boolean.TRUE.equals(level.getEnabled().get(feature));
         }
      }
      return (enabled);
   }



   /*******************************************************************************
    ** The permission mode through the given levels (a later declaration wins),
    ** EDIT_OR_GRANTED when none declares one, as core resolves it.
    *******************************************************************************/
   private static FeaturePermissionMode modeThrough(RecordFeature feature, RecordFeaturesMetaData... levels)
   {
      FeaturePermissionMode mode = FeaturePermissionMode.EDIT_OR_GRANTED;
      for(RecordFeaturesMetaData level : levels)
      {
         if(declaredMode(level, feature) != null)
         {
            mode = declaredMode(level, feature);
         }
      }
      return (mode);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static FeaturePermissionMode declaredMode(RecordFeaturesMetaData level, RecordFeature feature)
   {
      return (level == null ? null : level.getPermissionModes().get(feature));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Map<String, Serializable> declaredSettings(RecordFeaturesMetaData level, RecordFeature feature)
   {
      return (level == null || level.getSettings().get(feature) == null ? Map.of() : level.getSettings().get(feature));
   }



   /*******************************************************************************
    ** One feature_setting record; no settings means a null settings column.
    *******************************************************************************/
   private static QRecord row(String scopeType, String scopeName, RecordFeature feature, boolean enabled, FeaturePermissionMode mode, Map<String, Serializable> settings)
   {
      QRecord record = new QRecord().withValue("scopeType", scopeType).withValue("scopeName", scopeName).withValue("feature", feature.name())
         .withValue("enabled", enabled).withValue("permissionMode", mode == null ? null : mode.name());
      if(!settings.isEmpty())
      {
         try
         {
            record.withValue("settings", MAPPER.writeValueAsString(settings));
         }
         catch(JsonProcessingException e)
         {
            throw (new IllegalStateException("Feature settings are not serializable", e));
         }
      }
      return (record);
   }

}
