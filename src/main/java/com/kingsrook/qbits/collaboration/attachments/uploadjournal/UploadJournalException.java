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


import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** Journal failure with a machine-readable reason. Messages never contain the
 ** bucket, storage key or lease token.
 *******************************************************************************/
public class UploadJournalException extends QException
{
   /*******************************************************************************
    ** Why the journal refused or failed an operation.
    *******************************************************************************/
   public enum Reason
   {
      /***************************************************************************
       ** Another transaction held the row past the bounded wait.
       ***************************************************************************/
      BUSY,
      /***************************************************************************
       ** A live lease belongs to another holder.
       ***************************************************************************/
      LEASE_HELD,
      /***************************************************************************
       ** The reservation's token, fence or lease no longer owns the row.
       ***************************************************************************/
      STALE_RESERVATION,
      /***************************************************************************
       ** Same locator or intake key, different immutable manifest (or tampered row).
       ***************************************************************************/
      IMMUTABLE_CONFLICT,
      /***************************************************************************
       ** The operation does not apply to the row's current state.
       ***************************************************************************/
      ILLEGAL_STATE,
      /***************************************************************************
       ** Registration requires VERIFIED original storage.
       ***************************************************************************/
      STORAGE_NOT_VERIFIED,
      /***************************************************************************
       ** No such journal row.
       ***************************************************************************/
      NOT_FOUND,
      /***************************************************************************
       ** Backend is not the qualified file-backed H2 configuration.
       ***************************************************************************/
      UNSUPPORTED_BACKEND,
      /***************************************************************************
       ** The journal owns its transactions and refuses an ambient one.
       ***************************************************************************/
      AMBIENT_TRANSACTION,
      /***************************************************************************
       ** Unique constraint hit; handled internally, not normally surfaced.
       ***************************************************************************/
      DUPLICATE,
      /***************************************************************************
       ** Other database failure.
       ***************************************************************************/
      DATABASE
   }

   private final Reason reason;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public UploadJournalException(Reason reason, String message)
   {
      super(message);
      this.reason = reason;
   }



   /*******************************************************************************
    ** Constructor with cause.
    *******************************************************************************/
   public UploadJournalException(Reason reason, String message, Throwable cause)
   {
      super(message, cause);
      this.reason = reason;
   }



   /*******************************************************************************
    ** The refusal reason.
    *******************************************************************************/
   public Reason getReason()
   {
      return (reason);
   }
}
