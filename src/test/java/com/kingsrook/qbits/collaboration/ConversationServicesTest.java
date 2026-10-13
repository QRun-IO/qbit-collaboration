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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.kingsrook.qbits.collaboration.comments.CommentEntry;
import com.kingsrook.qbits.collaboration.comments.CommentPage;
import com.kingsrook.qbits.collaboration.comments.CommentsService;
import com.kingsrook.qbits.collaboration.comments.MentionEntry;
import com.kingsrook.qbits.collaboration.follow.FollowChangeBatcher;
import com.kingsrook.qbits.collaboration.follow.FollowProcesses;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.follow.SendFollowDigestsProcess;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotifier;
import com.kingsrook.qbits.collaboration.notify.NoOpNotifier;
import com.kingsrook.qbits.collaboration.notify.RecordingNotifier;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.OtherUserAccess;
import com.kingsrook.qbits.collaboration.security.UserSessionSupplier;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeEvent;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeType;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
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
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Memory-backed acceptance tests exercise real permissions, actions and callbacks.
 *******************************************************************************/
public class ConversationServicesTest extends PersonalServiceFixture
{
   private static RecordingNotifier recorder;
   private static final Map<String, QSession> sessions = new HashMap<>();
   private final CommentsService comments = new CommentsService();
   private final FollowService follows = new FollowService();

   /*******************************************************************************
    ** Isolated transport/session provider for each test.
    *******************************************************************************/
   @BeforeEach
   void configure() throws Exception
   {
      recorder = new RecordingNotifier();
      sessions.clear();
      sessions.put("bob", session("bob", "a"));
      sessions.put("eve", session("eve", "b"));
      sessions.put("alice", session("alice", "a"));
      config.withNotifier(new QCodeReference(TestNotifier.class)).withUserSessionSupplier(new QCodeReference(TestSessions.class));
      QContext.getQSession().getUser().setFullName("Alice Example");
      new QInstanceValidator().validate(instance);
   }

   /*******************************************************************************
    ** Test transport configured through the actual QCodeReference loader.
    *******************************************************************************/
   public static class TestNotifier implements CollaborationNotifier
   {
      @Override
      public void notify(CollaborationNotification n)
      {
         recorder.notify(n);
      }
   }

   /*******************************************************************************
    ** The provider returns explicit test identities with real record locks.
    *******************************************************************************/
   public static class TestSessions implements UserSessionSupplier
   {
      @Override
      public Optional<QSession> sessionFor(String userId)
      {
         return (Optional.ofNullable(sessions.get(userId)));
      }
   }

   /*******************************************************************************
    ** Explicit read-only recipient permissions.
    *******************************************************************************/
   private static QSession session(String user, String tenant)
   {
      return (new QSession().withUser(new QUser().withIdReference(user)).withPermissions("document.read").withSecurityKeyValue("tenant", tenant));
   }

   /*******************************************************************************
    ** Mentions are recipient safe and deduplicated.
    *******************************************************************************/
   @Test
   void mentionsAreRecipientSafeAndDeduplicated() throws Exception
   {
      CommentEntry entry = comments.post("document", "01", "Hi @[Bob](user:bob) @[Again](user:bob) @[Eve](user:eve) @[Unknown](user:missing)", null, "title");
      assertThat(entry.authorDisplayName()).isEqualTo("Alice Example");
      assertThat(entry.canEdit()).isTrue();
      assertThat(entry.canDelete()).isTrue();
      assertThat(entry.mentions()).containsExactly(new MentionEntry("bob", true), new MentionEntry("eve", false), new MentionEntry("missing", false));
      assertThat(recorder.notifications()).singleElement().satisfies(n ->
      {
         assertThat(n.recipientUserId()).isEqualTo("bob");
         assertThat(n.category()).isEqualTo(CollaborationNotification.Category.MENTION);
         assertThat(n.link()).isEqualTo("/app/document/1");
         assertThat(n.sourceRecordId()).isEqualTo("1");
      });
      assertThat(CollaborationRecords.query(CollaborationTables.MENTION, new QQueryFilter().withCriteria("mentionedUserId", QCriteriaOperator.EQUALS, "bob")))
         .singleElement().satisfies(mention -> assertThat(mention.getValueInstant("notifiedAt")).isNotNull());
      assertThat(follows.followers("document", 1)).extracting(f -> f.userId()).containsExactlyInAnyOrder("alice", "bob");
      assertThat(follows.followers("document", 1)).allSatisfy(f ->
      {
         assertThat(f.notifyOnComment()).isTrue();
         assertThat(f.notifyOnChange()).isFalse();
      });
      comments.edit(entry.id(), "Again @[Bob](user:bob)");
      assertThat(recorder.notifications()).hasSize(1);
      config.withUserSessionSupplier(null);
      CommentEntry unknown = comments.post("document", 2, "@[Bob](user:bob)", null, null);
      assertThat(unknown.mentions()).containsExactly(new MentionEntry("bob", false));
      assertThat(recorder.notifications()).hasSize(1);
      assertThat(new OtherUserAccess().canRead("bob", "document", 1)).isEmpty();
   }

   /*******************************************************************************
    ** Transactions defer delivery and recheck access.
    *******************************************************************************/
   @Test
   void transactionsDeferDeliveryAndRecheckAccess() throws Exception
   {
      QBackendTransaction tx = new QBackendTransaction();
      QContext.setTransaction(tx);
      comments.post("document", 1, "@[Bob](user:bob)", null, null);
      assertThat(recorder.notifications()).isEmpty();
      assertThat(CollaborationRecords.query(CollaborationTables.MENTION, new QQueryFilter()).getFirst().getValueInstant("notifiedAt")).isNull();
      tx.commit();
      assertThat(recorder.notifications()).hasSize(1);
      assertThat(QContext.getQSession().getUser().getIdReference()).isEqualTo("alice");
      assertThat(QContext.getQBackendTransaction()).isSameAs(tx);
      comments.post("document", 1, "Another @[Bob](user:bob)", null, null);
      tx.rollback();
      assertThat(recorder.notifications()).hasSize(1);
      comments.post("document", 1, "Revoked @[Bob](user:bob)", null, null);
      sessions.put("bob", session("bob", "b"));
      tx.commit();
      assertThat(recorder.notifications()).hasSize(1);
      QContext.clearTransaction();
      sessions.put("bob", session("wrong-user", "a"));
      assertThat(new OtherUserAccess().canRead("bob", "document", 1)).isEmpty();
      new NoOpNotifier().notify(recorder.notifications().getFirst());
   }

   /*******************************************************************************
    ** Replies history and resolution.
    *******************************************************************************/
   @Test
   void repliesHistoryAndResolution() throws Exception
   {
      CommentEntry root = comments.post("document", 1, "Root", null, null);
      CommentEntry reply = comments.post("document", 1, "Reply", root.id(), null);
      CommentEntry nested = comments.post("document", 1, "Flattened", reply.id(), null);
      assertThat(nested.threadId()).isEqualTo(root.id());
      assertThat(nested.parentCommentId()).isEqualTo(root.id());
      CommentEntry edited = comments.edit(reply.id(), "Changed @[Bob](user:bob)");
      assertThat(edited.editedAt()).isNotNull();
      assertThat(comments.history(reply.id())).singleElement().satisfies(revision ->
      {
         assertThat(revision.body()).isEqualTo("Reply");
         assertThat(revision.editedByUserId()).isEqualTo("alice");
      });
      comments.resolve(root.id());
      assertThat(comments.list("document", 1, null, 20).threads().getFirst().resolved()).isTrue();
      comments.reopen(root.id());
      CommentEntry thread = comments.list("document", 1, null, 20).threads().getFirst();
      assertThat(thread.resolved()).isFalse();
      assertThat(thread.replies()).extracting(CommentEntry::id).containsExactly(reply.id(), nested.id());
      assertThatThrownBy(() -> comments.resolve(reply.id())).hasMessageContaining("root");
      assertThatThrownBy(() -> comments.post("document", 2, "Wrong parent", root.id(), null)).isInstanceOf(QNotFoundException.class);
   }

   /*******************************************************************************
    ** Own delete others and moderation.
    *******************************************************************************/
   @Test
   void ownDeleteOthersAndModeration() throws Exception
   {
      CommentEntry row = comments.post("document", 1, "Delete me", null, null);
      login("bob");
      assertThat(comments.history(row.id())).isEmpty();
      assertThatThrownBy(() -> comments.delete(row.id())).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> comments.edit(row.id(), "rewrite")).isInstanceOf(QPermissionDeniedException.class);
      admin();
      assertThatThrownBy(() -> comments.edit(row.id(), "moderated rewrite")).isInstanceOf(QPermissionDeniedException.class);
      comments.delete(row.id());
      assertThat(comments.list("document", 1, null, 20).threads()).singleElement().satisfies(deleted ->
      {
         assertThat(deleted.body()).isNull();
         assertThat(deleted.deleted()).isTrue();
         assertThat(deleted.canEdit()).isFalse();
      });
      login("alice");
      CommentEntry mine = comments.post("document", 1, "Own", null, null);
      comments.delete(mine.id());
      assertThatThrownBy(() -> comments.edit(mine.id(), "undelete")).isInstanceOf(QPermissionDeniedException.class);
   }

   /*******************************************************************************
    ** Parent and feature permissions.
    *******************************************************************************/
   @Test
   void parentAndFeaturePermissions() throws Exception
   {
      assertThatThrownBy(() -> comments.post("document", 3, "secret", null, null)).hasMessage("Not found");
      assertThatThrownBy(() -> comments.list("document", 3, null, 20)).hasMessage("Not found");
      QContext.getQSession().getPermissions().remove("document.write");
      assertThatThrownBy(() -> comments.post("document", 1, "read only", null, null)).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().withPermissions("document.comment");
      CommentEntry row = comments.post("document", 1, "explicit grant", null, null);
      QContext.getQSession().getPermissions().remove("document.comment");
      assertThat(comments.list("document", 1, null, 20).threads().getFirst().canEdit()).isFalse();
      assertThatThrownBy(() -> comments.edit(row.id(), "denied")).isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> comments.resolve(row.id())).isInstanceOf(QPermissionDeniedException.class);
      assertThat(follows.follow("document", 1, null, null).notifyOnChange()).isFalse();
      assertThatThrownBy(() -> follows.follow("document", 3, null, null)).hasMessage("Not found");
   }

   /*******************************************************************************
    ** Thread keyset paging uses latest activity.
    *******************************************************************************/
   @Test
   void threadKeysetPagingUsesLatestActivity() throws Exception
   {
      CommentEntry first = comments.post("document", 1, "first", null, null);
      CommentEntry second = comments.post("document", 1, "second", null, null);
      Instant tie = Instant.parse("2026-01-01T00:00:00Z");
      for(CommentEntry row : List.of(first, second))
      {
         CollaborationRecords.update(CollaborationTables.COMMENT, new QRecord().withValue("id", row.id()).withValue("lastActivityAt", tie));
      }
      CommentPage page = comments.list("document", 1, null, 1);
      assertThat(page.threads()).extracting(CommentEntry::id).containsExactly(second.id());
      CommentPage next = comments.list("document", 1, page.nextCursor(), 1);
      assertThat(next.threads()).extracting(CommentEntry::id).containsExactly(first.id());
      assertThat(next.nextCursor()).isNull();
      comments.post("document", 1, "new reply", first.id(), null);
      assertThat(comments.list("document", 1, null, 1).threads().getFirst().id()).isEqualTo(first.id());
      assertThatThrownBy(() -> comments.list("document", 1, "bad!", 20)).hasMessage("Invalid cursor");
      assertThatThrownBy(() -> comments.list("document", 1, null, 51)).hasMessageContaining("Limit");
      assertThatThrownBy(() -> comments.post("document", 1, " ", null, null)).hasMessageContaining("body");
      assertThatThrownBy(() -> comments.post("document", 1, "a".repeat(20001), null, null)).hasMessageContaining("body");
      assertThatThrownBy(() -> comments.post("document", 1, "ok", null, "missing")).hasMessage("Unknown field");
      String mentions = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "@[P](user:u" + i + ")").collect(java.util.stream.Collectors.joining());
      assertThatThrownBy(() -> comments.post("document", 1, mentions, null, null)).hasMessageContaining("50 users");
   }

   /*******************************************************************************
    ** Follow defaults mute and unfollow.
    *******************************************************************************/
   @Test
   void followDefaultsMuteAndUnfollow() throws Exception
   {
      assertThat(follows.state("document", 1)).isEmpty();
      assertThat(follows.follow("document", 1, null, null)).satisfies(f ->
      {
         assertThat(f.reason()).isEqualTo("MANUAL");
         assertThat(f.notifyOnChange()).isTrue();
         assertThat(f.notifyOnComment()).isTrue();
      });
      follows.follow("document", 1, false, false);
      assertThat(follows.state("document", 1).orElseThrow().notifyOnChange()).isFalse();
      follows.ensureFollower("bob", "document", 1, "ASSIGNED");
      assertThat(follows.followers("document", 1)).anySatisfy(f ->
      {
         assertThat(f.userId()).isEqualTo("bob");
         assertThat(f.notifyOnChange()).isTrue();
      });
      QRecord bob = CollaborationRecords.query(CollaborationTables.FOLLOW, new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, "bob")).getFirst();
      CollaborationRecords.update(CollaborationTables.FOLLOW, new QRecord().withValue("id", bob.getValue("id")).withValue("mutedUntil", Instant.now().plusSeconds(600)));
      comments.post("document", 1, "Muted", null, null);
      assertThat(recorder.notifications()).isEmpty();
      CollaborationRecords.update(CollaborationTables.FOLLOW, new QRecord().withValue("id", bob.getValue("id")).withValue("mutedUntil", Instant.EPOCH));
      comments.post("document", 1, "Unmuted", null, null);
      assertThat(recorder.notifications()).singleElement().satisfies(n -> assertThat(n.category()).isEqualTo(CollaborationNotification.Category.FOLLOW));
      follows.unfollow("document", 1);
      assertThat(follows.state("document", 1)).isEmpty();
      follows.unfollow("document", 1);
   }

   /*******************************************************************************
    ** People throttle search and mention ranking.
    *******************************************************************************/
   @Test
   void peopleThrottleSearchAndMentionRanking() throws Exception
   {
      Instant at = Instant.parse("2026-01-01T00:00:00Z");
      new PeopleService(Clock.fixed(at, ZoneOffset.UTC)).touch();
      QContext.getQSession().getUser().setFullName("Changed Alice");
      new PeopleService(Clock.fixed(at.plusSeconds(599), ZoneOffset.UTC)).touch();
      assertThat(new PeopleService().search("Alice", 5)).singleElement().satisfies(p -> assertThat(p.displayName()).isEqualTo("Alice Example"));
      new PeopleService(Clock.fixed(at.plusSeconds(600), ZoneOffset.UTC)).touch();
      assertThat(new PeopleService().search("changed", 5)).hasSize(1);
      login("bob");
      new PeopleService().touch();
      follows.follow("document", 1, null, null);
      login("eve");
      new PeopleService().touch();
      login("alice");
      QRecord eve = CollaborationRecords.query(CollaborationTables.USER, new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, "eve")).getFirst();
      CollaborationRecords.update(CollaborationTables.USER, new QRecord().withValue("id", eve.getValue("id")).withValue("email", "search@example.invalid"));
      assertThat(new PeopleService().search("EXAMPLE.INVALID", 50)).singleElement().satisfies(p -> assertThat(p.userId()).isEqualTo("eve"));
      assertThat(comments.mentionCandidates("document", 1, "").getFirst().person().userId()).isEqualTo("bob");
      assertThat(comments.mentionCandidates("document", 1, "eve").getFirst().canRead()).contains(false);
      CollaborationRecords.update(CollaborationTables.USER, new QRecord().withValue("id", eve.getValue("id")).withValue("active", false));
      assertThat(new PeopleService().search("eve", 50)).isEmpty();
      assertThatThrownBy(() -> new PeopleService().search("", 51)).hasMessageContaining("Limit");
   }

   /*******************************************************************************
    ** Direct writes and parent read restrictions.
    *******************************************************************************/
   @Test
   void directWritesAndParentReadRestrictions() throws Exception
   {
      CommentEntry row = comments.post("document", 1, "body", null, null);
      for(String table : List.of(CollaborationTables.COMMENT, CollaborationTables.COMMENT_REVISION, CollaborationTables.MENTION, CollaborationTables.FOLLOW, CollaborationTables.ACTIVITY))
      {
         assertThatThrownBy(() -> new InsertAction().execute(new InsertInput(table).withRecords(List.of(new QRecord())))).isInstanceOf(QPermissionDeniedException.class);
         assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(table).withRecords(List.of(new QRecord().withValue("id", 1))))).isInstanceOf(QPermissionDeniedException.class);
         assertThatThrownBy(() -> new DeleteAction().execute(new DeleteInput(table).withPrimaryKeys(List.of(1)))).isInstanceOf(QPermissionDeniedException.class);
      }
      login("viewer", CollaborationPermissions.VIEW);
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.COMMENT)).getRecords()).hasSize(1);
      QContext.getQSession().getSecurityKeyValues().clear();
      QContext.getQSession().withSecurityKeyValue("tenant", "b");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.COMMENT)).getRecords()).isEmpty();
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.COMMENT).withPrimaryKey(row.id())).getRecord()).isNull();
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(CollaborationTables.FOLLOW))).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().getPermissions().remove("document.read");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.COMMENT)).getRecords()).isEmpty();
   }

   /*******************************************************************************
    ** Batcher sends one window skips actor and rechecks access.
    *******************************************************************************/
   @Test
   void batcherSendsOneWindowSkipsActorAndRechecksAccess() throws Exception
   {
      follows.follow("document", 1, true, true);
      follows.ensureFollower("bob", "document", 1, "ASSIGNED");
      Instant at = Instant.parse("2026-01-01T00:00:00Z");
      FollowChangeBatcher batcher = new FollowChangeBatcher(Clock.fixed(at, ZoneOffset.UTC));
      batcher.recordChange("document", "1", "alice", Set.of("title"));
      batcher.recordChange("document", "1", "alice", Set.of("tenant"));
      assertThat(CollaborationRecords.query(CollaborationTables.FOLLOW_DIGEST, new QQueryFilter())).hasSize(1);
      admin();
      new FollowChangeBatcher(Clock.fixed(at.plusSeconds(299), ZoneOffset.UTC)).sendDue();
      assertThat(recorder.notifications()).isEmpty();
      new FollowChangeBatcher(Clock.fixed(at.plusSeconds(300), ZoneOffset.UTC)).sendDue();
      assertThat(recorder.notifications()).singleElement().satisfies(n ->
      {
         assertThat(n.recipientUserId()).isEqualTo("bob");
         assertThat(n.body()).contains("title", "tenant");
      });
      new FollowChangeBatcher(Clock.fixed(at.plusSeconds(301), ZoneOffset.UTC)).sendDue();
      assertThat(recorder.notifications()).hasSize(1);
      batcher.recordChange("document", "1", "alice", Set.of("title"));
      sessions.put("bob", session("bob", "b"));
      new FollowChangeBatcher(Clock.fixed(at.plusSeconds(600), ZoneOffset.UTC)).sendDue();
      assertThat(recorder.notifications()).hasSize(1);
      assertThat(CollaborationRecords.query(CollaborationTables.FOLLOW_DIGEST, new QQueryFilter())).isEmpty();
   }

   /*******************************************************************************
    ** Released listener defers until commit.
    *******************************************************************************/
   @Test
   void releasedListenerDefersUntilCommit() throws Exception
   {
      config.withFollowChangeTables(List.of("document"));
      instance.withRecordChangeListener(new QCodeReference(FollowChangeBatcher.class));
      follows.ensureFollower("bob", "document", 1, "ASSIGNED");
      QBackendTransaction tx = new QBackendTransaction();
      new UpdateAction().execute(new UpdateInput("document").withTransaction(tx).withRecords(List.of(new QRecord().withValue("id", 1).withValue("title", "Updated"))));
      assertThat(CollaborationRecords.query(CollaborationTables.FOLLOW_DIGEST, new QQueryFilter())).isEmpty();
      tx.commit();
      assertThat(CollaborationRecords.query(CollaborationTables.FOLLOW_DIGEST, new QQueryFilter())).hasSize(1);
      assertThat(new FollowChangeBatcher().appliesTo("document", RecordChangeType.DELETE)).isFalse();
      assertThat(new FollowChangeBatcher().appliesTo("other", RecordChangeType.UPDATE)).isFalse();
      assertThatThrownBy(() -> new SendFollowDigestsProcess().run(null, null)).isInstanceOf(QPermissionDeniedException.class);
      admin();
      new SendFollowDigestsProcess().run(null, null);
   }

   /*******************************************************************************
    ** Bulk follow review and result.
    *******************************************************************************/
   @Test
   @SuppressWarnings("unchecked")
   void bulkFollowReviewAndResult() throws Exception
   {
      FollowProcesses.processesFor("document").forEach(instance::addProcess);
      new QInstanceValidator().validate(instance);
      for(String name : List.of("collaborationFollow_document", "collaborationUnfollow_document"))
      {
         RunProcessInput input = new RunProcessInput();
         input.setProcessName(name);
         input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
         input.addValue(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, "document");
         input.addValue(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, "document");
         input.addValue(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
         input.addValue("recordsParam", "recordIds");
         input.addValue("recordIds", "1,2");
         RunProcessOutput review = new RunProcessAction().execute(input);
         assertThat(review.getException()).isEmpty();
         List<ProcessSummaryLine> lines = (List<ProcessSummaryLine>) review.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
         assertThat(lines).anySatisfy(line -> assertThat(line.getCount()).isEqualTo(2));
         RunProcessInput go = new RunProcessInput();
         go.setProcessName(name);
         go.setProcessUUID(review.getProcessUUID());
         go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
         go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
         RunProcessOutput result = new RunProcessAction().execute(go);
         assertThat((List<ProcessSummaryLine>) result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY)).anySatisfy(line -> assertThat(line.getCount()).isEqualTo(2));
         assertThat(follows.state("document", 1).isPresent()).isEqualTo(name.contains("Follow_"));
      }
   }

   /*******************************************************************************
    ** Metadata schema and real home counts.
    *******************************************************************************/
   @Test
   void metadataSchemaAndRealHomeCounts() throws Exception
   {
      var installed = new com.kingsrook.qqq.backend.core.model.metadata.QInstance()
         .withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData().withName("anonymous").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.FULLY_ANONYMOUS));
      installed.addBackend(instance.getBackend("memory"));
      installed.addTable(instance.getTable("document").clone());
      installed.addSecurityKeyType(instance.getSecurityKeyType("tenant"));
      installed.addScheduler(new com.kingsrook.qqq.backend.core.model.metadata.scheduleing.simple.SimpleSchedulerMetaData().withName("minute"));
      CollaborationQBitConfig configured = new CollaborationQBitConfig().withBackendName("memory").withFollowActionTables(List.of("document"))
         .withFollowChangeTables(List.of("document")).withSchedulerName("minute");
      new CollaborationQBitProducer().withQBitConfig(configured).produce(installed);
      assertThat(installed.getProcess("collaborationFollow_document").getLabel()).isEqualTo("Follow");
      assertThat(installed.getProcess("collaborationUnfollow_document").getLabel()).isEqualTo("Unfollow");
      assertThat(installed.getRecordChangeListeners()).hasSize(1);
      assertThat(installed.getProcess(SendFollowDigestsProcess.NAME).getSchedule().getRepeatSeconds()).isEqualTo(60);
      assertThat(installed.getProcess(SendFollowDigestsProcess.NAME).getIsHidden()).isTrue();
      String sql = CollaborationSchema.postgresScript(config);
      for(String table : List.of(CollaborationTables.COMMENT, CollaborationTables.COMMENT_REVISION, CollaborationTables.MENTION,
         CollaborationTables.FOLLOW, CollaborationTables.ACTIVITY, CollaborationTables.FOLLOW_DIGEST))
      {
         assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + table);
      }
      assertThat(sql).contains("ON collaboration_comment (table_name, record_id, create_date)", "ON collaboration_follow_digest (due_at)");
      comments.post("document", 1, "home", null, null);
      admin();
      String html = com.kingsrook.qbits.collaboration.app.CollaborationHome.render(Instant.now().plusSeconds(1));
      assertThat(html).contains("Comments this week</h3><p>1", "Open threads</h3><p>1", "Followers</h3><p>1");
      assertThatThrownBy(() -> config.withFollowActionTables(List.of("missing")).validate(instance)).hasMessageContaining("host table");
   }

   /*******************************************************************************
    ** Participant ranking precedes the search limit.
    *******************************************************************************/
   @Test
   void participantRankingPrecedesTheSearchLimit() throws Exception
   {
      for(int i = 0; i < 55; i++)
      {
         CollaborationRecords.insert(CollaborationTables.USER, new QRecord().withValue("userId", "directory" + i)
            .withValue("displayName", "A Person " + i).withValue("active", true));
      }
      login("bob");
      QContext.getQSession().getUser().setFullName("Z Bob");
      follows.follow("document", 1, null, null);
      login("alice");
      assertThat(comments.mentionCandidates("document", 1, "")).hasSize(50).first().satisfies(candidate -> assertThat(candidate.person().userId()).isEqualTo("bob"));
   }
}
