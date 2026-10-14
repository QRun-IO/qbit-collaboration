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

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.customfields.BuildFieldIndexes;
import com.kingsrook.qbits.collaboration.customfields.CustomFieldStorage;
import com.kingsrook.qbits.collaboration.customfields.FieldDefinitions;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionProviderInterface;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** JDBC contract tests capture SQL and connection state without a live PostgreSQL server.
 *******************************************************************************/
public class CustomFieldSqlTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Use the real backend connection boundary with a deterministic driver double.
    *******************************************************************************/
   private QRecord setupSql() throws Exception
   {
      admin();
      Jdbc.sql.clear();
      Jdbc.parameters.clear();
      Jdbc.failProbe = false;
      Jdbc.failDdl = false;
      Jdbc.usage = 0;
      Jdbc.connections = 0;
      ConnectionManager.resetConnectionProviders();
      instance.addBackend(new RDBMSBackendMetaData().withName("test-postgres").withVendor("postgres").withConnectionProvider(new QCodeReference(Jdbc.class)));
      instance.getTable("document").withBackendName("test-postgres").withBackendDetails(new RDBMSTableBackendDetails().withTableName("tenant.orders"));
      return (CollaborationRecords.insert(CollaborationTables.FIELD_DEFINITION, new QRecord().withValue("tableName", "document")
         .withValue("name", "total").withValue("label", "Total").withValue("type", "DECIMAL").withValue("status", "ACTIVE")
         .withValue("filterable", true).withValue("indexState", "NONE")));
   }

   /*******************************************************************************
    ** Column absence is cached; retry after migration is explicit and executes once.
    *******************************************************************************/
   @Test
   void columnProbeIsCachedAndReportsFailures() throws Exception
   {
      setupSql();
      var table = instance.getTable("document");
      Jdbc.failProbe = true;
      assertThat(CustomFieldStorage.availability(table).json()).isFalse();
      assertThat(CustomFieldStorage.availability(table).reason()).contains("column unavailable");
      assertThat(Jdbc.connections).isEqualTo(1);
      Jdbc.failProbe = false;
      CustomFieldStorage.invalidate(instance, "document");
      assertThat(CustomFieldStorage.availability(table).json()).isTrue();
      assertThat(Jdbc.connections).isEqualTo(2);
      assertThat(Jdbc.sql).contains("SELECT qqq_custom_fields FROM \"tenant\".\"orders\" LIMIT 0");
      assertThatThrownBy(() -> CustomFieldStorage.identifier(" ")).isInstanceOf(IllegalArgumentException.class);
   }

   /*******************************************************************************
    ** Concurrent DDL requires autocommit and failures remain visible on the definition.
    *******************************************************************************/
   @Test
   void indexConnectionAndFailureStates() throws Exception
   {
      var definition = setupSql();
      BuildFieldIndexes.build(definition);
      assertThat(FieldDefinitions.get(definition.getValue("id")).getValueString("indexState")).isEqualTo("READY");
      assertThat(Jdbc.sql).anyMatch(sql -> sql.contains("CREATE INDEX CONCURRENTLY") && sql.contains("::numeric"));
      Jdbc.failDdl = true;
      BuildFieldIndexes.build(definition);
      assertThat(FieldDefinitions.get(definition.getValue("id")).getValueString("indexState")).isEqualTo("FAILED");
      assertThat(FieldDefinitions.get(definition.getValue("id")).getValueString("statusMessage")).contains("test DDL failure");
      Jdbc.failDdl = false;
      definition.withValue("status", "ARCHIVED");
      BuildFieldIndexes.build(definition);
      assertThat(FieldDefinitions.get(definition.getValue("id")).getValueString("indexState")).isEqualTo("NONE");
      assertThat(Jdbc.sql).anyMatch(sql -> sql.startsWith("DROP INDEX CONCURRENTLY"));
   }

   /*******************************************************************************
    ** Used stable ids cannot be removed; labels may change and new ids may be added.
    *******************************************************************************/
   @Test
   void activeOptionRemovalUsesBoundContainmentQuery() throws Exception
   {
      var definition = setupSql().withValue("type", "SELECT").withValue("options", "[{\"id\":\"a\",\"label\":\"Alpha\"}]");
      var changed = new QRecord(definition).withValue("options", "[]");
      Jdbc.usage = 1;
      assertThatThrownBy(() -> FieldDefinitions.validate(changed, definition)).hasMessageContaining("still used");
      assertThat(Jdbc.sql).anyMatch(sql -> sql.contains("qqq_custom_fields @> ?::jsonb"));
      assertThat(Jdbc.parameters.get(1)).isEqualTo("{\"total\":\"a\"}");
      FieldDefinitions.validate(new QRecord(definition).withValue("options", "[{\"id\":\"a\",\"label\":\"Renamed\"},{\"id\":\"b\",\"label\":\"Added\"}]"), definition);
      Jdbc.usage = 0;
      FieldDefinitions.validate(changed, definition);
      definition.withValue("type", "MULTI_SELECT");
      changed.withValue("type", "MULTI_SELECT");
      FieldDefinitions.validate(changed, definition);
      assertThat(Jdbc.parameters.get(1)).isEqualTo("total");
      assertThat(Jdbc.parameters.get(2)).isEqualTo("[\"a\"]");
   }

   /*******************************************************************************
    ** Capture the JDBC boundary, including whether concurrent DDL was autocommitted.
    *******************************************************************************/
   public static class Jdbc implements ConnectionProviderInterface
   {
      static final List<String> sql = new ArrayList<>();
      static final Map<Integer, String> parameters = new LinkedHashMap<>();
      static boolean failProbe;
      static boolean failDdl;
      static long usage;
      static int connections;

      /*******************************************************************************
       ** No external connection configuration is needed by the test double.
       *******************************************************************************/
      @Override
      public void init(RDBMSBackendMetaData backend)
      {
      }

      /*******************************************************************************
       ** A new connection starts outside autocommit to verify the index job sets it.
       *******************************************************************************/
      @Override
      public Connection getConnection()
      {
         connections++;
         boolean[] autoCommit = {false};
         return ((Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) ->
         {
            return (switch(method.getName())
            {
               case "setAutoCommit" ->
               {
                  autoCommit[0] = (Boolean) args[0];
                  yield null;
               }
               case "getAutoCommit" -> autoCommit[0];
               case "createStatement" -> statement(null, autoCommit);
               case "prepareStatement" -> statement((String) args[0], autoCommit);
               case "close" -> null;
               default -> throw new UnsupportedOperationException(method.getName());
            });
         }));
      }

      /*******************************************************************************
       ** Only supported SQL operations are accepted; unexpected calls fail the test.
       *******************************************************************************/
      private Object statement(String prepared, boolean[] autoCommit)
      {
         return (Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{prepared == null ? Statement.class : PreparedStatement.class}, (proxy, method, args) ->
         {
            if(method.getName().equals("close"))
            {
               return (null);
            }
            if(method.getName().equals("setString"))
            {
               parameters.put((Integer) args[0], (String) args[1]);
               return (null);
            }
            String command = prepared == null ? (String) args[0] : prepared;
            sql.add(command);
            if(command.startsWith("SELECT qqq_custom_fields") && failProbe)
            {
               throw (new SQLException("column unavailable"));
            }
            if(method.getName().equals("execute"))
            {
               assertThat(autoCommit[0]).isTrue();
               if(failDdl)
               {
                  throw (new SQLException("test DDL failure"));
               }
               return (false);
            }
            return (Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class}, (result, operation, values) -> switch(operation.getName())
            {
               case "next", "getBoolean" -> true;
               case "getLong" -> usage;
               case "close" -> null;
               default -> throw new UnsupportedOperationException(operation.getName());
            }));
         }));
      }
   }
}
