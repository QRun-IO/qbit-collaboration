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

import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/*******************************************************************************
 ** Disposable Redis servers for presence tests.
 **
 ** Docker Desktop publishes an unqualified random port only on the Windows side;
 ** a Linux build container then reaches it through host.docker.internal, which
 ** was measured stalling established connections for 300 ms to 2 s (server saw
 ** no bytes during the stall; the Docker bridge and the VM-local published port
 ** showed none). Setting "fixture.redis.vmLocalPort=true" asks Docker for an
 ** explicit host port 0 (same ephemeral allocation on a normal Docker host),
 ** which Docker Desktop binds inside the VM. The client, server, scripts and
 ** every assertion are unchanged.
 *******************************************************************************/
final class RedisFixtures
{
   static final String VM_LOCAL_PORT_PROPERTY = "fixture.redis.vmLocalPort";



   /***************************************************************************
    ** Create (not start) the Redis fixture used by all presence tests.
    ***************************************************************************/
   static GenericContainer<?> redis()
   {
      GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
      if(Boolean.getBoolean(VM_LOCAL_PORT_PROPERTY))
      {
         redis.setPortBindings(List.of("0:6379"));
      }
      return (redis);
   }



   /***************************************************************************
    ** Host this JVM must dial: the sibling's bridge address only when this JVM is in a
    ** container the same daemon reports on its default bridge, otherwise the
    ** published endpoint at the host Testcontainers reports (see FixtureEndpoint).
    ***************************************************************************/
   static String host(GenericContainer<?> redis)
   {
      return (FixtureEndpoint.of(redis, 6379).host());
   }



   /***************************************************************************
    ** Port matching {@link #host(GenericContainer)}.
    ***************************************************************************/
   static int port(GenericContainer<?> redis)
   {
      return (FixtureEndpoint.of(redis, 6379).port());
   }

}
