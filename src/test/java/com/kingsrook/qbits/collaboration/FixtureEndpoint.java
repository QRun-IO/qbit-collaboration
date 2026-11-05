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


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;


/*******************************************************************************
 ** Where a disposable test container is reachable FROM THIS JVM.
 **
 ** The original behavior is Testcontainers' own: the host it reports (which honors
 ** TESTCONTAINERS_HOST_OVERRIDE) and the published port. The bridge address of the
 ** sibling is used in exactly one topology: a JVM inside a build container that the
 ** SAME daemon reports on its default bridge (a local full verify through the host
 ** socket). Being inside some container is not enough: on the native runner the job
 ** container is not a container of the isolated test daemon at all, so a sibling's
 ** bridge address (a private address of that other daemon) is unreachable and the
 ** published endpoint at the configured host must be dialed.
 **
 ** The choice is made from the daemon's own description of the build container's
 ** networks (no connection probing). If the build container cannot be identified or
 ** is not on the default bridge (a job network, host networking, a remote or
 ** separate daemon), the published endpoint is used. Timeouts, scenarios and
 ** assertions are unchanged. The address is re-read from the daemon on every call
 ** (a restarted container may get another bridge IP or published port).
 *******************************************************************************/
final class FixtureEndpoint
{
   private static final String DEFAULT_BRIDGE = "bridge";

   private static final Pattern MOUNTINFO_CONTAINER_ID = Pattern.compile("/docker/containers/([0-9a-f]{64})/");

   private static volatile Boolean buildContainerOnDefaultBridge;



   /***************************************************************************
    ** Host and port to dial.
    ***************************************************************************/
   record Address(String host, int port)
   {
   }



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private FixtureEndpoint()
   {
   }



   /***************************************************************************
    ** Resolve the endpoint of one exposed port of a running container, from a fresh
    ** inspection (not Testcontainers' cached mapping).
    ***************************************************************************/
   static Address of(GenericContainer<?> container, int internalPort)
   {
      InspectContainerResponse inspected = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec();
      var networks = inspected.getNetworkSettings().getNetworks();
      String bridgeIp = networks == null || networks.get(DEFAULT_BRIDGE) == null ? null : networks.get(DEFAULT_BRIDGE).getIpAddress();
      Integer publishedPort = null;
      var bindings = inspected.getNetworkSettings().getPorts().getBindings().get(new ExposedPort(internalPort));
      if(bindings != null && bindings.length > 0 && bindings[0].getHostPortSpec() != null)
      {
         publishedPort = Integer.valueOf(bindings[0].getHostPortSpec());
      }
      return (choose(onBridgeWithBuildContainer(), bridgeIp, internalPort, container.getHost(), publishedPort));
   }



   /***************************************************************************
    ** Pure decision, separately tested. With the build container on the default
    ** bridge: the sibling's bridge address and its own port. Otherwise the published
    ** endpoint exactly as Testcontainers reports it.
    ***************************************************************************/
   static Address choose(boolean buildContainerOnDefaultBridge, String bridgeIp, int internalPort, String publishedHost, Integer publishedPort)
   {
      if(buildContainerOnDefaultBridge)
      {
         if(bridgeIp == null || bridgeIp.isBlank())
         {
            throw new IllegalStateException("FIXTURE_ENDPOINT_NO_BRIDGE_ADDRESS: the build container cannot reach a sibling without its bridge address");
         }
         return (new Address(bridgeIp, internalPort));
      }
      if(publishedHost == null || publishedHost.isBlank() || publishedPort == null)
      {
         throw new IllegalStateException("FIXTURE_ENDPOINT_NOT_PUBLISHED: no published endpoint to dial");
      }
      return (new Address(publishedHost, publishedPort));
   }



   /***************************************************************************
    ** How the fixture publishes its port: the historical loopback-only binding
    ** wherever a loopback dial can work, otherwise the daemon's own default binding.
    ***************************************************************************/
   static PortBinding publishedBinding(int internalPort)
   {
      boolean loopback = loopbackPublication(onBridgeWithBuildContainer(), DockerClientFactory.instance().dockerHostIpAddress());
      return (new PortBinding(loopback ? Ports.Binding.bindIpAndPort("127.0.0.1", 0) : Ports.Binding.empty(), new ExposedPort(internalPort)));
   }



   /***************************************************************************
    ** Pure decision, separately tested. A loopback-only publication is reachable
    ** when the build container dials the bridge address (publication unused) or the
    ** Docker host is this machine; if Testcontainers dials another host (a remote or
    ** separate daemon, TESTCONTAINERS_HOST_OVERRIDE) a loopback binding could never
    ** be reached, so the daemon's configured default binding is used instead.
    ***************************************************************************/
   static boolean loopbackPublication(boolean buildContainerOnDefaultBridge, String dockerHost)
   {
      return (buildContainerOnDefaultBridge || dockerHost == null || dockerHost.isBlank()
         || dockerHost.equals("localhost") || dockerHost.equals("127.0.0.1") || dockerHost.equals("::1"));
   }



   /***************************************************************************
    ** Whether this JVM runs in a container that the daemon reports on the default
    ** bridge. Decided once per JVM from the daemon's description of this container.
    ***************************************************************************/
   private static boolean onBridgeWithBuildContainer()
   {
      Boolean known = buildContainerOnDefaultBridge;
      if(known == null)
      {
         known = decideBuildContainerOnDefaultBridge();
         buildContainerOnDefaultBridge = known;
      }
      return (known);
   }



   /***************************************************************************
    ** Inspect the build container; anything that cannot be established means the
    ** original published endpoint (false).
    ***************************************************************************/
   private static boolean decideBuildContainerOnDefaultBridge()
   {
      if(!Files.exists(Path.of("/.dockerenv")))
      {
         return (false);
      }
      try
      {
         String id = ownContainerId(Files.readString(Path.of("/proc/self/mountinfo")), System.getenv("HOSTNAME"));
         if(id == null)
         {
            return (false);
         }
         var networks = DockerClientFactory.instance().client().inspectContainerCmd(id).exec().getNetworkSettings().getNetworks();
         return (networks != null && networks.containsKey(DEFAULT_BRIDGE));
      }
      catch(Exception e)
      {
         return (false);
      }
   }



   /***************************************************************************
    ** The id of the container this JVM runs in: from the mount table when it names
    ** the container directory, otherwise the host name (the container id unless
    ** overridden); null when neither exists.
    ***************************************************************************/
   static String ownContainerId(String mountInfo, String hostName)
   {
      Matcher matcher = MOUNTINFO_CONTAINER_ID.matcher(mountInfo == null ? "" : mountInfo);
      if(matcher.find())
      {
         return (matcher.group(1));
      }
      return (hostName == null || hostName.isBlank() ? null : hostName.trim());
   }

}
