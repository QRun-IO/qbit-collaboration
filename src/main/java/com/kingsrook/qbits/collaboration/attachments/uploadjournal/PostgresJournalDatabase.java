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


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;


/*******************************************************************************
 ** Narrow qualification of this owner's fixed PostgreSQL 17 journal and attachment
 ** pair, on the native QQQ postgres backend (vendor and backend type "postgres").
 ** Observation only: no schema migration and no session or server setting is
 ** changed at runtime.
 *******************************************************************************/
final class PostgresJournalDatabase
{
   static final String VENDOR = "postgres";

   private static final String SETTINGS = "SELECT current_setting('server_version_num')::int, current_setting('transaction_read_only'), pg_is_in_recovery(),"
      + " current_setting('synchronous_commit'), current_setting('fsync'), current_setting('full_page_writes'), current_setting('TimeZone'),"
      + " (SELECT setting::bigint FROM pg_settings WHERE name='lock_timeout'), current_setting('transaction_isolation'), current_database()";

   private static final String TABLES = "SELECT c.relname, c.relkind, c.relpersistence FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
      + " WHERE n.nspname=current_schema() AND c.relname = ANY(?)";

   private static final String COLLATIONS = "SELECT c.relname, a.attname FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace"
      + " JOIN pg_collation co ON co.oid=a.attcollation WHERE n.nspname=current_schema() AND c.relname = ANY(?) AND a.attnum>0 AND NOT a.attisdropped AND NOT co.collisdeterministic";

   private static final String UNIQUES = "SELECT i.relname, a.attname FROM pg_index ix JOIN pg_class i ON i.oid=ix.indexrelid"
      + " JOIN pg_class t ON t.oid=ix.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace"
      + " CROSS JOIN LATERAL unnest(ix.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)"
      + " JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum"
      + " WHERE n.nspname=current_schema() AND t.relname = ? AND ix.indisunique AND ix.indisvalid AND ix.indpred IS NULL AND ix.indexprs IS NULL AND k.ord<=ix.indnkeyatts"
      + " ORDER BY i.relname, k.ord";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private PostgresJournalDatabase()
   {
   }



   /*******************************************************************************
    ** The native postgres module identity, not merely a vendor string: a "postgres"
    ** vendor label on the generic rdbms backend type is refused.
    *******************************************************************************/
   static boolean is(RDBMSBackendMetaData backend)
   {
      return (VENDOR.equals(backend.getVendor()) && VENDOR.equals(backend.getBackendType()));
   }



   /*******************************************************************************
    ** Check the real connection, current durability settings and physical tables.
    *******************************************************************************/
   static void requireQualifiedConnection(Connection connection, RDBMSBackendMetaData backend) throws SQLException, QException
   {
      String expected = baseUrl(ConnectionManager.getJdbcUrl(backend));
      String actual = baseUrl(connection.getMetaData().getURL());
      String catalog;
      try
      {
         URI location = URI.create(expected.substring("jdbc:".length()));
         catalog = location.getPath() == null || !location.getPath().startsWith("/") ? "" : location.getPath().substring(1);
         if(location.getHost() == null || location.getUserInfo() != null || !catalog.matches("[A-Za-z0-9_]+"))
         {
            throw (unsupported());
         }
      }
      catch(IllegalArgumentException | IndexOutOfBoundsException e)
      {
         throw (unsupported());
      }
      if(!is(backend) || !expected.equals(actual) || !catalog.equals(connection.getCatalog()) || connection.isClosed() || connection.isReadOnly()
         || connection.getAutoCommit() || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
         || connection.getMetaData().getDatabaseMajorVersion() != 17)
      {
         throw (unsupported());
      }
      try(Statement statement = connection.createStatement())
      {
         statement.setQueryTimeout(10);
         try(ResultSet settings = statement.executeQuery(SETTINGS))
         {
            if(!settings.next() || settings.getInt(1) / 10000 != 17 || !"off".equals(settings.getString(2)) || settings.getBoolean(3)
               || !Set.of("on", "remote_apply").contains(settings.getString(4)) || !"on".equals(settings.getString(5)) || !"on".equals(settings.getString(6))
               || !Set.of("UTC", "Etc/UTC").contains(settings.getString(7)) || settings.getLong(8) < 1 || settings.getLong(8) > 5000
               || !"read committed".equals(settings.getString(9)) || !catalog.equals(settings.getString(10)))
            {
               throw (unsupported());
            }
         }
      }
      List<String> names = List.of(CollaborationTables.UPLOAD_JOURNAL, CollaborationTables.ATTACHMENT);
      for(String tableName : names)
      {
         QTableMetaData table = QContext.getQInstance().getTable(tableName);
         if(table.getBackendDetails() != null && (!(table.getBackendDetails() instanceof RDBMSTableBackendDetails details)
            || (details.getTableName() != null && !tableName.equals(details.getTableName()))))
         {
            throw (unsupported());
         }
      }
      requireTables(connection, names);
   }



   /*******************************************************************************
    ** Permanent (logged) ordinary tables only, deterministic text comparison, and
    ** complete valid non-partial unique indexes for the journal's two identities.
    *******************************************************************************/
   private static void requireTables(Connection connection, List<String> names) throws SQLException, QException
   {
      java.sql.Array array = connection.createArrayOf("text", names.toArray());
      try
      {
         try(PreparedStatement statement = connection.prepareStatement(TABLES))
         {
            statement.setQueryTimeout(10);
            statement.setArray(1, array);
            try(ResultSet rows = statement.executeQuery())
            {
               Set<String> observed = new java.util.HashSet<>();
               while(rows.next())
               {
                  if(!names.contains(rows.getString(1)) || !observed.add(rows.getString(1)) || !"r".equals(rows.getString(2)) || !"p".equals(rows.getString(3)))
                  {
                     throw (unsupported());
                  }
               }
               if(observed.size() != names.size())
               {
                  throw (unsupported());
               }
            }
         }
         try(PreparedStatement statement = connection.prepareStatement(COLLATIONS))
         {
            statement.setQueryTimeout(10);
            statement.setArray(1, array);
            try(ResultSet rows = statement.executeQuery())
            {
               if(rows.next())
               {
                  throw (unsupported());
               }
            }
         }
      }
      finally
      {
         array.free();
      }
      try(PreparedStatement statement = connection.prepareStatement(UNIQUES))
      {
         statement.setQueryTimeout(10);
         statement.setString(1, CollaborationTables.UPLOAD_JOURNAL);
         try(ResultSet rows = statement.executeQuery())
         {
            Map<String, List<String>> columns = new HashMap<>();
            while(rows.next())
            {
               columns.computeIfAbsent(rows.getString(1), key -> new ArrayList<>()).add(rows.getString(2));
            }
            if(!columns.containsValue(List.of("locator_hash")) || !columns.containsValue(List.of("intake_key")))
            {
               throw (unsupported());
            }
         }
      }
   }



   /*******************************************************************************
    ** Compare the configured target without logging credentials or driver options.
    *******************************************************************************/
   private static String baseUrl(String url) throws UploadJournalException
   {
      if(url == null || !url.startsWith("jdbc:postgresql://"))
      {
         throw (unsupported());
      }
      return (url.split("\\?", 2)[0]);
   }



   /*******************************************************************************
    ** Deliberately omit connection URLs and settings from externally visible errors.
    *******************************************************************************/
   private static UploadJournalException unsupported()
   {
      return (new UploadJournalException(UploadJournalException.Reason.UNSUPPORTED_BACKEND,
         "Retained upload journaling requires its configured PostgreSQL 17 database, permanent owner tables, deterministic comparison, READ COMMITTED isolation, a UTC session, a bounded lock timeout and synchronous durable commits."));
   }
}
