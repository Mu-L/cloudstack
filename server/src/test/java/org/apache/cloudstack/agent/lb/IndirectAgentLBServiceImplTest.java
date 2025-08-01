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
package org.apache.cloudstack.agent.lb;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import com.cloud.agent.AgentManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.resource.ResourceState;
import com.cloud.utils.exception.CloudRuntimeException;

public class IndirectAgentLBServiceImplTest {

    @Mock
    HostDao hostDao;
    @Mock
    MessageBus messageBus;
    @Mock
    AgentManager agentManager;

    @Mock
    HostVO host1;
    @Mock
    HostVO host2;
    @Mock
    HostVO host3;
    @Mock
    HostVO host4;

    @Spy
    @InjectMocks
    private IndirectAgentLBServiceImpl agentMSLB = new IndirectAgentLBServiceImpl();

    private final String msCSVList = "192.168.10.10, 192.168.10.11, 192.168.10.12";
    private final List<String> msList = Arrays.asList(msCSVList.replace(" ","").split(","));

    private static final long DC_1_ID = 1L;
    private static final long DC_2_ID = 2L;
    private AutoCloseable closeable;

    private void overrideDefaultConfigValue(final ConfigKey configKey, final String name, final Object o) throws IllegalAccessException, NoSuchFieldException {
        final Field f = ConfigKey.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(configKey, o);
    }

    private void addField(final IndirectAgentLBServiceImpl provider, final String name, final Object o) throws IllegalAccessException, NoSuchFieldException {
        Field f = IndirectAgentLBServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(provider, o);
    }

    private void configureMocks() throws NoSuchFieldException, IllegalAccessException {
        List<HostVO> hosts = Arrays.asList(host1, host2, host3, host4);
        long id = 1;
        for (HostVO h : Arrays.asList(host1, host2, host3, host4)) {
            when(h.getId()).thenReturn(id);
            when(h.getDataCenterId()).thenReturn(DC_1_ID);
            when(h.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
            when(h.getType()).thenReturn(Host.Type.Routing);
            when(h.getRemoved()).thenReturn(null);
            when(h.getResourceState()).thenReturn(ResourceState.Enabled);
            id++;
        }
        addField(agentMSLB, "hostDao", hostDao);
        addField(agentMSLB, "agentManager", agentManager);

        List<Long> hostIds = hosts.stream().map(HostVO::getId).collect(Collectors.toList());
        doReturn(hostIds).when(hostDao).findHostIdsByZoneClusterResourceStateTypeAndHypervisorType(Mockito.anyLong(),
                Mockito.eq(null), Mockito.eq(null), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
    }

    @Before
    public void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);
        configureMocks();
        agentMSLB.configure("someName", null);
        overrideDefaultConfigValue(ApiServiceConfiguration.ManagementServerAddresses, "_defaultValue", msCSVList);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    public void testStaticLBSetting() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "static");
        for (HostVO host : Arrays.asList(host1, host2, host3, host4)) {
            List<String> listToSend = agentMSLB.getManagementServerList(host.getId(), host.getDataCenterId(), null);
            Assert.assertEquals(msList, listToSend);
        }
    }

    @Test
    public void testStaticLBSettingNullHostId() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "static");
        List<String> listToSend = agentMSLB.getManagementServerList(host2.getId(), host2.getDataCenterId(), null);
        Assert.assertEquals(listToSend, agentMSLB.getManagementServerList(null, DC_1_ID, null));
    }

    private void testRoundRobinForExistingHosts(List<String> list) {
        for (HostVO hostVO : Arrays.asList(host1, host2, host3, host4)) {
            List<String> listToSend = agentMSLB.getManagementServerList(hostVO.getId(), hostVO.getDataCenterId(), null);
            Assert.assertEquals(list, listToSend);
            Assert.assertEquals(list.get(0), listToSend.get(0));
            list.add(list.get(0));
            list.remove(0);
        }
    }

    @Test
    public void testRoundRobinDeterministicOrder() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "roundrobin");
        List<String> listHost2 = agentMSLB.getManagementServerList(host2.getId(), host2.getDataCenterId(), null);
        Assert.assertEquals(listHost2, agentMSLB.getManagementServerList(host2.getId(), host2.getDataCenterId(), null));
    }

    @Test
    public void testRoundRobinLBSettingConnectedAgents() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "roundrobin");
        List<String> list = new ArrayList<>(msList);
        testRoundRobinForExistingHosts(list);
    }

    @Test
    public void testRoundRobinLBSettingNullHostId() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "roundrobin");
        List<String> list = new ArrayList<>(msList);
        testRoundRobinForExistingHosts(list);
        List<String> listToSend = agentMSLB.getManagementServerList(null, DC_1_ID, null);
        Assert.assertEquals(list, listToSend);
        Assert.assertEquals(list.get(0), listToSend.get(0));
        list.add(list.get(0));
        list.remove(0);
    }

    @Test
    public void testShuffleLBSetting() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "shuffle");
        List<String> shuffleListHost2 = agentMSLB.getManagementServerList(host2.getId(), host2.getDataCenterId(), null);
        Assert.assertEquals(new HashSet<>(msList), new HashSet<>(shuffleListHost2));
    }

    @Test
    public void testShuffleLBSettingNullHostId() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "shuffle");
        Assert.assertEquals(new HashSet<>(msList), new HashSet<>(agentMSLB.getManagementServerList(null, DC_1_ID, null)));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testInvalidAlgorithmSetting() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm, "_defaultValue", "invalid-algo");
        agentMSLB.getManagementServerList(host1.getId(), host1.getDataCenterId(), null);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testExceptionOnEmptyHostSetting() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ApiServiceConfiguration.ManagementServerAddresses, "_defaultValue", "");
        // This should throw exception
        agentMSLB.getManagementServerList(host1.getId(), host1.getDataCenterId(), null);
    }

    @Test
    public void testGetOrderedRunningHostIdsEmptyList() {
        doReturn(Collections.emptyList()).when(hostDao).findHostIdsByZoneClusterResourceStateTypeAndHypervisorType(
                Mockito.eq(DC_1_ID), Mockito.eq(null), Mockito.eq(null), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
        Assert.assertTrue(agentMSLB.getOrderedHostIdList(DC_1_ID, false).isEmpty());
    }

    @Test
    public void testGetOrderedRunningHostIdsOrderList() {
        doReturn(Arrays.asList(host4.getId(), host2.getId(), host1.getId(), host3.getId())).when(hostDao)
                .findHostIdsByZoneClusterResourceStateTypeAndHypervisorType(Mockito.eq(DC_1_ID), Mockito.eq(null), Mockito.eq(null),
                        Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
        Assert.assertEquals(Arrays.asList(host1.getId(), host2.getId(), host3.getId(), host4.getId()),
                agentMSLB.getOrderedHostIdList(DC_1_ID, false));
    }
}
