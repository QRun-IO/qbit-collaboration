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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;

/*******************************************************************************
 ** Probe once per instance/table; metadata refresh never performs schema DDL.
 *******************************************************************************/
public final class CustomFieldStorage
{
   private static final Map<QInstance, Map<String, Availability>> CACHE = new WeakHashMap<>();

   /*******************************************************************************
    ** Static backend boundary.
    *******************************************************************************/
   private CustomFieldStorage()
   {
   }

   /*******************************************************************************
    ** A visible reason accompanies every fallback.
    *******************************************************************************/
   public record Availability(boolean json, String reason)
   {
   }

   /*******************************************************************************
    ** Memory needs no schema. Only PostgreSQL implements the JSON SQL contract.
    *******************************************************************************/
   public static synchronized Availability availability(QTableMetaData table)
   {
      var instance = QContext.getQInstance();
      return (CACHE.computeIfAbsent(instance, key -> new HashMap<>()).computeIfAbsent(table.getName(), key -> probe(table)));
   }

   /*******************************************************************************
    ** Hosts can retry after applying the one-time migration.
    *******************************************************************************/
   public static synchronized void invalidate(QInstance instance, String table)
   {
      CACHE.getOrDefault(instance, new HashMap<>()).remove(table);
   }

   /*******************************************************************************
    ** Failed probes never install JSON fields that would break ordinary queries.
    *******************************************************************************/
   private static Availability probe(QTableMetaData table)
   {
      var backend = QContext.getQInstance().getBackend(table.getBackendName());
      if(new MemoryBackendModule().getBackendType().equals(backend.getBackendType()))
      {
         return (new Availability(true, null));
      }
      if(!postgres(table))
      {
         return (new Availability(false, "Backend does not support JsonPathStorage; EAV fields are read-only filters (values are not selected)."));
      }
      try(Connection connection = connection(table); var statement = connection.createStatement())
      {
         statement.executeQuery("SELECT qqq_custom_fields FROM " + sqlTable(table) + " LIMIT 0").close();
         return (new Availability(true, null));
      }
      catch(SQLException | IllegalArgumentException e)
      {
         return (new Availability(false, "JSON column unavailable; EAV fields are read-only filters. " + e.getMessage()));
      }
   }

   /*******************************************************************************
    ** Match the installed RDBMS metadata, not a backend's arbitrary configured name.
    *******************************************************************************/
   public static boolean postgres(QTableMetaData table)
   {
      return (QContext.getQInstance().getBackend(table.getBackendName()) instanceof RDBMSBackendMetaData backend
         && ("postgres".equalsIgnoreCase(backend.getVendor()) || "postgresql".equalsIgnoreCase(backend.getVendor())));
   }

   /*******************************************************************************
    ** A fresh backend connection is intentionally outside QContext's transaction.
    *******************************************************************************/
   public static Connection connection(QTableMetaData table) throws SQLException
   {
      return (ConnectionManager.getConnection((RDBMSBackendMetaData) QContext.getQInstance().getBackend(table.getBackendName())));
   }

   /*******************************************************************************
    ** Quote metadata identifiers; never accept SQL fragments from definitions.
    *******************************************************************************/
   public static String identifier(String name)
   {
      if(name == null || name.isBlank() || name.indexOf('\0') >= 0)
      {
         throw (new IllegalArgumentException("Invalid SQL identifier"));
      }
      return ("\"" + name.replace("\"", "\"\"") + "\"");
   }

   /*******************************************************************************
    ** Backend table names may include a schema qualification.
    *******************************************************************************/
   public static String sqlTable(QTableMetaData table)
   {
      String name = table.getBackendDetails() instanceof com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails details ? details.getTableName() : null;
      if(name == null)
      {
         name = table.getName();
      }
      return (java.util.Arrays.stream(name.split("\\.")).map(CustomFieldStorage::identifier).collect(java.util.stream.Collectors.joining(".")));
   }
}
