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


package com.kingsrook.qbits.collaboration.devserver;


import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.collaboration.attachments.AttachmentsService;
import com.kingsrook.qbits.collaboration.favorites.FavoritesService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.presence.PresenceRegistry;
import com.kingsrook.qbits.collaboration.recents.RecentsService;
import com.kingsrook.qbits.collaboration.sharing.SharingService;
import com.kingsrook.qbits.collaboration.tags.TagsService;
import com.kingsrook.qbits.collaboration.timeline.TimelineService;
import com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.fields.JsonPathStorage;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.MockAuthenticationModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;
import com.kingsrook.qqq.middleware.javalin.QJavalinImplementation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Exercise the real demo seed, permissions, audit history and local file URLs.
 *******************************************************************************/
class CollaborationDevServerTest
{
   /*******************************************************************************
    ** Build without frontend assets, then smoke the same server on a free port.
    *******************************************************************************/
   @Test
   void seededDemoValidatesAndServesCollaboration() throws Exception
   {
      String oldUser = System.getProperty("collab.dev.user");
      String oldPort = System.getProperty("collab.dev.port");
      QInstance instance = null;
      QApplicationJavalinServer server = null;
      MemoryRecordStore.getInstance().reset();
      CollaborationDevServer.MemoryStorage.clear();
      try(HttpClient client = HttpClient.newHttpClient())
      {
         System.setProperty("collab.dev.user", "alice");
         instance = CollaborationDevServer.buildInstance();
         CollaborationDevServer.seed(instance);
         new QInstanceValidator().validate(instance);
         assertThat(rows("person")).hasSize(25);
         assertThat(rows("sales_order")).hasSize(40);
         assertThat(rows("person")).anySatisfy(row ->
         {
            assertThat(row.getValueInteger("id")).isEqualTo(1);
            assertThat(row.getValueString("cf_preferred_contact")).isEqualTo("video");
         });
         assertThat(rows("sales_order")).allSatisfy(row -> assertThat(row.getValueString("cf_purchase_order_no")).startsWith("PO-"));
         assertThat(instance.getTable("person").getField("cf_preferred_contact").getBackendStorage()).isInstanceOf(JsonPathStorage.class);
         assertThat(instance.getTable("sales_order").getField("cf_purchase_order_no").getBackendStorage()).isInstanceOf(JsonPathStorage.class);
         assertThat(instance.getTable("sales_order").getSearchFields()).contains("cf_purchase_order_no");
         for(String table : List.of("person", "sales_order"))
         {
            var features = RecordFeaturesResolver.resolve(instance, instance.getTable(table)).getFeatures();
            for(RecordFeature feature : RecordFeature.values())
            {
               assertThat(features.get(feature).getEnabled()).as(table + " " + feature).isTrue();
            }
            assertThat(new TagsService().tagsFor(table, 1)).hasSize(4);
         }
         assertThat(new TagsService().mode("person")).isEqualTo("FREE");
         assertThat(new TagsService().mode("sales_order")).isEqualTo("APPROVED_ONLY");
         assertThat(FavoritesService.list()).hasSize(6);
         assertThat(RecentsService.list(20)).hasSize(6);
         assertThat(new TimelineService().timeline("person", 1, null, 100, null).items())
            .anySatisfy(item -> assertThat(item.details()).anySatisfy(detail ->
            {
               assertThat(detail.fieldName()).isEqualTo("title");
               assertThat(detail.oldValue()).isEqualTo("Purchasing Director");
               assertThat(detail.newValue()).isEqualTo("VP of Purchasing");
            }));
         assertThat(new SharingService().preview("person", 1, List.of("bob")).getFirst().canRead()).isTrue();
         assertThat(new SharingService().preview("sales_order", 1, List.of("bob")).getFirst().canRead()).isFalse();
         QContext.setQSession(CollaborationDevServer.session("admin"));
         assertThat(rows(CollaborationTables.USER)).hasSize(3);
         assertThat(rows(CollaborationTables.FIELD_DEFINITION)).hasSize(2).allSatisfy(row -> assertThat(row.getValueString("status")).isEqualTo("ACTIVE"));
         assertThat(rows(CollaborationTables.TAG)).hasSize(8);
         assertThat(rows(CollaborationTables.ATTACHMENT)).hasSize(3);
         assertThat(rows(CollaborationTables.COMMENT)).hasSize(6).anySatisfy(row -> assertThat(row.getValue("resolvedAt")).isNotNull());
         assertThat(rows(CollaborationTables.MENTION)).hasSize(3);
         assertThat(rows(CollaborationTables.TASK)).hasSize(4).anySatisfy(row ->
         {
            assertThat(row.getValueString("status")).isEqualTo("OPEN");
            assertThat(row.getValueInstant("dueAt")).isBefore(Instant.now());
         });
         assertThat(rows(CollaborationTables.SHARE)).hasSize(2);
         assertThat(rows(CollaborationTables.RECORD_ASSIGNMENT)).hasSize(2);
         assertThat(rows(CollaborationTables.ACCESS_REQUEST)).singleElement().satisfies(row -> assertThat(row.getValueString("status")).isEqualTo("OPEN"));
         assertThat(rows("auditDetail")).isNotEmpty();
         for(String user : CollaborationDevServer.USERS)
         {
            System.setProperty("collab.dev.user", user);
            QSession mock = new MockAuthenticationModule().createSession(instance, Map.of("sessionUUID", UUID.randomUUID().toString()));
            assertThat(mock.getUser().getIdReference()).isEqualTo(user);
            assertThat(mock.getPermissions()).isEqualTo(CollaborationDevServer.session(user).getPermissions());
         }
         assertThatThrownBy(() -> CollaborationDevServer.session("unknown")).isInstanceOf(IllegalArgumentException.class);
         System.setProperty("collab.dev.user", "alice");
         int port;
         try(ServerSocket socket = new ServerSocket(0))
         {
            port = socket.getLocalPort();
         }
         System.setProperty("collab.dev.port", Integer.toString(port));
         server = CollaborationDevServer.server(instance, port, false);
         server.start();
         String base = "http://localhost:" + port;
         assertThat(get(client, base + "/qqq/v1/version").statusCode()).isEqualTo(200);
         var response = get(client, base + "/qqq/v1/qbit/collaboration/records/person/1/comments");
         assertThat(response.statusCode()).isEqualTo(200);
         assertThat(new ObjectMapper().readTree(response.body()).get("threads")).hasSize(3);
         QContext.init(instance, CollaborationDevServer.session("alice"));
         AttachmentsService attachments = new AttachmentsService();
         var png = attachments.list("person", 1).getFirst();
         var image = get(client, attachments.downloadUrl(png.id(), true).toString());
         assertThat(image.statusCode()).isEqualTo(200);
         assertThat(image.headers().firstValue("Content-Type").orElseThrow()).startsWith("image/png");
         assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.body()))).isNotNull();
         var pdf = attachments.list("sales_order", 1).getFirst();
         var document = get(client, attachments.downloadUrl(pdf.id(), false).toString());
         assertThat(document.statusCode()).isEqualTo(200);
         assertThat(new String(document.body(), java.nio.charset.StandardCharsets.US_ASCII)).startsWith("%PDF-1.4");
         assertThat(document.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment", "order-confirmation.pdf");
         System.setProperty("collab.dev.user", "bob");
         assertThat(get(client, base + "/qqq/v1/qbit/collaboration/records/person/1/comments").statusCode()).isEqualTo(200);
         assertThat(get(client, base + "/qqq/v1/qbit/collaboration/records/sales_order/1/comments").statusCode()).isEqualTo(404);
      }
      finally
      {
         if(server != null)
         {
            server.stop();
         }
         if(instance != null)
         {
            PresenceRegistry.close(instance);
            RealtimeBus.close(instance);
         }
         QContext.clear();
         QJavalinImplementation.setSessionCookieHttpOnly(false);
         MemoryRecordStore.getInstance().reset();
         CollaborationDevServer.MemoryStorage.clear();
         restore("collab.dev.user", oldUser);
         restore("collab.dev.port", oldPort);
      }
   }



   /*******************************************************************************
    ** Read through normal permissions, including the seeded admin-only tables.
    *******************************************************************************/
   private static List<QRecord> rows(String table) throws Exception
   {
      return (new QueryAction().execute(new QueryInput(table)).getRecords());
   }



   /*******************************************************************************
    ** Each request gets a fresh MOCK session with the currently selected user.
    *******************************************************************************/
   private static HttpResponse<byte[]> get(HttpClient client, String url) throws Exception
   {
      return (client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
         .header("Cookie", "sessionUUID=" + UUID.randomUUID()).GET().build(), HttpResponse.BodyHandlers.ofByteArray()));
   }



   /*******************************************************************************
    ** Do not leak demo properties into the rest of the test suite.
    *******************************************************************************/
   private static void restore(String name, String value)
   {
      if(value == null)
      {
         System.clearProperty(name);
      }
      else
      {
         System.setProperty(name, value);
      }
   }
}
