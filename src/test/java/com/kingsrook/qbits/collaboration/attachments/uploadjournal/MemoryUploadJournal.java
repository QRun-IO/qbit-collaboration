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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;

/*******************************************************************************
 ** Explicit test-only journal for existing memory-backend service behavior tests.
 ** It provides no durability or rollback evidence. Production constructors use
 ** JdbcUploadJournal; actual transaction/restart tests exercise that implementation.
 *******************************************************************************/
public final class MemoryUploadJournal implements UploadJournal
{
   private final Map<Integer, UploadEntry> rows = new LinkedHashMap<>();
   private final Map<Integer, String> tokens = new LinkedHashMap<>();

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized UploadEntry prepare(UploadCandidate candidate) throws QException
   {
      for(UploadEntry entry : rows.values())
      {
         if(entry.candidate().locatorHash().equals(candidate.locatorHash())
            || (candidate.intakeKey() != null && candidate.intakeKey().equals(entry.candidate().intakeKey())))
         {
            if(!entry.candidate().equals(candidate))
            {
               throw failure(UploadJournalException.Reason.IMMUTABLE_CONFLICT);
            }
            return (entry);
         }
      }
      UploadEntry entry = new UploadEntry(rows.size() + 1, candidate, UploadJournalState.PREPARED, UploadStorageState.PENDING, null, 0L, null);
      rows.put(entry.id(), entry);
      return (entry);
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized UploadReservation reserve(Integer journalId, Duration lease) throws QException
   {
      duration(lease);
      UploadEntry entry = require(journalId);
      if(entry.isLeased(Instant.now()))
      {
         throw failure(UploadJournalException.Reason.LEASE_HELD);
      }
      entry = entry.leased(entry.fence() + 1, Instant.now().plus(lease));
      rows.put(journalId, entry);
      String token = UUID.randomUUID().toString();
      tokens.put(journalId, token);
      return (new UploadReservation(entry, token, entry.fence(), entry.leaseUntil()));
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized UploadReservation renew(UploadReservation reservation, Duration lease) throws QException
   {
      duration(lease);
      UploadEntry entry = holder(reservation).leased(reservation.fence(), Instant.now().plus(lease));
      rows.put(entry.id(), entry);
      return (new UploadReservation(entry, reservation.leaseToken(), entry.fence(), entry.leaseUntil()));
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized UploadEntry recordStorage(UploadReservation reservation, UploadStorageState storageState) throws QException
   {
      UploadEntry entry = holder(reservation);
      if(storageState == null || storageState.ordinal() < entry.storageState().ordinal())
      {
         throw failure(UploadJournalException.Reason.ILLEGAL_STATE);
      }
      return (replace(entry, entry.state(), storageState, entry.attachmentId(), entry.leaseUntil()));
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized UploadEntry register(UploadReservation reservation, AttachmentRegistrar registrar) throws QException
   {
      UploadEntry entry = holder(reservation);
      if(entry.attachmentId() != null)
      {
         return (entry);
      }
      if(entry.storageState() != UploadStorageState.VERIFIED)
      {
         throw failure(UploadJournalException.Reason.STORAGE_NOT_VERIFIED);
      }
      // Memory-backend fixtures do not prove atomic rollback. The JDBC tests do.
      Integer attachment = registrar.insert(new QBackendTransaction(), entry.candidate(), entry.id());
      return (replace(entry, UploadJournalState.REGISTERED, entry.storageState(), attachment, entry.leaseUntil()));
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized UploadEntry complete(UploadReservation reservation) throws QException
   {
      UploadEntry entry = require(reservation.journalId());
      if(entry.state() == UploadJournalState.COMPLETE && entry.fence().equals(reservation.fence()))
      {
         return (entry);
      }
      entry = holder(reservation);
      if(entry.state() != UploadJournalState.REGISTERED || entry.attachmentId() == null)
      {
         throw failure(UploadJournalException.Reason.ILLEGAL_STATE);
      }
      tokens.remove(entry.id());
      return (replace(entry, UploadJournalState.COMPLETE, entry.storageState(), entry.attachmentId(), null));
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized boolean release(UploadReservation reservation) throws QException
   {
      UploadEntry entry = require(reservation.journalId());
      if(entry.state() == UploadJournalState.COMPLETE || !matches(entry, reservation))
      {
         return (false);
      }
      tokens.remove(entry.id());
      replace(entry, entry.state(), entry.storageState(), entry.attachmentId(), null);
      return (true);
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized Optional<UploadEntry> find(Integer journalId)
   {
      return (Optional.ofNullable(rows.get(journalId)));
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized Optional<UploadEntry> findByLocation(String bucketName, String storageKey)
   {
      String locator = UploadCandidate.locatorHash(bucketName, storageKey);
      return (rows.values().stream().filter(entry -> entry.candidate().locatorHash().equals(locator)).findFirst());
   }

   /*******************************************************************************
    ** Test-only implementation; does not establish durability.
    *******************************************************************************/
   @Override
   public synchronized List<UploadEntry> listIncomplete(int limit)
   {
      if(limit < 1 || limit > 500)
      {
         throw new IllegalArgumentException("Invalid fixture limit.");
      }
      return (rows.values().stream().filter(entry -> entry.state() != UploadJournalState.COMPLETE).limit(limit).toList());
   }

   /*******************************************************************************
    ** Enforce fixture state without claiming production transaction guarantees.
    *******************************************************************************/
   private UploadEntry require(Integer id) throws QException
   {
      UploadEntry entry = rows.get(id);
      if(entry == null)
      {
         throw failure(UploadJournalException.Reason.NOT_FOUND);
      }
      return (entry);
   }

   /*******************************************************************************
    ** Enforce fixture state without claiming production transaction guarantees.
    *******************************************************************************/
   private UploadEntry holder(UploadReservation reservation) throws QException
   {
      UploadEntry entry = require(reservation.journalId());
      if(!matches(entry, reservation))
      {
         throw failure(UploadJournalException.Reason.STALE_RESERVATION);
      }
      return (entry);
   }

   /*******************************************************************************
    ** Enforce fixture state without claiming production transaction guarantees.
    *******************************************************************************/
   private boolean matches(UploadEntry entry, UploadReservation reservation)
   {
      return (entry.fence().equals(reservation.fence()) && entry.isLeased(Instant.now())
         && reservation.leaseToken().equals(tokens.get(entry.id())));
   }

   /*******************************************************************************
    ** Enforce fixture state without claiming production transaction guarantees.
    *******************************************************************************/
   private UploadEntry replace(UploadEntry entry, UploadJournalState state, UploadStorageState storage, Integer attachment, Instant leaseUntil)
   {
      UploadEntry changed = new UploadEntry(entry.id(), entry.candidate(), state, storage, attachment, entry.fence(), leaseUntil);
      rows.put(entry.id(), changed);
      return (changed);
   }

   /*******************************************************************************
    ** Enforce fixture state without claiming production transaction guarantees.
    *******************************************************************************/
   private static void duration(Duration lease)
   {
      if(lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(Duration.ofMinutes(30)) > 0)
      {
         throw new IllegalArgumentException("Invalid fixture lease.");
      }
   }

   /*******************************************************************************
    ** Enforce fixture state without claiming production transaction guarantees.
    *******************************************************************************/
   private static UploadJournalException failure(UploadJournalException.Reason reason)
   {
      return (new UploadJournalException(reason, "Memory test journal refused: " + reason));
   }
}
