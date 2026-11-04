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
import java.util.List;
import java.util.Optional;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** Durable journal of retained upload candidates. The intended order, owned by
 ** AttachmentsService (this interface never reads, writes or deletes bytes):
 **
 **   1. prepare(candidate)         independently committed, before the first PUT
 **   2. reserve(id, lease)         exclusive fenced lease (original or recovery)
 **   3. recordStorage(UNKNOWN)     before the PUT; VERIFIED once bytes are proven
 **   4. register(reservation, cb)  attachment insert + checkpoint, one transaction
 **   5. (caller) lifecycle.afterStored, after the commit, idempotent and retried
 **   6. complete(reservation)      only after step 5 returned
 **
 ** A crash, timeout or failure at any step leaves the row visible; recovery
 ** reserves it again once the lease lapses, after the caller reauthorizes the
 ** original actor and the current source. Nothing here deletes an original.
 **
 ** AttachmentsService owns authorization and lifecycle policy around this port.
 *******************************************************************************/
public interface UploadJournal
{
   /*******************************************************************************
    ** Inserts the registering callback's attachment row. Runs inside the journal's
    ** owned transaction, which the callback must use for every write and must not
    ** commit, roll back or close. Returns the new attachment id. Throwing rolls
    ** back the insert and the checkpoint together.
    *******************************************************************************/
   @FunctionalInterface
   interface AttachmentRegistrar
   {
      /*******************************************************************************
       ** Insert the attachment metadata row for the journaled candidate.
       *******************************************************************************/
      Integer insert(QBackendTransaction transaction, UploadCandidate candidate, Integer journalId) throws QException;
   }



   /*******************************************************************************
    ** Durably record the candidate in its own committed transaction. Idempotent
    ** for an identical manifest; an immutable conflict for any different one.
    *******************************************************************************/
   UploadEntry prepare(UploadCandidate candidate) throws QException;



   /*******************************************************************************
    ** Take the exclusive lease, failing with LEASE_HELD while another holder's
    ** lease is live. Each grant increments the fence. Lease bounded 1s to 30m.
    *******************************************************************************/
   UploadReservation reserve(Integer journalId, Duration lease) throws QException;

   /*******************************************************************************
    ** Extend only a still-live exact reservation. Expired holders cannot renew.
    *******************************************************************************/
   UploadReservation renew(UploadReservation reservation, Duration lease) throws QException;



   /*******************************************************************************
    ** Report original-object knowledge. Forward only: PENDING to UNKNOWN to
    ** VERIFIED. Requires the live reservation.
    *******************************************************************************/
   UploadEntry recordStorage(UploadReservation reservation, UploadStorageState storageState) throws QException;



   /*******************************************************************************
    ** Requires VERIFIED storage and the live reservation. Inserts the attachment
    ** through the callback and checkpoints its id and REGISTERED in one same-database
    ** transaction. If already REGISTERED the callback is not called again and the
    ** existing entry is returned, so the attachment is never registered twice.
    *******************************************************************************/
   UploadEntry register(UploadReservation reservation, AttachmentRegistrar registrar) throws QException;



   /*******************************************************************************
    ** Mark COMPLETE after the post-commit lifecycle registration returned. Requires
    ** REGISTERED and the live reservation; repeating it for the same fence is a no-op.
    *******************************************************************************/
   UploadEntry complete(UploadReservation reservation) throws QException;



   /*******************************************************************************
    ** Give up the lease early so recovery need not wait. Changes no other data.
    ** Returns false, harmlessly, if the reservation was no longer the holder.
    *******************************************************************************/
   boolean release(UploadReservation reservation) throws QException;



   /*******************************************************************************
    ** Current snapshot of one row.
    *******************************************************************************/
   Optional<UploadEntry> find(Integer journalId) throws QException;



   /*******************************************************************************
    ** Snapshot found by the object location, hashed internally.
    *******************************************************************************/
   Optional<UploadEntry> findByLocation(String bucketName, String storageKey) throws QException;



   /*******************************************************************************
    ** Oldest-first rows that are not COMPLETE, for recovery sweeps. Limit 1 to 500.
    *******************************************************************************/
   List<UploadEntry> listIncomplete(int limit) throws QException;
}
