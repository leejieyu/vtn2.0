/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencord.cordvtn.impl.handler;

import com.google.common.collect.ImmutableSet;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;

import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.instructions.ExtensionTreatment;
import org.opencord.cordvtn.api.net.VtnNetwork;
import org.opencord.cordvtn.api.node.CordVtnNode;
import org.opencord.cordvtn.api.instance.Instance;
import org.opencord.cordvtn.api.instance.InstanceHandler;
import org.opencord.cordvtn.impl.CordVtnNodeManager;
import org.opencord.cordvtn.impl.CordVtnPipeline;

import static org.opencord.cordvtn.api.net.ServiceNetwork.ServiceNetworkType.PRIVATE;
import static org.opencord.cordvtn.api.net.ServiceNetwork.ServiceNetworkType.PUBLIC;
import static org.opencord.cordvtn.api.net.ServiceNetwork.ServiceNetworkType.VSG;

/**
 * Provides network connectivity for default service instances.
 */
@Component(immediate = true)
public class DefaultInstanceHandler extends AbstractInstanceHandler implements InstanceHandler {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CordVtnPipeline pipeline;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CordVtnNodeManager nodeManager;

    @Activate
    protected void activate() {
        netTypes = ImmutableSet.of(PRIVATE, PUBLIC, VSG);
        super.activate();
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    public void instanceDetected(Instance instance) {
        if (instance.isNestedInstance()) {
            return;
        }
        log.info("Instance is detected {}", instance);

        VtnNetwork vtnNet = getVtnNetwork(instance);
        populateDefaultRules(instance, vtnNet, true);
    }

    @Override
    public void instanceRemoved(Instance instance) {
        if (instance.isNestedInstance()) {
            return;
        }
        log.info("Instance is removed {}", instance);

        VtnNetwork vtnNet = getVtnNetwork(instance);
        populateDefaultRules(instance, vtnNet, false);
    }

    private void populateDefaultRules(Instance instance, VtnNetwork vtnNet, boolean install) {
        long vni = vtnNet.segmentId().id();
        Ip4Prefix serviceIpRange = vtnNet.subnet().getIp4Prefix();

        populateInPortRule(instance, install, vni);
        populateDstIpRule(instance, vni, install);
        populateTunnelInRule(instance, vni, install);

        if (install) {
            populateDirectAccessRule(vni, serviceIpRange, serviceIpRange, true);
            populateServiceIsolationRule(serviceIpRange, true);
        } else if (getInstances(vtnNet.id()).isEmpty()) {
            populateDirectAccessRule(vni, serviceIpRange, serviceIpRange, false);
            populateServiceIsolationRule(serviceIpRange, false);
        }
    }

    private void populateInPortRule(Instance instance, boolean install, long vni) {
        long metadataMask = 0x7fffffffffffffffL;
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchInPort(instance.portNumber())
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(instance.ipAddress().toIpPrefix())
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .writeMetadata(vni, metadataMask)
                .transition(CordVtnPipeline.TABLE_ACCESS)
                .build();

        FlowRule flowRule = DefaultFlowRule.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(CordVtnPipeline.PRIORITY_DEFAULT)
                .forDevice(instance.deviceId())
                .forTable(CordVtnPipeline.TABLE_IN_PORT)
                .makePermanent()
                .build();

        pipeline.processFlowRule(install, flowRule);

        selector = DefaultTrafficSelector.builder()
                .matchInPort(instance.portNumber())
                .build();

        treatment = DefaultTrafficTreatment.builder()
                .writeMetadata(vni, metadataMask)
                .transition(CordVtnPipeline.TABLE_IN_SERVICE)
                .build();

        flowRule = DefaultFlowRule.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(CordVtnPipeline.PRIORITY_LOW)
                .forDevice(instance.deviceId())
                .forTable(CordVtnPipeline.TABLE_IN_PORT)
                .makePermanent()
                .build();

        pipeline.processFlowRule(install, flowRule);
        log.info("InPortRule of Instance: " + instance +
                         "have been installed, set metadata as the value of VNI: " + vni);
    }

    private void populateDstIpRule(Instance instance, long vni, boolean install) {
        Ip4Address tunnelIp = nodeManager.dataIp(instance.deviceId()).getIp4Address();

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(instance.ipAddress().toIpPrefix())
                .matchMetadata(vni)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setEthDst(instance.mac())
                .setOutput(instance.portNumber())
                .build();

        FlowRule flowRule = DefaultFlowRule.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(CordVtnPipeline.PRIORITY_DEFAULT)
                .forDevice(instance.deviceId())
                .forTable(CordVtnPipeline.TABLE_DST)
                .makePermanent()
                .build();

        pipeline.processFlowRule(install, flowRule);

        for (CordVtnNode node : nodeManager.completeNodes()) {
            if (node.integrationBridgeId().equals(instance.deviceId())) {
                continue;
            }

            ExtensionTreatment tunnelDst =
                    pipeline.tunnelDstTreatment(node.integrationBridgeId(), tunnelIp);
            if (tunnelDst == null) {
                continue;
            }

            treatment = DefaultTrafficTreatment.builder()
                    .setEthDst(instance.mac())
                    .setTunnelId(vni)
                    .extension(tunnelDst, node.integrationBridgeId())
                    .setOutput(nodeManager.tunnelPort(node.integrationBridgeId()))
                    .build();

            flowRule = DefaultFlowRule.builder()
                    .fromApp(appId)
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .withPriority(CordVtnPipeline.PRIORITY_DEFAULT)
                    .forDevice(node.integrationBridgeId())
                    .forTable(CordVtnPipeline.TABLE_DST)
                    .makePermanent()
                    .build();

            pipeline.processFlowRule(install, flowRule);
        }
    }

    private void populateTunnelInRule(Instance instance, long vni, boolean install) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchTunnelId(vni)
                .matchEthDst(instance.mac())
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(instance.portNumber())
                .build();

        FlowRule flowRule = DefaultFlowRule.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(CordVtnPipeline.PRIORITY_DEFAULT)
                .forDevice(instance.deviceId())
                .forTable(CordVtnPipeline.TABLE_TUNNEL_IN)
                .makePermanent()
                .build();

        pipeline.processFlowRule(install, flowRule);
    }

    private void populateDirectAccessRule(long vni, Ip4Prefix srcRange, Ip4Prefix dstRange, boolean install) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchMetadata(vni)
                .matchIPSrc(srcRange)
                .matchIPDst(dstRange)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .transition(CordVtnPipeline.TABLE_DST)
                .build();


        nodeManager.completeNodes().stream().forEach(node -> {
            FlowRule flowRuleDirect = DefaultFlowRule.builder()
                    .fromApp(appId)
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .withPriority(CordVtnPipeline.PRIORITY_DEFAULT)
                    .forDevice(node.integrationBridgeId())
                    .forTable(CordVtnPipeline.TABLE_ACCESS)
                    .makePermanent()
                    .build();

            pipeline.processFlowRule(install, flowRuleDirect);
        });
    }

    private void populateServiceIsolationRule(Ip4Prefix dstRange, boolean install) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(dstRange)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .drop()
                .build();

        nodeManager.completeNodes().stream().forEach(node -> {
            FlowRule flowRuleDirect = DefaultFlowRule.builder()
                    .fromApp(appId)
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .withPriority(CordVtnPipeline.PRIORITY_LOW)
                    .forDevice(node.integrationBridgeId())
                    .forTable(CordVtnPipeline.TABLE_ACCESS)
                    .makePermanent()
                    .build();

            pipeline.processFlowRule(install, flowRuleDirect);
        });
    }
}
