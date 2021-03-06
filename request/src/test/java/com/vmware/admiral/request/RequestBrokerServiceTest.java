/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.vmware.admiral.adapter.common.ContainerOperationType;
import com.vmware.admiral.common.test.CommonTestStateFactory;
import com.vmware.admiral.common.util.UriUtilsExtended;
import com.vmware.admiral.compute.ComputeConstants;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.CompositeComponentFactoryService;
import com.vmware.admiral.compute.container.CompositeComponentService.CompositeComponent;
import com.vmware.admiral.compute.container.CompositeDescriptionService.CompositeDescription;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerFactoryService;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.compute.container.GroupResourcePlacementService;
import com.vmware.admiral.compute.container.GroupResourcePlacementService.GroupResourcePlacementState;
import com.vmware.admiral.compute.container.ServiceNetwork;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService.ContainerNetworkDescription;
import com.vmware.admiral.compute.container.network.ContainerNetworkService;
import com.vmware.admiral.compute.container.network.ContainerNetworkService.ContainerNetworkState;
import com.vmware.admiral.compute.container.volume.ContainerVolumeDescriptionService;
import com.vmware.admiral.compute.container.volume.ContainerVolumeDescriptionService.ContainerVolumeDescription;
import com.vmware.admiral.compute.container.volume.ContainerVolumeService;
import com.vmware.admiral.compute.container.volume.ContainerVolumeService.ContainerVolumeState;
import com.vmware.admiral.log.EventLogService.EventLogState;
import com.vmware.admiral.log.EventLogService.EventLogState.EventLogType;
import com.vmware.admiral.request.ContainerAllocationTaskService.ContainerAllocationTaskState;
import com.vmware.admiral.request.RequestBrokerService.RequestBrokerState;
import com.vmware.admiral.request.RequestBrokerService.RequestBrokerState.SubStage;
import com.vmware.admiral.request.RequestStatusService.RequestStatus;
import com.vmware.admiral.request.ReservationTaskService.ReservationTaskState;
import com.vmware.admiral.request.composition.CompositionSubTaskService;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.admiral.service.test.MockDockerAdapterService;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ComputeService.PowerState;
import com.vmware.photon.controller.model.resources.ResourcePoolService;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.AuthCredentialsService;

public class RequestBrokerServiceTest extends RequestBaseTest {

    @Test
    public void testRequestLifeCycle() throws Throwable {
        host.log("########  Start of testRequestLifeCycle ######## ");
        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();
        createDockerHost(dockerHostDesc, resourcePool);

        // setup Container desc:
        ContainerDescription containerDesc = createContainerDescription();

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a container instance:
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        String requestSelfLink = request.documentSelfLink;
        request = waitForRequestToComplete(request);

        // 2. Reservation stage:
        String rsrvSelfLink = UriUtils.buildUriPath(ReservationTaskFactoryService.SELF_LINK,
                extractId(requestSelfLink));
        ReservationTaskState rsrvTask = getDocument(ReservationTaskState.class, rsrvSelfLink);
        assertNotNull(rsrvTask);
        assertEquals(request.resourceDescriptionLink, rsrvTask.resourceDescriptionLink);
        assertEquals(requestSelfLink, rsrvTask.serviceTaskCallback.serviceSelfLink);
        assertEquals(request.tenantLinks, rsrvTask.tenantLinks);

        // 3. Allocation stage:
        String allocationSelfLink = UriUtils.buildUriPath(
                ContainerAllocationTaskFactoryService.SELF_LINK,
                extractId(requestSelfLink));
        ContainerAllocationTaskState allocationTask = getDocument(
                ContainerAllocationTaskState.class, allocationSelfLink);
        assertNotNull(allocationTask);
        assertEquals(request.resourceDescriptionLink, allocationTask.resourceDescriptionLink);
        if (allocationTask.serviceTaskCallback == null) {
            allocationTask = getDocument(ContainerAllocationTaskState.class, allocationSelfLink);
        }
        assertEquals(requestSelfLink, allocationTask.serviceTaskCallback.serviceSelfLink);

        request = getDocument(RequestBrokerState.class, requestSelfLink);

        assertNotNull("ResourceLinks null for requestSelfLink: " + requestSelfLink,
                request.resourceLinks);
        assertEquals(1, request.resourceLinks.size());
        ContainerState containerState = getDocument(ContainerState.class,
                request.resourceLinks.get(0));
        assertNotNull(containerState);

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);
        assertEquals(Integer.valueOf(100), rs.progress);

        // 4. Remove the container
        request = TestRequestStateFactory.createRequestState();
        request.operation = ContainerOperationType.DELETE.id;
        request.resourceLinks = new ArrayList<>();
        request.resourceLinks.add(containerState.documentSelfLink);
        request = startRequest(request);

        request = waitForRequestToComplete(request);

        // Verify the container is removed
        containerState = searchForDocument(ContainerState.class, request.resourceLinks.get(0));
        assertNull(containerState);

        // Verify request status
        rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);
        assertEquals(Integer.valueOf(100), rs.progress);
    }

    @Test
    public void testCompositeComponentRequestLifeCycle() throws Throwable {
        host.log("########  Start of testCompositeCompositeRequestLifeCycle ######## ");
        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();
        createDockerHost(dockerHostDesc, resourcePool);

        // setup Composite description with 1 container
        CompositeDescription compositeDesc = createCompositeDesc(containerDesc);

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a composite container:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        String requestSelfLink = request.documentSelfLink;
        String requestId = extractId(requestSelfLink);
        request = waitForRequestToComplete(request);

        // 2. Reservation stage:
        String allocationTaskId = requestId + "-" + containerDesc.name
                + CompositionSubTaskService.ALLOC_SUFFIX;
        String rsrvSelfLink = UriUtils.buildUriPath(ReservationTaskFactoryService.SELF_LINK,
                allocationTaskId);
        ReservationTaskState rsrvTask = getDocument(ReservationTaskState.class, rsrvSelfLink);
        assertNotNull(rsrvTask);
        String containerDescLink = UriUtils.buildUriPath(ContainerDescriptionService.FACTORY_LINK,
                containerDesc.documentSelfLink);
        String rsrvRequestSelfLink = UriUtils.buildUriPath(RequestBrokerFactoryService.SELF_LINK,
                allocationTaskId);
        assertEquals(containerDescLink, rsrvTask.resourceDescriptionLink);
        assertEquals(rsrvRequestSelfLink, rsrvTask.serviceTaskCallback.serviceSelfLink);
        assertEquals(request.tenantLinks, rsrvTask.tenantLinks);

        // 3. Allocation stage:
        String allocationSelfLink = UriUtils
                .buildUriPath(ContainerAllocationTaskFactoryService.SELF_LINK, allocationTaskId);
        ContainerAllocationTaskState allocationTask = getDocument(
                ContainerAllocationTaskState.class, allocationSelfLink);
        assertNotNull(allocationTask);
        assertEquals(containerDescLink, allocationTask.resourceDescriptionLink);
        if (allocationTask.serviceTaskCallback == null) {
            allocationTask = getDocument(ContainerAllocationTaskState.class, allocationSelfLink);
        }
        assertEquals(rsrvRequestSelfLink, allocationTask.serviceTaskCallback.serviceSelfLink);

        assertNotNull("ResourceLinks null for requestSelfLink: " + requestSelfLink,
                request.resourceLinks);
        assertEquals(1, request.resourceLinks.size());
        ContainerState containerState = getDocument(ContainerState.class,
                request.resourceLinks.get(0));
        assertNotNull(containerState);

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);
        assertEquals(Integer.valueOf(100), rs.progress);

        // 4. Remove the composite component
        request = TestRequestStateFactory.createRequestState();
        request.resourceType = ResourceType.COMPOSITE_COMPONENT_TYPE.getName();
        request.operation = ContainerOperationType.DELETE.id;
        request.resourceLinks = new ArrayList<>();
        request.resourceLinks.add(UriUtils.buildUriPath(CompositeComponentFactoryService.SELF_LINK,
                extractId(requestSelfLink)));
        request = startRequest(request);

        request = waitForRequestToComplete(request);

        // Verify the container is removed
        containerState = searchForDocument(ContainerState.class, request.resourceLinks.get(0));
        assertNull(containerState);

        // Verify request status
        rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);
        assertEquals(Integer.valueOf(100), rs.progress);
    }

    @Test
    public void testCompositeComponentWithContainerNetworkRequestLifeCycle() throws Throwable {
        host.log(
                "########  Start of testCompositeComponentWithContainerNetworkRequestLifeCycle ######## ");

        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();

        delete(computeHost.documentSelfLink);
        computeHost = null;

        ComputeState dockerHost1 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost1);

        ComputeState dockerHost2 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost2);

        // setup Composite description with 2 containers and 1 network

        String networkName = "mynet";

        ContainerNetworkDescription networkDesc = TestRequestStateFactory
                .createContainerNetworkDescription(networkName);
        networkDesc.documentSelfLink = UUID.randomUUID().toString();

        ContainerDescription container1Desc = TestRequestStateFactory.createContainerDescription();
        container1Desc.documentSelfLink = UUID.randomUUID().toString();
        container1Desc.name = "container1";
        container1Desc.networks = new HashMap<>();
        container1Desc.networks.put(networkName, new ServiceNetwork());

        ContainerDescription container2Desc = TestRequestStateFactory.createContainerDescription();
        container2Desc.documentSelfLink = UUID.randomUUID().toString();
        container2Desc.name = "container2";
        container2Desc.affinity = new String[] { "!container1:hard" };
        container2Desc.networks = new HashMap<>();
        container2Desc.networks.put(networkName, new ServiceNetwork());

        CompositeDescription compositeDesc = createCompositeDesc(networkDesc, container1Desc,
                container2Desc);
        assertNotNull(compositeDesc);

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a composite container:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        request = waitForRequestToComplete(request);

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);

        assertEquals(Integer.valueOf(100), rs.progress);
        assertEquals(1, request.resourceLinks.size());

        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.get(0));

        String networkLink = null;
        String containerLink1 = null;
        String containerLink2 = null;

        Iterator<String> iterator = cc.componentLinks.iterator();
        while (iterator.hasNext()) {
            String link = iterator.next();
            if (link.startsWith(ContainerNetworkService.FACTORY_LINK)) {
                networkLink = link;
            } else if (containerLink1 == null) {
                containerLink1 = link;
            } else {
                containerLink2 = link;
            }
        }

        ContainerState cont1 = getDocument(ContainerState.class, containerLink1);
        ContainerState cont2 = getDocument(ContainerState.class, containerLink2);

        boolean containerIsProvisionedOnAnyHosts = cont1.parentLink
                .equals(dockerHost1.documentSelfLink)
                || cont1.parentLink.equals(dockerHost2.documentSelfLink);
        assertTrue(containerIsProvisionedOnAnyHosts);

        containerIsProvisionedOnAnyHosts = cont2.parentLink.equals(dockerHost1.documentSelfLink)
                || cont2.parentLink.equals(dockerHost2.documentSelfLink);
        assertTrue(containerIsProvisionedOnAnyHosts);

        // provisioned on different hosts
        assertFalse(cont1.parentLink.equals(cont2.parentLink));

        ContainerNetworkState network = getDocument(ContainerNetworkState.class, networkLink);
        boolean networkIsProvisionedOnAnyHosts = network.originatingHostLink
                .equals(dockerHost1.documentSelfLink)
                || network.originatingHostLink.equals(dockerHost2.documentSelfLink);
        assertTrue(networkIsProvisionedOnAnyHosts);

        assertEquals(cc.documentSelfLink, cont1.compositeComponentLink);
        assertEquals(cc.documentSelfLink, cont2.compositeComponentLink);
        assertTrue((network.compositeComponentLinks.size() == 1)
                && network.compositeComponentLinks.contains(cc.documentSelfLink));
    }

    @Test
    public void testRequestLifeCycleWithContainerNetworkFailureShouldCleanNetworks()
            throws Throwable {
        host.log(
                "########  Start of testRequestLifeCycleWithContainerNetworkFailureShouldCleanNetworks ######## ");

        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();

        delete(computeHost.documentSelfLink);
        computeHost = null;

        ComputeState dockerHost1 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost1);

        ComputeState dockerHost2 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost2);

        // setup Composite description with 2 containers and 1 network

        String networkName = "mynet";

        ContainerNetworkDescription networkDesc = TestRequestStateFactory
                .createContainerNetworkDescription(networkName);
        networkDesc.documentSelfLink = UUID.randomUUID().toString();

        ContainerDescription container1Desc = TestRequestStateFactory.createContainerDescription();
        container1Desc.documentSelfLink = UUID.randomUUID().toString();
        container1Desc.name = "container1";
        container1Desc.networks = new HashMap<>();
        container1Desc.networks.put(networkName, new ServiceNetwork());

        ContainerDescription container2Desc = TestRequestStateFactory.createContainerDescription();
        container2Desc.documentSelfLink = UUID.randomUUID().toString();
        container2Desc.name = "container2";
        container2Desc.affinity = new String[] { "!container1:hard" };
        container2Desc.networks = new HashMap<>();
        container2Desc.networks.put(networkName, new ServiceNetwork());
        container2Desc.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED,
                Boolean.TRUE.toString());

        CompositeDescription compositeDesc = createCompositeDesc(networkDesc, container1Desc,
                container2Desc);
        assertNotNull(compositeDesc);

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a composite container with expected failure:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // 2. Wait for reservation removed substage
        waitForRequestToFail(request);

        // 3. Verify that the group placement has been released.
        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals(groupPlacementState.allocatedInstancesCount, 0);

        String containerLink1 = null;
        String containerLink2 = null;

        Iterator<String> iterator = compositeDesc.descriptionLinks.iterator();
        while (iterator.hasNext()) {
            String link = iterator.next();
            if (link.startsWith(ContainerNetworkDescriptionService.FACTORY_LINK)) {
                continue;
            } else if (containerLink1 == null) {
                containerLink1 = link;
            } else {
                containerLink2 = link;
            }
        }

        assertEquals(0,
                groupPlacementState.resourceQuotaPerResourceDesc.get(containerLink1).intValue());
        assertEquals(0,
                groupPlacementState.resourceQuotaPerResourceDesc.get(containerLink2).intValue());

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);

        // and there must be no container network state left
        ServiceDocumentQueryResult networkStates = getDocument(ServiceDocumentQueryResult.class,
                ContainerNetworkService.FACTORY_LINK);
        assertEquals(0L, networkStates.documentCount.longValue());
    }

    @Test
    public void testNetworkRequestLifeCycleWithNetworkFailureShouldCleanNetworks()
            throws Throwable {
        host.log(
                "########  Start of testNetworkRequestLifeCycleWithNetworkFailureShouldCleanNetworks ######## ");

        // setup 1 network

        String networkName = "mynet";

        ContainerNetworkDescription networkDesc = TestRequestStateFactory
                .createContainerNetworkDescription(networkName);
        networkDesc.documentSelfLink = UUID.randomUUID().toString();
        // e.g. Docker host was not available!
        networkDesc.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED,
                Boolean.TRUE.toString());

        networkDesc = doPost(networkDesc, ContainerNetworkDescriptionService.FACTORY_LINK);
        addForDeletion(networkDesc);

        // 1. Request a network with expected failure:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.NETWORK_TYPE.getName(), networkDesc.documentSelfLink);
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // 2. Wait for reservation removed substage
        request = waitForRequestToFail(request);

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);

        assertEquals(TaskStage.FAILED, rs.taskInfo.stage);
        assertEquals(SubStage.ERROR.name(), rs.subStage);

        // and there must be no container network state left
        ServiceDocumentQueryResult networkStates = getDocument(ServiceDocumentQueryResult.class,
                ContainerNetworkService.FACTORY_LINK);
        assertEquals(0L, networkStates.documentCount.longValue());
    }

    @Test
    public void testCompositeComponentWithContainerVolumeRequestLifeCycle() throws Throwable {
        host.log(
                "########  Start of testCompositeComponentWithContainerVolumeRequestLifeCycle ######## ");

        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();

        delete(computeHost.documentSelfLink);
        computeHost = null;

        ComputeState dockerHost1 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost1);

        ComputeState dockerHost2 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost2);

        String sharedVolumeName = "postgres";
        String volumeName = String.format("%s:/etc/pgdata/postgres", sharedVolumeName);

        ContainerVolumeDescription volumeDesc = TestRequestStateFactory
                .createContainerVolumeDescription(sharedVolumeName);
        volumeDesc.documentSelfLink = UUID.randomUUID().toString();

        ContainerDescription container1Desc = TestRequestStateFactory.createContainerDescription();
        container1Desc.documentSelfLink = UUID.randomUUID().toString();
        container1Desc.name = "container1";
        container1Desc.volumes = new String[] { volumeName };

        ContainerDescription container2Desc = TestRequestStateFactory.createContainerDescription();
        container2Desc.documentSelfLink = UUID.randomUUID().toString();
        container2Desc.name = "container2";
        container2Desc.affinity = new String[] { "!container1:hard" };

        // setup Composite description with 2 containers and 1 network
        CompositeDescription compositeDesc = createCompositeDesc(volumeDesc, container1Desc,
                container2Desc);
        assertNotNull(compositeDesc);

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a composite container:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);

        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        request = waitForRequestToComplete(request);

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);

        assertEquals(Integer.valueOf(100), rs.progress);
        assertEquals(1, request.resourceLinks.size());

        CompositeComponent cc = getDocument(CompositeComponent.class, request.resourceLinks.get(0));

        String volumeLink = null;
        String containerLink1 = null;
        String containerLink2 = null;

        Iterator<String> iterator = cc.componentLinks.iterator();

        while (iterator.hasNext()) {
            String link = iterator.next();
            if (link.startsWith(ContainerVolumeService.FACTORY_LINK)) {
                volumeLink = link;
            } else if (containerLink1 == null) {
                containerLink1 = link;
            } else {
                containerLink2 = link;
            }
        }

        ContainerState cont1 = getDocument(ContainerState.class, containerLink1);
        ContainerState cont2 = getDocument(ContainerState.class, containerLink2);

        boolean containerIsProvisionedOnAnyHosts = cont1.parentLink
                .equals(dockerHost1.documentSelfLink)
                || cont1.parentLink.equals(dockerHost2.documentSelfLink);
        assertTrue(containerIsProvisionedOnAnyHosts);

        containerIsProvisionedOnAnyHosts = cont2.parentLink.equals(dockerHost1.documentSelfLink)
                || cont2.parentLink.equals(dockerHost2.documentSelfLink);
        assertTrue(containerIsProvisionedOnAnyHosts);

        // provisioned on different hosts
        assertFalse(cont1.parentLink.equals(cont2.parentLink));

        ContainerVolumeState volume = getDocument(ContainerVolumeState.class, volumeLink);
        assertTrue(volume.name.contains(sharedVolumeName));

        String volumeHostPath = volume.originatingHostReference.getPath();

        boolean volumeIsProvisionedOnAnyHosts = volumeHostPath.equals(dockerHost1.documentSelfLink)
                || volumeHostPath.equals(dockerHost2.documentSelfLink);

        assertTrue(volumeIsProvisionedOnAnyHosts);

        assertEquals(cc.documentSelfLink, cont1.compositeComponentLink);
        assertEquals(cc.documentSelfLink, cont2.compositeComponentLink);
        assertEquals(cc.documentSelfLink, volume.compositeComponentLink);
    }

    @Test
    public void testRequestLifeCycleWithContainerVolumeFailureShouldCleanVolumes()
            throws Throwable {
        host.log(
                "########  Start of testRequestLifeCycleWithContainerVolumeFailureShouldCleanVolumes ######## ");

        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();

        delete(computeHost.documentSelfLink);
        computeHost = null;

        ComputeState dockerHost1 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost1);

        ComputeState dockerHost2 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost2);

        // setup Composite description with 2 containers and 1 network

        String volumeName = "myvolume";

        ContainerVolumeDescription volumeDesc = TestRequestStateFactory
                .createContainerVolumeDescription(volumeName);
        volumeDesc.documentSelfLink = UUID.randomUUID().toString();

        ContainerDescription container1Desc = TestRequestStateFactory.createContainerDescription();
        container1Desc.documentSelfLink = UUID.randomUUID().toString();
        container1Desc.name = "container1";
        container1Desc.volumes = new String[] { volumeName + ":/tmp" };

        ContainerDescription container2Desc = TestRequestStateFactory.createContainerDescription();
        container2Desc.documentSelfLink = UUID.randomUUID().toString();
        container2Desc.name = "container2";
        container2Desc.volumes = new String[] { volumeName + ":/tmp" };
        container2Desc.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED,
                Boolean.TRUE.toString());

        CompositeDescription compositeDesc = createCompositeDesc(volumeDesc, container1Desc,
                container2Desc);
        assertNotNull(compositeDesc);

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a composite container with expected failure:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // 2. Wait for reservation removed substage
        waitForRequestToFail(request);

        // 3. Verify that the group placement has been released.
        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals(groupPlacementState.allocatedInstancesCount, 0);

        String containerLink1 = null;
        String containerLink2 = null;

        Iterator<String> iterator = compositeDesc.descriptionLinks.iterator();
        while (iterator.hasNext()) {
            String link = iterator.next();
            if (link.startsWith(ContainerVolumeDescriptionService.FACTORY_LINK)) {
                continue;
            } else if (containerLink1 == null) {
                containerLink1 = link;
            } else {
                containerLink2 = link;
            }
        }

        assertEquals(0,
                groupPlacementState.resourceQuotaPerResourceDesc.get(containerLink1).intValue());
        assertEquals(0,
                groupPlacementState.resourceQuotaPerResourceDesc.get(containerLink2).intValue());

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);

        // and there must be no container network state left
        ServiceDocumentQueryResult volumeStates = getDocument(ServiceDocumentQueryResult.class,
                ContainerVolumeService.FACTORY_LINK);
        assertEquals(0L, volumeStates.documentCount.longValue());
    }

    @Test
    public void testRequestLifeCycleFailureShouldCleanReservations() throws Throwable {
        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();
        createDockerHost(dockerHostDesc, resourcePool);

        // setup Container desc:
        ContainerDescription containerDesc = createContainerDescription();

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a container instance:
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.customProperties = new HashMap<>();
        request.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED,
                Boolean.TRUE.toString());

        assertEquals(groupPlacementState.allocatedInstancesCount, 0);
        request = startRequest(request);

        // 2. Wait for reservation removed substage
        waitForRequestToFail(request);

        // 3. Verify that the group placement has been released.
        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals(groupPlacementState.allocatedInstancesCount, 0);
        assertEquals(0,
                groupPlacementState.resourceQuotaPerResourceDesc.get(containerDesc.documentSelfLink)
                        .intValue());
    }

    @Test
    public void testRequestLifeCycleWithHostAssociatedAuthentication() throws Throwable {
        host.log(
                "########  Start of testRequestLifeCycleWithHostAssociatedAuthentication ######## ");
        safeDelete(containerDesc);
        safeDelete(resourcePool);
        safeDelete(hostDesc);
        safeDelete(computeHost);
        safeDelete(groupPlacementState);

        // setup Docker Host:
        resourcePool = TestRequestStateFactory.createResourcePool();
        resourcePool.documentSelfLink = UUID.randomUUID().toString();
        resourcePool.id = resourcePool.documentSelfLink;
        resourcePool = doPost(resourcePool, ResourcePoolService.FACTORY_LINK);
        assertNotNull(resourcePool);

        hostDesc = TestRequestStateFactory.createDockerHostDescription();
        hostDesc.documentSelfLink = UUID.randomUUID().toString();
        hostDesc = doPost(hostDesc, ComputeDescriptionService.FACTORY_LINK);
        assertNotNull(hostDesc);

        computeHost = TestRequestStateFactory.createDockerComputeHost();
        computeHost.id = UUID.randomUUID().toString();
        computeHost.resourcePoolLink = resourcePool.documentSelfLink;
        computeHost.descriptionLink = hostDesc.documentSelfLink;
        computeHost.customProperties = new HashMap<>();
        computeHost.customProperties.put(ComputeConstants.HOST_AUTH_CREDENTIALS_PROP_NAME,
                UriUtils.buildUriPath(AuthCredentialsService.FACTORY_LINK,
                        CommonTestStateFactory.AUTH_CREDENTIALS_ID));
        computeHost.customProperties.put(
                ContainerHostService.DOCKER_HOST_AVAILABLE_MEMORY_PROP_NAME, Long.MAX_VALUE + "");
        computeHost = doPost(computeHost, ComputeService.FACTORY_LINK);
        assertNotNull(computeHost);

        // setup Container desc:
        ContainerDescription desc = TestRequestStateFactory.createContainerDescription();
        desc.documentSelfLink = UUID.randomUUID().toString();
        containerDesc = doPost(desc, ContainerDescriptionService.FACTORY_LINK);
        assertNotNull(containerDesc);

        // setup Group Placement:
        groupPlacementState = TestRequestStateFactory.createGroupResourcePlacementState();
        groupPlacementState.resourcePoolLink = resourcePool.documentSelfLink;
        groupPlacementState = doPost(groupPlacementState, GroupResourcePlacementService.FACTORY_LINK);
        assertNotNull(groupPlacementState);

        // 1. Request a container instance:
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        String requestSelfLink = request.documentSelfLink;
        request = waitForRequestToComplete(request);

        assertNotNull("ResourceLinks null for requestSelfLink: " + requestSelfLink,
                request.resourceLinks);
        assertEquals(1, request.resourceLinks.size());
        ContainerState containerState = getDocument(ContainerState.class,
                request.resourceLinks.get(0));
        assertNotNull(containerState);
    }

    // @Ignore("Not implemented yet. Part of the Reservation refactoring with multiple reservation
    // selections.")
    @Test
    public void testShouldSelectHostFromAllResourcePools() throws Throwable {
        safeDelete(computeHost);
        computeHost = null;

        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();

        ResourcePoolState defaultResourcePool = getDocument(ResourcePoolState.class,
                GroupResourcePlacementService.DEFAULT_RESOURCE_POOL_LINK);

        try {
            createDockerHost(dockerHostDesc, resourcePool);

            // setup Container desc:
            ContainerDescription containerDesc = createContainerDescription();

            // setup Group Placement:
            GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

            GroupResourcePlacementState groupPlacementState2 = TestRequestStateFactory
                    .createGroupResourcePlacementState();
            groupPlacementState2.memoryLimit = 0;
            groupPlacementState2.resourcePoolLink = defaultResourcePool.documentSelfLink;
            groupPlacementState2 = doPost(groupPlacementState2, GroupResourcePlacementService.FACTORY_LINK);

            // 1. Request a container instance:
            RequestBrokerState request = TestRequestStateFactory.createRequestState();
            request.resourceDescriptionLink = containerDesc.documentSelfLink;
            request.tenantLinks = groupPlacementState.tenantLinks;
            host.log("########  Start of request ######## ");
            request = startRequest(request);

            // wait for request completed state:
            waitForRequestToComplete(request);
        } finally {
            safeDelete(computeHost);
            computeHost = null;
        }

    }

    @Test
    public void testContainerShouldNotBeDeployedWhenHostIsSuspended() throws Throwable {
        // Create a docker hosts
        ResourcePoolState resourcePool = createResourcePool();
        ComputeState dockerHost = createDockerHost(createDockerHostDescription(), resourcePool);

        ComputeState computeState = new ComputeState();
        computeState.powerState = PowerState.SUSPEND;

        // Disable the hosts
        doOperation(computeState,
                UriUtilsExtended.buildUri(host, dockerHost.documentSelfLink), false,
                Action.PATCH);

        // Try to deploy a container
        ContainerDescription containerDescription = createContainerDescription();
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);
        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToFail(request);

        assertTrue(request.taskInfo.failure.message.startsWith(
                "No powered-on container hosts found"));

        // check the custom property from container description
        assertNotNull(request.customProperties);
        String containerDescProp = request.customProperties.get("propKey string");
        assertNotNull(containerDescProp);
        assertEquals("customPropertyValue string", containerDescProp);
    }

    @Test
    public void testContainerShouldBeDeployedOnHostWithStateOn() throws Throwable {
        // Create docker hosts
        ResourcePoolState resourcePool = createResourcePool();
        List<ComputeState> containerHostsToSuspend = new ArrayList<>();
        String testHostSelfLink = UriUtils.buildUriPath(ComputeService.FACTORY_LINK,
                CommonTestStateFactory.DOCKER_COMPUTE_ID);

        containerHostsToSuspend.add(getDocument(ComputeState.class, testHostSelfLink));
        containerHostsToSuspend
                .add(createDockerHost(createDockerHostDescription(), resourcePool, true));
        containerHostsToSuspend
                .add(createDockerHost(createDockerHostDescription(), resourcePool, true));
        ComputeState activeDockerHost = createDockerHost(createDockerHostDescription(),
                resourcePool, true);

        // Leave only 1 with power state ON
        ComputeState computeState = new ComputeState();
        computeState.powerState = PowerState.ON;
        doOperation(computeState,
                UriUtilsExtended.buildUri(host, activeDockerHost.documentSelfLink), false,
                Action.PATCH);

        computeState.powerState = PowerState.SUSPEND;
        for (ComputeState containerHost : containerHostsToSuspend) {
            doOperation(computeState,
                    UriUtilsExtended.buildUri(host, containerHost.documentSelfLink), false,
                    Action.PATCH);
        }

        // Deploy a container
        ContainerDescription containerDescription = createContainerDescription();
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToComplete(request);

        ContainerState containerState = getDocument(ContainerState.class,
                request.resourceLinks.get(0));

        // The container should be deployed on the host with power state ON
        assertEquals(containerState.parentLink, activeDockerHost.documentSelfLink);
    }

    @Test
    public void testShouldPublishEventLogOnTaskFailure() throws Throwable {
        // Create a docker hosts
        ResourcePoolState resourcePool = createResourcePool();
        ComputeState dockerHost = createDockerHost(createDockerHostDescription(), resourcePool);

        ComputeState computeState = new ComputeState();
        computeState.powerState = PowerState.SUSPEND;

        // Disable the hosts
        doOperation(computeState,
                UriUtilsExtended.buildUri(host, dockerHost.documentSelfLink), false,
                Action.PATCH);

        // Try to deploy a container
        ContainerDescription containerDescription = createContainerDescription();
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);
        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToFail(request);
        assertTrue(request.taskInfo.failure.message.startsWith(
                "No powered-on container hosts found"));

        // the event is created after the task is failed, wait for it to be created
        final String requestTrackerLink = request.requestTrackerLink;
        waitFor(() -> {
            RequestStatus rs = getDocument(RequestStatus.class, requestTrackerLink);
            return rs.eventLogLink != null;
        });

        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs.eventLogLink);
        EventLogState el = getDocument(EventLogState.class, rs.eventLogLink);
        assertNotNull(el);
        assertEquals(EventLogType.ERROR, el.eventLogType);
    }

    @Test
    public void testContainerStateShouldBeRemovedAfterFailure() throws Throwable {
        // Create docker hosts
        ResourcePoolState resourcePool = createResourcePool();
        // try to deploy a container
        ContainerDescription containerDescription = createContainerDescription();
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // stop the adapter service
        MockDockerAdapterService service = new MockDockerAdapterService();
        service.setSelfLink(MockDockerAdapterService.SELF_LINK);
        host.stopService(service);

        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        request = waitForRequestToFail(request);
        ContainerState containerState = searchForDocument(ContainerState.class,
                request.resourceLinks.get(0));
        assertNull(containerState);
    }

    @Test
    // Issue VSYM-994 - Request tracker link is empty in the response. UI does not refresh after
    // day2 operation on containers.
    public void testRequestTrackerLinkShouldBeReturnedAndCreated() throws Throwable {
        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();
        createDockerHost(dockerHostDesc, resourcePool);

        // setup Container desc:
        ContainerDescription containerDesc = createContainerDescription();

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // Request a container instance:
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        String requestTrackerLink = request.requestTrackerLink;

        assertNotNull("Request tracker link should not be null!", requestTrackerLink);
        assertFalse("Request tracker link should not be null!", requestTrackerLink.isEmpty());

        waitFor("request tracker stage was not updated", () -> {
            RequestStatus requestStatus = getDocument(RequestStatus.class, requestTrackerLink);
            assertNotNull(requestStatus);
            return requestStatus.taskInfo.stage.ordinal() > TaskStage.CREATED.ordinal();
        });
    }

    @Test
    // Issue VSYM-443
    public void testGroupResourcePlacementAfterFailedProvisionOperation() throws Throwable {
        ResourcePoolState resourcePool = createResourcePool();
        ContainerDescription containerDescription = createContainerDescription();
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);
        long allocatedInstancesCount = groupPlacementState.allocatedInstancesCount;
        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;

        request = startRequest(request);
        waitForRequestToComplete(request);

        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals("Allocated instances count is not correct",
                groupPlacementState.allocatedInstancesCount, allocatedInstancesCount + 1);

        // stop the adapter service
        MockDockerAdapterService service = new MockDockerAdapterService();
        service.setSelfLink(MockDockerAdapterService.SELF_LINK);
        host.stopService(service);

        request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request = startRequest(request);
        waitForRequestToFail(request);

        // verify available instances are not changed after the failure
        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals("Allocated instances count is not correct after a failed provisioning",
                groupPlacementState.allocatedInstancesCount, allocatedInstancesCount + 1);
    }

    @Test
    public void testGroupResourcePlacementAfterSuccessfulProvisionOperation() throws Throwable {
        ResourcePoolState resourcePool = createResourcePool();
        ContainerDescription containerDescription = createContainerDescription();
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);
        long allocatedInstancesCount = groupPlacementState.allocatedInstancesCount;
        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;

        request = startRequest(request);
        request = waitForRequestToComplete(request);
        List<String> resourceLinks = request.resourceLinks;

        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals("Allocated instances count was not increased after provisioning",
                groupPlacementState.allocatedInstancesCount, allocatedInstancesCount + 1);

        request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_TYPE.getName();
        request.resourceDescriptionLink = containerDescription.documentSelfLink;
        request.resourceLinks = resourceLinks;
        request.tenantLinks = groupPlacementState.tenantLinks;
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;
        request = startRequest(request);
        waitForRequestToComplete(request);

        // verify available instances are not changed after the failure
        groupPlacementState = getDocument(GroupResourcePlacementState.class,
                groupPlacementState.documentSelfLink);
        assertEquals("Placement was not cleaned",
                groupPlacementState.allocatedInstancesCount, allocatedInstancesCount);
    }

    @Test
    public void testDeletingRequestBrokerShouldDeleteAssociatedRequestStatus() throws Throwable {
        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();
        createDockerHost(dockerHostDesc, resourcePool);

        // setup Container desc:
        ContainerDescription containerDesc = createContainerDescription();

        // setup Group Placement:
        GroupResourcePlacementState groupPlacementState = createGroupResourcePlacement(resourcePool);

        // 1. Request a container instance:
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPlacementState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        request = waitForRequestToComplete(request);

        String requestTrackerLink = request.requestTrackerLink;
        RequestStatus requestStatus = searchForDocument(RequestStatus.class, requestTrackerLink);
        assertNotNull(requestStatus);

        delete(request.documentSelfLink);

        waitFor("RequestStatus wasn't deleted: " + requestStatus.documentSelfLink, () -> {
            RequestStatus reqStatus = searchForDocument(RequestStatus.class, requestTrackerLink);
            return reqStatus == null;
        });
    }

    @Test
    public void testCompositeComponentWithContainerServiceLinks() throws Throwable {
        CompositeComponent cc = setUpCompositeWithServiceLinks(false);

        String containerLink1 = null;
        String containerLink2 = null;

        Iterator<String> iterator = cc.componentLinks.iterator();
        while (iterator.hasNext()) {
            String link = iterator.next();
            if (link.startsWith(ContainerFactoryService.SELF_LINK + "/container1")) {
                containerLink1 = link;
            } else if (link.startsWith(ContainerFactoryService.SELF_LINK + "/container2")) {
                containerLink2 = link;
            }
        }

        ContainerState cont1 = getDocument(ContainerState.class, containerLink1);
        ContainerState cont2 = getDocument(ContainerState.class, containerLink2);

        String[] links = cont2.links;

        assertEquals(1, links.length);
        assertEquals(cont1.names.get(0) + ":mycontainer", cont2.links[0]);

        // Containers are placed on a single host when using "legacy" links
        assertTrue(cont1.parentLink.equals(cont2.parentLink));
    }

    @Test
    public void testCompositeComponentWithContainerServiceLinksAndNetwork() throws Throwable {
        CompositeComponent cc = setUpCompositeWithServiceLinks(true);

        String containerLink1 = null;
        String containerLink2 = null;

        Iterator<String> iterator = cc.componentLinks.iterator();
        while (iterator.hasNext()) {
            String link = iterator.next();
            if (link.startsWith(ContainerFactoryService.SELF_LINK + "/container1")) {
                containerLink1 = link;
            } else if (link.startsWith(ContainerFactoryService.SELF_LINK + "/container2")) {
                containerLink2 = link;
            }
        }

        ContainerState cont1 = getDocument(ContainerState.class, containerLink1);
        ContainerState cont2 = getDocument(ContainerState.class, containerLink2);

        String[] links = cont2.networks.values().iterator().next().links;

        assertEquals(1, links.length);
        assertEquals(cont1.names.get(0) + ":mycontainer", links[0]);

        // Containers are placed on multiple hosts when using user define network links
    }

    private CompositeComponent setUpCompositeWithServiceLinks(boolean includeNetwork)
            throws Throwable {
        // setup Docker Host:
        ResourcePoolState resourcePool = createResourcePool();
        ComputeDescription dockerHostDesc = createDockerHostDescription();

        delete(computeHost.documentSelfLink);
        computeHost = null;

        ComputeState dockerHost1 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost1);

        ComputeState dockerHost2 = createDockerHost(dockerHostDesc, resourcePool, true);
        addForDeletion(dockerHost2);

        ContainerDescription container1Desc = TestRequestStateFactory.createContainerDescription();
        container1Desc.documentSelfLink = UUID.randomUUID().toString();
        container1Desc.name = "container1";

        ContainerDescription container2Desc = TestRequestStateFactory.createContainerDescription();
        container2Desc.documentSelfLink = UUID.randomUUID().toString();
        container2Desc.name = "container2";
        container2Desc.links = new String[] { "container1:mycontainer" };

        CompositeDescription compositeDesc;
        if (includeNetwork) {
            ContainerNetworkDescription networkDesc = TestRequestStateFactory
                    .createContainerNetworkDescription("testnet");
            networkDesc.documentSelfLink = UUID.randomUUID().toString();

            container1Desc.networks = Collections.singletonMap(networkDesc.name,
                    new ServiceNetwork());
            container2Desc.networks = Collections.singletonMap(networkDesc.name,
                    new ServiceNetwork());

            compositeDesc = createCompositeDesc(networkDesc, container1Desc, container2Desc);
        } else {
            compositeDesc = createCompositeDesc(container1Desc, container2Desc);
        }

        assertNotNull(compositeDesc);

        // setup Group Placement:
        GroupResourcePlacementState groupPlacememtState = createGroupResourcePlacement(resourcePool);

        // 1. Request a composite container:
        RequestBrokerState request = TestRequestStateFactory.createRequestState(
                ResourceType.COMPOSITE_COMPONENT_TYPE.getName(), compositeDesc.documentSelfLink);
        request.tenantLinks = groupPlacememtState.tenantLinks;
        host.log("########  Start of request ######## ");
        request = startRequest(request);

        // wait for request completed state:
        request = waitForRequestToComplete(request);

        // Verify request status
        RequestStatus rs = getDocument(RequestStatus.class, request.requestTrackerLink);
        assertNotNull(rs);

        assertEquals(Integer.valueOf(100), rs.progress);
        assertEquals(1, request.resourceLinks.size());

        return getDocument(CompositeComponent.class, request.resourceLinks.get(0));
    }

}
