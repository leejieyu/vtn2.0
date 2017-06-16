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
package org.opencord.cordvtn.impl;

import com.google.common.collect.ImmutableSet;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.host.HostService;
import org.opencord.cordvtn.api.Constants;
import org.opencord.cordvtn.api.config.CordVtnConfig;
import org.opencord.cordvtn.api.config.OpenStackConfig;
import org.opencord.cordvtn.api.config.XosConfig;
import org.opencord.cordvtn.api.core.CordVtnAdminService;
import org.opencord.cordvtn.api.core.CordVtnService;
import org.opencord.cordvtn.api.core.CordVtnStore;
import org.opencord.cordvtn.api.core.CordVtnStoreDelegate;
import org.opencord.cordvtn.api.instance.Instance;
import org.opencord.cordvtn.api.net.NetworkId;
import org.opencord.cordvtn.api.net.NetworkService;
import org.opencord.cordvtn.api.net.PortId;
import org.opencord.cordvtn.api.net.ServiceNetwork;
import org.opencord.cordvtn.api.net.ServiceNetworkService;
import org.opencord.cordvtn.api.net.ServicePort;
import org.opencord.cordvtn.api.net.SubnetId;
import org.opencord.cordvtn.api.net.VtnNetwork;
import org.opencord.cordvtn.api.net.VtnNetworkEvent;
import org.opencord.cordvtn.api.net.VtnNetworkListener;
import org.opencord.cordvtn.api.net.VtnPort;
import org.opencord.cordvtn.impl.external.OpenStackNetworking;
import org.opencord.cordvtn.impl.external.XosServiceNetworking;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.network.Port;
import org.openstack4j.model.network.Subnet;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provides implementation of administering and interfacing VTN networks.
 * tc 可以提供完整信息给 聊天程序.
 * 由于建立 dependency 会导致 openstack 错误,所以还没有建立 serviceChain 表.
 */
@Component(immediate = true)
@Service
public class CordVtnManager extends ListenerRegistry<VtnNetworkEvent, VtnNetworkListener>
        implements CordVtnAdminService, CordVtnService, NetworkService,
        ServiceNetworkService {

    protected final Logger log = getLogger(getClass());

    private static final String MSG_SERVICE_NET  = "VTN network %s %s";
    private static final String MSG_SERVICE_PORT = "VTN port %s %s";
    private static final String MSG_NET  = "Network %s %s";
    private static final String MSG_PORT = "Port %s %s";
    private static final String MSG_SUBNET = "Subnet %s %s";

    private static final String CREATED = "created";
    private static final String UPDATED = "updated";
    private static final String REMOVED = "removed";

    private static final String ERR_NULL_SERVICE_PORT = "Service port cannot be null";
    private static final String ERR_NULL_SERVICE_NET  = "Service network cannot be null";
    private static final String ERR_NULL_PORT = "Port cannot be null";
    private static final String ERR_NULL_NET  = "Network cannot be null";
    private static final String ERR_NULL_SUBNET  = "Subnet cannot be null";
    private static final String ERR_NULL_PORT_ID = "Port ID cannot be null";
    private static final String ERR_NULL_NET_ID  = "Network ID cannot be null";
    private static final String ERR_NULL_SUBNET_ID = "Subnet ID cannot be null";

    private static final String ERR_SYNC = "VTN store is out of sync: ";
    private static final String ERR_NOT_FOUND = " does not exist";
    private static final String ERR_IN_USE_PORT = "There are ports still in use on the network %s";
    private static final String ERR_SUBNET_DUPLICATE = "Subnet already exists for network %s";

    private static final String PORT = "port ";
    private static final String NETWORK  = "network ";
    private static final String SUBNET  = "subnet for ";
    private static final String PROVIDER = "provider ";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigService configService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry configRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CordVtnStore store;

    // TODO add cordvtn config service and move this
    private static final Class<CordVtnConfig> CONFIG_CLASS = CordVtnConfig.class;
    private final ConfigFactory configFactory =
            new ConfigFactory<ApplicationId, CordVtnConfig>(
                    SubjectFactories.APP_SUBJECT_FACTORY, CONFIG_CLASS, "cordvtn") {
                @Override
                public CordVtnConfig createConfig() {
                    return new CordVtnConfig();
                }
            };

    private final CordVtnStoreDelegate delegate = new InternalCordVtnStoreDelegate();
    private ApplicationId appId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(Constants.CORDVTN_APP_ID);
        configRegistry.registerConfigFactory(configFactory);
        store.setDelegate(delegate);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        configRegistry.unregisterConfigFactory(configFactory);
        store.unsetDelegate(delegate);
        log.info("Stopped");
    }

    @Override
    public void purgeStates() {
        store.clear();
    }

    @Override
    public void syncStates() {
        syncNetwork();
        syncServiceNetwork();
    }

    @Override
    public void createServiceNetwork(ServiceNetwork serviceNet) {
        checkNotNull(serviceNet, ERR_NULL_SERVICE_NET);
        synchronized (this) {
            Network network = store.network(serviceNet.id());
            if (network == null) {
                final String error = ERR_SYNC + NETWORK + serviceNet.id() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }

            Subnet subnet = getSubnet(serviceNet.id());
            if (subnet == null) {
                final String error = ERR_SYNC + SUBNET + serviceNet.id() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }

            // TODO check VTN network instead of network
            serviceNet.providers().stream().forEach(provider -> {
                if (store.network(provider.id()) == null) {
                    final String error = ERR_SYNC + PROVIDER + provider.id() + ERR_NOT_FOUND;
                    throw new IllegalStateException(error);
                }
            });

            store.createVtnNetwork(VtnNetwork.of(network, subnet, serviceNet));
            log.info(String.format(MSG_SERVICE_NET, CREATED, serviceNet.id()));
        }
    }

    @Override
    public void updateServiceNetwork(ServiceNetwork serviceNet) {
        checkNotNull(serviceNet, ERR_NULL_SERVICE_NET);
        synchronized (this) {
            VtnNetwork existing = store.vtnNetwork(serviceNet.id());
            if (existing == null) {
                final String error = ERR_SYNC + NETWORK + serviceNet.id() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }
            // only providers update is allowed
            VtnNetwork updated = VtnNetwork.builder(existing)
                    .providers(serviceNet.providers())
                    .build();
            store.updateVtnNetwork(updated);
            log.info(String.format(MSG_SERVICE_NET, UPDATED, serviceNet.id()));
        }
    }

    @Override
    public void removeServiceNetwork(NetworkId netId) {
        checkNotNull(netId, ERR_NULL_NET_ID);
        // TODO check if the network still exists?
        store.removeVtnNetwork(netId);
        log.info(String.format(MSG_SERVICE_NET, REMOVED, netId));
    }

    @Override
    public void createServicePort(ServicePort servicePort) {
        checkNotNull(servicePort, ERR_NULL_SERVICE_PORT);
        synchronized (this) {
            Port port = store.port(servicePort.id());
            if (port == null) {
                final String error = ERR_SYNC + PORT + servicePort.id() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }
            store.createVtnPort(VtnPort.of(port, servicePort));
            log.info(String.format(MSG_SERVICE_PORT, CREATED, servicePort.id()));
        }
    }

    @Override
    public void updateServicePort(ServicePort servicePort) {
        checkNotNull(servicePort, ERR_NULL_SERVICE_PORT);
        synchronized (this) {
            VtnPort vtnPort = store.vtnPort(servicePort.id());
            if (vtnPort == null) {
                final String error = ERR_SYNC + PORT + servicePort.id() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }
            store.updateVtnPort(VtnPort.of(vtnPort, servicePort));
            log.info(String.format(MSG_SERVICE_PORT, UPDATED, servicePort.id()));
        }
    }

    @Override
    public void removeServicePort(PortId portId) {
        checkNotNull(portId, ERR_NULL_PORT_ID);
        store.removeVtnPort(portId);
        log.info(String.format(MSG_SERVICE_PORT, REMOVED, portId));
    }

    @Override
    public void createNetwork(Network network) {
        checkNotNull(network, ERR_NULL_NET);
        store.createNetwork(network);
        log.info(String.format(MSG_NET, CREATED, network.getId()));
    }

    @Override
    public void updateNetwork(Network network) {
        checkNotNull(network, ERR_NULL_NET);
        store.updateNetwork(network);
        log.info(String.format(MSG_NET, UPDATED, network.getId()));
    }

    @Override
    public void removeNetwork(NetworkId netId) {
        checkNotNull(netId, ERR_NULL_NET_ID);
        // FIXME Neutron removes network anyway even if there's an exception here
        store.removeNetwork(netId);
        log.info(String.format(MSG_NET, REMOVED, netId));
    }

    @Override
    public void createPort(Port port) {
        checkNotNull(port, ERR_NULL_PORT);
        synchronized (this) {
            if (store.network(NetworkId.of(port.getNetworkId())) == null) {
                final String error = ERR_SYNC + port.getNetworkId() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }
            store.createPort(port);
            log.info(String.format(MSG_PORT, CREATED, port.getId()));
        }
    }

    @Override
    public void updatePort(Port port) {
        checkNotNull(port, ERR_NULL_PORT);
        synchronized (this) {
            if (store.network(NetworkId.of(port.getNetworkId())) == null) {
                final String error = ERR_SYNC + port.getNetworkId() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }
            store.updatePort(port);
            log.info(String.format(MSG_PORT, UPDATED, port.getId()));
        }
    }

    @Override
    public void removePort(PortId portId) {
        checkNotNull(portId, ERR_NULL_PORT_ID);
        synchronized (this) {
            if (getInstance(portId) != null) {
                final String error = String.format(ERR_IN_USE_PORT, portId);
                throw new IllegalStateException(error);
            }
            removeServicePort(portId);
            store.removePort(portId);
            log.info(String.format(MSG_PORT, REMOVED, portId));
        }
    }

    @Override
    public void createSubnet(Subnet subnet) {
        checkNotNull(subnet, ERR_NULL_SUBNET);
        synchronized (this) {
            if (store.network(NetworkId.of(subnet.getNetworkId())) == null) {
                final String error = ERR_SYNC + subnet.getNetworkId() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }

            Subnet existing = getSubnet(NetworkId.of(subnet.getNetworkId()));
            if (existing != null && !Objects.equals(existing.getId(), subnet.getId())) {
                // CORD does not allow multiple subnets for a network
                final String error = String.format(ERR_SUBNET_DUPLICATE, subnet.getNetworkId());
                throw new IllegalStateException(error);
            }
            store.createSubnet(subnet);
            // FIXME update the network as well with the new subnet
            log.info(String.format(MSG_SUBNET, CREATED, subnet.getId()));
        }
    }

    @Override
    public void updateSubnet(Subnet subnet) {
        checkNotNull(subnet, ERR_NULL_SUBNET);
        synchronized (this) {
            if (store.network(NetworkId.of(subnet.getNetworkId())) == null) {
                final String error = ERR_SYNC + subnet.getNetworkId() + ERR_NOT_FOUND;
                throw new IllegalStateException(error);
            }
            store.updateSubnet(subnet);
            log.info(String.format(MSG_SUBNET, UPDATED, subnet.getId()));
        }
    }

    @Override
    public void removeSubnet(SubnetId subnetId) {
        checkNotNull(subnetId, ERR_NULL_SUBNET_ID);
        // FIXME Neutron removes network anyway even if there's an exception here
        synchronized (this) {
            removeServiceNetwork(NetworkId.of(store.subnet(subnetId).getNetworkId()));
            store.removeSubnet(subnetId);
            log.info(String.format(MSG_SUBNET, REMOVED, subnetId));
        }
    }

    @Override
    public VtnNetwork vtnNetwork(NetworkId netId) {
        checkNotNull(netId, ERR_NULL_NET_ID);

        // return default VTN network if the network and subnet exist
        VtnNetwork vtnNet = store.vtnNetwork(netId);
        return vtnNet == null ? getDefaultVtnNetwork(netId) : vtnNet;
    }

    @Override
    public Set<VtnNetwork> vtnNetworks() {
        Set<VtnNetwork> vtnNetworks = networks().stream()
                .filter(net -> vtnNetwork(NetworkId.of(net.getId())) != null)
                .map(net -> vtnNetwork(NetworkId.of(net.getId())))
                .collect(Collectors.toSet());
        return ImmutableSet.copyOf(vtnNetworks);
    }

    @Override
    public VtnPort vtnPort(PortId portId) {
        checkNotNull(portId, ERR_NULL_PORT_ID);

        // return default VTN port if the port exists
        VtnPort vtnPort = store.vtnPort(portId);
        return vtnPort == null ? getDefaultPort(portId) : vtnPort;
    }

    @Override
    public VtnPort vtnPort(String portName) {
        Optional<Port> port = store.ports()
                .stream()
                .filter(p -> p.getId().contains(portName.substring(3)))
                .findFirst();
        if (!port.isPresent()) {
            return null;
        }
        return vtnPort(PortId.of(port.get().getId()));
    }

    @Override
    public Set<VtnPort> vtnPorts() {
        Set<VtnPort> vtnPorts = ports().stream()
                .filter(port -> vtnPort(PortId.of(port.getId())) != null)
                .map(port -> vtnPort(PortId.of(port.getId())))
                .collect(Collectors.toSet());
        return ImmutableSet.copyOf(vtnPorts);
    }

    @Override
    public ServiceNetwork serviceNetwork(NetworkId netId) {
        checkNotNull(netId, ERR_NULL_NET_ID);
        return store.vtnNetwork(netId);
    }

    @Override
    public Set<ServiceNetwork> serviceNetworks() {
        return ImmutableSet.copyOf(store.vtnNetworks());
    }

    @Override
    public ServicePort servicePort(PortId portId) {
        checkNotNull(portId, ERR_NULL_PORT_ID);
        return store.vtnPort(portId);
    }

    @Override
    public Set<ServicePort> servicePorts() {
        return ImmutableSet.copyOf(store.vtnPorts());
    }

    @Override
    public Network network(NetworkId netId) {
        checkNotNull(netId, ERR_NULL_NET_ID);
        return store.network(netId);
    }

    @Override
    public Set<Network> networks() {
        return ImmutableSet.copyOf(store.networks());
    }

    @Override
    public Port port(PortId portId) {
        checkNotNull(portId, ERR_NULL_PORT_ID);
        return store.port(portId);
    }

    @Override
    public Set<Port> ports() {
        return ImmutableSet.copyOf(store.ports());
    }

    @Override
    public Subnet subnet(SubnetId subnetId) {
        checkNotNull(subnetId, ERR_NULL_SUBNET_ID);
        return store.subnet(subnetId);
    }

    @Override
    public Set<Subnet> subnets() {
        return ImmutableSet.copyOf(store.subnets());
    }

    private void syncNetwork() {
        CordVtnConfig config = configService.getConfig(appId, CordVtnConfig.class);
        if (config == null) {
            final String error = "Failed to read network configurations";
            throw new IllegalArgumentException(error);
        }

        OpenStackConfig osConfig = config.openStackConfig();
        NetworkService netService = OpenStackNetworking.builder()
                .endpoint(osConfig.endpoint())
                .tenant(osConfig.tenant())
                .user(osConfig.user())
                .password(osConfig.password())
                .build();

        netService.networks().forEach(this::createNetwork);
        netService.subnets().forEach(this::createSubnet);
        netService.ports().forEach(this::createPort);
    }

    private void syncServiceNetwork() {
        CordVtnConfig config = configService.getConfig(appId, CordVtnConfig.class);
        if (config == null) {
            final String error = "Failed to read network configurations";
            throw new IllegalArgumentException(error);
        }

        XosConfig xosConfig = config.xosConfig();
        ServiceNetworkService snetService = XosServiceNetworking.builder()
                .endpoint(xosConfig.endpoint())
                .user(xosConfig.user())
                .password(xosConfig.password())
                .build();

        snetService.serviceNetworks().forEach(this::createServiceNetwork);
        snetService.servicePorts().forEach(this::createServicePort);
    }

    private Instance getInstance(PortId portId) {
        VtnPort vtnPort = vtnPort(portId);
        if (vtnPort == null) {
            final String error = "Failed to build VTN port for " + portId.id();
            throw new IllegalStateException(error);
        }
        Host host = hostService.getHost(HostId.hostId(vtnPort.mac()));
        if (host == null) {
            return null;
        }
        return Instance.of(host);
    }

    private VtnNetwork getDefaultVtnNetwork(NetworkId netId) {
        Network network = network(netId);
        Subnet subnet = getSubnet(netId);
        if (network == null || subnet == null) {
            return null;
        }
        return VtnNetwork.of(network, subnet, null);
    }

    private VtnPort getDefaultPort(PortId portId) {
        Port port = port(portId);
        if (port == null) {
            return null;
        }
        return VtnPort.of(port, null);
    }

    private Subnet getSubnet(NetworkId netId) {
        Optional<Subnet> subnet = subnets().stream()
                .filter(s -> Objects.equals(s.getNetworkId(), netId.id()))
                .findFirst();
        return subnet.orElse(null);
    }

    private class InternalCordVtnStoreDelegate implements CordVtnStoreDelegate {

        @Override
        public void notify(VtnNetworkEvent event) {
            if (event != null) {
                log.trace("send service network event {}", event);
                process(event);
            }
        }
    }
}
