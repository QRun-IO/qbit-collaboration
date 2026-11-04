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


package com.kingsrook.qbits.collaboration.attachments;

import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Trusted host extension for attachment lifecycle policy. The default preserves
 ** ordinary Collaboration behavior. Implementations must be concurrency-safe and
 ** must not retain request context. Callbacks receive defensive record copies.
 ** This is not an untrusted scripting API or a new file-storage abstraction.
 *******************************************************************************/
public class AttachmentLifecyclePolicy
{
   /*******************************************************************************
    ** Configuration-only capability policy, evaluated during metadata production
    ** without a session. Dynamic parent visibility owners opt in to pinned counts.
    *******************************************************************************/
   public boolean requiresPinnedAggregateParent()
   {
      return (false);
   }

   /*******************************************************************************
    ** An installed retention owner must authorize an exact archival reference
    ** transfer in the caller's real transaction. Ordinary policies deny this.
    *******************************************************************************/
   public void beforeReferenceTransfer(QRecord attachment, String tableName, String recordId, QBackendTransaction transaction) throws QException
   {
      throw (new QException("Retained reference transfer requires an archive policy."));
   }

   /*******************************************************************************
    ** Persist the reference-transfer outcome in the same transaction. The policy
    ** must not commit or close the transaction; failure requires caller rollback.
    *******************************************************************************/
   public void afterReferenceTransfer(QRecord attachment, String tableName, String recordId, QBackendTransaction transaction) throws QException
   {
      throw (new QException("Retained reference transfer requires an atomic archive audit policy."));
   }

   /*******************************************************************************
    ** Called with canonical source, actual digest/size and persisted retention
    ** decision immediately before the first object write. There is no row ID yet.
    ** Retained originals require an independently durable intent before storage.
    *******************************************************************************/
   public void beforeStored(QRecord attachment) throws QException
   {
      if(Boolean.TRUE.equals(attachment.getValueBoolean("retained")))
      {
         throw (new QException("Retained storage requires a durable write-intent policy."));
      }
   }

   /*******************************************************************************
    ** Recheck current adoption authority after external operations, without
    ** creating another intent or granting access to the original's bytes.
    *******************************************************************************/
   public void authorizeAdoption(QRecord attachment) throws QException
   {
      throw (new QException("Existing-original adoption is not configured."));
   }

   /*******************************************************************************
    ** Explicit adoption of an existing owner original, not a new upload. The
    ** policy must authorize the current source and commit its intent before read.
    *******************************************************************************/
   public void beforeAdoption(QRecord attachment) throws QException
   {
      throw (new QException("Existing-original adoption requires an audited retention policy."));
   }

   /*******************************************************************************
    ** Record the retention transition in the owner's transaction. Registration
    ** follows separately; failure after commit leaves a retained retryable row.
    *******************************************************************************/
   public void afterAdoption(QRecord attachment, QBackendTransaction transaction) throws QException
   {
      throw (new QException("Existing-original adoption requires an atomic retention audit."));
   }

   /*******************************************************************************
    ** Called after storage and metadata insertion, before upload returns. Failure
    ** leaves the original intact for reconciliation; it is not a rollback hook.
    ** Enforce incomplete registration/quarantine in beforeAccess as appropriate.
    *******************************************************************************/
   public void afterStored(QRecord attachment) throws QException
   {
   }

   /*******************************************************************************
    ** Before reading an existing original for registration recovery. This is not
    ** a write attempt or a user download; retained owners must audit it explicitly.
    *******************************************************************************/
   public void beforeRecovery(QRecord candidate) throws QException
   {
      if(Boolean.TRUE.equals(candidate.getValueBoolean("retained")))
      {
         throw (new QException("Retained recovery requires a durable recovery-attempt policy."));
      }
   }

   /*******************************************************************************
    ** Called after parent authorization and before either preview or download URL
    ** signing. Throw to deny. A successful audit here means access was requested,
    ** not that bytes were downloaded. Mandatory audit must commit independently
    ** before returning; the caller transaction may still roll back afterwards.
    *******************************************************************************/
   public void beforeAccess(QRecord attachment, boolean inline) throws QException
   {
   }

   /*******************************************************************************
    ** Optional authenticated delivery after ordinary parent/lifecycle checks.
    ** Return null to retain the existing storage URL. No alternate lookup occurs.
    *******************************************************************************/
   public AttachmentDownload downloadDelivery(QRecord attachment) throws QException
   {
      return (null);
   }

   /*******************************************************************************
    ** Authorize and durably audit a server-side content read, distinct from URL
    ** issuance. Retained content requires an implementation of this new contract.
    *******************************************************************************/
   public void beforeContentRead(QRecord attachment) throws QException
   {
      if(Boolean.TRUE.equals(attachment.getValueBoolean("retained")))
      {
         throw (new QException("Retained content requires an audited content-read policy."));
      }
   }

   /*******************************************************************************
    ** Persist the verified server-read result before content leaves the service.
    ** This does not attest user download, printer spooling or physical output.
    *******************************************************************************/
   public void afterContentRead(QRecord attachment, Long bytesRead) throws QException
   {
      if(Boolean.TRUE.equals(attachment.getValueBoolean("retained")))
      {
         throw (new QException("Retained content requires an audited read-result policy."));
      }
   }

   /*******************************************************************************
    ** Return true to prohibit deletion and preserve bytes on parent cleanup.
    ** Called during intake before storage with complete metadata but no row id;
    ** true is persisted on the attachment and cannot be cleared by this API.
    ** It is also queried for legacy rows during deletion/read hints. Failure
    ** denies deletion. Do not depend on parent existence or caller permissions.
    *******************************************************************************/
   public boolean isRetained(QRecord attachment) throws QException
   {
      return (false);
   }

   /*******************************************************************************
    ** Called after ownership/parent checks and before applying display metadata.
    ** The second record contains id and proposed fields, not the complete row.
    ** This records an intent only; it does not attest that a transaction committed.
    *******************************************************************************/
   public void beforeMetadataChange(QRecord attachment, QRecord changes) throws QException
   {
   }

   /*******************************************************************************
    ** Validate the actual owner transaction before applying display metadata.
    ** Required providers must reject unsupported/no-op transactions and incompatible
    ** audit backends before the first write. This method does not commit anything.
    *******************************************************************************/
   public void validateMetadataTransaction(QRecord attachment, QBackendTransaction transaction) throws QException
   {
      if(Boolean.TRUE.equals(attachment.getValueBoolean("retained")))
      {
         throw (new QException("Retained metadata requires an atomic audit transaction policy."));
      }
   }

   /*******************************************************************************
    ** Record the successful display metadata change in the supplied transaction
    ** before its owner commits. Required providers must validate real transaction
    ** and backend compatibility, insert their event using this transaction and
    ** throw on failure. Never commit or close the supplied owner transaction.
    *******************************************************************************/
   public void afterMetadataChange(QRecord attachment, QRecord changes, QBackendTransaction transaction) throws QException
   {
      if(Boolean.TRUE.equals(attachment.getValueBoolean("retained")))
      {
         throw (new QException("Retained metadata requires an atomic audit-result policy."));
      }
   }
}
