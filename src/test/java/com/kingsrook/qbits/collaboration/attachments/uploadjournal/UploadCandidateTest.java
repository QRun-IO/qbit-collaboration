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


import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Pure validation and hashing of the immutable candidate; no database.
 ** NOT YET RUN: written without a build.
 *******************************************************************************/
public class UploadCandidateTest
{
   private static final String HASH = "a".repeat(64);



   /*******************************************************************************
    ** A server-shaped candidate with overridable pieces.
    *******************************************************************************/
   private static UploadCandidate make(String fileName, String description, Long size, Boolean retained, String intakeKey, String fingerprint)
   {
      return (new UploadCandidate("document", "7", "alice", fileName, "application/pdf", description, size, HASH, "bucket", "attachments/key", retained,
         intakeKey, fingerprint));
   }



   /*******************************************************************************
    ** Only retained uploads are journaled.
    *******************************************************************************/
   @Test
   void nonRetainedUploadsAreRejected()
   {
      assertThatThrownBy(() -> make("a.pdf", null, 1L, false, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("a.pdf", null, 1L, null, null, null)).isInstanceOf(IllegalArgumentException.class);
   }



   /*******************************************************************************
    ** Bounds, control characters, hashes and the intake pair are enforced.
    *******************************************************************************/
   @Test
   void membersAreBounded()
   {
      assertThatThrownBy(() -> make("x".repeat(256), null, 1L, true, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("bad\nname", null, 1L, true, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make(" ", null, 1L, true, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("a.pdf", "d".repeat(4097), 1L, true, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("a.pdf", null, -1L, true, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("a.pdf", null, UploadCandidate.MAX_SIZE_BYTES + 1, true, null, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("a.pdf", null, 1L, true, HASH, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> make("a.pdf", null, 1L, true, "short", HASH)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new UploadCandidate("document", "7", "alice", "a.pdf", "application/pdf", null, 1L, "NOT-HEX", "bucket", "key", true, null, null))
         .isInstanceOf(IllegalArgumentException.class);
      assertThat(make("a.pdf", "line one\nline two", 0L, true, HASH, HASH).description()).contains("\n");
   }



   /*******************************************************************************
    ** The locator hash covers bucket and key only; the manifest hash covers all.
    ** toString never prints the bucket, key, description or actor.
    *******************************************************************************/
   @Test
   void hashesAreStableAndRedacted()
   {
      UploadCandidate one = make("a.pdf", "secret description", 1L, true, null, null);
      UploadCandidate sameLocationOtherName = make("b.pdf", "secret description", 1L, true, null, null);
      assertThat(one.locatorHash()).hasSize(64).isEqualTo(sameLocationOtherName.locatorHash()).isEqualTo(UploadCandidate.locatorHash("bucket", "attachments/key"));
      assertThat(one.manifestHash()).hasSize(64).isNotEqualTo(sameLocationOtherName.manifestHash());
      assertThat(UploadCandidate.locatorHash("ab", "c")).isNotEqualTo(UploadCandidate.locatorHash("a", "bc"));
      assertThat(one.toString()).doesNotContain("bucket", "attachments/key", "secret description", "alice").contains(one.locatorHash());
   }
}
