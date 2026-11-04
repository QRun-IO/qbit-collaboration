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
 ** What the journal knows about the original object. The journal never reads or
 ** writes bytes; the storage owner reports these facts. Never moves backwards.
 *******************************************************************************/
public enum UploadStorageState
{
   /*******************************************************************************
    ** No write has been attempted that the journal knows of.
    *******************************************************************************/
   PENDING,

   /*******************************************************************************
    ** A write was attempted and its outcome is not known (timeout, crash). The
    ** object may exist; it stays visible and reconcilable and is never deleted.
    *******************************************************************************/
   UNKNOWN,

   /*******************************************************************************
    ** The storage owner verified the exact bytes and hash of the original.
    *******************************************************************************/
   VERIFIED
}
