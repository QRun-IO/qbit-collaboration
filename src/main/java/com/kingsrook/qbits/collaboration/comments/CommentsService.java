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


package com.kingsrook.qbits.collaboration.comments;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.RecordCursor;
import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.follow.FollowEntry;
import com.kingsrook.qbits.collaboration.follow.FollowService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.notify.CollaborationNotification;
import com.kingsrook.qbits.collaboration.notify.Notifications;
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.people.PersonEntry;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.CollaborationRecordAccessChecker;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Record comments, one-level threads, edit history and recipient-safe mentions.
 *******************************************************************************/
public class CommentsService
{
   private static final Pattern MENTION = Pattern.compile("@\\[[^\\]\\r\\n]+]\\(user:([^()\\s]+)\\)");

   /*******************************************************************************
    ** Validate all input and the optional reply target before inserting anything.
    *******************************************************************************/
   public CommentEntry post(String table, Serializable recordId, String body, Integer parentCommentId, String fieldName) throws QException
   {
      new PeopleService().touch();
      ParentRecordAccess.requireFeature(table, recordId, "comment");
      String id = FollowService.canonicalId(table, recordId);
      Set<String> mentions = parse(body);
      Integer rootId = null;
      if(parentCommentId != null)
      {
         QRecord parent = requireComment(parentCommentId);
         if(!table.equals(parent.getValueString("tableName")) || !id.equals(parent.getValueString("recordId")))
         {
            throw (new QNotFoundException("Not found"));
         }
         rootId = parent.getValueInteger("threadId") == null ? parent.getValueInteger("id") : parent.getValueInteger("threadId");
      }
      if(fieldName != null && !QContext.getQInstance().getTable(table).getFields().containsKey(fieldName))
      {
         throw (new QBadRequestException("Unknown field"));
      }
      Instant now = Instant.now();
      QRecord row = CollaborationRecords.insert(CollaborationTables.COMMENT, reference(table, id).withValue("body", body)
         .withValue("threadId", rootId).withValue("parentCommentId", rootId).withValue("fieldName", fieldName)
         .withValue("authorUserId", PersonalRecords.currentUserId()).withValue("lastActivityAt", now));
      bumpRoot(row, now);
      Set<String> notified = processMentions(row, mentions);
      new FollowService().ensureFollower(PersonalRecords.currentUserId(), table, id, "COMMENTED");
      ActivityWriter.write(table, id, "COMMENTED", "Comment added", CollaborationTables.COMMENT, row.getValueString("id"));
      notifyFollowers(row, notified);
      return (entry(row, List.of()));
   }

   /*******************************************************************************
    ** Keyset-page root threads by latest activity; fetch replies oldest first.
    *******************************************************************************/
   public CommentPage list(String table, Serializable recordId, String cursor, Integer limit) throws QException
   {
      new PeopleService().touch();
      String id = FollowService.canonicalId(table, recordId);
      requireLimit(limit);
      RecordCursor before = cursor == null ? null : RecordCursor.decode(cursor);
      if(before != null && !before.kind().equals("COMMENT"))
      {
         throw (new QBadRequestException("Invalid comment cursor"));
      }
      QQueryFilter filter = CollaborationRecords.parent(table, id).withCriteria("threadId", QCriteriaOperator.IS_BLANK)
         .withOrderBy(new QFilterOrderBy("lastActivityAt", false)).withOrderBy(new QFilterOrderBy("id", false));
      if(before != null)
      {
         filter.withSubFilter(before.beforeFilter("lastActivityAt", "COMMENT"));
      }
      List<QRecord> roots = CollaborationRecords.query(CollaborationTables.COMMENT, filter.withLimit(limit + 1));
      List<CommentEntry> result = new ArrayList<>();
      for(QRecord row : roots.stream().limit(limit).toList())
      {
         List<CommentEntry> replies = new ArrayList<>();
         for(QRecord reply : CollaborationRecords.query(CollaborationTables.COMMENT, CollaborationRecords.parent(table, id)
            .withCriteria("threadId", QCriteriaOperator.EQUALS, row.getValue("id"))
            .withOrderBy(new QFilterOrderBy("createDate")).withOrderBy(new QFilterOrderBy("id"))))
         {
            replies.add(entry(reply, List.of()));
         }
         result.add(entry(row, replies));
      }
      return (new CommentPage(List.copyOf(result), roots.size() > limit ? key(roots.get(limit - 1)).encode() : null));
   }

   /*******************************************************************************
    ** Authors alone can edit; administrators cannot rewrite another author's body.
    *******************************************************************************/
   public CommentEntry edit(Serializable commentId, String body) throws QException
   {
      new PeopleService().touch();
      QRecord row = requireComment(commentId);
      requireAuthor(row);
      requireCommentFeature(row);
      Set<String> mentions = parse(body);
      Instant now = Instant.now();
      CollaborationRecords.insert(CollaborationTables.COMMENT_REVISION, reference(row.getValueString("tableName"), row.getValueString("recordId"))
         .withValue("commentId", row.getValue("id")).withValue("body", row.getValueString("body")).withValue("editedAt", now)
         .withValue("editedByUserId", PersonalRecords.currentUserId()));
      CollaborationRecords.update(CollaborationTables.COMMENT, new QRecord().withValue("id", row.getValue("id")).withValue("body", body)
         .withValue("editedAt", now).withValue("lastActivityAt", now));
      row.withValue("body", body).withValue("editedAt", now);
      bumpRoot(row, now);
      processMentions(row, mentions);
      ActivityWriter.write(row.getValueString("tableName"), row.getValueString("recordId"), "COMMENT_EDITED", "Comment edited", CollaborationTables.COMMENT, row.getValueString("id"));
      return (entry(row, List.of()));
   }

   /*******************************************************************************
    ** Soft delete; moderators still need to see the actual parent.
    *******************************************************************************/
   public void delete(Serializable commentId) throws QException
   {
      new PeopleService().touch();
      QRecord row = requireComment(commentId);
      if(!isAdmin())
      {
         requireAuthor(row);
         requireCommentFeature(row);
      }
      Instant now = Instant.now();
      CollaborationRecords.update(CollaborationTables.COMMENT, new QRecord().withValue("id", row.getValue("id"))
         .withValue("deletedAt", now).withValue("deletedByUserId", PersonalRecords.currentUserId()).withValue("lastActivityAt", now));
      bumpRoot(row, now);
   }

   /*******************************************************************************
    ** Resolve only a root thread.
    *******************************************************************************/
   public void resolve(Serializable commentId) throws QException
   {
      setResolved(commentId, true);
   }

   /*******************************************************************************
    ** Reopen only a root thread.
    *******************************************************************************/
   public void reopen(Serializable commentId) throws QException
   {
      setResolved(commentId, false);
   }

   /*******************************************************************************
    ** Anyone able to comment can change root resolution state.
    *******************************************************************************/
   private void setResolved(Serializable commentId, boolean resolved) throws QException
   {
      new PeopleService().touch();
      QRecord row = requireComment(commentId);
      requireCommentFeature(row);
      if(row.getValue("threadId") != null)
      {
         throw (new QBadRequestException("Resolve or reopen the root comment."));
      }
      Instant now = Instant.now();
      CollaborationRecords.update(CollaborationTables.COMMENT, new QRecord().withValue("id", row.getValue("id"))
         .withValue("resolvedAt", resolved ? now : null).withValue("resolvedByUserId", resolved ? PersonalRecords.currentUserId() : null).withValue("lastActivityAt", now));
      ActivityWriter.write(row.getValueString("tableName"), row.getValueString("recordId"), resolved ? "COMMENT_RESOLVED" : "COMMENT_REOPENED",
         resolved ? "Thread resolved" : "Thread reopened", CollaborationTables.COMMENT, row.getValueString("id"));
   }

   /*******************************************************************************
    ** History follows parent read access without requiring edit permission.
    *******************************************************************************/
   public List<CommentRevision> history(Serializable commentId) throws QException
   {
      new PeopleService().touch();
      QRecord row = requireComment(commentId);
      return (CollaborationRecords.query(CollaborationTables.COMMENT_REVISION, new QQueryFilter().withCriteria("commentId", QCriteriaOperator.EQUALS, row.getValue("id"))
         .withOrderBy(new QFilterOrderBy("editedAt")).withOrderBy(new QFilterOrderBy("id"))).stream()
         .map(revision -> new CommentRevision(revision.getValueInteger("id"), revision.getValueString("body"), revision.getValueInstant("editedAt"), revision.getValueString("editedByUserId"))).toList());
   }

   /*******************************************************************************
    ** Rank current participants before other matching active directory users.
    *******************************************************************************/
   public List<MentionCandidate> mentionCandidates(String table, Serializable recordId, String q) throws QException
   {
      new PeopleService().touch();
      String id = FollowService.canonicalId(table, recordId);
      Set<String> participants = new HashSet<>();
      new FollowService().followers(table, id).forEach(follower -> participants.add(follower.userId()));
      CollaborationRecords.query(CollaborationTables.COMMENT, CollaborationRecords.parent(table, id)).forEach(row -> participants.add(row.getValueString("authorUserId")));
      List<PersonEntry> people = new PeopleService().search(q, 50, participants);
      List<MentionCandidate> result = new ArrayList<>();
      for(PersonEntry person : people)
      {
         result.add(new MentionCandidate(person, new CollaborationRecordAccessChecker().canRead(person.userId(), table, id)));
      }
      return (List.copyOf(result));
   }

   /*******************************************************************************
    ** Validate before persistence; deduplicate and reject more than fifty mentions.
    *******************************************************************************/
   private Set<String> parse(String body) throws QException
   {
      if(body == null || body.isBlank() || body.length() > 20000)
      {
         throw (new QBadRequestException("Comment body must contain 1 to 20000 characters."));
      }
      Set<String> users = new LinkedHashSet<>();
      Matcher matcher = MENTION.matcher(body);
      while(matcher.find())
      {
         if(matcher.group(1).length() > 255)
         {
            throw (new QBadRequestException("Mention user ID is too long."));
         }
         users.add(matcher.group(1));
      }
      if(users.size() > 50)
      {
         throw (new QBadRequestException("A comment may mention at most 50 users."));
      }
      return (users);
   }

   /*******************************************************************************
    ** Keep historical mentions so an edit never re-notifies an already mentioned user.
    *******************************************************************************/
   private Set<String> processMentions(QRecord row, Set<String> users) throws QException
   {
      Set<String> existing = new HashSet<>();
      mentionRows(row).forEach(mention -> existing.add(mention.getValueString("mentionedUserId")));
      Set<String> notified = new HashSet<>();
      String table = row.getValueString("tableName");
      String id = row.getValueString("recordId");
      for(String user : users)
      {
         if(existing.contains(user))
         {
            continue;
         }
         boolean visible = new CollaborationRecordAccessChecker().canRead(user, table, id).orElse(false);
         QRecord mention = CollaborationRecords.insert(CollaborationTables.MENTION, reference(table, id).withValue("commentId", row.getValue("id"))
            .withValue("mentionedUserId", user).withValue("visible", visible));
         if(visible)
         {
            new FollowService().ensureFollower(user, table, id, "MENTIONED");
            if(!user.equals(PersonalRecords.currentUserId()))
            {
               notify(row, user, CollaborationNotification.Category.MENTION, "You were mentioned in a comment", mention.getValueInteger("id"));
               notified.add(user);
            }
         }
      }
      return (notified);
   }

   /*******************************************************************************
    ** Mention recipients get one notification; muted and author subscriptions are skipped.
    *******************************************************************************/
   private void notifyFollowers(QRecord row, Set<String> mentioned) throws QException
   {
      String author = PersonalRecords.currentUserId();
      for(FollowEntry follower : new FollowService().followers(row.getValueString("tableName"), row.getValueString("recordId")))
      {
         if(!author.equals(follower.userId()) && !mentioned.contains(follower.userId()) && Boolean.TRUE.equals(follower.notifyOnComment())
            && (follower.mutedUntil() == null || !follower.mutedUntil().isAfter(Instant.now())))
         {
            notify(row, follower.userId(), CollaborationNotification.Category.FOLLOW, "New comment on a followed record", null);
         }
      }
   }

   /*******************************************************************************
    ** Delivery re-checks recipient access, including after transaction commit.
    *******************************************************************************/
   private void notify(QRecord row, String user, CollaborationNotification.Category category, String title, Integer mentionId) throws QException
   {
      Notifications.send(new CollaborationNotification(user, title, row.getValueString("body"), category,
         row.getValueString("tableName"), row.getValueString("recordId"), Notifications.link(row.getValueString("tableName"), row.getValueString("recordId"))),
         mentionId == null ? null : () -> CollaborationRecords.update(CollaborationTables.MENTION,
            new QRecord().withValue("id", mentionId).withValue("notifiedAt", Instant.now())));
   }

   /*******************************************************************************
    ** Generic child reference for query security.
    *******************************************************************************/
   private QRecord reference(String table, String id)
   {
      return (new QRecord().withValue("tableName", table).withValue("recordId", id).withValue("parentDeleted", false));
   }

   /*******************************************************************************
    ** Parent visibility is checked before exposing even ownership metadata.
    *******************************************************************************/
   private QRecord requireComment(Serializable id) throws QException
   {
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.COMMENT, new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id));
      if(rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().getValueBoolean("parentDeleted")))
      {
         throw (new QNotFoundException("Not found"));
      }
      QRecord row = rows.getFirst();
      ParentRecordAccess.requireVisibleParent(row.getValueString("tableName"), row.getValueString("recordId"));
      return (row);
   }

   /*******************************************************************************
    ** Deleted comments cannot be edited by their author.
    *******************************************************************************/
   private void requireAuthor(QRecord row) throws QException
   {
      if(!PersonalRecords.currentUserId().equals(row.getValueString("authorUserId")) || row.getValue("deletedAt") != null)
      {
         throw (new QPermissionDeniedException("Only the author can edit or delete this comment."));
      }
   }

   /*******************************************************************************
    ** Shared core record feature permission boundary.
    *******************************************************************************/
   private void requireCommentFeature(QRecord row) throws QException
   {
      ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "comment");
   }

   /*******************************************************************************
    ** Update root ordering whenever a reply changes.
    *******************************************************************************/
   private void bumpRoot(QRecord row, Instant at) throws QException
   {
      if(row.getValue("threadId") != null)
      {
         CollaborationRecords.update(CollaborationTables.COMMENT, new QRecord().withValue("id", row.getValue("threadId")).withValue("lastActivityAt", at));
      }
   }

   /*******************************************************************************
    ** Comment cursor key uses root activity, not creation time.
    *******************************************************************************/
   private RecordCursor key(QRecord row)
   {
      return (new RecordCursor(row.getValueInstant("lastActivityAt"), "COMMENT", row.getValueLong("id")));
   }

   /*******************************************************************************
    ** Bound public collection responses.
    *******************************************************************************/
   private void requireLimit(Integer limit) throws QException
   {
      if(limit == null || limit < 1 || limit > 100)
      {
         throw (new QBadRequestException("Limit must be between 1 and 100"));
      }
   }

   /*******************************************************************************
    ** Child rows are safe after comment or parent authorization.
    *******************************************************************************/
   private List<QRecord> mentionRows(QRecord row) throws QException
   {
      return (CollaborationRecords.query(CollaborationTables.MENTION, new QQueryFilter().withCriteria("commentId", QCriteriaOperator.EQUALS, row.getValue("id"))));
   }

   /*******************************************************************************
    ** Capabilities reflect ownership and the same write permission as mutations.
    *******************************************************************************/
   private CommentEntry entry(QRecord row, List<CommentEntry> replies) throws QException
   {
      boolean author = PersonalRecords.currentUserId().equals(row.getValueString("authorUserId"));
      boolean deleted = row.getValue("deletedAt") != null;
      boolean feature;
      try
      {
         requireCommentFeature(row);
         feature = true;
      }
      catch(QPermissionDeniedException expected)
      {
         feature = false;
      }
      List<MentionEntry> mentions = mentionRows(row).stream().map(mention -> new MentionEntry(mention.getValueString("mentionedUserId"), mention.getValueBoolean("visible"))).toList();
      return (new CommentEntry(row.getValueInteger("id"), row.getValueInteger("threadId"), row.getValueInteger("parentCommentId"), deleted ? null : row.getValueString("body"),
         row.getValueString("fieldName"), row.getValueString("authorUserId"), new PeopleService().displayName(row.getValueString("authorUserId")), row.getValueInstant("createDate"),
         row.getValueInstant("editedAt"), deleted, row.getValue("resolvedAt") != null, author && feature && !deleted, !deleted && ((author && feature) || isAdmin()), mentions, replies));
   }

   /*******************************************************************************
    ** Moderation is distinct from permission to edit another author's text.
    *******************************************************************************/
   private boolean isAdmin()
   {
      return (QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN));
   }
}
