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


import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationQBitProducer;
import com.kingsrook.qbits.collaboration.assignment.AssignmentService;
import com.kingsrook.qbits.collaboration.attachments.AttachmentStorage;
import com.kingsrook.qbits.collaboration.attachments.AttachmentsService;
import com.kingsrook.qbits.collaboration.comments.CommentsService;
import com.kingsrook.qbits.collaboration.customfields.FieldDefinitions;
import com.kingsrook.qbits.collaboration.favorites.FavoritesService;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.RecordingNotifier;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.presence.PresenceRegistry;
import com.kingsrook.qbits.collaboration.recents.RecentsService;
import com.kingsrook.qbits.collaboration.sharing.AccessRequestService;
import com.kingsrook.qbits.collaboration.sharing.PermissionHolderDirectory;
import com.kingsrook.qbits.collaboration.sharing.SharingService;
import com.kingsrook.qbits.collaboration.tags.TagsService;
import com.kingsrook.qbits.collaboration.tasks.TasksService;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.collaboration.RuntimeMetaDataOverlay;
import com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.audits.AuditsMetaDataProvider;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.audits.QAuditRules;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordOwnership;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleCustomizerInterface;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.realtime.RealtimeBus;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;
import io.javalin.config.JavalinConfig;


/*******************************************************************************
 ** Disposable Sales CRM for viewing collaboration in the Next dashboard.
 ** Build the Next export with QQQ_NEXT_OUTPUT=export pnpm build, then place out/
 ** under EXPORT/next-dashboard/ (EXPORT, not next-dashboard, is on the classpath).
 ** After mvnw.cmd test-compile dependency:build-classpath -Dmdep.includeScope=test
 ** -Dmdep.outputFile=target/demo-classpath.txt, run:
 ** java -Dqqq.javalin.frontend=next -Dcollab.dev.user=alice -Dcollab.dev.port=8098
 ** -cp "target/test-classes;target/classes;DEPS;EXPORT" com.kingsrook.qbits.collaboration.devserver.CollaborationDevServer
 ** DEPS is the content of target/demo-classpath.txt. See README for PowerShell.
 *******************************************************************************/
public class CollaborationDevServer extends AbstractQQQApplication
{
   private final QInstance instance;
   static final List<String> USERS = List.of("alice", "bob", "admin");
   static final String USER_COOKIE = "collab_dev_user";
   private static final ThreadLocal<String> REQUEST_USER = new ThreadLocal<>();



   /*******************************************************************************
    ** Keep the already seeded instance when Javalin initializes the application.
    *******************************************************************************/
   public CollaborationDevServer(QInstance instance)
   {
      this.instance = instance;
   }



   /*******************************************************************************
    ** No HTTP server is needed to build or test the demo.
    *******************************************************************************/
   @Override
   public QInstance defineQInstance()
   {
      return (instance);
   }



   /*******************************************************************************
    ** Select a demo identity for this JVM; restarting resets all demo data.
    *******************************************************************************/
   public static void main(String[] args) throws Exception
   {
      String user = System.getProperty("collab.dev.user", "alice");
      session(user);
      int port = Integer.parseInt(System.getProperty("collab.dev.port", "8098"));
      QInstance instance = buildInstance(port);
      seed(instance);
      QApplicationJavalinServer server = server(instance, port, true);
      server.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() ->
      {
         server.stop();
         PresenceRegistry.close(instance);
         RealtimeBus.close(instance);
      }));
      System.out.println("COLLABORATION DEV SERVER READY http://localhost:" + port + "/?createMockSession=true");
      System.out.println("Person: http://localhost:" + port + "/app/person/1  Order: http://localhost:" + port + "/app/sales_order/1");
      System.out.println("Users (-Dcollab.dev.user): alice (Sales), bob (people read/comment), admin (all + collaboration.admin). Current: " + user);
      Thread.currentThread().join();
   }



   /*******************************************************************************
    ** Share the actual startup configuration with the HTTP smoke test.
    *******************************************************************************/
   static QApplicationJavalinServer server(QInstance instance, int port, boolean frontend)
   {
      return (new QApplicationJavalinServer(new CollaborationDevServer(instance)).withPort(port)
         .withServeFrontendNext(frontend).withServeFrontendMaterialDashboard(false)
         .withServeLegacyUnversionedMiddlewareAPI(false).withStrictUiApiRoutes(true)
         .withJavalinConfigCustomizer(config ->
         {
            config.jetty.host = "127.0.0.1";
            userSelection(config);
            MemoryStorage.routes(config);
         }));
   }



   /*******************************************************************************
    ** Pick the demo user per browser, not per JVM. A request carrying ?user=bob (for
    ** example the page load of /?createMockSession=true&user=bob) is remembered in a
    ** cookie, and every request exposes its choice to the session customizer, which
    ** QQQ calls without the request. Without either, the collab.dev.user default holds.
    *******************************************************************************/
   static void userSelection(JavalinConfig config)
   {
      config.routes.before(context ->
      {
         String requested = context.queryParam("user");
         if(requested != null && USERS.contains(requested))
         {
            context.cookie(USER_COOKIE, requested);
            REQUEST_USER.set(requested);
            return;
         }
         String remembered = context.cookie(USER_COOKIE);
         REQUEST_USER.set(remembered != null && USERS.contains(remembered) ? remembered : null);
      });
      config.routes.after(context -> REQUEST_USER.remove());
   }



   /*******************************************************************************
    ** The user for the request being handled on this thread, else the JVM default.
    *******************************************************************************/
   static String selectedUser()
   {
      String requested = REQUEST_USER.get();
      return (requested != null ? requested : System.getProperty("collab.dev.user", "alice"));
   }



   /*******************************************************************************
    ** Normal host metadata, plus the same collaboration installation as a real app.
    *******************************************************************************/
   static QInstance buildInstance() throws Exception
   {
      return (buildInstance(Integer.parseInt(System.getProperty("collab.dev.port", "8098"))));
   }



   /*******************************************************************************
    ** The port is part of the configuration: shared links are absolute URLs to this server.
    *******************************************************************************/
   static QInstance buildInstance(int port) throws Exception
   {
      QInstance instance = new QInstance().withInstanceDefaultAuthentication(new QAuthenticationMetaData()
         .withName("mock").withType(QAuthenticationType.MOCK).withCustomizer(new QCodeReference(DemoSessions.class)));
      instance.setEnforcePermissionsInCoreActions(true);
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.addPossibleValueSource(new QPossibleValueSource().withName("sales_user").withType(QPossibleValueSourceType.ENUM)
         .withEnumValues(List.of(new QPossibleValue<>("alice", "Alice Morgan"), new QPossibleValue<>("bob", "Bob Chen"), new QPossibleValue<>("admin", "Demo Administrator"))));
      instance.addPossibleValueSource(new QPossibleValueSource().withName("sales_order_status").withType(QPossibleValueSourceType.ENUM)
         .withEnumValues(List.of(new QPossibleValue<>("Draft"), new QPossibleValue<>("Confirmed"), new QPossibleValue<>("Processing"), new QPossibleValue<>("Shipped"), new QPossibleValue<>("Delivered"))));
      instance.addTable(baseTable("person", "People", "person")
         .withRecordLabelFormatAndFields("%s %s", "firstName", "lastName")
         .withField(new QFieldMetaData("firstName", QFieldType.STRING).withIsRequired(true))
         .withField(new QFieldMetaData("lastName", QFieldType.STRING).withIsRequired(true))
         .withField(new QFieldMetaData("email", QFieldType.STRING))
         .withField(new QFieldMetaData("company", QFieldType.STRING))
         .withField(new QFieldMetaData("title", QFieldType.STRING))
         .withField(new QFieldMetaData("region", QFieldType.STRING))
         .withField(new QFieldMetaData("ownerUserId", QFieldType.STRING).withLabel("Account owner").withPossibleValueSourceName("sales_user"))
         .withField(new QFieldMetaData("assigneeUserId", QFieldType.STRING).withLabel("Account partner").withPossibleValueSourceName("sales_user"))
         .withSection(new QFieldSection("contact", new QIcon("person"), Tier.T1, List.of("id", "firstName", "lastName", "email")).withLabel("Contact"))
         .withSection(new QFieldSection("account", new QIcon("business"), Tier.T2, List.of("company", "title", "region", "ownerUserId", "assigneeUserId")).withLabel("Account")));
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("person"));
      instance.addTable(baseTable("sales_order", "Sales orders", "receipt_long")
         .withRecordLabelFormatAndFields("%s", "orderNo")
         .withField(new QFieldMetaData("orderNo", QFieldType.STRING).withLabel("Order no.").withIsRequired(true))
         .withField(new QFieldMetaData("customer", QFieldType.INTEGER).withPossibleValueSourceName("person").withIsRequired(true))
         .withField(new QFieldMetaData("status", QFieldType.STRING).withPossibleValueSourceName("sales_order_status"))
         .withField(new QFieldMetaData("total", QFieldType.DECIMAL))
         .withField(new QFieldMetaData("orderDate", QFieldType.DATE))
         .withSection(new QFieldSection("order", new QIcon("receipt_long"), Tier.T1, List.of("id", "orderNo", "customer", "status")).withLabel("Order"))
         .withSection(new QFieldSection("commercial", new QIcon("payments"), Tier.T2, List.of("total", "orderDate")).withLabel("Commercial details")));
      instance.addApp(new QAppMetaData().withName("sales").withLabel("Sales").withIcon(new QIcon("storefront"))
         .withSection(new QAppSection().withName("customers").withLabel("Customers & orders").withTables(List.of("person", "sales_order"))));
      instance.addApp(new QAppMetaData().withName("admin").withLabel("Admin").withIcon(new QIcon("settings"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("admin")));
      new AuditsMetaDataProvider().withRecordIdType(QFieldType.STRING).withDefineRecordScreens(true)
         .defineAll(instance, "memory", table -> table.withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS)));
      new CollaborationQBitProducer().withQBitConfig(new CollaborationQBitConfig().withBackendName("memory")
         .withAdminApp(true).withParentAppName("admin").withUiApi(true).withFrontendBaseUrl("http://localhost:" + port)
         .withTableFeatures("person", RecordFeature.values()).withTableFeatures("sales_order", RecordFeature.values())
         .withAttachmentStorage(new QCodeReference(MemoryStorage.class))
         .withUserSessionProvider(new QCodeReference(DemoSessions.class))
         .withPermissionHolderDirectory(new QCodeReference(DemoSessions.class))
         .withNotifier(new QCodeReference(RecordingNotifier.class))
         .withAssignActionTables(List.of("sales_order"))
         .withFollowActionTables(List.of("person", "sales_order")).withFollowChangeTables(List.of("person", "sales_order"))
         .withTagActionTables(List.of("person", "sales_order"))).produce(instance);
      RecordFeaturesMetaData.ofOrWithNew(instance.getTable("person")).withSetting(RecordFeature.TAGS, "mode", "FREE")
         .withOwnership(new RecordOwnership().withOwnerFieldName("ownerUserId").withAssigneeFieldName("assigneeUserId"));
      RecordFeaturesMetaData.ofOrWithNew(instance.getTable("sales_order")).withSetting(RecordFeature.TAGS, "mode", "APPROVED_ONLY");
      QContext.init(instance, session("admin"));
      new QInstanceEnricher(instance).enrich();
      new QInstanceValidator().validate(instance);
      RuntimeMetaDataOverlay.refresh(instance);
      return (instance);
   }



   /*******************************************************************************
    ** All Sales writes are permission checked and produce field-level audits.
    *******************************************************************************/
   private static QTableMetaData baseTable(String name, String label, String icon)
   {
      return (new QTableMetaData().withName(name).withLabel(label).withIcon(new QIcon(icon)).withBackendName("memory")
         .withPrimaryKeyField("id").withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("modifyDate", QFieldType.DATE_TIME)
            .withBehavior(com.kingsrook.qqq.backend.core.model.metadata.fields.DynamicDefaultValueBehavior.MODIFY_DATE))
         .withSection(new QFieldSection("audit", new QIcon("history"), Tier.T3, List.of("modifyDate")).withLabel("Updated"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS))
         .withAuditRules(new QAuditRules().withAuditLevel(AuditLevel.FIELD)));
   }



   /*******************************************************************************
    ** Stable grants shared by MOCK browser sessions, mentions and share checks.
    *******************************************************************************/
   static QSession session(String user)
   {
      if(!USERS.contains(user))
      {
         throw (new IllegalArgumentException("collab.dev.user must be alice, bob or admin: " + user));
      }
      String name = switch(user)
      {
         case "alice" -> "Alice Morgan";
         case "bob" -> "Bob Chen";
         default -> "Demo Administrator";
      };
      QSession session = new QSession().withUser(new QUser().withIdReference(user).withFullName(name))
         .withPermissions("person.read", "person.comment")
         .withValueForFrontend("user", new HashMap<>(Map.of("name", name, "email", user + "@example.com")));
      if(!user.equals("bob"))
      {
         session.withPermissions("audit.read", "auditDetail.read", "auditTable.read", "auditUser.read");
         for(String table : List.of("person", "sales_order"))
         {
            for(String permission : List.of("read", "write", "insert", "edit", "delete", "comment", "attach", "tag", "share", "task", "assign"))
            {
               session.withPermissions(table + "." + permission);
            }
         }
      }
      if(user.equals("admin"))
      {
         session.withPermissions("admin.hasAccess", "collaboration.admin", "collaboration.view", "collaboration.tags.manage", "collaboration.fields.manage");
      }
      return (session);
   }



   /*******************************************************************************
    ** MOCK normally creates User:number / John Smith; replace both explicitly.
    *******************************************************************************/
   public static class DemoSessions implements QAuthenticationModuleCustomizerInterface, UserSessionProviderInterface, PermissionHolderDirectory
   {
      /*******************************************************************************
       ** Preserve the browser session UUID while applying the selected user's grants.
       *******************************************************************************/
      @Override
      public void customizeSession(QInstance instance, QSession target, Map<String, Object> context)
      {
         QSession selected = session(selectedUser());
         target.setUser(selected.getUser());
         target.setPermissions(selected.getPermissions());
         target.setValuesForFrontend(selected.getValuesForFrontend());
      }



      /*******************************************************************************
       ** Return fresh sessions, so access checks cannot mutate another caller.
       *******************************************************************************/
      @Override
      public Optional<QSession> sessionForUser(String userId)
      {
         return (USERS.contains(userId) ? Optional.of(session(userId)) : Optional.empty());
      }



      /*******************************************************************************
       ** Access requests can find their configured approver without external IAM.
       *******************************************************************************/
      @Override
      public Set<String> userIdsWithPermission(String permission)
      {
         return (USERS.stream().filter(user -> session(user).getPermissions().contains(permission)).collect(java.util.stream.Collectors.toSet()));
      }
   }



   /*******************************************************************************
    ** Seed through normal actions/services, including lifecycle, access and audits.
    *******************************************************************************/
   static void seed(QInstance instance) throws Exception
   {
      for(String user : USERS)
      {
         QContext.init(instance, session(user));
         new PeopleService().touch();
      }
      QContext.setQSession(session("admin"));
      publishField(new QRecord().withValue("tableName", "person").withValue("name", "preferred_contact").withValue("label", "Preferred contact")
         .withValue("type", "SELECT").withValue("options", "[{\"id\":\"email\",\"label\":\"Email\"},{\"id\":\"phone\",\"label\":\"Phone\"},{\"id\":\"video\",\"label\":\"Video call\"}]"));
      publishField(new QRecord().withValue("tableName", "sales_order").withValue("name", "purchase_order_no").withValue("label", "Purchase order no.")
         .withValue("type", "STRING").withValue("searchable", true));
      seedSales();
      seedCollaboration();
      QContext.setQSession(session(System.getProperty("collab.dev.user", "alice")));
   }



   /*******************************************************************************
    ** A published definition contributes actual memory JsonPathStorage fields.
    *******************************************************************************/
   private static void publishField(QRecord definition) throws Exception
   {
      QRecord saved = insert(CollaborationTables.FIELD_DEFINITION, List.of(definition)).getFirst();
      FieldDefinitions.apply(saved.getValue("id"), "Publish");
   }



   /*******************************************************************************
    ** Fictional but realistic accounts; stable IDs and random seed aid screenshots.
    *******************************************************************************/
   private static void seedSales() throws Exception
   {
      String[] names = { "Maya Patel", "James Rivera", "Olivia Brooks", "Noah Kim", "Emma Wilson", "Liam Foster", "Ava Mitchell", "Ethan Reed", "Sofia Garcia", "Lucas Bennett",
         "Isabella Turner", "Mason Wright", "Amelia Scott", "Logan Hayes", "Charlotte Evans", "Oliver Chen", "Harper Davis", "Elijah Morgan", "Evelyn Price", "Henry Lewis",
         "Abigail Ross", "Jack Sullivan", "Grace Nguyen", "Daniel Park", "Zoe Campbell" };
      String[] companies = { "Northstar Outfitters", "Prairie Health Supply", "Harbor Coffee Roasters", "Summit Industrial", "Cedar Grove Foods", "Bluebird Logistics", "Riverbend Packaging", "Atlas Workshop", "Orchard Market", "Clearwater Labs" };
      String[] titles = { "Purchasing Director", "Operations Manager", "Founder", "Supply Chain Lead", "Finance Director" };
      String[] regions = { "Midwest", "Northeast", "West", "Southeast", "Southwest" };
      List<QRecord> people = new ArrayList<>();
      for(int i = 0; i < names.length; i++)
      {
         String[] name = names[i].split(" ");
         people.add(new QRecord().withValue("id", i + 1).withValue("firstName", name[0]).withValue("lastName", name[1])
            .withValue("email", (name[0] + "." + name[1]).toLowerCase(java.util.Locale.ROOT) + "@example.com")
            .withValue("company", companies[i % companies.length]).withValue("title", titles[i % titles.length]).withValue("region", regions[i % regions.length])
            .withValue("ownerUserId", "alice").withValue("assigneeUserId", i % 3 == 0 ? "bob" : "alice")
            .withValue("cf_preferred_contact", i % 3 == 0 ? "phone" : "email"));
      }
      insert("person", people);
      Random random = new Random(8098);
      List<QRecord> orders = new ArrayList<>();
      String[] statuses = { "Draft", "Confirmed", "Processing", "Shipped", "Delivered" };
      for(int i = 0; i < 40; i++)
      {
         orders.add(new QRecord().withValue("id", i + 1).withValue("orderNo", "SO-2026-" + (1001 + i)).withValue("customer", i % 25 + 1)
            .withValue("status", statuses[i % statuses.length]).withValue("total", BigDecimal.valueOf(75000 + random.nextInt(2400000), 2))
            .withValue("orderDate", LocalDate.of(2026, 9, 1).plusDays(i)).withValue("cf_purchase_order_no", "PO-" + (8400 + i)));
      }
      insert("sales_order", orders);
   }



   /*******************************************************************************
    ** Person 1 and order 1 contain the richest panels for the first demo visit.
    *******************************************************************************/
   private static void seedCollaboration() throws Exception
   {
      TagsService tags = new TagsService();
      for(String table : List.of("person", "sales_order"))
      {
         for(String label : List.of("vip", "renewal", "at-risk", "new-business"))
         {
            var tag = tags.create(table, label, label.equals("at-risk") ? "red" : "blue");
            tags.apply(table, 1, List.of(tag.id()), List.of());
            tags.apply(table, label.equals("vip") ? 2 : 3, List.of(tag.id()), List.of());
         }
      }
      QContext.setQSession(session("alice"));
      CommentsService comments = new CommentsService();
      comments.post("person", 1, "Renewal review is Thursday. @[Bob Chen](user:bob), please confirm Maya's preferred delivery window.", null, null);
      QContext.setQSession(session("bob"));
      comments.post("person", 1, "Confirmed: Tuesday mornings work best. @[Alice Morgan](user:alice), the warehouse contact is ready.", null, null);
      QContext.setQSession(session("alice"));
      var resolved = comments.post("person", 1, "Please check the purchasing title before sending the proposal.", null, "title");
      comments.post("person", 1, "Maya confirmed her new title on our call.", resolved.id(), null);
      comments.resolve(resolved.id());
      comments.post("sales_order", 1, "Annual replenishment order. @[Demo Administrator](user:admin), please review the purchase order reference.", null, null);
      comments.post("person", 2, "Expansion opportunity: add the two new Midwest locations to the renewal proposal.", null, null);
      AttachmentsService attachments = new AttachmentsService();
      attachments.upload("person", 1, "northstar-account-plan.png", "image/png", new ByteArrayInputStream(picture()), "Account plan for the renewal review");
      attachments.upload("sales_order", 1, "order-confirmation.pdf", "application/pdf", new ByteArrayInputStream(pdf()), "Draft confirmation for SO-2026-1001");
      attachments.upload("sales_order", 2, "delivery-plan.png", "image/png", new ByteArrayInputStream(picture()), "Proposed delivery schedule");
      Instant now = Instant.now();
      TasksService tasks = new TasksService();
      tasks.create("person", 1, "Send the renewal proposal", "Include the expanded delivery schedule.", "alice", now.minusSeconds(86400), null);
      tasks.create("person", 1, "Confirm warehouse contact", "Check the receiving team's direct number.", "bob", now.plusSeconds(172800), null);
      tasks.create("sales_order", 1, "Approve purchase order reference", "Match the reference on the customer's PDF.", "alice", now.plusSeconds(86400), null);
      var completed = tasks.create("person", 2, "Review regional pricing", "Use the current Midwest price list.", "alice", now.plusSeconds(86400), null);
      tasks.complete(completed.getValueInteger("id"));
      new AssignmentService().assign("sales_order", 1, "alice", "admin");
      new AssignmentService().assign("sales_order", 2, "alice", "alice");
      new SharingService().share("person", 1, List.of("bob"), "Please review the account before Thursday.");
      new SharingService().share("sales_order", 1, List.of("bob"), "Please check the delivery plan.");
      QContext.setQSession(session("bob"));
      new AccessRequestService().request("sales_order", "1", "I need order access to coordinate the Northstar delivery.", null);
      QContext.setQSession(session("alice"));
      for(String table : List.of("person", "sales_order"))
      {
         for(int id = 1; id <= 3; id++)
         {
            new FollowService().follow(table, id, true, true);
            FavoritesService.star("RECORD", table, id);
            RecentsService.recordView("RECORD", table, id);
         }
      }
      update("person", new QRecord().withValue("id", 1).withValue("title", "VP of Purchasing").withValue("cf_preferred_contact", "video"));
      update("sales_order", new QRecord().withValue("id", 1).withValue("status", "Confirmed").withValue("total", new BigDecimal("18750.00")));
      update("sales_order", new QRecord().withValue("id", 2).withValue("status", "Processing"));
   }



   /*******************************************************************************
    ** Fail startup on row-level action errors instead of showing a partial demo.
    *******************************************************************************/
   private static List<QRecord> insert(String table, List<QRecord> records) throws Exception
   {
      return (checked(new InsertAction().execute(new InsertInput(table).withRecords(records)).getRecords()));
   }



   /*******************************************************************************
    ** Ordinary updates generate real audit details and follow-change events.
    *******************************************************************************/
   private static void update(String table, QRecord record) throws Exception
   {
      checked(new UpdateAction().execute(new UpdateInput(table).withRecords(List.of(record))).getRecords());
   }



   /*******************************************************************************
    ** Both insert and update may return errors without throwing an exception.
    *******************************************************************************/
   private static List<QRecord> checked(List<QRecord> records)
   {
      for(QRecord record : records)
      {
         if(record.getErrors() != null && !record.getErrors().isEmpty())
         {
            throw (new IllegalStateException("Demo seed failed: " + record.getErrors()));
         }
      }
      return (records);
   }



   /*******************************************************************************
    ** Generate a real image so previews need no downloaded assets.
    *******************************************************************************/
   private static byte[] picture() throws IOException
   {
      BufferedImage image = new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = image.createGraphics();
      graphics.setColor(new Color(0x12324a));
      graphics.fillRect(0, 0, 640, 320);
      graphics.setColor(new Color(0x55d6be));
      graphics.fillRect(40, 180, 160, 90);
      graphics.fillRect(240, 130, 160, 140);
      graphics.fillRect(440, 95, 160, 175);
      graphics.setColor(Color.WHITE);
      graphics.setFont(new Font("SansSerif", Font.BOLD, 26));
      graphics.drawString("Northstar | Growth plan", 40, 60);
      graphics.dispose();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ImageIO.write(image, "png", bytes);
      return (bytes.toByteArray());
   }



   /*******************************************************************************
    ** A small, valid PDF with a proper cross-reference table, generated locally.
    *******************************************************************************/
   private static byte[] pdf() throws IOException
   {
      String content = "BT /F1 18 Tf 50 740 Td (Northstar Outfitters - SO-2026-1001) Tj 0 -35 Td (Order confirmation: USD 18,750.00) Tj ET\n";
      List<String> objects = List.of("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
         "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
         "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
      List<Integer> offsets = new ArrayList<>();
      for(int i = 0; i < objects.size(); i++)
      {
         offsets.add(bytes.size());
         bytes.write(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
      }
      int start = bytes.size();
      bytes.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
      for(Integer offset : offsets)
      {
         bytes.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
      }
      bytes.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + start + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
      return (bytes.toByteArray());
   }



   /*******************************************************************************
    ** Adapt the attachment HTTP test's fake to real local downloads. Opaque expiring
    ** links are issued only after AttachmentsService checks parent access.
    *******************************************************************************/
   public static class MemoryStorage extends AttachmentStorage
   {
      private static final Map<String, StoredFile> objects = new ConcurrentHashMap<>();
      private static final Map<String, Download> downloads = new ConcurrentHashMap<>();

      /*******************************************************************************
       ** Retain MIME type with the bytes for browser previews.
       *******************************************************************************/
      private record StoredFile(byte[] bytes, String contentType) {}

      /*******************************************************************************
       ** Capability URLs expire just like the object-storage URLs they substitute.
       *******************************************************************************/
      private record Download(String key, String filename, Instant expires) {}



      /*******************************************************************************
       ** The service enforces this cap while reading the upload stream.
       *******************************************************************************/
      @Override
      public long maxBytes(String name)
      {
         return (5 * 1024 * 1024);
      }



      /*******************************************************************************
       ** Same bounded-stream storage approach as CollaborationApiHttpTest.MemoryStorage.
       *******************************************************************************/
      @Override
      public void put(String name, PutRequest request)
      {
         try
         {
            objects.put(request.getKey(), new StoredFile(request.getStream().readAllBytes(), request.getContentType()));
         }
         catch(IOException e)
         {
            throw (new IllegalStateException("Cannot store demo attachment", e));
         }
      }



      /*******************************************************************************
       ** Deleting the object also makes any outstanding download links unusable.
       *******************************************************************************/
      @Override
      public void delete(String name, String key)
      {
         objects.remove(key);
      }



      /*******************************************************************************
       ** Issue a local URL on the configured demo port after the access check.
       *******************************************************************************/
      @Override
      public URL presign(String name, PresignGet request)
      {
         downloads.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
         String token = UUID.randomUUID().toString();
         downloads.put(token, new Download(request.getKey(), request.getDownloadAs(), Instant.now().plus(request.getTtl())));
         try
         {
            return (URI.create("http://localhost:" + System.getProperty("collab.dev.port", "8098") + "/demo-files/" + token).toURL());
         }
         catch(Exception e)
         {
            throw (new IllegalStateException(e));
         }
      }



      /*******************************************************************************
       ** Serve only issued capabilities; never expose an object-key listing.
       *******************************************************************************/
      static void routes(JavalinConfig config)
      {
         config.routes.get("/demo-files/{token}", context ->
         {
            Download download = downloads.get(context.pathParam("token"));
            StoredFile file = download == null ? null : objects.get(download.key());
            if(download == null || !download.expires().isAfter(Instant.now()) || file == null)
            {
               context.status(404);
               return;
            }
            context.contentType(file.contentType() == null ? "application/octet-stream" : file.contentType());
            context.header("Cache-Control", "no-store");
            context.header("X-Content-Type-Options", "nosniff");
            context.header("Content-Disposition", download.filename() == null ? "inline" : "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(download.filename(), StandardCharsets.UTF_8).replace("+", "%20"));
            context.result(file.bytes());
         });
      }



      /*******************************************************************************
       ** Keep the smoke test isolated from the other in-memory service tests.
       *******************************************************************************/
      static void clear()
      {
         objects.clear();
         downloads.clear();
      }
   }
}
