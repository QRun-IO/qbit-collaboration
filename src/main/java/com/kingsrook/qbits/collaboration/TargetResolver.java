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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.collaboration.favorites.FavoriteEntry;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.permissions.fieldaccess.FieldAccessHelper;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.exceptions.QValueException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Resolves targets through core permission and field-access APIs. Each list batches record label
 ** refreshes by table, preserving table permissions and record security locks.
 ** Next accepts the flat /app/name/ routes, including dotted process names.
 *******************************************************************************/
public final class TargetResolver
{
   private static final Set<String> TYPES = Set.of("TABLE", "APP", "RECORD", "PROCESS", "REPORT", "SAVED_VIEW");



   /*******************************************************************************
    ** Static operations only.
    *******************************************************************************/
   private TargetResolver()
   {
   }



   /*******************************************************************************
    ** Validate target shape without requiring visibility (also used to unstar).
    *******************************************************************************/
   public static String recordId(String type, String name, Serializable recordId) throws QException
   {
      String id = recordId == null ? null : recordId.toString();
      if(type == null || !TYPES.contains(type) || name == null || name.isBlank()
         || ("RECORD".equals(type) ? id == null || id.isBlank() : id != null))
      {
         throw (new QBadRequestException("Invalid target"));
      }
      if("RECORD".equals(type))
      {
         QTableMetaData table = QContext.getQInstance().getTable(name);
         if(table != null && table.getPrimaryKeyField() != null)
         {
            try
            {
               id = ValueUtils.getValueAsFieldType(table.getField(table.getPrimaryKeyField()).getType(), id).toString();
            }
            catch(QValueException expected)
            {
               throw (new QNotFoundException("Not found"));
            }
         }
      }
      return (id);
   }



   /*******************************************************************************
    ** Resolve one target for writes; missing and invisible have the same error.
    *******************************************************************************/
   public static FavoriteEntry require(String type, String name, Serializable id) throws QException
   {
      String recordId = recordId(type, name, id);
      QRecord row = new QRecord().withValue("targetType", type).withValue("targetName", name).withValue("recordId", recordId);
      FavoriteEntry entry;
      if("RECORD".equals(type))
      {
         QRecord record = get(name, recordId);
         entry = entry(row, record == null ? null : record.getRecordLabel(), record == null ? null : path(name) + encode(recordId) + "/");
      }
      else
      {
         entry = metadataEntry(row);
      }
      if(!entry.available())
      {
         throw (new QNotFoundException("Not found"));
      }
      return (entry);
   }



   /*******************************************************************************
    ** Persist record labels only when all label fields are FULL for this session.
    *******************************************************************************/
   public static String cacheableLabel(FavoriteEntry target)
   {
      return ("RECORD".equals(target.targetType())
         && !FieldAccessHelper.isRecordLabelFullAccess(QContext.getQInstance().getTable(target.targetName())) ? null : target.label());
   }



   /*******************************************************************************
    ** One QueryAction per referenced record table; no cached labels escape.
    *******************************************************************************/
   public static List<FavoriteEntry> refresh(List<QRecord> rows) throws QException
   {
      Map<String, List<String>> idsByTable = new LinkedHashMap<>();
      for(QRecord row : rows)
      {
         if("RECORD".equals(row.getValueString("targetType")))
         {
            idsByTable.computeIfAbsent(row.getValueString("targetName"), key -> new ArrayList<>()).add(row.getValueString("recordId"));
         }
      }
      Map<String, Map<String, QRecord>> recordsByTable = new HashMap<>();
      for(Map.Entry<String, List<String>> group : idsByTable.entrySet())
      {
         String table = group.getKey();
         Map<String, QRecord> records = new HashMap<>();
         recordsByTable.put(table, records);
         if(canReadTable(table))
         {
            QueryInput input = new QueryInput(table).withShouldGenerateDisplayValues(true)
               .withFilter(new QQueryFilter().withCriteria(QContext.getQInstance().getTable(table).getPrimaryKeyField(), QCriteriaOperator.IN, group.getValue()));
            input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
            try
            {
               for(QRecord record : new QueryAction().execute(input).getRecords())
               {
                  records.put(record.getValueString(QContext.getQInstance().getTable(table).getPrimaryKeyField()), record);
               }
            }
            catch(QPermissionDeniedException expected)
            {
               // Permission may have changed since the metadata check; fail closed.
            }
         }
      }
      List<FavoriteEntry> entries = new ArrayList<>();
      for(QRecord row : rows)
      {
         String table = row.getValueString("targetName");
         String id = row.getValueString("recordId");
         if("RECORD".equals(row.getValueString("targetType")))
         {
            QRecord record = recordsByTable.get(table).get(id);
            entries.add(entry(row, record == null ? null : record.getRecordLabel(), record == null ? null : path(table) + encode(id) + "/"));
         }
         else
         {
            entries.add(metadataEntry(row));
         }
      }
      return (entries);
   }



   /*******************************************************************************
    ** Metadata targets use the same permission helpers as core. Saved views also
    ** require a visible savedView row and READ on the view's target table.
    *******************************************************************************/
   private static FavoriteEntry metadataEntry(QRecord row) throws QException
   {
      QInstance instance = QContext.getQInstance();
      AbstractActionInput input = new AbstractActionInput();
      String name = row.getValueString("targetName");
      String label = null;
      String href = null;
      switch(row.getValueString("targetType"))
      {
         case "TABLE" ->
         {
            if(canReadTable(name))
            {
               label = instance.getTable(name).getLabel();
               href = path(name);
            }
         }
         case "APP" ->
         {
            if(PermissionsHelper.hasAppPermission(input, name))
            {
               label = instance.getApp(name).getLabel();
               href = path(name);
            }
         }
         case "PROCESS" ->
         {
            if(PermissionsHelper.hasProcessPermission(input, name))
            {
               label = instance.getProcess(name).getLabel();
               href = path(name);
            }
         }
         case "REPORT" ->
         {
            if(PermissionsHelper.hasReportPermission(input, name))
            {
               label = instance.getReport(name).getLabel();
               href = path(name);
            }
         }
         case "SAVED_VIEW" ->
         {
            QRecord view = get("savedView", name);
            if(view != null && canReadTable(view.getValueString("tableName")))
            {
               label = view.getValueString("label");
               href = path(view.getValueString("tableName")) + "savedView/" + encode(name) + "/";
            }
         }
         default ->
         {
            // Unknown persisted types are unavailable rather than exposing their cache.
         }
      }
      return (entry(row, label, href));
   }



   /*******************************************************************************
    ** Both table visibility and per-record security are checked by GetAction.
    *******************************************************************************/
   private static QRecord get(String table, Serializable id) throws QException
   {
      if(!canReadTable(table))
      {
         return (null);
      }
      GetInput input = new GetInput(table).withPrimaryKey(id).withShouldGenerateDisplayValues(true);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      try
      {
         return (new GetAction().execute(input).getRecord());
      }
      catch(QPermissionDeniedException expected)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** Guard existence before invoking the core table permission helper.
    *******************************************************************************/
   private static boolean canReadTable(String table)
   {
      return (table != null && QContext.getQInstance().getTable(table) != null
         && PermissionsHelper.hasTablePermission(new AbstractActionInput(), table, TablePermissionSubType.READ));
   }



   /*******************************************************************************
    ** A missing href means unavailable; never substitute a cached label.
    *******************************************************************************/
   private static FavoriteEntry entry(QRecord row, String label, String href)
   {
      return (new FavoriteEntry(row.getValueInteger("id"), row.getValueString("targetType"), row.getValueString("targetName"),
         row.getValueString("recordId"), href == null ? "Unavailable" : label == null ? row.getValueString("targetName") : label,
         row.getValueString("folder"), row.getValueInteger("sortOrder"), href != null, href));
   }



   /*******************************************************************************
    ** Shared canonical record href, with optional configured frontend origin.
    *******************************************************************************/
   public static String recordHref(String table, String id)
   {
      String base = CollaborationRecords.config().getFrontendBaseUrl();
      return ((base == null ? "" : base.replaceAll("/+$", "")) + path(table) + encode(id));
   }

   /*******************************************************************************
    ** Flat Next router path; trailing slash also supports dotted process names.
    *******************************************************************************/
   private static String path(String name)
   {
      return ("/app/" + encode(name) + "/");
   }



   /*******************************************************************************
    ** Encode a single route segment, including slash, hash and spaces in ids.
    *******************************************************************************/
   public static String encode(String value)
   {
      return (URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
   }
}
