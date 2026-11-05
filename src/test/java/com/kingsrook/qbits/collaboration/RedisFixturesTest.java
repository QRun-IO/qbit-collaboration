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

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** The VM-local published-port request must reach the container definition.
 *******************************************************************************/
class RedisFixturesTest
{

   /***************************************************************************
    ** Default is Docker's ordinary random port; the property asks for host port 0.
    ***************************************************************************/
   @Test
   void explicitHostPortIsRequestedOnlyWhenEnabled()
   {
      String previous = System.getProperty(RedisFixtures.VM_LOCAL_PORT_PROPERTY);
      try
      {
         System.clearProperty(RedisFixtures.VM_LOCAL_PORT_PROPERTY);
         assertThat(RedisFixtures.redis().getPortBindings()).isEmpty();
         System.setProperty(RedisFixtures.VM_LOCAL_PORT_PROPERTY, "true");
         assertThat(RedisFixtures.redis().getPortBindings()).containsExactly("0:6379/tcp");
      }
      finally
      {
         if(previous == null)
         {
            System.clearProperty(RedisFixtures.VM_LOCAL_PORT_PROPERTY);
         }
         else
         {
            System.setProperty(RedisFixtures.VM_LOCAL_PORT_PROPERTY, previous);
         }
      }
   }

}
