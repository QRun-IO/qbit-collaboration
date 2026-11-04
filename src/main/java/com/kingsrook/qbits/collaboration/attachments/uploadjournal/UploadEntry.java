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


import java.time.Instant;


/*******************************************************************************
 ** Immutable snapshot of one journal row at read time. It never carries the
 ** lease token, so it cannot be used to mutate the journal.
 *******************************************************************************/
public final class UploadEntry
{
   private final Integer id;
   private final UploadCandidate candidate;
   private final UploadJournalState state;
   private final UploadStorageState storageState;
   private final Integer attachmentId;
   private final Long fence;
   private final Instant leaseUntil;



   /*******************************************************************************
    ** Package-local; only the journal creates snapshots.
    *******************************************************************************/
   UploadEntry(Integer id, UploadCandidate candidate, UploadJournalState state, UploadStorageState storageState, Integer attachmentId, Long fence, Instant leaseUntil)
   {
      this.id = id;
      this.candidate = candidate;
      this.state = state;
      this.storageState = storageState;
      this.attachmentId = attachmentId;
      this.fence = fence;
      this.leaseUntil = leaseUntil;
   }



   /*******************************************************************************
    ** Journal row id.
    *******************************************************************************/
   public Integer id()
   {
      return (id);
   }



   /*******************************************************************************
    ** The preserved manifest, including the original actor and source.
    *******************************************************************************/
   public UploadCandidate candidate()
   {
      return (candidate);
   }



   /*******************************************************************************
    ** Registration progress.
    *******************************************************************************/
   public UploadJournalState state()
   {
      return (state);
   }



   /*******************************************************************************
    ** What is known about the original object.
    *******************************************************************************/
   public UploadStorageState storageState()
   {
      return (storageState);
   }



   /*******************************************************************************
    ** The registered attachment id, or null before the registering commit.
    *******************************************************************************/
   public Integer attachmentId()
   {
      return (attachmentId);
   }



   /*******************************************************************************
    ** Number of reservations ever granted; the fencing counter.
    *******************************************************************************/
   public Long fence()
   {
      return (fence);
   }



   /*******************************************************************************
    ** When the current lease lapses, or null when no lease is held.
    *******************************************************************************/
   public Instant leaseUntil()
   {
      return (leaseUntil);
   }



   /*******************************************************************************
    ** True while a lease is held as of the given instant.
    *******************************************************************************/
   public boolean isLeased(Instant now)
   {
      return (leaseUntil != null && leaseUntil.isAfter(now));
   }



   /*******************************************************************************
    ** Copy reflecting a newly granted lease.
    *******************************************************************************/
   UploadEntry leased(Long newFence, Instant newLeaseUntil)
   {
      return (new UploadEntry(id, candidate, state, storageState, attachmentId, newFence, newLeaseUntil));
   }



   /*******************************************************************************
    ** Never prints the manifest's bucket or key.
    *******************************************************************************/
   @Override
   public String toString()
   {
      return ("UploadEntry[id=" + id + ", state=" + state + ", storage=" + storageState + ", attachmentId=" + attachmentId + ", fence=" + fence + ", " + candidate + "]");
   }
}
