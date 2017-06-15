package org.opencord.cordvtn.impl;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.host.HostService;
import org.opencord.cordvtn.api.core.CordVtnService;
import org.opencord.cordvtn.api.core.CordVtnStore;
import org.opencord.cordvtn.api.instance.Instance;
import org.opencord.cordvtn.api.net.NetworkId;
import org.opencord.cordvtn.api.net.PortId;
import org.opencord.cordvtn.api.net.ProviderNetwork;
import org.opencord.cordvtn.api.net.ServiceNetwork;
import org.opencord.cordvtn.api.net.ServiceNetworkService;
import org.opencord.cordvtn.api.net.VtnPort;
import org.slf4j.Logger;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.onosproject.net.AnnotationKeys.PORT_NAME;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Created by wangbin on 2017/6/12.
 */

@Component(immediate = true)
@Service(value = ServiceChains.class)
public final class ServiceChains {

    protected final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ServiceNetworkService serviceNetworkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CordVtnService vtnService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CordVtnStore store;



    private Map<NetworkId, Integer> netIdMap;
    private Map<Integer, List<NetworkId>> chainMap;
    private Integer chainId;
    private Map<PortNumber, String> numberNameMap;



    public ServiceChains() {
        netIdMap = new HashMap<>();
        chainMap = new HashMap<>();
        numberNameMap = new HashMap<>();
        chainId = 0;
    }

    /**
     * Returns 看serviceNetwork 中的网络是否已经存在创建好的服务链中.
     *
     * @param serviceNetwork
     * @return
     */

    private boolean contains(ServiceNetwork serviceNetwork) {
        if (netIdMap.containsKey(serviceNetwork.id())) {
            return true;
        }

        for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
            if (netIdMap.containsKey(providerNetwork.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 如果不存在包含该 serviceNetwork 的服务链的表项,就新建一个.
     * 如果存在,就更新服务链的信息.
     * Returns serviceNetwork's chainId.
     *
     * @param serviceNetwork serviceNetwork
     * @return serviceNetwork's chainId.
     */

    public Integer addInChainmap(ServiceNetwork serviceNetwork) {
        if (!contains(serviceNetwork)) {
            List<NetworkId> netIdList = new LinkedList<>();
            netIdList.add(serviceNetwork.id());
            for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
                netIdList.add(providerNetwork.id());
            }
            chainMap.put(chainId, netIdList);

            netIdMap.put(serviceNetwork.id(), chainId);
            for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
                netIdMap.put(providerNetwork.id(), chainId);
            }

        } else {
            Integer chainid;
            if (netIdMap.containsKey(serviceNetwork.id())) {
                chainid = netIdMap.get(serviceNetwork.id());
                for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
                    chainMap.get(chainid).add(chainMap.get(chainid)
                            .indexOf(providerNetwork.id()) + 1, providerNetwork.id());
                }
                for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
                    netIdMap.put(providerNetwork.id(), chainId);
                }
            }

            for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
                if (netIdMap.containsKey(providerNetwork.id())) {
                    chainid = netIdMap.get(providerNetwork.id());
                    chainMap.get(chainid).add(chainMap.get(chainid)
                            .indexOf(serviceNetwork.id()), providerNetwork.id());
                    netIdMap.put(serviceNetwork.id(), chainId);
                }
            }
        }
        return chainId++;
    }

    public void tcHelper() {
        this.tcHelper(chainMap);
    }

    /**
     * 假设一个serviceNetwork, 只有一个providerNetwork.
     * @param chainmap
     */

    private void tcHelper(Map<Integer, List<NetworkId>> chainmap) {
        log.info("tcHelper");
        Set<VtnPort> vtnPorts = vtnService.vtnPorts();
        buildNumberNameMap();
        for (List<NetworkId> netidList : chainmap.values()) {
            for (int i = 0; i < netidList.size(); i++) {
                for (VtnPort vtnPort : vtnPorts) {
                    NetworkId networkId = netidList.get(i);
                    if (vtnPort.netId() == networkId) {
                        PortId portId = vtnPort.id();
                        Instance instance = getInstance(portId);
                        DeviceId deviceId = instance.deviceId();
                        PortNumber portNumber = instance.portNumber();
                        String portName = numberNameMap.get(portNumber);
                        ServiceNetwork serviceNetwork = serviceNetworkService
                                .serviceNetwork(networkId);
                        for (ProviderNetwork providerNetwork : serviceNetwork.providers()) {
                            int bandwidth = providerNetwork.bandwidth();
                            log.info(("deviceId: " + deviceId) +
                            "portName: " + portName +
                            "bandwidth: " + bandwidth);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns port name.
     *
     * @param port port
     * @return port name
     */
    private String portName(Port port) {
        return port.annotations().value(PORT_NAME);
    }

    /**
     * Returns Instance.
     *
     * @param portId
     * @return Instance
     */
    private Instance getInstance(PortId portId) {
        VtnPort vtnPort = vtnService.vtnPort(portId);
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

    /**
     * 建立 portNumber 和 portName 的 映射表.
     */
    public void buildNumberNameMap() {
        numberNameMap.clear();
        for (Device device : deviceService.getDevices()) {
            for (Port port : deviceService.getPorts(device.id())) {
                numberNameMap.put(port.number(), portName(port));
            }
        }
    }

}
