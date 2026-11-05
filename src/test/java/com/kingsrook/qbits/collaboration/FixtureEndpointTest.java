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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** The topology decision of the sibling-container fixtures.
 *******************************************************************************/
class FixtureEndpointTest
{
   private static final String CONTAINER_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";



   /***************************************************************************
    ** Build container on the default bridge: dial the sibling bridge IP and its own port, never the published port.
    ***************************************************************************/
   @Test
   void buildContainerOnTheBridgeUsesTheBridgeAddress()
   {
      assertEquals(new FixtureEndpoint.Address("172.17.0.9", 5432), FixtureEndpoint.choose(true, "172.17.0.9", 5432, "localhost", 41111));
      assertEquals(new FixtureEndpoint.Address("172.17.0.9", 5432), FixtureEndpoint.choose(true, "172.17.0.9", 5432, null, null));
   }



   /***************************************************************************
    ** Every other topology (JVM on the Docker host, a separate or remote daemon such as the native runner's isolated
    ** test daemon, job network, host networking, unidentified build container) dials the published endpoint
    ** at the host Testcontainers reports (including TESTCONTAINERS_HOST_OVERRIDE), never a private bridge address.
    ***************************************************************************/
   @Test
   void everyOtherTopologyDialsThePublishedEndpoint()
   {
      assertEquals(new FixtureEndpoint.Address("localhost", 41111), FixtureEndpoint.choose(false, "172.17.0.9", 5432, "localhost", 41111));
      assertEquals(new FixtureEndpoint.Address("172.17.0.1", 32768), FixtureEndpoint.choose(false, "172.17.0.3", 6379, "172.17.0.1", 32768));
      assertEquals(new FixtureEndpoint.Address("host.docker.internal", 40000), FixtureEndpoint.choose(false, null, 5432, "host.docker.internal", 40000));
   }



   /***************************************************************************
    ** An unknown endpoint is an error, never a guessed address.
    ***************************************************************************/
   @Test
   void missingEndpointFailsClosed()
   {
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(true, null, 5432, "localhost", 41111));
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(true, " ", 5432, "localhost", 41111));
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(false, "172.17.0.9", 5432, "localhost", null));
      assertThrows(IllegalStateException.class, () -> FixtureEndpoint.choose(false, "172.17.0.9", 5432, " ", 41111));
   }



   /***************************************************************************
    ** The historical loopback-only publication stays wherever a loopback dial can work; when Testcontainers dials
    ** another host (the native runner's separate daemon) the daemon's default binding is used so the dial can succeed.
    ***************************************************************************/
   @Test
   void loopbackPublicationOnlyWhereALoopbackDialCanWork()
   {
      assertTrue(FixtureEndpoint.loopbackPublication(false, "localhost"));
      assertTrue(FixtureEndpoint.loopbackPublication(false, "127.0.0.1"));
      assertTrue(FixtureEndpoint.loopbackPublication(false, "::1"));
      assertTrue(FixtureEndpoint.loopbackPublication(false, null));
      assertTrue(FixtureEndpoint.loopbackPublication(true, "172.17.0.1"));
      assertFalse(FixtureEndpoint.loopbackPublication(false, "172.17.0.1"));
      assertFalse(FixtureEndpoint.loopbackPublication(false, "host.docker.internal"));
      assertFalse(FixtureEndpoint.loopbackPublication(false, "docker.example.test"));
   }



   /***************************************************************************
    ** The build container id comes from the mount table when present, else the host name.
    ***************************************************************************/
   @Test
   void ownContainerIdPrefersTheMountTableThenTheHostName()
   {
      String mountInfo = "700 699 0:50 /docker/containers/" + CONTAINER_ID + "/hostname /etc/hostname rw,relatime - ext4 /dev/sda1 rw";
      assertEquals(CONTAINER_ID, FixtureEndpoint.ownContainerId(mountInfo, "unrelated"));
      assertEquals("abc123", FixtureEndpoint.ownContainerId("no container mounts here", " abc123 "));
      assertNull(FixtureEndpoint.ownContainerId("", null));
      assertNull(FixtureEndpoint.ownContainerId(null, " "));
   }

}
