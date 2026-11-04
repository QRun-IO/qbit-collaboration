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
import java.util.List;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;


/*******************************************************************************
 ** Narrow qualification of this owner's fixed MySQL journal and attachment pair.
 ** No schema migration or server-global setting is performed at runtime.
 *******************************************************************************/
final class MySqlJournalDatabase
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private MySqlJournalDatabase()
   {
   }


   /*******************************************************************************
    ** Check the real connection, current durability settings and physical tables.
    ** A host-configured session lock timeout bounds contention without mutation.
    *******************************************************************************/
   static void requireQualifiedConnection(Connection connection, RDBMSBackendMetaData backend) throws SQLException, QException
   {
      String expected = baseUrl(ConnectionManager.getJdbcUrl(backend));
      String actual = baseUrl(connection.getMetaData().getURL());
      String catalog;
      try
      {
         URI location = URI.create(expected.substring("jdbc:".length()));
         catalog = location.getPath().substring(1);
         if(location.getHost() == null || location.getUserInfo() != null || !catalog.matches("[A-Za-z0-9_]+"))
         {
            throw (unsupported());
         }
      }
      catch(IllegalArgumentException | IndexOutOfBoundsException e)
      {
         throw (unsupported());
      }
      if(!expected.equals(actual) || !catalog.equals(connection.getCatalog()) || connection.isClosed() || connection.isReadOnly()
         || connection.getAutoCommit() || !"MySQL".equals(connection.getMetaData().getDatabaseProductName())
         || !connection.getMetaData().getDatabaseProductVersion().startsWith("8.4."))
      {
         throw (unsupported());
      }
      try(Statement statement = connection.createStatement())
      {
         statement.setQueryTimeout(10);
         try(ResultSet settings = statement.executeQuery("SELECT @@GLOBAL.innodb_flush_log_at_trx_commit, @@GLOBAL.log_bin, @@GLOBAL.sync_binlog, @@GLOBAL.read_only, @@SESSION.time_zone, @@GLOBAL.system_time_zone, @@SESSION.innodb_lock_wait_timeout, @@GLOBAL.innodb_doublewrite, @@SESSION.transaction_read_only"))
         {
            if(!settings.next() || !List.of("ON", "DETECT_AND_RECOVER").contains(settings.getString(8)) || settings.getBoolean(9)
               || settings.getInt(7) < 1 || settings.getInt(7) > 5 || settings.getInt(1) != 1 || (settings.getBoolean(2) && settings.getInt(3) != 1) || settings.getBoolean(4)
               || !(List.of("+00:00", "UTC").contains(settings.getString(5))
                  || ("SYSTEM".equals(settings.getString(5)) && "UTC".equals(settings.getString(6)))))
            {
               throw (unsupported());
            }
         }
      }
      for(String tableName : List.of(CollaborationTables.UPLOAD_JOURNAL, CollaborationTables.ATTACHMENT))
      {
         QTableMetaData table = QContext.getQInstance().getTable(tableName);
         if(table.getBackendDetails() != null && (!(table.getBackendDetails() instanceof RDBMSTableBackendDetails details)
            || (details.getTableName() != null && !tableName.equals(details.getTableName()))))
         {
            throw (unsupported());
         }
         try(PreparedStatement statement = connection.prepareStatement("SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"))
         {
            statement.setQueryTimeout(10);
            statement.setString(1, catalog);
            statement.setString(2, tableName);
            try(ResultSet tableInfo = statement.executeQuery())
            {
               if(!tableInfo.next() || !tableName.equals(tableInfo.getString(1)) || !"InnoDB".equals(tableInfo.getString(2))
                  || (CollaborationTables.UPLOAD_JOURNAL.equals(tableName) && !"utf8mb4_0900_bin".equals(tableInfo.getString(3))) || tableInfo.next())
               {
                  throw (unsupported());
               }
            }
         }
      }
      try(PreparedStatement statement = connection.prepareStatement("SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLLATION_NAME IS NOT NULL"))
      {
         statement.setQueryTimeout(10);
         statement.setString(1, catalog);
         statement.setString(2, CollaborationTables.UPLOAD_JOURNAL);
         try(ResultSet columns = statement.executeQuery())
         {
            int count = 0;
            while(columns.next())
            {
               count++;
               if(!"utf8mb4_0900_bin".equals(columns.getString(1)))
               {
                  throw (unsupported());
               }
            }
            if(count < 1)
            {
               throw (unsupported());
            }
         }
      }
      try(PreparedStatement statement = connection.prepareStatement("SELECT INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND NON_UNIQUE = 0 ORDER BY INDEX_NAME, SEQ_IN_INDEX"))
      {
         statement.setQueryTimeout(10);
         statement.setString(1, catalog);
         statement.setString(2, CollaborationTables.UPLOAD_JOURNAL);
         try(ResultSet indexes = statement.executeQuery())
         {
            java.util.Map<String, List<String>> columns = new java.util.HashMap<>();
            while(indexes.next())
            {
               columns.computeIfAbsent(indexes.getString(1), key -> new java.util.ArrayList<>()).add(indexes.getString(2));
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
      if(url == null || !url.startsWith("jdbc:mysql://"))
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
         "Retained upload journaling requires its configured MySQL 8.4 catalog, InnoDB owner tables, binary journal identifiers, a UTC session, a 1-5 second lock timeout and synchronous durable commits."));
   }
}
