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


/*******************************************************************************
 ** Registration progress of a journaled retained upload. Only moves forward.
 ** NOT YET WIRED: no producer, table registration or service uses this yet.
 *******************************************************************************/
public enum UploadJournalState
{
   /*******************************************************************************
    ** Candidate is durable; no attachment row has been committed for it.
    *******************************************************************************/
   PREPARED,

   /*******************************************************************************
    ** The attachment row and its checkpoint committed together; the post-commit
    ** lifecycle registration has not yet been confirmed.
    *******************************************************************************/
   REGISTERED,

   /*******************************************************************************
    ** Lifecycle registration returned after the registering commit.
    *******************************************************************************/
   COMPLETE
}
