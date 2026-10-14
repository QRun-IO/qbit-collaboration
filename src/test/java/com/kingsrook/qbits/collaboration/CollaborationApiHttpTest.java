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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.collaboration.attachments.AttachmentStorage;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleCustomizerInterface;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;
import com.kingsrook.qqq.middleware.javalin.QJavalinImplementation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Real HTTP, real memory actions, and real parent locks; only file storage is fake.
 *******************************************************************************/
class CollaborationApiHttpTest extends PersonalServiceFixture
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
   private QApplicationJavalinServer server;
   private String baseUrl;
   private String sessionSuffix;

   /*******************************************************************************
    ** Select a free loopback port and use the same mock-auth customization as hosts.
    *******************************************************************************/
   @BeforeEach
   void start() throws Exception
   {
      MemoryStorage.objects.clear();
      MemoryStorage.lastGet = null;
      MemoryStorage.fail = false;
      config.withAttachmentStorage(new QCodeReference(MemoryStorage.class)).withUserSessionProvider(new QCodeReference(CarolSessions.class));
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK)
         .withCustomizer(new QCodeReference(TestSessions.class)));
      int port;
      try(ServerSocket socket = new ServerSocket(0))
      {
         port = socket.getLocalPort();
      }
      sessionSuffix = "-" + UUID.randomUUID();
      baseUrl = "http://127.0.0.1:" + port + "/qqq/v1/qbit/collaboration";
      server = new QApplicationJavalinServer(new AbstractQQQApplication()
      {
         @Override
         public QInstance defineQInstance()
         {
            return (instance);
         }
      }).withPort(port).withServeFrontendNext(false).withServeFrontendMaterialDashboard(false)
         .withServeLegacyUnversionedMiddlewareAPI(false).withStrictUiApiRoutes(true);
      server.start();
   }

   /*******************************************************************************
    ** Release HTTP and global server resources between tests.
    *******************************************************************************/
   @AfterEach
   void stop()
   {
      if(server != null)
      {
         server.stop();
      }
      client.close();
      QJavalinImplementation.setSessionCookieHttpOnly(false);
      MemoryStorage.objects.clear();
   }

   /*******************************************************************************
    ** Sharing validates recipients and access requests never reveal parent existence.
    *******************************************************************************/
   @Test
   void sharingAndAccessRequests() throws Exception
   {
      assertThat(ok("GET", "/records/document/001/link", null).get("label").asText()).isEqualTo("First document");
      var preview = ok("POST", "/records/document/1/shares/preview", "{\"toUserIds\":[\"alice\",\"unknown\"]}");
      assertThat(preview.at("/recipients/0/canRead").asBoolean()).isTrue();
      assertThat(preview.at("/recipients/1/canRead").isNull()).isTrue();
      assertThat(ok("POST", "/records/document/1/shares", "{\"toUserIds\":[\"alice\"],\"message\":\"Review\"}").get("items")).hasSize(1);
      assertError(400, "POST", "/records/document/1/shares", "{\"toUserIds\":[]}", "alice");
      assertError(403, "POST", "/records/document/1/shares/preview", "{\"toUserIds\":[\"alice\"]}", "reader");
      assertError(404, "GET", "/records/document/3/link", null, "alice");
      var request = ok("POST", "/access-requests", "{\"tableName\":\"document\",\"recordId\":\"999\",\"message\":\"Please\"}");
      assertThat(request.get("status").asText()).isEqualTo("OPEN");
      assertThat(ok("GET", "/access-requests/mine", null).get("items")).hasSize(1);
      assertThat(json(request("GET", "/access-requests/mine", null, "reader"), 200).get("items")).isEmpty();
      assertError(400, "POST", "/access-requests", "{\"tableName\":\"document\"}", "alice");
   }

   /*******************************************************************************
    ** Tasks support partial edits, status transitions, caller lists and assignment.
    *******************************************************************************/
   @Test
   void tasksAssignmentsAndMyWork() throws Exception
   {
      var task = ok("POST", "/records/document/1/tasks", "{\"title\":\"Review\",\"dueAt\":\"2026-12-01T00:00:00Z\"}");
      String path = "/tasks/" + task.get("id").asInt();
      assertThat(ok("GET", "/records/document/1/tasks", null).get("items")).hasSize(1);
      assertThat(ok("GET", "/tasks/mine", null).get("items")).hasSize(1);
      assertThat(ok("PATCH", path, "{\"notes\":\"Keep title\"}").get("title").asText()).isEqualTo("Review");
      assertThat(ok("PATCH", path, "{\"status\":\"DONE\"}").get("status").asText()).isEqualTo("DONE");
      assertThat(ok("GET", "/tasks/mine?status=DONE", null).get("items")).hasSize(1);
      assertThat(ok("GET", "/records/document/1/tasks", null).get("items")).isEmpty();
      assertThat(ok("GET", "/records/document/1/tasks?includeDone=true", null).get("items")).hasSize(1);
      ok("PATCH", path, "{\"status\":\"OPEN\"}");
      ok("PATCH", path, "{\"status\":\"CANCELLED\"}");
      ok("PATCH", path, "{\"status\":\"OPEN\",\"dueAt\":null}");
      assertThat(ok("PUT", "/records/document/1/assignment", "{\"ownerUserId\":\"alice\",\"assigneeUserId\":\"alice\"}").get("ownerUserId").asText()).isEqualTo("alice");
      var work = ok("GET", "/my-work", null);
      assertThat(work.get("openTaskCount").asInt()).isEqualTo(1);
      assertThat(work.at("/assignedRecordsByTable/document").asInt()).isEqualTo(1);
      assertThat(work.get("tasks")).hasSize(1);
      assertError(400, "PATCH", path, "{\"status\":\"INVALID\",\"title\":\"Not saved\"}", "alice");
      assertError(400, "PATCH", path, "{\"dueAt\":\"invalid\"}", "alice");
      assertError(403, "PATCH", path, "{\"title\":\"Denied\"}", "reader");
      assertError(404, "PATCH", path, "{}", "outsider");
      assertError(404, "POST", "/records/document/3/tasks", "{}", "alice");
      assertError(403, "PUT", "/records/document/1/assignment", "{}", "reader");
      assertError(400, "GET", "/tasks/mine?status=UNKNOWN", null, "alice");
      assertError(400, "GET", "/records/document/1/tasks?includeDone=wrong", null, "alice");
      assertError(400, "POST", "/records/document/1/tasks", "{\"title\":5}", "alice");
   }

   /*******************************************************************************
    ** Every payload that carries a user id also carries a display name, resolved in
    ** one batch per response; ids with no directory entry fall back to themselves.
    *******************************************************************************/
   @Test
   void payloadsCarryDisplayNames() throws Exception
   {
      assertThat(json(request("GET", "/people/carol", null, "carol"), 200).get("displayName").asText()).isEqualTo("Carol Diaz");

      var task = ok("POST", "/records/document/1/tasks", "{\"title\":\"Review\",\"assigneeUserId\":\"carol\"}");
      assertThat(task.get("assigneeDisplayName").asText()).isEqualTo("Carol Diaz");
      assertThat(task.get("createdByDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(task.get("completedByDisplayName").isNull()).isTrue();
      assertThat(ok("GET", "/records/document/1/tasks", null).at("/items/0/assigneeDisplayName").asText()).isEqualTo("Carol Diaz");
      assertThat(ok("GET", "/my-work", null).at("/tasks").size()).isZero();
      var done = ok("PATCH", "/tasks/" + task.get("id").asInt(), "{\"status\":\"DONE\"}");
      assertThat(done.get("completedByDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(json(request("GET", "/tasks/mine?status=DONE", null, "carol"), 200).at("/items/0/createdByDisplayName").asText()).isEqualTo("Alice Morgan");

      JsonNode upload = json(upload("alice", "report.txt", "hello", "/records/document/1/attachments"), 200);
      assertThat(upload.get("createdByDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(ok("GET", "/records/document/1/attachments", null).at("/items/0/createdByDisplayName").asText()).isEqualTo("Alice Morgan");

      JsonNode comment = ok("POST", "/records/document/1/comments", "{\"body\":\"First\"}");
      assertThat(comment.get("authorDisplayName").asText()).isEqualTo("Alice Morgan");
      ok("PATCH", "/comments/" + comment.get("id").asInt(), "{\"body\":\"Edited\"}");
      assertThat(ok("GET", "/comments/" + comment.get("id").asInt() + "/history", null).at("/items/0/editedByDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(ok("GET", "/records/document/1/followers", null).findValuesAsText("userDisplayName")).contains("Alice Morgan");

      JsonNode shared = ok("POST", "/records/document/1/shares", "{\"toUserIds\":[\"carol\"],\"message\":\"Look\"}");
      assertThat(shared.at("/items/0/fromDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(shared.at("/items/0/toDisplayName").asText()).isEqualTo("Carol Diaz");

      JsonNode request = ok("POST", "/access-requests", "{\"tableName\":\"document\",\"recordId\":\"999\",\"message\":\"Please\"}");
      assertThat(request.get("requesterDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(request.get("handledByDisplayName").isNull()).isTrue();
      assertThat(ok("GET", "/access-requests/mine", null).at("/items/0/requesterDisplayName").asText()).isEqualTo("Alice Morgan");

      JsonNode assignment = ok("PUT", "/records/document/1/assignment", "{\"ownerUserId\":\"alice\",\"assigneeUserId\":\"carol\"}");
      assertThat(assignment.get("ownerDisplayName").asText()).isEqualTo("Alice Morgan");
      assertThat(assignment.get("assigneeDisplayName").asText()).isEqualTo("Carol Diaz");
   }

   /*******************************************************************************
    ** Presence endpoints share parent checks and return typed merged snapshots.
    *******************************************************************************/
   @Test
   void presenceHttp() throws Exception
   {
      String body = "{\"connectionId\":\"tab-one\",\"table\":\"document\",\"pk\":\"1\",\"mode\":\"EDITING\",\"fieldName\":\"title\"}";
      assertThat(ok("POST", "/presence", body).get("viewers")).hasSize(1);
      assertThat(ok("GET", "/presence?table=document&pk=1", null).at("/viewers/0/mode").asText()).isEqualTo("EDITING");
      assertThat(ok("GET", "/presence?table=document", null).at("/viewers/0/fieldName").isNull()).isTrue();
      assertError(404, "GET", "/presence?table=document&pk=3", null, "alice");
      assertError(404, "POST", "/presence", body, "outsider");
      assertError(400, "POST", "/presence", "{\"connectionId\":\"x\",\"table\":\"document\",\"mode\":\"BAD\"}", "alice");
      assertError(400, "GET", "/presence", null, "alice");
      assertThat(ok("DELETE", "/presence", "{\"connectionId\":\"tab-one\",\"table\":\"document\",\"pk\":\"1\"}").get("success").asBoolean()).isTrue();
      assertThat(ok("GET", "/presence?table=document&pk=1", null).get("viewers")).isEmpty();
   }

   /*******************************************************************************
    ** Every personal operation and offset cursor runs through the HTTP boundary.
    *******************************************************************************/
   @Test
   void favoritesAndRecents() throws Exception
   {
      String first = "{\"targetType\":\"RECORD\",\"targetName\":\"document\",\"recordId\":\"1\"}";
      String second = first.replace("\"1\"", "\"2\"");
      assertThat(ok("PUT", "/favorites", first).get("success").asBoolean()).isTrue();
      ok("PUT", "/favorites", second);
      JsonNode page = ok("GET", "/favorites?limit=1", null);
      assertThat(page.get("items")).hasSize(1);
      assertThat(page.at("/items/0/label").asText()).isEqualTo("First document");
      assertThat(page.at("/items/0/available").asBoolean()).isTrue();
      JsonNode next = ok("GET", "/favorites?limit=1&cursor=" + page.get("nextCursor").asText(), null);
      int secondId = next.at("/items/0/id").asInt();
      assertThat(next.at("/items/0/recordId").asText()).isEqualTo("2");
      assertThat(next.get("nextCursor").isNull()).isTrue();
      ok("PATCH", "/favorites/order", "{\"ids\":[" + secondId + "],\"folders\":{\"" + secondId + "\":\"Work\"}}");
      assertThat(ok("GET", "/favorites", null).at("/items/0/folder").asText()).isEqualTo("Work");
      ok("DELETE", "/favorites", first);
      assertThat(ok("GET", "/favorites", null).get("items")).hasSize(1);
      ok("POST", "/recents", first);
      assertThat(ok("GET", "/recents", null).at("/items/0/recordId").asText()).isEqualTo("1");
      assertThat(request("GET", "/favorites", null, "reader").body()).contains("\"items\":[]");
      assertError(400, "GET", "/favorites?cursor=broken", null, "alice");
      assertError(400, "PATCH", "/favorites/order", "{\"ids\":[99999]}", "alice");
      assertError(400, "PUT", "/favorites", "{\"targetType\":\"WRONG\",\"targetName\":\"document\"}", "alice");
      assertError(404, "PUT", "/favorites", first.replace("\"1\"", "\"3\""), "alice");
   }

   /*******************************************************************************
    ** Multipart bytes, metadata, temporary URLs, rename, deletion and failure safety.
    *******************************************************************************/
   @Test
   void attachments() throws Exception
   {
      JsonNode uploaded = json(upload("alice", "report.txt", "hello", "/records/document/1/attachments"), 200);
      int id = uploaded.get("id").asInt();
      assertThat(uploaded.get("fileName").asText()).isEqualTo("report.txt");
      assertThat(uploaded.get("sizeBytes").asLong()).isEqualTo(5);
      assertThat(uploaded.get("description").asText()).isEqualTo("A report");
      assertThat(uploaded.get("createdByUserId").asText()).isEqualTo("alice");
      assertThat(uploaded.has("storageKey")).isFalse();
      assertThat(MemoryStorage.objects.values()).singleElement().satisfies(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello"));
      assertThat(ok("GET", "/records/document/1/attachments", null).get("items")).hasSize(1);
      assertThat(ok("GET", "/attachments/" + id + "/download", null).get("url").asText()).startsWith("https://files.example.invalid/");
      assertThat(MemoryStorage.lastGet.getDownloadAs()).isEqualTo("report.txt");
      assertThat(MemoryStorage.lastGet.getTtl()).isEqualTo(Duration.ofMinutes(5));
      ok("GET", "/attachments/" + id + "/preview", null);
      assertThat(MemoryStorage.lastGet.getDownloadAs()).isNull();
      assertThat(ok("PATCH", "/attachments/" + id, "{\"fileName\":\"renamed.txt\",\"description\":\"\"}").get("fileName").asText()).isEqualTo("renamed.txt");
      assertError(403, "PATCH", "/attachments/" + id, "{\"description\":\"no\"}", "reader");
      assertError(404, "GET", "/attachments/" + id + "/download", null, "outsider");
      assertError(400, "POST", "/records/document/1/attachments", "{}", "alice");
      assertThat(upload("reader", "x.txt", "x", "/records/document/1/attachments").statusCode()).isEqualTo(403);
      assertThat(upload("alice", "x.txt", "x", "/records/document/3/attachments").statusCode()).isEqualTo(404);
      config.withDefaultMaxBytes(2L);
      assertThat(upload("alice", "x.txt", "toolarge", "/records/document/1/attachments").statusCode()).isEqualTo(400);
      assertThat(MemoryStorage.objects).hasSize(1);
      config.withDefaultMaxBytes(1000L).withBlockedExtensions(List.of("exe"));
      assertThat(upload("alice", "x.exe", "x", "/records/document/1/attachments").statusCode()).isEqualTo(400);
      MemoryStorage.fail = true;
      assertThat(request("GET", "/attachments/" + id + "/download", null, "alice").statusCode()).isEqualTo(500);
      MemoryStorage.fail = false;
      ok("DELETE", "/attachments/" + id, null);
      assertThat(MemoryStorage.objects).isEmpty();
      assertError(404, "GET", "/attachments/" + id + "/preview", null, "alice");
   }

   /*******************************************************************************
    ** Vocabulary creation and record associations preserve caller permissions.
    *******************************************************************************/
   @Test
   void tags() throws Exception
   {
      JsonNode tag = ok("POST", "/tags", "{\"table\":\"document\",\"label\":\"Urgent\",\"color\":\"red\"}");
      assertThat(tag.get("approved").asBoolean()).isTrue();
      assertThat(ok("GET", "/tags?table=document&q=urg", null).at("/items/0/name").asText()).isEqualTo("urgent");
      int id = tag.get("id").asInt();
      assertThat(ok("PUT", "/records/document/1/tags", "{\"add\":[" + id + "]}").get("items")).hasSize(1);
      assertThat(ok("GET", "/records/document/1/tags", null).at("/items/0/id").asInt()).isEqualTo(id);
      ok("PUT", "/records/document/1/tags", "{\"remove\":[" + id + "]}");
      assertThat(ok("GET", "/records/document/1/tags", null).get("items")).isEmpty();
      assertError(403, "POST", "/tags", "{\"table\":\"document\",\"label\":\"Denied\"}", "reader");
      assertError(400, "POST", "/tags", "{\"table\":\"document\",\"label\":\"\"}", "alice");
      assertError(400, "PUT", "/records/document/1/tags", "{\"add\":[null]}", "alice");
      assertError(400, "PUT", "/records/document/1/tags", "{\"add\":[1.5]}", "alice");
   }

   /*******************************************************************************
    ** The picker sends tag ids through the v1 table query as any JSON number or string.
    *******************************************************************************/
   @Test
   void tagFilterByIdThroughTableQuery() throws Exception
   {
      RuntimeMetaDataOverlay.refresh(instance);
      int a = ok("POST", "/tags", "{\"table\":\"document\",\"label\":\"Alpha\"}").get("id").asInt();
      int b = ok("POST", "/tags", "{\"table\":\"document\",\"label\":\"Beta\"}").get("id").asInt();
      ok("PUT", "/records/document/1/tags", "{\"add\":[" + a + "," + b + "]}");
      ok("PUT", "/records/document/2/tags", "{\"add\":[" + a + "]}");
      for(String ida : List.of(a + "", "\"" + a + "\"", a + ".0"))
      {
         assertThat(tagQuery("IN", "[" + ida + "]")).containsExactlyInAnyOrder(1, 2);
         assertThat(tagQuery("EQUALS", "[" + ida + "]")).containsExactlyInAnyOrder(1, 2);
         assertThat(tagQuery("NOT_IN", "[" + ida + "]")).isEmpty();
      }
      for(String idb : List.of(b + "", "\"" + b + "\"", b + ".0"))
      {
         assertThat(tagQuery("CONTAINS_ALL", "[" + a + "," + idb + "]")).containsExactly(1);
         assertThat(tagQuery("NOT_IN", "[" + idb + "]")).containsExactly(2);
      }
      assertThat(tagQuery("IN", "[\"alpha\"]")).containsExactlyInAnyOrder(1, 2);
   }

   /*******************************************************************************
    ** Run the v1 table query filtered on the virtual tags field; return matching ids.
    *******************************************************************************/
   private List<Integer> tagQuery(String operator, String values) throws Exception
   {
      String serverUrl = baseUrl.substring(0, baseUrl.indexOf("/qbit/"));
      String body = "{\"filter\":{\"criteria\":[{\"fieldName\":\"collaborationTags\",\"operator\":\"" + operator + "\",\"values\":" + values + "}],\"orderBys\":[{\"fieldName\":\"id\"}]}}";
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(serverUrl + "/table/document/query")).timeout(Duration.ofSeconds(20))
         .header("Cookie", "sessionUUID=alice" + sessionSuffix).header("Content-Type", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      JsonNode result = json(response, 200);
      List<Integer> ids = new java.util.ArrayList<>();
      for(JsonNode record : result.get("records"))
      {
         ids.add(record.at("/values/id").asInt());
      }
      return (ids);
   }

   /*******************************************************************************
    ** Comments, revisions, mentions, follow settings and the merged timeline.
    *******************************************************************************/
   @Test
   void conversationsAndTimeline() throws Exception
   {
      ok("DELETE", "/records/document/1/follow", null);
      assertThat(ok("GET", "/records/document/1/follow", null).get("following").asBoolean()).isFalse();
      JsonNode root = ok("POST", "/records/document/1/comments", "{\"body\":\"First\"}");
      int id = root.get("id").asInt();
      assertThat(root.get("authorUserId").asText()).isEqualTo("alice");
      ok("POST", "/records/document/1/comments", "{\"body\":\"Second\"}");
      JsonNode page = ok("GET", "/records/document/1/comments?limit=1", null);
      assertThat(page.get("threads")).hasSize(1);
      String cursor = page.get("nextCursor").asText();
      JsonNode next = ok("GET", "/records/document/1/comments?limit=1&cursor=" + cursor, null);
      assertThat(next.at("/threads/0/id").asInt()).isEqualTo(id);
      assertThat(next.get("nextCursor").isNull()).isTrue();
      ok("POST", "/records/document/1/comments", "{\"body\":\"Reply\",\"parentCommentId\":" + id + "}");
      assertThat(ok("GET", "/records/document/1/comments", null).at("/threads/0/replies")).hasSize(1);
      ok("PATCH", "/comments/" + id, "{\"body\":\"Edited @[Alice](user:alice)\"}");
      assertThat(ok("GET", "/comments/" + id + "/history", null).at("/items/0/body").asText()).isEqualTo("First");
      ok("POST", "/comments/" + id + "/resolve", null);
      assertThat(ok("GET", "/records/document/1/comments", null).at("/threads/0/resolved").asBoolean()).isTrue();
      ok("POST", "/comments/" + id + "/reopen", null);
      assertThat(ok("GET", "/records/document/1/comments", null).at("/threads/0/resolved").asBoolean()).isFalse();
      assertThat(json(request("GET", "/people/carol", null, "carol"), 200).get("displayName").asText()).isEqualTo("Carol Diaz");
      JsonNode people = ok("GET", "/people?q=carol", null);
      assertThat(people.at("/items/0/userId").asText()).isEqualTo("carol");
      assertThat(ok("GET", "/people?q=alice", null).get("items")).as("the caller is not offered to themself").isEmpty();
      assertThat(ok("GET", "/people", null).findValuesAsText("userId")).doesNotContain("alice").contains("carol");
      assertThat(ok("GET", "/people/alice", null).get("displayName").asText()).isEqualTo("Alice Morgan");
      assertThat(ok("GET", "/mentions/candidates?table=document&pk=1&q=alice", null).get("items")).isEmpty();
      assertThat(ok("GET", "/mentions/candidates?table=document&pk=1", null).findValuesAsText("userId")).doesNotContain("alice");
      JsonNode candidate = ok("GET", "/mentions/candidates?table=document&pk=1&q=carol", null).at("/items/0");
      assertThat(candidate.at("/person/userId").asText()).isEqualTo("carol");
      assertThat(candidate.get("canRead").asText()).isEqualTo("true");
      assertThat(ok("GET", "/records/document/1/follow", null).at("/follow/reason").asText()).isEqualTo("COMMENTED");
      JsonNode state = ok("PUT", "/records/document/1/follow", "{\"notifyOnChange\":false,\"notifyOnComment\":true}");
      assertThat(state.at("/follow/notifyOnChange").asBoolean()).isFalse();
      assertThat(ok("GET", "/records/document/1/followers", null).at("/items/0/userId").asText()).isEqualTo("alice");
      JsonNode timeline = ok("GET", "/records/document/1/timeline?limit=1&types=ACTIVITY", null);
      assertThat(timeline.get("items")).hasSize(1);
      assertThat(timeline.at("/items/0/kind").asText()).isEqualTo("ACTIVITY");
      String before = timeline.get("nextCursor").asText();
      JsonNode older = ok("GET", "/records/document/1/timeline?before=" + before + "&types=ACTIVITY", null);
      assertThat(older.at("/items/0/id").asLong()).isNotEqualTo(timeline.at("/items/0/id").asLong());
      ok("DELETE", "/comments/" + id, null);
      assertThat(ok("GET", "/records/document/1/comments", null).at("/threads/0/deleted").asBoolean()).isTrue();
      ok("DELETE", "/records/document/1/follow", null);
      assertThat(ok("GET", "/records/document/1/follow", null).get("follow").isNull()).isTrue();
      assertError(404, "GET", "/people/missing", null, "alice");
      assertError(403, "PATCH", "/comments/" + id, "{\"body\":\"no\"}", "reader");
      assertError(400, "GET", "/records/document/1/timeline?cursor=" + cursor, null, "alice");
   }

   /*******************************************************************************
    ** Missing and locked parents are indistinguishable, across all record panels.
    *******************************************************************************/
   @Test
   void authorizationAndValidation() throws Exception
   {
      for(String feature : List.of("attachments", "tags", "comments", "follow", "followers", "timeline"))
      {
         HttpResponse<String> hidden = request("GET", "/records/document/3/" + feature, null, "alice");
         HttpResponse<String> absent = request("GET", "/records/document/999/" + feature, null, "alice");
         assertThat(hidden.statusCode()).isEqualTo(404);
         assertThat(hidden.body()).isEqualTo(absent.body());
         assertThat(json(hidden, 404).get("error").asText()).isEqualTo("Not found");
      }
      assertError(404, "POST", "/records/document/3/comments", "not json", "alice");
      assertError(404, "GET", "/mentions/candidates?table=document&pk=3", null, "alice");
      assertError(403, "POST", "/records/document/1/comments", "{\"body\":\"Denied\"}", "reader");
      assertError(400, "POST", "/records/document/1/comments", "{\"body\":\"\"}", "alice");
      for(String body : List.of("not json", "[]", "null", "{\"body\":42}\", \"{}"))
      {
         assertError(400, "POST", "/records/document/1/comments", body, "alice");
      }
      for(String limit : List.of("0", "101", "bad"))
      {
         assertError(400, "GET", "/records/document/1/comments?limit=" + limit, null, "alice");
      }
      ok("GET", "/records/document/1/comments?limit=100", null);
      ok("GET", "/records/document/1/timeline?limit=100", null);
      ok("GET", "/people?limit=100", null);
      assertError(400, "GET", "/records/document/1/comments?cursor=invalid", null, "alice");
      assertError(400, "GET", "/records/document/1/timeline?before=a&cursor=b", null, "alice");
      assertError(400, "GET", "/tags", null, "alice");
      assertError(400, "GET", "/comments/invalid/history", null, "alice");
      assertError(404, "GET", "/comments/999/history", null, "alice");
   }

   /*******************************************************************************
    ** Send JSON with a persistent caller cookie; credentials travel over HTTP.
    *******************************************************************************/
   private HttpResponse<String> request(String method, String path, String body, String caller) throws Exception
   {
      return (client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
         .header("Cookie", "sessionUUID=" + caller + sessionSuffix).header("Content-Type", "application/json")
         .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()));
   }

   /*******************************************************************************
    ** Assert transport status before interpreting the returned wire body.
    *******************************************************************************/
   private JsonNode json(HttpResponse<String> response, int status) throws Exception
   {
      assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
      return (JSON.readTree(response.body()));
   }

   /*******************************************************************************
    ** Successful request by the record author.
    *******************************************************************************/
   private JsonNode ok(String method, String path, String body) throws Exception
   {
      return (json(request(method, path, body, "alice"), 200));
   }

   /*******************************************************************************
    ** The live server publishes the same typed extension that its routes execute.
    *******************************************************************************/
   @Test
   void publishedDescription() throws Exception
   {
      String serverUrl = baseUrl.substring(0, baseUrl.indexOf("/qqq/"));
      JsonNode description = json(client.send(HttpRequest.newBuilder(URI.create(serverUrl + "/qqq/v1/openapi.json")).GET().build(),
         HttpResponse.BodyHandlers.ofString()), 200);
      JsonNode schemas = description.at("/components/schemas");
      assertThat(schemas.has("CollaborationCommentPageV1")).isTrue();
      assertThat(schemas.at("/CollaborationFollowV1/properties/reason/enum")).hasSize(6);
      assertThat(description.get("paths").has("/qqq/v1/qbit/collaboration/records/{table}/{pk}/attachments")).isTrue();
      assertThat(description.get("paths").has("/qqq/v1/qbit/collaboration/my-work")).isTrue();
      JsonNode version = json(client.send(HttpRequest.newBuilder(URI.create(serverUrl + "/qqq/v1/version")).GET().build(),
         HttpResponse.BodyHandlers.ofString()), 200);
      assertThat(version.at("/extensions/0/name").asText()).isEqualTo("collaboration");
      assertThat(version.at("/extensions/0/version").asText()).isEqualTo("1.0.0");
   }

   /*******************************************************************************
    ** Framework errors always contain a human-readable error property.
    *******************************************************************************/
   private void assertError(int status, String method, String path, String body, String caller) throws Exception
   {
      assertThat(json(request(method, path, body, caller), status).get("error").asText()).isNotBlank();
   }

   /*******************************************************************************
    ** Build an actual multipart body without filesystem fixtures.
    *******************************************************************************/
   private HttpResponse<String> upload(String caller, String name, String content, String path) throws Exception
   {
      String boundary = "CollaborationBoundary";
      String payload = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\nA report\r\n"
         + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: text/plain\r\n\r\n"
         + content + "\r\n--" + boundary + "--\r\n";
      return (client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
         .header("Cookie", "sessionUUID=" + caller + sessionSuffix).header("Content-Type", "multipart/form-data; boundary=" + boundary)
         .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString()));
   }

   /*******************************************************************************
    ** Test auth maps stable cookies to distinct users, grants and tenant locks.
    *******************************************************************************/
   public static class TestSessions implements QAuthenticationModuleCustomizerInterface
   {
      @Override
      public void customizeSession(QInstance qInstance, QSession session, Map<String, Object> context)
      {
         String caller = session.getUuid().split("-")[0];
         session.withUser(new QUser().withIdReference(caller).withFullName(switch(caller)
         {
            case "alice" -> "Alice Morgan";
            case "carol" -> "Carol Diaz";
            default -> caller;
         }));
         session.withPermissions("document.read").withSecurityKeyValue("tenant", caller.equals("outsider") ? "b" : "a");
         if(caller.equals("alice") || caller.equals("carol"))
         {
            session.withPermissions("document.write");
         }
      }
   }

   /*******************************************************************************
    ** Carol is the one other person who can open the shared documents.
    *******************************************************************************/
   public static class CarolSessions implements UserSessionProviderInterface
   {
      @Override
      public Optional<QSession> sessionForUser(String userId)
      {
         return (Optional.ofNullable("carol".equals(userId) ? new QSession().withUser(new QUser().withIdReference("carol")).withPermissions("document.read", "document.write")
            .withSecurityKeyValue("tenant", "a") : null));
      }
   }

   /*******************************************************************************
    ** Public no-arg adapter loaded through host configuration, with in-memory bytes.
    *******************************************************************************/
   public static class MemoryStorage extends AttachmentStorage
   {
      static final Map<String, byte[]> objects = new ConcurrentHashMap<>();
      static volatile PresignGet lastGet;
      static volatile boolean fail;

      @Override
      public long maxBytes(String name)
      {
         return (1000000);
      }

      @Override
      public void put(String name, PutRequest request)
      {
         try
         {
            objects.put(request.getKey(), request.getStream().readAllBytes());
         }
         catch(IOException e)
         {
            throw (new IllegalStateException(e));
         }
      }

      @Override
      public void delete(String name, String key)
      {
         objects.remove(key);
      }

      @Override
      public URL presign(String name, PresignGet request)
      {
         if(fail)
         {
            throw (new IllegalStateException("Test provider unavailable"));
         }
         lastGet = request;
         try
         {
            return (URI.create("https://files.example.invalid/" + request.getKey()).toURL());
         }
         catch(Exception e)
         {
            throw (new IllegalStateException(e));
         }
      }
   }
}
