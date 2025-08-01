// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.engine.orchestration;

import static com.cloud.configuration.ConfigurationManager.MESSAGE_DELETE_VLAN_IP_RANGE_EVENT;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.db.VMNetworkMapVO;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMNetworkMapDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKey.Scope;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.cloudstack.network.dao.NetworkPermissionDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.CleanupPersistentNetworkResourceAnswer;
import com.cloud.agent.api.CleanupPersistentNetworkResourceCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.SetupPersistentNetworkAnswer;
import com.cloud.agent.api.SetupPersistentNetworkCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.deployasis.OVFNetworkTO;
import com.cloud.alert.AlertManager;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.bgp.BGPService;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.ASNumberDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Ipv6Service;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Event;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkProfile;
import com.cloud.network.NetworkService;
import com.cloud.network.NetworkStateListener;
import com.cloud.network.Networks;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetworkSetupInfo;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.VpcVirtualNetworkApplianceService;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.Ipv6GuestPrefixSubnetNetworkMapDao;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NetworkAccountDao;
import com.cloud.network.dao.NetworkAccountVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkDomainVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkServiceMapVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.RemoteAccessVpnVO;
import com.cloud.network.dao.RouterNetworkDao;
import com.cloud.network.element.AggregatedCommandExecutor;
import com.cloud.network.element.ConfigDriveNetworkElement;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.DnsServiceProvider;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.RedundantResource;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.element.VirtualRouterElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.guru.NetworkGuruAdditionalFunctions;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StaticNatRuleImpl;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ManagementServer;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.Dhcp;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicExtraDhcpOptionVO;
import com.cloud.vm.NicIpAlias;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicExtraDhcpOptionDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicIpAliasVO;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.googlecode.ipv6.IPv6Address;

/**
 * NetworkManagerImpl implements NetworkManager.
 */
public class NetworkOrchestrator extends ManagerBase implements NetworkOrchestrationService, Listener, Configurable {

    @Inject
    EntityManager _entityMgr;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    AccountDao _accountDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserVmDao _userVmDao;
    @Inject
    AlertManager _alertMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    NetworkDao _networksDao;
    @Inject
    NetworkDetailsDao networkDetailsDao;
    @Inject
    NicDao _nicDao;
    @Inject
    RulesManager _rulesMgr;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    RemoteAccessVpnService _vpnMgr;
    @Inject
    PodVlanMapDao _podVlanMapDao;
    @Inject
    NetworkOfferingDetailsDao _ntwkOffDetailsDao;
    @Inject
    AccountGuestVlanMapDao _accountGuestVlanMapDao;
    @Inject
    DataCenterVnetDao _datacenterVnetDao;
    @Inject
    NetworkAccountDao _networkAccountDao;
    @Inject
    protected NicIpAliasDao _nicIpAliasDao;
    @Inject
    protected NicExtraDhcpOptionDao _nicExtraDhcpOptionDao;
    @Inject
    protected IPAddressDao _publicIpAddressDao;
    @Inject
    protected IpAddressManager _ipAddrMgr;
    @Inject
    MessageBus _messageBus;
    @Inject
    VMNetworkMapDao _vmNetworkMapDao;
    @Inject
    DomainRouterDao routerDao;
    @Inject
    DomainRouterJoinDao routerJoinDao;
    @Inject
    RemoteAccessVpnDao _remoteAccessVpnDao;
    @Inject
    VpcVirtualNetworkApplianceService _routerService;
    @Inject
    UserVmManager _userVmMgr;
    @Inject
    TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Inject
    ResourceManager resourceManager;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    public ManagementServer mgr;
    @Inject
    NetworkPermissionDao networkPermissionDao;
    @Inject
    Ipv6Service ipv6Service;
    @Inject
    RouterNetworkDao routerNetworkDao;
    @Inject
    private VlanDetailsDao vlanDetailsDao;

    List<NetworkGuru> networkGurus;
    @Inject
    private NsxProviderDao nsxProviderDao;
    @Inject
    private NetrisProviderDao netrisProviderDao;
    @Inject
    private ASNumberDao asNumberDao;
    @Inject
    private BGPService bgpService;
    @Inject
    private Ipv6GuestPrefixSubnetNetworkMapDao ipv6GuestPrefixSubnetNetworkMapDao;

    @Override
    public List<NetworkGuru> getNetworkGurus() {
        return networkGurus;
    }

    public void setNetworkGurus(final List<NetworkGuru> networkGurus) {
        this.networkGurus = networkGurus;
    }

    List<NetworkElement> networkElements;

    public List<NetworkElement> getNetworkElements() {
        return networkElements;
    }

    public void setNetworkElements(final List<NetworkElement> networkElements) {
        this.networkElements = networkElements;
    }

    @Inject
    NetworkDomainDao _networkDomainDao;

    List<IpDeployer> ipDeployers;

    public List<IpDeployer> getIpDeployers() {
        return ipDeployers;
    }

    public void setIpDeployers(final List<IpDeployer> ipDeployers) {
        this.ipDeployers = ipDeployers;
    }

    List<DhcpServiceProvider> _dhcpProviders;

    public List<DhcpServiceProvider> getDhcpProviders() {
        return _dhcpProviders;
    }

    public void setDhcpProviders(final List<DhcpServiceProvider> dhcpProviders) {
        _dhcpProviders = dhcpProviders;
    }

    @Inject
    VMInstanceDao _vmDao;
    @Inject
    FirewallManager _firewallMgr;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;

    @Inject
    NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject
    PortForwardingRulesDao _portForwardingRulesDao;
    @Inject
    PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Inject
    AgentManager _agentMgr;
    @Inject
    HostDao _hostDao;
    @Inject
    NetworkServiceMapDao _ntwkSrvcDao;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    PrivateIpDao _privateIpDao;
    @Inject
    NetworkACLManager _networkACLMgr;
    @Inject
    NetworkModel _networkModel;
    @Inject
    NicSecondaryIpDao _nicSecondaryIpDao;
    @Inject
    ClusterDao clusterDao;
    @Inject
    RoutedIpv4Manager routedIpv4Manager;

    protected StateMachine2<Network.State, Network.Event, Network> _stateMachine;
    ScheduledExecutorService _executor;

    SearchBuilder<IPAddressVO> AssignIpAddressSearch;
    SearchBuilder<IPAddressVO> AssignIpAddressFromPodVlanSearch;

    HashMap<Long, Long> _lastNetworkIdsToFree = new HashMap<Long, Long>();

    private void updateRouterDefaultDns(final VirtualMachineProfile vmProfile, final NicProfile nicProfile) {
        if (!Type.DomainRouter.equals(vmProfile.getType()) || !nicProfile.isDefaultNic()) {
            return;
        }
        DomainRouterVO router = routerDao.findById(vmProfile.getId());
        if (router != null && router.getVpcId() != null) {
            final Vpc vpc = _vpcMgr.getActiveVpc(router.getVpcId());
            if (StringUtils.isNotBlank(vpc.getIp4Dns1())) {
                nicProfile.setIPv4Dns1(vpc.getIp4Dns1());
                nicProfile.setIPv4Dns2(vpc.getIp4Dns2());
            }
            if (StringUtils.isNotBlank(vpc.getIp6Dns1())) {
                nicProfile.setIPv6Dns1(vpc.getIp6Dns1());
                nicProfile.setIPv6Dns2(vpc.getIp6Dns2());
            }
            return;
        }
        List<Long> networkIds = routerNetworkDao.getRouterNetworks(vmProfile.getId());
        if (CollectionUtils.isEmpty(networkIds) || networkIds.size() > 1) {
            return;
        }
        final NetworkVO routerNetwork = _networksDao.findById(networkIds.get(0));
        if (StringUtils.isNotBlank(routerNetwork.getDns1())) {
            nicProfile.setIPv4Dns1(routerNetwork.getDns1());
            nicProfile.setIPv4Dns2(routerNetwork.getDns2());
        }
        if (StringUtils.isNotBlank(routerNetwork.getIp6Dns1())) {
            nicProfile.setIPv6Dns1(routerNetwork.getIp6Dns1());
            nicProfile.setIPv6Dns2(routerNetwork.getIp6Dns2());
        }
    }

    @Override
    @DB
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        // populate providers
        final Map<Network.Service, Set<Network.Provider>> defaultSharedNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        final Set<Network.Provider> defaultProviders = new HashSet<Network.Provider>();
        final Set<Network.Provider> tungstenProvider = new HashSet<>();

        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultSharedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.UserData, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultIsolatedNetworkOfferingProviders = defaultSharedNetworkOfferingProviders;
        defaultIsolatedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultSharedSGEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        final Set<Provider> sgProviders = new HashSet<Provider>();
        sgProviders.add(Provider.SecurityGroupProvider);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.SecurityGroup, sgProviders);

        tungstenProvider.add(Provider.Tungsten);
        final Map<Network.Service, Set<Network.Provider>> defaultTungstenSharedSGEnabledNetworkOfferingProviders = new HashMap<>();
        defaultTungstenSharedSGEnabledNetworkOfferingProviders.put(Service.Connectivity, tungstenProvider);
        defaultTungstenSharedSGEnabledNetworkOfferingProviders.put(Service.Dhcp, tungstenProvider);
        defaultTungstenSharedSGEnabledNetworkOfferingProviders.put(Service.Dns, tungstenProvider);
        defaultTungstenSharedSGEnabledNetworkOfferingProviders.put(Service.UserData, tungstenProvider);
        defaultTungstenSharedSGEnabledNetworkOfferingProviders.put(Service.SecurityGroup, tungstenProvider);


        final Map<Network.Service, Set<Network.Provider>> defaultIsolatedSourceNatEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.SourceNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        final Map<Network.Service, Set<Network.Provider>> defaultVPCOffProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VPCVirtualRouter);
        defaultVPCOffProviders.put(Service.Dhcp, defaultProviders);
        defaultVPCOffProviders.put(Service.Dns, defaultProviders);
        defaultVPCOffProviders.put(Service.UserData, defaultProviders);
        defaultVPCOffProviders.put(Service.NetworkACL, defaultProviders);
        defaultVPCOffProviders.put(Service.Gateway, defaultProviders);
        defaultVPCOffProviders.put(Service.Lb, defaultProviders);
        defaultVPCOffProviders.put(Service.SourceNat, defaultProviders);
        defaultVPCOffProviders.put(Service.StaticNat, defaultProviders);
        defaultVPCOffProviders.put(Service.PortForwarding, defaultProviders);
        defaultVPCOffProviders.put(Service.Vpn, defaultProviders);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                NetworkOfferingVO offering = null;
                //#1 - quick cloud network offering
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.QuickCloudNoServices) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.QuickCloudNoServices, "Offering for QuickCloud with no services", TrafficType.Guest, null, true,
                            Availability.Optional, null, new HashMap<Network.Service, Set<Network.Provider>>(), true, Network.GuestType.Shared, false, null, true, null, true,
                            false, null, false, null, true, false, false, false, false, null, null, null, true, null, null, false);
                }

                //#2 - SG enabled network offering
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOfferingWithSGService) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedNetworkOfferingWithSGService, "Offering for Shared Security group enabled networks",
                            TrafficType.Guest, null, true, Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true,
                            null, true, false, null, false, null, true, false, false, false, false, null, null, null, true, null, null, false);
                }

                //#3 - shared network offering with no SG service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOffering) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedNetworkOffering, "Offering for Shared networks", TrafficType.Guest, null, true,
                            Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true, null, true, false, null, false,
                            null, true, false, false, false, false, null, null, null, true, null, null, false);
                }

                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DEFAULT_TUNGSTEN_SHARED_NETWORK_OFFERING_WITH_SGSERVICE) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DEFAULT_TUNGSTEN_SHARED_NETWORK_OFFERING_WITH_SGSERVICE, "Offering for Tungsten Shared Security group enabled networks",
                            TrafficType.Guest, null, true, Availability.Optional, null, defaultTungstenSharedSGEnabledNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, true,
                            null, true, false, null, false, null, true, false, true, false, false, null, null, null, true, null, null, false);
                    offering.setState(NetworkOffering.State.Enabled);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                //#4 - default isolated offering with Source nat service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService,
                            "Offering for Isolated networks with Source Nat service enabled", TrafficType.Guest, null, false, Availability.Required, null,
                            defaultIsolatedSourceNatEnabledNetworkOfferingProviders, true, Network.GuestType.Isolated, false, null, true, null, false, false, null, false, null,
                            true, false, false, false, false, null, null, null, true, null, null, false);
                }

                //#5 - default vpc offering with LB service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworks) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworks,
                            "Offering for Isolated VPC networks with Source Nat service enabled", TrafficType.Guest, null, false, Availability.Optional, null,
                            defaultVPCOffProviders, true, Network.GuestType.Isolated, false, null, true, null, false, false, null, false, null, true, true, false, false, false, null, null, null, true, null, null, false);
                }

                //#6 - default vpc offering with no LB service
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksNoLB) == null) {
                    //remove LB service
                    defaultVPCOffProviders.remove(Service.Lb);
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksNoLB,
                            "Offering for Isolated VPC networks with Source Nat service enabled and LB service disabled", TrafficType.Guest, null, false, Availability.Optional,
                            null, defaultVPCOffProviders, true, Network.GuestType.Isolated, false, null, false, null, false, false, null, false, null, true, true, false, false, false, null, null, null, true, null, null, false);
                }

                //#7 - isolated offering with source nat disabled
                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOffering) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOffering, "Offering for Isolated networks with no Source Nat service",
                            TrafficType.Guest, null, true, Availability.Optional, null, defaultIsolatedNetworkOfferingProviders, true, Network.GuestType.Isolated, false, null,
                            true, null, true, false, null, false, null, true, false, false, false, false, null, null, null, true, null, null, false);
                }

                //#8 - network offering with internal lb service
                final Map<Network.Service, Set<Network.Provider>> internalLbOffProviders = new HashMap<Network.Service, Set<Network.Provider>>();
                final Set<Network.Provider> defaultVpcProvider = new HashSet<Network.Provider>();
                defaultVpcProvider.add(Network.Provider.VPCVirtualRouter);

                final Set<Network.Provider> defaultInternalLbProvider = new HashSet<Network.Provider>();
                defaultInternalLbProvider.add(Network.Provider.InternalLbVm);

                internalLbOffProviders.put(Service.Dhcp, defaultVpcProvider);
                internalLbOffProviders.put(Service.Dns, defaultVpcProvider);
                internalLbOffProviders.put(Service.UserData, defaultVpcProvider);
                internalLbOffProviders.put(Service.NetworkACL, defaultVpcProvider);
                internalLbOffProviders.put(Service.Gateway, defaultVpcProvider);
                internalLbOffProviders.put(Service.Lb, defaultInternalLbProvider);
                internalLbOffProviders.put(Service.SourceNat, defaultVpcProvider);

                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksWithInternalLB) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultIsolatedNetworkOfferingForVpcNetworksWithInternalLB,
                            "Offering for Isolated VPC networks with Internal Lb support", TrafficType.Guest, null, false, Availability.Optional, null, internalLbOffProviders,
                            true, Network.GuestType.Isolated, false, null, false, null, false, false, null, false, null, true, true, false, false, false, null, null, null, true, null, null, false);
                    offering.setInternalLb(true);
                    offering.setPublicLb(false);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                final Map<Network.Service, Set<Network.Provider>> netscalerServiceProviders = new HashMap<Network.Service, Set<Network.Provider>>();
                final Set<Network.Provider> vrProvider = new HashSet<Network.Provider>();
                vrProvider.add(Provider.VirtualRouter);
                final Set<Network.Provider> sgProvider = new HashSet<Network.Provider>();
                sgProvider.add(Provider.SecurityGroupProvider);
                final Set<Network.Provider> nsProvider = new HashSet<Network.Provider>();
                nsProvider.add(Provider.Netscaler);
                netscalerServiceProviders.put(Service.Dhcp, vrProvider);
                netscalerServiceProviders.put(Service.Dns, vrProvider);
                netscalerServiceProviders.put(Service.UserData, vrProvider);
                netscalerServiceProviders.put(Service.SecurityGroup, sgProvider);
                netscalerServiceProviders.put(Service.StaticNat, nsProvider);
                netscalerServiceProviders.put(Service.Lb, nsProvider);

                final Map<Service, Map<Capability, String>> serviceCapabilityMap = new HashMap<Service, Map<Capability, String>>();
                final Map<Capability, String> elb = new HashMap<Capability, String>();
                elb.put(Capability.ElasticLb, "true");
                final Map<Capability, String> eip = new HashMap<Capability, String>();
                eip.put(Capability.ElasticIp, "true");
                serviceCapabilityMap.put(Service.Lb, elb);
                serviceCapabilityMap.put(Service.StaticNat, eip);

                if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedEIPandELBNetworkOffering) == null) {
                    offering = _configMgr.createNetworkOffering(NetworkOffering.DefaultSharedEIPandELBNetworkOffering,
                            "Offering for Shared networks with Elastic IP and Elastic LB capabilities", TrafficType.Guest, null, true, Availability.Optional, null,
                            netscalerServiceProviders, true, Network.GuestType.Shared, false, null, true, serviceCapabilityMap, true, false, null, false, null, true, false, false, false, false, null, null, null, true, null, null, false);
                    offering.setDedicatedLB(false);
                    _networkOfferingDao.update(offering.getId(), offering);
                }

                _networkOfferingDao.persistDefaultL2NetworkOfferings();
            }
        });

        AssignIpAddressSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressSearch.and("dc", AssignIpAddressSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressSearch.and("allocated", AssignIpAddressSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressSearch.and("vlanId", AssignIpAddressSearch.entity().getVlanId(), Op.IN);
        final SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("type", vlanSearch.entity().getVlanType(), Op.EQ);
        vlanSearch.and("networkId", vlanSearch.entity().getNetworkId(), Op.EQ);
        AssignIpAddressSearch.join("vlan", vlanSearch, vlanSearch.entity().getId(), AssignIpAddressSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressSearch.done();

        AssignIpAddressFromPodVlanSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressFromPodVlanSearch.and("dc", AssignIpAddressFromPodVlanSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.and("allocated", AssignIpAddressFromPodVlanSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressFromPodVlanSearch.and("vlanId", AssignIpAddressFromPodVlanSearch.entity().getVlanId(), Op.IN);

        final SearchBuilder<VlanVO> podVlanSearch = _vlanDao.createSearchBuilder();
        podVlanSearch.and("type", podVlanSearch.entity().getVlanType(), Op.EQ);
        podVlanSearch.and("networkId", podVlanSearch.entity().getNetworkId(), Op.EQ);
        final SearchBuilder<PodVlanMapVO> podVlanMapSB = _podVlanMapDao.createSearchBuilder();
        podVlanMapSB.and("podId", podVlanMapSB.entity().getPodId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.join("podVlanMapSB", podVlanMapSB, podVlanMapSB.entity().getVlanDbId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(),
                JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.join("vlan", podVlanSearch, podVlanSearch.entity().getId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);

        AssignIpAddressFromPodVlanSearch.done();

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Network-Scavenger"));

        _agentMgr.registerForHostEvents(this, true, false, true);

        Network.State.getStateMachine().registerListener(new NetworkStateListener(_configDao));

        logger.info("Network Manager is configured.");

        return true;
    }

    @Override
    public boolean start() {
        final int netGcInterval = NumbersUtil.parseInt(_configDao.getValue(NetworkGcInterval.key()), 60);
        logger.info("Network Manager will run the NetworkGarbageCollector every '{}' seconds.", netGcInterval);

        _executor.scheduleWithFixedDelay(new NetworkGarbageCollector(), netGcInterval, netGcInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected NetworkOrchestrator() {
        setStateMachine();
    }

    private void updateRouterIpInNetworkDetails(Long networkId, String routerIp, String routerIpv6) {
        if (StringUtils.isNotBlank(routerIp)) {
            networkDetailsDao.addDetail(networkId, ApiConstants.ROUTER_IP, routerIp, true);
        }
        if (StringUtils.isNotBlank(routerIpv6)) {
            networkDetailsDao.addDetail(networkId, ApiConstants.ROUTER_IPV6, routerIpv6, true);
        }
    }

    @Override
    public List<? extends Network> setupNetwork(final Account owner, final NetworkOffering offering, final DeploymentPlan plan, final String name, final String displayText, final boolean isDefault)
            throws ConcurrentOperationException {
        return setupNetwork(owner, offering, null, plan, name, displayText, false, null, null, null, null, true);
    }

    @Override
    @DB
    public List<? extends Network> setupNetwork(final Account owner, final NetworkOffering offering, final Network predefined, final DeploymentPlan plan, final String name,
                                                final String displayText, final boolean errorIfAlreadySetup, final Long domainId, final ACLType aclType, final Boolean subdomainAccess, final Long vpcId,
                                                final Boolean isDisplayNetworkEnabled) throws ConcurrentOperationException {

        final Account locked = _accountDao.acquireInLockTable(owner.getId());
        if (locked == null) {
            throw new ConcurrentOperationException("Unable to acquire lock on " + owner);
        }

        try {
            if (predefined == null
                    || offering.getTrafficType() != TrafficType.Guest && predefined.getCidr() == null && predefined.getBroadcastUri() == null && !(predefined
                    .getBroadcastDomainType() == BroadcastDomainType.Vlan || predefined.getBroadcastDomainType() == BroadcastDomainType.Lswitch || predefined
                    .getBroadcastDomainType() == BroadcastDomainType.Vxlan)) {
                final List<NetworkVO> configs = _networksDao.listBy(owner.getId(), offering.getId(), plan.getDataCenterId());
                if (!configs.isEmpty()) {
                    return existingConfiguration(offering, configs, errorIfAlreadySetup);
                }
            }

            final List<NetworkVO> networks = new ArrayList<NetworkVO>();

            long related = -1;

            for (final NetworkGuru guru : networkGurus) {
                final Network network = guru.design(offering, plan, predefined, name, vpcId, owner);
                if (network == null) {
                    continue;
                }

                // Ensure cidr size is equal to 64 for
                //      - networks other than shared networks
                //      - shared networks with SLAAC V6 only
                if (predefined != null && StringUtils.isNotBlank(predefined.getIp6Cidr()) &&
                        (!GuestType.Shared.equals(offering.getGuestType()) || guru.isSlaacV6Only())) {
                    _networkModel.checkIp6CidrSizeEqualTo64(predefined.getIp6Cidr());
                }

                if (network.getId() != -1) {
                    if (network instanceof NetworkVO) {
                        networks.add((NetworkVO) network);
                    } else {
                        networks.add(_networksDao.findById(network.getId()));
                    }
                    continue;
                }

                final long id = _networksDao.getNextInSequence(Long.class, "id");
                if (related == -1) {
                    related = id;
                }

                final long relatedFile = related;
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        final NetworkVO vo = getNetworkVO(id, offering, plan, predefined,
                                network, guru, owner, name, displayText,relatedFile, aclType,vpcId, isDisplayNetworkEnabled);
                        final NetworkVO networkPersisted = _networksDao.persist(vo, vo.getGuestType() == Network.GuestType.Isolated,
                                finalizeServicesAndProvidersForNetwork(offering, plan.getPhysicalNetworkId()));
                        networks.add(networkPersisted);
                        if (network.getPvlanType() != null) {
                            NetworkDetailVO detailVO = new NetworkDetailVO(networkPersisted.getId(), ApiConstants.ISOLATED_PVLAN_TYPE, network.getPvlanType().toString(), true);
                            networkDetailsDao.persist(detailVO);
                        }

                        updateRouterIpInNetworkDetails(networkPersisted.getId(), network.getRouterIp(), network.getRouterIpv6());

                        if (predefined instanceof NetworkVO && guru instanceof NetworkGuruAdditionalFunctions) {
                            final NetworkGuruAdditionalFunctions functions = (NetworkGuruAdditionalFunctions) guru;
                            functions.finalizeNetworkDesign(networkPersisted.getId(), ((NetworkVO) predefined).getVlanIdAsUUID());
                        }

                        if (domainId != null && aclType == ACLType.Domain) {
                            _networksDao.addDomainToNetwork(id, domainId, subdomainAccess == null || subdomainAccess);
                        }
                        String ipv6Cidr = network.getIp6Cidr();
                        String ipv6Gateway = network.getIp6Gateway();
                        if (StringUtils.isNoneBlank(ipv6Cidr, ipv6Gateway)) {
                            ipv6Service.assignIpv6SubnetToNetwork(ipv6Cidr, networkPersisted.getId());
                        }
                    }
                });
                guru.setup(network, relatedFile);
            }

            if (networks.isEmpty()) {
                // see networkOfferingVO.java
                final CloudRuntimeException ex = new CloudRuntimeException("Unable to convert network offering with specified id to network profile");
                ex.addProxyObject(offering.getUuid(), "offeringId");
                throw ex;
            }

            return networks;
        } finally {
            logger.debug("Releasing lock for {}", locked);
            _accountDao.releaseFromLockTable(locked.getId());
        }
    }

    @NotNull
    private static NetworkVO getNetworkVO(long id, final NetworkOffering offering, final DeploymentPlan plan, final Network predefined,
                                          Network network, final NetworkGuru guru, final Account owner,
                                          final String name, final String displayText, long relatedFile, final ACLType aclType,
                                          final Long vpcId, final Boolean isDisplayNetworkEnabled) {
        final NetworkVO vo = new NetworkVO(id, network, offering.getId(), guru.getName(), owner.getDomainId(), owner.getId(),
                relatedFile, name, displayText, predefined.getNetworkDomain(), offering.getGuestType(),
                plan.getDataCenterId(), plan.getPhysicalNetworkId(), aclType, offering.isSpecifyIpRanges(),
                vpcId, offering.isRedundantRouter(), predefined.getExternalId());
        vo.setDisplayNetwork(isDisplayNetworkEnabled == null || isDisplayNetworkEnabled);
        vo.setStrechedL2Network(offering.isSupportingStrechedL2());
        return vo;
    }

    private List<? extends Network> existingConfiguration(final NetworkOffering offering, List<NetworkVO> configs,
                                                          final boolean errorIfAlreadySetup) {
        logger.debug("Found existing network configuration for offering {}: {}", offering, configs.get(0));

        if (errorIfAlreadySetup) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "Found existing network configuration (with specified id) for offering (with specified id)");
            ex.addProxyObject(offering.getUuid(), "offeringId");
            ex.addProxyObject(configs.get(0).getUuid(), "networkConfigId");
            throw ex;
        } else {
            return configs;
        }
    }

    @Override
    @DB
    public void allocate(final VirtualMachineProfile vm, final LinkedHashMap<? extends Network, List<? extends NicProfile>> networks, final Map<String, Map<Integer, String>> extraDhcpOptions) throws InsufficientCapacityException,
            ConcurrentOperationException {

        logger.trace("allocating networks for {}(template {}); {} networks", vm.getInstanceName(), vm.getTemplate().getUuid(), networks.size());
        int deviceId = 0;
        int size;
        size = determineNumberOfNicsRequired(vm, networks);

        final boolean[] deviceIds = new boolean[size];
        Arrays.fill(deviceIds, false);

        List<Pair<Network, NicProfile>> profilesList = getOrderedNetworkNicProfileMapping(networks);
        final List<NicProfile> nics = new ArrayList<NicProfile>(size);
        NicProfile defaultNic = null;
        Network nextNetwork = null;
        for (Pair<Network, NicProfile> networkNicPair : profilesList) {
            nextNetwork = networkNicPair.first();
            Pair<NicProfile, Integer> newDeviceInfo = addRequestedNicToNicListWithDeviceNumberAndRetrieveDefaultDevice(vm, extraDhcpOptions, networkNicPair.second(), deviceIds, deviceId, nextNetwork, nics, defaultNic);
            defaultNic = newDeviceInfo.first();
            deviceId = newDeviceInfo.second();
        }
        createExtraNics(vm,size, nics, nextNetwork);

        if (nics.size() == 1) {
            nics.get(0).setDefaultNic(true);
        }
    }

    /**
     * Method to check and add devices to the nic list and update the info
     */
    private Pair<NicProfile, Integer> addRequestedNicToNicListWithDeviceNumberAndRetrieveDefaultDevice(VirtualMachineProfile vm, Map<String, Map<Integer, String>> extraDhcpOptions,
                                                                                                       NicProfile requested, boolean[] deviceIds, int deviceId, Network nextNetwork, List<NicProfile> nics, NicProfile defaultNic)
            throws InsufficientAddressCapacityException, InsufficientVirtualNetworkCapacityException {
        Pair<NicProfile, Integer> rc = new Pair<>(null, null);
        Boolean isDefaultNic = false;
        if (vm != null && requested != null && requested.isDefaultNic()) {
            isDefaultNic = true;
        }

        while (deviceIds[deviceId] && deviceId < deviceIds.length) {
            deviceId++;
        }

        final Pair<NicProfile, Integer> vmNicPair = allocateNic(requested, nextNetwork, isDefaultNic, deviceId, vm);
        NicProfile vmNic = null;
        if (vmNicPair != null) {
            vmNic = vmNicPair.first();
            if (vmNic == null) {
                return rc;
            }
            deviceId = vmNicPair.second();
        }

        final int devId = vmNic.getDeviceId();
        if (devId >= deviceIds.length) {
            throw new IllegalArgumentException("Device id for nic is too large: " + vmNic);
        }
        if (deviceIds[devId]) {
            throw new IllegalArgumentException("Conflicting device id for two different nics: " + vmNic);
        }

        deviceIds[devId] = true;

        if (vmNic.isDefaultNic()) {
            if (defaultNic != null) {
                throw new IllegalArgumentException("You cannot specify two nics as default nics: nic 1 = " + defaultNic + "; nic 2 = " + vmNic);
            }
            defaultNic = vmNic;
        }

        nics.add(vmNic);
        vm.addNic(vmNic);
        saveExtraDhcpOptions(nextNetwork.getUuid(), vmNic.getId(), extraDhcpOptions);
        rc.first(defaultNic);
        rc.second(deviceId);
        return rc;
    }

    /**
     * Method to get oredered list of Network and NicProfile pair
     * @return ordered list of Network and NicProfile pair
     * @param networks the map od networks to nic profiles list
     */
    private List<Pair<Network, NicProfile>> getOrderedNetworkNicProfileMapping(final LinkedHashMap<? extends Network, List<? extends NicProfile>> networks) {
        List<Pair<Network, NicProfile>> profilesList = new ArrayList<>();
        for (final Map.Entry<? extends Network, List<? extends NicProfile>> network : networks.entrySet()) {
            List<? extends NicProfile> requestedProfiles = network.getValue();
            if (requestedProfiles == null) {
                requestedProfiles = new ArrayList<NicProfile>();
            }
            if (requestedProfiles.isEmpty()) {
                requestedProfiles.add(null);
            }
            for (final NicProfile requested : requestedProfiles) {
                profilesList.add(new Pair<Network, NicProfile>(network.getKey(), requested));
            }
        }
        profilesList.sort(new Comparator<Pair<Network, NicProfile>>() {
            @Override
            public int compare(Pair<Network, NicProfile> pair1, Pair<Network, NicProfile> pair2) {
                int profile1Order = Integer.MAX_VALUE;
                int profile2Order = Integer.MAX_VALUE;
                if (pair1 != null && pair1.second() != null && pair1.second().getOrderIndex() != null) {
                    profile1Order = pair1.second().getOrderIndex();
                }
                if (pair2 != null && pair2.second() != null && pair2.second().getOrderIndex() != null) {
                    profile2Order = pair2.second().getOrderIndex();
                }
                return profile1Order - profile2Order;
            }
        });
        return profilesList;
    }

    /**
     * private transaction method to run over the objects and determine nic requirements
     * @return the total numer of nics required
     */
    private int determineNumberOfNicsRequired(final VirtualMachineProfile vm, final LinkedHashMap<? extends Network, List<? extends NicProfile>> networks) {
        int size = 0;
        for (final Network ntwk : networks.keySet()) {
            final List<? extends NicProfile> profiles = networks.get(ntwk);
            if (profiles != null && !profiles.isEmpty()) {
                size = size + profiles.size();
            } else {
                size = size + 1;
            }
        }

        List<OVFNetworkTO> netprereqs = templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(vm.getTemplate().getId());
        if (size < netprereqs.size()) {
            size = netprereqs.size();
        }
        return size;
    }

    /**
     * Method to add nics as required
     * @param size the number needed
     * @param nics the list of nics present
     * @param finalNetwork the network to add the nics to
     * @throws InsufficientVirtualNetworkCapacityException great
     * @throws InsufficientAddressCapacityException also magnificent, as the name suggests
     */
    private void createExtraNics(final VirtualMachineProfile vm, int size, List<NicProfile> nics, Network finalNetwork) throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        if (nics.size() != size) {
            logger.warn("Number of nics {} doesn't match number of requested nics {}", nics.size(), size);
            if (nics.size() > size) {
                throw new CloudRuntimeException("Number of nics " + nics.size() + " doesn't match number of requested networks " + size);
            } else {
                if (finalNetwork == null) {
                    throw new CloudRuntimeException(String.format("can not assign network to %d remaining required NICs", size - nics.size()));
                }
                // create extra
                for (int extraNicNum = nics.size(); extraNicNum < size; extraNicNum++) {
                    final Pair<NicProfile, Integer> vmNicPair = allocateNic(new NicProfile(), finalNetwork, false, extraNicNum, vm);
                }
            }
        }
    }

    @Override
    public void saveExtraDhcpOptions(final String networkUuid, final Long nicId, final Map<String, Map<Integer, String>> extraDhcpOptionMap) {

        if (extraDhcpOptionMap != null) {
            Map<Integer, String> extraDhcpOption = extraDhcpOptionMap.get(networkUuid);
            if (extraDhcpOption != null) {
                List<NicExtraDhcpOptionVO> nicExtraDhcpOptionList = new LinkedList<>();

                for (Integer code : extraDhcpOption.keySet()) {
                    Dhcp.DhcpOptionCode.valueOfInt(code); //check if code is supported or not.
                    NicExtraDhcpOptionVO nicExtraDhcpOptionVO = new NicExtraDhcpOptionVO(nicId, code, extraDhcpOption.get(code));
                    nicExtraDhcpOptionList.add(nicExtraDhcpOptionVO);
                }
                _nicExtraDhcpOptionDao.saveExtraDhcpOptions(nicExtraDhcpOptionList);
            }
        }
    }

    private NicVO persistNicAfterRaceCheck(final NicVO nic, final Long networkId, final NicProfile profile, int deviceId) {
        return Transaction.execute(new TransactionCallback<NicVO>() {
            @Override
            public NicVO doInTransaction(TransactionStatus status) {
                NicVO vo = _nicDao.findNonPlaceHolderByIp4AddressAndNetworkId(profile.getIPv4Address(), networkId);
                if (vo == null) {
                    applyProfileToNic(nic, profile, deviceId);
                    vo = _nicDao.persist(nic);
                    return vo;
                } else {
                    return null;
                }
            }
        });
    }

    private NicVO checkForRaceAndAllocateNic(final NicProfile requested, final Network network, final Boolean isDefaultNic, int deviceId, final VirtualMachineProfile vm)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        final NetworkVO ntwkVO = _networksDao.findById(network.getId());
        logger.debug("Allocating nic for vm {} in network {} with requested profile {}", vm.getVirtualMachine(), network, requested);
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, ntwkVO.getGuruName());

        NicVO vo = null;
        boolean retryIpAllocation;
        do {
            retryIpAllocation = false;
            final NicProfile profile = guru.allocate(network, requested, vm);
            if (profile == null) {
                return null;
            }

            if (isNicAllocatedForProviderPublicNetworkOnVR(network, profile, vm, Provider.Nsx)) {
                String guruName = "NsxPublicNetworkGuru";
                NetworkGuru nsxGuru = AdapterBase.getAdapterByName(networkGurus, guruName);
                nsxGuru.allocate(network, profile, vm);
            } else if (isNicAllocatedForProviderPublicNetworkOnVR(network, profile, vm, Provider.Netris)) {
                String guruName = "NetrisPublicNetworkGuru";
                NetworkGuru netrisGuru = AdapterBase.getAdapterByName(networkGurus, guruName);
                netrisGuru.allocate(network, profile, vm);
            }

            if (isDefaultNic != null) {
                profile.setDefaultNic(isDefaultNic);
            }

            if (requested != null && requested.getMode() == null) {
                profile.setMode(requested.getMode());
            } else {
                profile.setMode(network.getMode());
            }

            vo = new NicVO(guru.getName(), vm.getId(), network.getId(), vm.getType());

            DataCenterVO dcVo = _dcDao.findById(network.getDataCenterId());
            if (dcVo.getNetworkType() == NetworkType.Basic) {
                configureNicProfileBasedOnRequestedIp(requested, profile, network);
            }

            if (profile.getIpv4AllocationRaceCheck()) {
                vo = persistNicAfterRaceCheck(vo, network.getId(), profile, deviceId);
            } else {
                applyProfileToNic(vo, profile, deviceId);
                vo = _nicDao.persist(vo);
            }

            if (vo == null) {
                if (requested.getRequestedIPv4() != null) {
                    throw new InsufficientVirtualNetworkCapacityException("Unable to acquire requested Guest IP address " + requested.getRequestedIPv4() + " for network " + network, DataCenter.class, dcVo.getId());
                } else {
                    requested.setIPv4Address(null);
                }
                retryIpAllocation = true;
            }
        } while (retryIpAllocation);

        return vo;
    }

    @DB
    @Override
    public Pair<NicProfile, Integer> allocateNic(final NicProfile requested, final Network network, final Boolean isDefaultNic, int deviceId, final VirtualMachineProfile vm)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException {

        if (requested != null && requested.getMode() == null) {
            requested.setMode(network.getMode());
        }

        NicVO vo = checkForRaceAndAllocateNic(requested, network, isDefaultNic, deviceId, vm);

        final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());
        final NicProfile vmNic = new NicProfile(vo, network, vo.getBroadcastUri(), vo.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                _networkModel.getNetworkTag(vm.getHypervisorType(), network));
        if (vm.getType() == Type.DomainRouter) {
            Pair<NetworkVO, VpcVO> networks = getGuestNetworkRouterAndVpcDetails(vm.getId());
            setMtuDetailsInVRNic(networks, network, vo);
            _nicDao.update(vo.getId(), vo);
            setMtuInVRNicProfile(networks, network.getTrafficType(), vmNic);
        }
        return new Pair<NicProfile, Integer>(vmNic, Integer.valueOf(deviceId));
    }

    private boolean isNicAllocatedForProviderPublicNetworkOnVR(Network network, NicProfile requested, VirtualMachineProfile vm, Provider provider) {
        if (ObjectUtils.anyNull(network, requested, vm)) {
            return false;
        }
        boolean isVirtualRouter = vm.getType() == Type.DomainRouter;
        boolean isPublicTraffic = network.getTrafficType() == TrafficType.Public;
        if (!isVirtualRouter || !isPublicTraffic || requested.getIPv4Address() == null) {
            return false;
        }
        long dataCenterId = vm.getVirtualMachine().getDataCenterId();
        if (Provider.Nsx == provider && nsxProviderDao.findByZoneId(dataCenterId) == null) {
            return false;
        } else if (Provider.Netris == provider && netrisProviderDao.findByZoneId(dataCenterId) == null) {
            return false;
        }

        Long vpcId = _ipAddressDao.findByIp(requested.getIPv4Address()).getVpcId();
        List<IPAddressVO> ips = _ipAddressDao.listByAssociatedVpc(vpcId, true);

        if (CollectionUtils.isEmpty(ips)) {
            return false;
        }

        ips = ips.stream().filter(x -> !x.getAddress().addr().equals(requested.getIPv4Address())).collect(Collectors.toList());
        IPAddressVO ip = ips.get(0);
        String detailKey = Provider.Nsx == provider ? ApiConstants.NSX_DETAIL_KEY : ApiConstants.NETRIS_DETAIL_KEY;
        VlanDetailsVO vlanDetail = vlanDetailsDao.findDetail(ip.getVlanId(), detailKey);
        if (vlanDetail == null) {
            return false;
        }
        boolean isForProvider = vlanDetail.getValue().equalsIgnoreCase("true");
        return isForProvider && !ip.isForSystemVms();
    }

    private void setMtuDetailsInVRNic(final Pair<NetworkVO, VpcVO> networks, Network network, NicVO vo) {
        if (TrafficType.Public == network.getTrafficType()) {
            if (networks == null) {
                return;
            }
            NetworkVO networkVO = networks.first();
            VpcVO vpcVO = networks.second();
            if (vpcVO != null) {
                vo.setMtu(vpcVO.getPublicMtu());
            } else {
                vo.setMtu(networkVO.getPublicMtu());
            }
        } else if (TrafficType.Guest == network.getTrafficType()) {
            vo.setMtu(network.getPrivateMtu());
        }
    }

    private void setMtuInVRNicProfile(final Pair<NetworkVO, VpcVO> networks, TrafficType trafficType, NicProfile vmNic) {
        if (networks == null) {
            return;
        }
        NetworkVO networkVO = networks.first();
        VpcVO vpcVO = networks.second();
        if (networkVO != null) {
            if (TrafficType.Public == trafficType) {
                if (vpcVO != null) {
                    vmNic.setMtu(vpcVO.getPublicMtu());
                } else {
                    vmNic.setMtu(networkVO.getPublicMtu());
                }
            } else if (TrafficType.Guest == trafficType) {
                vmNic.setMtu(networkVO.getPrivateMtu());
            }
        }
    }

    private Pair<NetworkVO, VpcVO> getGuestNetworkRouterAndVpcDetails(long routerId) {
        List<DomainRouterJoinVO> routerVo = routerJoinDao.getRouterByIdAndTrafficType(routerId, TrafficType.Guest);
        if (routerVo.isEmpty()) {
            routerVo = routerJoinDao.getRouterByIdAndTrafficType(routerId, TrafficType.Public);
            if (routerVo.isEmpty()) {
                return null;
            }
        }
        DomainRouterJoinVO guestRouterDetails = routerVo.get(0);
        VpcVO vpc = null;
        if (guestRouterDetails.getVpcId() != 0)  {
            vpc = _entityMgr.findById(VpcVO.class, guestRouterDetails.getVpcId());
        }
        long networkId = guestRouterDetails.getNetworkId();
        return new Pair<>(_networksDao.findById(networkId), vpc);
    }

    /**
     * If the requested IPv4 address from the NicProfile was configured then it configures the IPv4 address, Netmask and Gateway to deploy the VM with the requested IP.
     */
    protected void configureNicProfileBasedOnRequestedIp(NicProfile requestedNicProfile, NicProfile nicProfile, Network network) {
        if (requestedNicProfile == null) {
            return;
        }
        String requestedIpv4Address = requestedNicProfile.getRequestedIPv4();
        if (requestedIpv4Address == null) {
            return;
        }
        if (!NetUtils.isValidIp4(requestedIpv4Address)) {
            throw new InvalidParameterValueException(String.format("The requested [IPv4 address='%s'] is not a valid IP address", requestedIpv4Address));
        }

        VlanVO vlanVo = _vlanDao.findByNetworkIdAndIpv4(network.getId(), requestedIpv4Address);
        if (vlanVo == null) {
            throw new InvalidParameterValueException(String.format("Trying to configure a Nic with the requested [IPv4='%s'] but cannot find a Vlan for the [network '%s']",
                    requestedIpv4Address, network));
        }

        String ipv4Gateway = vlanVo.getVlanGateway();
        String ipv4Netmask = vlanVo.getVlanNetmask();

        if (!NetUtils.isValidIp4(ipv4Gateway)) {
            throw new InvalidParameterValueException(String.format("The [IPv4Gateway='%s'] from [Vlan id=%d uuid=%s] is not valid", ipv4Gateway, vlanVo.getId(), vlanVo.getUuid()));
        }
        if (!NetUtils.isValidIp4Netmask(ipv4Netmask)) {
            throw new InvalidParameterValueException(String.format("The [IPv4Netmask='%s'] from [Vlan id=%d uuid=%s] is not valid", ipv4Netmask, vlanVo.getId(), vlanVo.getUuid()));
        }

        acquireLockAndCheckIfIpv4IsFree(network, requestedIpv4Address);

        nicProfile.setIPv4Address(requestedIpv4Address);
        nicProfile.setIPv4Gateway(ipv4Gateway);
        nicProfile.setIPv4Netmask(ipv4Netmask);

        if (nicProfile.getMacAddress() == null) {
            try {
                String macAddress = _networkModel.getNextAvailableMacAddressInNetwork(network.getId());
                nicProfile.setMacAddress(macAddress);
            } catch (InsufficientAddressCapacityException e) {
                throw new CloudRuntimeException(String.format("Cannot get next available mac address in [network %s]", network), e);
            }
        }
    }

    /**
     * Acquires lock in "user_ip_address" and checks if the requested IPv4 address is Free.
     */
    protected void acquireLockAndCheckIfIpv4IsFree(Network network, String requestedIpv4Address) {
        IPAddressVO ipVO = _ipAddressDao.findByIpAndSourceNetworkId(network.getId(), requestedIpv4Address);
        if (ipVO == null) {
            throw new InvalidParameterValueException(
                    String.format("Cannot find IPAddressVO for guest [IPv4 address='%s'] and [network %s]", requestedIpv4Address, network));
        }
        try {
            IPAddressVO lockedIpVO = _ipAddressDao.acquireInLockTable(ipVO.getId());
            validateLockedRequestedIp(ipVO, lockedIpVO);
            lockedIpVO.setState(IPAddressVO.State.Allocated);
            _ipAddressDao.update(lockedIpVO.getId(), lockedIpVO);
        } finally {
            _ipAddressDao.releaseFromLockTable(ipVO.getId());
        }
    }

    /**
     * Validates the locked IP, throwing an exception if the locked IP is null or the locked IP is not in 'Free' state.
     */
    protected void validateLockedRequestedIp(IPAddressVO ipVO, IPAddressVO lockedIpVO) {
        if (lockedIpVO == null) {
            throw new InvalidParameterValueException(String.format("Cannot acquire guest [IPv4 address='%s'] as it was removed while acquiring lock", ipVO.getAddress()));
        }
        if (lockedIpVO.getState() != IPAddressVO.State.Free) {
            throw new InvalidParameterValueException(
                    String.format("Cannot acquire guest [IPv4 address='%s']; The Ip address is in [state='%s']", ipVO.getAddress(), lockedIpVO.getState().toString()));
        }
    }

    protected Integer applyProfileToNic(final NicVO vo, final NicProfile profile, Integer deviceId) {
        if (profile.getDeviceId() != null) {
            vo.setDeviceId(profile.getDeviceId());
        } else if (deviceId != null) {
            vo.setDeviceId(deviceId++);
        }

        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }

        vo.setDefaultNic(profile.isDefaultNic());

        vo.setIPv4Address(profile.getIPv4Address());
        vo.setAddressFormat(profile.getFormat());

        if (profile.getMacAddress() != null) {
            vo.setMacAddress(profile.getMacAddress());
        }

        vo.setMode(profile.getMode());
        vo.setIPv4Netmask(profile.getIPv4Netmask());
        vo.setIPv4Gateway(profile.getIPv4Gateway());

        if (profile.getBroadCastUri() != null) {
            vo.setBroadcastUri(profile.getBroadCastUri());
        }

        if (profile.getIsolationUri() != null) {
            vo.setIsolationUri(profile.getIsolationUri());
        }

        vo.setState(Nic.State.Allocated);

        vo.setIPv6Address(profile.getIPv6Address());
        vo.setIPv6Gateway(profile.getIPv6Gateway());
        vo.setIPv6Cidr(profile.getIPv6Cidr());

        return deviceId;
    }

    protected void applyProfileToNicForRelease(final NicVO vo, final NicProfile profile) {
        vo.setIPv4Gateway(profile.getIPv4Gateway());
        vo.setAddressFormat(profile.getFormat());
        vo.setIPv4Address(profile.getIPv4Address());
        vo.setIPv6Address(profile.getIPv6Address());
        vo.setMacAddress(profile.getMacAddress());
        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }
        vo.setBroadcastUri(profile.getBroadCastUri());
        vo.setIsolationUri(profile.getIsolationUri());
        vo.setIPv4Netmask(profile.getIPv4Netmask());
    }

    protected void applyProfileToNetwork(final NetworkVO network, final NetworkProfile profile) {
        network.setBroadcastUri(profile.getBroadcastUri());
        network.setDns1(profile.getDns1());
        network.setDns2(profile.getDns2());
        network.setPhysicalNetworkId(profile.getPhysicalNetworkId());
    }

    protected NicTO toNicTO(final NicVO nic, final NicProfile profile, final NetworkVO config) {
        final NicTO to = new NicTO();
        to.setDeviceId(nic.getDeviceId());
        to.setBroadcastType(config.getBroadcastDomainType());
        to.setType(config.getTrafficType());
        to.setIp(nic.getIPv4Address());
        to.setNetmask(nic.getIPv4Netmask());
        to.setMac(nic.getMacAddress());
        to.setDns1(profile.getIPv4Dns1());
        to.setDns2(profile.getIPv4Dns2());
        if (nic.getIPv4Gateway() != null) {
            to.setGateway(nic.getIPv4Gateway());
        } else {
            to.setGateway(config.getGateway());
        }
        if (nic.getVmType() != VirtualMachine.Type.User) {
            to.setPxeDisable(true);
        }
        to.setDefaultNic(nic.isDefaultNic());
        to.setBroadcastUri(nic.getBroadcastUri());
        to.setIsolationuri(nic.getIsolationUri());
        if (profile != null) {
            to.setDns1(profile.getIPv4Dns1());
            to.setDns2(profile.getIPv4Dns2());
        }

        final Integer networkRate = _networkModel.getNetworkRate(config.getId(), null);
        to.setNetworkRateMbps(networkRate);

        to.setUuid(config.getUuid());

        return to;
    }

    boolean isNetworkImplemented(final NetworkVO network) {
        final Network.State state = network.getState();
        final NetworkOfferingVO offeringVO = _networkOfferingDao.findById(network.getNetworkOfferingId());
        if (state == Network.State.Implemented) {
            return true;
        } else if (state == Network.State.Setup) {
            final DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
            if ((!isSharedNetworkOfferingWithServices(network.getNetworkOfferingId()) && !offeringVO.isPersistent()) || zone.getNetworkType() == NetworkType.Basic) {
                return true;
            }
        }
        return false;
    }

    Pair<NetworkGuru, NetworkVO> implementNetwork(final long networkId, final DeployDestination dest, final ReservationContext context, final boolean isRouter) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        Pair<NetworkGuru, NetworkVO> implemented = null;
        if (!isRouter) {
            implemented = implementNetwork(networkId, dest, context);
        } else {
            // At the time of implementing network (using implementNetwork() method), if the VR needs to be deployed then
            // it follows the same path of regular VM deployment. This leads to a nested call to implementNetwork() while
            // preparing VR nics. This flow creates issues in dealing with network state transitions. The original call
            // puts network in "Implementing" state and then the nested call again tries to put it into same state resulting
            // in issues. In order to avoid it, implementNetwork() call for VR is replaced with below code.
            final NetworkVO network = _networksDao.findById(networkId);
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            implemented = new Pair<NetworkGuru, NetworkVO>(guru, network);
        }
        return implemented;
    }

    /**
     * Creates a dummy NicTO object which is used by the respective hypervisors to setup network elements / resources
     * - bridges(KVM), VLANs(Xen) and portgroups(VMWare) for L2 network
     */
    private NicTO createNicTOFromNetworkAndOffering(NetworkVO networkVO, NetworkOfferingVO networkOfferingVO, HostVO hostVO) {
        NicTO to = new NicTO();
        to.setName(_networkModel.getNetworkTag(hostVO.getHypervisorType(), networkVO));
        to.setBroadcastType(networkVO.getBroadcastDomainType());
        to.setType(networkVO.getTrafficType());
        to.setBroadcastUri(networkVO.getBroadcastUri());
        to.setIsolationuri(networkVO.getBroadcastUri());
        to.setNetworkRateMbps(_configMgr.getNetworkOfferingNetworkRate(networkOfferingVO.getId(), networkVO.getDataCenterId()));
        to.setSecurityGroupEnabled(_networkModel.isSecurityGroupSupportedInNetwork(networkVO));
        return to;
    }

    private Pair<Boolean, NicTO> isNtwConfiguredInCluster(HostVO hostVO, Map<Long, List<Long>> clusterToHostsMap, NetworkVO networkVO, NetworkOfferingVO networkOfferingVO) {
        Long clusterId = hostVO.getClusterId();
        List<Long> hosts = clusterToHostsMap.get(clusterId);
        if (hosts == null) {
            hosts = new ArrayList<>();
        }
        if (hostVO.getHypervisorType() == HypervisorType.KVM || hostVO.getHypervisorType() == HypervisorType.XenServer) {
            hosts.add(hostVO.getId());
            clusterToHostsMap.put(clusterId, hosts);
            return new Pair<>(false, createNicTOFromNetworkAndOffering(networkVO, networkOfferingVO, hostVO));
        }
        if (hosts != null && !hosts.isEmpty()) {
            return new Pair<>(true, createNicTOFromNetworkAndOffering(networkVO, networkOfferingVO, hostVO));
        }
        hosts.add(hostVO.getId());
        clusterToHostsMap.put(clusterId, hosts);
        return new Pair<>(false, createNicTOFromNetworkAndOffering(networkVO, networkOfferingVO, hostVO));
    }

    private void setupPersistentNetwork(NetworkVO network, NetworkOfferingVO offering, Long dcId) throws AgentUnavailableException, OperationTimedoutException {
        List<ClusterVO> clusterVOs = clusterDao.listClustersByDcId(dcId);
        List<HostVO> hosts = resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, dcId);
        Map<Long, List<Long>> clusterToHostsMap = new HashMap<>();

        for (HostVO host : hosts) {
            try {
                Pair<Boolean, NicTO> networkCfgStateAndDetails = isNtwConfiguredInCluster(host, clusterToHostsMap, network, offering);
                if (networkCfgStateAndDetails.first()) {
                    continue;
                }
                NicTO to = networkCfgStateAndDetails.second();
                SetupPersistentNetworkCommand cmd = new SetupPersistentNetworkCommand(to);
                final SetupPersistentNetworkAnswer answer = (SetupPersistentNetworkAnswer) _agentMgr.send(host.getId(), cmd);

                if (answer == null) {
                    logger.warn("Unable to get an answer to the SetupPersistentNetworkCommand from agent: {}", host);
                    clusterToHostsMap.get(host.getClusterId()).remove(host.getId());
                    continue;
                }

                if (!answer.getResult()) {
                    logger.warn("Unable to setup agent {} due to {}", host, answer.getDetails());
                    clusterToHostsMap.get(host.getClusterId()).remove(host.getId());
                }
            } catch (Exception e) {
                logger.warn("Failed to connect to host: {}", host);
            }
        }
        if (clusterToHostsMap.keySet().size() != clusterVOs.size()) {
            logger.warn("Hosts on all clusters may not have been configured with network devices.");
        }
    }

    private boolean networkMeetsPersistenceCriteria(NetworkVO network, NetworkOfferingVO offering, boolean cleanup) {
        boolean criteriaMet = offering.isPersistent() &&
                (network.getBroadcastUri() != null && BroadcastDomainType.getSchemeValue(network.getBroadcastUri()) == BroadcastDomainType.Vlan);
        if (!cleanup) {
            return criteriaMet && network.getGuestType() == GuestType.L2;
        } else {
            return criteriaMet && (network.getGuestType() == GuestType.L2 || network.getGuestType() == GuestType.Isolated);
        }
    }

    @Override
    @DB
    public Pair<NetworkGuru, NetworkVO> implementNetwork(final long networkId, final DeployDestination dest, final ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        final Pair<NetworkGuru, NetworkVO> implemented = new Pair<NetworkGuru, NetworkVO>(null, null);

        NetworkVO network = _networksDao.findById(networkId);
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        if (isNetworkImplemented(network)) {
            logger.debug("Network {} is already implemented", network);
            implemented.set(guru, network);
            return implemented;
        }

        // Acquire lock only when network needs to be implemented
        network = _networksDao.acquireInLockTable(networkId, NetworkLockTimeout.value());
        if (network == null) {
            // see NetworkVO.java
            final ConcurrentOperationException ex = new ConcurrentOperationException("Unable to acquire network configuration");
            ex.addProxyObject(_entityMgr.findById(Network.class, networkId).getUuid());
            throw ex;
        }

        logger.debug("Lock is acquired for network id {} as a part of network implement", network);

        try {
            if (isNetworkImplemented(network)) {
                logger.debug("Network {} is already implemented", network);
                implemented.set(guru, network);
                return implemented;
            }

            logger.debug("Asking {} to implement {}", guru.getName(), network);

            final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

            network.setReservationId(context.getReservationId());
            if (isSharedNetworkWithServices(network)) {
                network.setState(Network.State.Implementing);
            } else {
                stateTransitTo(network, Event.ImplementNetwork);
            }

            final Network result = guru.implement(network, offering, dest, context);
            network.setCidr(result.getCidr());
            network.setBroadcastUri(result.getBroadcastUri());
            network.setGateway(result.getGateway());
            network.setMode(result.getMode());
            network.setPhysicalNetworkId(result.getPhysicalNetworkId());
            _networksDao.update(networkId, network);

            // implement network elements and re-apply all the network rules
            implementNetworkElementsAndResources(dest, context, network, offering);

            long dcId = dest.getDataCenter().getId();
            if (networkMeetsPersistenceCriteria(network, offering, false)) {
                setupPersistentNetwork(network, offering, dcId);
            }
            if (isSharedNetworkWithServices(network)) {
                network.setState(Network.State.Implemented);
            } else {
                stateTransitTo(network, Event.OperationSucceeded);
            }

            network.setRestartRequired(false);
            _networksDao.update(network.getId(), network);
            UsageEventUtils.publishNetworkUpdate(network);
            implemented.set(guru, network);
            return implemented;
        } catch (final NoTransitionException e) {
            logger.error(e.getMessage());
            return new Pair<NetworkGuru, NetworkVO>(null, null);
        } catch (final CloudRuntimeException | OperationTimedoutException e) {
            logger.error("Caught exception: {}", e.getMessage());
            return new Pair<NetworkGuru, NetworkVO>(null, null);
        } finally {
            if (implemented.first() == null) {
                logger.debug("Cleaning up because we're unable to implement the network {}", network);
                try {
                    if (isSharedNetworkWithServices(network)) {
                        network.setState(Network.State.Shutdown);
                        _networksDao.update(networkId, network);
                    } else {
                        stateTransitTo(network, Event.OperationFailed);
                    }
                } catch (final NoTransitionException e) {
                    logger.error(e.getMessage());
                }

                try {
                    shutdownNetwork(networkId, context, false);
                } catch (final Exception e) {
                    // Don't throw this exception as it would hide the original thrown exception, just log
                    logger.error("Exception caught while shutting down a network as part of a failed implementation", e);
                }
            }

            _networksDao.releaseFromLockTable(networkId);
            logger.debug("Lock is released for network {} as a part of network implement", network);
        }
    }

    @Override
    public void implementNetworkElementsAndResources(final DeployDestination dest, final ReservationContext context, final Network network, final NetworkOffering offering)
            throws ConcurrentOperationException, InsufficientAddressCapacityException, ResourceUnavailableException, InsufficientCapacityException {

        // Associate a source NAT IP (if one isn't already associated with the network) if this is a
        //     1) 'Isolated' or 'Shared' guest virtual network in the advance zone
        //     2) network has SourceNat or Gateway service
        //     3) network offering does not support a shared source NAT rule

        final boolean sharedSourceNat = offering.isSharedSourceNat();
        final DataCenter zone = _dcDao.findById(network.getDataCenterId());

        if (!sharedSourceNat
                && (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.SourceNat) || _networkModel.areServicesSupportedInNetwork(network.getId(), Service.Gateway))
                && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {

            List<IPAddressVO> ips = null;
            final Account owner = _entityMgr.findById(Account.class, network.getAccountId());
            if (network.getVpcId() != null) {
                ips = _ipAddressDao.listByAssociatedVpc(network.getVpcId(), true);
                if (ips.isEmpty()) {
                    final Vpc vpc = _vpcMgr.getActiveVpc(network.getVpcId());
                    logger.debug("Creating a source nat ip for vpc {}", vpc);
                    _vpcMgr.assignSourceNatIpAddressToVpc(owner, vpc, null);
                }
            } else {
                ips = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);
                if (ips.isEmpty()) {
                    logger.debug("Creating a source nat ip for network {}", network);
                    _ipAddrMgr.assignSourceNatIpAddressToGuestNetwork(owner, network);
                }
            }
        }
        // get providers to implement
        final List<Provider> providersToImplement = getNetworkProviders(network.getId());
        implementNetworkElements(dest, context, network, offering, providersToImplement);

        //Reset the extra DHCP option that may have been cleared per nic.
        List<NicVO> nicVOs = _nicDao.listByNetworkId(network.getId());
        for (NicVO nicVO : nicVOs) {
            if (nicVO.getState() == Nic.State.Reserved) {
                configureExtraDhcpOptions(network, nicVO.getId());
            }
        }

        for (final NetworkElement element : networkElements) {
            if (element instanceof AggregatedCommandExecutor && providersToImplement.contains(element.getProvider())) {
                ((AggregatedCommandExecutor) element).prepareAggregatedExecution(network, dest);
            }
        }

        try {
            // reapply all the firewall/staticNat/lb rules
            logger.debug("Reprogramming network {} as a part of network implement", network);
            if (!reprogramNetworkRules(network.getId(), CallContext.current().getCallingAccount(), network)) {
                logger.warn("Failed to re-program the network as a part of network {} implement", network);
                // see DataCenterVO.java
                final ResourceUnavailableException ex = new ResourceUnavailableException("Unable to apply network rules as a part of network " + network + " implement", DataCenter.class,
                        network.getDataCenterId());
                ex.addProxyObject(_entityMgr.findById(DataCenter.class, network.getDataCenterId()).getUuid());
                throw ex;
            }
            for (final NetworkElement element : networkElements) {
                if (element instanceof AggregatedCommandExecutor && providersToImplement.contains(element.getProvider())) {
                    if (!((AggregatedCommandExecutor) element).completeAggregatedExecution(network, dest)) {
                        logger.warn("Failed to re-program the network as a part of network {} implement due to aggregated commands execution failure!", network);
                        // see DataCenterVO.java
                        final ResourceUnavailableException ex = new ResourceUnavailableException("Unable to apply network rules as a part of network " + network + " implement", DataCenter.class,
                                network.getDataCenterId());
                        ex.addProxyObject(_entityMgr.findById(DataCenter.class, network.getDataCenterId()).getUuid());
                        throw ex;
                    }
                }
            }
            reconfigureAndApplyStaticRouteForVpcVpn(network);
        } finally {
            for (final NetworkElement element : networkElements) {
                if (element instanceof AggregatedCommandExecutor && providersToImplement.contains(element.getProvider())) {
                    ((AggregatedCommandExecutor) element).cleanupAggregatedExecution(network, dest);
                }
            }
        }
    }

    private void reconfigureAndApplyStaticRouteForVpcVpn(Network network) {
        if (network.getVpcId() != null) {
            _vpcMgr.reconfigStaticNatForVpcVr(network.getVpcId());
            try {
                _vpcMgr.applyStaticRouteForVpcVpnIfNeeded(network.getVpcId(), true);
            } catch (ResourceUnavailableException e) {
                logger.error("Unable to apply static routes for vpc " + network.getVpcId() + " due to " + e.getMessage());
            }
        }
    }

    private void implementNetworkElements(final DeployDestination dest, final ReservationContext context, final Network network, final NetworkOffering offering, final List<Provider> providersToImplement)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        for (NetworkElement element : networkElements) {
            if (providersToImplement.contains(element.getProvider())) {
                if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                    // The physicalNetworkId will not get translated into a uuid by the response serializer,
                    // because the serializer would look up the NetworkVO class's table and retrieve the
                    // network id instead of the physical network id.
                    // So just throw this exception as is. We may need to TBD by changing the serializer.
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                            + network.getPhysicalNetworkId());
                }

                logger.debug("Asking {} to implement {}", element.getName(), network);

                if (!element.implement(network, offering, dest, context)) {
                    CloudRuntimeException ex = new CloudRuntimeException("Failed to implement provider " + element.getProvider().getName() + " for network with specified id");
                    ex.addProxyObject(network.getUuid(), "networkId");
                    throw ex;
                }
            }
        }
    }

    // This method re-programs the rules/ips for existing network
    protected boolean reprogramNetworkRules(final long networkId, final Account caller, final Network network) throws ResourceUnavailableException {
        boolean success = true;

        //Apply egress rules first to effect the egress policy early on the guest traffic
        final List<FirewallRuleVO> firewallEgressRulesToApply = _firewallDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Egress);
        final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        final DataCenter zone = _dcDao.findById(network.getDataCenterId());
        if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall) && _networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall)
                && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {
            // add default egress rule to accept the traffic
            _firewallMgr.applyDefaultEgressFirewallRule(network.getId(), offering.isEgressDefaultPolicy(), true);
        }
        if (!_firewallMgr.applyFirewallRules(firewallEgressRulesToApply, false, caller)) {
            logger.warn("Failed to reapply firewall Egress rule(s) as a part of network {} restart", network);
            success = false;
        }

        // associate all ip addresses
        if (!_ipAddrMgr.applyIpAssociations(network, false)) {
            logger.warn("Failed to apply ip addresses as a part of network {} restart", network);
            success = false;
        }

        // apply BGP settings
        if (!bgpService.applyBgpPeers(network, false)) {
            logger.warn("Failed to apply bpg peers as a part of network {} restart", network);
            success = false;
        }


        // apply static nat
        if (!_rulesMgr.applyStaticNatsForNetwork(network, false, caller)) {
            logger.warn("Failed to apply static nats a part of network {} restart", network);
            success = false;
        }

        // apply firewall rules
        final List<FirewallRuleVO> firewallIngressRulesToApply = _firewallDao.listByNetworkPurposeTrafficType(networkId, Purpose.Firewall, FirewallRule.TrafficType.Ingress);
        if (!_firewallMgr.applyFirewallRules(firewallIngressRulesToApply, false, caller)) {
            logger.warn("Failed to reapply Ingress firewall rule(s) as a part of network {} restart", network);
            success = false;
        }

        // apply port forwarding rules
        if (!_rulesMgr.applyPortForwardingRulesForNetwork(networkId, false, caller)) {
            logger.warn("Failed to reapply port forwarding rule(s) as a part of network {} restart", network);
            success = false;
        }

        // apply static nat rules
        if (!_rulesMgr.applyStaticNatRulesForNetwork(networkId, false, caller)) {
            logger.warn("Failed to reapply static nat rule(s) as a part of network {} restart", network);
            success = false;
        }

        // apply public load balancer rules
        if (!_lbMgr.applyLoadBalancersForNetwork(network, Scheme.Public)) {
            logger.warn("Failed to reapply Public load balancer rules as a part of network {} restart", network);
            success = false;
        }

        // apply internal load balancer rules
        if (!_lbMgr.applyLoadBalancersForNetwork(network, Scheme.Internal)) {
            logger.warn("Failed to reapply internal load balancer rules as a part of network {} restart", network);
            success = false;
        }

        // apply vpn rules
        final List<? extends RemoteAccessVpn> vpnsToReapply = _vpnMgr.listRemoteAccessVpns(networkId);
        if (vpnsToReapply != null) {
            for (final RemoteAccessVpn vpn : vpnsToReapply) {
                // Start remote access vpn per ip
                if (_vpnMgr.startRemoteAccessVpn(vpn.getServerAddressId(), false) == null) {
                    logger.warn("Failed to reapply vpn rules as a part of network {} restart", network);
                    success = false;
                }
            }
        }

        //apply network ACLs
        if (!_networkACLMgr.applyACLToNetwork(networkId)) {
            logger.warn("Failed to reapply network ACLs as a part of  of network {}", network);
            success = false;
        }

        return success;
    }

    protected boolean prepareElement(final NetworkElement element, final Network network, final NicProfile profile, final VirtualMachineProfile vmProfile, final DeployDestination dest,
                                     final ReservationContext context) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        element.prepare(network, profile, vmProfile, dest, context);
        if (vmProfile.getType() == Type.User && element.getProvider() != null) {
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)
                    && _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, element.getProvider()) && element instanceof DhcpServiceProvider) {
                final DhcpServiceProvider sp = (DhcpServiceProvider) element;
                if (isDhcpAccrossMultipleSubnetsSupported(sp)) {
                    if (!sp.configDhcpSupportForSubnet(network, profile, vmProfile, dest, context)) {
                        return false;
                    }
                }
                if (!sp.addDhcpEntry(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dns)
                    && _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dns, element.getProvider()) && element instanceof DnsServiceProvider) {
                final DnsServiceProvider sp = (DnsServiceProvider) element;
                if (profile.getIPv6Address() == null) {
                    if (!sp.configDnsSupportForSubnet(network, profile, vmProfile, dest, context)) {
                        return false;
                    }
                }
                if (!sp.addDnsEntry(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.UserData)
                    && _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.UserData, element.getProvider()) && element instanceof UserDataServiceProvider) {
                final UserDataServiceProvider sp = (UserDataServiceProvider) element;
                if (!sp.addPasswordAndUserdata(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (element instanceof ConfigDriveNetworkElement && ((
                    _networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp) &&
                            _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, element.getProvider())
            ) || (
                    _networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dns) &&
                            _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dns, element.getProvider())
            ) || (
                    _networkModel.areServicesSupportedInNetwork(network.getId(), Service.UserData) &&
                            _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.UserData, element.getProvider())
            ))) {
                final ConfigDriveNetworkElement sp = (ConfigDriveNetworkElement) element;
                return sp.createConfigDriveIso(profile, vmProfile, dest, null);
            }
        }
        return true;
    }

    @Override
    public boolean canUpdateInSequence(Network network, boolean forced) {
        List<Provider> providers = getNetworkProviders(network.getId());

        //check if the there are no service provider other than virtualrouter.
        for (Provider provider : providers) {
            if (provider != Provider.VirtualRouter)
                throw new UnsupportedOperationException("Cannot update the network resources in sequence when providers other than virtualrouter are used");
        }
        //check if routers are in correct state before proceeding with the update
        List<DomainRouterVO> routers = routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER);
        for (DomainRouterVO router : routers){
            if (router.getRedundantState() == VirtualRouter.RedundantState.UNKNOWN) {
                if (!forced) {
                    throw new CloudRuntimeException("Domain router: " + router.getInstanceName() + " is in unknown state, Cannot update network. set parameter forced to true for forcing an update");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> getServicesNotSupportedInNewOffering(Network network, long newNetworkOfferingId) {
        NetworkOffering offering = _networkOfferingDao.findById(newNetworkOfferingId);
        List<String> services = _ntwkOfferingSrvcDao.listServicesForNetworkOffering(offering.getId());
        List<NetworkServiceMapVO> serviceMap = _ntwkSrvcDao.getServicesInNetwork(network.getId());
        List<String> servicesNotInNewOffering = new ArrayList<>();
        for (NetworkServiceMapVO serviceVO : serviceMap) {
            boolean inlist = false;
            for (String service : services) {
                if (serviceVO.getService().equalsIgnoreCase(service)) {
                    inlist = true;
                    break;
                }
            }
            if (!inlist) {
                //ignore Gateway service as this has no effect on the
                //behaviour of network.
                if (!serviceVO.getService().equalsIgnoreCase(Service.Gateway.getName()))
                    servicesNotInNewOffering.add(serviceVO.getService());
            }
        }
        return servicesNotInNewOffering;
    }

    @Override
    public void cleanupConfigForServicesInNetwork(List<String> services, final Network network) {
        long networkId = network.getId();
        Account caller = _accountDao.findById(Account.ACCOUNT_ID_SYSTEM);
        long userId = User.UID_SYSTEM;
        //remove all PF/Static Nat rules for the network
        logger.info("Services: {} are no longer supported in network: {} after applying new network offering: {} removing the related configuration",
                services::toString, network::toString, () -> _networkOfferingDao.findById(network.getNetworkOfferingId()));
        if (services.contains(Service.StaticNat.getName()) || services.contains(Service.PortForwarding.getName())) {
            try {
                if (_rulesMgr.revokeAllPFStaticNatRulesForNetwork(networkId, userId, caller)) {
                    logger.debug("Successfully cleaned up portForwarding/staticNat rules for network {}", network);
                } else {
                    logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup", network);
                }
                if (services.contains(Service.StaticNat.getName())) {
                    //removing static nat configured on ips.
                    //optimizing the db operations using transaction.
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(TransactionStatus status) {
                            List<IPAddressVO> ips = _ipAddressDao.listStaticNatPublicIps(network.getId());
                            for (IPAddressVO ip : ips) {
                                ip.setOneToOneNat(false);
                                ip.setAssociatedWithVmId(null);
                                ip.setVmIp(null);
                                ip.setForRouter(false);
                                _ipAddressDao.update(ip.getId(), ip);
                            }
                        }
                    });
                }
            } catch (ResourceUnavailableException ex) {
                logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
            }
        }
        if (services.contains(Service.SourceNat.getName())) {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    List<IPAddressVO> ips = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);
                    //removing static nat configured on ips.
                    for (IPAddressVO ip : ips) {
                        ip.setSourceNat(false);
                        _ipAddressDao.update(ip.getId(), ip);
                    }
                }
            });
        }
        if (services.contains(Service.Lb.getName())) {
            //remove all LB rules for the network
            if (_lbMgr.removeAllLoadBalanacersForNetwork(networkId, caller, userId)) {
                logger.debug("Successfully cleaned up load balancing rules for network {}", network);
            } else {
                logger.warn("Failed to cleanup LB rules as a part of network {} cleanup", network);
            }
        }

        if (services.contains(Service.Firewall.getName())) {
            //revoke all firewall rules for the network
            try {
                if (_firewallMgr.revokeAllFirewallRulesForNetwork(network, userId, caller)) {
                    logger.debug("Successfully cleaned up firewallRules rules for network {}", network);
                } else {
                    logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup", network);
                }
            } catch (ResourceUnavailableException ex) {
                logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
            }
        }

        //do not remove vpn service for vpc networks.
        if (services.contains(Service.Vpn.getName()) && network.getVpcId() == null) {
            RemoteAccessVpnVO vpn = _remoteAccessVpnDao.findByAccountAndNetwork(network.getAccountId(), networkId);
            try {
                _vpnMgr.destroyRemoteAccessVpnForIp(vpn.getServerAddressId(), caller, true);
            } catch (ResourceUnavailableException ex) {
                logger.warn("Failed to cleanup remote access vpn resources of network: {} due to Exception: {}", network, ex);
            }
        }
    }

    @Override
    public void configureUpdateInSequence(Network network) {
        List<Provider> providers = getNetworkProviders(network.getId());
        for (NetworkElement element : networkElements) {
            if (providers.contains(element.getProvider())) {
                if (element instanceof RedundantResource) {
                    ((RedundantResource) element).configureResource(network);
                }
            }
        }
    }

    @Override
    public int getResourceCount(Network network) {
        List<Provider> providers = getNetworkProviders(network.getId());
        int resourceCount = 0;
        for (NetworkElement element : networkElements) {
            if (providers.contains(element.getProvider())) {
                //currently only one element implements the redundant resource interface
                if (element instanceof RedundantResource) {
                    resourceCount = ((RedundantResource) element).getResourceCount(network);
                    break;
                }
            }
        }
        return resourceCount;
    }

    @Override
    public void configureExtraDhcpOptions(Network network, long nicId, Map<Integer, String> extraDhcpOptions) {
        if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)) {
            if (_networkModel.getNetworkServiceCapabilities(network.getId(), Service.Dhcp).containsKey(Capability.ExtraDhcpOptions)) {
                DhcpServiceProvider sp = getDhcpServiceProvider(network);
                sp.setExtraDhcpOptions(network, nicId, extraDhcpOptions);
            }
        }
    }

    @Override
    public void configureExtraDhcpOptions(Network network, long nicId) {
        Map<Integer, String> extraDhcpOptions = getExtraDhcpOptions(nicId);
        configureExtraDhcpOptions(network, nicId, extraDhcpOptions);
    }

    @Override
    public void finalizeUpdateInSequence(Network network, boolean success) {
        List<Provider> providers = getNetworkProviders(network.getId());
        for (NetworkElement element : networkElements) {
            if (providers.contains(element.getProvider())) {
                //currently only one element implements the redundant resource interface
                if (element instanceof RedundantResource) {
                    ((RedundantResource) element).finalize(network, success);
                    break;
                }
            }
        }
    }

    @Override
    public void setHypervisorHostname(VirtualMachineProfile vm, DeployDestination dest, boolean migrationSuccessful) throws ResourceUnavailableException {
        String hypervisorHostName = VirtualMachineManager.getHypervisorHostname(dest.getHost().getName());
        if (StringUtils.isNotEmpty(hypervisorHostName)) {
            final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
            for (final NicVO nic : nics) {
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());
                final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());
                final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                        _networkModel.getNetworkTag(vm.getHypervisorType(), network));
                setHypervisorHostnameInNetwork(vm, dest, network, profile, migrationSuccessful);
            }
        }
    }

    private void setHypervisorHostnameInNetwork(VirtualMachineProfile vm, DeployDestination dest, Network network, NicProfile profile, boolean migrationSuccessful) {
        for (final NetworkElement element : networkElements) {
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.UserData) && element instanceof UserDataServiceProvider
                && (element instanceof ConfigDriveNetworkElement && !migrationSuccessful || element instanceof VirtualRouterElement && migrationSuccessful)) {
                String errorMsg = String.format("Failed to add hypervisor host name while applying the userdata during the migration of VM %s, " +
                        "VM needs to stop and start to apply the userdata again", vm.getInstanceName());
                try {
                    final UserDataServiceProvider sp = (UserDataServiceProvider) element;
                    if (!sp.saveHypervisorHostname(profile, network, vm, dest)) {
                        logger.error(errorMsg);
                    }
                } catch (ResourceUnavailableException e) {
                    logger.error("{}, error states {}", errorMsg, e);
                }
            }
        }
    }

    @DB
    protected void updateNic(final NicVO nic, final Network network, final int count) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                _nicDao.update(nic.getId(), nic);

                if (nic.getVmType() == VirtualMachine.Type.User) {
                    logger.debug(String.format("Changing active number of nics for network id=%s on %d", network, count));
                    _networksDao.changeActiveNicsBy(network.getId(), count);
                }

                if (nic.getVmType() == VirtualMachine.Type.User
                        || nic.getVmType() == VirtualMachine.Type.DomainRouter && _networksDao.findById(network.getId()).getTrafficType() == TrafficType.Guest) {
                    _networksDao.setCheckForGc(network.getId());
                }
            }
        });
    }

    @Override
    public void prepare(final VirtualMachineProfile vmProfile, final DeployDestination dest, final ReservationContext context) throws InsufficientCapacityException, ConcurrentOperationException,
            ResourceUnavailableException {
        final List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());
        // we have to implement default nics first - to ensure that default network elements start up first in multiple
        //nics case
        // (need for setting DNS on Dhcp to domR's Ip4 address)
        Collections.sort(nics, new Comparator<NicVO>() {

            @Override
            public int compare(final NicVO nic1, final NicVO nic2) {
                final boolean isDefault1 = nic1.isDefaultNic();
                final boolean isDefault2 = nic2.isDefaultNic();

                return isDefault1 ^ isDefault2 ? isDefault1 ^ true ? 1 : -1 : 0;
            }
        });

        for (final NicVO nic : nics) {
            final Pair<NetworkGuru, NetworkVO> implemented = implementNetwork(nic.getNetworkId(), dest, context, vmProfile.getVirtualMachine().getType() == Type.DomainRouter);
            if (implemented == null || implemented.first() == null) {
                NetworkVO network = _networksDao.findById(nic.getNetworkId());
                logger.warn("Failed to implement network: {} as a part of preparing nic {}", network, nic);
                throw new CloudRuntimeException(String.format("Failed to implement network id=%s as a part preparing nic %s", network, nic));
            }

            final NetworkVO network = implemented.second();
            final NicProfile profile = prepareNic(vmProfile, dest, context, nic.getId(), network);
            if (vmProfile.getType() == Type.DomainRouter) {
                Pair<NetworkVO, VpcVO> networks = getGuestNetworkRouterAndVpcDetails(vmProfile.getId());
                setMtuInVRNicProfile(networks, network.getTrafficType(), profile);
            }
            vmProfile.addNic(profile);
        }
    }

    @Override
    public NicProfile prepareNic(final VirtualMachineProfile vmProfile, final DeployDestination dest, final ReservationContext context, final long nicId, final Network network)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException, InsufficientCapacityException,
            ResourceUnavailableException {

        final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vmProfile.getId());
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        final NicVO nic = _nicDao.findById(nicId);

        NicProfile profile = null;
        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
            nic.setState(Nic.State.Reserving);
            nic.setReservationId(context.getReservationId());
            _nicDao.update(nic.getId(), nic);
            URI broadcastUri = nic.getBroadcastUri();
            if (broadcastUri == null) {
                broadcastUri = network.getBroadcastUri();
            }

            final URI isolationUri = nic.getIsolationUri();

            profile = new NicProfile(nic, network, broadcastUri, isolationUri,

                    networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getHypervisorType(), network));
            guru.reserve(profile, network, vmProfile, dest, context);
            nic.setIPv4Address(profile.getIPv4Address());
            nic.setAddressFormat(profile.getFormat());
            nic.setIPv6Address(profile.getIPv6Address());
            nic.setIPv6Cidr(profile.getIPv6Cidr());
            nic.setIPv6Gateway(profile.getIPv6Gateway());
            nic.setMacAddress(profile.getMacAddress());
            nic.setIsolationUri(profile.getIsolationUri());
            nic.setBroadcastUri(profile.getBroadCastUri());
            nic.setReserver(guru.getName());
            nic.setState(Nic.State.Reserved);
            nic.setIPv4Netmask(profile.getIPv4Netmask());
            nic.setIPv4Gateway(profile.getIPv4Gateway());

            if (profile.getReservationStrategy() != null) {
                nic.setReservationStrategy(profile.getReservationStrategy());
            }
        } else {
            profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                    _networkModel.getNetworkTag(vmProfile.getHypervisorType(), network));
            guru.updateNicProfile(profile, network);
            nic.setState(Nic.State.Reserved);
        }

        if (vmProfile.getType() == Type.DomainRouter) {
            Pair<NetworkVO, VpcVO> networks = getGuestNetworkRouterAndVpcDetails(vmProfile.getId());
            setMtuDetailsInVRNic(networks, network, nic);
        }
        updateNic(nic, network, 1);

        final List<Provider> providersToImplement = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providersToImplement.contains(element.getProvider())) {
                if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                            + network.getPhysicalNetworkId());
                }
                logger.debug("Asking {} to prepare for {}", element.getName(), nic);
                if (!prepareElement(element, network, profile, vmProfile, dest, context)) {
                    throw new InsufficientAddressCapacityException("unable to configure the dhcp service, due to insufficiant address capacity", Network.class, network.getId());
                }
            }
        }

        profile.setSecurityGroupEnabled(_networkModel.isSecurityGroupSupportedInNetwork(network));
        guru.updateNicProfile(profile, network);
        updateRouterDefaultDns(vmProfile, profile);
        configureExtraDhcpOptions(network, nicId);
        return profile;
    }

    @Override
    public Map<Integer, String> getExtraDhcpOptions(long nicId) {
        List<NicExtraDhcpOptionVO> nicExtraDhcpOptionVOList = _nicExtraDhcpOptionDao.listByNicId(nicId);
        return nicExtraDhcpOptionVOList
                .stream()
                .collect(Collectors.toMap(NicExtraDhcpOptionVO::getCode, NicExtraDhcpOptionVO::getValue));
    }

    @Override
    public void prepareNicForMigration(final VirtualMachineProfile vm, final DeployDestination dest) {
        if (vm.getType().equals(VirtualMachine.Type.DomainRouter) && (vm.getHypervisorType().equals(HypervisorType.KVM) || vm.getHypervisorType().equals(HypervisorType.VMware))) {
            //Include nics hot plugged and not stored in DB
            prepareAllNicsForMigration(vm, dest);
            return;
        }
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        final ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), null, null);
        for (final NicVO nic : nics) {
            final NetworkVO network = _networksDao.findById(nic.getNetworkId());
            final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());

            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                    _networkModel.getNetworkTag(vm.getHypervisorType(), network));
            if (guru instanceof NetworkMigrationResponder) {
                if (!((NetworkMigrationResponder) guru).prepareMigration(profile, network, vm, dest, context)) {
                    logger.error("NetworkGuru {} prepareForMigration failed.", guru); // XXX: Transaction error
                }
            }

            if (network.getGuestType() == Network.GuestType.L2 && vm.getType() == VirtualMachine.Type.User) {
                _userVmMgr.setupVmForPvlan(false, vm.getVirtualMachine().getHostId(), profile);
            }

            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        if (!((NetworkMigrationResponder) element).prepareMigration(profile, network, vm, dest, context)) {
                            logger.error("NetworkElement {} prepareForMigration failed.", element); // XXX: Transaction error
                        }
                    }
                }
            }
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }
    }

    /*
    Prepare All Nics for migration including the nics dynamically created and not stored in DB
    This is a temporary workaround work KVM migration
    Once clean fix is added by stored dynamically nics is DB, this workaround won't be needed
     */
    @Override
    public void prepareAllNicsForMigration(final VirtualMachineProfile vm, final DeployDestination dest) {
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        final ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), null, null);
        Long guestNetworkId = null;
        for (final NicVO nic : nics) {
            final NetworkVO network = _networksDao.findById(nic.getNetworkId());
            if (network.getTrafficType().equals(TrafficType.Guest) && network.getGuestType().equals(GuestType.Isolated)) {
                guestNetworkId = network.getId();
            }
            final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());

            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                    _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vm.getHypervisorType(), network));
            if (guru instanceof NetworkMigrationResponder) {
                if (!((NetworkMigrationResponder) guru).prepareMigration(profile, network, vm, dest, context)) {
                    logger.error("NetworkGuru {} prepareForMigration failed.", guru); // XXX: Transaction error
                }
            }
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException(String.format("Service provider %s either doesn't exist or is not enabled in physical network: %s", element.getProvider().getName(), _physicalNetworkDao.findById(network.getPhysicalNetworkId())));
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        if (!((NetworkMigrationResponder) element).prepareMigration(profile, network, vm, dest, context)) {
                            logger.error("NetworkElement {} prepareForMigration failed.", element); // XXX: Transaction error
                        }
                    }
                }
            }
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }

        final List<String> addedURIs = new ArrayList<String>();
        if (guestNetworkId != null) {
            final List<IPAddressVO> publicIps = _ipAddressDao.listByAssociatedNetwork(guestNetworkId, null);
            for (final IPAddressVO userIp : publicIps) {
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                final URI broadcastUri = BroadcastDomainType.Vlan.toUri(publicIp.getVlanTag());
                final long ntwkId = publicIp.getNetworkId();
                final Nic nic = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(ntwkId, vm.getId(),
                        broadcastUri.toString());
                if (nic == null && !addedURIs.contains(broadcastUri.toString())) {
                    //Nic details are not available in DB
                    //Create nic profile for migration
                    final NetworkVO network = _networksDao.findById(ntwkId);
                    final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                    final NicProfile profile = new NicProfile();
                    logger.debug("Creating nic profile for migration. BroadcastUri: {} NetworkId: {} VM: {}", broadcastUri.toString(), network, vm);
                    profile.setDeviceId(255); //dummyId
                    profile.setIPv4Address(userIp.getAddress().toString());
                    profile.setIPv4Netmask(publicIp.getNetmask());
                    profile.setIPv4Gateway(publicIp.getGateway());
                    profile.setMacAddress(publicIp.getMacAddress());
                    profile.setBroadcastType(network.getBroadcastDomainType());
                    profile.setTrafficType(network.getTrafficType());
                    profile.setBroadcastUri(broadcastUri);
                    profile.setIsolationUri(Networks.IsolationType.Vlan.toUri(publicIp.getVlanTag()));
                    profile.setSecurityGroupEnabled(_networkModel.isSecurityGroupSupportedInNetwork(network));
                    profile.setName(_networkModel.getNetworkTag(vm.getHypervisorType(), network));
                    profile.setNetworkRate(_networkModel.getNetworkRate(network.getId(), vm.getId()));
                    profile.setNetworkId(network.getId());

                    guru.updateNicProfile(profile, network);
                    vm.addNic(profile);
                    addedURIs.add(broadcastUri.toString());
                }
            }
        }
    }

    private NicProfile findNicProfileById(final VirtualMachineProfile vm, final long id) {
        for (final NicProfile nic : vm.getNics()) {
            if (nic.getId() == id) {
                return nic;
            }
        }
        return null;
    }

    @Override
    public void commitNicForMigration(final VirtualMachineProfile src, final VirtualMachineProfile dst) {
        for (final NicProfile nicSrc : src.getNics()) {
            final NetworkVO network = _networksDao.findById(nicSrc.getNetworkId());
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile nicDst = findNicProfileById(dst, nicSrc.getId());
            final ReservationContext src_context = new ReservationContextImpl(nicSrc.getReservationId(), null, null);
            final ReservationContext dst_context = new ReservationContextImpl(nicDst.getReservationId(), null, null);

            if (guru instanceof NetworkMigrationResponder) {
                ((NetworkMigrationResponder) guru).commitMigration(nicSrc, network, src, src_context, dst_context);
            }

            if (network.getGuestType() == Network.GuestType.L2 && src.getType() == VirtualMachine.Type.User) {
                _userVmMgr.setupVmForPvlan(true, src.getVirtualMachine().getHostId(), nicSrc);
            }

            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        ((NetworkMigrationResponder) element).commitMigration(nicSrc, network, src, src_context, dst_context);
                    }
                }
            }
            // update the reservation id
            final NicVO nicVo = _nicDao.findById(nicDst.getId());
            nicVo.setReservationId(nicDst.getReservationId());
            _nicDao.persist(nicVo);
        }
    }

    @Override
    public void rollbackNicForMigration(final VirtualMachineProfile src, final VirtualMachineProfile dst) {
        for (final NicProfile nicDst : dst.getNics()) {
            final NetworkVO network = _networksDao.findById(nicDst.getNetworkId());
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile nicSrc = findNicProfileById(src, nicDst.getId());
            final ReservationContext src_context = new ReservationContextImpl(nicSrc.getReservationId(), null, null);
            final ReservationContext dst_context = new ReservationContextImpl(nicDst.getReservationId(), null, null);

            if (guru instanceof NetworkMigrationResponder) {
                ((NetworkMigrationResponder) guru).rollbackMigration(nicDst, network, dst, src_context, dst_context);
            }

            if (network.getGuestType() == Network.GuestType.L2 && src.getType() == VirtualMachine.Type.User) {
                _userVmMgr.setupVmForPvlan(true, dst.getVirtualMachine().getHostId(), nicDst);
            }

            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        ((NetworkMigrationResponder) element).rollbackMigration(nicDst, network, dst, src_context, dst_context);
                    }
                }
            }
        }
    }

    @Override
    @DB
    public void release(final VirtualMachineProfile vmProfile, final boolean forced) throws ConcurrentOperationException, ResourceUnavailableException {
        final List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());
        for (final NicVO nic : nics) {
            releaseNic(vmProfile, nic.getId());
        }
    }

    @Override
    @DB
    public void releaseNic(final VirtualMachineProfile vmProfile, final Nic nic) throws ConcurrentOperationException, ResourceUnavailableException {
        releaseNic(vmProfile, nic.getId());
    }

    @DB
    protected void releaseNic(final VirtualMachineProfile vmProfile, final long nicId) throws ConcurrentOperationException, ResourceUnavailableException {
        final Pair<Network, NicProfile> networkToRelease = Transaction.execute(new TransactionCallback<>() {
            @Override
            public Pair<Network, NicProfile> doInTransaction(final TransactionStatus status) {
                final NicVO nic = _nicDao.lockRow(nicId, true);
                if (nic == null) {
                    throw new ConcurrentOperationException(String.format("Unable to acquire lock on nic id=%d", nicId));
                }

                final Nic.State originalState = nic.getState();
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());

                if (originalState == Nic.State.Reserved || originalState == Nic.State.Reserving) {
                    if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
                        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                        nic.setState(Nic.State.Releasing);
                        _nicDao.update(nic.getId(), nic);
                        final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null, _networkModel
                                .isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getHypervisorType(), network));
                        if (guru.release(profile, vmProfile, nic.getReservationId())) {
                            logger.debug("The nic {} on {} was released according to {} by guru {}, now updating record.", nic, profile, vmProfile, guru);
                            applyProfileToNicForRelease(nic, profile);
                            nic.setState(Nic.State.Allocated);
                            if (originalState == Nic.State.Reserved) {
                                updateNic(nic, network, -1);
                            } else {
                                _nicDao.update(nic.getId(), nic);
                            }
                        }
                        // Perform release on network elements
                        return new Pair<>(network, profile);
                    } else {
                        nic.setState(Nic.State.Allocated);
                        updateNic(nic, network, -1);
                    }
                }

                return null;
            }
        });

        // cleanup the entry in vm_network_map
        if (vmProfile.getType().equals(VirtualMachine.Type.User)) {
            final NicVO nic = _nicDao.findById(nicId);
            if (nic != null) {
                final NetworkVO vmNetwork = _networksDao.findById(nic.getNetworkId());
                final VMNetworkMapVO vno = _vmNetworkMapDao.findByVmAndNetworkId(vmProfile.getVirtualMachine().getId(), vmNetwork.getId());
                if (vno != null) {
                    _vmNetworkMapDao.remove(vno.getId());
                }
            }
        }

        if (networkToRelease != null) {
            final Network network = networkToRelease.first();
            final NicProfile profile = networkToRelease.second();
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    logger.debug("Asking {} to release {}", element.getName(), profile);
                    //NOTE: Context appear to never be used in release method
                    //implementations. Consider removing it from interface Element
                    element.release(network, profile, vmProfile, null);
                }
            }
        }
    }

    @Override
    public void cleanupNics(final VirtualMachineProfile vm) {
        logger.debug("Cleaning network for vm: {}", vm);

        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            removeNic(vm, nic);
        }
    }

    @Override
    public void removeNic(final VirtualMachineProfile vm, final Nic nic) {
        removeNic(vm, _nicDao.findById(nic.getId()));
    }

    protected void removeNic(final VirtualMachineProfile vm, final NicVO nic) {

        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start && nic.getState() != Nic.State.Allocated) {
            // Nics with reservation strategy 'Start' should go through release phase in the Nic life cycle.
            // Ensure that release is performed before Nic is to be removed to avoid resource leaks.
            try {
                releaseNic(vm, nic.getId());
            } catch (final Exception ex) {
                logger.warn("Failed to release nic: {} as part of remove operation due to", nic.toString(), ex);
            }
        }

        final NetworkVO network = _networksDao.findById(nic.getNetworkId());
        if (network != null && network.getTrafficType() == TrafficType.Guest) {
            final String nicIp = StringUtils.isEmpty(nic.getIPv4Address()) ? nic.getIPv6Address() : nic.getIPv4Address();
            if (StringUtils.isNotEmpty(nicIp)) {
                NicProfile nicProfile = new NicProfile(nic.getIPv4Address(), nic.getIPv6Address(), nic.getMacAddress());
                nicProfile.setId(nic.getId());
                cleanupNicDhcpDnsEntry(network, vm, nicProfile);
            }
        }

        Boolean preserveNics = (Boolean) vm.getParameter(VirtualMachineProfile.Param.PreserveNics);
        if (BooleanUtils.isNotTrue(preserveNics)) {
            nic.setState(Nic.State.Deallocating);
            _nicDao.update(nic.getId(), nic);
        }

        final NicProfile profile = new NicProfile(nic, network, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(
                vm.getHypervisorType(), network));

        /*
         * We need to release the nics with a Create ReservationStrategy here
         * because the nic is now being removed.
         */
        if (nic.getReservationStrategy() == Nic.ReservationStrategy.Create) {
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    logger.debug("Asking {} to release {}, according to the reservation strategy {}.", element.getName(), nic, nic.getReservationStrategy());
                    try {
                        element.release(network, profile, vm, null);
                    } catch (final ConcurrentOperationException ex) {
                        logger.warn("release failed during the nic " + nic.toString() + " removeNic due to ", ex);
                    } catch (final ResourceUnavailableException ex) {
                        logger.warn("release failed during the nic " + nic.toString() + " removeNic due to ", ex);
                    }
                }
            }
        }

        if (vm.getType() == Type.User
                && network.getTrafficType() == TrafficType.Guest
                && network.getGuestType() == GuestType.Shared
                && isLastNicInSubnet(nic)) {
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)) {
                // remove the dhcpservice ip if this is the last nic in subnet.
                final DhcpServiceProvider dhcpServiceProvider = getDhcpServiceProvider(network);
                if (dhcpServiceProvider != null
                        && isDhcpAccrossMultipleSubnetsSupported(dhcpServiceProvider)) {
                    removeDhcpServiceInSubnet(nic);
                }
            }
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dns)) {
                final DnsServiceProvider dnsServiceProvider = getDnsServiceProvider(network);
                if (dnsServiceProvider != null) {
                    try {
                        if (!dnsServiceProvider.removeDnsSupportForSubnet(network)) {
                            logger.warn("Failed to remove the ip alias on the dns server");
                        }
                    } catch (final ResourceUnavailableException e) {
                        //failed to remove the dnsconfig.
                        logger.info("Unable to delete the ip alias due to unable to contact the dns server.");
                    }
                }
            }
        }

        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        guru.deallocate(network, profile, vm);
        if (BooleanUtils.isNotTrue(preserveNics)) {
            _nicDao.remove(nic.getId());
        }

        logger.debug("Removed nic {}", nic);
        // release assigned IPv6 for Isolated Network VR NIC

        if (Type.User.equals(vm.getType()) && GuestType.Isolated.equals(network.getGuestType())
                && _networkOfferingDao.isIpv6Supported(network.getNetworkOfferingId()) && StringUtils.isNotEmpty(nic.getIPv6Address())) {
            final boolean usageHidden = networkDetailsDao.isNetworkUsageHidden(network.getId());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NET_IP6_RELEASE, network.getAccountId(), network.getDataCenterId(), 0L,
                    nic.getIPv6Address(), false, Vlan.VlanType.VirtualNetwork.toString(), false, usageHidden,
                    IPv6Address.class.getName(), null);
        }

        //remove the secondary ip addresses corresponding to this nic
        if (!removeVmSecondaryIpsOfNic(nic.getId())) {
            logger.debug("Removing nic {} secondary ip addresses failed", nic);
        }
    }

    public boolean isDhcpAccrossMultipleSubnetsSupported(final DhcpServiceProvider dhcpServiceProvider) {

        final Map<Network.Capability, String> capabilities = dhcpServiceProvider.getCapabilities().get(Network.Service.Dhcp);
        final String supportsMultipleSubnets = capabilities.get(Network.Capability.DhcpAccrossMultipleSubnets);
        if (supportsMultipleSubnets != null && Boolean.valueOf(supportsMultipleSubnets)) {
            return true;
        }
        return false;
    }

    private boolean isLastNicInSubnet(final NicVO nic) {
        if (_nicDao.listByNetworkIdTypeAndGatewayAndBroadcastUri(nic.getNetworkId(), VirtualMachine.Type.User, nic.getIPv4Gateway(), nic.getBroadcastUri()).size() > 1) {
            return false;
        }
        return true;
    }

    @DB
    @Override
    public void removeDhcpServiceInSubnet(final Nic nic) {
        final Network network = _networksDao.findById(nic.getNetworkId());
        final DhcpServiceProvider dhcpServiceProvider = getDhcpServiceProvider(network);
        try {
            final NicIpAliasVO ipAlias = _nicIpAliasDao.findByGatewayAndNetworkIdAndState(nic.getIPv4Gateway(), network.getId(), NicIpAlias.State.active);
            if (ipAlias != null) {
                ipAlias.setState(NicIpAlias.State.revoked);
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        _nicIpAliasDao.update(ipAlias.getId(), ipAlias);
                        final IPAddressVO aliasIpaddressVo = _publicIpAddressDao.findByIpAndSourceNetworkId(ipAlias.getNetworkId(), ipAlias.getIp4Address());
                        _publicIpAddressDao.unassignIpAddress(aliasIpaddressVo.getId());
                    }
                });
                if (!dhcpServiceProvider.removeDhcpSupportForSubnet(network)) {
                    logger.warn("Failed to remove the ip alias on the router, marking it as removed in db and freed the allocated ip {}", ipAlias.getIp4Address());
                }
            }
        } catch (final ResourceUnavailableException e) {
            //failed to remove the dhcpconfig on the router.
            logger.info("Unable to delete the ip alias due to unable to contact the virtualrouter.");
        }

    }

    @Override
    public void removeNics(final VirtualMachineProfile vm) {
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            _nicDao.remove(nic.getId());
        }
    }

    @Override
    @DB
    public Network createPrivateNetwork(final long networkOfferingId, final String name, final String displayText, final String gateway, final String cidr, final String vlanId, final boolean bypassVlanOverlapCheck, final Account owner, final PhysicalNetwork pNtwk, final Long vpcId) throws ConcurrentOperationException, InsufficientCapacityException, ResourceAllocationException {
        // create network for private gateway
        return createGuestNetwork(networkOfferingId, name, displayText, gateway, cidr, vlanId,
                bypassVlanOverlapCheck, null, owner, null, pNtwk, pNtwk.getDataCenterId(), ACLType.Account, null,
                vpcId, null, null, true, null, null, null, true, null, null,
                null, null, null, null, null, null);
    }

    @Override
    @DB
    public Network createGuestNetwork(final long networkOfferingId, final String name, final String displayText, final String gateway, final String cidr, String vlanId,
                                      boolean bypassVlanOverlapCheck, String networkDomain, final Account owner, final Long domainId, final PhysicalNetwork pNtwk,
                                      final long zoneId, final ACLType aclType, Boolean subdomainAccess, final Long vpcId, final String ip6Gateway, final String ip6Cidr,
                                      final Boolean isDisplayNetworkEnabled, final String isolatedPvlan, Network.PVlanType isolatedPvlanType, String externalId,
                                      String routerIp, String routerIpv6, String ip4Dns1, String ip4Dns2, String ip6Dns1, String ip6Dns2,
                                      Pair<Integer, Integer> vrIfaceMTUs, Integer networkCidrSize) throws ConcurrentOperationException, InsufficientCapacityException, ResourceAllocationException {
        // create Isolated/Shared/L2 network
        return createGuestNetwork(networkOfferingId, name, displayText, gateway, cidr, vlanId, bypassVlanOverlapCheck,
                networkDomain, owner, domainId, pNtwk, zoneId, aclType, subdomainAccess, vpcId, ip6Gateway, ip6Cidr,
                isDisplayNetworkEnabled, isolatedPvlan, isolatedPvlanType, externalId, false, routerIp, routerIpv6, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2, vrIfaceMTUs, networkCidrSize);
    }

    @DB
    private Network createGuestNetwork(final long networkOfferingId, final String name, final String displayText, final String gateway, final String cidr, String vlanId,
                                       boolean bypassVlanOverlapCheck, String networkDomain, final Account owner, final Long domainId, final PhysicalNetwork pNtwk,
                                       final long zoneId, final ACLType aclType, Boolean subdomainAccess, final Long vpcId, final String ip6Gateway, final String ip6Cidr,
                                       final Boolean isDisplayNetworkEnabled, final String isolatedPvlan, Network.PVlanType isolatedPvlanType, String externalId,
                                       final Boolean isPrivateNetwork, String routerIp, String routerIpv6, final String ip4Dns1, final String ip4Dns2,
                                       final String ip6Dns1, final String ip6Dns2, Pair<Integer, Integer> vrIfaceMTUs, Integer networkCidrSize) throws ConcurrentOperationException, InsufficientCapacityException, ResourceAllocationException {

        final NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        final DataCenterVO zone = _dcDao.findById(zoneId);
        // this method supports only guest network creation
        if (ntwkOff.getTrafficType() != TrafficType.Guest) {
            logger.warn("Only guest networks can be created using this method");
            return null;
        }

        final boolean updateResourceCount = resourceCountNeedsUpdate(ntwkOff, aclType);
        //check resource limits
        if (updateResourceCount) {
            _resourceLimitMgr.checkResourceLimit(owner, ResourceType.network, isDisplayNetworkEnabled);
        }

        // Validate network offering
        if (ntwkOff.getState() != NetworkOffering.State.Enabled) {
            // see NetworkOfferingVO
            final InvalidParameterValueException ex = new InvalidParameterValueException("Can't use specified network offering id as its state is not " + NetworkOffering.State.Enabled);
            ex.addProxyObject(ntwkOff.getUuid(), "networkOfferingId");
            throw ex;
        }

        // Validate physical network
        if (pNtwk.getState() != PhysicalNetwork.State.Enabled) {
            // see PhysicalNetworkVO.java
            final InvalidParameterValueException ex = new InvalidParameterValueException("Specified physical network id is" + " in incorrect state:" + pNtwk.getState());
            ex.addProxyObject(pNtwk.getUuid(), "physicalNetworkId");
            throw ex;
        }

        boolean ipv6 = false;

        if (StringUtils.isNoneBlank(ip6Gateway, ip6Cidr)) {
            ipv6 = true;
        }
        // Validate zone
        if (zone.getNetworkType() == NetworkType.Basic) {
            // In Basic zone the network should have aclType=Domain, domainId=1, subdomainAccess=true
            if (aclType == null || aclType != ACLType.Domain) {
                throw new InvalidParameterValueException("Only AclType=Domain can be specified for network creation in Basic zone");
            }

            // Only one guest network is supported in Basic zone
            final List<NetworkVO> guestNetworks = _networksDao.listByZoneAndTrafficType(zone.getId(), TrafficType.Guest);
            if (!guestNetworks.isEmpty()) {
                throw new InvalidParameterValueException("Can't have more than one Guest network in zone with network type " + NetworkType.Basic);
            }

            // if zone is basic, only Shared network offerings w/o source nat service are allowed
            if (!(ntwkOff.getGuestType() == GuestType.Shared && !_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat))) {
                throw new InvalidParameterValueException("For zone of type " + NetworkType.Basic + " only offerings of " + "guestType " + GuestType.Shared + " with disabled "
                        + Service.SourceNat.getName() + " service are allowed");
            }

            if (domainId == null || domainId != Domain.ROOT_DOMAIN) {
                throw new InvalidParameterValueException("Guest network in Basic zone should be dedicated to ROOT domain");
            }

            if (subdomainAccess == null) {
                subdomainAccess = true;
            } else if (!subdomainAccess) {
                throw new InvalidParameterValueException("Subdomain access should be set to true for the" + " guest network in the Basic zone");
            }

            if (vlanId == null) {
                vlanId = Vlan.UNTAGGED;
            } else {
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    throw new InvalidParameterValueException("Only vlan " + Vlan.UNTAGGED + " can be created in " + "the zone of type " + NetworkType.Basic);
                }
            }

        } else if (zone.getNetworkType() == NetworkType.Advanced) {
            if (zone.isSecurityGroupEnabled()) {
                if (isolatedPvlan != null) {
                    throw new InvalidParameterValueException("Isolated Private VLAN is not supported with security group!");
                }
                // Only Account specific Isolated network with sourceNat service disabled are allowed in security group
                // enabled zone
                if ((ntwkOff.getGuestType() != GuestType.Shared) && (ntwkOff.getGuestType() != GuestType.L2)) {
                    throw new InvalidParameterValueException("Only shared or L2 guest network can be created in security group enabled zone");
                }
                if (_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat)) {
                    throw new InvalidParameterValueException("Service SourceNat is not allowed in security group enabled zone");
                }
            }

            //don't allow eip/elb networks in Advance zone
            if (ntwkOff.isElasticIp() || ntwkOff.isElasticLb()) {
                throw new InvalidParameterValueException("Elastic IP and Elastic LB services are supported in zone of type " + NetworkType.Basic);
            }
        }

        if (ipv6 && !GuestType.Shared.equals(ntwkOff.getGuestType())) {
            _networkModel.checkIp6CidrSizeEqualTo64(ip6Cidr);
        }

        //TODO(VXLAN): Support VNI specified
        // VlanId can be specified only when network offering supports it
        final boolean vlanSpecified = vlanId != null;
        if (vlanSpecified != ntwkOff.isSpecifyVlan()) {
            if (vlanSpecified) {
                if (!isSharedNetworkWithoutSpecifyVlan(ntwkOff) && !isPrivateGatewayWithoutSpecifyVlan(ntwkOff)) {
                    throw new InvalidParameterValueException("Can't specify vlan; corresponding offering says specifyVlan=false");
                }
            } else {
                throw new InvalidParameterValueException("Vlan has to be specified; corresponding offering says specifyVlan=true");
            }
        }

        if (vlanSpecified) {
            URI uri = encodeVlanIdIntoBroadcastUri(vlanId, pNtwk);
            // Aux: generate secondary URI for secondary VLAN ID (if provided) for performing checks
            URI secondaryUri = StringUtils.isNotBlank(isolatedPvlan) ? BroadcastDomainType.fromString(isolatedPvlan) : null;
            if (isSharedNetworkWithoutSpecifyVlan(ntwkOff) || isPrivateGatewayWithoutSpecifyVlan(ntwkOff)) {
                bypassVlanOverlapCheck = true;
            }
            //don't allow to specify vlan tag used by physical network for dynamic vlan allocation
            if (!(bypassVlanOverlapCheck && (ntwkOff.getGuestType() == GuestType.Shared || isPrivateNetwork))
                    && _dcDao.findVnet(zoneId, pNtwk.getId(), BroadcastDomainType.getValue(uri)).size() > 0) {
                throw new InvalidParameterValueException("The VLAN tag to use for new guest network, " + vlanId + " is already being used for dynamic vlan allocation for the guest network in zone "
                        + zone.getName());
            }
            if (secondaryUri != null && !(bypassVlanOverlapCheck && ntwkOff.getGuestType() == GuestType.Shared) &&
                    _dcDao.findVnet(zoneId, pNtwk.getId(), BroadcastDomainType.getValue(secondaryUri)).size() > 0) {
                throw new InvalidParameterValueException(String.format(
                        "The VLAN tag for isolated PVLAN %s is already being used for dynamic vlan allocation for the guest network in zone %s",
                        isolatedPvlan, zone));
            }
            if (!UuidUtils.isUuid(vlanId)) {
                // For Isolated and L2 networks, don't allow to create network with vlan that already exists in the zone
                if (!hasGuestBypassVlanOverlapCheck(bypassVlanOverlapCheck, ntwkOff, isPrivateNetwork)) {
                    if (_networksDao.listByZoneAndUriAndGuestType(zoneId, uri.toString(), null).size() > 0) {
                        throw new InvalidParameterValueException(String.format(
                                "Network with vlan %s already exists or overlaps with other network vlans in zone %s",
                                vlanId, zone));
                    } else if (secondaryUri != null && _networksDao.listByZoneAndUriAndGuestType(zoneId, secondaryUri.toString(), null).size() > 0) {
                        throw new InvalidParameterValueException(String.format(
                                "Network with vlan %s already exists or overlaps with other network vlans in zone %s",
                                isolatedPvlan, zone));
                    } else {
                        final List<DataCenterVnetVO> dcVnets = _datacenterVnetDao.findVnet(zoneId, BroadcastDomainType.getValue(uri));
                        //for the network that is created as part of private gateway,
                        //the vnet is not coming from the data center vnet table, so the list can be empty
                        if (!dcVnets.isEmpty()) {
                            final DataCenterVnetVO dcVnet = dcVnets.get(0);
                            // Fail network creation if specified vlan is dedicated to a different account
                            if (dcVnet.getAccountGuestVlanMapId() != null) {
                                final Long accountGuestVlanMapId = dcVnet.getAccountGuestVlanMapId();
                                final AccountGuestVlanMapVO map = _accountGuestVlanMapDao.findById(accountGuestVlanMapId);
                                if (map.getAccountId() != owner.getAccountId()) {
                                    throw new InvalidParameterValueException("Vlan " + vlanId + " is dedicated to a different account");
                                }
                                // Fail network creation if owner has a dedicated range of vlans but the specified vlan belongs to the system pool
                            } else {
                                final List<AccountGuestVlanMapVO> maps = _accountGuestVlanMapDao.listAccountGuestVlanMapsByAccount(owner.getAccountId());
                                if (maps != null && !maps.isEmpty()) {
                                    final int vnetsAllocatedToAccount = _datacenterVnetDao.countVnetsAllocatedToAccount(zoneId, owner.getAccountId());
                                    final int vnetsDedicatedToAccount = _datacenterVnetDao.countVnetsDedicatedToAccount(zoneId, owner.getAccountId());
                                    if (vnetsAllocatedToAccount < vnetsDedicatedToAccount) {
                                        throw new InvalidParameterValueException("Specified vlan " + vlanId + " doesn't belong" + " to the vlan range dedicated to the owner "
                                                + owner.getAccountName());
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // don't allow to creating shared network with given Vlan ID, if there already exists a isolated network or
                    // shared network with same Vlan ID in the zone
                    if (!bypassVlanOverlapCheck && _networksDao.listByZoneAndUriAndGuestType(zoneId, uri.toString(), GuestType.Isolated).size() > 0) {
                        throw new InvalidParameterValueException(String.format(
                                "There is an existing isolated/shared network that overlaps with vlan id:%s in zone %s", vlanId, zone));
                    }
                }
            }

        }

        // If networkDomain is not specified, take it from the global configuration
        if (_networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Dns)) {
            final Map<Network.Capability, String> dnsCapabilities = _networkModel.getNetworkOfferingServiceCapabilities(_entityMgr.findById(NetworkOffering.class, networkOfferingId),
                    Service.Dns);
            final String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                if (networkDomain != null) {
                    // TBD: NetworkOfferingId and zoneId. Send uuids instead.
                    throw new InvalidParameterValueException(String.format(
                            "Domain name change is not supported by network offering id=%d in zone %s",
                            networkOfferingId, zone));
                }
            } else {
                if (networkDomain == null) {
                    // 1) Get networkDomain from the corresponding account/domain/zone
                    if (aclType == ACLType.Domain) {
                        networkDomain = _networkModel.getDomainNetworkDomain(domainId, zoneId);
                    } else if (aclType == ACLType.Account) {
                        networkDomain = _networkModel.getAccountNetworkDomain(owner.getId(), zoneId);
                    }

                    // 2) If null, generate networkDomain using domain suffix from the global config variables
                    if (networkDomain == null) {
                        networkDomain = "cs" + Long.toHexString(owner.getId()) + GuestDomainSuffix.valueIn(zoneId);
                    }

                } else {
                    // validate network domain
                    if (!NetUtils.verifyDomainName(networkDomain)) {
                        throw new InvalidParameterValueException("Invalid network domain. Total length shouldn't exceed 190 chars. Each domain "
                                + "label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
                    }
                }
            }
        }

        // In Advance zone Cidr for Shared networks and Isolated networks w/o source nat service can't be NULL - 2.2.x
        // limitation, remove after we introduce support for multiple ip ranges
        // with different Cidrs for the same Shared network
        final boolean cidrRequired = zone.getNetworkType() == NetworkType.Advanced
                && ntwkOff.getTrafficType() == TrafficType.Guest
                && (ntwkOff.getGuestType() == GuestType.Shared || (ntwkOff.getGuestType() == GuestType.Isolated
                && !_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat)
                && !_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.Gateway)));
        if (cidr == null && ip6Cidr == null && cidrRequired) {
            if (ntwkOff.getGuestType() == GuestType.Shared) {
                throw new InvalidParameterValueException(String.format("Gateway/netmask are required when creating %s networks.", Network.GuestType.Shared));
            } else {
                throw new InvalidParameterValueException("gateway/netmask are required when create network of" + " type " + GuestType.Isolated + " with service " + Service.SourceNat.getName() + " disabled");
            }
        }

        checkL2OfferingServices(ntwkOff);

        // No cidr can be specified in Basic zone
        if (zone.getNetworkType() == NetworkType.Basic && cidr != null) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask can't be specified for zone of type " + NetworkType.Basic);
        }

        // Check if cidr is RFC1918 compliant if the network is Guest Isolated for IPv4
        if (cidr != null && (ntwkOff.getGuestType() == Network.GuestType.Isolated && ntwkOff.getTrafficType() == TrafficType.Guest) &&
                !NetUtils.validateGuestCidr(cidr, !ConfigurationManager.AllowNonRFC1918CompliantIPs.value())) {
                throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not RFC 1918 or 6598 compliant");
        }

        final String networkDomainFinal = networkDomain;
        final String vlanIdFinal = vlanId;
        final Boolean subdomainAccessFinal = subdomainAccess;
        final Network network = Transaction.execute(new TransactionCallback<Network>() {
            @Override
            public Network doInTransaction(final TransactionStatus status) {
                Long physicalNetworkId = null;
                if (pNtwk != null) {
                    physicalNetworkId = pNtwk.getId();
                }
                final DataCenterDeployment plan = new DataCenterDeployment(zoneId, null, null, null, null, physicalNetworkId);
                final NetworkVO userNetwork = new NetworkVO();
                userNetwork.setNetworkDomain(networkDomainFinal);

                if (cidr != null && gateway != null) {
                    userNetwork.setCidr(cidr);
                    userNetwork.setGateway(gateway);
                }

                if (StringUtils.isNoneBlank(ip6Gateway, ip6Cidr)) {
                    userNetwork.setIp6Cidr(ip6Cidr);
                    userNetwork.setIp6Gateway(ip6Gateway);
                }

                if (externalId != null) {
                    userNetwork.setExternalId(externalId);
                }

                if (StringUtils.isNotBlank(routerIp)) {
                    userNetwork.setRouterIp(routerIp);
                }

                if (StringUtils.isNotBlank(routerIpv6)) {
                    userNetwork.setRouterIpv6(routerIpv6);
                }

                if (vrIfaceMTUs != null) {
                    if (vrIfaceMTUs.first() != null && vrIfaceMTUs.first() > 0) {
                        userNetwork.setPublicMtu(vrIfaceMTUs.first());
                    } else {
                        userNetwork.setPublicMtu(Integer.valueOf(NetworkService.VRPublicInterfaceMtu.defaultValue()));
                    }

                    if (vrIfaceMTUs.second() != null && vrIfaceMTUs.second() > 0) {
                        userNetwork.setPrivateMtu(vrIfaceMTUs.second());
                    } else {
                        userNetwork.setPrivateMtu(Integer.valueOf(NetworkService.VRPrivateInterfaceMtu.defaultValue()));
                    }
                } else {
                    userNetwork.setPublicMtu(Integer.valueOf(NetworkService.VRPublicInterfaceMtu.defaultValue()));
                    userNetwork.setPrivateMtu(Integer.valueOf(NetworkService.VRPrivateInterfaceMtu.defaultValue()));
                }

                if (!GuestType.L2.equals(userNetwork.getGuestType())) {
                    if (StringUtils.isNotBlank(ip4Dns1)) {
                        userNetwork.setDns1(ip4Dns1);
                    }
                    if (StringUtils.isNotBlank(ip4Dns2)) {
                        userNetwork.setDns2(ip4Dns2);
                    }
                    if (StringUtils.isNotBlank(ip6Dns1)) {
                        userNetwork.setIp6Dns1(ip6Dns1);
                    }
                    if (StringUtils.isNotBlank(ip6Dns2)) {
                        userNetwork.setIp6Dns2(ip6Dns2);
                    }
                }

                if (vlanIdFinal != null) {
                    if (isolatedPvlan == null) {
                        URI uri = null;
                        if (UuidUtils.isUuid(vlanIdFinal)) {
                            //Logical router's UUID provided as VLAN_ID
                            userNetwork.setVlanIdAsUUID(vlanIdFinal); //Set transient field
                        } else {
                            uri = encodeVlanIdIntoBroadcastUri(vlanIdFinal, pNtwk);
                        }

                        if (_networksDao.listByPhysicalNetworkPvlan(physicalNetworkId, uri.toString()).size() > 0) {
                            throw new InvalidParameterValueException(String.format(
                                    "Network with vlan %s already exists or overlaps with other network pvlans in zone %s",
                                    vlanIdFinal, zone));
                        }

                        userNetwork.setBroadcastUri(uri);
                        if (!vlanIdFinal.equalsIgnoreCase(Vlan.UNTAGGED)) {
                            userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                        } else {
                            userNetwork.setBroadcastDomainType(BroadcastDomainType.Native);
                        }
                    } else {
                        if (vlanIdFinal.equalsIgnoreCase(Vlan.UNTAGGED)) {
                            throw new InvalidParameterValueException("Cannot support pvlan with untagged primary vlan!");
                        }
                        URI uri = NetUtils.generateUriForPvlan(vlanIdFinal, isolatedPvlan, isolatedPvlanType.toString());
                        if (_networksDao.listByPhysicalNetworkPvlan(physicalNetworkId, uri.toString(), isolatedPvlanType).size() > 0) {
                            throw new InvalidParameterValueException(String.format(
                                    "Network with primary vlan %s and secondary vlan %s type %s already exists or overlaps with other network pvlans in zone %s",
                                    vlanIdFinal, isolatedPvlan, isolatedPvlanType, zone));
                        }
                        userNetwork.setBroadcastUri(uri);
                        userNetwork.setBroadcastDomainType(BroadcastDomainType.Pvlan);
                        userNetwork.setPvlanType(isolatedPvlanType);
                    }
                }
                userNetwork.setNetworkCidrSize(networkCidrSize);
                final List<? extends Network> networks = setupNetwork(owner, ntwkOff, userNetwork, plan, name, displayText, true, domainId, aclType, subdomainAccessFinal, vpcId,
                        isDisplayNetworkEnabled);
                Network network = null;
                if (networks == null || networks.isEmpty()) {
                    throw new CloudRuntimeException("Fail to create a network");
                } else {
                    if (networks.size() > 0 && networks.get(0).getGuestType() == Network.GuestType.Isolated && networks.get(0).getTrafficType() == TrafficType.Guest) {
                        Network defaultGuestNetwork = networks.get(0);
                        for (final Network nw : networks) {
                            if (nw.getCidr() != null && nw.getCidr().equals(zone.getGuestNetworkCidr())) {
                                defaultGuestNetwork = nw;
                            }
                        }
                        network = defaultGuestNetwork;
                    } else {
                        // For shared network
                        network = networks.get(0);
                    }
                }

                if (updateResourceCount) {
                    _resourceLimitMgr.incrementResourceCount(owner.getId(), ResourceType.network, isDisplayNetworkEnabled);
                }
                UsageEventUtils.publishNetworkCreation(network);

                return network;
            }
        });

        CallContext.current().setEventDetails("Network Id: " + network.getId());
        CallContext.current().putContextParameter(Network.class, network.getUuid());
        return network;
    }

    @Override
    public boolean isSharedNetworkWithoutSpecifyVlan(NetworkOffering offering) {
        if (offering == null || offering.getTrafficType() != TrafficType.Guest || offering.getGuestType() != GuestType.Shared) {
            return false;
        }
        return !offering.isSpecifyVlan();
    }

    private boolean isPrivateGatewayWithoutSpecifyVlan(NetworkOffering ntwkOff) {
        return ntwkOff.getId() == _networkOfferingDao.findByUniqueName(NetworkOffering.SystemPrivateGatewayNetworkOfferingWithoutVlan).getId();
    }

    /**
     * Encodes VLAN/VXLAN ID into a Broadcast URI according to the isolation method from the Physical Network.
     *
     * @return Broadcast URI, e.g. 'vlan://vlan_ID' or 'vxlan://vlxan_ID'
     */
    protected URI encodeVlanIdIntoBroadcastUri(String vlanId, PhysicalNetwork pNtwk) {
        if (pNtwk == null) {
            throw new InvalidParameterValueException(String.format("Failed to encode VLAN/VXLAN %s into a Broadcast URI. Physical Network cannot be null.", vlanId));
        }

        if (!pNtwk.getIsolationMethods().isEmpty() && StringUtils.isNotBlank(pNtwk.getIsolationMethods().get(0))) {
            String isolationMethod = pNtwk.getIsolationMethods().get(0).toLowerCase();
            String vxlan = BroadcastDomainType.Vxlan.toString().toLowerCase();
            if (isolationMethod.equals(vxlan)) {
                return BroadcastDomainType.encodeStringIntoBroadcastUri(vlanId, BroadcastDomainType.Vxlan);
            }
        }
        return BroadcastDomainType.fromString(vlanId);
    }

    /**
     * Checks bypass VLAN id/range overlap check during network creation for guest networks
     *
     * @param bypassVlanOverlapCheck bypass VLAN id/range overlap check
     * @param ntwkOff                network offering
     */
    private boolean hasGuestBypassVlanOverlapCheck(final boolean bypassVlanOverlapCheck, final NetworkOfferingVO ntwkOff, final boolean isPrivateNetwork) {
        return bypassVlanOverlapCheck && (ntwkOff.getGuestType() != GuestType.Isolated || isPrivateNetwork);
    }

    /**
     * Checks for L2 network offering services. Only 2 cases allowed:
     * - No services
     * - User Data service only, provided by ConfigDrive
     *
     * @param ntwkOff network offering
     */
    protected void checkL2OfferingServices(NetworkOfferingVO ntwkOff) {
        if (ntwkOff.getGuestType() == GuestType.L2 && !_networkModel.listNetworkOfferingServices(ntwkOff.getId()).isEmpty() &&
                (!_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.UserData) ||
                        (_networkModel.areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.UserData) &&
                                _networkModel.listNetworkOfferingServices(ntwkOff.getId()).size() > 1))) {
            throw new InvalidParameterValueException("For L2 networks, only UserData service is allowed");
        }
    }

    @Override
    @DB
    public boolean shutdownNetwork(final long networkId, final ReservationContext context, final boolean cleanupElements) {
        NetworkVO network = null;
        try {
            //do global lock for the network
            network = _networksDao.acquireInLockTable(networkId, NetworkLockTimeout.value());
            if (network == null) {
                logger.warn("Unable to acquire lock for the network {} as a part of network shutdown", network);
                return false;
            }
            logger.debug("Lock is acquired for network {} as a part of network shutdown", network);

            final Network.State initialState = network.getState();
            if (initialState == Network.State.Allocated) {
                logger.debug(String.format("Network [%s] is in Allocated state, no need to shutdown.", network));
                return true;
            }

            if (initialState != Network.State.Implemented && initialState != Network.State.Shutdown) {
                logger.debug("Network is not implemented: " + network);
                return false;
            }

            if (isSharedNetworkWithServices(network)) {
                network.setState(Network.State.Shutdown);
                _networksDao.update(network.getId(), network);
            } else {
                try {
                    stateTransitTo(network, Event.DestroyNetwork);
                } catch (final NoTransitionException e) {
                    network.setState(Network.State.Shutdown);
                    _networksDao.update(network.getId(), network);
                }
            }

            final boolean success = shutdownNetworkElementsAndResources(context, cleanupElements, network);

            final NetworkVO networkFinal = network;
            final boolean result = Transaction.execute(new TransactionCallback<Boolean>() {
                @Override
                public Boolean doInTransaction(final TransactionStatus status) {
                    boolean result = false;

                    if (success) {
                        logger.debug("Network {} is shutdown successfully, cleaning up corresponding resources now.", networkFinal);
                        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, networkFinal.getGuruName());
                        final NetworkProfile profile = convertNetworkToNetworkProfile(networkFinal.getId());
                        guru.shutdown(profile, _networkOfferingDao.findById(networkFinal.getNetworkOfferingId()));

                        applyProfileToNetwork(networkFinal, profile);
                        final DataCenterVO zone = _dcDao.findById(networkFinal.getDataCenterId());
                        if (isSharedNetworkOfferingWithServices(networkFinal.getNetworkOfferingId()) && zone.getNetworkType() == NetworkType.Advanced) {
                            networkFinal.setState(Network.State.Setup);
                        } else {
                            try {
                                stateTransitTo(networkFinal, Event.OperationSucceeded);
                            } catch (final NoTransitionException e) {
                                networkFinal.setState(Network.State.Allocated);
                                networkFinal.setRestartRequired(false);
                            }
                        }
                        _networksDao.update(networkFinal.getId(), networkFinal);
                        _networksDao.clearCheckForGc(networkId);
                        if (initialState == Network.State.Implemented) {
                            UsageEventUtils.publishNetworkUpdate(networkFinal);
                        }
                        result = true;
                    } else {
                        try {
                            stateTransitTo(networkFinal, Event.OperationFailed);
                        } catch (final NoTransitionException e) {
                            networkFinal.setState(Network.State.Implemented);
                            _networksDao.update(networkFinal.getId(), networkFinal);
                        }
                        result = false;
                    }

                    return result;
                }
            });

            return result;
        } finally {
            if (network != null) {
                _networksDao.releaseFromLockTable(network.getId());
                logger.debug("Lock is released for network {} as a part of network shutdown", network);
            }
        }
    }

    @Override
    public boolean shutdownNetworkElementsAndResources(final ReservationContext context, final boolean cleanupElements, final Network network) {

        // get providers to shutdown
        final List<Provider> providersToShutdown = getNetworkProviders(network.getId());

        // 1) Cleanup all the rules for the network. If it fails, just log the failure and proceed with shutting down
        // the elements
        boolean cleanupResult = true;
        boolean cleanupNeeded = false;
        try {
            for (final Provider provider : providersToShutdown) {
                if (provider.cleanupNeededOnShutdown()) {
                    cleanupNeeded = true;
                    break;
                }
            }
            if (cleanupNeeded) {
                cleanupResult = shutdownNetworkResources(network, context.getAccount(), context.getCaller().getId());
            }
        } catch (final Exception ex) {
            logger.warn("shutdownNetworkRules failed during the network {} shutdown due to", network, ex);
        } finally {
            // just warn the administrator that the network elements failed to shutdown
            if (!cleanupResult) {
                logger.warn("Failed to cleanup network {} resources as a part of shutdownNetwork", network);
            }
        }

        // 2) Shutdown all the network elements
        boolean success = true;
        for (final NetworkElement element : networkElements) {
            if (providersToShutdown.contains(element.getProvider())) {
                try {
                    logger.debug("Sending network shutdown to {}", element.getName());
                    if (!element.shutdown(network, context, cleanupElements)) {
                        logger.warn("Unable to complete shutdown of the network elements due to element: {}", element.getName());
                        success = false;
                    }
                } catch (final ResourceUnavailableException e) {
                    logger.warn("Unable to complete shutdown of the network elements due to element: {}", element.getName(), e);
                    success = false;
                } catch (final ConcurrentOperationException e) {
                    logger.warn("Unable to complete shutdown of the network elements due to element: {}", element.getName(), e);
                    success = false;
                } catch (final Exception e) {
                    logger.warn("Unable to complete shutdown of the network elements due to element: {}", element.getName(), e);
                    success = false;
                }
            }
        }
        reconfigureAndApplyStaticRouteForVpcVpn(network);
        return success;
    }

    private void cleanupPersistentnNetworkResources(NetworkVO network) {
        long networkOfferingId = network.getNetworkOfferingId();
        NetworkOfferingVO offering = _networkOfferingDao.findById(networkOfferingId);
        if (offering != null) {
            if (networkMeetsPersistenceCriteria(network, offering, true) &&
                    _networksDao.getOtherPersistentNetworksCount(network.getId(), network.getBroadcastUri().toString(), offering.isPersistent()) == 0) {
                List<HostVO> hosts = resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, network.getDataCenterId());
                for (HostVO host : hosts) {
                    try {
                        NicTO to = createNicTOFromNetworkAndOffering(network, offering, host);
                        CleanupPersistentNetworkResourceCommand cmd = new CleanupPersistentNetworkResourceCommand(to);
                        CleanupPersistentNetworkResourceAnswer answer = (CleanupPersistentNetworkResourceAnswer) _agentMgr.send(host.getId(), cmd);
                        if (answer == null) {
                            logger.warn("Unable to get an answer to the CleanupPersistentNetworkResourceCommand from agent: {}", host);
                            continue;
                        }

                        if (!answer.getResult()) {
                            logger.warn("Unable to setup agent {} due to {}", host, answer.getDetails());
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to cleanup network resources on host: {}", host);
                    }
                }
            }
        }
    }

    @Override
    @DB
    public boolean destroyNetwork(final long networkId, final ReservationContext context, final boolean forced) {
        final Account callerAccount = context.getAccount();

        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            logger.debug("Unable to find network with id: {}", networkId);
            return false;
        }
        // Make sure that there are no user vms in the network that are not Expunged/Error
        final List<UserVmVO> userVms = _userVmDao.listByNetworkIdAndStates(networkId);

        for (final UserVmVO vm : userVms) {
            if (!(vm.getState() == VirtualMachine.State.Expunging && vm.getRemoved() != null)) {
                logger.warn("Can't delete the network, not all user vms are expunged. Vm {} is in {} state", vm, vm.getState());
                return false;
            }
        }

        // Don't allow to delete network via api call when it has vms assigned to it
        final int nicCount = getActiveNicsInNetwork(networkId);
        if (nicCount > 0) {
            logger.debug("The network {} has active Nics, but shouldn't.", network);
            // at this point we have already determined that there are no active user vms in network
            // if the op_networks table shows active nics, it's a bug in releasing nics updating op_networks
            _networksDao.changeActiveNicsBy(networkId, -1 * nicCount);
        }

        //In Basic zone, make sure that there are no non-removed console proxies and SSVMs using the network
        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        if (zone.getNetworkType() == NetworkType.Basic) {
            final List<VMInstanceVO> systemVms = _vmDao.listNonRemovedVmsByTypeAndNetwork(network.getId(), Type.ConsoleProxy, Type.SecondaryStorageVm);
            if (systemVms != null && !systemVms.isEmpty()) {
                logger.warn("Can't delete the network, not all consoleProxy/secondaryStorage vms are expunged");
                return false;
            }
        }

        cleanupPersistentnNetworkResources(network);

        // Shutdown network first
        shutdownNetwork(networkId, context, false);

        // get updated state for the network
        network = _networksDao.findById(networkId);
        if (network.getState() != Network.State.Allocated && network.getState() != Network.State.Setup && !forced) {
            logger.debug("Network is not in the correct state to be destroyed: {}", network.getState());
            return false;
        }

        boolean success = true;
        if (!cleanupNetworkResources(networkId, callerAccount, context.getCaller().getId())) {
            logger.warn("Unable to delete network {}: failed to cleanup network resources", network);
            return false;
        }

        // get providers to destroy
        final List<Provider> providersToDestroy = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providersToDestroy.contains(element.getProvider())) {
                try {
                    logger.debug("Sending destroy to {}", element);

                    if (!element.destroy(network, context)) {
                        success = false;
                        logger.warn("Unable to complete destroy of the network: failed to destroy network element {}", element.getName());
                    }
                } catch (final ResourceUnavailableException e) {
                    logger.warn("Unable to complete destroy of the network due to element: {}", element.getName(), e);
                    success = false;
                } catch (final ConcurrentOperationException e) {
                    logger.warn("Unable to complete destroy of the network due to element: {}", element.getName(), e);
                    success = false;
                } catch (final Exception e) {
                    logger.warn("Unable to complete destroy of the network due to element: {}", element.getName(), e);
                    success = false;
                }
            }
        }

        if (success) {
            logger.debug("Network {} is destroyed successfully, cleaning up corresponding resources now.", network);

            final NetworkVO networkFinal = network;
            try {
                final List<VlanVO> deletedVlanRangeToPublish = Transaction.execute(new TransactionCallback<List<VlanVO>>() {
                    @Override
                    public List<VlanVO> doInTransaction(TransactionStatus status) {
                        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, networkFinal.getGuruName());

                        if (!guru.trash(networkFinal, _networkOfferingDao.findById(networkFinal.getNetworkOfferingId()))) {
                            throw new CloudRuntimeException("Failed to trash network.");
                        }
                        Pair<Boolean, List<VlanVO>> deletedVlans = deleteVlansInNetwork(networkFinal, context.getCaller().getId(), callerAccount);
                        if (!deletedVlans.first()) {
                            logger.warn("Failed to delete network " + networkFinal + "; was unable to cleanup corresponding ip ranges");
                            throw new CloudRuntimeException("Failed to delete network " + networkFinal + "; was unable to cleanup corresponding ip ranges");
                        } else {
                            // commit transaction only when ips and vlans for the network are released successfully

                            routedIpv4Manager.releaseBgpPeersForGuestNetwork(networkId);
                            routedIpv4Manager.releaseIpv4SubnetForGuestNetwork(networkId);
                            ipv6Service.releaseIpv6SubnetForNetwork(networkId);
                            ipv6Service.removePublicIpv6PlaceholderNics(networkFinal);
                            try {
                                stateTransitTo(networkFinal, Event.DestroyNetwork);
                            } catch (final NoTransitionException e) {
                                logger.debug(e.getMessage());
                            }
                            if (_networksDao.remove(networkFinal.getId())) {
                                final NetworkDomainVO networkDomain = _networkDomainDao.getDomainNetworkMapByNetworkId(networkFinal.getId());
                                if (networkDomain != null) {
                                    _networkDomainDao.remove(networkDomain.getId());
                                }

                                final NetworkAccountVO networkAccount = _networkAccountDao.getAccountNetworkMapByNetworkId(networkFinal.getId());
                                if (networkAccount != null) {
                                    _networkAccountDao.remove(networkAccount.getId());
                                }

                                networkDetailsDao.removeDetails(networkFinal.getId());
                                networkPermissionDao.removeAllPermissions(networkFinal.getId());
                                ASNumberVO asNumber = asNumberDao.findByZoneAndNetworkId(zone.getId(), networkId);
                                if (asNumber != null) {
                                    logger.debug(String.format("Releasing AS number %s from network %s", asNumber.getAsNumber(), networkId));
                                    bgpService.releaseASNumber(zone.getId(), asNumber.getAsNumber(), true);
                                }
                            }

                            final NetworkOffering ntwkOff = _entityMgr.findById(NetworkOffering.class, networkFinal.getNetworkOfferingId());
                            final boolean updateResourceCount = resourceCountNeedsUpdate(ntwkOff, networkFinal.getAclType());
                            if (updateResourceCount) {
                                _resourceLimitMgr.decrementResourceCount(networkFinal.getAccountId(), ResourceType.network, networkFinal.getDisplayNetwork());
                            }
                        }
                        return deletedVlans.second();
                    }
                });
                publishDeletedVlanRanges(deletedVlanRangeToPublish);
                if (_networksDao.findById(network.getId()) == null) {
                    // remove its related ACL permission
                    final Pair<Class<?>, Long> networkMsg = new Pair<Class<?>, Long>(Network.class, networkFinal.getId());
                    _messageBus.publish(_name, EntityManager.MESSAGE_REMOVE_ENTITY_EVENT, PublishScope.LOCAL, networkMsg);
                }
                UsageEventUtils.publishNetworkDeletion(network);
                return true;
            } catch (final CloudRuntimeException e) {
                logger.error("Failed to delete network", e);
                return false;
            }
        }

        return success;
    }

    private void publishDeletedVlanRanges(List<VlanVO> deletedVlanRangeToPublish) {
        if (CollectionUtils.isNotEmpty(deletedVlanRangeToPublish)) {
            for (VlanVO vlan : deletedVlanRangeToPublish) {
                _messageBus.publish(_name, MESSAGE_DELETE_VLAN_IP_RANGE_EVENT, PublishScope.LOCAL, vlan);
            }
        }
    }

    @Override
    public boolean resourceCountNeedsUpdate(final NetworkOffering ntwkOff, final ACLType aclType) {
        //Update resource count only for Isolated account specific non-system networks
        final boolean updateResourceCount = ntwkOff.getGuestType() == GuestType.Isolated && !ntwkOff.isSystemOnly() && aclType == ACLType.Account;
        return updateResourceCount;
    }

    protected Pair<Boolean, List<VlanVO>> deleteVlansInNetwork(final NetworkVO network, final long userId, final Account callerAccount) {
        final long networkId = network.getId();
        //cleanup Public vlans
        final List<VlanVO> publicVlans = _vlanDao.listVlansByNetworkId(networkId);
        List<VlanVO> deletedPublicVlanRange = new ArrayList<>();
        boolean result = true;
        for (final VlanVO vlan : publicVlans) {
            VlanVO vlanRange = _configMgr.deleteVlanAndPublicIpRange(userId, vlan.getId(), callerAccount);
            if (vlanRange == null) {
                logger.warn("Failed to delete vlan [id: {}, uuid: {}];", vlan.getId(), vlan.getUuid());
                result = false;
            } else {
                deletedPublicVlanRange.add(vlanRange);
            }
        }

        //cleanup private vlans
        final int privateIpAllocCount = _privateIpDao.countAllocatedByNetworkId(networkId);
        if (privateIpAllocCount > 0) {
            logger.warn("Can't delete Private ip range for network {} as it has allocated ip addresses", network);
            result = false;
        } else {
            _privateIpDao.deleteByNetworkId(networkId);
            logger.debug("Deleted ip range for private network {}", network);
        }

        // release vlans of user-shared networks without specifyvlan
        if (isSharedNetworkWithoutSpecifyVlan(_networkOfferingDao.findById(network.getNetworkOfferingId()))) {
            logger.debug("Releasing vnet for the network {}", network);
            _dcDao.releaseVnet(BroadcastDomainType.getValue(network.getBroadcastUri()), network.getDataCenterId(),
                    network.getPhysicalNetworkId(), network.getAccountId(), network.getReservationId());
        }
        return new Pair<>(result, deletedPublicVlanRange);
    }

    public class NetworkGarbageCollector extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            final GlobalLock gcLock = GlobalLock.getInternLock("Network.GC.Lock");
            try {
                if (gcLock.lock(3)) {
                    try {
                        reallyRun();
                    } finally {
                        gcLock.unlock();
                    }
                }
            } finally {
                gcLock.releaseRef();
            }
        }

        public void reallyRun() {
            try {
                final List<Long> shutdownList = new ArrayList<Long>();
                final long currentTime = System.currentTimeMillis() / 1000;
                final HashMap<Long, Long> stillFree = new HashMap<Long, Long>();

                final List<Long> networkIds = _networksDao.findNetworksToGarbageCollect();
                final int netGcWait = NumbersUtil.parseInt(_configDao.getValue(NetworkGcWait.key()), 60);
                logger.info("NetworkGarbageCollector uses '{}' seconds for GC interval.", netGcWait);

                for (final Long networkId : networkIds) {
                    if (!_networkModel.isNetworkReadyForGc(networkId)) {
                        continue;
                    }

                    if (!networkDetailsDao.findDetails(Network.AssociatedNetworkId, String.valueOf(networkId), null).isEmpty()) {
                        logger.debug("Network {} is associated to a shared network, skipping", networkId);
                        continue;
                    }

                    final Long time = _lastNetworkIdsToFree.remove(networkId);
                    if (time == null) {
                        logger.debug("We found network {} to be free for the first time. Adding it to the list: {}", () -> _networksDao.findById(networkId), () -> currentTime);
                        stillFree.put(networkId, currentTime);
                    } else if (time > currentTime - netGcWait) {
                        logger.debug("Network {} is still free but it's not time to shutdown yet: {}", () -> _networksDao.findById(networkId), time::toString);
                        stillFree.put(networkId, time);
                    } else {
                        shutdownList.add(networkId);
                    }
                }

                _lastNetworkIdsToFree = stillFree;

                final CallContext cctx = CallContext.current();

                for (final Long networkId : shutdownList) {

                    // If network is removed, unset gc flag for it
                    if (_networksDao.findById(networkId) == null) {
                        logger.debug("Network id={} is removed, so clearing up corresponding gc check", networkId);
                        _networksDao.clearCheckForGc(networkId);
                    } else {
                        try {

                            final User caller = cctx.getCallingUser();
                            final Account owner = cctx.getCallingAccount();

                            final ReservationContext context = new ReservationContextImpl(null, null, caller, owner);

                            shutdownNetwork(networkId, context, false);
                        } catch (final Exception e) {
                            logger.warn("Unable to shutdown network: {}", () -> _networksDao.findById(networkId));
                        }
                    }
                }
            } catch (final Exception e) {
                logger.warn("Caught exception while running network gc: ", e);
            }
        }
    }

    @Override
    public boolean startNetwork(final long networkId, final DeployDestination dest, final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {

        // Check if network exists
        final NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Network with specified id doesn't exist");
            ex.addProxyObject(String.valueOf(networkId), "networkId");
            throw ex;
        }

        // implement the network
        logger.debug("Starting network {}...", network);
        final Pair<NetworkGuru, NetworkVO> implementedNetwork = implementNetwork(networkId, dest, context);
        if (implementedNetwork == null || implementedNetwork.first() == null) {
            logger.warn("Failed to start the network {}", network);
            return false;
        } else {
            return true;
        }
    }

    @Override
    public boolean restartNetwork(final Long networkId, final Account callerAccount, final User callerUser, final boolean cleanup, final boolean livePatch) throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {
        boolean status = true;
        boolean restartRequired = false;
        final NetworkVO network = _networksDao.findById(networkId);

        logger.debug("Restarting network {}...", network);

        final ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        final NetworkOffering offering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        final DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);

        if (cleanup) {
            if (!rollingRestartRouters(network, offering, dest, context)) {
                status = false;
                restartRequired = true;
            }
            setRestartRequired(network, restartRequired);
            return status;
        } else if (livePatch) {
            List<DomainRouterVO> domainRouters;
            if (Objects.nonNull(network.getVpcId())) {
                domainRouters = routerDao.listByVpcId(network.getVpcId());
            } else {
                domainRouters = routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER, VirtualRouter.Role.INTERNAL_LB_VM);
            }
            for (DomainRouterVO router: domainRouters) {
                try {
                    VMInstanceVO instanceVO = _vmDao.findById(router.getId());
                    if (instanceVO == null) {
                        logger.info("Did not find a virtual router instance for the network");
                        continue;
                    }
                    Pair<Boolean, String> patched = mgr.updateSystemVM(instanceVO, true);
                    if (patched.first()) {
                        logger.info("Successfully patched router {}", router);
                    }
                } catch (CloudRuntimeException e) {
                    throw new CloudRuntimeException(String.format("Failed to live patch router: %s", router), e);
                }

            }
        }

        logger.debug("Implementing the network {} elements and resources as a part of network restart without cleanup", network);
        try {
            implementNetworkElementsAndResources(dest, context, network, offering);
            setRestartRequired(network, false);
            return true;
        } catch (final Exception ex) {
            logger.warn("Failed to implement network {} elements and resources as a part of network restart due to ", network, ex);
            return false;
        }
    }

    @Override
    public void destroyExpendableRouters(final List<? extends VirtualRouter> routers, final ReservationContext context) throws ResourceUnavailableException {
        final List<VirtualRouter> remainingRouters = new ArrayList<>();
        for (final VirtualRouter router : routers) {
            if (router.getState() == VirtualMachine.State.Stopped ||
                    router.getState() == VirtualMachine.State.Error ||
                    router.getState() == VirtualMachine.State.Shutdown ||
                    router.getState() == VirtualMachine.State.Unknown) {
                logger.debug("Destroying old router {}", router);
                _routerService.destroyRouter(router.getId(), context.getAccount(), context.getCaller().getId());
            } else {
                remainingRouters.add(router);
            }
        }

        if (remainingRouters.size() < 2) {
            return;
        }

        VirtualRouter backupRouter = null;
        for (final VirtualRouter router : remainingRouters) {
            if (router.getRedundantState() == VirtualRouter.RedundantState.BACKUP) {
                backupRouter = router;
            }
        }
        if (backupRouter == null) {
            backupRouter = routers.get(routers.size() - 1);
        }
        if (backupRouter != null) {
            _routerService.destroyRouter(backupRouter.getId(), context.getAccount(), context.getCaller().getId());
        }
    }

    @Override
    public boolean areRoutersRunning(final List<? extends VirtualRouter> routers) {
        for (final VirtualRouter router : routers) {
            if (router.getState() != VirtualMachine.State.Running) {
                logger.debug("Found new router {} to be in non-Running state: {}. Please try restarting network again." + router.getInstanceName(), router.getState());
                return false;
            }
        }
        return true;
    }

    /**
     * Cleanup entry on VR file specified by type
     */
    @Override
    public void cleanupNicDhcpDnsEntry(Network network, VirtualMachineProfile vmProfile, NicProfile nicProfile) {

        final List<Provider> networkProviders = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (networkProviders.contains(element.getProvider())) {
                if (!_networkModel.isProviderEnabledInPhysicalNetwork(_networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                            + network.getPhysicalNetworkId());
                }
                if (vmProfile.getType() == Type.User && element.getProvider() != null) {
                    if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Dhcp)
                            && _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, element.getProvider()) && element instanceof DhcpServiceProvider) {
                        final DhcpServiceProvider sp = (DhcpServiceProvider) element;
                        try {
                            sp.removeDhcpEntry(network, nicProfile, vmProfile);
                        } catch (ResourceUnavailableException e) {
                            logger.error("Failed to remove dhcp-dns entry due to: ", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * rollingRestartRouters performs restart of routers of a network by first
     * deploying a new VR and then destroying old VRs in rolling fashion. For
     * non-redundant network, it will re-program the new router as final step
     * otherwise deploys a backup router for the network.
     * @param network network to be restarted
     * @param offering network offering
     * @param dest deployment destination
     * @param context reservation context
     * @return returns true when the rolling restart operation succeeds
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     * @throws InsufficientCapacityException
     */
    private boolean rollingRestartRouters(final NetworkVO network, final NetworkOffering offering, final DeployDestination dest, final ReservationContext context) throws ResourceUnavailableException, ConcurrentOperationException, InsufficientCapacityException {
        if (!NetworkOrchestrationService.RollingRestartEnabled.value()) {
            if (shutdownNetworkElementsAndResources(context, true, network)) {
                implementNetworkElementsAndResources(dest, context, network, offering);
                return true;
            }
            logger.debug("Failed to shutdown the network elements and resources as a part of network restart: {}", network.getState());
            return false;
        }
        logger.debug("Performing rolling restart of routers of network {}", network);
        destroyExpendableRouters(routerDao.findByNetwork(network.getId()), context);

        final List<Provider> providersToImplement = getNetworkProviders(network.getId());
        final List<DomainRouterVO> oldRouters = routerDao.findByNetwork(network.getId());

        // Deploy a new router
        if (oldRouters.size() > 0) {
            network.setRollingRestart(true);
        }
        implementNetworkElements(dest, context, network, offering, providersToImplement);
        if (oldRouters.size() > 0) {
            network.setRollingRestart(false);
        }

        // For redundant network wait for 3*advert_int+skew_seconds for VRRP to kick in
        if (network.isRedundant() || (oldRouters.size() == 1 && oldRouters.get(0).getIsRedundantRouter())) {
            try {
                Thread.sleep(NetworkOrchestrationService.RVRHandoverTime);
            } catch (final InterruptedException ignored) {
            }
        }

        // Destroy old routers
        for (final DomainRouterVO oldRouter : oldRouters) {
            _routerService.stopRouter(oldRouter.getId(), true);
            _routerService.destroyRouter(oldRouter.getId(), context.getAccount(), context.getCaller().getId());
        }

        if (network.isRedundant()) {
            // Add a new backup router for redundant network
            implementNetworkElements(dest, context, network, offering, providersToImplement);
        } else {
            // Re-apply rules for non-redundant network
            implementNetworkElementsAndResources(dest, context, network, offering);
        }

        return areRoutersRunning(routerDao.findByNetwork(network.getId()));
    }

    private void setRestartRequired(final NetworkVO network, final boolean restartRequired) {
        logger.debug("Marking network {} with restartRequired={}", network, restartRequired);
        network.setRestartRequired(restartRequired);
        _networksDao.update(network.getId(), network);
    }

    protected int getActiveNicsInNetwork(final long networkId) {
        return _networksDao.getActiveNicsIn(networkId);
    }

    @Override
    public NetworkProfile convertNetworkToNetworkProfile(final long networkId) {
        final NetworkVO network = _networksDao.findById(networkId);
        final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
        final NetworkProfile profile = new NetworkProfile(network);
        guru.updateNetworkProfile(profile);

        return profile;
    }

    @Override
    public UserDataServiceProvider getPasswordResetProvider(final Network network) {
        final String passwordProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (passwordProvider == null) {
            logger.debug("Network {} doesn't support service {}", network, Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider) _networkModel.getElementImplementingProvider(passwordProvider);
    }

    @Override
    public UserDataServiceProvider getSSHKeyResetProvider(final Network network) {
        final String SSHKeyProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (SSHKeyProvider == null) {
            logger.debug("Network {} doesn't support service", network, Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider) _networkModel.getElementImplementingProvider(SSHKeyProvider);
    }

    @Override
    public DhcpServiceProvider getDhcpServiceProvider(final Network network) {
        final String DhcpProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.Dhcp);

        if (DhcpProvider == null) {
            logger.debug("Network {} doesn't support service {}", network, Service.Dhcp.getName());
            return null;
        }

        final NetworkElement element = _networkModel.getElementImplementingProvider(DhcpProvider);
        if (element instanceof DhcpServiceProvider) {
            return (DhcpServiceProvider) element;
        } else {
            return null;
        }
    }

    @Override
    public DnsServiceProvider getDnsServiceProvider(final Network network) {
        final String dnsProvider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.Dns);

        if (dnsProvider == null) {
            logger.debug("Network {} doesn't support service {}", network, Service.Dhcp.getName());
            return null;
        }

        return (DnsServiceProvider) _networkModel.getElementImplementingProvider(dnsProvider);
    }

    protected boolean isSharedNetworkWithServices(final Network network) {
        assert network != null;
        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        if (network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced
                && isSharedNetworkOfferingWithServices(network.getNetworkOfferingId())) {
            return true;
        }
        return false;
    }

    protected boolean isSharedNetworkOfferingWithServices(final long networkOfferingId) {
        final NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        if (networkOffering.getGuestType() == Network.GuestType.Shared
                && (_networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.SourceNat)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.StaticNat)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Firewall)
                || _networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.PortForwarding) || _networkModel.areServicesSupportedByNetworkOffering(
                networkOfferingId, Service.Lb))) {
            return true;
        }
        return false;
    }

    @Override
    public List<? extends Nic> listVmNics(final long vmId, final Long nicId, final Long networkId, String keyword) {
        List<NicVO> result = null;

        if (keyword == null || keyword.isEmpty()) {
            if (nicId == null && networkId == null) {
                result = _nicDao.listByVmId(vmId);
            } else {
                result = _nicDao.listByVmIdAndNicIdAndNtwkId(vmId, nicId, networkId);
            }
        } else {
            result = _nicDao.listByVmIdAndKeyword(vmId, keyword);
        }

        for (final NicVO nic : result) {
            if (_networkModel.isProviderForNetwork(Provider.NiciraNvp, nic.getNetworkId())) {
                //For NSX Based networks, add nsxlogicalswitch, nsxlogicalswitchport to each result
                logger.info("Listing NSX logical switch and logical switch por for each nic");
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());
                final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                final NetworkGuruAdditionalFunctions guruFunctions = (NetworkGuruAdditionalFunctions) guru;

                final Map<String, ? extends Object> nsxParams = guruFunctions.listAdditionalNicParams(nic.getUuid());
                if (nsxParams != null) {
                    final String lswitchUuuid = nsxParams.containsKey(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID)
                            ? (String) nsxParams.get(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID) : null;
                    final String lswitchPortUuuid = nsxParams.containsKey(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID)
                            ? (String) nsxParams.get(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID) : null;
                    nic.setNsxLogicalSwitchUuid(lswitchUuuid);
                    nic.setNsxLogicalSwitchPortUuid(lswitchPortUuuid);
                }
            }
        }

        return result;
    }

    @DB
    @Override
    public boolean reallocate(final VirtualMachineProfile vm, final DataCenterDeployment dest) throws InsufficientCapacityException, ConcurrentOperationException {
        final VMInstanceVO vmInstance = _vmDao.findById(vm.getId());
        final DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == NetworkType.Basic) {
            final List<NicVO> nics = _nicDao.listByVmId(vmInstance.getId());
            final NetworkVO network = _networksDao.findById(nics.get(0).getNetworkId());
            final LinkedHashMap<Network, List<? extends NicProfile>> profiles = new LinkedHashMap<Network, List<? extends NicProfile>>();
            profiles.put(network, new ArrayList<NicProfile>());

            Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientCapacityException>() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) throws InsufficientCapacityException {
                    cleanupNics(vm);
                    allocate(vm, profiles, null);
                }
            });
        }
        return true;
    }

    private boolean cleanupNetworkResources(final long networkId, final Account caller, final long callerUserId) {
        boolean success = true;
        final NetworkVO network = _networksDao.findById(networkId);
        final NetworkOfferingVO networkOffering= _networkOfferingDao.findById(network.getNetworkOfferingId());

        //remove BGP peers from the network
        if (routedIpv4Manager.removeBgpPeersFromNetwork(network) != null) {
            logger.debug("Successfully removed BGP peers from network id={}", networkId);
        } else {
            success = false;
            logger.warn("Failed to remove BGP peers from network as a part of network id={} cleanup", networkId);
        }

        //remove all PF/Static Nat rules for the network
        try {
            if (_rulesMgr.revokeAllPFStaticNatRulesForNetwork(networkId, callerUserId, caller)) {
                logger.debug("Successfully cleaned up portForwarding/staticNat rules for network {}", network);
            } else {
                success = false;
                logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup", network);
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
        }

        //remove all LB rules for the network
        if (_lbMgr.removeAllLoadBalanacersForNetwork(networkId, caller, callerUserId)) {
            logger.debug("Successfully cleaned up load balancing rules for network {}", network);
        } else {
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            success = false;
            logger.warn("Failed to cleanup LB rules as a part of network {} cleanup", network);
        }

        //revoke all firewall rules for the network
        try {
            if (_firewallMgr.revokeAllFirewallRulesForNetwork(network, callerUserId, caller)) {
                logger.debug("Successfully cleaned up firewallRules rules for network {}", network);
            } else {
                success = false;
                logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup", network);
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
        }

        //revoke all network ACLs for network
        try {
            if (_networkACLMgr.revokeACLItemsForNetwork(networkId)) {
                logger.debug("Successfully cleaned up NetworkACLs for network {}", network);
            } else {
                success = false;
                logger.warn("Failed to cleanup NetworkACLs as a part of network {} cleanup", network);
            }
        } catch (final ResourceUnavailableException ex) {
            success = false;
            logger.warn("Failed to cleanup Network ACLs as a part of network {} cleanup due to resourceUnavailable ", network, ex);
        }

        //release all ip addresses
        final List<IPAddressVO> ipsToRelease = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        for (final IPAddressVO ipToRelease : ipsToRelease) {
            if (ipToRelease.getVpcId() == null) {
                if (!ipToRelease.isPortable()) {
                    final IPAddressVO ip = _ipAddrMgr.markIpAsUnavailable(ipToRelease.getId());
                    assert ip != null : "Unable to mark the ip address id=" + ipToRelease.getId() + " as unavailable.";
                } else {
                    // portable IP address are associated with owner, until explicitly requested to be disassociated
                    // so as part of network clean up just break IP association with guest network
                    ipToRelease.setAssociatedWithNetworkId(null);
                    _ipAddressDao.update(ipToRelease.getId(), ipToRelease);
                    logger.debug("Portable IP address {} is no longer associated with any network", ipToRelease);
                }
            } else {
                _vpcMgr.unassignIPFromVpcNetwork(ipToRelease, network);
            }
        }

        try {
            if (!_ipAddrMgr.applyIpAssociations(network, true)) {
                logger.warn("Unable to apply ip address associations for {}", network);
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.NETWORK.name(), network.getUuid());

        return success;
    }

    private boolean shutdownNetworkResources(final Network network, final Account caller, final long callerUserId) {
        // This method cleans up network rules on the backend w/o touching them in the DB
        boolean success = true;

        // Mark all PF rules as revoked and apply them on the backend (not in the DB)
        final List<PortForwardingRuleVO> pfRules = _portForwardingRulesDao.listByNetwork(network.getId());
        logger.debug("Releasing {} port forwarding rules for network id={} as a part of shutdownNetworkRules.", pfRules.size(), network);

        for (final PortForwardingRuleVO pfRule : pfRules) {
            logger.trace("Marking pf rule {} with Revoke state", pfRule);
            pfRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(pfRules, true, false)) {
                logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // Mark all static rules as revoked and apply them on the backend (not in the DB)
        final List<FirewallRuleVO> firewallStaticNatRules = _firewallDao.listByNetworkAndPurpose(network.getId(), Purpose.StaticNat);
        final List<StaticNatRule> staticNatRules = new ArrayList<StaticNatRule>();
        logger.debug("Releasing {} static nat rules for network {} as a part of shutdownNetworkRules", firewallStaticNatRules.size(), network);

        for (final FirewallRuleVO firewallStaticNatRule : firewallStaticNatRules) {
            logger.trace("Marking static nat rule {} with Revoke state", firewallStaticNatRule);
            final IpAddress ip = _ipAddressDao.findById(firewallStaticNatRule.getSourceIpAddressId());
            final FirewallRuleVO ruleVO = _firewallDao.findById(firewallStaticNatRule.getId());

            if (ip == null || !ip.isOneToOneNat() || ip.getAssociatedWithVmId() == null) {
                throw new InvalidParameterValueException(String.format("Source ip address of the rule %s is not static nat enabled", firewallStaticNatRule));
            }

            //String dstIp = _networkModel.getIpInNetwork(ip.getAssociatedWithVmId(), firewallStaticNatRule.getNetworkId());
            ruleVO.setState(FirewallRule.State.Revoke);
            staticNatRules.add(new StaticNatRuleImpl(ruleVO, ip.getVmIp()));
        }

        try {
            if (!_firewallMgr.applyRules(staticNatRules, true, false)) {
                logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        try {
            if (!_lbMgr.revokeLoadBalancersForNetwork(network, Scheme.Public)) {
                logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        try {
            if (!_lbMgr.revokeLoadBalancersForNetwork(network, Scheme.Internal)) {
                logger.warn("Failed to cleanup internal lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup public lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // revoke all firewall rules for the network w/o applying them on the DB
        final List<FirewallRuleVO> firewallRules = _firewallDao.listByNetworkPurposeTrafficType(network.getId(), Purpose.Firewall, FirewallRule.TrafficType.Ingress);
        logger.debug("Releasing firewall ingress rules for network {} as a part of shutdownNetworkRules", firewallRules.size(), network);

        for (final FirewallRuleVO firewallRule : firewallRules) {
            logger.trace("Marking firewall ingress rule {} with Revoke state", firewallRule);
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(firewallRules, true, false)) {
                logger.warn("Failed to cleanup firewall ingress rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup firewall ingress rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        final List<FirewallRuleVO> firewallEgressRules = _firewallDao.listByNetworkPurposeTrafficType(network.getId(), Purpose.Firewall, FirewallRule.TrafficType.Egress);
        logger.debug("Releasing {} firewall egress rules for network {} as a part of shutdownNetworkRules", firewallEgressRules.size(), network);

        try {
            // delete default egress rule
            final DataCenter zone = _dcDao.findById(network.getDataCenterId());
            if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Firewall)
                    && (network.getGuestType() == Network.GuestType.Isolated || network.getGuestType() == Network.GuestType.Shared && zone.getNetworkType() == NetworkType.Advanced)) {
                // add default egress rule to accept the traffic
                _firewallMgr.applyDefaultEgressFirewallRule(network.getId(), _networkModel.getNetworkEgressDefaultPolicy(network.getId()), false);
            }

        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup firewall default egress rule as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        for (final FirewallRuleVO firewallRule : firewallEgressRules) {
            logger.trace("Marking firewall egress rule {} with Revoke state", firewallRule);
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(firewallEgressRules, true, false)) {
                logger.warn("Failed to cleanup firewall egress rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (final ResourceUnavailableException ex) {
            logger.warn("Failed to cleanup firewall egress rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        if (network.getVpcId() != null) {
            logger.debug("Releasing Network ACL Items for network {} as a part of shutdownNetworkRules", network);

            try {
                //revoke all Network ACLs for the network w/o applying them in the DB
                if (!_networkACLMgr.revokeACLItemsForNetwork(network.getId())) {
                    logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules");
                    success = false;
                }
            } catch (final ResourceUnavailableException ex) {
                logger.warn("Failed to cleanup network ACLs as a part of shutdownNetworkRules due to ", ex);
                success = false;
            }

        }

        //release all static nats for the network
        if (!_rulesMgr.applyStaticNatForNetwork(network, false, caller, true)) {
            logger.warn("Failed to disable static nats as part of shutdownNetworkRules for network {}", network);
            success = false;
        }

        // Get all ip addresses, mark as releasing and release them on the backend
        final List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        final List<PublicIp> publicIpsToRelease = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (final IPAddressVO userIp : userIps) {
                userIp.setState(IpAddress.State.Releasing);
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                publicIpsToRelease.add(publicIp);
            }
        }

        try {
            if (!_ipAddrMgr.applyIpAssociations(network, true, true, publicIpsToRelease)) {
                logger.warn("Unable to apply ip address associations for {} as a part of shutdownNetworkRules", network);
                success = false;
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }

    @Override
    public boolean processAnswers(final long agentId, final long seq, final Answer[] answers) {
        return false;
    }

    @Override
    public boolean processCommands(final long agentId, final long seq, final Command[] commands) {
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(final long agentId, final AgentControlCommand cmd) {
        return null;
    }

    public void processHostAdded(long hostId) {
    }

    @Override
    public void processConnect(final Host host, final StartupCommand cmd, final boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand) || cmd.isConnectionTransferred()) {
            return;
        }
        final long hostId = host.getId();
        final StartupRoutingCommand startup = (StartupRoutingCommand) cmd;

        final String dataCenter = startup.getDataCenter();

        long dcId = -1;
        DataCenterVO dc = _dcDao.findByName(dataCenter);
        if (dc == null) {
            try {
                dcId = Long.parseLong(dataCenter);
                dc = _dcDao.findById(dcId);
            } catch (final NumberFormatException e) {
            }
        }
        if (dc == null) {
            throw new IllegalArgumentException("Host " + startup.getPrivateIpAddress() + " sent incorrect data center: " + dataCenter);
        }
        dcId = dc.getId();
        final HypervisorType hypervisorType = startup.getHypervisorType();

        logger.debug("Host's hypervisorType is: {}", hypervisorType);

        final List<PhysicalNetworkSetupInfo> networkInfoList = new ArrayList<PhysicalNetworkSetupInfo>();

        // list all physicalnetworks in the zone & for each get the network names
        final List<PhysicalNetworkVO> physicalNtwkList = _physicalNetworkDao.listByZone(dcId);
        for (final PhysicalNetworkVO pNtwk : physicalNtwkList) {
            final String publicName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Public, hypervisorType);
            final String privateName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Management, hypervisorType);
            final String guestName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Guest, hypervisorType);
            final String storageName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Storage, hypervisorType);
            // String controlName = _pNTrafficTypeDao._networkModel.getNetworkTag(pNtwk.getId(), TrafficType.Control, hypervisorType);
            final PhysicalNetworkSetupInfo info = new PhysicalNetworkSetupInfo();
            info.setPhysicalNetworkId(pNtwk.getId());
            info.setGuestNetworkName(guestName);
            info.setPrivateNetworkName(privateName);
            info.setPublicNetworkName(publicName);
            info.setStorageNetworkName(storageName);
            final PhysicalNetworkTrafficTypeVO mgmtTraffic = _pNTrafficTypeDao.findBy(pNtwk.getId(), TrafficType.Management);
            if (mgmtTraffic != null) {
                final String vlan = mgmtTraffic.getVlan();
                info.setMgmtVlan(vlan);
            }
            networkInfoList.add(info);
        }

        // send the names to the agent
        logger.debug("Sending CheckNetworkCommand to check the Network is setup correctly on Agent");
        final CheckNetworkCommand nwCmd = new CheckNetworkCommand(networkInfoList);

        final CheckNetworkAnswer answer = (CheckNetworkAnswer) _agentMgr.easySend(hostId, nwCmd);

        if (answer == null) {
            logger.warn("Unable to get an answer to the CheckNetworkCommand from agent: {}", host);
            throw new ConnectionException(true, String.format("Unable to get an answer to the CheckNetworkCommand from agent: %s", host));
        }

        if (!answer.getResult()) {
            logger.warn("Unable to setup agent {} due to {}", host, answer.getDetails());
            final String msg = "Incorrect Network setup on agent, Reinitialize agent after network names are setup, details : " + answer.getDetails();
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, dcId, host.getPodId(), msg, msg);
            throw new ConnectionException(true, msg);
        } else {
            if (answer.needReconnect()) {
                throw new ConnectionException(false, "Reinitialize agent after network setup.");
            }
            logger.debug("Network setup is correct on Agent");
            return;
        }
    }

    @Override
    public boolean processDisconnect(final long agentId, final Status state) {
        return false;
    }

    @Override
    public void processHostAboutToBeRemoved(long hostId) {
    }

    @Override
    public void processHostRemoved(long hostId, long clusterId) {
    }

    @Override
    public boolean isRecurring() {
        return false;
    }

    @Override
    public int getTimeout() {
        return 0;
    }

    @Override
    public boolean processTimeout(final long agentId, final long seq) {
        return false;
    }

    @Override
    public Map<String, String> finalizeServicesAndProvidersForNetwork(final NetworkOffering offering, final Long physicalNetworkId) {
        final Map<String, String> svcProviders = new HashMap<String, String>();
        final Map<String, List<String>> providerSvcs = new HashMap<String, List<String>>();
        final List<NetworkOfferingServiceMapVO> servicesMap = _ntwkOfferingSrvcDao.listByNetworkOfferingId(offering.getId());

        final boolean checkPhysicalNetwork = physicalNetworkId != null ? true : false;

        for (final NetworkOfferingServiceMapVO serviceMap : servicesMap) {
            if (svcProviders.containsKey(serviceMap.getService())) {
                // FIXME - right now we pick up the first provider from the list, need to add more logic based on
                // provider load, etc
                continue;
            }

            final String service = serviceMap.getService();
            String provider = serviceMap.getProvider();

            if (provider == null) {
                provider = _networkModel.getDefaultUniqueProviderForService(service).getName();
            }

            // check that provider is supported
            if (checkPhysicalNetwork) {
                if (!_pNSPDao.isServiceProviderEnabled(physicalNetworkId, provider, service)) {
                    throw new UnsupportedServiceException("Provider " + provider + " is either not enabled or doesn't " + "support service " + service + " in physical network id="
                            + physicalNetworkId);
                }
            }

            svcProviders.put(service, provider);
            List<String> l = providerSvcs.get(provider);
            if (l == null) {
                providerSvcs.put(provider, l = new ArrayList<String>());
            }
            l.add(service);
        }

        return svcProviders;
    }

    private List<Provider> getNetworkProviders(final long networkId) {
        final List<String> providerNames = _ntwkSrvcDao.getDistinctProviders(networkId);
        final List<Provider> providers = new ArrayList<Provider>();
        for (final String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }

        return providers;
    }

    @Override
    public boolean setupDns(final Network network, final Provider provider) {
        final boolean dnsProvided = _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dns, provider);
        final boolean dhcpProvided = _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, provider);

        final boolean setupDns = dnsProvided || dhcpProvided;
        return setupDns;
    }

    protected NicProfile getNicProfileForVm(final Network network, final NicProfile requested, final VirtualMachine vm) {
        NicProfile nic = null;
        if (requested != null && requested.getBroadCastUri() != null) {
            final String broadcastUri = requested.getBroadCastUri().toString();
            final String ipAddress = requested.getIPv4Address();
            final NicVO nicVO = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri);
            if (nicVO != null) {
                if (ipAddress == null || nicVO.getIPv4Address().equals(ipAddress)) {
                    nic = _networkModel.getNicProfile(vm, network.getId(), broadcastUri);
                }
            }
        } else {
            final NicVO nicVO = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
            if (nicVO != null) {
                nic = _networkModel.getNicProfile(vm, network.getId(), null);
            }
        }
        return nic;
    }

    @Override
    public NicProfile createNicForVm(final Network network, final NicProfile requested, final ReservationContext context, final VirtualMachineProfile vmProfile, final boolean prepare)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException, ConcurrentOperationException, InsufficientCapacityException,
            ResourceUnavailableException {
        final VirtualMachine vm = vmProfile.getVirtualMachine();
        final DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = _hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);

        NicProfile nic = getNicProfileForVm(network, requested, vm);

        //1) allocate nic (if needed) Always allocate if it is a user vm
        if (nic == null || vmProfile.getType() == VirtualMachine.Type.User) {
            final int deviceId = _nicDao.getFreeDeviceId(vm.getId());

            boolean isDefaultNic = getNicProfileDefaultNic(requested);

            nic = allocateNic(requested, network, isDefaultNic, deviceId, vmProfile).first();

            if (nic == null) {
                throw new CloudRuntimeException("Failed to allocate nic for vm " + vm + " in network " + network);
            }

            //Update vm_network_map table
            if (vmProfile.getType() == VirtualMachine.Type.User) {
                final VMNetworkMapVO vno = new VMNetworkMapVO(vm.getId(), network.getId());
                _vmNetworkMapDao.persist(vno);
            }
            logger.debug("Nic is allocated successfully for vm {} in network {}", vm, network);
        }

        //2) prepare nic
        if (prepare) {
            final Pair<NetworkGuru, NetworkVO> implemented = implementNetwork(nic.getNetworkId(), dest, context, vmProfile.getVirtualMachine().getType() == Type.DomainRouter);
            if (implemented == null || implemented.first() == null) {
                logger.warn("Failed to implement network {} as a part of preparing nic {}", network, nic);
                throw new CloudRuntimeException(String.format("Failed to implement network %s as a part preparing nic %s", network, nic));
            }
            nic = prepareNic(vmProfile, dest, context, nic.getId(), implemented.second());
            logger.debug("Nic is prepared successfully for vm {} in network {}", vm, network);
        }

        return nic;
    }

    private boolean getNicProfileDefaultNic(NicProfile nicProfile) {
        if (nicProfile != null) {
            logger.debug("Using requested nic profile isDefaultNic value [{}].", nicProfile.isDefaultNic());
            return nicProfile.isDefaultNic();
        }

        logger.debug("Using isDefaultNic default value [false] as requested nic profile is null.");
        return false;
    }

    @Override
    public List<NicProfile> getNicProfiles(final Long vmId, HypervisorType hypervisorType) {
        final List<NicVO> nics = _nicDao.listByVmId(vmId);
        final List<NicProfile> profiles = new ArrayList<NicProfile>();

        if (nics != null) {
            for (final Nic nic : nics) {
                final NetworkVO network = _networksDao.findById(nic.getNetworkId());
                final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vmId);

                final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                        _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(hypervisorType, network));
                guru.updateNicProfile(profile, network);
                profiles.add(profile);
            }
        }
        return profiles;
    }

    @Override
    public List<NicProfile> getNicProfiles(final VirtualMachine vm) {
        return getNicProfiles(vm.getId(), vm.getHypervisorType());
    }

    @Override
    public Map<String, String> getSystemVMAccessDetails(final VirtualMachine vm) {
        final Map<String, String> accessDetails = new HashMap<>();
        accessDetails.put(NetworkElementCommand.ROUTER_NAME, vm.getInstanceName());
        String privateIpAddress = null;
        for (final NicProfile profile : getNicProfiles(vm)) {
            if (profile == null) {
                continue;
            }
            final Network network = _networksDao.findById(profile.getNetworkId());
            if (network == null) {
                continue;
            }
            final String address = profile.getIPv4Address();
            if (network.getTrafficType() == Networks.TrafficType.Control) {
                accessDetails.put(NetworkElementCommand.ROUTER_IP, address);
            }
            if (network.getTrafficType() == Networks.TrafficType.Guest) {
                accessDetails.put(NetworkElementCommand.ROUTER_GUEST_IP, address);
            }
            if (network.getTrafficType() == Networks.TrafficType.Management) {
                privateIpAddress = address;
            }
            if (network.getTrafficType() != null && StringUtils.isNotEmpty(address)) {
                accessDetails.put(network.getTrafficType().name(), address);
            }
        }

        if (privateIpAddress != null && StringUtils.isEmpty(accessDetails.get(NetworkElementCommand.ROUTER_IP))) {
            accessDetails.put(NetworkElementCommand.ROUTER_IP,  privateIpAddress);
        }
        return accessDetails;
    }

    @Override
    public boolean stateTransitTo(final Network network, final Network.Event e) throws NoTransitionException {
        return _stateMachine.transitTo(network, e, null, _networksDao);
    }

    private void setStateMachine() {
        _stateMachine = Network.State.getStateMachine();
    }

    private Map<Service, Set<Provider>> getServiceProvidersMap(final long networkId) {
        final Map<Service, Set<Provider>> map = new HashMap<Service, Set<Provider>>();
        final List<NetworkServiceMapVO> nsms = _ntwkSrvcDao.getServicesInNetwork(networkId);
        for (final NetworkServiceMapVO nsm : nsms) {
            Set<Provider> providers = map.get(Service.getService(nsm.getService()));
            if (providers == null) {
                providers = new HashSet<Provider>();
            }
            providers.add(Provider.getProvider(nsm.getProvider()));
            map.put(Service.getService(nsm.getService()), providers);
        }
        return map;
    }

    @Override
    public List<Provider> getProvidersForServiceInNetwork(final Network network, final Service service) {
        final Map<Service, Set<Provider>> service2ProviderMap = getServiceProvidersMap(network.getId());
        if (service2ProviderMap.get(service) != null) {
            final List<Provider> providers = new ArrayList<Provider>(service2ProviderMap.get(service));
            return providers;
        }
        return null;
    }

    protected List<NetworkElement> getElementForServiceInNetwork(final Network network, final Service service) {
        final List<NetworkElement> elements = new ArrayList<NetworkElement>();
        final List<Provider> providers = getProvidersForServiceInNetwork(network, service);
        //Only support one provider now
        if (providers == null) {
            logger.error("Cannot find {} provider for network {}", service.getName(), network);
            return null;
        }
        if (providers.size() != 1 && service != Service.Lb) {
            //support more than one LB providers only
            logger.error("Found {} {} providers for network! {}", providers.size(), service.getName(), network);
            return null;
        }

        for (final Provider provider : providers) {
            final NetworkElement element = _networkModel.getElementImplementingProvider(provider.getName());
            logger.info("Let {} handle {} in network {}", element.getName(), service.getName(), network);
            elements.add(element);
        }
        return elements;
    }

    @Override
    public StaticNatServiceProvider getStaticNatProviderForNetwork(final Network network) {
        //only one provider per Static nat service is supoprted
        final NetworkElement element = getElementForServiceInNetwork(network, Service.StaticNat).get(0);
        assert element instanceof StaticNatServiceProvider;
        return (StaticNatServiceProvider) element;
    }

    @Override
    public LoadBalancingServiceProvider getLoadBalancingProviderForNetwork(final Network network, final Scheme lbScheme) {
        final List<NetworkElement> lbElements = getElementForServiceInNetwork(network, Service.Lb);
        NetworkElement lbElement = null;
        if (lbElements.size() > 1) {
            String providerName = null;
            //get network offering details
            final NetworkOffering off = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
            if (lbScheme == Scheme.Public) {
                providerName = _ntwkOffDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.PublicLbProvider);
            } else {
                providerName = _ntwkOffDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.InternalLbProvider);
            }
            if (providerName == null) {
                throw new InvalidParameterValueException("Can't find Lb provider supporting scheme " + lbScheme.toString() + " in network " + network);
            }
            lbElement = _networkModel.getElementImplementingProvider(providerName);
        } else if (lbElements.size() == 1) {
            lbElement = lbElements.get(0);
        }

        assert lbElement != null;
        assert lbElement instanceof LoadBalancingServiceProvider;
        return (LoadBalancingServiceProvider) lbElement;
    }

    @Override
    public boolean isNetworkInlineMode(final Network network) {
        final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        return offering.isInline();
    }

    @Override
    public boolean isSecondaryIpSetForNic(final long nicId) {
        final NicVO nic = _nicDao.findById(nicId);
        return nic.getSecondaryIp();
    }

    private boolean removeVmSecondaryIpsOfNic(final long nicId) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                final List<NicSecondaryIpVO> ipList = _nicSecondaryIpDao.listByNicId(nicId);
                if (ipList != null) {
                    for (final NicSecondaryIpVO ip : ipList) {
                        _nicSecondaryIpDao.remove(ip.getId());
                    }
                    logger.debug("Revoving nic secondary ip entry ...");
                }
            }
        });

        return true;
    }

    @Override
    public NicVO savePlaceholderNic(final Network network, final String ip4Address, final String ip6Address, final Type vmType) {
        return savePlaceholderNic(network, ip4Address, ip6Address, null, null, null, vmType);
    }

    @Override
    public NicVO savePlaceholderNic(final Network network, final String ip4Address, final String ip6Address, final String ip6Cidr, final String ip6Gateway, final String reserver, final Type vmType) {
        final NicVO nic = new NicVO(null, null, network.getId(), null);
        nic.setIPv4Address(ip4Address);
        nic.setIPv6Address(ip6Address);
        nic.setIPv6Cidr(ip6Cidr);
        nic.setIPv6Gateway(ip6Gateway);
        nic.setReservationStrategy(ReservationStrategy.PlaceHolder);
        if (reserver != null) {
            nic.setReserver(reserver);
        }
        nic.setState(Nic.State.Reserved);
        nic.setVmType(vmType);
        return _nicDao.persist(nic);
    }

    @DB
    @Override
    public Pair<NicProfile, Integer> importNic(final String macAddress, int deviceId, final Network network, final Boolean isDefaultNic, final VirtualMachine vm, final Network.IpAddresses ipAddresses, final DataCenter dataCenter, final boolean forced)
            throws ConcurrentOperationException, InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        logger.debug("Allocating nic for vm {} in network {} during import", vm, network);
        String selectedIp = null;
        if (ipAddresses != null && StringUtils.isNotEmpty(ipAddresses.getIp4Address())) {
            if (ipAddresses.getIp4Address().equals("auto")) {
                ipAddresses.setIp4Address(null);
            }
            selectedIp = getSelectedIpForNicImport(network, dataCenter, ipAddresses);
            if (selectedIp == null && network.getGuestType() != GuestType.L2 && !_networkModel.listNetworkOfferingServices(network.getNetworkOfferingId()).isEmpty()) {
                throw new InsufficientVirtualNetworkCapacityException("Unable to acquire Guest IP  address for network " + network, DataCenter.class,
                        network.getDataCenterId());
            }
        }
        final String finalSelectedIp = selectedIp;
        final NicVO vo = Transaction.execute(new TransactionCallback<NicVO>() {
            @Override
            public NicVO doInTransaction(TransactionStatus status) {
                if (StringUtils.isBlank(macAddress)) {
                    throw new CloudRuntimeException("Mac address not specified");
                }
                String macAddressToPersist = macAddress.trim();
                if (!NetUtils.isValidMac(macAddressToPersist)) {
                    throw new CloudRuntimeException("Invalid mac address: " + macAddressToPersist);
                }
                NicVO existingNic = _nicDao.findByNetworkIdAndMacAddress(network.getId(), macAddressToPersist);
                if (existingNic != null) {
                    macAddressToPersist = generateNewMacAddressIfForced(network, macAddressToPersist, forced);
                }
                NicVO vo = new NicVO(network.getGuruName(), vm.getId(), network.getId(), vm.getType());
                vo.setMacAddress(macAddressToPersist);
                vo.setAddressFormat(Networks.AddressFormat.Ip4);
                Pair<String, String> pair = getNetworkGatewayAndNetmaskForNicImport(network, dataCenter, finalSelectedIp);
                String gateway = pair.first();
                String netmask = pair.second();
                if (NetUtils.isValidIp4(finalSelectedIp) && StringUtils.isNotEmpty(gateway)) {
                    vo.setIPv4Address(finalSelectedIp);
                    vo.setIPv4Gateway(gateway);
                    vo.setIPv4Netmask(netmask);
                }
                vo.setBroadcastUri(network.getBroadcastUri());
                vo.setMode(network.getMode());
                vo.setState(Nic.State.Reserved);
                vo.setReservationStrategy(ReservationStrategy.Start);
                vo.setReservationId(UUID.randomUUID().toString());
                vo.setIsolationUri(network.getBroadcastUri());
                vo.setDeviceId(deviceId);
                vo.setDefaultNic(isDefaultNic);
                vo = _nicDao.persist(vo);

                int count = 1;
                if (vo.getVmType() == VirtualMachine.Type.User) {
                    logger.debug("Changing active number of nics for network {} on {}", network, count);
                    _networksDao.changeActiveNicsBy(network.getId(), count);
                }
                if (vo.getVmType() == VirtualMachine.Type.User
                        || vo.getVmType() == VirtualMachine.Type.DomainRouter && _networksDao.findById(network.getId()).getTrafficType() == TrafficType.Guest) {
                    _networksDao.setCheckForGc(network.getId());
                }
                if (vm.getType() == Type.DomainRouter) {
                    Pair<NetworkVO, VpcVO> networks = getGuestNetworkRouterAndVpcDetails(vm.getId());
                    setMtuDetailsInVRNic(networks, network, vo);
                }

                return vo;
            }
        });

        final Integer networkRate = _networkModel.getNetworkRate(network.getId(), vm.getId());
        final NicProfile vmNic = new NicProfile(vo, network, vo.getBroadcastUri(), vo.getIsolationUri(), networkRate, _networkModel.isSecurityGroupSupportedInNetwork(network),
                _networkModel.getNetworkTag(vm.getHypervisorType(), network));

        return new Pair<>(vmNic, Integer.valueOf(deviceId));
    }

    protected String getSelectedIpForNicImport(Network network, DataCenter dataCenter, Network.IpAddresses ipAddresses) {
        if (network.getGuestType() == GuestType.L2) {
            return null;
        }
        return dataCenter.getNetworkType() == NetworkType.Basic ?
                getSelectedIpForNicImportOnBasicZone(ipAddresses.getIp4Address(), network, dataCenter):
                _ipAddrMgr.acquireGuestIpAddress(network, ipAddresses.getIp4Address());
    }

    protected String getSelectedIpForNicImportOnBasicZone(String requestedIp, Network network, DataCenter dataCenter) {
        IPAddressVO ipAddressVO = StringUtils.isBlank(requestedIp) ?
                _ipAddressDao.findBySourceNetworkIdAndDatacenterIdAndState(network.getId(), dataCenter.getId(), IpAddress.State.Free):
                _ipAddressDao.findByIp(requestedIp);
        if (ipAddressVO == null || ipAddressVO.getState() != IpAddress.State.Free) {
            String msg = String.format("Cannot find a free IP to assign to VM NIC on network %s", network.getName());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        return ipAddressVO.getAddress() != null ? ipAddressVO.getAddress().addr() : null;
    }

    /**
     * Obtain the gateway and netmask for a VM NIC to import
     * If the VM to import is on a Basic Zone, then obtain the information from the vlan table instead of the network
     */
    protected Pair<String, String> getNetworkGatewayAndNetmaskForNicImport(Network network, DataCenter dataCenter, String selectedIp) {
        String gateway = network.getGateway();
        String netmask = StringUtils.isNotEmpty(network.getCidr()) ? NetUtils.cidr2Netmask(network.getCidr()) : null;
        if (dataCenter.getNetworkType() == NetworkType.Basic) {
            IPAddressVO freeIp = _ipAddressDao.findByIp(selectedIp);
            if (freeIp != null) {
                VlanVO vlan = _vlanDao.findById(freeIp.getVlanId());
                gateway = vlan != null ? vlan.getVlanGateway() : null;
                netmask = vlan != null ? vlan.getVlanNetmask() : null;
            }
        }
        return new Pair<>(gateway, netmask);
    }

    private String generateNewMacAddressIfForced(Network network, String macAddress, boolean forced) {
        if (!forced) {
            throw new CloudRuntimeException("NIC with MAC address " + macAddress + " exists on network " + network +
                    " and forced flag is disabled");
        }
        try {
            logger.debug("Generating a new mac address on network {} as the mac address {} already exists", network, macAddress);
            String newMacAddress = _networkModel.getNextAvailableMacAddressInNetwork(network.getId());
            logger.debug("Successfully generated the mac address {}, using it instead of the conflicting address {}", newMacAddress, macAddress);
            return newMacAddress;
        } catch (InsufficientAddressCapacityException e) {
            String msg = String.format("Could not generate a new mac address on network %s", network);
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    public void unmanageNics(VirtualMachineProfile vm) {
        logger.debug("Unmanaging NICs for VM: {}", vm);

        VirtualMachine virtualMachine = vm.getVirtualMachine();
        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            removeNic(vm, nic);
            NetworkVO network = _networksDao.findById(nic.getNetworkId());
            if (virtualMachine.getState() != VirtualMachine.State.Stopped) {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, virtualMachine.getAccountId(), virtualMachine.getDataCenterId(), virtualMachine.getId(),
                        Long.toString(nic.getId()), network.getNetworkOfferingId(), null, 0L, virtualMachine.getClass().getName(), virtualMachine.getUuid(), virtualMachine.isDisplay());
            }
        }
    }

    @Override
    public void expungeLbVmRefs(List<Long> vmIds, Long batchSize) {
        if (CollectionUtils.isEmpty(networkElements) || CollectionUtils.isEmpty(vmIds)) {
            return;
        }
        for (NetworkElement element : networkElements) {
            if (element instanceof LoadBalancingServiceProvider) {
                LoadBalancingServiceProvider lbProvider = (LoadBalancingServiceProvider)element;
                lbProvider.expungeLbVmRefs(vmIds, batchSize);
            }
        }
    }

    @Override
    public String getConfigComponentName() {
        return NetworkOrchestrationService.class.getSimpleName();
    }

    public static final ConfigKey<Integer> NetworkGcWait = new ConfigKey<Integer>(Integer.class, "network.gc.wait", "Advanced", "600",
            "Time (in seconds) to wait before shutting down a network that's not in used", false, Scope.Global, null);
    public static final ConfigKey<Integer> NetworkGcInterval = new ConfigKey<Integer>(Integer.class, "network.gc.interval", "Advanced", "600",
            "Seconds to wait before checking for networks to shutdown", true, Scope.Global, null);

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{NetworkGcWait, NetworkGcInterval, NetworkLockTimeout, DeniedRoutes,
                GuestDomainSuffix, NetworkThrottlingRate, MinVRVersion,
                PromiscuousMode, MacAddressChanges, ForgedTransmits, MacLearning, RollingRestartEnabled,
                TUNGSTEN_ENABLED, NSX_ENABLED, NETRIS_ENABLED };
    }
}
