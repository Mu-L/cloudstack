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
package com.cloud.host.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.persistence.TableGenerator;

import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.utils.jsinterpreter.TagAsRuleHelper;
import org.apache.commons.collections.CollectionUtils;

import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.cluster.agentlb.HostTransferMapVO;
import com.cloud.cluster.agentlb.dao.HostTransferMapDao;
import com.cloud.configuration.ManagementServiceConfiguration;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.gpu.dao.HostGpuGroupsDao;
import com.cloud.gpu.dao.VGPUTypesDao;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.Status.Event;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.info.RunningHostCountInfo;
import com.cloud.org.Grouping;
import com.cloud.org.Managed;
import com.cloud.resource.ResourceState;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.Attribute;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.UpdateBuilder;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.lang3.ObjectUtils;

@DB
@TableGenerator(name = "host_req_sq", table = "op_host", pkColumnName = "id", valueColumnName = "sequence", allocationSize = 1)
public class HostDaoImpl extends GenericDaoBase<HostVO, Long> implements HostDao { //FIXME: , ExternalIdDao {

    private static final String LIST_HOST_IDS_BY_HOST_TAGS = "SELECT filtered.host_id, COUNT(filtered.tag) AS tag_count "
                                                             + "FROM (SELECT host_id, tag, is_tag_a_rule FROM host_tags GROUP BY host_id,tag,is_tag_a_rule) AS filtered "
                                                             + "WHERE tag IN (%s) AND (is_tag_a_rule = 0 OR is_tag_a_rule IS NULL) "
                                                             + "GROUP BY host_id "
                                                             + "HAVING tag_count = %s ";
    private static final String SEPARATOR = ",";
    private static final String LIST_CLUSTER_IDS_FOR_HOST_TAGS = "select distinct cluster_id from host join ( %s ) AS selected_hosts ON host.id = selected_hosts.host_id";
    private static final String GET_HOSTS_OF_ACTIVE_VMS = "select h.id " +
            "from vm_instance vm " +
            "join host h on (vm.host_id=h.id) " +
            "where vm.service_offering_id= ? and vm.state not in (\"Destroyed\", \"Expunging\", \"Error\") group by h.id";
    private static final String GET_ORDERED_HW_VERSIONS_IN_DC = "select hypervisor_version from host " +
            "where type = 'Routing' and status = 'Up' and hypervisor_type = ? and data_center_id = ? " +
            "group by hypervisor_version " +
            "order by hypervisor_version asc";

    protected SearchBuilder<HostVO> TypePodDcStatusSearch;

    protected SearchBuilder<HostVO> IdsSearch;
    protected SearchBuilder<HostVO> IdStatusSearch;
    protected SearchBuilder<HostVO> TypeDcSearch;
    protected SearchBuilder<HostVO> TypeDcStatusSearch;
    protected SearchBuilder<HostVO> TypeStatusStateSearch;
    protected SearchBuilder<HostVO> MsStatusSearch;
    protected SearchBuilder<HostVO> DcPrivateIpAddressSearch;
    protected SearchBuilder<HostVO> DcStorageIpAddressSearch;
    protected SearchBuilder<HostVO> PublicIpAddressSearch;
    protected SearchBuilder<HostVO> UnremovedIpAddressSearch;

    protected SearchBuilder<HostVO> GuidSearch;
    protected SearchBuilder<HostVO> DcSearch;
    protected SearchBuilder<HostVO> PodSearch;
    protected SearchBuilder<HostVO> ClusterSearch;
    protected SearchBuilder<HostVO> TypeSearch;
    protected SearchBuilder<HostVO> StatusSearch;
    protected SearchBuilder<HostVO> ResourceStateSearch;
    protected SearchBuilder<HostVO> NameLikeSearch;
    protected SearchBuilder<HostVO> NameSearch;
    protected SearchBuilder<HostVO> hostHypervisorTypeAndVersionSearch;
    protected SearchBuilder<HostVO> SequenceSearch;
    protected SearchBuilder<HostVO> DirectlyConnectedSearch;
    protected SearchBuilder<HostVO> UnmanagedDirectConnectSearch;
    protected SearchBuilder<HostVO> UnmanagedApplianceSearch;
    protected SearchBuilder<HostVO> MaintenanceCountSearch;
    protected SearchBuilder<HostVO> HostTypeCountSearch;
    protected SearchBuilder<HostVO> ResponsibleMsSearch;
    protected SearchBuilder<HostVO> ResponsibleMsDcSearch;
    protected GenericSearchBuilder<HostVO, String> ResponsibleMsIdSearch;
    protected GenericSearchBuilder<HostVO, String> LastMsIdSearch;
    protected SearchBuilder<HostVO> HostTypeClusterCountSearch;
    protected SearchBuilder<HostVO> HostTypeZoneCountSearch;
    protected SearchBuilder<HostVO> ClusterStatusSearch;
    protected SearchBuilder<HostVO> TypeNameZoneSearch;
    protected SearchBuilder<HostVO> AvailHypevisorInZone;
    protected SearchBuilder<HostVO> ClusterHypervisorSearch;

    protected SearchBuilder<HostVO> DirectConnectSearch;
    protected SearchBuilder<HostVO> ManagedDirectConnectSearch;
    protected SearchBuilder<HostVO> ManagedRoutingServersSearch;
    protected SearchBuilder<HostVO> SecondaryStorageVMSearch;

    protected GenericSearchBuilder<HostVO, Long> HostsInStatusesSearch;
    protected GenericSearchBuilder<HostVO, Long> CountRoutingByDc;
    protected SearchBuilder<HostTransferMapVO> HostTransferSearch;
    protected SearchBuilder<ClusterVO> ClusterManagedSearch;
    protected SearchBuilder<HostVO> RoutingSearch;

    protected SearchBuilder<HostVO> HostsForReconnectSearch;
    protected GenericSearchBuilder<HostVO, Long> ClustersOwnedByMSSearch;
    protected GenericSearchBuilder<HostVO, Long> ClustersForHostsNotOwnedByAnyMSSearch;
    protected GenericSearchBuilder<ClusterVO, Long> AllClustersSearch;
    protected SearchBuilder<HostVO> HostsInClusterSearch;

    protected SearchBuilder<HostVO> searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag;

    protected SearchBuilder<HostTagVO> searchBuilderFindByRuleTag;

    protected Attribute _statusAttr;
    protected Attribute _resourceStateAttr;
    protected Attribute _msIdAttr;
    protected Attribute _pingTimeAttr;

    @Inject
    protected HostDetailsDao _detailsDao;
    @Inject
    protected HostGpuGroupsDao _hostGpuGroupsDao;
    @Inject
    protected VGPUTypesDao _vgpuTypesDao;
    @Inject
    protected HostTagsDao _hostTagsDao;
    @Inject
    protected HostTransferMapDao _hostTransferDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    ManagementServiceConfiguration mgmtServiceConf;

    public HostDaoImpl() {
        super();
    }

    @PostConstruct
    public void init() {

        MaintenanceCountSearch = createSearchBuilder();
        MaintenanceCountSearch.and("cluster", MaintenanceCountSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        MaintenanceCountSearch.and("resourceState", MaintenanceCountSearch.entity().getResourceState(), SearchCriteria.Op.IN);
        MaintenanceCountSearch.done();

        HostTypeCountSearch = createSearchBuilder();
        HostTypeCountSearch.and("type", HostTypeCountSearch.entity().getType(), SearchCriteria.Op.EQ);
        HostTypeCountSearch.and("zoneId", HostTypeCountSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        HostTypeCountSearch.and("resourceState", HostTypeCountSearch.entity().getResourceState(), SearchCriteria.Op.EQ);
        HostTypeCountSearch.done();

        ResponsibleMsSearch = createSearchBuilder();
        ResponsibleMsSearch.and("managementServerId", ResponsibleMsSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        ResponsibleMsSearch.done();

        ResponsibleMsDcSearch = createSearchBuilder();
        ResponsibleMsDcSearch.and("managementServerId", ResponsibleMsDcSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        ResponsibleMsDcSearch.and("dcId", ResponsibleMsDcSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ResponsibleMsDcSearch.done();

        ResponsibleMsIdSearch = createSearchBuilder(String.class);
        ResponsibleMsIdSearch.selectFields(ResponsibleMsIdSearch.entity().getUuid());
        ResponsibleMsIdSearch.and("managementServerId", ResponsibleMsIdSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        ResponsibleMsIdSearch.done();

        LastMsIdSearch = createSearchBuilder(String.class);
        LastMsIdSearch.selectFields(LastMsIdSearch.entity().getUuid());
        LastMsIdSearch.and("lastManagementServerId", LastMsIdSearch.entity().getLastManagementServerId(), SearchCriteria.Op.EQ);
        LastMsIdSearch.done();

        HostTypeClusterCountSearch = createSearchBuilder();
        HostTypeClusterCountSearch.and("cluster", HostTypeClusterCountSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        HostTypeClusterCountSearch.and("type", HostTypeClusterCountSearch.entity().getType(), SearchCriteria.Op.EQ);
        HostTypeClusterCountSearch.and("status", HostTypeClusterCountSearch.entity().getStatus(), SearchCriteria.Op.IN);
        HostTypeClusterCountSearch.and("removed", HostTypeClusterCountSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        HostTypeClusterCountSearch.done();

        HostTypeZoneCountSearch = createSearchBuilder();
        HostTypeZoneCountSearch.and("type", HostTypeZoneCountSearch.entity().getType(), SearchCriteria.Op.EQ);
        HostTypeZoneCountSearch.and("dc", HostTypeZoneCountSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        HostTypeZoneCountSearch.and("removed", HostTypeZoneCountSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        HostTypeZoneCountSearch.done();

        TypePodDcStatusSearch = createSearchBuilder();
        HostVO entity = TypePodDcStatusSearch.entity();
        TypePodDcStatusSearch.and("type", entity.getType(), SearchCriteria.Op.EQ);
        TypePodDcStatusSearch.and("pod", entity.getPodId(), SearchCriteria.Op.EQ);
        TypePodDcStatusSearch.and("dc", entity.getDataCenterId(), SearchCriteria.Op.EQ);
        TypePodDcStatusSearch.and("cluster", entity.getClusterId(), SearchCriteria.Op.EQ);
        TypePodDcStatusSearch.and("status", entity.getStatus(), SearchCriteria.Op.EQ);
        TypePodDcStatusSearch.and("resourceState", entity.getResourceState(), SearchCriteria.Op.EQ);
        TypePodDcStatusSearch.done();

        MsStatusSearch = createSearchBuilder();
        MsStatusSearch.and("ms", MsStatusSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        MsStatusSearch.and("type", MsStatusSearch.entity().getType(), SearchCriteria.Op.EQ);
        MsStatusSearch.and("resourceState", MsStatusSearch.entity().getResourceState(), SearchCriteria.Op.NIN);
        MsStatusSearch.done();

        TypeDcSearch = createSearchBuilder();
        TypeDcSearch.and("type", TypeDcSearch.entity().getType(), SearchCriteria.Op.EQ);
        TypeDcSearch.and("dc", TypeDcSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        TypeDcSearch.done();

        SecondaryStorageVMSearch = createSearchBuilder();
        SecondaryStorageVMSearch.and("type", SecondaryStorageVMSearch.entity().getType(), SearchCriteria.Op.EQ);
        SecondaryStorageVMSearch.and("dc", SecondaryStorageVMSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        SecondaryStorageVMSearch.and("status", SecondaryStorageVMSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        SecondaryStorageVMSearch.done();

        TypeDcStatusSearch = createSearchBuilder();
        TypeDcStatusSearch.and("type", TypeDcStatusSearch.entity().getType(), SearchCriteria.Op.EQ);
        TypeDcStatusSearch.and("dc", TypeDcStatusSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        TypeDcStatusSearch.and("status", TypeDcStatusSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        TypeDcStatusSearch.and("resourceState", TypeDcStatusSearch.entity().getResourceState(), SearchCriteria.Op.EQ);
        TypeDcStatusSearch.done();

        TypeStatusStateSearch = createSearchBuilder();
        TypeStatusStateSearch.and("type", TypeStatusStateSearch.entity().getType(), SearchCriteria.Op.EQ);
        TypeStatusStateSearch.and("cluster", TypeStatusStateSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        TypeStatusStateSearch.and("pod", TypeStatusStateSearch.entity().getPodId(), SearchCriteria.Op.EQ);
        TypeStatusStateSearch.and("zone", TypeStatusStateSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        TypeStatusStateSearch.and("status", TypeStatusStateSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        TypeStatusStateSearch.and("resourceState", TypeStatusStateSearch.entity().getResourceState(), SearchCriteria.Op.EQ);
        TypeStatusStateSearch.done();

        IdsSearch = createSearchBuilder();
        IdsSearch.and("id", IdsSearch.entity().getId(), SearchCriteria.Op.IN);
        IdsSearch.done();

        IdStatusSearch = createSearchBuilder();
        IdStatusSearch.and("id", IdStatusSearch.entity().getId(), SearchCriteria.Op.EQ);
        IdStatusSearch.and("states", IdStatusSearch.entity().getStatus(), SearchCriteria.Op.IN);
        IdStatusSearch.done();

        DcPrivateIpAddressSearch = createSearchBuilder();
        DcPrivateIpAddressSearch.and("privateIpAddress", DcPrivateIpAddressSearch.entity().getPrivateIpAddress(), SearchCriteria.Op.EQ);
        DcPrivateIpAddressSearch.and("dc", DcPrivateIpAddressSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        DcPrivateIpAddressSearch.done();

        DcStorageIpAddressSearch = createSearchBuilder();
        DcStorageIpAddressSearch.and("storageIpAddress", DcStorageIpAddressSearch.entity().getStorageIpAddress(), SearchCriteria.Op.EQ);
        DcStorageIpAddressSearch.and("dc", DcStorageIpAddressSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        DcStorageIpAddressSearch.done();

        PublicIpAddressSearch = createSearchBuilder();
        PublicIpAddressSearch.and("publicIpAddress", PublicIpAddressSearch.entity().getPublicIpAddress(), SearchCriteria.Op.EQ);
        PublicIpAddressSearch.done();

        UnremovedIpAddressSearch = createSearchBuilder();
        UnremovedIpAddressSearch.and("removed", UnremovedIpAddressSearch.entity().getRemoved(), Op.NULL); // We don't want any removed hosts
        UnremovedIpAddressSearch.and().op("publicIpAddress", UnremovedIpAddressSearch.entity().getPublicIpAddress(), SearchCriteria.Op.EQ);
        UnremovedIpAddressSearch.or("privateIpAddress", UnremovedIpAddressSearch.entity().getPrivateIpAddress(), SearchCriteria.Op.EQ);
        UnremovedIpAddressSearch.cp();
        UnremovedIpAddressSearch.done();

        GuidSearch = createSearchBuilder();
        GuidSearch.and("guid", GuidSearch.entity().getGuid(), SearchCriteria.Op.EQ);
        GuidSearch.done();

        DcSearch = createSearchBuilder();
        DcSearch.and("dc", DcSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        DcSearch.and("hypervisorType", DcSearch.entity().getHypervisorType(), Op.EQ);
        DcSearch.and("type", DcSearch.entity().getType(), Op.EQ);
        DcSearch.and("status", DcSearch.entity().getStatus(), Op.EQ);
        DcSearch.and("resourceState", DcSearch.entity().getResourceState(), Op.EQ);
        DcSearch.done();

        ClusterStatusSearch = createSearchBuilder();
        ClusterStatusSearch.and("cluster", ClusterStatusSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        ClusterStatusSearch.and("status", ClusterStatusSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        ClusterStatusSearch.done();

        TypeNameZoneSearch = createSearchBuilder();
        TypeNameZoneSearch.and("name", TypeNameZoneSearch.entity().getName(), SearchCriteria.Op.EQ);
        TypeNameZoneSearch.and("type", TypeNameZoneSearch.entity().getType(), SearchCriteria.Op.EQ);
        TypeNameZoneSearch.and("zoneId", TypeNameZoneSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        TypeNameZoneSearch.done();

        PodSearch = createSearchBuilder();
        PodSearch.and("podId", PodSearch.entity().getPodId(), SearchCriteria.Op.EQ);
        PodSearch.and("type", PodSearch.entity().getType(), Op.EQ);
        PodSearch.done();

        ClusterSearch = createSearchBuilder();
        ClusterSearch.and("clusterId", ClusterSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        ClusterSearch.and("type", ClusterSearch.entity().getType(), Op.EQ);
        ClusterSearch.done();

        TypeSearch = createSearchBuilder();
        TypeSearch.and("type", TypeSearch.entity().getType(), SearchCriteria.Op.EQ);
        TypeSearch.done();

        StatusSearch = createSearchBuilder();
        StatusSearch.and("status", StatusSearch.entity().getStatus(), SearchCriteria.Op.IN);
        StatusSearch.done();

        ResourceStateSearch = createSearchBuilder();
        ResourceStateSearch.and("resourceState", ResourceStateSearch.entity().getResourceState(), SearchCriteria.Op.IN);
        ResourceStateSearch.done();

        NameLikeSearch = createSearchBuilder();
        NameLikeSearch.and("name", NameLikeSearch.entity().getName(), SearchCriteria.Op.LIKE);
        NameLikeSearch.done();

        NameSearch = createSearchBuilder();
        NameSearch.and("name", NameSearch.entity().getName(), SearchCriteria.Op.EQ);
        NameSearch.done();

        hostHypervisorTypeAndVersionSearch = createSearchBuilder();
        hostHypervisorTypeAndVersionSearch.and("hypervisorType", hostHypervisorTypeAndVersionSearch.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        hostHypervisorTypeAndVersionSearch.and("hypervisorVersion", hostHypervisorTypeAndVersionSearch.entity().getHypervisorVersion(), SearchCriteria.Op.EQ);
        hostHypervisorTypeAndVersionSearch.and("type", hostHypervisorTypeAndVersionSearch.entity().getType(), SearchCriteria.Op.EQ);
        hostHypervisorTypeAndVersionSearch.and("status", hostHypervisorTypeAndVersionSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        hostHypervisorTypeAndVersionSearch.done();

        SequenceSearch = createSearchBuilder();
        SequenceSearch.and("id", SequenceSearch.entity().getId(), SearchCriteria.Op.EQ);
        // SequenceSearch.addRetrieve("sequence", SequenceSearch.entity().getSequence());
        SequenceSearch.done();

        DirectlyConnectedSearch = createSearchBuilder();
        DirectlyConnectedSearch.and("resource", DirectlyConnectedSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        DirectlyConnectedSearch.and("ms", DirectlyConnectedSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        DirectlyConnectedSearch.and("statuses", DirectlyConnectedSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        DirectlyConnectedSearch.and("resourceState", DirectlyConnectedSearch.entity().getResourceState(), SearchCriteria.Op.NOTIN);
        DirectlyConnectedSearch.done();

        ClusterHypervisorSearch = createSearchBuilder();
        ClusterHypervisorSearch.and("clusterId", ClusterHypervisorSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        ClusterHypervisorSearch.and("hypervisor", ClusterHypervisorSearch.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        ClusterHypervisorSearch.and("type", ClusterHypervisorSearch.entity().getType(), SearchCriteria.Op.EQ);
        ClusterHypervisorSearch.and("status", ClusterHypervisorSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        ClusterHypervisorSearch.and("resourceState", ClusterHypervisorSearch.entity().getResourceState(), SearchCriteria.Op.EQ);
        ClusterHypervisorSearch.done();

        UnmanagedDirectConnectSearch = createSearchBuilder();
        UnmanagedDirectConnectSearch.and("resource", UnmanagedDirectConnectSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        UnmanagedDirectConnectSearch.and("server", UnmanagedDirectConnectSearch.entity().getManagementServerId(), SearchCriteria.Op.NULL);
        UnmanagedDirectConnectSearch.and("lastPinged", UnmanagedDirectConnectSearch.entity().getLastPinged(), SearchCriteria.Op.LTEQ);
        UnmanagedDirectConnectSearch.and("resourceStates", UnmanagedDirectConnectSearch.entity().getResourceState(), SearchCriteria.Op.NIN);
        UnmanagedDirectConnectSearch.and("clusterIn", UnmanagedDirectConnectSearch.entity().getClusterId(), SearchCriteria.Op.IN);
        try {
            HostTransferSearch = _hostTransferDao.createSearchBuilder();
        } catch (Throwable e) {
            logger.debug("error", e);
        }
        HostTransferSearch.and("id", HostTransferSearch.entity().getId(), SearchCriteria.Op.NULL);
        UnmanagedDirectConnectSearch.join("hostTransferSearch", HostTransferSearch, HostTransferSearch.entity().getId(), UnmanagedDirectConnectSearch.entity().getId(),
                JoinType.LEFTOUTER);
        ClusterManagedSearch = _clusterDao.createSearchBuilder();
        ClusterManagedSearch.and("managed", ClusterManagedSearch.entity().getManagedState(), SearchCriteria.Op.EQ);
        UnmanagedDirectConnectSearch.join("ClusterManagedSearch", ClusterManagedSearch, ClusterManagedSearch.entity().getId(), UnmanagedDirectConnectSearch.entity().getClusterId(),
                JoinType.INNER);
        UnmanagedDirectConnectSearch.done();

        DirectConnectSearch = createSearchBuilder();
        DirectConnectSearch.and("resource", DirectConnectSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        DirectConnectSearch.and("id", DirectConnectSearch.entity().getId(), SearchCriteria.Op.EQ);
        DirectConnectSearch.and().op("nullserver", DirectConnectSearch.entity().getManagementServerId(), SearchCriteria.Op.NULL);
        DirectConnectSearch.or("server", DirectConnectSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        DirectConnectSearch.cp();
        DirectConnectSearch.done();

        UnmanagedApplianceSearch = createSearchBuilder();
        UnmanagedApplianceSearch.and("resource", UnmanagedApplianceSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        UnmanagedApplianceSearch.and("server", UnmanagedApplianceSearch.entity().getManagementServerId(), SearchCriteria.Op.NULL);
        UnmanagedApplianceSearch.and("types", UnmanagedApplianceSearch.entity().getType(), SearchCriteria.Op.IN);
        UnmanagedApplianceSearch.and("lastPinged", UnmanagedApplianceSearch.entity().getLastPinged(), SearchCriteria.Op.LTEQ);
        UnmanagedApplianceSearch.done();

        AvailHypevisorInZone = createSearchBuilder();
        AvailHypevisorInZone.and("zoneId", AvailHypevisorInZone.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        AvailHypevisorInZone.and("hostId", AvailHypevisorInZone.entity().getId(), SearchCriteria.Op.NEQ);
        AvailHypevisorInZone.and("type", AvailHypevisorInZone.entity().getType(), SearchCriteria.Op.EQ);
        AvailHypevisorInZone.groupBy(AvailHypevisorInZone.entity().getHypervisorType());
        AvailHypevisorInZone.done();

        HostsInStatusesSearch = createSearchBuilder(Long.class);
        HostsInStatusesSearch.selectFields(HostsInStatusesSearch.entity().getId());
        HostsInStatusesSearch.and("dc", HostsInStatusesSearch.entity().getDataCenterId(), Op.EQ);
        HostsInStatusesSearch.and("pod", HostsInStatusesSearch.entity().getPodId(), Op.EQ);
        HostsInStatusesSearch.and("cluster", HostsInStatusesSearch.entity().getClusterId(), Op.EQ);
        HostsInStatusesSearch.and("type", HostsInStatusesSearch.entity().getType(), Op.EQ);
        HostsInStatusesSearch.and("statuses", HostsInStatusesSearch.entity().getStatus(), Op.IN);
        HostsInStatusesSearch.done();

        CountRoutingByDc = createSearchBuilder(Long.class);
        CountRoutingByDc.select(null, Func.COUNT, null);
        CountRoutingByDc.and("dc", CountRoutingByDc.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        CountRoutingByDc.and("type", CountRoutingByDc.entity().getType(), SearchCriteria.Op.EQ);
        CountRoutingByDc.and("status", CountRoutingByDc.entity().getStatus(), SearchCriteria.Op.EQ);
        CountRoutingByDc.done();

        ManagedDirectConnectSearch = createSearchBuilder();
        ManagedDirectConnectSearch.and("resource", ManagedDirectConnectSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        ManagedDirectConnectSearch.and("server", ManagedDirectConnectSearch.entity().getManagementServerId(), SearchCriteria.Op.NULL);
        ManagedDirectConnectSearch.done();

        ManagedRoutingServersSearch = createSearchBuilder();
        ManagedRoutingServersSearch.and("server", ManagedRoutingServersSearch.entity().getManagementServerId(), SearchCriteria.Op.NNULL);
        ManagedRoutingServersSearch.and("type", ManagedRoutingServersSearch.entity().getType(), SearchCriteria.Op.EQ);
        ManagedRoutingServersSearch.done();

        RoutingSearch = createSearchBuilder();
        RoutingSearch.and("type", RoutingSearch.entity().getType(), SearchCriteria.Op.EQ);
        RoutingSearch.done();

        HostsForReconnectSearch = createSearchBuilder();
        HostsForReconnectSearch.and("resource", HostsForReconnectSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        HostsForReconnectSearch.and("server", HostsForReconnectSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        HostsForReconnectSearch.and("lastPinged", HostsForReconnectSearch.entity().getLastPinged(), SearchCriteria.Op.LTEQ);
        HostsForReconnectSearch.and("resourceStates", HostsForReconnectSearch.entity().getResourceState(), SearchCriteria.Op.NIN);
        HostsForReconnectSearch.and("cluster", HostsForReconnectSearch.entity().getClusterId(), SearchCriteria.Op.NNULL);
        HostsForReconnectSearch.and("status", HostsForReconnectSearch.entity().getStatus(), SearchCriteria.Op.IN);
        HostsForReconnectSearch.done();

        ClustersOwnedByMSSearch = createSearchBuilder(Long.class);
        ClustersOwnedByMSSearch.select(null, Func.DISTINCT, ClustersOwnedByMSSearch.entity().getClusterId());
        ClustersOwnedByMSSearch.and("resource", ClustersOwnedByMSSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        ClustersOwnedByMSSearch.and("cluster", ClustersOwnedByMSSearch.entity().getClusterId(), SearchCriteria.Op.NNULL);
        ClustersOwnedByMSSearch.and("server", ClustersOwnedByMSSearch.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        ClustersOwnedByMSSearch.done();

        ClustersForHostsNotOwnedByAnyMSSearch = createSearchBuilder(Long.class);
        ClustersForHostsNotOwnedByAnyMSSearch.select(null, Func.DISTINCT, ClustersForHostsNotOwnedByAnyMSSearch.entity().getClusterId());
        ClustersForHostsNotOwnedByAnyMSSearch.and("resource", ClustersForHostsNotOwnedByAnyMSSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        ClustersForHostsNotOwnedByAnyMSSearch.and("cluster", ClustersForHostsNotOwnedByAnyMSSearch.entity().getClusterId(), SearchCriteria.Op.NNULL);
        ClustersForHostsNotOwnedByAnyMSSearch.and("server", ClustersForHostsNotOwnedByAnyMSSearch.entity().getManagementServerId(), SearchCriteria.Op.NULL);

        ClusterManagedSearch = _clusterDao.createSearchBuilder();
        ClusterManagedSearch.and("managed", ClusterManagedSearch.entity().getManagedState(), SearchCriteria.Op.EQ);
        ClustersForHostsNotOwnedByAnyMSSearch.join("ClusterManagedSearch", ClusterManagedSearch, ClusterManagedSearch.entity().getId(),
                ClustersForHostsNotOwnedByAnyMSSearch.entity().getClusterId(), JoinType.INNER);

        ClustersForHostsNotOwnedByAnyMSSearch.done();

        AllClustersSearch = _clusterDao.createSearchBuilder(Long.class);
        AllClustersSearch.select(null, Func.NATIVE, AllClustersSearch.entity().getId());
        AllClustersSearch.and("managed", AllClustersSearch.entity().getManagedState(), SearchCriteria.Op.EQ);
        AllClustersSearch.done();

        HostsInClusterSearch = createSearchBuilder();
        HostsInClusterSearch.and("resource", HostsInClusterSearch.entity().getResource(), SearchCriteria.Op.NNULL);
        HostsInClusterSearch.and("cluster", HostsInClusterSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        HostsInClusterSearch.and("server", HostsInClusterSearch.entity().getManagementServerId(), SearchCriteria.Op.NNULL);
        HostsInClusterSearch.done();

        searchBuilderFindByRuleTag = _hostTagsDao.createSearchBuilder();
        searchBuilderFindByRuleTag.and("is_tag_a_rule", searchBuilderFindByRuleTag.entity().getIsTagARule(), Op.EQ);
        searchBuilderFindByRuleTag.or("tagDoesNotExist", searchBuilderFindByRuleTag.entity().getIsTagARule(), Op.NULL);

        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag = createSearchBuilder();
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.and("id", searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.entity().getId(), Op.EQ);
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.and("type", searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.entity().getType(), Op.EQ);
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.and("cluster_id", searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.entity().getClusterId(), Op.EQ);
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.and("pod_id", searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.entity().getPodId(), Op.EQ);
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.and("data_center_id", searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.entity().getDataCenterId(), Op.EQ);
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.join("id", searchBuilderFindByRuleTag, searchBuilderFindByRuleTag.entity().getHostId(),
                searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.entity().getId(), JoinType.LEFTOUTER);

        searchBuilderFindByRuleTag.done();
        searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.done();

        _statusAttr = _allAttributes.get("status");
        _msIdAttr = _allAttributes.get("managementServerId");
        _pingTimeAttr = _allAttributes.get("lastPinged");
        _resourceStateAttr = _allAttributes.get("resourceState");

        assert (_statusAttr != null && _msIdAttr != null && _pingTimeAttr != null) : "Couldn't find one of these attributes";
    }

    @Override
    public long countBy(long clusterId, ResourceState... states) {
        SearchCriteria<HostVO> sc = MaintenanceCountSearch.create();

        sc.setParameters("resourceState", (Object[])states);
        sc.setParameters("cluster", clusterId);

        return getCount(sc);
    }

    @Override
    public Integer countAllByType(final Host.Type type) {
        SearchCriteria<HostVO> sc = HostTypeCountSearch.create();
        sc.setParameters("type", type);
        return getCount(sc);
    }

    @Override
    public Integer countAllInClusterByTypeAndStates(Long clusterId, final Host.Type type, List<Status> status) {
        SearchCriteria<HostVO> sc = HostTypeClusterCountSearch.create();
        if (clusterId != null) {
            sc.setParameters("cluster", clusterId);
        }
        if (type != null) {
            sc.setParameters("type", type);
        }
        if (status != null) {
            sc.setParameters("status", status.toArray());
        }
        return getCount(sc);
    }

    @Override
    public Integer countAllByTypeInZone(long zoneId, Type type) {
        SearchCriteria<HostVO> sc = HostTypeCountSearch.create();
        sc.setParameters("type", type);
        sc.setParameters("zoneId", zoneId);
        return getCount(sc);
    }

    @Override
    public Integer countUpAndEnabledHostsInZone(long zoneId) {
        SearchCriteria<HostVO> sc = HostTypeCountSearch.create();
        sc.setParameters("type", Type.Routing);
        sc.setParameters("resourceState", ResourceState.Enabled);
        sc.setParameters("zoneId", zoneId);
        return getCount(sc);
    }

    @Override
    public Pair<Integer, Integer> countAllHostsAndCPUSocketsByType(Type type) {
        GenericSearchBuilder<HostVO, SumCount> sb = createSearchBuilder(SumCount.class);
        sb.select("sum", Func.SUM, sb.entity().getCpuSockets());
        sb.select("count", Func.COUNT, null);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.done();
        SearchCriteria<SumCount> sc = sb.create();
        sc.setParameters("type", type);
        SumCount result = customSearch(sc, null).get(0);
        return new Pair<>((int)result.count, (int)result.sum);
    }

    private List<Long> listIdsForRoutingByZoneIdAndResourceState(long zoneId, ResourceState state) {
        return listIdsBy(Type.Routing, Status.Up, state, null, zoneId, null, null);
    }

    @Override
    public List<Long> listEnabledIdsByDataCenterId(long id) {
        return listIdsForRoutingByZoneIdAndResourceState(id, ResourceState.Enabled);
    }

    @Override
    public List<Long> listDisabledIdsByDataCenterId(long id) {
        return listIdsForRoutingByZoneIdAndResourceState(id, ResourceState.Disabled);
    }

    @Override
    public List<HostVO> listByDataCenterIdAndHypervisorType(long zoneId, Hypervisor.HypervisorType hypervisorType) {
        SearchBuilder<ClusterVO> clusterSearch = _clusterDao.createSearchBuilder();

        clusterSearch.and("allocationState", clusterSearch.entity().getAllocationState(), SearchCriteria.Op.EQ);
        clusterSearch.and("hypervisorType", clusterSearch.entity().getHypervisorType(), SearchCriteria.Op.EQ);

        SearchBuilder<HostVO> hostSearch = createSearchBuilder();

        hostSearch.and("dc", hostSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        hostSearch.and("type", hostSearch.entity().getType(), Op.EQ);
        hostSearch.and("status", hostSearch.entity().getStatus(), Op.EQ);
        hostSearch.and("resourceState", hostSearch.entity().getResourceState(), Op.EQ);

        hostSearch.join("clusterSearch", clusterSearch, hostSearch.entity().getClusterId(), clusterSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        hostSearch.done();

        SearchCriteria<HostVO> sc = hostSearch.create();

        sc.setParameters("dc", zoneId);
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);

        sc.setJoinParameters("clusterSearch", "allocationState", Grouping.AllocationState.Enabled);
        sc.setJoinParameters("clusterSearch", "hypervisorType", hypervisorType.toString());

        return listBy(sc);
    }

    @Override
    public HostVO findByGuid(String guid) {
        SearchCriteria<HostVO> sc = GuidSearch.create("guid", guid);
        return findOneBy(sc);
    }

    /*
     * Find hosts which is in Disconnected, Down, Alert and ping timeout and server is not null, set server to null
     */
    private void resetHosts(long managementServerId, long lastPingSecondsAfter) {
        SearchCriteria<HostVO> sc = HostsForReconnectSearch.create();
        sc.setParameters("server", managementServerId);
        sc.setParameters("lastPinged", lastPingSecondsAfter);
        sc.setParameters("status", Status.Disconnected, Status.Down, Status.Alert);

        StringBuilder sb = new StringBuilder();
        List<HostVO> hosts = lockRows(sc, null, true); // exclusive lock
        for (HostVO host : hosts) {
            host.setManagementServerId(null);
            update(host.getId(), host);
            sb.append(host.getId());
            sb.append(" ");
        }

        logger.trace("Following hosts got reset: {}", sb);
    }

    /*
     * Returns a list of cluster owned by @managementServerId
     */
    private List<Long> findClustersOwnedByManagementServer(long managementServerId) {
        SearchCriteria<Long> sc = ClustersOwnedByMSSearch.create();
        sc.setParameters("server", managementServerId);

        return customSearch(sc, null);
    }

    /*
     * Returns clusters based on the list of hosts not owned by any MS
     */
    private List<Long> findClustersForHostsNotOwnedByAnyManagementServer() {
        SearchCriteria<Long> sc = ClustersForHostsNotOwnedByAnyMSSearch.create();
        sc.setJoinParameters("ClusterManagedSearch", "managed", Managed.ManagedState.Managed);

        return customSearch(sc, null);
    }

    /**
     * This determines if hosts belonging to cluster(@clusterId) are up for grabs
     * This is used for handling following cases:
     * 1. First host added in cluster
     * 2. During MS restart all hosts in a cluster are without any MS
     */
    private boolean canOwnCluster(long clusterId) {
        SearchCriteria<HostVO> sc = HostsInClusterSearch.create();
        sc.setParameters("cluster", clusterId);

        List<HostVO> hosts = search(sc, null);
        return (hosts == null || hosts.isEmpty());
    }

    @Override
    @DB
    public List<HostVO> findAndUpdateDirectAgentToLoad(long lastPingSecondsAfter, Long limit, long managementServerId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();

        if (logger.isDebugEnabled()) {
            logger.debug("Resetting hosts suitable for reconnect");
        }
        // reset hosts that are suitable candidates for reconnect
        resetHosts(managementServerId, lastPingSecondsAfter);
        if (logger.isDebugEnabled()) {
            logger.debug("Completed resetting hosts suitable for reconnect");
        }

        List<HostVO> assignedHosts = new ArrayList<>();

        if (logger.isDebugEnabled()) {
            logger.debug("Acquiring hosts for clusters already owned by this management server");
        }
        List<Long> clusters = findClustersOwnedByManagementServer(managementServerId);
        txn.start();
        if (!clusters.isEmpty()) {
            // handle clusters already owned by @managementServerId
            SearchCriteria<HostVO> sc = UnmanagedDirectConnectSearch.create();
            sc.setParameters("lastPinged", lastPingSecondsAfter);
            sc.setJoinParameters("ClusterManagedSearch", "managed", Managed.ManagedState.Managed);
            sc.setParameters("clusterIn", clusters.toArray());
            List<HostVO> unmanagedHosts = lockRows(sc, new Filter(HostVO.class, "clusterId", true, 0L, limit), true); // host belongs to clusters owned by @managementServerId
            StringBuilder sb = new StringBuilder();
            for (HostVO host : unmanagedHosts) {
                host.setManagementServerId(managementServerId);
                update(host.getId(), host);
                assignedHosts.add(host);
                sb.append(host.getId());
                sb.append(" ");
            }
            logger.trace("Following hosts got acquired for clusters already owned: {}", sb);
        }
        logger.debug("Completed acquiring hosts for clusters already owned by this management server");

        if (assignedHosts.size() < limit) {
            if (logger.isDebugEnabled()) {
                logger.debug("Acquiring hosts for clusters not owned by any management server");
            }
            // for remaining hosts not owned by any MS check if they can be owned (by owning full cluster)
            clusters = findClustersForHostsNotOwnedByAnyManagementServer();
            List<Long> updatedClusters = clusters;
            if (clusters.size() > limit) {
                updatedClusters = clusters.subList(0, limit.intValue());
            }
            if (!updatedClusters.isEmpty()) {
                SearchCriteria<HostVO> sc = UnmanagedDirectConnectSearch.create();
                sc.setParameters("lastPinged", lastPingSecondsAfter);
                sc.setJoinParameters("ClusterManagedSearch", "managed", Managed.ManagedState.Managed);
                sc.setParameters("clusterIn", updatedClusters.toArray());
                List<HostVO> unmanagedHosts = lockRows(sc, null, true);

                // group hosts based on cluster
                Map<Long, List<HostVO>> hostMap = new HashMap<>();
                for (HostVO host : unmanagedHosts) {
                    if (hostMap.get(host.getClusterId()) == null) {
                        hostMap.put(host.getClusterId(), new ArrayList<>());
                    }
                    hostMap.get(host.getClusterId()).add(host);
                }

                StringBuilder sb = new StringBuilder();
                for (Long clusterId : hostMap.keySet()) {
                    if (canOwnCluster(clusterId)) { // cluster is not owned by any other MS, so @managementServerId can own it
                        List<HostVO> hostList = hostMap.get(clusterId);
                        for (HostVO host : hostList) {
                            host.setManagementServerId(managementServerId);
                            update(host.getId(), host);
                            assignedHosts.add(host);
                            sb.append(host.getId());
                            sb.append(" ");
                        }
                    }
                    if (assignedHosts.size() > limit) {
                        break;
                    }
                }
                logger.trace("Following hosts got acquired from newly owned clusters: {}", sb);
            }
            logger.debug("Completed acquiring hosts for clusters not owned by any management server");
        }
        txn.commit();

        return assignedHosts;
    }

    @Override
    @DB
    public List<HostVO> findAndUpdateApplianceToLoad(long lastPingSecondsAfter, long managementServerId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();

        txn.start();
        SearchCriteria<HostVO> sc = UnmanagedApplianceSearch.create();
        sc.setParameters("lastPinged", lastPingSecondsAfter);
        sc.setParameters("types", Type.ExternalDhcp, Type.ExternalFirewall, Type.ExternalLoadBalancer, Type.BaremetalDhcp, Type.BaremetalPxe, Type.TrafficMonitor,
                Type.L2Networking, Type.NetScalerControlCenter);
        List<HostVO> hosts = lockRows(sc, null, true);

        for (HostVO host : hosts) {
            host.setManagementServerId(managementServerId);
            update(host.getId(), host);
        }

        txn.commit();

        return hosts;
    }

    @Override
    public void markHostsAsDisconnected(long msId, long lastPing) {
        SearchCriteria<HostVO> sc = MsStatusSearch.create();
        sc.setParameters("ms", msId);

        HostVO host = createForUpdate();
        host.setLastPinged(lastPing);
        host.setDisconnectedOn(new Date());
        UpdateBuilder ub = getUpdateBuilder(host);
        ub.set(host, "status", Status.Disconnected);

        update(ub, sc, null);

        sc = MsStatusSearch.create();
        sc.setParameters("ms", msId);

        host = createForUpdate();
        host.setManagementServerId(null);
        host.setLastPinged(lastPing);
        host.setDisconnectedOn(new Date());
        ub = getUpdateBuilder(host);
        update(ub, sc, null);
    }

    @Override
    public List<HostVO> listByHostTag(Host.Type type, Long clusterId, Long podId, Long dcId, String hostTag) {
        return listHostsWithOrWithoutHostTags(type, clusterId, podId, dcId, hostTag, true);
    }

    private List<HostVO> listHostsWithOrWithoutHostTags(Host.Type type, Long clusterId, Long podId, Long dcId, String hostTags, boolean withHostTags) {
        if (StringUtils.isEmpty(hostTags)) {
            logger.debug("Host tags not specified, to list hosts");
            return new ArrayList<>();
        }

        SearchBuilder<HostVO> hostSearch = createSearchBuilder();
        HostVO entity = hostSearch.entity();
        hostSearch.and("type", entity.getType(), SearchCriteria.Op.EQ);
        hostSearch.and("pod", entity.getPodId(), SearchCriteria.Op.EQ);
        hostSearch.and("dc", entity.getDataCenterId(), SearchCriteria.Op.EQ);
        hostSearch.and("cluster", entity.getClusterId(), SearchCriteria.Op.EQ);
        hostSearch.and("status", entity.getStatus(), SearchCriteria.Op.EQ);
        hostSearch.and("resourceState", entity.getResourceState(), SearchCriteria.Op.EQ);

        SearchCriteria<HostVO> sc = hostSearch.create();
        if (type != null) {
            sc.setParameters("type", type.toString());
        }
        if (podId != null) {
            sc.setParameters("pod", podId);
        }
        if (clusterId != null) {
            sc.setParameters("cluster", clusterId);
        }
        if (dcId != null) {
            sc.setParameters("dc", dcId);
        }
        sc.setParameters("status", Status.Up.toString());
        sc.setParameters("resourceState", ResourceState.Enabled.toString());

        List<HostVO> upAndEnabledHosts = listBy(sc);
        if (CollectionUtils.isEmpty(upAndEnabledHosts)) {
            return new ArrayList<>();
        }

        List<Long> hostIdsByHostTags = findHostIdsByHostTags(hostTags);
        if (CollectionUtils.isEmpty(hostIdsByHostTags)) {
            return withHostTags ? new ArrayList<>() : upAndEnabledHosts;
        }

        if (withHostTags) {
            List<HostVO> upAndEnabledHostsWithHostTags = new ArrayList<>();
            upAndEnabledHosts.forEach((host) -> { if (hostIdsByHostTags.contains(host.getId())) upAndEnabledHostsWithHostTags.add(host);});
            return upAndEnabledHostsWithHostTags;
        } else {
            List<HostVO> upAndEnabledHostsWithoutHostTags = new ArrayList<>();
            upAndEnabledHosts.forEach((host) -> { if (!hostIdsByHostTags.contains(host.getId())) upAndEnabledHostsWithoutHostTags.add(host);});
            return upAndEnabledHostsWithoutHostTags;
        }
    }

    @Override
    public List<HostVO> listAllUpAndEnabledNonHAHosts(Type type, Long clusterId, Long podId, long dcId, String haTag) {
        if (StringUtils.isNotEmpty(haTag)) {
            return listHostsWithOrWithoutHostTags(type, clusterId, podId, dcId, haTag, false);
        }

        SearchBuilder<HostTagVO> hostTagSearch = _hostTagsDao.createSearchBuilder();
        hostTagSearch.and();
        hostTagSearch.op("isTagARule", hostTagSearch.entity().getIsTagARule(), Op.EQ);
        hostTagSearch.or("tagDoesNotExist", hostTagSearch.entity().getIsTagARule(), Op.NULL);
        hostTagSearch.cp();

        SearchBuilder<HostVO> hostSearch = createSearchBuilder();

        hostSearch.and("type", hostSearch.entity().getType(), SearchCriteria.Op.EQ);
        hostSearch.and("clusterId", hostSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        hostSearch.and("podId", hostSearch.entity().getPodId(), SearchCriteria.Op.EQ);
        hostSearch.and("zoneId", hostSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        hostSearch.and("status", hostSearch.entity().getStatus(), SearchCriteria.Op.EQ);
        hostSearch.and("resourceState", hostSearch.entity().getResourceState(), SearchCriteria.Op.EQ);

        hostSearch.join("hostTagSearch", hostTagSearch, hostSearch.entity().getId(), hostTagSearch.entity().getHostId(), JoinBuilder.JoinType.LEFTOUTER);

        SearchCriteria<HostVO> sc = hostSearch.create();

        sc.setJoinParameters("hostTagSearch", "isTagARule", false);

        if (type != null) {
            sc.setParameters("type", type);
        }

        if (clusterId != null) {
            sc.setParameters("clusterId", clusterId);
        }

        if (podId != null) {
            sc.setParameters("podId", podId);
        }

        sc.setParameters("zoneId", dcId);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);

        return listBy(sc);
    }

    @Override
    public void loadDetails(HostVO host) {
        Map<String, String> details = _detailsDao.findDetails(host.getId());
        host.setDetails(details);
    }

    @Override
    public void loadHostTags(HostVO host) {
        List<HostTagVO> hostTagVOList = _hostTagsDao.getHostTags(host.getId());
        if (CollectionUtils.isNotEmpty(hostTagVOList)) {
            List<String> hostTagList = hostTagVOList.parallelStream().map(HostTagVO::getTag).collect(Collectors.toList());
            host.setHostTags(hostTagList, hostTagVOList.get(0).getIsTagARule());
        } else {
            host.setHostTags(null, null);
        }
    }

    @DB
    @Override
    public List<HostVO> findLostHosts(long timeout) {
        List<HostVO> result = new ArrayList<>();
        String sql = "select h.id from host h left join  cluster c on h.cluster_id=c.id where h.mgmt_server_id is not null and h.last_ping < ? and h.status in ('Up', 'Updating', 'Disconnected', 'Connecting') and h.type not in ('ExternalFirewall', 'ExternalLoadBalancer', 'TrafficMonitor', 'SecondaryStorage', 'LocalSecondaryStorage', 'L2Networking') and (h.cluster_id is null or c.managed_state = 'Managed') ;";
        try (TransactionLegacy txn = TransactionLegacy.currentTxn();
                PreparedStatement pstmt = txn.prepareStatement(sql)) {
            pstmt.setLong(1, timeout);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1); //ID column
                    result.add(findById(id));
                }
            }
        } catch (SQLException e) {
            logger.warn("Exception: ", e);
        }
        return result;
    }

    @Override
    public void saveDetails(HostVO host) {
        Map<String, String> details = host.getDetails();
        if (details == null) {
            return;
        }
        _detailsDao.persist(host.getId(), details);
    }

    protected void saveHostTags(HostVO host) {
        List<String> hostTags = host.getHostTags();
        if (CollectionUtils.isEmpty(hostTags)) {
            return;
        }
        _hostTagsDao.persist(host.getId(), hostTags, host.getIsTagARule());
    }

    protected void saveGpuRecords(HostVO host) {
        HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails = host.getGpuGroupDetails();
        if (groupDetails != null) {
            // Create/Update GPU group entries
            _hostGpuGroupsDao.persist(host.getId(), new ArrayList<>(groupDetails.keySet()));
            // Create/Update VGPU types entries
            _vgpuTypesDao.persist(host.getId(), groupDetails);
        }
    }

    @Override
    @DB
    public HostVO persist(HostVO host) {
        final String InsertSequenceSql = "INSERT INTO op_host(id) VALUES(?)";

        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();

        HostVO dbHost = super.persist(host);

        try {
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(InsertSequenceSql);
            pstmt.setLong(1, dbHost.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to persist the sequence number for this host");
        }

        saveDetails(host);
        loadDetails(dbHost);
        saveHostTags(host);
        loadHostTags(dbHost);
        saveGpuRecords(host);

        txn.commit();

        return dbHost;
    }

    @Override
    @DB
    public boolean update(Long hostId, HostVO host) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();

        boolean persisted = super.update(hostId, host);
        if (!persisted) {
            return false;
        }

        saveDetails(host);
        saveHostTags(host);
        saveGpuRecords(host);

        txn.commit();

        return true;
    }

    @Override
    @DB
    public List<RunningHostCountInfo> getRunningHostCounts(Date cutTime) {
        String sql = "select * from (" + "select h.data_center_id, h.type, count(*) as count from host as h INNER JOIN mshost as m ON h.mgmt_server_id=m.msid "
                + "where h.status='Up' and h.type='SecondaryStorage' and m.last_update > ? " + "group by h.data_center_id, h.type " + "UNION ALL "
                + "select h.data_center_id, h.type, count(*) as count from host as h INNER JOIN mshost as m ON h.mgmt_server_id=m.msid "
                + "where h.status='Up' and h.type='Routing' and m.last_update > ? " + "group by h.data_center_id, h.type) as t " + "ORDER by t.data_center_id, t.type";

        ArrayList<RunningHostCountInfo> l = new ArrayList<>();

        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt;
        try {
            pstmt = txn.prepareAutoCloseStatement(sql);
            String gmtCutTime = DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), cutTime);
            pstmt.setString(1, gmtCutTime);
            pstmt.setString(2, gmtCutTime);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                RunningHostCountInfo info = new RunningHostCountInfo();
                info.setDcId(rs.getLong(1));
                info.setHostType(rs.getString(2));
                info.setCount(rs.getInt(3));

                l.add(info);
            }
        } catch (SQLException e) {
            logger.debug("SQLException caught", e);
        }
        return l;
    }

    @Override
    public long getNextSequence(long hostId) {
        logger.trace("getNextSequence(), hostId: {}", hostId);

        TableGenerator tg = _tgs.get("host_req_sq");
        assert tg != null : "how can this be wrong!";

        return s_seqFetcher.getNextSequence(Long.class, tg, hostId);
    }

    @Override
    public boolean updateState(Status oldStatus, Event event, Status newStatus, Host vo, Object data) {
        // lock target row from beginning to avoid lock-promotion caused deadlock
        HostVO host = lockRow(vo.getId(), true);
        if (host == null) {
            if (event == Event.Remove && newStatus == Status.Removed) {
                host = findByIdIncludingRemoved(vo.getId());
            }
        }

        if (host == null) {
            return false;
        }
        long oldPingTime = host.getLastPinged();

        SearchBuilder<HostVO> sb = createSearchBuilder();
        sb.and("status", sb.entity().getStatus(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("update", sb.entity().getUpdated(), SearchCriteria.Op.EQ);
        if (newStatus.checkManagementServer()) {
            sb.and("ping", sb.entity().getLastPinged(), SearchCriteria.Op.EQ);
            sb.and().op("nullmsid", sb.entity().getManagementServerId(), SearchCriteria.Op.NULL);
            sb.or("msid", sb.entity().getManagementServerId(), SearchCriteria.Op.EQ);
            sb.cp();
        }
        sb.done();

        SearchCriteria<HostVO> sc = sb.create();

        sc.setParameters("status", oldStatus);
        sc.setParameters("id", host.getId());
        sc.setParameters("update", host.getUpdated());
        long oldUpdateCount = host.getUpdated();
        if (newStatus.checkManagementServer()) {
            sc.setParameters("ping", oldPingTime);
            sc.setParameters("msid", host.getManagementServerId());
        }

        long newUpdateCount = host.incrUpdated();
        UpdateBuilder ub = getUpdateBuilder(host);
        ub.set(host, _statusAttr, newStatus);
        if (newStatus.updateManagementServer()) {
            if (newStatus.lostConnection()) {
                ub.set(host, _msIdAttr, null);
            } else {
                ub.set(host, _msIdAttr, host.getManagementServerId());
            }
            if (event.equals(Event.Ping) || event.equals(Event.AgentConnected)) {
                ub.set(host, _pingTimeAttr, System.currentTimeMillis() >> 10);
            }
        }
        if (event.equals(Event.ManagementServerDown)) {
            ub.set(host, _pingTimeAttr, ((System.currentTimeMillis() >> 10) - mgmtServiceConf.getTimeout()));
        }
        int result = update(ub, sc, null);
        assert result <= 1 : "How can this update " + result + " rows? ";

        if (result == 0) {
            HostVO ho = findById(host.getId());
            assert ho != null : "How how how? : " + host.getId();

            // TODO handle this if(debug){}else{log.debug} it makes no sense
            if (logger.isDebugEnabled()) {
                String str = "Unable to update host for event:" + event +
                        ". Name=" + host.getName() +
                        "; New=[status=" + newStatus + ":msid=" + (newStatus.lostConnection() ? "null" : host.getManagementServerId()) +
                        ":lastpinged=" + host.getLastPinged() + "]" +
                        "; Old=[status=" + oldStatus.toString() + ":msid=" + host.getManagementServerId() + ":lastpinged=" + oldPingTime +
                        "]" +
                        "; DB=[status=" + vo.getStatus().toString() + ":msid=" + vo.getManagementServerId() + ":lastpinged=" + vo.getLastPinged() +
                        ":old update count=" + oldUpdateCount + "]";
                logger.debug(str);
            } else {
                String msg = "Agent status update: [" + "id = " + host.getId() +
                        "; name = " + host.getName() +
                        "; old status = " + oldStatus +
                        "; event = " + event +
                        "; new status = " + newStatus +
                        "; old update count = " + oldUpdateCount +
                        "; new update count = " + newUpdateCount + "]";
                logger.debug(msg);
            }

            if (ho.getState() == newStatus) {
                logger.debug("Host {} state has already been updated to {}", ho.getName(), newStatus);
                return true;
            }
        }

        return result > 0;
    }

    @Override
    public boolean updateResourceState(ResourceState oldState, ResourceState.Event event, ResourceState newState, Host vo) {
        HostVO host = (HostVO)vo;
        SearchBuilder<HostVO> sb = createSearchBuilder();
        sb.and("resource_state", sb.entity().getResourceState(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.done();

        SearchCriteria<HostVO> sc = sb.create();

        sc.setParameters("resource_state", oldState);
        sc.setParameters("id", host.getId());

        UpdateBuilder ub = getUpdateBuilder(host);
        ub.set(host, _resourceStateAttr, newState);
        int result = update(ub, sc, null);
        assert result <= 1 : "How can this update " + result + " rows? ";

        // TODO handle this if(debug){}else{log.debug} it makes no sense
        if (logger.isDebugEnabled() && result == 0) {
            HostVO ho = findById(host.getId());
            assert ho != null : "How how how? : " + host.getId();

            String str = "Unable to update resource state: [" + "m = " + host.getId() +
                    "; name = " + host.getName() +
                    "; old state = " + oldState +
                    "; event = " + event +
                    "; new state = " + newState + "]";
            logger.debug(str);
        } else {
            String msg = "Resource state update: [" + "id = " + host.getId() +
                    "; name = " + host.getName() +
                    "; old state = " + oldState +
                    "; event = " + event +
                    "; new state = " + newState + "]";
            logger.debug(msg);
        }

        return result > 0;
    }

    @Override
    public HostVO findByTypeNameAndZoneId(long zoneId, String name, Host.Type type) {
        SearchCriteria<HostVO> sc = TypeNameZoneSearch.create();
        sc.setParameters("type", type);
        sc.setParameters("name", name);
        sc.setParameters("zoneId", zoneId);
        return findOneBy(sc);
    }

    @Override
    public List<HostVO> findByDataCenterId(Long zoneId) {
        SearchCriteria<HostVO> sc = DcSearch.create();
        sc.setParameters("dc", zoneId);
        sc.setParameters("type", Type.Routing);
        return listBy(sc);
    }

    @Override
    public List<Long> listIdsByDataCenterId(Long zoneId) {
        return listIdsBy(Type.Routing, null, null, null, zoneId, null, null);
    }

    @Override
    public List<HostVO> findByPodId(Long podId) {
        return findByPodId(podId, null);
    }

    @Override
    public List<HostVO> findByPodId(Long podId, Type type) {
        SearchCriteria<HostVO> sc = PodSearch.create();
        sc.setParameters("podId", podId);
        if (type != null) {
            sc.setParameters("type", Type.Routing);
        }
        return listBy(sc);
    }

    @Override
    public List<Long> listIdsByPodId(Long podId) {
        return listIdsBy(null, null, null, null, null, podId, null);
    }

    @Override
    public List<HostVO> findByClusterId(Long clusterId) {
        return findByClusterId(clusterId, null);
    }

    @Override
    public List<HostVO> findByClusterId(Long clusterId, Type type) {
        SearchCriteria<HostVO> sc = ClusterSearch.create();
        sc.setParameters("clusterId", clusterId);
        if (type != null) {
            sc.setParameters("type", Type.Routing);
        }
        return listBy(sc);
    }

    protected List<Long> listIdsBy(Host.Type type, Status status, ResourceState resourceState,
             HypervisorType hypervisorType, Long zoneId, Long podId, Long clusterId) {
        GenericSearchBuilder<HostVO, Long> sb = createSearchBuilder(Long.class);
        sb.selectFields(sb.entity().getId());
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.and("status", sb.entity().getStatus(), SearchCriteria.Op.EQ);
        sb.and("resourceState", sb.entity().getResourceState(), SearchCriteria.Op.EQ);
        sb.and("hypervisorType", sb.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        sb.and("zoneId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodId(), SearchCriteria.Op.EQ);
        sb.and("clusterId", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
        sb.done();
        SearchCriteria<Long> sc = sb.create();
        if (type != null) {
            sc.setParameters("type", type);
        }
        if (status != null) {
            sc.setParameters("status", status);
        }
        if (resourceState != null) {
            sc.setParameters("resourceState", resourceState);
        }
        if (hypervisorType != null) {
            sc.setParameters("hypervisorType", hypervisorType);
        }
        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }
        if (podId != null) {
            sc.setParameters("podId", podId);
        }
        if (clusterId != null) {
            sc.setParameters("clusterId", clusterId);
        }
        return customSearch(sc, null);
    }

    @Override
    public List<Long> listIdsByClusterId(Long clusterId) {
        return listIdsBy(null, null, null, null, null, null, clusterId);
    }

    @Override
    public List<Long> listIdsForUpRouting(Long zoneId, Long podId, Long clusterId) {
        return listIdsBy(Type.Routing, Status.Up, null, null, zoneId, podId, clusterId);
    }

    @Override
    public List<Long> listIdsByType(Type type) {
        return listIdsBy(type, null, null, null, null, null, null);
    }

    @Override
    public List<Long> listIdsForUpEnabledByZoneAndHypervisor(Long zoneId, HypervisorType hypervisorType) {
        return listIdsBy(null, Status.Up, ResourceState.Enabled, hypervisorType, zoneId, null, null);
    }

    @Override
    public List<HostVO> findByClusterIdAndEncryptionSupport(Long clusterId) {
        SearchBuilder<DetailVO> hostCapabilitySearch = _detailsDao.createSearchBuilder();
        DetailVO tagEntity = hostCapabilitySearch.entity();
        hostCapabilitySearch.and("capability", tagEntity.getName(), SearchCriteria.Op.EQ);
        hostCapabilitySearch.and("value", tagEntity.getValue(), SearchCriteria.Op.EQ);

        SearchBuilder<HostVO> hostSearch = createSearchBuilder();
        HostVO entity = hostSearch.entity();
        hostSearch.and("cluster", entity.getClusterId(), SearchCriteria.Op.EQ);
        hostSearch.and("status", entity.getStatus(), SearchCriteria.Op.EQ);
        hostSearch.join("hostCapabilitySearch", hostCapabilitySearch, entity.getId(), tagEntity.getHostId(), JoinBuilder.JoinType.INNER);

        SearchCriteria<HostVO> sc = hostSearch.create();
        sc.setJoinParameters("hostCapabilitySearch", "value", Boolean.toString(true));
        sc.setJoinParameters("hostCapabilitySearch", "capability", Host.HOST_VOLUME_ENCRYPTION);

        if (clusterId != null) {
            sc.setParameters("cluster", clusterId);
        }
        sc.setParameters("status", Status.Up.toString());
        sc.setParameters("resourceState", ResourceState.Enabled.toString());

        return listBy(sc);
    }

    @Override
    public HostVO findByPublicIp(String publicIp) {
        SearchCriteria<HostVO> sc = PublicIpAddressSearch.create();
        sc.setParameters("publicIpAddress", publicIp);
        return findOneBy(sc);
    }

    @Override
    public HostVO findByIp(final String ipAddress) {
        SearchCriteria<HostVO> sc = UnremovedIpAddressSearch.create();
        sc.setParameters("publicIpAddress", ipAddress);
        sc.setParameters("privateIpAddress", ipAddress);
        return findOneBy(sc);
    }

    @Override
    public List<HostVO> findHypervisorHostInCluster(long clusterId) {
        SearchCriteria<HostVO> sc = TypeStatusStateSearch.create();
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("cluster", clusterId);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);

        return listBy(sc);
    }

    @Override
    public List<HostVO> findHypervisorHostInZone(long zoneId) {
        SearchCriteria<HostVO> sc = TypeStatusStateSearch.create();
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("zone", zoneId);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);

        return listBy(sc);
    }

    @Override
    public List<HostVO> findHypervisorHostInPod(long podId) {
        SearchCriteria<HostVO> sc = TypeStatusStateSearch.create();
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("pod", podId);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);

        return listBy(sc);
    }

    @Override
    public HostVO findAnyStateHypervisorHostInCluster(long clusterId) {
        SearchCriteria<HostVO> sc = TypeStatusStateSearch.create();
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("cluster", clusterId);
        List<HostVO> list = listBy(sc, new Filter(1));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public HostVO findOldestExistentHypervisorHostInCluster(long clusterId) {
        SearchCriteria<HostVO> sc = TypeStatusStateSearch.create();
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("cluster", clusterId);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);
        Filter orderByFilter = new Filter(HostVO.class, "created", true, null, null);

        List<HostVO> hosts = search(sc, orderByFilter, null, false);
        if (hosts != null && !hosts.isEmpty()) {
            return hosts.get(0);
        }

        return null;
    }

    @Override
    public List<Long> listAllHosts(long zoneId) {
        return listIdsBy(null, null, null, null, zoneId, null, null);
    }

    @Override
    public List<HostVO> listAllHostsByZoneAndHypervisorType(long zoneId, HypervisorType hypervisorType) {
        SearchCriteria<HostVO> sc = DcSearch.create();
        sc.setParameters("dc", zoneId);
        if (hypervisorType != null) {
            sc.setParameters("hypervisorType", hypervisorType.toString());
        }
        return listBy(sc);
    }

    @Override
    public List<HostVO> listAllHostsThatHaveNoRuleTag(Type type, Long clusterId, Long podId, Long dcId) {
        SearchCriteria<HostVO> sc = searchBuilderFindByIdTypeClusterIdPodIdDcIdAndWithoutRuleTag.create();
        if (type != null) {
            sc.setParameters("type", type);
        }
        if (clusterId != null) {
            sc.setParameters("cluster_id", clusterId);
        }
        if (podId != null) {
            sc.setParameters("pod_id", podId);
        }
        if (dcId != null) {
            sc.setParameters("data_center_id", dcId);
        }
        sc.setJoinParameters("id", "is_tag_a_rule", false);

        return search(sc, null);
    }

    @Override
    public List<Long> listClustersByHostTag(String hostTags) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        String selectStmtToListClusterIdsByHostTags = LIST_CLUSTER_IDS_FOR_HOST_TAGS;
        PreparedStatement pstmt;
        List<Long> result = new ArrayList<>();
        List<String> tags = Arrays.asList(hostTags.split(SEPARATOR));
        String selectStmtToListHostIdsByHostTags = getSelectStmtToListHostIdsByHostTags(tags);
        selectStmtToListClusterIdsByHostTags = String.format(selectStmtToListClusterIdsByHostTags, selectStmtToListHostIdsByHostTags);

        try {
            pstmt = txn.prepareStatement(selectStmtToListClusterIdsByHostTags);

            for (int i = 0; i < tags.size(); i++){
                pstmt.setString(i+1, tags.get(i));
            }

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(rs.getLong(1));
            }
            pstmt.close();
            return result;
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + selectStmtToListClusterIdsByHostTags, e);
        }
    }

    private List<Long> findHostIdsByHostTags(String hostTags){
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt;
        List<Long> result = new ArrayList<>();
        List<String> tags = Arrays.asList(hostTags.split(SEPARATOR));
        String selectStmtToListHostIdsByHostTags = getSelectStmtToListHostIdsByHostTags(tags);
        try {
            pstmt = txn.prepareStatement(selectStmtToListHostIdsByHostTags);

            for (int i = 0; i < tags.size(); i++){
                pstmt.setString(i+1, tags.get(i));
            }

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(rs.getLong(1));
            }
            pstmt.close();
            return result;
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + selectStmtToListHostIdsByHostTags, e);
        }
    }

    public List<HostVO> findHostsWithTagRuleThatMatchComputeOferringTags(String computeOfferingTags) {
        List<HostTagVO> hostTagVOList = _hostTagsDao.findHostRuleTags();
        List<HostVO> result = new ArrayList<>();
        for (HostTagVO rule: hostTagVOList) {
            if (TagAsRuleHelper.interpretTagAsRule(rule.getTag(), computeOfferingTags, HostTagsDao.hostTagRuleExecutionTimeout.value())) {
                result.add(findById(rule.getHostId()));
            }
        }

        return result;
    }

    public List<Long> findClustersThatMatchHostTagRule(String computeOfferingTags) {
        Set<Long> result = new HashSet<>();
        List<HostVO> hosts = findHostsWithTagRuleThatMatchComputeOferringTags(computeOfferingTags);
        for (HostVO host: hosts) {
            result.add(host.getClusterId());
        }
        return new ArrayList<>(result);
    }

    @Override
    public List<Long> listSsvmHostsWithPendingMigrateJobsOrderedByJobCount() {
        String query = "SELECT cel.host_id, COUNT(*) " +
                "FROM cmd_exec_log cel " +
                "JOIN host h ON cel.host_id = h.id " +
                "WHERE h.removed IS NULL " +
                "GROUP BY cel.host_id " +
                "ORDER BY 2";

        TransactionLegacy txn = TransactionLegacy.currentTxn();
        List<Long> result = new ArrayList<>();

        PreparedStatement pstmt;
        try {
            pstmt = txn.prepareAutoCloseStatement(query);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add((long) rs.getInt(1));
            }
        } catch (SQLException e) {
            logger.warn("SQLException caught while listing SSVMs with least migrate jobs.", e);
        }
        return result;
    }

    private String getSelectStmtToListHostIdsByHostTags(List<String> hostTags){
        List<String> questionMarks = new ArrayList<>();
        hostTags.forEach((tag) -> questionMarks.add("?"));
        return String.format(LIST_HOST_IDS_BY_HOST_TAGS, String.join(SEPARATOR, questionMarks), questionMarks.size());
    }

    @Override
    public List<HostVO> listHostsWithActiveVMs(long offeringId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt;
        List<HostVO> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(GET_HOSTS_OF_ACTIVE_VMS);
        try {
            pstmt = txn.prepareAutoCloseStatement(sql.toString());
            pstmt.setLong(1, offeringId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(toEntityBean(rs, false));
            }
            return result;
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + sql, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + sql, e);
        }
    }

    @Override
    public List<HostVO> listHostsByMsAndDc(long msId, long dcId) {
        SearchCriteria<HostVO> sc = ResponsibleMsDcSearch.create();
        sc.setParameters("managementServerId", msId);
        sc.setParameters("dcId", dcId);
        return listBy(sc);
    }

    @Override
    public List<HostVO> listHostsByMsDcResourceState(long msId, long dcId, List<ResourceState> excludedResourceStates) {
        QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getManagementServerId(), Op.EQ, msId);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        if (CollectionUtils.isNotEmpty(excludedResourceStates)) {
            sc.and(sc.entity().getResourceState(), Op.NIN, excludedResourceStates.toArray());
        }
        return listBy(sc.create());
    }

    @Override
    public List<HostVO> listHostsByMs(long msId) {
        SearchCriteria<HostVO> sc = ResponsibleMsSearch.create();
        sc.setParameters("managementServerId", msId);
        return listBy(sc);
    }

    @Override
    public List<HostVO> listHostsByMsResourceState(long msId, List<ResourceState> excludedResourceStates) {
        QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getManagementServerId(), Op.EQ, msId);
        if (CollectionUtils.isNotEmpty(excludedResourceStates)) {
            sc.and(sc.entity().getResourceState(), Op.NIN, excludedResourceStates.toArray());
        }
        return listBy(sc.create());
    }

    @Override
    public int countHostsByMsResourceStateTypeAndHypervisorType(long msId,
                                                                List<ResourceState> excludedResourceStates,
                                                                List<Type> hostTypes,
                                                                List<HypervisorType> hypervisorTypes) {
        QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getManagementServerId(), Op.EQ, msId);
        if (CollectionUtils.isNotEmpty(excludedResourceStates)) {
            sc.and(sc.entity().getResourceState(), Op.NIN, excludedResourceStates.toArray());
        }
        if (CollectionUtils.isNotEmpty(hostTypes)) {
            sc.and(sc.entity().getType(), Op.IN, hostTypes.toArray());
        }
        if (CollectionUtils.isNotEmpty(hypervisorTypes)) {
            sc.and(sc.entity().getHypervisorType(), Op.IN, hypervisorTypes.toArray());
        }
        return getCount(sc.create());
    }

    @Override
    public List<String> listByMs(long msId) {
        SearchCriteria<String> sc = ResponsibleMsIdSearch.create();
        sc.addAnd("managementServerId", SearchCriteria.Op.EQ, msId);
        return customSearch(sc, null);
    }

    @Override
    public List<String> listByLastMs(long msId) {
        SearchCriteria<String> sc = LastMsIdSearch.create();
        sc.addAnd("lastManagementServerId", SearchCriteria.Op.EQ, msId);
        return customSearch(sc, null);
    }

    @Override
    public List<String> listOrderedHostsHypervisorVersionsInDatacenter(long datacenterId, HypervisorType hypervisorType) {
        PreparedStatement pstmt;
        List<String> result = new ArrayList<>();
        try {
            TransactionLegacy txn = TransactionLegacy.currentTxn();
            pstmt = txn.prepareAutoCloseStatement(GET_ORDERED_HW_VERSIONS_IN_DC);
            pstmt.setString(1, Objects.toString(hypervisorType));
            pstmt.setLong(2, datacenterId);
            ResultSet resultSet = pstmt.executeQuery();
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
        } catch (SQLException e) {
            logger.error("Error trying to obtain hypervisor version on datacenter", e);
        }
        return result;
    }

    @Override
    public List<HostVO> listByType(Host.Type type) {
        SearchCriteria<HostVO> sc = TypeSearch.create();
        sc.setParameters("type", type);
        return listBy(sc);
    }

    String sqlFindHostInZoneToExecuteCommand = "Select  id from host "
            + " where type = 'Routing' and hypervisor_type = ? and data_center_id = ? and status = 'Up' "
            + " and resource_state = '%s' "
            + " ORDER by rand() limit 1";

    @Override
    public HostVO findHostInZoneToExecuteCommand(long zoneId, HypervisorType hypervisorType) {
        try (TransactionLegacy tx = TransactionLegacy.currentTxn()) {
            String sql = createSqlFindHostToExecuteCommand(false);
            ResultSet rs = executeSqlGetResultsetForMethodFindHostInZoneToExecuteCommand(hypervisorType, zoneId, tx, sql);
            if (rs.next()) {
                return findById(rs.getLong("id"));
            }
            sql = createSqlFindHostToExecuteCommand(true);
            rs = executeSqlGetResultsetForMethodFindHostInZoneToExecuteCommand(hypervisorType, zoneId, tx, sql);
            if (!rs.next()) {
                throw new CloudRuntimeException(String.format("Could not find a host in zone [zoneId=%d] to operate on. ", zoneId));
            }
            return findById(rs.getLong("id"));
        } catch (SQLException e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public List<HostVO> listAllHostsUpByZoneAndHypervisor(long zoneId, HypervisorType hypervisorType) {
        return listByDataCenterIdAndHypervisorType(zoneId, hypervisorType)
                .stream()
                .filter(x -> x.getStatus().equals(Status.Up) &&
                        x.getType() == Host.Type.Routing)
                .collect(Collectors.toList());
    }

    @Override
    public List<HostVO> listByHostCapability(Type type, Long clusterId, Long podId, long dcId, String hostCapabilty) {
        SearchBuilder<DetailVO> hostCapabilitySearch = _detailsDao.createSearchBuilder();
        DetailVO tagEntity = hostCapabilitySearch.entity();
        hostCapabilitySearch.and("capability", tagEntity.getName(), SearchCriteria.Op.EQ);
        hostCapabilitySearch.and("value", tagEntity.getValue(), SearchCriteria.Op.EQ);

        SearchBuilder<HostVO> hostSearch = createSearchBuilder();
        HostVO entity = hostSearch.entity();
        hostSearch.and("type", entity.getType(), SearchCriteria.Op.EQ);
        hostSearch.and("pod", entity.getPodId(), SearchCriteria.Op.EQ);
        hostSearch.and("dc", entity.getDataCenterId(), SearchCriteria.Op.EQ);
        hostSearch.and("cluster", entity.getClusterId(), SearchCriteria.Op.EQ);
        hostSearch.and("status", entity.getStatus(), SearchCriteria.Op.EQ);
        hostSearch.and("resourceState", entity.getResourceState(), SearchCriteria.Op.EQ);
        hostSearch.join("hostCapabilitySearch", hostCapabilitySearch, entity.getId(), tagEntity.getHostId(), JoinBuilder.JoinType.INNER);

        SearchCriteria<HostVO> sc = hostSearch.create();
        sc.setJoinParameters("hostCapabilitySearch", "value", Boolean.toString(true));
        sc.setJoinParameters("hostCapabilitySearch", "capability", hostCapabilty);
        sc.setParameters("type", type.toString());
        if (podId != null) {
            sc.setParameters("pod", podId);
        }
        if (clusterId != null) {
            sc.setParameters("cluster", clusterId);
        }
        sc.setParameters("dc", dcId);
        sc.setParameters("status", Status.Up.toString());
        sc.setParameters("resourceState", ResourceState.Enabled.toString());

        return listBy(sc);
    }

    @Override
    public List<HostVO> listByClusterHypervisorTypeAndHostCapability(Long clusterId, HypervisorType hypervisorType, String hostCapabilty) {
        SearchBuilder<DetailVO> hostCapabilitySearch = _detailsDao.createSearchBuilder();
        DetailVO tagEntity = hostCapabilitySearch.entity();
        hostCapabilitySearch.and("capability", tagEntity.getName(), SearchCriteria.Op.EQ);
        hostCapabilitySearch.and("value", tagEntity.getValue(), SearchCriteria.Op.EQ);

        SearchBuilder<HostVO> hostSearch = createSearchBuilder();
        HostVO entity = hostSearch.entity();
        hostSearch.and("clusterId", entity.getClusterId(), SearchCriteria.Op.EQ);
        hostSearch.and("hypervisor", entity.getHypervisorType(), SearchCriteria.Op.EQ);
        hostSearch.and("type", entity.getType(), SearchCriteria.Op.EQ);
        hostSearch.and("status", entity.getStatus(), SearchCriteria.Op.EQ);
        hostSearch.and("resourceState", entity.getResourceState(), SearchCriteria.Op.EQ);
        hostSearch.join("hostCapabilitySearch", hostCapabilitySearch, entity.getId(), tagEntity.getHostId(), JoinBuilder.JoinType.INNER);

        SearchCriteria<HostVO> sc = hostSearch.create();
        sc.setJoinParameters("hostCapabilitySearch", "value", Boolean.toString(true));
        sc.setJoinParameters("hostCapabilitySearch", "capability", hostCapabilty);

        sc.setParameters("clusterId", clusterId);
        sc.setParameters("hypervisor", hypervisorType);
        sc.setParameters("type", Type.Routing);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);
        return listBy(sc);
    }

    @Override
    public List<HostVO> listByClusterAndHypervisorType(long clusterId, HypervisorType hypervisorType) {
        SearchCriteria<HostVO> sc = ClusterHypervisorSearch.create();
        sc.setParameters("clusterId", clusterId);
        sc.setParameters("hypervisor", hypervisorType);
        sc.setParameters("type", Type.Routing);
        sc.setParameters("status", Status.Up);
        sc.setParameters("resourceState", ResourceState.Enabled);
        return listBy(sc);
    }

    @Override
    public HostVO findByName(String name) {
        SearchCriteria<HostVO> sc = NameSearch.create();
        sc.setParameters("name", name);
        return findOneBy(sc);
    }

    @Override
    public HostVO findHostByHypervisorTypeAndVersion(HypervisorType hypervisorType, String hypervisorVersion) {
        SearchCriteria<HostVO> sc = hostHypervisorTypeAndVersionSearch.create();
        sc.setParameters("hypervisorType", hypervisorType);
        sc.setParameters("hypervisorVersion", hypervisorVersion);
        sc.setParameters("type", Host.Type.Routing);
        sc.setParameters("status", Status.Up);
        return findOneBy(sc);
    }

    private ResultSet executeSqlGetResultsetForMethodFindHostInZoneToExecuteCommand(HypervisorType hypervisorType, long zoneId, TransactionLegacy tx, String sql) throws SQLException {
        PreparedStatement pstmt = tx.prepareAutoCloseStatement(sql);
        pstmt.setString(1, Objects.toString(hypervisorType));
        pstmt.setLong(2, zoneId);
        return pstmt.executeQuery();
    }

    private String createSqlFindHostToExecuteCommand(boolean useDisabledHosts) {
        String hostResourceStatus = "Enabled";
        if (useDisabledHosts) {
            hostResourceStatus = "Disabled";
        }
        return String.format(sqlFindHostInZoneToExecuteCommand, hostResourceStatus);
    }

    @Override
    public boolean isHostUp(long hostId) {
        GenericSearchBuilder<HostVO, Status> sb = createSearchBuilder(Status.class);
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.selectFields(sb.entity().getStatus());
        SearchCriteria<Status> sc = sb.create();
        sc.setParameters("id", hostId);
        List<Status> statuses = customSearch(sc, null);
        return CollectionUtils.isNotEmpty(statuses) && Status.Up.equals(statuses.get(0));
    }

    @Override
    public List<Long> findHostIdsByZoneClusterResourceStateTypeAndHypervisorType(final Long zoneId,
                final Long clusterId, final Long managementServerId,
                final List<ResourceState> resourceStates, final List<Type> types,
                final List<Hypervisor.HypervisorType> hypervisorTypes) {
        GenericSearchBuilder<HostVO, Long> sb = createSearchBuilder(Long.class);
        sb.selectFields(sb.entity().getId());
        sb.and("zoneId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("clusterId", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
        sb.and("msId", sb.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        sb.and("resourceState", sb.entity().getResourceState(), SearchCriteria.Op.IN);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.IN);
        if (CollectionUtils.isNotEmpty(hypervisorTypes)) {
            sb.and().op(sb.entity().getHypervisorType(), SearchCriteria.Op.NULL);
            sb.or("hypervisorTypes", sb.entity().getHypervisorType(), SearchCriteria.Op.IN);
            sb.cp();
        }
        sb.done();
        SearchCriteria<Long> sc = sb.create();
        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }
        if (clusterId != null) {
            sc.setParameters("clusterId", clusterId);
        }
        if (managementServerId != null) {
            sc.setParameters("msId", managementServerId);
        }
        if (CollectionUtils.isNotEmpty(hypervisorTypes)) {
            sc.setParameters("hypervisorTypes", hypervisorTypes.toArray());
        }
        sc.setParameters("resourceState", resourceStates.toArray());
        sc.setParameters("type", types.toArray());
        return customSearch(sc, null);
    }

    @Override
    public List<HypervisorType> listDistinctHypervisorTypes(final Long zoneId) {
        GenericSearchBuilder<HostVO, String> sb = createSearchBuilder(String.class);
        sb.and("zoneId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.select(null, Func.DISTINCT, sb.entity().getHypervisorType());
        sb.done();
        SearchCriteria<String> sc = sb.create();
        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }
        sc.setParameters("type", Type.Routing);
        List<String> hypervisorString = customSearch(sc, null);
        return hypervisorString.stream().map(HypervisorType::getType).collect(Collectors.toList());
    }

    @Override
    public List<Pair<HypervisorType, CPU.CPUArch>> listDistinctHypervisorArchTypes(final Long zoneId) {
        SearchBuilder<HostVO> sb = createSearchBuilder();
        sb.select(null, Func.DISTINCT_PAIR, sb.entity().getHypervisorType(), sb.entity().getArch());
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.and("zoneId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.done();
        SearchCriteria<HostVO> sc = sb.create();
        sc.setParameters("type", Type.Routing);
        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }
        final List<HostVO> hosts = search(sc, null);
        return hosts.stream()
                .map(h -> new Pair<>(h.getHypervisorType(), h.getArch()))
                .collect(Collectors.toList());
    }

    @Override
    public List<CPU.CPUArch> listDistinctArchTypes(final Long clusterId) {
        GenericSearchBuilder<HostVO, String> sb = createSearchBuilder(String.class);
        sb.and("clusterId", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.select(null, Func.DISTINCT, sb.entity().getArch());
        sb.done();
        SearchCriteria<String> sc = sb.create();
        if (clusterId != null) {
            sc.setParameters("clusterId", clusterId);
        }
        sc.setParameters("type", Type.Routing);
        List<String> archStrings = customSearch(sc, null);
        return archStrings.stream().map(CPU.CPUArch::fromType).collect(Collectors.toList());
    }

    @Override
    public List<HostVO> listByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        SearchCriteria<HostVO> sc = IdsSearch.create();
        sc.setParameters("id", ids.toArray());
        return search(sc, null);
    }


    @Override
    public Long findClusterIdByVolumeInfo(VolumeInfo volumeInfo) {
        VirtualMachine virtualMachine = volumeInfo.getAttachedVM();
        if (virtualMachine == null) {
            return null;
        }

        Long hostId = ObjectUtils.defaultIfNull(virtualMachine.getHostId(), virtualMachine.getLastHostId());
        Host host = findById(hostId);

        if (host == null) {
            logger.warn(String.format("VM [%s] has null host on DB, either this VM was never started, or there is some inconsistency on the DB.", virtualMachine.getUuid()));
            return null;
        }

        return host.getClusterId();
    }


    @Override
    public List<String> listDistinctStorageAccessGroups(String name, String keyword) {
        GenericSearchBuilder<HostVO, String> searchBuilder = createSearchBuilder(String.class);

        searchBuilder.select(null, SearchCriteria.Func.DISTINCT, searchBuilder.entity().getStorageAccessGroups());
        if (name != null) {
            searchBuilder.and().op("storageAccessGroupExact", searchBuilder.entity().getStorageAccessGroups(), Op.EQ);
            searchBuilder.or("storageAccessGroupPrefix", searchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
            searchBuilder.or("storageAccessGroupSuffix", searchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
            searchBuilder.or("storageAccessGroupMiddle", searchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
            searchBuilder.cp();
        }
        if (keyword != null) {
            searchBuilder.and("keyword", searchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
        }
        searchBuilder.done();

        SearchCriteria<String> sc = searchBuilder.create();
        if (name != null) {
            sc.setParameters("storageAccessGroupExact", name);
            sc.setParameters("storageAccessGroupPrefix", name + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + name);
            sc.setParameters("storageAccessGroupMiddle", "%," + name + ",%");
        }

        if (keyword != null) {
            sc.setParameters("keyword", "%" + keyword + "%");
        }

        return customSearch(sc, null);
    }
}
