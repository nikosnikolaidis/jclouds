/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.docker.compute.strategy;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.find;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.jclouds.compute.ComputeServiceAdapter;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.HardwareBuilder;
import org.jclouds.compute.domain.Processor;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.docker.DockerApi;
import org.jclouds.docker.compute.options.DockerTemplateOptions;
import org.jclouds.docker.domain.Config;
import org.jclouds.docker.domain.Container;
import org.jclouds.docker.domain.ContainerSummary;
import org.jclouds.docker.domain.HostConfig;
import org.jclouds.docker.domain.Image;
import org.jclouds.docker.domain.ImageSummary;
import org.jclouds.docker.options.CreateImageOptions;
import org.jclouds.docker.options.ListContainerOptions;
import org.jclouds.docker.options.RemoveContainerOptions;
import org.jclouds.domain.Location;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.logging.Logger;

/**
 * defines the connection between the {@link org.jclouds.docker.DockerApi} implementation and
 * the jclouds {@link org.jclouds.compute.ComputeService}
 */
@Singleton
public class DockerComputeServiceAdapter implements
        ComputeServiceAdapter<Container, Hardware, Image, Location> {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   private final DockerApi api;

   @Inject
   public DockerComputeServiceAdapter(DockerApi api) {
      this.api = checkNotNull(api, "api");
   }

   @SuppressWarnings({ "rawtypes", "unchecked" })
   @Override
   public NodeAndInitialCredentials<Container> createNodeWithGroupEncodedIntoName(String group, String name,
                                                                                  Template template) {
      checkNotNull(template, "template was null");
      TemplateOptions options = template.getOptions();
      checkNotNull(options, "template options was null");

      String imageId = checkNotNull(template.getImage().getId(), "template image id must not be null");
      String loginUser = template.getImage().getDefaultCredentials().getUser();
      String loginUserPassword = template.getImage().getDefaultCredentials().getOptionalPassword().or("password");

      DockerTemplateOptions templateOptions = DockerTemplateOptions.class.cast(options);

      Config containerConfig = null;
      Config.Builder containerConfigBuilder = templateOptions.getConfigBuilder();
      if (containerConfigBuilder == null) {
         containerConfigBuilder = Config.builder().image(imageId);

         containerConfigBuilder.entrypoint(templateOptions.getEntrypoint());
         containerConfigBuilder.cmd(templateOptions.getCommands());
         containerConfigBuilder.memory(templateOptions.getMemory());
         containerConfigBuilder.hostname(templateOptions.getHostname());
         containerConfigBuilder.cpuShares(templateOptions.getCpuShares());
         containerConfigBuilder.openStdin(templateOptions.getOpenStdin());
         containerConfigBuilder.env(templateOptions.getEnv());

         if (!templateOptions.getVolumes().isEmpty()) {
            Map<String, Object> volumes = Maps.newLinkedHashMap();
            for (String containerDir : templateOptions.getVolumes().values()) {
               volumes.put(containerDir, Maps.newHashMap());
            }
            containerConfigBuilder.volumes(volumes);
         }

         HostConfig.Builder hostConfigBuilder = HostConfig.builder()
               .publishAllPorts(true)
               .privileged(templateOptions.getPrivileged());

         if (!templateOptions.getPortBindings().isEmpty()) {
            Map<String, List<Map<String, String>>> portBindings = Maps.newHashMap();
            for (Map.Entry<Integer, Integer> entry : templateOptions.getPortBindings().entrySet()) {
               portBindings.put(entry.getValue() + "/tcp",
                     Lists.<Map<String, String>>newArrayList(ImmutableMap.of("HostIp", "0.0.0.0", "HostPort", Integer.toString(entry.getKey()))));
            }
            hostConfigBuilder.portBindings(portBindings);
         }

         if (!templateOptions.getDns().isEmpty()) {
            hostConfigBuilder.dns(templateOptions.getDns());
         }

         if (!templateOptions.getExtraHosts().isEmpty()) {
            List<String> extraHosts = Lists.newArrayList();
            for (Map.Entry<String, String> entry : templateOptions.getExtraHosts().entrySet()) {
               extraHosts.add(entry.getKey() + ":" + entry.getValue());
            }
            hostConfigBuilder.extraHosts(extraHosts);
         }

         if (!templateOptions.getVolumes().isEmpty()) {
            for (Map.Entry<String, String> entry : templateOptions.getVolumes().entrySet()) {
               hostConfigBuilder.binds(ImmutableList.of(entry.getKey() + ":" + entry.getValue()));
            }
         }

         if (!templateOptions.getVolumesFrom().isEmpty()) {
            hostConfigBuilder.volumesFrom(templateOptions.getVolumesFrom());
         }

         hostConfigBuilder.networkMode(templateOptions.getNetworkMode());

         containerConfigBuilder.hostConfig(hostConfigBuilder.build());

         // add the inbound ports into exposed ports map
         containerConfig = containerConfigBuilder.build();
         Map<String, Object> exposedPorts = Maps.newHashMap();
         if (containerConfig.exposedPorts() == null) {
            exposedPorts.putAll(containerConfig.exposedPorts());
         }
         for (int inboundPort : templateOptions.getInboundPorts()) {
            String portKey = inboundPort + "/tcp";
            if (!exposedPorts.containsKey(portKey)) {
               exposedPorts.put(portKey, Maps.newHashMap());
            }
         }
         containerConfigBuilder.exposedPorts(exposedPorts);

         // build once more after setting inboundPorts
         containerConfig = containerConfigBuilder.build();

         // finally update port bindings
         Map<String, List<Map<String, String>>> portBindings = Maps.newHashMap();
         Map<String, List<Map<String, String>>> existingBindings = containerConfig.hostConfig().portBindings();
         if (existingBindings != null) {
            portBindings.putAll(existingBindings);
         }
         for (String exposedPort : containerConfig.exposedPorts().keySet()) {
            if (!portBindings.containsKey(exposedPort)) {
               portBindings.put(exposedPort, Lists.<Map<String, String>>newArrayList(ImmutableMap.of("HostIp", "0.0.0.0")));
            }
         }
         hostConfigBuilder = HostConfig.builder().fromHostConfig(containerConfig.hostConfig());
         hostConfigBuilder.portBindings(portBindings);
         containerConfigBuilder.hostConfig(hostConfigBuilder.build());

      } else {
         containerConfigBuilder.image(imageId);
      }

      containerConfig = containerConfigBuilder.build();

      logger.debug(">> creating new container with containerConfig(%s)", containerConfig);
      Container container = api.getContainerApi().createContainer(name, containerConfig);
      logger.trace("<< container(%s)", container.id());

      HostConfig hostConfig = containerConfig.hostConfig();

      api.getContainerApi().startContainer(container.id(), hostConfig);
      container = api.getContainerApi().inspectContainer(container.id());
      if (container.state().exitCode() != 0) {
         destroyNode(container.id());
         throw new IllegalStateException(String.format("Container %s has not started correctly", container.id()));
      }
      return new NodeAndInitialCredentials(container, container.id(),
              LoginCredentials.builder().user(loginUser).password(loginUserPassword).build());
   }

   @Override
   public Iterable<Hardware> listHardwareProfiles() {
      Set<Hardware> hardware = Sets.newLinkedHashSet();
      // todo they are only placeholders at the moment
      hardware.add(new HardwareBuilder().ids("micro").hypervisor("lxc").name("micro").processor(new Processor(1, 1)).ram(512).build());
      hardware.add(new HardwareBuilder().ids("small").hypervisor("lxc").name("small").processor(new Processor(1, 1)).ram(1024).build());
      hardware.add(new HardwareBuilder().ids("medium").hypervisor("lxc").name("medium").processor(new Processor(2, 1)).ram(2048).build());
      hardware.add(new HardwareBuilder().ids("large").hypervisor("lxc").name("large").processor(new Processor(2, 1)).ram(3072).build());
      return hardware;
   }

   /**
    * Method based on {@link org.jclouds.docker.features.ImageApi#listImages()}. It retrieves additional
    * information by inspecting each image.
    *
    * @see org.jclouds.compute.ComputeServiceAdapter#listImages()
    */
   @Override
   public Set<Image> listImages() {
      Set<Image> images = Sets.newHashSet();
      for (ImageSummary imageSummary : api.getImageApi().listImages()) {
         // less efficient than just listImages but returns richer json that needs repoTags coming from listImages
         Image inspected = api.getImageApi().inspectImage(imageSummary.id());
         inspected = Image.create(inspected.id(), inspected.author(), inspected.comment(), inspected.config(),
                    inspected.containerConfig(), inspected.parent(), inspected.created(), inspected.container(),
                 inspected.dockerVersion(), inspected.architecture(), inspected.os(), inspected.size(),
                    inspected.virtualSize(), imageSummary.repoTags());
         images.add(inspected);
      }
      return images;
   }

   @Override
   public Image getImage(final String imageIdOrName) {
      checkNotNull(imageIdOrName);
      if (imageIdOrName.startsWith("sha256")) {
         // less efficient than just inspectImage but listImages return repoTags
         return find(listImages(), new Predicate<Image>() {
            @Override
            public boolean apply(Image input) {
               // Only attempt match on id as we should try to pull again anyway if using name
               return input.id().equals(imageIdOrName);
            }
         }, null);
      }

      // Image is not cached or getting image by name so try to pull it
      api.getImageApi().createImage(CreateImageOptions.Builder.fromImage(imageIdOrName));

      // as above this ensure repotags are returned
      return find(listImages(), new Predicate<Image>() {
         @Override
         public boolean apply(Image input) {
            for (String tag : input.repoTags()) {
               if (tag.equals(imageIdOrName) || tag.equals(imageIdOrName + ":latest")) {
                  return true;
               }
            }
            return false;
         }
      }, null);
   }

   @Override
   public Iterable<Container> listNodes() {
      Set<Container> containers = Sets.newHashSet();
      for (ContainerSummary containerSummary : api.getContainerApi().listContainers(ListContainerOptions.Builder.all(true))) {
         // less efficient than just listNodes but returns richer json
         containers.add(api.getContainerApi().inspectContainer(containerSummary.id()));
      }
      return containers;
   }

   @Override
   public Iterable<Container> listNodesByIds(final Iterable<String> ids) {
      Set<Container> containers = Sets.newHashSet();
      for (String id : ids) {
         containers.add(api.getContainerApi().inspectContainer(id));
      }
      return containers;
   }

   @Override
   public Iterable<Location> listLocations() {
      return ImmutableSet.of();
   }

   @Override
   public Container getNode(String id) {
      return api.getContainerApi().inspectContainer(id);
   }

   @Override
   public void destroyNode(String id) {
      api.getContainerApi().removeContainer(id, RemoveContainerOptions.Builder.force(true));
   }

   @Override
   public void rebootNode(String id) {
      api.getContainerApi().stopContainer(id);
      api.getContainerApi().startContainer(id);
   }

   @Override
   public void resumeNode(String id) {
      api.getContainerApi().unpause(id);
   }

   @Override
   public void suspendNode(String id) {
      api.getContainerApi().pause(id);
   }

}
