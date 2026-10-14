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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.assignment.AssignmentService;
import com.kingsrook.qbits.collaboration.comments.CommentsService;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.mywork.MyWorkService;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotifier;
import com.kingsrook.qbits.collaboration.notify.RecordingNotifier;
import com.kingsrook.qqq.backend.core.actions.collaboration.UserSessionProviderInterface;
import com.kingsrook.qbits.collaboration.sharing.AccessRequestService;
import com.kingsrook.qbits.collaboration.sharing.PermissionHolderDirectory;
import com.kingsrook.qbits.collaboration.sharing.SharingService;
import com.kingsrook.qbits.collaboration.tasks.SendTaskRemindersProcess;
import com.kingsrook.qbits.collaboration.tasks.TaskReminders;
import com.kingsrook.qbits.collaboration.tasks.TasksService;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.audits.AuditLevel;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.DenyBehavior;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Service and streamed-process acceptance with real parent locks and notifications.
 *******************************************************************************/
public class SharingWorkTest extends PersonalServiceFixture
{
   private static RecordingNotifier recorder;
   private static final Map<String, QSession> sessions = new HashMap<>();
   private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
   private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
   private final SharingService sharing = new SharingService();
   private final AccessRequestService access = new AccessRequestService();
   private final TasksService tasks = new TasksService(CLOCK);
   private final AssignmentService assignments = new AssignmentService();

   /*******************************************************************************
    ** Configure.
    *******************************************************************************/
   @BeforeEach
   void configure() throws Exception
   {
      recorder = new RecordingNotifier();
      sessions.clear();
      sessions.put("alice", session("alice", "a"));
      sessions.put("bob", session("bob", "a"));
      sessions.put("eve", session("eve", "b"));
      config.withNotifier(new QCodeReference(TestNotifier.class)).withUserSessionProvider(new QCodeReference(TestSessions.class));
      QContext.getQSession().getUser().setFullName("Alice");
      CollaborationRecords.insert(CollaborationTables.USER, new QRecord().withValue("userId", "eve").withValue("displayName", "Sam"));
      WorkProcesses.assignments("document").forEach(instance::addProcess);
      new QInstanceValidator().validate(instance);
   }

   /*******************************************************************************
    ** Test Notifier.
    *******************************************************************************/
   public static class TestNotifier implements CollaborationNotifier
   {
      /*******************************************************************************
       ** Use the configured test boundary.
       *******************************************************************************/
      @Override
      public void notify(CollaborationNotification notification)
      {
         recorder.notify(notification);
      }
   }

   /*******************************************************************************
    ** Test Sessions.
    *******************************************************************************/
   public static class TestSessions implements UserSessionProviderInterface
   {
      /*******************************************************************************
       ** Use the configured test boundary.
       *******************************************************************************/
      @Override
      public Optional<QSession> sessionForUser(String user)
      {
         return (Optional.ofNullable(sessions.get(user)));
      }
   }

   /*******************************************************************************
    ** Directory.
    *******************************************************************************/
   public static class Directory implements PermissionHolderDirectory
   {
      /*******************************************************************************
       ** Use the configured test boundary.
       *******************************************************************************/
      @Override
      public Set<String> userIdsWithPermission(String permission)
      {
         return (permission.equals("collaboration.admin") ? Set.of("admin") : Set.of("approver", "admin"));
      }
   }

   /*******************************************************************************
    ** Session.
    *******************************************************************************/
   private static QSession session(String user, String tenant)
   {
      return (new QSession().withUser(new QUser().withIdReference(user)).withPermissions("document.read", "document.task").withSecurityKeyValue("tenant", tenant));
   }

   /*******************************************************************************
    ** Links Preview And Recipient Safe Shares.
    *******************************************************************************/
   @Test
   void linksPreviewAndRecipientSafeShares() throws Exception
   {
      assertThat(sharing.link("document", "01")).isEqualTo(new SharingService.Link("/app/document/1", "First document"));
      config.withFrontendBaseUrl("https://example.test/");
      assertThat(sharing.link("document", 1).url()).isEqualTo("https://example.test/app/document/1");
      assertThat(TargetResolver.recordHref("a b", "a/b?#")).isEqualTo("https://example.test/app/a%20b/a%2Fb%3F%23");
      assertThat(sharing.preview("document", 1, List.of("bob", "eve", "missing")))
         .containsExactly(new SharingService.Recipient("bob", "bob", true), new SharingService.Recipient("eve", "Sam", false), new SharingService.Recipient("missing", "missing", null));
      List<QRecord> shares = sharing.share("document", 1, List.of("bob", "eve", "missing"), "First document confidential contents");
      assertThat(shares).hasSize(3);
      assertThat(recorder.notifications()).hasSize(3);
      assertThat(recorder.notifications().getFirst().title()).contains("Alice shared First document with you");
      for(CollaborationNotification notification : recorder.notifications().subList(1, 3))
      {
         assertThat(notification.title() + notification.body()).doesNotContain("First document", "confidential contents");
         assertThat(notification.title()).contains("Documents");
         assertThat(notification.link()).startsWith("/app/collaboration_access_request/create?tableName=document&recordId=1&shareId=");
         assertThat(notification.sourceTableName()).isNull();
      }
      assertThat(new FollowService().followers("document", 1)).singleElement().satisfies(row ->
      {
         assertThat(row.userId()).isEqualTo("bob");
         assertThat(row.reason()).isEqualTo("SHARED");
      });
      assertThat(rows(CollaborationTables.ACTIVITY).stream().filter(row -> "SHARED".equals(row.getValueString("type")))).hasSize(3);
      assertThatThrownBy(() -> sharing.markOpened(shares.getFirst().getValueInteger("id"))).hasMessage("Not found");
      login("bob");
      sharing.markOpened(shares.getFirst().getValueInteger("id"));
      Instant opened = rows(CollaborationTables.SHARE).getFirst().getValueInstant("openedAt");
      sharing.markOpened(shares.getFirst().getValueInteger("id"));
      assertThat(rows(CollaborationTables.SHARE).getFirst().getValueInstant("openedAt")).isEqualTo(opened);
   }

   /*******************************************************************************
    ** Sharing Validation And After Commit Checks.
    *******************************************************************************/
   @Test
   void sharingValidationAndAfterCommitChecks() throws Exception
   {
      assertThatThrownBy(() -> sharing.preview("document", 1, List.of())).hasMessageContaining("1 to 25");
      assertThatThrownBy(() -> sharing.preview("document", 1, java.util.Collections.nCopies(26, "bob"))).hasMessageContaining("1 to 25");
      assertThatThrownBy(() -> sharing.share("document", 1, List.of("bob"), "x".repeat(2001))).hasMessageContaining("2000");
      assertThatThrownBy(() -> sharing.link("document", 3)).hasMessage("Not found");
      QBackendTransaction tx = new QBackendTransaction();
      QContext.setTransaction(tx);
      sharing.share("document", 1, List.of("bob", "eve"), "First document");
      assertThat(recorder.notifications()).isEmpty();
      sessions.put("bob", session("bob", "b"));
      tx.commit();
      QContext.clearTransaction();
      assertThat(recorder.notifications()).singleElement().satisfies(n -> assertThat(n.recipientUserId()).isEqualTo("eve"));
      QContext.getQSession().setPermissions(Set.of("document.read"));
      assertThatThrownBy(() -> sharing.preview("document", 1, List.of("bob"))).hasMessageContaining("share");
      QContext.getQSession().setPermissions(Set.of("document.read", "document.share"));
      assertThat(sharing.preview("document", 1, List.of("alice"))).singleElement().satisfies(r -> assertThat(r.canRead()).isTrue());
   }

   /*******************************************************************************
    ** Requests Do Not Reveal Existence And Remain Owner Locked.
    *******************************************************************************/
   @Test
   void requestsDoNotRevealExistenceAndRemainOwnerLocked() throws Exception
   {
      QContext.getQSession().setPermissions(Set.of());
      QRecord existing = access.request("document", 3, "Please", null);
      QRecord missing = access.request("document", 999, "Please", null);
      assertThat(existing.getValues().keySet()).isEqualTo(missing.getValues().keySet());
      for(String field : existing.getValues().keySet())
      {
         if(!List.of("id", "recordId", "createDate", "modifyDate").contains(field))
         {
            assertThat(existing.getValue(field)).isEqualTo(missing.getValue(field));
         }
      }
      assertThat(existing.getValues()).doesNotContainKeys("label", "recordLabel", "title");
      assertThat(access.request("document", "03", "Changed", null).getValue("id")).isEqualTo(existing.getValue("id"));
      assertThat(visibleRequests()).hasSize(2);
      assertThat(visibleRequests().getFirst().getDisplayValue("tableName")).isEqualTo("Documents");
      login("bob");
      assertThat(visibleRequests()).isEmpty();
      assertThatThrownBy(() -> access.withdraw(existing.getValueInteger("id"))).hasMessage("Not found");
      admin();
      assertThat(visibleRequests()).hasSize(2);
      login("alice");
      access.withdraw(existing.getValueInteger("id"));
      assertThatThrownBy(() -> access.withdraw(existing.getValueInteger("id"))).hasMessageContaining("no longer open");
      assertThat(access.request("document", 3, null, null).getValue("id")).isNotEqualTo(existing.getValue("id"));
      assertThatThrownBy(() -> new InsertAction().execute(new InsertInput(CollaborationTables.ACCESS_REQUEST).withRecords(List.of(new QRecord()))))
         .hasMessageContaining("service actions");
   }

   /*******************************************************************************
    ** Requests Resolve Directory And Owners And Record Outcomes.
    *******************************************************************************/
   @Test
   void requestsResolveDirectoryAndOwnersAndRecordOutcomes() throws Exception
   {
      config.withPermissionHolderDirectory(new QCodeReference(Directory.class));
      CollaborationRecords.insert(CollaborationTables.FEATURE_SETTING, new QRecord().withValue("scopeType", "TABLE").withValue("scopeName", "document")
         .withValue("feature", "SHARING").withValue("enabled", true).withValue("settings", "{\"accessApproverPermission\":\"document.approve\"}"));
      assignments.assign("document", 1, "bob", null);
      QRecord request = access.request("document", 1, "First document", null);
      assertThat(recorder.notifications()).extracting(CollaborationNotification::recipientUserId).containsExactlyInAnyOrder("admin", "approver", "bob");
      assertThat(recorder.notifications()).allSatisfy(n -> assertThat(n.title() + n.body()).doesNotContain("First document"));
      assertThatThrownBy(() -> access.handle(request.getValueInteger("id"), "GRANTED", null)).hasMessageContaining("approver permission");
      login("approver", "document.approve");
      assertThat(visibleRequests()).isEmpty();
      access.handle(request.getValueInteger("id"), "GRANTED", "Changed in identity system");
      assertThat(rows(CollaborationTables.ACCESS_REQUEST).getFirst().getValueString("status")).isEqualTo("GRANTED");
      assertThat(recorder.notifications().getLast().recipientUserId()).isEqualTo("alice");
      assertThat(recorder.notifications().getLast().body()).doesNotContain("Changed in identity system");
      assertThat(QContext.getQSession().hasPermission("document.approve")).isTrue();
   }

   /*******************************************************************************
    ** Share References Cannot Be Forged.
    *******************************************************************************/
   @Test
   void shareReferencesCannotBeForged() throws Exception
   {
      Integer share = sharing.share("document", 1, List.of("eve"), "secret").getFirst().getValueInteger("id");
      assertThatThrownBy(() -> access.request("document", 1, null, share)).hasMessage("Not found");
      login("eve");
      assertThatThrownBy(() -> access.request("document", 2, null, share)).hasMessage("Not found");
      assertThat(access.request("document", 1, null, share).getValueInteger("shareId")).isEqualTo(share);
      sharing.markOpened(share);
   }

   /*******************************************************************************
    ** Tasks Refuse Unknown Assignees And Use Spec Reminder Defaults.
    *******************************************************************************/
   @Test
   void tasksRefuseUnknownAssigneesAndUseSpecReminderDefaults() throws Exception
   {
      assertThatThrownBy(() -> tasks.create("document", 1, "Work", null, "eve", null, null)).hasMessage("Sam can't open this record.");
      assertThatThrownBy(() -> tasks.create("document", 1, "Work", null, "missing", null, null)).hasMessageContaining("can't open");
      assertThatThrownBy(() -> tasks.create("document", 1, "x".repeat(201), null, null, null, null)).hasMessageContaining("200");
      QRecord future = tasks.create("document", 1, "Future", "Notes", "bob", NOW.plusSeconds(172800), null);
      assertThat(future.getValueInstant("remindAt")).isEqualTo(NOW.plusSeconds(86400));
      assertThat(recorder.notifications()).singleElement().satisfies(n -> assertThat(n.title()).contains("Future"));
      assertThat(new FollowService().followers("document", 1)).singleElement().satisfies(f -> assertThat(f.reason()).isEqualTo("ASSIGNED"));
      QRecord soon = tasks.create("document", 1, "Soon", null, null, NOW.plusSeconds(3600), null);
      assertThat(soon.getValueInstant("remindAt")).isEqualTo(soon.getValueInstant("dueAt"));
      assertThat(soon.getValueString("assigneeUserId")).isEqualTo("alice");
      config.withUserSessionProvider(null);
      assertThat(tasks.create("document", 1, "Self", null, null, null, null).getValueInstant("remindAt")).isNull();
   }

   /*******************************************************************************
    ** Task Transitions Updates And Mine Ordering.
    *******************************************************************************/
   @Test
   void taskTransitionsUpdatesAndMineOrdering() throws Exception
   {
      QRecord undated = tasks.create("document", 1, "Undated", null, null, null, null);
      QRecord later = tasks.create("document", 1, "Later", null, null, NOW.plusSeconds(200), null);
      QRecord first = tasks.create("document", 2, "First", null, null, NOW.plusSeconds(100), null);
      QRecord tie = tasks.create("document", 2, "Tie", null, null, NOW.plusSeconds(100), null);
      TasksService.Page page = tasks.mine(null, 2, null);
      assertThat(page.tasks()).extracting(r -> r.getValueString("title")).containsExactly("First", "Tie");
      assertThat(tasks.mine("OPEN", 2, page.nextCursor()).tasks()).extracting(r -> r.getValueString("title")).containsExactly("Later", "Undated");
      assertThatThrownBy(() -> tasks.mine(null, 0, null)).hasMessageContaining("Limit");
      assertThatThrownBy(() -> tasks.mine(null, 20, "bad")).hasMessageContaining("cursor");
      assertThatThrownBy(() -> tasks.mine("NOPE", 20, null)).hasMessageContaining("status");
      tasks.complete(first.getValueInteger("id"));
      tasks.complete(first.getValueInteger("id"));
      assertThat(tasks.mine("DONE", 10, null).tasks()).singleElement().satisfies(r -> assertThat(r.getValueInstant("completedAt")).isEqualTo(NOW));
      assertThat(rows(CollaborationTables.ACTIVITY).stream().filter(r -> "TASK_DONE".equals(r.getValueString("type")))).hasSize(1);
      tasks.reopen(first.getValueInteger("id"));
      assertThat(tasks.list("document", 2, false)).allSatisfy(r -> assertThat(r.getValueInstant("completedAt")).isNull());
      tasks.cancel(tie.getValueInteger("id"));
      assertThat(tasks.list("document", 2, false)).hasSize(1);
      assertThat(tasks.list("document", 2, true)).hasSize(2);
      tasks.update(later.getValueInteger("id"), "Updated", "notes", "bob", NOW, NOW.minusSeconds(10));
      login("bob");
      tasks.complete(later.getValueInteger("id"));
      assertThatThrownBy(() -> tasks.complete(undated.getValueInteger("id"))).hasMessageContaining("creator, assignee");
      admin();
      tasks.complete(undated.getValueInteger("id"));
   }

   /*******************************************************************************
    ** Reminders And Overdue Are Delivered Once With Fresh Access.
    *******************************************************************************/
   @Test
   void remindersAndOverdueAreDeliveredOnceWithFreshAccess() throws Exception
   {
      QRecord task = tasks.create("document", 1, "Scheduled", null, "bob", NOW.plusSeconds(100), NOW);
      recorder = new RecordingNotifier();
      assertThatThrownBy(() -> new TaskReminders(CLOCK).sendDue()).hasMessageContaining("admin");
      admin();
      TaskReminders reminders = new TaskReminders(CLOCK);
      reminders.sendDue();
      reminders.sendDue();
      assertThat(recorder.notifications()).hasSize(1);
      assertThat(WorkSupport.row(CollaborationTables.TASK, task.getValueInteger("id")).getValueInstant("remindedAt")).isEqualTo(NOW);
      new TaskReminders(Clock.fixed(NOW.plusSeconds(101), ZoneOffset.UTC)).sendDue();
      new TaskReminders(Clock.fixed(NOW.plusSeconds(102), ZoneOffset.UTC)).sendDue();
      assertThat(recorder.notifications()).hasSize(2);
      assertThat(recorder.notifications().getLast().title()).contains("overdue");
      tasks.update(task.getValueInteger("id"), "Changed", null, "bob", NOW.minusSeconds(1), NOW.minusSeconds(1));
      sessions.put("bob", session("bob", "b"));
      reminders.sendDue();
      assertThat(recorder.notifications()).hasSize(2);
      assertThat(WorkSupport.row(CollaborationTables.TASK, task.getValueInteger("id")).getValueInstant("remindedAt")).isNull();
      sessions.put("bob", session("bob", "a"));
      reminders.sendDue();
      assertThat(recorder.notifications()).hasSize(4);
      tasks.cancel(task.getValueInteger("id"));
      reminders.sendDue();
      assertThat(recorder.notifications()).hasSize(4);
      QContext.setTransaction(new QBackendTransaction());
      assertThatThrownBy(reminders::sendDue).hasMessageContaining("outside a transaction");
      QContext.clearTransaction();
      new TaskReminders().sendDue();
      new SendTaskRemindersProcess().run(null, null);
   }

   /*******************************************************************************
    ** Assignments Upsert And My Records Respect Parent Locks.
    *******************************************************************************/
   @Test
   void assignmentsUpsertAndMyRecordsRespectParentLocks() throws Exception
   {
      assertThatThrownBy(() -> assignments.assign("document", 1, "eve", null)).hasMessageContaining("can't open");
      QRecord first = assignments.assign("document", 1, "alice", "bob");
      QRecord again = assignments.assign("document", "01", "alice", "bob");
      assertThat(again.getValue("id")).isEqualTo(first.getValue("id"));
      assertThat(recorder.notifications()).hasSize(1);
      assignments.assign("document", 2, null, "alice");
      assertThat(assignments.myRecords(null, 1).get("document").count()).isEqualTo(2);
      assertThat(assignments.myRecords("document", 1).get("document").records()).hasSize(1);
      assertThatThrownBy(() -> assignments.myRecords(null, 101)).hasMessageContaining("Limit");
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(CollaborationTables.RECORD_ASSIGNMENT))).hasMessageContaining("collaboration");
      QContext.getQSession().getSecurityKeyValues().put("tenant", new ArrayList<>(List.of("b")));
      assertThat(assignments.myRecords(null, 10)).isEmpty();
      assertThat(instance.getTable(CollaborationTables.RECORD_ASSIGNMENT).getPermissionRules().getDenyBehavior()).isEqualTo(DenyBehavior.HIDDEN);
   }

   /*******************************************************************************
    ** My Work Summarizes Only Visible Work And Read Mentions.
    *******************************************************************************/
   @Test
   void myWorkSummarizesOnlyVisibleWorkAndReadMentions() throws Exception
   {
      for(int i = 0; i < 12; i++)
      {
         tasks.create("document", 1, "Task " + i, null, "bob", null, null);
      }
      assignments.assign("document", 1, "alice", "bob");
      new CommentsService().post("document", 1, "@[Bob](user:bob)", null, null);
      login("bob");
      MyWorkService service = new MyWorkService();
      MyWorkService.Summary summary = service.summary();
      assertThat(summary.openTaskCount()).isEqualTo(12);
      assertThat(summary.tasks()).hasSize(10);
      assertThat(summary.assignedRecordsByTable()).containsEntry("document", 1L);
      assertThat(summary.unreadMentionCount()).isEqualTo(1);
      service.markMentionsRead(List.of(-1));
      assertThat(service.summary().unreadMentionCount()).isEqualTo(1);
      service.markMentionsRead(null);
      assertThat(service.summary().unreadMentionCount()).isZero();
      QContext.getQSession().getSecurityKeyValues().put("tenant", new ArrayList<>(List.of("b")));
      assertThat(service.summary().openTaskCount()).isZero();
      assertThat(service.summary().assignedRecordsByTable()).isEmpty();
   }

   /*******************************************************************************
    ** Actual Streamed Actions Have Review And Result.
    *******************************************************************************/
   @Test
   void actualStreamedActionsHaveReviewAndResult() throws Exception
   {
      login("alice", "collaboration.view");
      QRecord task = tasks.create("document", 1, "Action", null, null, null, null);
      RunProcessOutput review = review("collaborationDone_collaboration_task", task.getValueString("id"), Map.of());
      assertThat(lines(review, false)).anySatisfy(line -> assertThat(line.getPluralFutureMessage()).contains("will be processed"));
      assertThat(lines(finish(review), true)).anySatisfy(line -> assertThat(line.getPluralPastMessage()).contains("processed"));
      assertThat(tasks.list("document", 1, false)).isEmpty();
      finish(review("collaborationReopen_collaboration_task", task.getValueString("id"), Map.of()));
      assertThat(tasks.list("document", 1, false)).hasSize(1);
      finish(review("collaborationAssign_document", "1,2", Map.of("assigneeUserId", "bob")));
      finish(review("collaborationOwn_document", "1,2", Map.of()));
      assertThat(assignments.myRecords(null, 10).get("document").count()).isEqualTo(2);
      assertThat(assignments.get("document", 1).orElseThrow().getValueString("assigneeUserId")).isEqualTo("bob");
   }

   /*******************************************************************************
    ** Access Actions Work For Admin And Configured Approver.
    *******************************************************************************/
   @Test
   void accessActionsWorkForAdminAndConfiguredApprover() throws Exception
   {
      QRecord granted = access.request("document", 999, null, null);
      QRecord declined = access.request("document", 998, null, null);
      admin();
      RunProcessOutput grant = review("collaborationGrant_collaboration_access_request", granted.getValueString("id"), Map.of("note", "Identity system updated"));
      assertThat(grant.getValues().get(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE)).isEqualTo("Record the outcome; grant access in your identity system first.");
      finish(grant);
      CollaborationRecords.insert(CollaborationTables.FEATURE_SETTING, new QRecord().withValue("scopeType", "TABLE").withValue("scopeName", "document")
         .withValue("feature", "SHARING").withValue("enabled", true).withValue("settings", "{\"accessApproverPermission\":\"document.approve\"}"));
      login("approver", "document.approve");
      finish(review("collaborationDecline_collaboration_access_request", declined.getValueString("id"), Map.of()));
      assertThat(rows(CollaborationTables.ACCESS_REQUEST)).extracting(r -> r.getValueString("status")).containsExactly("GRANTED", "DECLINED");
      assertThat(recorder.notifications()).extracting(CollaborationNotification::title).contains("Access request granted", "Access request declined");
   }

   /*******************************************************************************
    ** Cleanup Preserves Requests And Schema Includes Work Indexes.
    *******************************************************************************/
   @Test
   void cleanupPreservesRequestsAndSchemaIncludesWorkIndexes() throws Exception
   {
      tasks.create("document", 1, "Deleted", null, null, null, null);
      assignments.assign("document", 1, "alice", null);
      sharing.share("document", 1, List.of("bob"), null);
      access.request("document", 1, null, null);
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)));
      assertThat(rows(CollaborationTables.TASK)).isEmpty();
      assertThat(rows(CollaborationTables.RECORD_ASSIGNMENT)).isEmpty();
      assertThat(rows(CollaborationTables.SHARE)).isEmpty();
      assertThat(rows(CollaborationTables.ACCESS_REQUEST)).hasSize(1);
      new WorkCleanup().onParentsDeleted("document", List.of(1));
      new WorkCleanup().onParentsDeleted("document", List.of());
      String sql = CollaborationSchema.postgresScript(config);
      assertThat(sql).contains("collaboration_share", "collaboration_access_request", "collaboration_task", "collaboration_record_assignment", "overdue_notified_at",
         "read_at", "WHERE status = 'OPEN'", "assignee_user_id, status, due_at", "requester_user_id", "owner_user_id");
      for(String table : List.of(CollaborationTables.SHARE, CollaborationTables.ACCESS_REQUEST, CollaborationTables.TASK, CollaborationTables.RECORD_ASSIGNMENT))
      {
         assertThat(instance.getTable(table).getAuditRules().getAuditLevel()).isEqualTo(AuditLevel.FIELD);
      }
      assertThat(instance.getTable(CollaborationTables.TASK).getSearchFields()).containsExactly("title");
   }

   /*******************************************************************************
    ** Home Shows Real Counts.
    *******************************************************************************/
   @Test
   void homeShowsRealCounts() throws Exception
   {
      tasks.create("document", 1, "Overdue", null, null, NOW.minusSeconds(60), null);
      sharing.share("document", 1, List.of("bob"), null);
      access.request("document", 999, null, null);
      admin();
      String html = CollaborationHome.render(Instant.now().plusSeconds(5));
      assertThat(html).contains("Open access requests</h3><p>1", "Open tasks</h3><p>1", "Overdue tasks</h3><p>1", "Shares this week</h3><p>1");
      assertThat(SendTaskRemindersProcess.metadata(config).getIsHidden()).isTrue();
      assertThat(SendTaskRemindersProcess.metadata(config).getSchedule()).isNull();
      assertThat(SendTaskRemindersProcess.metadata(config.withSchedulerName("clock")).getSchedule().getRepeatSeconds()).isEqualTo(60);
   }

   /*******************************************************************************
    ** Undated Tasks And No Op Delivery Never Receive Receipts.
    *******************************************************************************/
   @Test
   void undatedTasksAndNoOpDeliveryNeverReceiveReceipts() throws Exception
   {
      tasks.create("document", 1, "No deadline", null, "bob", null, null);
      QRecord due = tasks.create("document", 2, "Due", null, "bob", NOW.minusSeconds(1), NOW);
      recorder = new RecordingNotifier();
      admin();
      config.withNotifier(new QCodeReference(com.kingsrook.qbits.collaboration.notify.NoOpNotifier.class));
      new TaskReminders(CLOCK).sendDue();
      assertThat(WorkSupport.row(CollaborationTables.TASK, due.getValueInteger("id")).getValueInstant("remindedAt")).isNull();
      assertThat(WorkSupport.row(CollaborationTables.TASK, due.getValueInteger("id")).getValueInstant("overdueNotifiedAt")).isNull();
      config.withNotifier(new QCodeReference(TestNotifier.class));
      new TaskReminders(CLOCK).sendDue();
      assertThat(recorder.notifications()).hasSize(2).allSatisfy(n -> assertThat(n.title()).doesNotContain("No deadline"));
   }

   /*******************************************************************************
    ** Cleanup Waits For Commit And Request Get Remains Owner Locked.
    *******************************************************************************/
   @Test
   void cleanupWaitsForCommitAndRequestGetRemainsOwnerLocked() throws Exception
   {
      tasks.create("document", 1, "Cleanup", null, null, null, null);
      QRecord request = access.request("document", 1, null, null);
      QBackendTransaction tx = new QBackendTransaction();
      QContext.setTransaction(tx);
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)).withTransaction(tx));
      assertThat(rows(CollaborationTables.TASK)).hasSize(1);
      tx.commit();
      QContext.clearTransaction();
      assertThat(rows(CollaborationTables.TASK)).isEmpty();
      login("bob");
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.ACCESS_REQUEST).withPrimaryKey(request.getValue("id"))).getRecord()).isNull();
      admin();
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.ACCESS_REQUEST).withPrimaryKey(request.getValue("id"))).getRecord()).isNotNull();
      login("alice");
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.ACCESS_REQUEST).withPrimaryKey(request.getValue("id"))).getRecord()).isNotNull();
   }

   /*******************************************************************************
    ** Rows.
    *******************************************************************************/
   private List<QRecord> rows(String table) throws Exception
   {
      return (CollaborationRecords.query(table, new QQueryFilter()));
   }

   /*******************************************************************************
    ** Visible Requests.
    *******************************************************************************/
   private List<QRecord> visibleRequests() throws Exception
   {
      return (new QueryAction().execute(new QueryInput(CollaborationTables.ACCESS_REQUEST).withShouldGenerateDisplayValues(true)).getRecords());
   }

   /*******************************************************************************
    ** Review.
    *******************************************************************************/
   private RunProcessOutput review(String name, String records, Map<String, Serializable> values) throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(name);
      input.addValue("testProcessName", name);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      input.addValue("recordsParam", "recordIds");
      input.addValue("recordIds", records);
      values.forEach(input::addValue);
      if(instance.getProcess(name).getStep("details") != null)
      {
         RunProcessOutput picker = new RunProcessAction().execute(input);
         assertThat(picker.getException()).isEmpty();
         input.setProcessUUID(picker.getProcessUUID());
         input.setStartAfterStep("details");
      }
      RunProcessOutput output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      return (output);
   }

   /*******************************************************************************
    ** Finish.
    *******************************************************************************/
   private RunProcessOutput finish(RunProcessOutput review) throws Exception
   {
      RunProcessInput go = new RunProcessInput();
      go.setProcessName((String) review.getValues().get("testProcessName"));
      go.setProcessUUID(review.getProcessUUID());
      go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
      go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput result = new RunProcessAction().execute(go);
      assertThat(result.getException()).isEmpty();
      return (result);
   }

   /*******************************************************************************
    ** Read the standard streamed process summary.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private List<ProcessSummaryLine> lines(RunProcessOutput output, boolean result)
   {
      return ((List<ProcessSummaryLine>) output.getValues().get(result ? StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY : StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY));
   }
}
