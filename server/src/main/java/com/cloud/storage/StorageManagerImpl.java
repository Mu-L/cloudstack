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
package com.cloud.storage;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.dao.StoragePoolAndAccessGroupMapDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.storage.CancelPrimaryStorageMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.storage.ChangeStoragePoolScopeCmd;
import org.apache.cloudstack.api.command.admin.storage.ConfigureStorageAccessCmd;
import org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.CreateStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeletePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.SyncStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.CreateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.RemoveSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.UpdateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.cloudstack.engine.subsystem.api.storage.ImageStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotService;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService.TemplateApiResult;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.secstorage.HeuristicVO;
import org.apache.cloudstack.secstorage.dao.SecondaryStorageHeuristicDao;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;
import org.apache.cloudstack.secstorage.heuristics.HeuristicType;
import org.apache.cloudstack.storage.command.CheckDataStoreStoragePolicyComplainceCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.SyncVolumePathAnswer;
import org.apache.cloudstack.storage.command.SyncVolumePathCommand;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreObjectDownloadDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreObjectDownloadVO;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.apache.cloudstack.storage.object.ObjectStore;
import org.apache.cloudstack.storage.object.ObjectStoreEntity;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.GetStoragePoolCapabilitiesAnswer;
import com.cloud.agent.api.GetStoragePoolCapabilitiesCommand;
import com.cloud.agent.api.GetStorageStatsAnswer;
import com.cloud.agent.api.GetStorageStatsCommand;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.manager.Commands;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.CapacityState;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.cluster.ClusterManagerListener;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.VsphereStoragePolicyVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VsphereStoragePolicyDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.DiscoveryException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceInUseException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageConflictException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.router.VirtualNetworkApplianceManager;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Grouping;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.resource.ResourceState;
import com.cloud.server.ConfigurationServer;
import com.cloud.server.ManagementServer;
import com.cloud.server.StatsCollector;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.dao.BucketDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.StoragePoolWorkDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.listener.StoragePoolMonitor;
import com.cloud.storage.listener.VolumeStateListener;
import com.cloud.template.TemplateManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.upgrade.SystemVmTemplateRegistration;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.UriUtils;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.common.collect.Sets;


@Component
public class StorageManagerImpl extends ManagerBase implements StorageManager, ClusterManagerListener, Configurable {

    protected String _name;
    @Inject
    protected AgentManager _agentMgr;
    @Inject
    protected TemplateManager _tmpltMgr;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected ConfigurationManager _configMgr;
    @Inject
    private VolumeDataStoreDao _volumeDataStoreDao;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected SnapshotDao _snapshotDao;
    @Inject
    protected StoragePoolHostDao _storagePoolHostDao;
    @Inject
    protected VMTemplatePoolDao _vmTemplatePoolDao = null;
    @Inject
    protected VMTemplateZoneDao _vmTemplateZoneDao;
    @Inject
    protected VMTemplateDao _vmTemplateDao = null;
    @Inject
    protected VMInstanceDao _vmInstanceDao;
    @Inject
    protected PrimaryDataStoreDao _storagePoolDao = null;
    @Inject
    protected StoragePoolDetailsDao _storagePoolDetailsDao;
    @Inject
    protected ImageStoreDao _imageStoreDao = null;
    @Inject
    protected ImageStoreDetailsDao _imageStoreDetailsDao = null;
    @Inject
    protected ImageStoreObjectDownloadDao _imageStoreObjectDownloadDao = null;
    @Inject
    protected SnapshotDataStoreDao _snapshotStoreDao = null;
    @Inject
    protected TemplateDataStoreDao _templateStoreDao = null;
    @Inject
    protected TemplateJoinDao _templateViewDao = null;
    @Inject
    protected VolumeDataStoreDao _volumeStoreDao = null;
    @Inject
    protected CapacityDao _capacityDao;
    @Inject
    protected CapacityManager _capacityMgr;
    @Inject
    protected DataCenterDao _dcDao = null;
    @Inject
    protected VMTemplateDao _templateDao;
    @Inject
    protected UserDao _userDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected StoragePoolWorkDao _storagePoolWorkDao;
    @Inject
    protected HypervisorGuruManager _hvGuruMgr;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    ManagementServer _msServer;
    @Inject
    VolumeService volService;
    @Inject
    VolumeDataFactory volFactory;
    @Inject
    TemplateDataFactory tmplFactory;
    @Inject
    SnapshotDataFactory snapshotFactory;
    @Inject
    ConfigurationServer _configServer;
    @Inject
    DataStoreManager _dataStoreMgr;
    @Inject
    DataStoreProviderManager _dataStoreProviderMgr;
    @Inject
    private TemplateService _imageSrv;
    @Inject
    EndPointSelector _epSelector;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    EntityManager _entityMgr;
    @Inject
    SnapshotService _snapshotService;
    @Inject
    public StorageService storageService;
    @Inject
    StoragePoolTagsDao _storagePoolTagsDao;
    @Inject
    StoragePoolAndAccessGroupMapDao _storagePoolAccessGroupMapDao;
    @Inject
    PrimaryDataStoreDao primaryStoreDao;
    @Inject
    DiskOfferingDetailsDao _diskOfferingDetailsDao;
    @Inject
    ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    VsphereStoragePolicyDao _vsphereStoragePolicyDao;
    @Inject
    private AnnotationDao annotationDao;

    @Inject
    private SecondaryStorageHeuristicDao secondaryStorageHeuristicDao;

    @Inject
    protected UserVmManager userVmManager;
    @Inject
    protected ObjectStoreDao _objectStoreDao;

    @Inject
    protected ObjectStoreDetailsDao _objectStoreDetailsDao;

    @Inject
    protected BucketDao _bucketDao;
    @Inject
    ConfigDepot configDepot;
    @Inject
    ConfigurationDao configurationDao;
    @Inject
    private ImageStoreDetailsUtil imageStoreDetailsUtil;
    @Inject
    protected HostPodDao _podDao;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    StorageManager storageManager;

    protected List<StoragePoolDiscoverer> _discoverers;

    public List<StoragePoolDiscoverer> getDiscoverers() {
        return _discoverers;
    }

    public void setDiscoverers(List<StoragePoolDiscoverer> discoverers) {
        _discoverers = discoverers;
    }

    protected GenericSearchBuilder<StoragePoolHostVO, Long> UpHostsInPoolSearch;
    protected SearchBuilder<VMInstanceVO> StoragePoolSearch;
    protected SearchBuilder<StoragePoolVO> LocalStorageSearch;

    ScheduledExecutorService _executor = null;
    int _storagePoolAcquisitionWaitSeconds = 1800; // 30 minutes
    int _downloadUrlCleanupInterval;
    int _downloadUrlExpirationInterval;
    private long _serverId;

    private final Map<String, HypervisorHostListener> hostListeners = new HashMap<>();

    private final Set<HypervisorType> zoneWidePoolSupportedHypervisorTypes = Sets.newHashSet(HypervisorType.KVM, HypervisorType.VMware,
            HypervisorType.Hyperv, HypervisorType.LXC, HypervisorType.Any, HypervisorType.Simulator);

    private static final String NFS_MOUNT_OPTIONS_INCORRECT = "An incorrect mount option was specified";

    public boolean share(VMInstanceVO vm, List<VolumeVO> vols, HostVO host, boolean cancelPreviousShare) throws StorageUnavailableException {

        // if pool is in maintenance and it is the ONLY pool available; reject
        List<VolumeVO> rootVolForGivenVm = volumeDao.findByInstanceAndType(vm.getId(), Type.ROOT);
        if (rootVolForGivenVm != null && rootVolForGivenVm.size() > 0) {
            boolean isPoolAvailable = isPoolAvailable(rootVolForGivenVm.get(0).getPoolId());
            if (!isPoolAvailable) {
                throw new StorageUnavailableException("Can not share " + vm, rootVolForGivenVm.get(0).getPoolId());
            }
        }

        // this check is done for maintenance mode for primary storage
        // if any one of the volume is unusable, we return false
        // if we return false, the allocator will try to switch to another PS if
        // available
        for (VolumeVO vol : vols) {
            if (vol.getRemoved() != null) {
                logger.warn("Volume: {} is removed, cannot share on this instance: {}", vol, vm);
                // not ok to share
                return false;
            }
        }
        // ok to share
        return true;
    }

    private boolean isPoolAvailable(Long poolId) {
        // get list of all pools
        List<StoragePoolVO> pools = _storagePoolDao.listAll();

        // if no pools or 1 pool which is in maintenance
        if (pools == null || pools.size() == 0 || (pools.size() == 1 && pools.get(0).getStatus().equals(StoragePoolStatus.Maintenance))) {
            return false;
        } else {
            return true;
        }
    }

    protected void enableDefaultDatastoreDownloadRedirectionForExistingInstallations() {
        if (!configDepot.isNewConfig(DataStoreDownloadFollowRedirects)) {
            logger.trace("{} is not a new configuration, skipping updating its value",
                    DataStoreDownloadFollowRedirects.key());
            return;
        }
        List<DataCenterVO> zones =
                _dcDao.listAll(new Filter(1));
        if (CollectionUtils.isNotEmpty(zones)) {
            logger.debug(String.format("Updating value for configuration: %s to true",
                DataStoreDownloadFollowRedirects.key()));
            configurationDao.update(DataStoreDownloadFollowRedirects.key(), "true");
        }
    }

    @Override
    public List<StoragePoolVO> ListByDataCenterHypervisor(long datacenterId, HypervisorType type) {
        List<StoragePoolVO> pools = _storagePoolDao.listByDataCenterId(datacenterId);
        List<StoragePoolVO> retPools = new ArrayList<>();
        for (StoragePoolVO pool : pools) {
            if (pool.getStatus() != StoragePoolStatus.Up) {
                continue;
            }
            if (pool.getScope() == ScopeType.ZONE) {
                if (pool.getHypervisor() != null && pool.getHypervisor() == type) {
                    retPools.add(pool);
                }
            } else {
                ClusterVO cluster = _clusterDao.findById(pool.getClusterId());
                if (type == cluster.getHypervisorType()) {
                    retPools.add(pool);
                }
            }
        }
        Collections.shuffle(retPools);
        return retPools;
    }

    @Override
    public boolean isLocalStorageActiveOnHost(Long hostId) {
        List<StoragePoolHostVO> storagePoolHostRefs = _storagePoolHostDao.listByHostId(hostId);
        for (StoragePoolHostVO storagePoolHostRef : storagePoolHostRefs) {
            StoragePoolVO primaryDataStoreVO = _storagePoolDao.findById(storagePoolHostRef.getPoolId());
            if (primaryDataStoreVO != null && (primaryDataStoreVO.getPoolType() == StoragePoolType.LVM || primaryDataStoreVO.getPoolType() == StoragePoolType.EXT)) {
                SearchBuilder<VolumeVO> volumeSB = volumeDao.createSearchBuilder();
                volumeSB.and("poolId", volumeSB.entity().getPoolId(), SearchCriteria.Op.EQ);
                volumeSB.and("removed", volumeSB.entity().getRemoved(), SearchCriteria.Op.NULL);
                volumeSB.and("state", volumeSB.entity().getState(), SearchCriteria.Op.NIN);

                SearchBuilder<VMInstanceVO> activeVmSB = _vmInstanceDao.createSearchBuilder();
                activeVmSB.and("state", activeVmSB.entity().getState(), SearchCriteria.Op.IN);
                volumeSB.join("activeVmSB", activeVmSB, volumeSB.entity().getInstanceId(), activeVmSB.entity().getId(), JoinBuilder.JoinType.INNER);

                SearchCriteria<VolumeVO> volumeSC = volumeSB.create();
                volumeSC.setParameters("poolId", primaryDataStoreVO.getId());
                volumeSC.setParameters("state", Volume.State.Expunging, Volume.State.Destroy);
                volumeSC.setJoinParameters("activeVmSB", "state", State.Starting, State.Running, State.Stopping, State.Migrating);

                List<VolumeVO> volumes = volumeDao.search(volumeSC, null);
                if (volumes.size() > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public Answer[] sendToPool(StoragePool pool, Commands cmds) throws StorageUnavailableException {
        return sendToPool(pool, null, null, cmds).second();
    }

    @Override
    public Answer sendToPool(StoragePool pool, long[] hostIdsToTryFirst, Command cmd) throws StorageUnavailableException {
        Answer[] answers = sendToPool(pool, hostIdsToTryFirst, null, new Commands(cmd)).second();
        if (answers == null) {
            return null;
        }
        return answers[0];
    }

    @Override
    public Answer sendToPool(StoragePool pool, Command cmd) throws StorageUnavailableException {
        if (cmd instanceof GetStorageStatsCommand && canPoolProvideStorageStats(pool)) {
            // Get stats from the pool directly instead of sending cmd to host
            return getStoragePoolStats(pool, (GetStorageStatsCommand) cmd);
        }

        Answer[] answers = sendToPool(pool, new Commands(cmd));
        if (answers == null) {
            return null;
        }
        return answers[0];
    }

    protected Pair<Long, Long> getStoragePoolIopsStats(PrimaryDataStoreDriver primaryStoreDriver, StoragePool pool) {
        Pair<Long, Long> result = primaryStoreDriver.getStorageIopsStats(pool);
        if (result != null) {
            return result;
        }
        Long usedIops = primaryStoreDriver.getUsedIops(pool);
        if (usedIops <= 0) {
            usedIops = null;
        }
        return new Pair<>(pool.getCapacityIops(), usedIops);
    }

    private GetStorageStatsAnswer getStoragePoolStats(StoragePool pool, GetStorageStatsCommand cmd) {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver) storeDriver;
        Pair<Long, Long> storageStats = primaryStoreDriver.getStorageStats(pool);
        if (storageStats == null) {
            return new GetStorageStatsAnswer(cmd, "Failed to get storage stats for pool: " + pool.getId());
        }
        Pair<Long, Long> iopsStats = getStoragePoolIopsStats(primaryStoreDriver, pool);
        return new GetStorageStatsAnswer(cmd, storageStats.first(), storageStats.second(),
                iopsStats.first(), iopsStats.second());
    }

    @Override
    public boolean canPoolProvideStorageStats(StoragePool pool) {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canProvideStorageStats();
    }

    @Override
    public boolean poolProvidesCustomStorageStats(StoragePool pool) {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).poolProvidesCustomStorageStats();
    }

    @Override
    public Map<String, String> getCustomStorageStats(StoragePool pool) {
        if (pool == null) {
            return null;
        }

        if (!pool.isManaged()) {
            return null;
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        if (storeDriver instanceof PrimaryDataStoreDriver) {
            return ((PrimaryDataStoreDriver)storeDriver).getCustomStorageStats(pool);
        }
        return null;
    }

    @Override
    public Answer getVolumeStats(StoragePool pool, Command cmd) {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver) storeDriver;
        HashMap<String, VolumeStatsEntry> statEntry = new HashMap<>();
        GetVolumeStatsCommand getVolumeStatsCommand = (GetVolumeStatsCommand) cmd;
        for (String volumeUuid : getVolumeStatsCommand.getVolumeUuids()) {
            Pair<Long, Long> volumeStats = primaryStoreDriver.getVolumeStats(pool, volumeUuid);
            if (volumeStats == null) {
                return new GetVolumeStatsAnswer(getVolumeStatsCommand, "Failed to get stats for volume: " + volumeUuid,
                        null);
            } else {
                VolumeStatsEntry volumeStatsEntry = new VolumeStatsEntry(volumeUuid, volumeStats.first(),
                        volumeStats.second());
                statEntry.put(volumeUuid, volumeStatsEntry);
            }
        }
        return new GetVolumeStatsAnswer(getVolumeStatsCommand, "", statEntry);
    }

    public Long chooseHostForStoragePool(StoragePoolVO poolVO, List<Long> avoidHosts, boolean sendToVmResidesOn, Long vmId) {
        if (sendToVmResidesOn) {
            if (vmId != null) {
                VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);
                if (vmInstance != null) {
                    Long hostId = vmInstance.getHostId();
                    if (hostId != null && !avoidHosts.contains(vmInstance.getHostId())) {
                        return hostId;
                    }
                }
            }
            /*
             * Can't find the vm where host resides on(vm is destroyed? or
             * volume is detached from vm), randomly choose a host to send the
             * cmd
             */
        }
        List<StoragePoolHostVO> poolHosts = _storagePoolHostDao.listByHostStatus(poolVO.getId(), Status.Up);
        Collections.shuffle(poolHosts);
        if (poolHosts != null && poolHosts.size() > 0) {
            for (StoragePoolHostVO sphvo : poolHosts) {
                if (!avoidHosts.contains(sphvo.getHostId())) {
                    return sphvo.getHostId();
                }
            }
        }
        return null;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) {
        Map<String, String> configs = _configDao.getConfiguration("management-server", params);

        _storagePoolAcquisitionWaitSeconds = NumbersUtil.parseInt(configs.get("pool.acquisition.wait.seconds"), 1800);
        logger.info("pool.acquisition.wait.seconds is configured as " + _storagePoolAcquisitionWaitSeconds + " seconds");

        _agentMgr.registerForHostEvents(new StoragePoolMonitor(this, _storagePoolDao, _storagePoolHostDao, _dataStoreProviderMgr), true, false, true);

        logger.info("Storage cleanup enabled: " + StorageCleanupEnabled.value() + ", interval: " + StorageCleanupInterval.value() + ", delay: " + StorageCleanupDelay.value()
        + ", template cleanup enabled: " + TemplateCleanupEnabled.value());

        String cleanupInterval = configs.get("extract.url.cleanup.interval");
        _downloadUrlCleanupInterval = NumbersUtil.parseInt(cleanupInterval, 7200);

        String urlExpirationInterval = configs.get("extract.url.expiration.interval");
        _downloadUrlExpirationInterval = NumbersUtil.parseInt(urlExpirationInterval, 14400);

        String workers = configs.get("expunge.workers");
        int wrks = NumbersUtil.parseInt(workers, 10);
        _executor = Executors.newScheduledThreadPool(wrks, new NamedThreadFactory("StorageManager-Scavenger"));

        _agentMgr.registerForHostEvents(ComponentContext.inject(LocalStoragePoolListener.class), true, false, false);

        _serverId = _msServer.getId();

        UpHostsInPoolSearch = _storagePoolHostDao.createSearchBuilder(Long.class);
        UpHostsInPoolSearch.selectFields(UpHostsInPoolSearch.entity().getHostId());
        SearchBuilder<HostVO> hostSearch = _hostDao.createSearchBuilder();
        hostSearch.and("status", hostSearch.entity().getStatus(), Op.EQ);
        hostSearch.and("resourceState", hostSearch.entity().getResourceState(), Op.EQ);
        UpHostsInPoolSearch.join("hosts", hostSearch, hostSearch.entity().getId(), UpHostsInPoolSearch.entity().getHostId(), JoinType.INNER);
        UpHostsInPoolSearch.and("pool", UpHostsInPoolSearch.entity().getPoolId(), Op.EQ);
        UpHostsInPoolSearch.done();

        StoragePoolSearch = _vmInstanceDao.createSearchBuilder();

        SearchBuilder<VolumeVO> volumeSearch = volumeDao.createSearchBuilder();
        volumeSearch.and("volumeType", volumeSearch.entity().getVolumeType(), SearchCriteria.Op.EQ);
        volumeSearch.and("poolId", volumeSearch.entity().getPoolId(), SearchCriteria.Op.EQ);
        volumeSearch.and("state", volumeSearch.entity().getState(), SearchCriteria.Op.EQ);
        StoragePoolSearch.join("vmVolume", volumeSearch, volumeSearch.entity().getInstanceId(), StoragePoolSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        StoragePoolSearch.done();

        LocalStorageSearch = _storagePoolDao.createSearchBuilder();
        SearchBuilder<StoragePoolHostVO> storageHostSearch = _storagePoolHostDao.createSearchBuilder();
        storageHostSearch.and("hostId", storageHostSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        LocalStorageSearch.join("poolHost", storageHostSearch, storageHostSearch.entity().getPoolId(), LocalStorageSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        LocalStorageSearch.and("type", LocalStorageSearch.entity().getPoolType(), SearchCriteria.Op.IN);
        LocalStorageSearch.done();

        Volume.State.getStateMachine().registerListener(new VolumeStateListener(_configDao, _vmInstanceDao));

        return true;
    }

    @Override
    public String getStoragePoolTags(long poolId) {
        return com.cloud.utils.StringUtils.listToCsvTags(getStoragePoolTagList(poolId));
    }

    @Override
    public List<String> getStoragePoolTagList(long poolId) {
        return _storagePoolDao.searchForStoragePoolTags(poolId);
    }

    @Override
    public boolean start() {
        if (StorageCleanupEnabled.value()) {
            Random generator = new Random();
            int initialDelay = generator.nextInt(StorageCleanupInterval.value());
            _executor.scheduleWithFixedDelay(new StorageGarbageCollector(), initialDelay, StorageCleanupInterval.value(), TimeUnit.SECONDS);
        } else {
            logger.debug("Storage cleanup is not enabled, so the storage cleanup thread is not being scheduled.");
        }

        _executor.scheduleWithFixedDelay(new DownloadURLGarbageCollector(), _downloadUrlCleanupInterval, _downloadUrlCleanupInterval, TimeUnit.SECONDS);
        enableDefaultDatastoreDownloadRedirectionForExistingInstallations();
        return true;
    }

    @Override
    public boolean stop() {
        if (StorageCleanupEnabled.value()) {
            _executor.shutdown();
        }
        return true;
    }

    protected String getValidatedPareForLocalStorage(Object obj, String paramName) {
        String result = obj == null ? null : obj.toString();
        if (StringUtils.isEmpty(result)) {
            throw new InvalidParameterValueException(String.format("Invalid %s provided", paramName));
        }
        return result;
    }

    protected DataStore createLocalStorage(Map<String, Object> poolInfos) throws ConnectionException{
        Object existingUuid = poolInfos.get("uuid");
        if( existingUuid == null ){
            poolInfos.put("uuid", UUID.randomUUID().toString());
        }
        String hostAddress = getValidatedPareForLocalStorage(poolInfos.get("host"), "host");
        String hostPath = getValidatedPareForLocalStorage(poolInfos.get("hostPath"), "path");
        Host host = _hostDao.findByName(hostAddress);

        if( host == null ) {
            host = _hostDao.findByIp(hostAddress);

            if( host == null ) {
                host = _hostDao.findByPublicIp(hostAddress);

                if( host == null ) {
                    throw new InvalidParameterValueException(String.format("host %s not found",hostAddress));
                }
             }
         }

        long capacityBytes = poolInfos.get("capacityBytes") != null ? Long.parseLong(poolInfos.get("capacityBytes").toString()) : 0;

        StoragePoolInfo pInfo = new StoragePoolInfo(poolInfos.get("uuid").toString(),
                                                    host.getPrivateIpAddress(),
                                                    hostPath,
                                                    hostPath,
                                                    StoragePoolType.Filesystem,
                                                    capacityBytes,
                                                    0,
                                                    (Map<String,String>)poolInfos.get("details"),
                                                    poolInfos.get("name").toString());

        return createLocalStorage(host, pInfo);
    }

    @DB
    @Override
    public DataStore createLocalStorage(Host host, StoragePoolInfo pInfo) throws ConnectionException {
        DataCenterVO dc = _dcDao.findById(host.getDataCenterId());
        if (dc == null) {
            return null;
        }
        boolean useLocalStorageForSystemVM = false;
        Boolean isLocal = ConfigurationManagerImpl.SystemVMUseLocalStorage.valueIn(dc.getId());
        if (isLocal != null) {
            useLocalStorageForSystemVM = isLocal.booleanValue();
        }
        if (!(dc.isLocalStorageEnabled() || useLocalStorageForSystemVM)) {
            return null;
        }
        DataStore store = null;
        DataStoreProvider provider = _dataStoreProviderMgr.getDefaultPrimaryDataStoreProvider();
        DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
        try {
            String hostAddress = pInfo.getHost();
            if (host.getHypervisorType() == Hypervisor.HypervisorType.VMware) {
                hostAddress = "VMFS datastore: " + pInfo.getHostPath();
            }
            StoragePoolVO pool = _storagePoolDao.findPoolByHostPath(host.getDataCenterId(), host.getPodId(), hostAddress, pInfo.getHostPath(), pInfo.getUuid());
            if (pool == null && host.getHypervisorType() == HypervisorType.VMware) {
                // perform run-time upgrade. In versions prior to 2.2.12, there
                // is a bug that we don't save local datastore info (host path
                // is empty), this will cause us
                // not able to distinguish multiple local datastores that may be
                // available on the host, to support smooth migration, we
                // need to perform runtime upgrade here
                if (pInfo.getHostPath().length() > 0) {
                    pool = _storagePoolDao.findPoolByHostPath(host.getDataCenterId(), host.getPodId(), hostAddress, "", pInfo.getUuid());
                }
            }
            if (pool == null) {
                //the path can be different, but if they have the same uuid, assume they are the same storage
                pool = _storagePoolDao.findPoolByHostPath(host.getDataCenterId(), host.getPodId(), hostAddress, null, pInfo.getUuid());
                if (pool != null) {
                    logger.debug("Found a storage pool: " + pInfo.getUuid() + ", but with different hostpath " + pInfo.getHostPath() + ", still treat it as the same pool");
                }
            }

            if (pool == null) {
                Map<String, Object> params = new HashMap<>();
                String name = pInfo.getName() != null ? pInfo.getName() : createLocalStoragePoolName(host, pInfo);
                params.put("zoneId", host.getDataCenterId());
                params.put("clusterId", host.getClusterId());
                params.put("podId", host.getPodId());
                params.put("hypervisorType", host.getHypervisorType());
                params.put("name", name);
                params.put("localStorage", true);
                params.put("details", pInfo.getDetails());
                params.put("uuid", pInfo.getUuid());
                params.put("providerName", provider.getName());
                params.put("scheme", pInfo.getPoolType().toString());
                params.put("host", pInfo.getHost());
                params.put("hostPath", pInfo.getHostPath());

                store = lifeCycle.initialize(params);
            } else {
                store = _dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
            }

            pool = _storagePoolDao.findById(store.getId());
            if (pool.getStatus() != StoragePoolStatus.Maintenance && pool.getStatus() != StoragePoolStatus.Removed) {
                HostScope scope = new HostScope(host.getId(), host.getClusterId(), host.getDataCenterId());
                lifeCycle.attachHost(store, scope, pInfo);
            }

        } catch (Exception e) {
            logger.warn("Unable to setup the local storage pool for {}", host, e);
            try {
                if (store != null) {
                    logger.debug("Trying to delete storage pool entry if exists {}", store);
                    lifeCycle.deleteDataStore(store);
                }
            } catch (Exception ex) {
                logger.debug("Failed to clean up local storage pool: {}", ex.getMessage());
            }
            throw new ConnectionException(true, "Unable to setup the local storage pool for " + host, e);
        }

        return _dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Primary);
    }

    /**
     * Creates the local storage pool name.
     * The name will follow the pattern: <hostname>-local-<firstBlockOfUuid>
     */
    protected String createLocalStoragePoolName(Host host, StoragePoolInfo storagePoolInformation) {
        return String.format("%s-%s-%s", StringUtils.trim(host.getName()), "local", storagePoolInformation.getUuid().split("-")[0]);
    }

    protected void checkNfsMountOptions(String nfsMountOpts) throws InvalidParameterValueException {
        String[] options = nfsMountOpts.replaceAll("\\s", "").split(",");
        Map<String, String> optionsMap = new HashMap<>();
        for (String option : options) {
            String[] keyValue = option.split("=");
            if (keyValue.length > 2) {
                throw new InvalidParameterValueException("Invalid value for NFS option " + keyValue[0]);
            }
            if (optionsMap.containsKey(keyValue[0])) {
                throw new InvalidParameterValueException("Duplicate NFS option values found for option " + keyValue[0]);
            }
            optionsMap.put(keyValue[0], null);
        }
    }

    protected void checkNFSMountOptionsForCreate(Map<String, String> details, HypervisorType hypervisorType, String scheme) throws InvalidParameterValueException {
        if (!details.containsKey(ApiConstants.NFS_MOUNT_OPTIONS)) {
            return;
        }
        if (!hypervisorType.equals(HypervisorType.KVM) && !hypervisorType.equals(HypervisorType.Simulator)) {
            throw new InvalidParameterValueException("NFS options can not be set for the hypervisor type " + hypervisorType);
        }
        if (!"nfs".equals(scheme)) {
            throw new InvalidParameterValueException("NFS options can only be set on pool type " + StoragePoolType.NetworkFilesystem);
        }
        checkNfsMountOptions(details.get(ApiConstants.NFS_MOUNT_OPTIONS));
    }

    protected void checkNFSMountOptionsForUpdate(Map<String, String> details, StoragePoolVO pool, Long accountId) throws InvalidParameterValueException {
        if (!details.containsKey(ApiConstants.NFS_MOUNT_OPTIONS)) {
            return;
        }
        if (!_accountMgr.isRootAdmin(accountId)) {
            throw new PermissionDeniedException("Only root admin can modify nfs options");
        }
        if (!pool.getHypervisor().equals(HypervisorType.KVM) && !pool.getHypervisor().equals((HypervisorType.Simulator))) {
            throw new InvalidParameterValueException("NFS options can only be set for the hypervisor type " + HypervisorType.KVM);
        }
        if (!pool.getPoolType().equals(StoragePoolType.NetworkFilesystem)) {
            throw new InvalidParameterValueException("NFS options can only be set on pool type " + StoragePoolType.NetworkFilesystem);
        }
        if (!pool.isInMaintenance()) {
            throw new InvalidParameterValueException("The storage pool should be in maintenance mode to edit nfs options");
        }
        checkNfsMountOptions(details.get(ApiConstants.NFS_MOUNT_OPTIONS));
    }

    @Override
    public PrimaryDataStoreInfo createPool(CreateStoragePoolCmd cmd) throws ResourceInUseException, IllegalArgumentException, UnknownHostException, ResourceUnavailableException {
        String providerName = cmd.getStorageProviderName();
        Map<String,String> uriParams = extractUriParamsAsMap(cmd.getUrl());
        boolean isFileScheme = "file".equals(uriParams.get("scheme"));
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            storeProvider = _dataStoreProviderMgr.getDefaultPrimaryDataStoreProvider();
            if (storeProvider == null) {
                throw new InvalidParameterValueException("can't find storage provider: " + providerName);
            }
        }

        Long clusterId = cmd.getClusterId();
        Long podId = cmd.getPodId();
        Long zoneId = cmd.getZoneId();

        ScopeType scopeType = ScopeType.CLUSTER;
        if (isFileScheme) {
            scopeType = ScopeType.HOST;
        }
        String scope = cmd.getScope();
        if (scope != null) {
            try {
                scopeType = Enum.valueOf(ScopeType.class, scope.toUpperCase());
            } catch (Exception e) {
                throw new InvalidParameterValueException("invalid scope for pool " + scope);
            }
        }

        if (scopeType == ScopeType.CLUSTER && clusterId == null) {
            throw new InvalidParameterValueException("cluster id can't be null, if scope is cluster");
        } else if (scopeType == ScopeType.ZONE && zoneId == null) {
            throw new InvalidParameterValueException("zone id can't be null, if scope is zone");
        }

        HypervisorType hypervisorType = HypervisorType.KVM;
        if (scopeType == ScopeType.ZONE) {
            // ignore passed clusterId and podId
            clusterId = null;
            podId = null;
            String hypervisor = cmd.getHypervisor();
            if (hypervisor != null) {
                try {
                    hypervisorType = HypervisorType.getType(hypervisor);
                } catch (Exception e) {
                    throw new InvalidParameterValueException("invalid hypervisor type " + hypervisor);
                }
            } else {
                throw new InvalidParameterValueException("Missing parameter hypervisor. Hypervisor type is required to create zone wide primary storage.");
            }

            if (!zoneWidePoolSupportedHypervisorTypes.contains(hypervisorType)) {
                throw new InvalidParameterValueException("Zone wide storage pool is not supported for hypervisor type " + hypervisor);
            }
        } else {
            ClusterVO clusterVO = _clusterDao.findById(clusterId);
            hypervisorType = clusterVO.getHypervisorType();
        }

        Map<String, String> details = extractApiParamAsMap(cmd.getDetails());
        checkNFSMountOptionsForCreate(details, hypervisorType, uriParams.get("scheme"));

        DataCenterVO zone = _dcDao.findById(cmd.getZoneId());
        if (zone == null) {
            throw new InvalidParameterValueException("unable to find zone by id " + zoneId);
        }
        // Check if zone is disabled
        Account account = CallContext.current().getCallingAccount();
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(account.getId())) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, Zone is currently disabled: %s", zone));
        }

        Map<String, Object> params = new HashMap<>();
        params.put("zoneId", zone.getId());
        params.put("clusterId", clusterId);
        params.put("podId", podId);
        params.put("hypervisorType", hypervisorType);
        params.put("url", cmd.getUrl());
        params.put("tags", cmd.getTags());
        params.put(ApiConstants.STORAGE_ACCESS_GROUPS, cmd.getStorageAccessGroups());
        params.put("isTagARule", cmd.isTagARule());
        params.put("name", cmd.getStoragePoolName());
        params.put("details", details);
        params.put("providerName", storeProvider.getName());
        params.put("managed", cmd.isManaged());
        params.put("capacityBytes", cmd.getCapacityBytes());
        params.put("capacityIops", cmd.getCapacityIops());
        if (MapUtils.isNotEmpty(uriParams)) {
            params.putAll(uriParams);
        }

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();
        DataStore store = null;
        try {
            if (isFileScheme) {
                store = createLocalStorage(params);
            } else {
                store = lifeCycle.initialize(params);
            }
            if (scopeType == ScopeType.CLUSTER) {
                ClusterScope clusterScope = new ClusterScope(clusterId, podId, zoneId);
                lifeCycle.attachCluster(store, clusterScope);
            } else if (scopeType == ScopeType.ZONE) {
                ZoneScope zoneScope = new ZoneScope(zoneId);
                lifeCycle.attachZone(store, zoneScope, hypervisorType);
            }
        } catch (Exception e) {
            logger.debug("Failed to add data store: " + e.getMessage(), e);
            try {
                // clean up the db, just absorb the exception thrown in deletion with error logged, so that user can get error for adding data store
                // not deleting data store.
                if (store != null) {
                    lifeCycle.deleteDataStore(store);
                }
            } catch (Exception ex) {
                logger.debug("Failed to clean up storage pool: " + ex.getMessage());
            }
            throw new CloudRuntimeException("Failed to add data store: " + e.getMessage(), e);
        }

        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Primary);
    }

    protected Map<String,String> extractUriParamsAsMap(String url) {
        Map<String,String> uriParams = new HashMap<>();
        UriUtils.UriInfo uriInfo;
        try {
            uriInfo = UriUtils.getUriInfo(url);
        } catch (CloudRuntimeException cre) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("URI validation for url: %s failed, returning empty uri params", url));
            }
            return uriParams;
        }

        String scheme = uriInfo.getScheme();
        String storageHost = uriInfo.getStorageHost();
        String storagePath = uriInfo.getStoragePath();
        if (scheme == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Scheme for url: %s is not found, returning empty uri params", url));
            }
            return uriParams;
        }
        boolean isHostOrPathBlank = StringUtils.isAnyBlank(storagePath, storageHost);
        if (scheme.equalsIgnoreCase("nfs")) {
            if (isHostOrPathBlank) {
                throw new InvalidParameterValueException("host or path is null, should be nfs://hostname/path");
            }
        } else if (scheme.equalsIgnoreCase("cifs")) {
            // Don't validate against a URI encoded URI.
            try {
                URI cifsUri = new URI(url);
                String warnMsg = UriUtils.getCifsUriParametersProblems(cifsUri);
                if (warnMsg != null) {
                    throw new InvalidParameterValueException(warnMsg);
                }
            } catch (URISyntaxException e) {
                throw new InvalidParameterValueException(url + " is not a valid uri");
            }
        } else if (scheme.equalsIgnoreCase("sharedMountPoint")) {
            if (storagePath == null) {
                throw new InvalidParameterValueException("host or path is null, should be sharedmountpoint://localhost/path");
            }
        } else if (scheme.equalsIgnoreCase("rbd")) {
            if (storagePath == null) {
                throw new InvalidParameterValueException("host or path is null, should be rbd://hostname/pool");
            }
        } else if (scheme.equalsIgnoreCase("gluster")) {
            if (isHostOrPathBlank) {
                throw new InvalidParameterValueException("host or path is null, should be gluster://hostname/volume");
            }
        }

        String hostPath = null;
        try {
            hostPath = URLDecoder.decode(storagePath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.error("[ignored] we are on a platform not supporting \"UTF-8\"!?!", e);
        }
        if (hostPath == null) { // if decoding fails, use getPath() anyway
            hostPath = storagePath;
        }

        uriParams.put("scheme", scheme);
        uriParams.put("host", storageHost);
        uriParams.put("hostPath", hostPath);
        uriParams.put("userInfo", uriInfo.getUserInfo());
        if (uriInfo.getPort() > 0) {
            uriParams.put("port", uriInfo.getPort() + "");
        }
        return uriParams;
    }

    private Map<String, String> extractApiParamAsMap(Map ds) {
        Map<String, String> details = new HashMap<>();
        if (ds != null) {
            Collection detailsCollection = ds.values();
            Iterator it = detailsCollection.iterator();
            while (it.hasNext()) {
                HashMap d = (HashMap)it.next();
                Iterator it2 = d.entrySet().iterator();
                while (it2.hasNext()) {
                    Map.Entry entry = (Map.Entry)it2.next();
                    details.put((String)entry.getKey(), (String)entry.getValue());
                }
            }
        }
        return details;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DISABLE_PRIMARY_STORAGE, eventDescription = "disable storage pool")
    public StoragePool disablePrimaryStoragePool(Long id) {
        StoragePoolVO primaryStorage = _storagePoolDao.findById(id);
        if (primaryStorage == null) {
            throw new IllegalArgumentException(String.format("Unable to find storage pool with ID: %d", id));
        }
        if (!primaryStorage.getStatus().equals(StoragePoolStatus.Up)) {
            throw new InvalidParameterValueException(String.format("Primary storage %s cannot be disabled. Storage pool state : %s", primaryStorage, primaryStorage.getStatus().toString()));
        }

        DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        DataStoreLifeCycle dataStoreLifeCycle = provider.getDataStoreLifeCycle();
        DataStore store = _dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
        ((PrimaryDataStoreLifeCycle)dataStoreLifeCycle).disableStoragePool(store);

        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(id, DataStoreRole.Primary);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ENABLE_PRIMARY_STORAGE, eventDescription = "enable storage pool")
    public StoragePool enablePrimaryStoragePool(Long id) {
        StoragePoolVO primaryStorage = _storagePoolDao.findById(id);
        if (primaryStorage == null) {
            throw new IllegalArgumentException(String.format("Unable to find storage pool with ID: %d", id));
        }
        if (!primaryStorage.getStatus().equals(StoragePoolStatus.Disabled)) {
            throw new InvalidParameterValueException(String.format("Primary storage %s cannot be enabled. Storage pool state : %s", primaryStorage, primaryStorage.getStatus()));
        }

        DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        DataStoreLifeCycle dataStoreLifeCycle = provider.getDataStoreLifeCycle();
        DataStore store = _dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
        ((PrimaryDataStoreLifeCycle)dataStoreLifeCycle).enableStoragePool(store);

        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(id, DataStoreRole.Primary);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_UPDATE_PRIMARY_STORAGE, eventDescription = "update storage pool")
    public PrimaryDataStoreInfo updateStoragePool(UpdateStoragePoolCmd cmd) throws IllegalArgumentException {
        // Input validation
        Long id = cmd.getId();

        StoragePoolVO pool = _storagePoolDao.findById(id);
        if (pool == null) {
            throw new IllegalArgumentException("Unable to find storage pool with ID: " + id);
        }

        Map<String, String> inputDetails = extractApiParamAsMap(cmd.getDetails());
        checkNFSMountOptionsForUpdate(inputDetails, pool, cmd.getEntityOwnerId());

        String name = cmd.getName();
        if(StringUtils.isNotBlank(name)) {
            logger.debug("Updating Storage Pool name to: " + name);
            pool.setName(name);
            _storagePoolDao.update(pool.getId(), pool);
        }


        final List<String> storagePoolTags = cmd.getTags();
        if (storagePoolTags != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Updating Storage Pool Tags to :" + storagePoolTags);
            }
            if (pool.getPoolType() == StoragePoolType.DatastoreCluster) {
                List<StoragePoolVO> childStoragePools = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(pool.getId());
                for (StoragePoolVO childPool : childStoragePools) {
                    _storagePoolTagsDao.persist(childPool.getId(), storagePoolTags, cmd.isTagARule());
                }
            }
            _storagePoolTagsDao.persist(pool.getId(), storagePoolTags, cmd.isTagARule());
        }

        boolean changes = false;
        Long updatedCapacityBytes = null;
        Long capacityBytes = cmd.getCapacityBytes();

        if (capacityBytes != null) {
            if (capacityBytes != pool.getCapacityBytes()) {
                updatedCapacityBytes = capacityBytes;
                changes = true;
            }
        }

        Long updatedCapacityIops = null;
        Long capacityIops = cmd.getCapacityIops();
        if (capacityIops != null) {
            if (!capacityIops.equals(pool.getCapacityIops())) {
                updatedCapacityIops = capacityIops;
                changes = true;
            }
        }

        // retrieve current details and merge/overlay input to capture changes
        Map<String, String> details = null;
        details = _storagePoolDetailsDao.listDetailsKeyPairs(id);
        if (inputDetails != null) {
            details.putAll(inputDetails);
            changes = true;
        }

        if (changes) {
            StoragePoolVO storagePool = _storagePoolDao.findById(id);
            DataStoreProvider dataStoreProvider = _dataStoreProviderMgr.getDataStoreProvider(storagePool.getStorageProviderName());
            DataStoreLifeCycle dataStoreLifeCycle = dataStoreProvider.getDataStoreLifeCycle();

            if (dataStoreLifeCycle instanceof PrimaryDataStoreLifeCycle) {
                if (updatedCapacityBytes != null) {
                    details.put(PrimaryDataStoreLifeCycle.CAPACITY_BYTES, updatedCapacityBytes != null ? String.valueOf(updatedCapacityBytes) : null);
                    _storagePoolDao.updateCapacityBytes(id, updatedCapacityBytes);
                }
                if (updatedCapacityIops != null) {
                    details.put(PrimaryDataStoreLifeCycle.CAPACITY_IOPS, updatedCapacityIops != null ? String.valueOf(updatedCapacityIops) : null);
                    _storagePoolDao.updateCapacityIops(id, updatedCapacityIops);
                }
                if (cmd.getUrl() != null) {
                    details.put("url", cmd.getUrl());
                }
                _storagePoolDao.update(id, storagePool);
                _storagePoolDao.updateDetails(id, details);
            }
        }

        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
    }

    private void changeStoragePoolScopeToZone(StoragePoolVO primaryStorage) {
        /*
         * For cluster wide primary storage the hypervisor type might not be set.
         * So, get it from the clusterVO.
         */
        Long clusterId = primaryStorage.getClusterId();
        ClusterVO clusterVO = _clusterDao.findById(clusterId);
        HypervisorType hypervisorType = clusterVO.getHypervisorType();
        if (!zoneWidePoolSupportedHypervisorTypes.contains(hypervisorType)) {
            throw new InvalidParameterValueException("Primary storage scope change to Zone is not supported for hypervisor type " + hypervisorType);
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        PrimaryDataStoreLifeCycle lifeCycle = (PrimaryDataStoreLifeCycle) storeProvider.getDataStoreLifeCycle();

        DataStore primaryStore = _dataStoreMgr.getPrimaryDataStore(primaryStorage.getId());
        ClusterScope clusterScope = new ClusterScope(primaryStorage.getClusterId(), null, primaryStorage.getDataCenterId());

        lifeCycle.changeStoragePoolScopeToZone(primaryStore, clusterScope, hypervisorType);
    }

    private void changeStoragePoolScopeToCluster(StoragePoolVO primaryStorage, Long clusterId) {
        if (clusterId == null) {
            throw new InvalidParameterValueException("Cluster ID not provided");
        }
        ClusterVO clusterVO = _clusterDao.findById(clusterId);
        if (clusterVO == null) {
            throw new InvalidParameterValueException("Unable to find cluster by id " + clusterId);
        }
        if (clusterVO.getAllocationState().equals(Grouping.AllocationState.Disabled)) {
            throw new PermissionDeniedException("Cannot perform this operation, Cluster is currently disabled: " + clusterId);
        }

        List<VirtualMachine.State> states = Arrays.asList(State.Starting, State.Running, State.Stopping, State.Migrating, State.Restoring);

        Long id = primaryStorage.getId();
        Pair<List<VMInstanceVO>, Integer> vmsNotInClusterUsingPool = _vmInstanceDao.listByVmsNotInClusterUsingPool(clusterId, id);
        if (vmsNotInClusterUsingPool.second() != 0) {
            throw new CloudRuntimeException(String.format("Cannot change scope of the storage pool [%s] to cluster [%s] " +
                    "as there are %s VMs with volumes in this pool that are running on other clusters. " +
                    "All such User VMs must be stopped and System VMs must be destroyed before proceeding. " +
                    "Please use the API listAffectedVmsForStorageScopeChange to get the list.",
                    primaryStorage.getName(), clusterVO.getName(), vmsNotInClusterUsingPool.second()));
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        PrimaryDataStoreLifeCycle lifeCycle = (PrimaryDataStoreLifeCycle) storeProvider.getDataStoreLifeCycle();

        DataStore primaryStore = _dataStoreMgr.getPrimaryDataStore(id);
        ClusterScope clusterScope = new ClusterScope(clusterId, clusterVO.getPodId(), primaryStorage.getDataCenterId());

        lifeCycle.changeStoragePoolScopeToCluster(primaryStore, clusterScope, primaryStorage.getHypervisor());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CHANGE_STORAGE_POOL_SCOPE, eventDescription = "changing storage pool scope")
    public void changeStoragePoolScope(ChangeStoragePoolScopeCmd cmd) throws IllegalArgumentException, InvalidParameterValueException, PermissionDeniedException {
        Long id = cmd.getId();

        Long accountId = cmd.getEntityOwnerId();
        if (!_accountMgr.isRootAdmin(accountId)) {
            throw new PermissionDeniedException("Only root admin can perform this operation");
        }

        ScopeType newScope = EnumUtils.getEnumIgnoreCase(ScopeType.class, cmd.getScope());
        if (newScope != ScopeType.ZONE && newScope != ScopeType.CLUSTER) {
            throw new InvalidParameterValueException("Invalid scope " + cmd.getScope() + "for Primary storage");
        }

        StoragePoolVO primaryStorage = _storagePoolDao.findById(id);
        if (primaryStorage == null) {
            throw new IllegalArgumentException("Unable to find storage pool with ID: " + id);
        }

        String eventDetails = String.format(" Storage pool Id: %s to %s",primaryStorage.getUuid(), newScope);
        CallContext.current().setEventDetails(eventDetails);

        ScopeType currentScope = primaryStorage.getScope();
        if (currentScope.equals(newScope)) {
            throw new InvalidParameterValueException("New scope must be different than the current scope");
        }

        if (currentScope != ScopeType.ZONE && currentScope != ScopeType.CLUSTER) {
            throw new InvalidParameterValueException("This operation is supported only for Primary storages having scope "
                    + ScopeType.CLUSTER + " or " + ScopeType.ZONE);
        }

        if (!primaryStorage.getStatus().equals(StoragePoolStatus.Disabled)) {
            throw new InvalidParameterValueException("Scope of the Primary storage with id "
                    + primaryStorage.getUuid() +
                    " cannot be changed, as it is not in the Disabled state");
        }

        Long zoneId = primaryStorage.getDataCenterId();
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }
        if (zone.getAllocationState().equals(Grouping.AllocationState.Disabled)) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zoneId);
        }

        if (newScope.equals(ScopeType.ZONE)) {
            changeStoragePoolScopeToZone(primaryStorage);
        } else {
            changeStoragePoolScopeToCluster(primaryStorage, cmd.getClusterId());
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONFIGURE_STORAGE_ACCESS, eventDescription = "configuring storage groups", async = true)
    public boolean configureStorageAccess(ConfigureStorageAccessCmd cmd) {
        Long zoneId = cmd.getZoneId();
        Long podId = cmd.getPodId();
        Long clusterId = cmd.getClusterId();
        Long hostId = cmd.getHostId();
        Long storagePoolId = cmd.getStorageId();

        long nonNullCount = Stream.of(zoneId, podId, clusterId, hostId, storagePoolId)
                .filter(Objects::nonNull)
                .count();

        if (nonNullCount != 1) {
            throw new IllegalArgumentException("Exactly one of zoneid, podid, clusterid, hostid or storagepoolid is required");
        }

        // SAG -> Storage Access Group
        List<String> storageAccessGroups = cmd.getStorageAccessGroups();
        if (storageAccessGroups == null) {
            throw new InvalidParameterValueException("storageaccessgroups parameter is required");
        }

        if (zoneId != null) {
            DataCenterVO zone = _dcDao.findById(zoneId);
            Set<String> existingSAGsSet = (zone.getStorageAccessGroups() == null || zone.getStorageAccessGroups().isEmpty())
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(zone.getStorageAccessGroups().split(",")));

            Set<String> storagePoolSAGsSet = new HashSet<>(storageAccessGroups);
            if (!existingSAGsSet.equals(storagePoolSAGsSet)) {
                _resourceMgr.updateZoneStorageAccessGroups(zone.getId(), storageAccessGroups);
                String preparedStoragePoolTags = CollectionUtils.isEmpty(storageAccessGroups) ? null : String.join(",", storageAccessGroups);
                zone.setStorageAccessGroups(preparedStoragePoolTags);

                if (!_dcDao.update(zoneId, zone)) {
                    throw new CloudRuntimeException("Failed to update zone with the storage access groups.");
                }
            }
        }

        if (podId != null) {
            HostPodVO pod = _podDao.findById(podId);
            Set<String> existingTagsSet = (pod.getStorageAccessGroups() == null || pod.getStorageAccessGroups().isEmpty())
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(pod.getStorageAccessGroups().split(",")));

            if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
                checkIfStorageAccessGroupsExistsOnZone(pod.getDataCenterId(), storageAccessGroups);
            }

            Set<String> storagePoolTagsSet = new HashSet<>(storageAccessGroups);
            if (!existingTagsSet.equals(storagePoolTagsSet)) {
                _resourceMgr.updatePodStorageAccessGroups(podId, storageAccessGroups);
                String preparedStoragePoolTags = CollectionUtils.isEmpty(storageAccessGroups) ? null : String.join(",", storageAccessGroups);
                pod.setStorageAccessGroups(preparedStoragePoolTags);

                if (!_podDao.update(podId, pod)) {
                    throw new CloudRuntimeException("Failed to update pod with the storage access groups.");
                }
            }
        }

        if (clusterId != null) {
            ClusterVO cluster = _clusterDao.findById(clusterId);
            Set<String> existingTagsSet = (cluster.getStorageAccessGroups() == null || cluster.getStorageAccessGroups().isEmpty())
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(cluster.getStorageAccessGroups().split(",")));

            if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
                checkIfStorageAccessGroupsExistsOnPod(cluster.getPodId(), storageAccessGroups);
            }

            Set<String> storagePoolTagsSet = new HashSet<>(storageAccessGroups);
            if (!existingTagsSet.equals(storagePoolTagsSet)) {
                _resourceMgr.updateClusterStorageAccessGroups(cluster.getId(), storageAccessGroups);
                String preparedStoragePoolTags = CollectionUtils.isEmpty(storageAccessGroups) ? null : String.join(",", storageAccessGroups);
                cluster.setStorageAccessGroups(preparedStoragePoolTags);

                if (!_clusterDao.update(clusterId, cluster)) {
                    throw new CloudRuntimeException("Failed to update cluster with the storage access groups.");
                }
            }
        }

        if (hostId != null) {
            HostVO host = _hostDao.findById(hostId);
            Set<String> existingTagsSet = (host.getStorageAccessGroups() == null || host.getStorageAccessGroups().isEmpty())
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(host.getStorageAccessGroups().split(",")));

            if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
                checkIfStorageAccessGroupsExistsOnCluster(host.getClusterId(), storageAccessGroups);
            }

            Set<String> storageAccessGroupsSet = new HashSet<>(storageAccessGroups);
            if (!existingTagsSet.equals(storageAccessGroupsSet)) {
                _resourceMgr.updateHostStorageAccessGroups(hostId, storageAccessGroups);
                String preparedStoragePoolTags = CollectionUtils.isEmpty(storageAccessGroups) ? null : String.join(",", storageAccessGroups);
                host.setStorageAccessGroups(preparedStoragePoolTags);

                if (!_hostDao.update(hostId, host)) {
                    throw new CloudRuntimeException("Failed to update host with the storage access groups.");
                }
            }
        }

        if (storagePoolId != null) {
            StoragePoolVO storagePool = _storagePoolDao.findById(storagePoolId);
            if (ScopeType.HOST.equals(storagePool.getScope())) {
                throw new CloudRuntimeException("Storage Access Groups are not suitable for local storage");
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Updating Storage Pool Access Group Maps to :" + storageAccessGroups);
            }

            if (storagePool.getPoolType() == StoragePoolType.DatastoreCluster) {
                List<StoragePoolVO> childStoragePools = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(storagePool.getId());
                for (StoragePoolVO childPool : childStoragePools) {
                    _resourceMgr.updateStoragePoolConnectionsOnHosts(childPool.getId(), storageAccessGroups);
                    _storagePoolAccessGroupMapDao.persist(childPool.getId(), storageAccessGroups);
                }
            } else {
                _resourceMgr.updateStoragePoolConnectionsOnHosts(storagePool.getId(), storageAccessGroups);
            }

            _storagePoolAccessGroupMapDao.persist(storagePool.getId(), storageAccessGroups);
        }

        return true;
    }

    protected void checkIfStorageAccessGroupsExistsOnZone(long zoneId, List<String> storageAccessGroups) {
        DataCenterVO zoneVO = _dcDao.findById(zoneId);

        String storageAccessGroupsOnZone = zoneVO.getStorageAccessGroups();
        List<String> zoneTagsList = parseTags(storageAccessGroupsOnZone);
        List<String> newTags = storageAccessGroups;

        List<String> existingTagsOnZone = (List<String>) CollectionUtils.intersection(newTags, zoneTagsList);

        if (CollectionUtils.isNotEmpty(existingTagsOnZone)) {
            throw new CloudRuntimeException(String.format("access groups already exist on the zone: %s", existingTagsOnZone));
        }
    }

    protected void checkIfStorageAccessGroupsExistsOnPod(long podId, List<String> storageAccessGroups) {
        HostPodVO podVO = _podDao.findById(podId);
        DataCenterVO zoneVO = _dcDao.findById(podVO.getDataCenterId());

        String storageAccessGroupsOnPod = podVO.getStorageAccessGroups();
        String storageAccessGroupsOnZone = zoneVO.getStorageAccessGroups();

        List<String> podTagsList = parseTags(storageAccessGroupsOnPod);
        List<String> zoneTagsList = parseTags(storageAccessGroupsOnZone);
        List<String> newTags = storageAccessGroups;

        List<String> existingTagsOnPod = (List<String>) CollectionUtils.intersection(newTags, podTagsList);
        List<String> existingTagsOnZone = (List<String>) CollectionUtils.intersection(newTags, zoneTagsList);

        if (CollectionUtils.isNotEmpty(existingTagsOnPod) || CollectionUtils.isNotEmpty(existingTagsOnZone)) {
            String message = "access groups already exist ";

            if (CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                message += String.format("on the pod: %s", existingTagsOnPod);
            }
            if (CollectionUtils.isNotEmpty(existingTagsOnZone)) {
                if (CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                    message += ", ";
                }
                message += String.format("on the zone: %s", existingTagsOnZone);
            }

            throw new CloudRuntimeException(message);
        }
    }

    protected void checkIfStorageAccessGroupsExistsOnCluster(long clusterId, List<String> storageAccessGroups) {
        ClusterVO clusterVO = _clusterDao.findById(clusterId);
        HostPodVO podVO = _podDao.findById(clusterVO.getPodId());
        DataCenterVO zoneVO = _dcDao.findById(podVO.getDataCenterId());

        String storageAccessGroupsOnCluster = clusterVO.getStorageAccessGroups();
        String storageAccessGroupsOnPod = podVO.getStorageAccessGroups();
        String storageAccessGroupsOnZone = zoneVO.getStorageAccessGroups();

        List<String> podTagsList = parseTags(storageAccessGroupsOnPod);
        List<String> zoneTagsList = parseTags(storageAccessGroupsOnZone);
        List<String> clusterTagsList = parseTags(storageAccessGroupsOnCluster);
        List<String> newTags = storageAccessGroups;

        List<String> existingTagsOnCluster = (List<String>) CollectionUtils.intersection(newTags, clusterTagsList);
        List<String> existingTagsOnPod = (List<String>) CollectionUtils.intersection(newTags, podTagsList);
        List<String> existingTagsOnZone = (List<String>) CollectionUtils.intersection(newTags, zoneTagsList);

        if (CollectionUtils.isNotEmpty(existingTagsOnCluster) || CollectionUtils.isNotEmpty(existingTagsOnPod) || CollectionUtils.isNotEmpty(existingTagsOnZone)) {
            String message = "access groups already exist ";

            if (CollectionUtils.isNotEmpty(existingTagsOnCluster)) {
                message += String.format("on the cluster: %s", existingTagsOnCluster);
            }
            if (CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                if (CollectionUtils.isNotEmpty(existingTagsOnCluster)) {
                    message += ", ";
                }
                message += String.format("on the pod: %s", existingTagsOnPod);
            }
            if (CollectionUtils.isNotEmpty(existingTagsOnZone)) {
                if (CollectionUtils.isNotEmpty(existingTagsOnCluster) || CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                    message += ", ";
                }
                message += String.format("on the zone: %s", existingTagsOnZone);
            }

            throw new CloudRuntimeException(message);
        }
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(tags.split(","));
    }

    @Override
    public void removeStoragePoolFromCluster(long hostId, String iScsiName, StoragePool storagePool) {
        final Map<String, String> details = new HashMap<>();

        details.put(DeleteStoragePoolCommand.DATASTORE_NAME, iScsiName);
        details.put(DeleteStoragePoolCommand.IQN, iScsiName);
        details.put(DeleteStoragePoolCommand.STORAGE_HOST, storagePool.getHostAddress());
        details.put(DeleteStoragePoolCommand.STORAGE_PORT, String.valueOf(storagePool.getPort()));

        final DeleteStoragePoolCommand cmd = new DeleteStoragePoolCommand();

        cmd.setDetails(details);
        cmd.setRemoveDatastore(true);

        final Answer answer = _agentMgr.easySend(hostId, cmd);

        if (answer == null || !answer.getResult()) {
            String errMsg = "Error interacting with host (related to DeleteStoragePoolCommand)" + (answer == null ? "" : (StringUtils.isNotBlank(answer.getDetails()) ? ": " + answer.getDetails() : ""));

            logger.error(errMsg);

            throw new CloudRuntimeException(errMsg);
        }
    }

    @Override
    @DB
    public boolean deletePool(DeletePoolCmd cmd) {
        Long id = cmd.getId();
        boolean forced = cmd.isForced();

        StoragePoolVO sPool = _storagePoolDao.findById(id);
        if (sPool == null) {
            logger.warn("Unable to find pool:" + id);
            throw new InvalidParameterValueException("Unable to find pool by id " + id);
        }
        if (sPool.getStatus() != StoragePoolStatus.Maintenance) {
            logger.warn("Unable to delete storage pool: {} due to it is not in Maintenance state", sPool);
            throw new InvalidParameterValueException(String.format("Unable to delete storage due to it is not in Maintenance state, pool: %s", sPool));
        }

        if (sPool.getPoolType() == StoragePoolType.DatastoreCluster) {
            // FR41 yet to handle on failure of deletion of any of the child storage pool
            if (checkIfDataStoreClusterCanbeDeleted(sPool, forced)) {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        List<StoragePoolVO> childStoragePools = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(sPool.getId());
                        for (StoragePoolVO childPool : childStoragePools) {
                            deleteDataStoreInternal(childPool, forced);
                        }
                    }
                });
            } else {
                logger.debug("Cannot delete storage pool {} as the following non-destroyed volumes are on it: {}.", sPool::toString, () -> getStoragePoolNonDestroyedVolumesLog(sPool.getId()));
                throw new CloudRuntimeException(String.format("Cannot delete pool %s as there are associated non-destroyed vols for this pool", sPool));
            }
        }
        return deleteDataStoreInternal(sPool, forced);
    }

    @Override
    public Pair<Map<String, String>, Boolean> getStoragePoolNFSMountOpts(StoragePool pool, Map<String, String> details) {
        boolean details_added = false;
        if (!pool.getPoolType().equals(Storage.StoragePoolType.NetworkFilesystem)) {
            return new Pair<>(details, details_added);
        }

        StoragePoolDetailVO nfsMountOpts = _storagePoolDetailsDao.findDetail(pool.getId(), ApiConstants.NFS_MOUNT_OPTIONS);
        if (nfsMountOpts != null) {
            if (details == null) {
                details = new HashMap<>();
            }
            details.put(ApiConstants.NFS_MOUNT_OPTIONS, nfsMountOpts.getValue());
            details_added = true;
        }
        return new Pair<>(details, details_added);
    }

    public String getStoragePoolMountFailureReason(String reason) {
        if (reason.toLowerCase().contains(NFS_MOUNT_OPTIONS_INCORRECT.toLowerCase())) {
            return NFS_MOUNT_OPTIONS_INCORRECT;
        } else {
            return null;
        }
    }

    private boolean checkIfDataStoreClusterCanbeDeleted(StoragePoolVO sPool, boolean forced) {
        List<StoragePoolVO> childStoragePools = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(sPool.getId());
        boolean canDelete = true;
        for (StoragePoolVO childPool : childStoragePools) {
            Pair<Long, Long> vlms = volumeDao.getCountAndTotalByPool(childPool.getId());
            if (forced) {
                if (vlms.first() > 0) {
                    Pair<Long, Long> nonDstrdVlms = volumeDao.getNonDestroyedCountAndTotalByPool(childPool.getId());
                    if (nonDstrdVlms.first() > 0) {
                        canDelete = false;
                        break;
                    }
                }
            } else {
                if (vlms.first() > 0) {
                    canDelete = false;
                    break;
                }
            }
        }
        return canDelete;
    }

    private boolean deleteDataStoreInternal(StoragePoolVO sPool, boolean forced) {
        Pair<Long, Long> vlms = volumeDao.getCountAndTotalByPool(sPool.getId());
        if (forced) {
            if (vlms.first() > 0) {
                Pair<Long, Long> nonDstrdVlms = volumeDao.getNonDestroyedCountAndTotalByPool(sPool.getId());
                if (nonDstrdVlms.first() > 0) {
                    logger.debug("Cannot delete storage pool {} as the following non-destroyed volumes are on it: {}.", sPool::toString, () -> getStoragePoolNonDestroyedVolumesLog(sPool.getId()));
                    throw new CloudRuntimeException(String.format("Cannot delete pool %s as there are non-destroyed volumes associated to this pool.", sPool));
                }
                // force expunge non-destroyed volumes
                List<VolumeVO> vols = volumeDao.listVolumesToBeDestroyed();
                for (VolumeVO vol : vols) {
                    AsyncCallFuture<VolumeApiResult> future = volService.expungeVolumeAsync(volFactory.getVolume(vol.getId()));
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        logger.debug("expunge volume failed: {}", vol, e);
                    }
                }
            }
        } else {
            // Check if the pool has associated volumes in the volumes table
            // If it does , then you cannot delete the pool
            if (vlms.first() > 0) {
                logger.debug("Cannot delete storage pool {} as the following non-destroyed volumes are on it: {}.", sPool::toString, () -> getStoragePoolNonDestroyedVolumesLog(sPool.getId()));
                throw new CloudRuntimeException(String.format("Cannot delete pool %s as there are non-destroyed volumes associated to this pool.", sPool));
            }
        }

        // First get the host_id from storage_pool_host_ref for given pool id
        StoragePoolVO lock = _storagePoolDao.acquireInLockTable(sPool.getId());

        if (lock == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to acquire lock when deleting PrimaryDataStoreVO: {}", sPool);
            }
            return false;
        }

        _storagePoolDao.releaseFromLockTable(lock.getId());
        logger.trace("Released lock for storage pool {}", sPool);

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(sPool.getStorageProviderName());
        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();
        DataStore store = _dataStoreMgr.getDataStore(sPool.getId(), DataStoreRole.Primary);
        return lifeCycle.deleteDataStore(store);
    }

    protected String getStoragePoolNonDestroyedVolumesLog(long storagePoolId) {
        StringBuilder sb = new StringBuilder();
        List<VolumeVO> nonDestroyedVols = volumeDao.findByPoolId(storagePoolId, null).stream().filter(vol -> vol.getState() != Volume.State.Destroy).collect(Collectors.toList());
        VMInstanceVO volInstance;
        List<String> logMessageInfo = new ArrayList<>();

        sb.append("[");
        for (VolumeVO vol : nonDestroyedVols) {
            volInstance = _vmInstanceDao.findById(vol.getInstanceId());
            logMessageInfo.add(String.format("Volume [%s] (attached to VM [%s])", vol.getUuid(), volInstance.getUuid()));
        }
        sb.append(String.join(", ", logMessageInfo));
        sb.append("]");

        return sb.toString();
    }

    protected void cleanupConnectedHostConnectionForFailedStorage(DataStore primaryStore, List<Long> poolHostIds) {
        List<HostVO> hosts = _hostDao.listByIds(poolHostIds);
        StoragePool pool = _storagePoolDao.findById(primaryStore.getId());
        for (HostVO host : hosts) {
            try {
                disconnectHostFromSharedPool(host, pool);
            } catch (StorageUnavailableException | StorageConflictException e) {
                logger.error("Error during cleaning up failed storage host connection", e);
            }
        }
    }

    @Override
    public void connectHostsToPool(DataStore primaryStore, List<Long> hostIds, Scope scope,
              boolean handleExceptionsPartially, boolean errorOnNoUpHost) throws CloudRuntimeException {
        if (CollectionUtils.isEmpty(hostIds)) {
            return;
        }
        CopyOnWriteArrayList<Long> poolHostIds = new CopyOnWriteArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(Math.max(1, Math.min(hostIds.size(),
                StoragePoolHostConnectWorkers.value())));
        List<Future<Void>> futures = new ArrayList<>();
        AtomicBoolean exceptionOccurred = new AtomicBoolean(false);
        for (Long hostId : hostIds) {
            futures.add(executorService.submit(() -> {
                if (exceptionOccurred.get()) {
                    return null;
                }
                Transaction.execute(new TransactionCallbackWithExceptionNoReturn<Exception>() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) throws Exception {
                        HostVO host = _hostDao.findById(hostId);
                        try {
                            connectHostToSharedPool(host, primaryStore.getId());
                            poolHostIds.add(hostId);
                        } catch (Exception e) {
                            if (handleExceptionsPartially && e.getCause() instanceof StorageConflictException) {
                                exceptionOccurred.set(true);
                                throw e;
                            }
                            logger.warn("Unable to establish a connection between {} and {}", host, primaryStore, e);
                            String reason = getStoragePoolMountFailureReason(e.getMessage());
                            if (handleExceptionsPartially && reason != null) {
                                exceptionOccurred.set(true);
                                throw new CloudRuntimeException(reason);
                            }
                        }
                    }
                });
                return null;
            }));
        }
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof StorageConflictException || cause instanceof CloudRuntimeException) {
                    executorService.shutdown();
                    cleanupConnectedHostConnectionForFailedStorage(primaryStore, poolHostIds);
                    primaryStoreDao.expunge(primaryStore.getId());
                    if (cause instanceof CloudRuntimeException) {
                        throw (CloudRuntimeException)cause;
                    }
                    throw new CloudRuntimeException("Storage has already been added as local storage", e);
                } else {
                    logger.error("Error occurred while connecting host to shared pool", e);
                }
            }
        }
        executorService.shutdown();
        if (poolHostIds.isEmpty()) {
            logger.warn("No host can access storage pool {} in {}: {}.",
                    primaryStore, scope.getScopeType(), scope.getScopeId());
            if (errorOnNoUpHost) {
                primaryStoreDao.expunge(primaryStore.getId());
                throw new CloudRuntimeException("Failed to access storage pool");
            }
        }
    }

    @Override
    public boolean connectHostToSharedPool(Host host, long poolId) throws StorageUnavailableException, StorageConflictException {
        StoragePool pool = (StoragePool)_dataStoreMgr.getDataStore(poolId, DataStoreRole.Primary);
        assert (pool.isShared()) : "Now, did you actually read the name of this method?";
        logger.debug("Adding pool {} to  host {}", pool, host);

        DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        HypervisorHostListener listener = hostListeners.get(provider.getName());
        return listener.hostConnect(host, pool);
    }

    @Override
    public void disconnectHostFromSharedPool(Host host, StoragePool pool) throws StorageUnavailableException, StorageConflictException {
        assert (pool.isShared()) : "Now, did you actually read the name of this method?";
        logger.debug("Removing pool {} from host {}", pool, host);

        DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        HypervisorHostListener listener = hostListeners.get(provider.getName());
        listener.hostDisconnected(host, pool);
    }

    @Override
    public void enableHost(long hostId) {
        List<DataStoreProvider> providers = _dataStoreProviderMgr.getProviders();
        if (providers != null) {
            for (DataStoreProvider provider : providers) {
                if (provider instanceof PrimaryDataStoreProvider) {
                    try {
                        HypervisorHostListener hypervisorHostListener = provider.getHostListener();
                        if (hypervisorHostListener != null) {
                            hypervisorHostListener.hostEnabled(hostId);
                        }
                    }
                    catch (Exception ex) {
                        logger.error("hostEnabled(long) failed for storage provider " + provider.getName(), ex);
                    }
                }
            }
        }
    }

    @Override
    public BigDecimal getStorageOverProvisioningFactor(Long poolId) {
        return new BigDecimal(CapacityManager.StorageOverprovisioningFactor.valueIn(poolId));
    }

    @Override
    public void createCapacityEntry(StoragePoolVO storagePool, short capacityType, long allocated) {
        SearchCriteria<CapacityVO> capacitySC = _capacityDao.createSearchCriteria();
        capacitySC.addAnd("hostOrPoolId", SearchCriteria.Op.EQ, storagePool.getId());
        capacitySC.addAnd("dataCenterId", SearchCriteria.Op.EQ, storagePool.getDataCenterId());
        capacitySC.addAnd("capacityType", SearchCriteria.Op.EQ, capacityType);

        List<CapacityVO> capacities = _capacityDao.search(capacitySC, null);

        long totalOverProvCapacity;
        if (storagePool.getPoolType().supportsOverProvisioning()) {
            // All this is for the inaccuracy of floats for big number multiplication.
            BigDecimal overProvFactor = getStorageOverProvisioningFactor(storagePool.getId());
            totalOverProvCapacity = overProvFactor.multiply(new BigDecimal(storagePool.getCapacityBytes())).longValue();
            logger.debug("Found storage pool {} of type {} with overprovisioning factor {}", storagePool, storagePool.getPoolType(), overProvFactor);
            logger.debug("Total over provisioned capacity calculated is {} * {}", overProvFactor, toHumanReadableSize(storagePool.getCapacityBytes()));
        } else {
            logger.debug("Found storage pool {} of type {}", storagePool, storagePool.getPoolType());
            totalOverProvCapacity = storagePool.getCapacityBytes();
        }

        logger.debug("Total over provisioned capacity of the pool {} is {}", storagePool, toHumanReadableSize(totalOverProvCapacity));
        CapacityState capacityState = CapacityState.Enabled;
        if (storagePool.getScope() == ScopeType.ZONE) {
            DataCenterVO dc = _dcDao.findById(storagePool.getDataCenterId());
            AllocationState allocationState = dc.getAllocationState();
            capacityState = (allocationState == AllocationState.Disabled) ? CapacityState.Disabled : CapacityState.Enabled;
        } else {
            if (storagePool.getClusterId() != null) {
                ClusterVO cluster = ApiDBUtils.findClusterById(storagePool.getClusterId());
                if (cluster != null) {
                    AllocationState allocationState = _configMgr.findClusterAllocationState(cluster);
                    capacityState = (allocationState == AllocationState.Disabled) ? CapacityState.Disabled : CapacityState.Enabled;
                }
            }
        }

        if (storagePool.getScope() == ScopeType.HOST) {
            List<StoragePoolHostVO> stoargePoolHostVO = _storagePoolHostDao.listByPoolId(storagePool.getId());

            if (stoargePoolHostVO != null && !stoargePoolHostVO.isEmpty()) {
                HostVO host = _hostDao.findById(stoargePoolHostVO.get(0).getHostId());

                if (host != null) {
                    capacityState = (host.getResourceState() == ResourceState.Disabled) ? CapacityState.Disabled : CapacityState.Enabled;
                }
            }
        }

        if (capacities.size() == 0) {
            CapacityVO capacity = new CapacityVO(storagePool.getId(), storagePool.getDataCenterId(), storagePool.getPodId(), storagePool.getClusterId(), allocated, totalOverProvCapacity,
                    capacityType);
            capacity.setCapacityState(capacityState);
            _capacityDao.persist(capacity);
        } else {
            CapacityVO capacity = capacities.get(0);
            if (capacity.getTotalCapacity() != totalOverProvCapacity || allocated != capacity.getUsedCapacity() || capacity.getCapacityState() != capacityState) {
                capacity.setTotalCapacity(totalOverProvCapacity);
                capacity.setUsedCapacity(allocated);
                capacity.setCapacityState(capacityState);
                _capacityDao.update(capacity.getId(), capacity);
            }
        }
        logger.debug("Successfully set Capacity - {} for capacity type - {} , DataCenterId - {}, Pool - {}, PodId {}",
                toHumanReadableSize(totalOverProvCapacity), capacityType, storagePool.getDataCenterId(), storagePool, storagePool.getPodId());
    }

    @Override
    public List<Long> getUpHostsInPool(long poolId) {
        SearchCriteria<Long> sc = UpHostsInPoolSearch.create();
        sc.setParameters("pool", poolId);
        sc.setJoinParameters("hosts", "status", Status.Up);
        sc.setJoinParameters("hosts", "resourceState", ResourceState.Enabled);
        return _storagePoolHostDao.customSearch(sc, null);
    }

    @Override
    public Pair<Long, Answer[]> sendToPool(StoragePool pool, long[] hostIdsToTryFirst, List<Long> hostIdsToAvoid, Commands cmds) throws StorageUnavailableException {
        List<Long> hostIds = getUpHostsInPool(pool.getId());
        Collections.shuffle(hostIds);
        if (hostIdsToTryFirst != null) {
            for (int i = hostIdsToTryFirst.length - 1; i >= 0; i--) {
                if (hostIds.remove(hostIdsToTryFirst[i])) {
                    hostIds.add(0, hostIdsToTryFirst[i]);
                }
            }
        }

        if (hostIdsToAvoid != null) {
            hostIds.removeAll(hostIdsToAvoid);
        }
        if (hostIds == null || hostIds.isEmpty()) {
            throw new StorageUnavailableException(String.format("Unable to send command to the pool %s due to there is no enabled hosts up in this cluster", pool), pool.getId());
        }
        for (Long hostId : hostIds) {
            try {
                List<Answer> answers = new ArrayList<>();
                Command[] cmdArray = cmds.toCommands();
                for (Command cmd : cmdArray) {
                    long targetHostId = _hvGuruMgr.getGuruProcessedCommandTargetHost(hostId, cmd);
                    answers.add(_agentMgr.send(targetHostId, cmd));
                }
                return new Pair<>(hostId, answers.toArray(new Answer[answers.size()]));
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                logger.debug("Unable to send storage pool command to {} via {}", pool::toString, () -> _hostDao.findById(hostId), () -> e);
            }
        }

        throw new StorageUnavailableException("Unable to send command to the pool ", pool.getId());
    }

    @Override
    public Pair<Long, Answer> sendToPool(StoragePool pool, long[] hostIdsToTryFirst, List<Long> hostIdsToAvoid, Command cmd) throws StorageUnavailableException {
        Commands cmds = new Commands(cmd);
        Pair<Long, Answer[]> result = sendToPool(pool, hostIdsToTryFirst, hostIdsToAvoid, cmds);
        return new Pair<>(result.first(), result.second()[0]);
    }

    private void cleanupInactiveTemplates() {
        List<VMTemplateVO> vmTemplateVOS = _templateDao.listUnRemovedTemplatesByStates(VirtualMachineTemplate.State.Inactive);
        for (VMTemplateVO template: vmTemplateVOS) {
            template.setRemoved(new Date());
            _templateDao.update(template.getId(), template);
        }
    }

    @Override
    public void cleanupStorage(boolean recurring) {
        GlobalLock scanLock = GlobalLock.getInternLock("storagemgr.cleanup");

        try {
            if (scanLock.lock(3)) {
                try {
                    // Cleanup primary storage pools
                    if (TemplateCleanupEnabled.value()) {
                        List<StoragePoolVO> storagePools = _storagePoolDao.listAll();
                        for (StoragePoolVO pool : storagePools) {
                            try {

                                List<VMTemplateStoragePoolVO> unusedTemplatesInPool = _tmpltMgr.getUnusedTemplatesInPool(pool);
                                logger.debug("Storage pool garbage collector found [{}] templates to be cleaned up in storage pool [{}].", unusedTemplatesInPool.size(), pool);
                                for (VMTemplateStoragePoolVO templatePoolVO : unusedTemplatesInPool) {
                                    if (templatePoolVO.getDownloadState() != VMTemplateStorageResourceAssoc.Status.DOWNLOADED) {
                                        logger.debug("Storage pool garbage collector is skipping " +
                                                "template: {} on pool {} because it is not completely downloaded.",
                                                () -> _templateDao.findById(templatePoolVO.getTemplateId()), () -> _storagePoolDao.findById(templatePoolVO.getPoolId()));
                                        continue;
                                    }

                                    if (!templatePoolVO.getMarkedForGC()) {
                                        templatePoolVO.setMarkedForGC(true);
                                        _vmTemplatePoolDao.update(templatePoolVO.getId(), templatePoolVO);
                                        logger.debug("Storage pool garbage collector has marked template [{}] on pool [{}] " +
                                                "for garbage collection.",
                                                () -> _templateDao.findById(templatePoolVO.getTemplateId()), () -> _storagePoolDao.findById(templatePoolVO.getPoolId()));
                                        continue;
                                    }

                                    _tmpltMgr.evictTemplateFromStoragePool(templatePoolVO);
                                }
                            } catch (Exception e) {
                                logger.error(String.format("Failed to clean up primary storage pool [%s] due to: [%s].", pool, e.getMessage()));
                                logger.debug(String.format("Failed to clean up primary storage pool [%s].", pool), e);
                            }
                        }
                    }

                    //destroy snapshots in destroying state in snapshot_store_ref
                    List<SnapshotDataStoreVO> ssSnapshots = _snapshotStoreDao.listByState(ObjectInDataStoreStateMachine.State.Destroying);
                    for (SnapshotDataStoreVO snapshotDataStoreVO : ssSnapshots) {
                        String snapshotUuid = null;
                        SnapshotVO snapshot = null;
                        final String storeRole = snapshotDataStoreVO.getRole().toString().toLowerCase();
                        if (logger.isDebugEnabled()) {
                            snapshot = _snapshotDao.findById(snapshotDataStoreVO.getSnapshotId());
                            if (snapshot == null) {
                                logger.warn(String.format("Did not find snapshot [ID: %d] for which store reference is in destroying state; therefore, it cannot be destroyed.", snapshotDataStoreVO.getSnapshotId()));
                                continue;
                            }
                            snapshotUuid = snapshot.getUuid();
                        }

                        try {
                            if (logger.isDebugEnabled()) {
                                logger.debug(String.format("Verifying if snapshot [%s] is in destroying state in %s data store ID: %d.", snapshotUuid, storeRole, snapshotDataStoreVO.getDataStoreId()));
                            }
                            SnapshotInfo snapshotInfo = snapshotFactory.getSnapshot(snapshotDataStoreVO.getSnapshotId(), snapshotDataStoreVO.getDataStoreId(), snapshotDataStoreVO.getRole());
                            if (snapshotInfo != null) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug(String.format("Snapshot [%s] in destroying state found in %s data store [%s]; therefore, it will be destroyed.", snapshotUuid, storeRole, snapshotInfo.getDataStore().getUuid()));
                                }
                                _snapshotService.deleteSnapshot(snapshotInfo);
                            } else if (logger.isDebugEnabled()) {
                                logger.debug(String.format("Did not find snapshot [%s] in destroying state in %s data store ID: %d.", snapshotUuid, storeRole, snapshotDataStoreVO.getDataStoreId()));
                            }
                        } catch (Exception e) {
                            logger.error("Failed to delete snapshot [{}] from storage due to: [{}].", snapshot, e.getMessage());
                            if (logger.isDebugEnabled()) {
                                logger.debug("Failed to delete snapshot [{}] from storage.", snapshot, e);
                            }
                        }
                    }
                    cleanupSecondaryStorage(recurring);

                    List<VolumeVO> vols = volumeDao.listVolumesToBeDestroyed(new Date(System.currentTimeMillis() - ((long)StorageCleanupDelay.value() << 10)));
                    for (VolumeVO vol : vols) {
                        if (Type.ROOT.equals(vol.getVolumeType())) {
                             VMInstanceVO vmInstanceVO = _vmInstanceDao.findById(vol.getInstanceId());
                             if (vmInstanceVO != null && vmInstanceVO.getState() == State.Destroyed) {
                                 logger.debug("ROOT volume [{}] will not be expunged because the VM is [{}], therefore this volume will be expunged with the VM"
                                         + " cleanup job.", vol, vmInstanceVO.getState());
                                 continue;
                             }
                        }
                        if (isVolumeSuspectedDestroyDuplicateOfVmVolume(vol)) {
                            logger.warn(String.format("Skipping cleaning up %s as it could be a duplicate for another volume on same pool", vol));
                            continue;
                        }
                        try {
                            // If this fails, just log a warning. It's ideal if we clean up the host-side clustered file
                            // system, but not necessary.
                            handleManagedStorage(vol);
                        } catch (Exception e) {
                            logger.error("Unable to destroy host-side clustered file system [{}] due to: [{}].", vol, e.getMessage());
                            logger.debug("Unable to destroy host-side clustered file system [{}].", vol, e);
                        }

                        try {
                            VolumeInfo volumeInfo = volFactory.getVolume(vol.getId());
                            if (volumeInfo != null) {
                                volService.ensureVolumeIsExpungeReady(vol.getId());
                                volService.expungeVolumeAsync(volumeInfo);
                            } else {
                                logger.debug("Volume [{}] is already destroyed.", vol);
                            }
                        } catch (Exception e) {
                            logger.error("Unable to destroy volume [{}] due to: [{}].", vol, e.getMessage());
                            logger.debug("Unable to destroy volume [{}].", vol, e);
                        }
                    }

                    // remove snapshots in Error state
                    List<SnapshotVO> snapshots = _snapshotDao.listAllByStatus(Snapshot.State.Error);
                    for (SnapshotVO snapshotVO : snapshots) {
                        try {
                            List<SnapshotDataStoreVO> storeRefs = _snapshotStoreDao.findBySnapshotId(snapshotVO.getId());
                            for (SnapshotDataStoreVO ref : storeRefs) {
                                _snapshotStoreDao.expunge(ref.getId());
                            }
                            _snapshotDao.expunge(snapshotVO.getId());
                        } catch (Exception e) {
                            logger.error("Unable to destroy snapshot [{}] due to: [{}].", snapshotVO, e.getMessage());
                            logger.debug("Unable to destroy snapshot [{}].", snapshotVO, e);
                        }
                    }

                    // destroy uploaded volumes in abandoned/error state
                    List<VolumeDataStoreVO> volumeDataStores = _volumeDataStoreDao.listByVolumeState(Volume.State.UploadError, Volume.State.UploadAbandoned);
                    for (VolumeDataStoreVO volumeDataStore : volumeDataStores) {
                        VolumeVO volume = volumeDao.findById(volumeDataStore.getVolumeId());
                        if (volume == null) {
                            logger.warn(String.format("Uploaded volume [%s] not found, so cannot be destroyed.", volumeDataStore.getVolumeId()));
                            continue;
                        }
                        try {
                            DataStore dataStore = _dataStoreMgr.getDataStore(volumeDataStore.getDataStoreId(), DataStoreRole.Image);
                            EndPoint ep = _epSelector.select(dataStore, volumeDataStore.getExtractUrl());
                            if (ep == null) {
                                logger.warn("There is no secondary storage VM for image store {}, cannot destroy uploaded volume {}.", dataStore, volume);
                                continue;
                            }
                            Host host = _hostDao.findById(ep.getId());
                            if (host != null && host.getManagementServerId() != null) {
                                if (_serverId == host.getManagementServerId().longValue()) {
                                    volService.destroyVolume(volume.getId());
                                    // decrement volume resource count
                                    _resourceLimitMgr.decrementVolumeResourceCount(volume.getAccountId(), volume.isDisplayVolume(),
                                            null, _diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId()));
                                    // expunge volume from secondary if volume is on image store
                                    VolumeInfo volOnSecondary = volFactory.getVolume(volume.getId(), DataStoreRole.Image);
                                    if (volOnSecondary != null) {
                                        logger.info("Expunging volume [{}] uploaded using HTTP POST from secondary data store.", volume);
                                        AsyncCallFuture<VolumeApiResult> future = volService.expungeVolumeAsync(volOnSecondary);
                                        VolumeApiResult result = future.get();
                                        if (!result.isSuccess()) {
                                            logger.warn("Failed to expunge volume {} from the image store {} due to: {}", volume, dataStore, result.getResult());
                                        }
                                    }
                                }
                            }
                        } catch (Throwable th) {
                            logger.error("Unable to destroy uploaded volume [{}] due to: [{}].", volume, th.getMessage());
                            logger.debug("Unable to destroy uploaded volume [{}].", volume, th);
                        }
                    }

                    // destroy uploaded templates in abandoned/error state
                    List<TemplateDataStoreVO> templateDataStores = _templateStoreDao.listByTemplateState(VirtualMachineTemplate.State.UploadError, VirtualMachineTemplate.State.UploadAbandoned);
                    for (TemplateDataStoreVO templateDataStore : templateDataStores) {
                        VMTemplateVO template = _templateDao.findById(templateDataStore.getTemplateId());
                        if (template == null) {
                            logger.warn(String.format("Uploaded template [%s] not found, so cannot be destroyed.", templateDataStore.getTemplateId()));
                            continue;
                        }
                        try {
                            DataStore dataStore = _dataStoreMgr.getDataStore(templateDataStore.getDataStoreId(), DataStoreRole.Image);
                            EndPoint ep = _epSelector.select(dataStore, templateDataStore.getExtractUrl());
                            if (ep == null) {
                                logger.warn("Cannot destroy uploaded template {} as there is no secondary storage VM for image store {}.", template, dataStore);
                                continue;
                            }
                            Host host = _hostDao.findById(ep.getId());
                            if (host != null && host.getManagementServerId() != null) {
                                if (_serverId == host.getManagementServerId().longValue()) {
                                    AsyncCallFuture<TemplateApiResult> future = _imageSrv.deleteTemplateAsync(tmplFactory.getTemplate(template.getId(), dataStore));
                                    TemplateApiResult result = future.get();
                                    if (!result.isSuccess()) {
                                        logger.warn("Failed to delete template {} from the image store {} due to: {}", template, dataStore, result.getResult());
                                        continue;
                                    }
                                    // remove from template_zone_ref
                                    List<VMTemplateZoneVO> templateZones = _vmTemplateZoneDao.listByZoneTemplate(((ImageStoreEntity)dataStore).getDataCenterId(), template.getId());
                                    if (templateZones != null) {
                                        for (VMTemplateZoneVO templateZone : templateZones) {
                                            _vmTemplateZoneDao.remove(templateZone.getId());
                                        }
                                    }
                                    // mark all the occurrences of this template in the given store as destroyed
                                    _templateStoreDao.removeByTemplateStore(template.getId(), dataStore.getId());
                                    // find all eligible image stores for this template
                                    List<DataStore> imageStores = _tmpltMgr.getImageStoreByTemplate(template.getId(), null);
                                    if (imageStores == null || imageStores.size() == 0) {
                                        template.setState(VirtualMachineTemplate.State.Inactive);
                                        _templateDao.update(template.getId(), template);

                                        // decrement template resource count
                                        _resourceLimitMgr.decrementResourceCount(template.getAccountId(), ResourceType.template);
                                    }
                                }
                            }
                        } catch (Throwable th) {
                            logger.error("Unable to destroy uploaded template [{}] due to: [{}].", template, th.getMessage());
                            logger.debug("Unable to destroy uploaded template [{}].", template, th);
                        }
                    }
                    cleanupInactiveTemplates();
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }

    protected boolean isVolumeSuspectedDestroyDuplicateOfVmVolume(VolumeVO gcVolume) {
        if (gcVolume.getPath() == null) {
            return false;
        }
        if (gcVolume.getPoolId() == null) {
            return false;
        }
        Long vmId = gcVolume.getInstanceId();
        if (vmId == null) {
            return false;
        }
        VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm == null) {
            return false;
        }
        List<VolumeVO> vmUsableVolumes = volumeDao.findUsableVolumesForInstance(vmId);
        for (VolumeVO vol : vmUsableVolumes) {
            if (gcVolume.getPoolId().equals(vol.getPoolId()) && gcVolume.getPath().equals(vol.getPath())) {
                logger.debug(String.format("%s meant for garbage collection could a possible duplicate for %s", gcVolume, vol));
                return true;
            }
        }
        return false;
    }

    /**
     * This method only applies for managed storage.
     *
     * For XenServer and vSphere, see if we need to remove an SR or a datastore, then remove the underlying volume
     * from any applicable access control list (before other code attempts to delete the volume that supports it).
     *
     * For KVM, just tell the underlying storage plug-in to remove the volume from any applicable access control list
     * (before other code attempts to delete the volume that supports it).
     */
    private void handleManagedStorage(Volume volume) {
        Long instanceId = volume.getInstanceId();

        if (instanceId != null) {
            StoragePoolVO storagePool = _storagePoolDao.findById(volume.getPoolId());

            if (storagePool != null && storagePool.isManaged()) {
                VMInstanceVO vmInstanceVO = _vmInstanceDao.findById(instanceId);
                if (vmInstanceVO == null) {
                    return;
                }

                Long lastHostId = vmInstanceVO.getLastHostId();

                if (lastHostId != null) {
                    HostVO host = _hostDao.findById(lastHostId);
                    ClusterVO cluster = _clusterDao.findById(host.getClusterId());
                    VolumeInfo volumeInfo = volFactory.getVolume(volume.getId());

                    if (cluster.getHypervisorType() == HypervisorType.KVM) {
                        volService.revokeAccess(volumeInfo, host, volumeInfo.getDataStore());
                    } else {
                        DataTO volTO = volFactory.getVolume(volume.getId()).getTO();
                        DiskTO disk = new DiskTO(volTO, volume.getDeviceId(), volume.getPath(), volume.getVolumeType());

                        DettachCommand cmd = new DettachCommand(disk, null);

                        cmd.setManaged(true);

                        cmd.setStorageHost(storagePool.getHostAddress());
                        cmd.setStoragePort(storagePool.getPort());

                        cmd.set_iScsiName(volume.get_iScsiName());

                        Answer answer = _agentMgr.easySend(lastHostId, cmd);

                        if (answer != null && answer.getResult()) {
                            volService.revokeAccess(volumeInfo, host, volumeInfo.getDataStore());
                        } else {
                            logger.warn("Unable to remove host-side clustered file system for the following volume: {}", volume);
                        }
                    }
                }
            }
        }
    }

    @DB
    List<Long> findAllVolumeIdInSnapshotTable(Long storeId) {
        String sql = "SELECT volume_id from snapshots, snapshot_store_ref WHERE snapshots.id = snapshot_store_ref.snapshot_id and store_id=? GROUP BY volume_id";
        List<Long> list = new ArrayList<>();
        try {
            TransactionLegacy txn = TransactionLegacy.currentTxn();
            ResultSet rs = null;
            PreparedStatement pstmt = null;
            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, storeId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                list.add(rs.getLong(1));
            }
            return list;
        } catch (Exception e) {
            logger.debug("failed to get all volumes who has snapshots in secondary storage " + storeId + " due to " + e.getMessage());
            return null;
        }

    }

    List<String> findAllSnapshotForVolume(Long volumeId) {
        String sql = "SELECT backup_snap_id FROM snapshots WHERE volume_id=? and backup_snap_id is not NULL";
        try {
            TransactionLegacy txn = TransactionLegacy.currentTxn();
            ResultSet rs = null;
            PreparedStatement pstmt = null;
            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, volumeId);
            rs = pstmt.executeQuery();
            List<String> list = new ArrayList<>();
            while (rs.next()) {
                list.add(rs.getString(1));
            }
            return list;
        } catch (Exception e) {
            logger.debug("failed to get all snapshots for a volume " + volumeId + " due to " + e.getMessage());
            return null;
        }
    }

    @Override
    @DB
    public void cleanupSecondaryStorage(boolean recurring) {
        // NOTE that object_store refactor will immediately delete the object from secondary storage when deleteTemplate etc api is issued.
        // so here we don't need to issue DeleteCommand to resource anymore, only need to remove db entry.
        try {
            // Cleanup templates in template_store_ref
            List<DataStore> imageStores = _dataStoreMgr.getImageStoresByScopeExcludingReadOnly(new ZoneScope(null));
            for (DataStore store : imageStores) {
                try {
                    long storeId = store.getId();
                    List<TemplateDataStoreVO> destroyedTemplateStoreVOs = _templateStoreDao.listDestroyed(storeId);
                    logger.debug("Secondary storage garbage collector found {} templates to cleanup on template_store_ref for store: {}", destroyedTemplateStoreVOs.size(), store);
                    for (TemplateDataStoreVO destroyedTemplateStoreVO : destroyedTemplateStoreVOs) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Deleting template store DB entry: " + destroyedTemplateStoreVO);
                        }
                        _templateStoreDao.remove(destroyedTemplateStoreVO.getId());
                    }
                } catch (Exception e) {
                    logger.warn("problem cleaning up templates in template_store_ref for store: {}", store, e);
                }
            }

            // CleanUp snapshots on snapshot_store_ref
            for (DataStore store : imageStores) {
                try {
                    List<SnapshotDataStoreVO> destroyedSnapshotStoreVOs = _snapshotStoreDao.listDestroyed(store.getId());
                    logger.debug("Secondary storage garbage collector found {} snapshots to cleanup on snapshot_store_ref for store: {}", destroyedSnapshotStoreVOs.size(), store);
                    for (SnapshotDataStoreVO destroyedSnapshotStoreVO : destroyedSnapshotStoreVOs) {
                        // check if this snapshot has child
                        SnapshotInfo snap = snapshotFactory.getSnapshot(destroyedSnapshotStoreVO.getSnapshotId(), store);
                        if (snap.getChild() != null) {
                            logger.debug("Skip snapshot on store: " + destroyedSnapshotStoreVO + " , because it has child");
                            continue;
                        }

                        if (logger.isDebugEnabled()) {
                            logger.debug("Deleting snapshot store DB entry: " + destroyedSnapshotStoreVO);
                        }

                        List<SnapshotDataStoreVO> imageStoreRefs = _snapshotStoreDao.listBySnapshotAndDataStoreRole(destroyedSnapshotStoreVO.getSnapshotId(), DataStoreRole.Image);
                        if (imageStoreRefs.size() <= 1) {
                            _snapshotDao.remove(destroyedSnapshotStoreVO.getSnapshotId());
                        }
                        SnapshotDataStoreVO snapshotOnPrimary = _snapshotStoreDao.findDestroyedReferenceBySnapshot(destroyedSnapshotStoreVO.getSnapshotId(), DataStoreRole.Primary);
                        if (snapshotOnPrimary != null) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Deleting snapshot on primary store reference DB entry: " + snapshotOnPrimary);
                            }
                            _snapshotStoreDao.remove(snapshotOnPrimary.getId());
                        }
                        _snapshotStoreDao.remove(destroyedSnapshotStoreVO.getId());
                    }

                } catch (Exception e2) {
                    logger.warn("problem cleaning up snapshots in snapshot_store_ref for store: {}", store, e2);
                }

            }

            // CleanUp volumes on volume_store_ref
            for (DataStore store : imageStores) {
                try {
                    List<VolumeDataStoreVO> destroyedStoreVOs = _volumeStoreDao.listDestroyed(store.getId());
                    destroyedStoreVOs.addAll(_volumeDataStoreDao.listByVolumeState(Volume.State.Expunged));
                    logger.debug("Secondary storage garbage collector found {} volumes to cleanup on volume_store_ref for store: {}", destroyedStoreVOs.size(), store);
                    for (VolumeDataStoreVO destroyedStoreVO : destroyedStoreVOs) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Deleting volume store DB entry: " + destroyedStoreVO);
                        }
                        _volumeStoreDao.remove(destroyedStoreVO.getId());
                    }

                } catch (Exception e2) {
                    logger.warn("problem cleaning up volumes in volume_store_ref for store: {}", store, e2);
                }
            }
        } catch (Exception e3) {
            logger.warn("problem cleaning up secondary storage DB entries. ", e3);
        }
    }

    @Override
    public String getPrimaryStorageNameLabel(VolumeVO volume) {
        Long poolId = volume.getPoolId();

        // poolId is null only if volume is destroyed, which has been checked
        // before.
        assert poolId != null;
        StoragePoolVO primaryDataStoreVO = _storagePoolDao.findById(poolId);
        assert primaryDataStoreVO != null;
        return primaryDataStoreVO.getUuid();
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_MAINTENANCE_PREPARE_PRIMARY_STORAGE,
            eventDescription = "preparing storage pool for maintenance", async = true)
    public PrimaryDataStoreInfo preparePrimaryStorageForMaintenance(Long primaryStorageId) throws ResourceUnavailableException, InsufficientCapacityException {
        StoragePoolVO primaryStorage = null;
        primaryStorage = _storagePoolDao.findById(primaryStorageId);

        if (primaryStorage == null) {
            String msg = "Unable to obtain lock on the storage pool record in preparePrimaryStorageForMaintenance()";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (!primaryStorage.getStatus().equals(StoragePoolStatus.Up) && !primaryStorage.getStatus().equals(StoragePoolStatus.ErrorInMaintenance)) {
            throw new InvalidParameterValueException(String.format("Primary storage %s is not ready for migration, as the status is:%s", primaryStorage, primaryStorage.getStatus().toString()));
        }

        DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
        DataStore store = _dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);

        if (primaryStorage.getPoolType() == StoragePoolType.DatastoreCluster) {
            if (primaryStorage.getStatus() == StoragePoolStatus.PrepareForMaintenance) {
                throw new CloudRuntimeException(String.format("There is already a job running for preparation for maintenance of the storage pool %s", primaryStorage));
            }
            handlePrepareDatastoreClusterMaintenance(lifeCycle, primaryStorageId);
        }
        lifeCycle.maintain(store);

        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
    }

    private void handlePrepareDatastoreClusterMaintenance(DataStoreLifeCycle lifeCycle, Long primaryStorageId) {
        StoragePoolVO datastoreCluster = _storagePoolDao.findById(primaryStorageId);
        datastoreCluster.setStatus(StoragePoolStatus.PrepareForMaintenance);
        _storagePoolDao.update(datastoreCluster.getId(), datastoreCluster);

        // Before preparing the datastorecluster to maintenance mode, the storagepools in the datastore cluster needs to put in maintenance
        List<StoragePoolVO> childDatastores = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(primaryStorageId);
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                for (StoragePoolVO childDatastore : childDatastores) {
                    // set the pool state to prepare for maintenance, so that VMs will not migrate to the storagepools in the same cluster
                    childDatastore.setStatus(StoragePoolStatus.PrepareForMaintenance);
                    _storagePoolDao.update(childDatastore.getId(), childDatastore);
                }
            }
        });
        for (Iterator<StoragePoolVO> iteratorChildDatastore = childDatastores.listIterator(); iteratorChildDatastore.hasNext(); ) {
            DataStore childStore = _dataStoreMgr.getDataStore(iteratorChildDatastore.next().getId(), DataStoreRole.Primary);
            try {
                lifeCycle.maintain(childStore);
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception on maintenance preparation of one of the child datastores in datastore cluster {} with error {}", datastoreCluster, e);
                }
                // Set to ErrorInMaintenance state of all child storage pools and datastore cluster
                for (StoragePoolVO childDatastore : childDatastores) {
                    childDatastore.setStatus(StoragePoolStatus.ErrorInMaintenance);
                    _storagePoolDao.update(childDatastore.getId(), childDatastore);
                }
                datastoreCluster.setStatus(StoragePoolStatus.ErrorInMaintenance);
                _storagePoolDao.update(datastoreCluster.getId(), datastoreCluster);
                throw new CloudRuntimeException(String.format("Failed to prepare maintenance mode for datastore cluster %s with error %s %s", datastoreCluster, e.getMessage(), e));
            }
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_MAINTENANCE_CANCEL_PRIMARY_STORAGE,
            eventDescription = "canceling maintenance for primary storage pool", async = true)
    public PrimaryDataStoreInfo cancelPrimaryStorageForMaintenance(CancelPrimaryStorageMaintenanceCmd cmd) throws ResourceUnavailableException {
        Long primaryStorageId = cmd.getId();
        StoragePoolVO primaryStorage = null;

        primaryStorage = _storagePoolDao.findById(primaryStorageId);

        if (primaryStorage == null) {
            String msg = "Unable to obtain lock on the storage pool in cancelPrimaryStorageForMaintenance()";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (primaryStorage.getStatus().equals(StoragePoolStatus.Up) || primaryStorage.getStatus().equals(StoragePoolStatus.PrepareForMaintenance)) {
            throw new StorageUnavailableException("Primary storage " + primaryStorage + " is not ready to complete migration, as the status is:" + primaryStorage.getStatus().toString(),
                    primaryStorageId);
        }

        DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
        DataStore store = _dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
        if (primaryStorage.getPoolType() == StoragePoolType.DatastoreCluster) {
            primaryStorage.setStatus(StoragePoolStatus.Up);
            _storagePoolDao.update(primaryStorage.getId(), primaryStorage);
            //FR41 need to handle when one of the primary stores is unable to cancel the maintenance mode
            List<StoragePoolVO> childDatastores = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(primaryStorageId);
            for (StoragePoolVO childDatastore : childDatastores) {
                DataStore childStore = _dataStoreMgr.getDataStore(childDatastore.getId(), DataStoreRole.Primary);
                lifeCycle.cancelMaintain(childStore);
            }
        }
        lifeCycle.cancelMaintain(store);

        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SYNC_STORAGE_POOL, eventDescription = "synchronising storage pool with management server", async = true)
    public StoragePool syncStoragePool(SyncStoragePoolCmd cmd) {
        Long poolId = cmd.getPoolId();
        StoragePoolVO pool = _storagePoolDao.findById(poolId);

        if (pool == null) {
            String msg = String.format("Unable to find the storage pool with id %d record while syncing storage pool with management server", poolId);
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (!pool.getPoolType().equals(StoragePoolType.DatastoreCluster)) {
            throw new InvalidParameterValueException("SyncStoragePool API is currently supported only for storage type of datastore cluster");
        }

        if (!pool.getStatus().equals(StoragePoolStatus.Up)) {
            throw new InvalidParameterValueException(String.format("Primary storage %s is not ready for syncing, as the status is %s", pool, pool.getStatus().toString()));
        }

        // find the host
        List<Long> poolIds = new ArrayList<>();
        poolIds.add(poolId);
        List<Long> hosts = _storagePoolHostDao.findHostsConnectedToPools(poolIds);
        if (hosts.size() > 0) {
            Long hostId = hosts.get(0);
            ModifyStoragePoolCommand modifyStoragePoolCommand = new ModifyStoragePoolCommand(true, pool);
            final Answer answer = _agentMgr.easySend(hostId, modifyStoragePoolCommand);

            if (answer == null) {
                throw new CloudRuntimeException(String.format("Unable to get an answer to the modify storage pool command %s", pool));
            }

            if (!answer.getResult()) {
                throw new CloudRuntimeException(String.format("Unable to process ModifyStoragePoolCommand for pool %s on the host %s due to %s", pool, _hostDao.findById(hostId), answer.getDetails()));
            }

            assert (answer instanceof ModifyStoragePoolAnswer) : "Well, now why won't you actually return the ModifyStoragePoolAnswer when it's ModifyStoragePoolCommand? Pool=" +
                    pool.getId() + "Host=" + hostId;
            ModifyStoragePoolAnswer mspAnswer = (ModifyStoragePoolAnswer) answer;
            StoragePoolVO poolVO = _storagePoolDao.findById(poolId);
            updateStoragePoolHostVOAndBytes(poolVO, hostId, mspAnswer);
            validateChildDatastoresToBeAddedInUpState(poolVO, mspAnswer.getDatastoreClusterChildren());
            syncDatastoreClusterStoragePool(poolId, mspAnswer.getDatastoreClusterChildren(), hostId);
            for (ModifyStoragePoolAnswer childDataStoreAnswer : mspAnswer.getDatastoreClusterChildren()) {
                StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();
                StoragePoolVO dataStoreVO = _storagePoolDao.findPoolByUUID(childStoragePoolInfo.getUuid());
                for (Long host : hosts) {
                    updateStoragePoolHostVOAndBytes(dataStoreVO, host, childDataStoreAnswer);
                }
            }

        } else {
            throw new CloudRuntimeException(String.format("Unable to sync storage pool [%s] as there no connected hosts to the storage pool", pool));
        }
        return (PrimaryDataStoreInfo) _dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
    }


    @Override
    public Heuristic createSecondaryStorageHeuristic(CreateSecondaryStorageSelectorCmd cmd) {
        String name = cmd.getName();
        String description = cmd.getDescription();
        long zoneId = cmd.getZoneId();
        String heuristicRule = cmd.getHeuristicRule();
        String type = cmd.getType();
        HeuristicType formattedType = EnumUtils.getEnumIgnoreCase(HeuristicType.class, type);

        if (formattedType == null) {
            throw new IllegalArgumentException(String.format("The given heuristic type [%s] is not valid for creating a new secondary storage selector." +
                    " The valid options are %s.", type, Arrays.asList(HeuristicType.values())));
        }

        HeuristicVO heuristic = secondaryStorageHeuristicDao.findByZoneIdAndType(zoneId, formattedType);

        if (heuristic != null) {
            DataCenterVO dataCenter = _dcDao.findById(zoneId);
            throw new CloudRuntimeException(String.format("There is already a heuristic rule in the specified %s with the type [%s].",
                    dataCenter, type));
        }

        validateHeuristicRule(heuristicRule);

        HeuristicVO heuristicVO = new HeuristicVO(name, description, zoneId, formattedType.toString(), heuristicRule);
        return secondaryStorageHeuristicDao.persist(heuristicVO);
    }

    @Override
    public Heuristic updateSecondaryStorageHeuristic(UpdateSecondaryStorageSelectorCmd cmd) {
        long heuristicId = cmd.getId();
        String heuristicRule = cmd.getHeuristicRule();

        HeuristicVO heuristicVO = secondaryStorageHeuristicDao.findById(heuristicId);
        validateHeuristicRule(heuristicRule);
        heuristicVO.setHeuristicRule(heuristicRule);

        return secondaryStorageHeuristicDao.persist(heuristicVO);
    }

    @Override
    public void removeSecondaryStorageHeuristic(RemoveSecondaryStorageSelectorCmd cmd) {
        Long heuristicId = cmd.getId();
        HeuristicVO heuristicVO = secondaryStorageHeuristicDao.findById(heuristicId);

        if (heuristicVO != null) {
            secondaryStorageHeuristicDao.remove(heuristicId);
        } else {
            throw new CloudRuntimeException("Unable to find an active heuristic with the specified UUID.");
        }
    }

    protected void validateHeuristicRule(String heuristicRule) {
        if (StringUtils.isBlank(heuristicRule)) {
            throw new IllegalArgumentException("Unable to create a new secondary storage selector as the given heuristic rule is blank.");
        }
    }

    public void syncDatastoreClusterStoragePool(long datastoreClusterPoolId, List<ModifyStoragePoolAnswer> childDatastoreAnswerList, long hostId) {
        StoragePoolVO datastoreClusterPool = _storagePoolDao.findById(datastoreClusterPoolId);
        List<StoragePoolTagVO> storageTags = _storagePoolTagsDao.findStoragePoolTags(datastoreClusterPoolId);
        List<StoragePoolVO> childDatastores = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(datastoreClusterPoolId);
        Set<String> childDatastoreUUIDs = new HashSet<>();
        for (StoragePoolVO childDatastore : childDatastores) {
            childDatastoreUUIDs.add(childDatastore.getUuid());
        }

        for (ModifyStoragePoolAnswer childDataStoreAnswer : childDatastoreAnswerList) {
            StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();
            StoragePoolVO dataStoreVO = getExistingPoolByUuid(childStoragePoolInfo.getUuid());
            if (dataStoreVO == null && childDataStoreAnswer.getPoolType().equalsIgnoreCase("NFS")) {
                List<StoragePoolVO> nfsStoragePools = _storagePoolDao.findPoolsByStorageType(StoragePoolType.NetworkFilesystem);
                for (StoragePoolVO storagePool : nfsStoragePools) {
                    String storagePoolUUID = storagePool.getUuid();
                    if (childStoragePoolInfo.getName().equalsIgnoreCase(storagePoolUUID.replaceAll("-", ""))) {
                        dataStoreVO = storagePool;
                        break;
                    }
                }
            }
            if (dataStoreVO != null) {
                if (dataStoreVO.getParent() != datastoreClusterPoolId) {
                    logger.debug(String.format("Storage pool %s with uuid %s is found to be under datastore cluster %s at vCenter, " +
                                    "so moving the storage pool to be a child storage pool under the datastore cluster in CloudStack management server",
                            childStoragePoolInfo.getName(), childStoragePoolInfo.getUuid(), datastoreClusterPool.getName()));
                    dataStoreVO.setParent(datastoreClusterPoolId);
                    _storagePoolDao.update(dataStoreVO.getId(), dataStoreVO);
                    if (CollectionUtils.isNotEmpty(storageTags)) {
                        storageTags.addAll(_storagePoolTagsDao.findStoragePoolTags(dataStoreVO.getId()));
                    } else {
                        storageTags = _storagePoolTagsDao.findStoragePoolTags(dataStoreVO.getId());
                    }
                    if (CollectionUtils.isNotEmpty(storageTags)) {
                        Set<StoragePoolTagVO> set = new LinkedHashSet<>(storageTags);
                        storageTags.clear();
                        storageTags.addAll(set);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Updating Storage Pool Tags to :" + storageTags);
                        }
                        _storagePoolTagsDao.persist(storageTags);
                    }
                } else {
                    // This is to find datastores which are removed from datastore cluster.
                    // The final set childDatastoreUUIDs contains the UUIDs of child datastores which needs to be removed from datastore cluster
                    childDatastoreUUIDs.remove(dataStoreVO.getUuid());
                }
            } else {
                dataStoreVO = createChildDatastoreVO(datastoreClusterPool, childDataStoreAnswer, storageTags);
            }
            updateStoragePoolHostVOAndBytes(dataStoreVO, hostId, childDataStoreAnswer);
        }

        handleRemoveChildStoragePoolFromDatastoreCluster(childDatastoreUUIDs);
    }

    private StoragePoolVO getExistingPoolByUuid(String uuid){
        if(!uuid.contains("-")){
            UUID poolUuid = new UUID(
                new BigInteger(uuid.substring(0, 16), 16).longValue(),
                new BigInteger(uuid.substring(16), 16).longValue()
            );
            uuid = poolUuid.toString();
        }
        return _storagePoolDao.findByUuid(uuid);
    }

    public void validateChildDatastoresToBeAddedInUpState(StoragePoolVO datastoreClusterPool, List<ModifyStoragePoolAnswer> childDatastoreAnswerList) {
        for (ModifyStoragePoolAnswer childDataStoreAnswer : childDatastoreAnswerList) {
            StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();
            StoragePoolVO dataStoreVO = _storagePoolDao.findPoolByUUID(childStoragePoolInfo.getUuid());
            if (dataStoreVO == null && childDataStoreAnswer.getPoolType().equalsIgnoreCase("NFS")) {
                List<StoragePoolVO> nfsStoragePools = _storagePoolDao.findPoolsByStorageType(StoragePoolType.NetworkFilesystem);
                for (StoragePoolVO storagePool : nfsStoragePools) {
                    String storagePoolUUID = storagePool.getUuid();
                    if (childStoragePoolInfo.getName().equalsIgnoreCase(storagePoolUUID.replaceAll("-", ""))) {
                          dataStoreVO = storagePool;
                          break;
                    }
                }
            }
            if (dataStoreVO != null && !dataStoreVO.getStatus().equals(StoragePoolStatus.Up)) {
                String msg = String.format("Cannot synchronise datastore cluster %s because primary storage %s is not in Up state, " +
                        "current state is %s", datastoreClusterPool, dataStoreVO, dataStoreVO.getStatus().toString());
                throw new CloudRuntimeException(msg);
            }
        }
    }

    private StoragePoolVO createChildDatastoreVO(StoragePoolVO datastoreClusterPool, ModifyStoragePoolAnswer childDataStoreAnswer, List<StoragePoolTagVO> storagePoolTagVOList) {
        StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();

        StoragePoolVO dataStoreVO = new StoragePoolVO();
        dataStoreVO.setStorageProviderName(datastoreClusterPool.getStorageProviderName());
        dataStoreVO.setHostAddress(childStoragePoolInfo.getHost());
        dataStoreVO.setPoolType(Storage.StoragePoolType.PreSetup);
        dataStoreVO.setPath(childStoragePoolInfo.getHostPath());
        dataStoreVO.setPort(datastoreClusterPool.getPort());
        dataStoreVO.setName(childStoragePoolInfo.getName());
        dataStoreVO.setUuid(childStoragePoolInfo.getUuid());
        dataStoreVO.setDataCenterId(datastoreClusterPool.getDataCenterId());
        dataStoreVO.setPodId(datastoreClusterPool.getPodId());
        dataStoreVO.setClusterId(datastoreClusterPool.getClusterId());
        dataStoreVO.setStatus(StoragePoolStatus.Up);
        dataStoreVO.setUserInfo(datastoreClusterPool.getUserInfo());
        dataStoreVO.setManaged(datastoreClusterPool.isManaged());
        dataStoreVO.setCapacityIops(datastoreClusterPool.getCapacityIops());
        dataStoreVO.setCapacityBytes(childDataStoreAnswer.getPoolInfo().getCapacityBytes());
        dataStoreVO.setUsedBytes(childDataStoreAnswer.getPoolInfo().getCapacityBytes() - childDataStoreAnswer.getPoolInfo().getAvailableBytes());
        dataStoreVO.setHypervisor(datastoreClusterPool.getHypervisor());
        dataStoreVO.setScope(datastoreClusterPool.getScope());
        dataStoreVO.setParent(datastoreClusterPool.getId());

        Map<String, String> details = new HashMap<>();
        if(StringUtils.isNotEmpty(childDataStoreAnswer.getPoolType())) {
            details.put("pool_type", childDataStoreAnswer.getPoolType());
        }

        List<String> storagePoolTags = new ArrayList<>();
        boolean isTagARule = false;
        if (CollectionUtils.isNotEmpty(storagePoolTagVOList)) {
            storagePoolTags = storagePoolTagVOList.parallelStream().map(StoragePoolTagVO::getTag).collect(Collectors.toList());
            isTagARule = storagePoolTagVOList.get(0).isTagARule();
        }
        List<String> storageAccessGroups = _storagePoolAccessGroupMapDao.getStorageAccessGroups(datastoreClusterPool.getId());

        _storagePoolDao.persist(dataStoreVO, details, storagePoolTags, isTagARule, storageAccessGroups);
        return dataStoreVO;
    }

    @Override
    public boolean checkIfHostAndStoragePoolHasCommonStorageAccessGroups(Host host, StoragePool pool) {
        String[] hostStorageAccessGroups = getStorageAccessGroups(null, null, null, host.getId());
        List<String> storagePoolAccessGroups = _storagePoolAccessGroupMapDao.getStorageAccessGroups(pool.getId());

        if (CollectionUtils.isEmpty(storagePoolAccessGroups)) {
            return true;
        }

        if (ArrayUtils.isEmpty(hostStorageAccessGroups)) {
            return false;
        }

        if (ArrayUtils.isNotEmpty(hostStorageAccessGroups)) {
            logger.debug(String.format("Storage access groups on the host %s are %s", host, hostStorageAccessGroups));
        }

        if (CollectionUtils.isNotEmpty(storagePoolAccessGroups)) {
            logger.debug(String.format("Storage access groups on the storage pool %s are %s", host, storagePoolAccessGroups));
        }

        List<String> hostTagList = Arrays.asList(hostStorageAccessGroups);
        return CollectionUtils.containsAny(hostTagList, storagePoolAccessGroups);
    }

    @Override
    public Pair<Boolean, String> checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(StoragePool destPool, Volume volume) {
        if (Volume.State.Ready.equals(volume.getState())) {
            Long vmId = volume.getInstanceId();
            VMInstanceVO vm = null;
            if (vmId != null) {
                vm = _vmInstanceDao.findById(vmId);
            }

            if (vm == null || State.Stopped.equals(vm.getState())) {
                Long srcPoolId = volume.getPoolId();
                StoragePoolVO srcPool = _storagePoolDao.findById(srcPoolId);
                List<String> srcStorageAccessGroups = _storagePoolAccessGroupMapDao.getStorageAccessGroups(srcPoolId);
                List<String> destStorageAccessGroups = _storagePoolAccessGroupMapDao.getStorageAccessGroups(destPool.getId());

                if (CollectionUtils.isNotEmpty(srcStorageAccessGroups) && CollectionUtils.isNotEmpty(destStorageAccessGroups)) {
                    logger.debug(String.format("Storage access groups on source storage %s are %s and destination storage %s are %s",
                            srcPool, srcStorageAccessGroups, destPool, destStorageAccessGroups));
                    List<String> intersection = new ArrayList<>(srcStorageAccessGroups);
                    intersection.retainAll(destStorageAccessGroups);
                    if (CollectionUtils.isNotEmpty(intersection)) {
                        return new Pair<>(true, "Success");
                    } else {
                        List<Long> poolIds = new ArrayList<>();
                        poolIds.add(srcPool.getId());
                        poolIds.add(destPool.getId());
                        Host hostWithPoolsAccess = findUpAndEnabledHostWithAccessToStoragePools(poolIds);
                        if (hostWithPoolsAccess == null) {
                            logger.debug("Storage access groups on source and destination storages do not match, and there is no common host connected to these storages");
                            return new Pair<>(false, "No common host connected to source and destination storages");
                        }
                    }
                }
                return new Pair<>(true, "Success");
            } else {
                if (State.Running.equals(vm.getState())) {
                    Long hostId = vm.getHostId();
                    String[] hostStorageAccessGroups = getStorageAccessGroups(null, null, null, hostId);
                    Long srcPoolId = volume.getPoolId();
                    StoragePoolVO srcPool = _storagePoolDao.findById(srcPoolId);
                    List<String> srcStorageAccessGroups = _storagePoolAccessGroupMapDao.getStorageAccessGroups(srcPoolId);
                    List<String> destStorageAccessGroups = _storagePoolAccessGroupMapDao.getStorageAccessGroups(destPool.getId());

                    logger.debug(String.format("Storage access groups on source storage %s are %s and destination storage %s are %s",
                            srcPool, srcStorageAccessGroups, destPool, destStorageAccessGroups));

                    if (CollectionUtils.isEmpty(srcStorageAccessGroups) && CollectionUtils.isEmpty(destStorageAccessGroups)) {
                        return new Pair<>(true, "Success");
                    }

                    if (CollectionUtils.isNotEmpty(srcStorageAccessGroups) && CollectionUtils.isNotEmpty(destStorageAccessGroups)) {
                        List<String> intersection = new ArrayList<>(srcStorageAccessGroups);
                        intersection.retainAll(destStorageAccessGroups);

                        if (ArrayUtils.isNotEmpty(hostStorageAccessGroups)) {
                            boolean hasSrcCommon = srcStorageAccessGroups.stream()
                                    .anyMatch(group -> Arrays.asList(hostStorageAccessGroups).contains(group));
                            boolean hasDestCommon = destStorageAccessGroups.stream()
                                    .anyMatch(group -> Arrays.asList(hostStorageAccessGroups).contains(group));
                            if (hasSrcCommon && hasDestCommon) {
                                return new Pair<>(true, "Success");
                            }
                        }

                        return new Pair<>(false, "No common storage access groups between source, destination pools and host");
                    }

                    if (CollectionUtils.isEmpty(srcStorageAccessGroups)) {
                        if (ArrayUtils.isNotEmpty(hostStorageAccessGroups)) {
                            List<String> hostAccessGroupList = Arrays.asList(hostStorageAccessGroups);
                            hostAccessGroupList.retainAll(destStorageAccessGroups);
                            if (CollectionUtils.isNotEmpty(hostAccessGroupList)) {
                                return new Pair<>(true, "Success");
                            }
                        }
                        return new Pair<>(false, "Host lacks access to destination storage groups");
                    }

                    return new Pair<>(true, "Success");
                }
            }
        }
        return new Pair<>(true, "Success");
    }

    @Override
    public String[] getStorageAccessGroups(Long zoneId, Long podId, Long clusterId, Long hostId) {
        List<String> storageAccessGroups = new ArrayList<>();
        if (hostId != null) {
            HostVO host = _hostDao.findById(hostId);
            ClusterVO cluster = _clusterDao.findById(host.getClusterId());
            HostPodVO pod = _podDao.findById(cluster.getPodId());
            DataCenterVO zone = _dcDao.findById(pod.getDataCenterId());
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(host.getStorageAccessGroups(), cluster.getStorageAccessGroups(), pod.getStorageAccessGroups(), zone.getStorageAccessGroups())));
        } else if (clusterId != null) {
            ClusterVO cluster = _clusterDao.findById(clusterId);
            HostPodVO pod = _podDao.findById(cluster.getPodId());
            DataCenterVO zone = _dcDao.findById(pod.getDataCenterId());
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(cluster.getStorageAccessGroups(), pod.getStorageAccessGroups(), zone.getStorageAccessGroups())));
        } else if (podId != null) {
            HostPodVO pod = _podDao.findById(podId);
            DataCenterVO zone = _dcDao.findById(pod.getDataCenterId());
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(pod.getStorageAccessGroups(), zone.getStorageAccessGroups())));
        } else if (zoneId != null) {
            DataCenterVO zone = _dcDao.findById(zoneId);
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(zone.getStorageAccessGroups())));
        }

        storageAccessGroups.removeIf(tag -> tag == null || tag.trim().isEmpty());

        return storageAccessGroups.isEmpty()
                ? new String[0]
                : storageAccessGroups.toArray(org.apache.commons.lang.ArrayUtils.EMPTY_STRING_ARRAY);
    }

    private void handleRemoveChildStoragePoolFromDatastoreCluster(Set<String> childDatastoreUUIDs) {

        for (String childDatastoreUUID : childDatastoreUUIDs) {
            StoragePoolVO dataStoreVO = _storagePoolDao.findPoolByUUID(childDatastoreUUID);
            List<VolumeVO> allVolumes = volumeDao.findByPoolId(dataStoreVO.getId());
            allVolumes.removeIf(volumeVO -> volumeVO.getInstanceId() == null);
            allVolumes.removeIf(volumeVO -> volumeVO.getState() != Volume.State.Ready);
            for (VolumeVO volume : allVolumes) {
                VMInstanceVO vmInstance = _vmInstanceDao.findById(volume.getInstanceId());
                if (vmInstance == null) {
                    continue;
                }
                long volumeId = volume.getId();
                Long hostId = vmInstance.getHostId();
                if (hostId == null) {
                    hostId = vmInstance.getLastHostId();
                }
                HostVO hostVO = _hostDao.findById(hostId);

                // Prepare for the syncvolumepath command
                DataTO volTO = volFactory.getVolume(volume.getId()).getTO();
                DiskTO disk = new DiskTO(volTO, volume.getDeviceId(), volume.getPath(), volume.getVolumeType());
                Map<String, String> details = new HashMap<>();
                details.put(DiskTO.PROTOCOL_TYPE, Storage.StoragePoolType.DatastoreCluster.toString());
                disk.setDetails(details);

                logger.debug("Attempting to process SyncVolumePathCommand for the volume {} on the host {} with state {}", volume, hostVO, hostVO.getResourceState());
                SyncVolumePathCommand cmd = new SyncVolumePathCommand(disk);
                final Answer answer = _agentMgr.easySend(hostId, cmd);
                // validate answer
                if (answer == null) {
                    throw new CloudRuntimeException(String.format("Unable to get an answer to the SyncVolumePath command for volume %s", volume));
                }
                if (!answer.getResult()) {
                    throw new CloudRuntimeException(String.format("Unable to process SyncVolumePathCommand for the volume %s to the host %s due to %s", volume, hostVO, answer.getDetails()));
                }
                assert (answer instanceof SyncVolumePathAnswer) : String.format("Well, now why won't you actually return the SyncVolumePathAnswer when it's SyncVolumePathCommand? volume=%s Host=%s", volume, hostVO);

                // check for the changed details of volume and update database
                VolumeVO volumeVO = volumeDao.findById(volumeId);
                String datastoreName = answer.getContextParam("datastoreName");
                if (datastoreName != null) {
                    StoragePoolVO storagePoolVO = _storagePoolDao.findByUuid(datastoreName);
                    if (storagePoolVO != null) {
                        volumeVO.setPoolId(storagePoolVO.getId());
                    } else {
                        logger.warn("Unable to find datastore {} while updating the new datastore of the volume {}", datastoreName, volumeVO);
                    }
                }

                String volumePath = answer.getContextParam("volumePath");
                if (volumePath != null) {
                    volumeVO.setPath(volumePath);
                }

                String chainInfo = answer.getContextParam("chainInfo");
                if (chainInfo != null) {
                    volumeVO.setChainInfo(chainInfo);
                }

                volumeDao.update(volumeVO.getId(), volumeVO);
            }
            dataStoreVO.setParent(0L);
            _storagePoolDao.update(dataStoreVO.getId(), dataStoreVO);
        }

    }

    private void updateStoragePoolHostVOAndBytes(StoragePool pool, long hostId, ModifyStoragePoolAnswer mspAnswer) {
        StoragePoolHostVO poolHost = _storagePoolHostDao.findByPoolHost(pool.getId(), hostId);
        if (poolHost == null) {
            poolHost = new StoragePoolHostVO(pool.getId(), hostId, mspAnswer.getPoolInfo().getLocalPath().replaceAll("//", "/"));
            _storagePoolHostDao.persist(poolHost);
        } else {
            poolHost.setLocalPath(mspAnswer.getPoolInfo().getLocalPath().replaceAll("//", "/"));
        }

        StoragePoolVO poolVO = _storagePoolDao.findById(pool.getId());
        poolVO.setUsedBytes(mspAnswer.getPoolInfo().getCapacityBytes() - mspAnswer.getPoolInfo().getAvailableBytes());
        poolVO.setCapacityBytes(mspAnswer.getPoolInfo().getCapacityBytes());

        _storagePoolDao.update(pool.getId(), poolVO);
    }

    protected class StorageGarbageCollector extends ManagedContextRunnable {

        public StorageGarbageCollector() {
        }

        @Override
        protected void runInContext() {
            try {
                logger.trace("Storage Garbage Collection Thread is running.");

                cleanupStorage(true);

            } catch (Exception e) {
                logger.error("Caught the following Exception", e);
            }
        }
    }

    @Override
    public void onManagementNodeJoined(List<? extends ManagementServerHost> nodeList, long selfNodeId) {
    }

    @Override
    public void onManagementNodeLeft(List<? extends ManagementServerHost> nodeList, long selfNodeId) {
        for (ManagementServerHost vo : nodeList) {
            if (vo.getMsid() == _serverId) {
                logger.info("Cleaning up storage maintenance jobs associated with Management server: {}", vo);
                List<Long> poolIds = _storagePoolWorkDao.searchForPoolIdsForPendingWorkJobs(vo.getMsid());
                if (poolIds.size() > 0) {
                    for (Long poolId : poolIds) {
                        StoragePoolVO pool = _storagePoolDao.findById(poolId);
                        // check if pool is in an inconsistent state
                        if (pool != null && (pool.getStatus().equals(StoragePoolStatus.ErrorInMaintenance) || pool.getStatus().equals(StoragePoolStatus.PrepareForMaintenance)
                                || pool.getStatus().equals(StoragePoolStatus.CancelMaintenance))) {
                            _storagePoolWorkDao.removePendingJobsOnMsRestart(vo.getMsid(), poolId);
                            pool.setStatus(StoragePoolStatus.ErrorInMaintenance);
                            _storagePoolDao.update(poolId, pool);
                        }

                    }
                }
            }
        }
    }

    @Override
    public void onManagementNodeIsolated() {
    }

    @Override
    public CapacityVO getSecondaryStorageUsedStats(Long hostId, Long zoneId) {
        SearchCriteria<HostVO> sc = _hostDao.createSearchCriteria();
        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }

        List<Long> hosts = new ArrayList<>();
        if (hostId != null) {
            hosts.add(hostId);
        } else {
            List<DataStore> stores = _dataStoreMgr.getImageStoresByScope(new ZoneScope(zoneId));
            if (stores != null) {
                for (DataStore store : stores) {
                    hosts.add(store.getId());
                }
            }
        }

        CapacityVO capacity = new CapacityVO(hostId, zoneId, null, null, 0, 0, Capacity.CAPACITY_TYPE_SECONDARY_STORAGE);
        for (Long id : hosts) {
            StorageStats stats = ApiDBUtils.getSecondaryStorageStatistics(id);
            if (stats == null) {
                continue;
            }
            capacity.setUsedCapacity(stats.getByteUsed() + capacity.getUsedCapacity());
            capacity.setTotalCapacity(stats.getCapacityBytes() + capacity.getTotalCapacity());
        }

        return capacity;
    }

    private CapacityVO getStoragePoolUsedStatsInternal(Long zoneId, Long podId, Long clusterId, List<Long> poolIds, Long poolId) {
        SearchCriteria<StoragePoolVO> sc = _storagePoolDao.createSearchCriteria();
        List<StoragePoolVO> pools = new ArrayList<>();

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }
        if (podId != null) {
            sc.addAnd("podId", SearchCriteria.Op.EQ, podId);
        }
        if (clusterId != null) {
            sc.addAnd("clusterId", SearchCriteria.Op.EQ, clusterId);
        }
        if (CollectionUtils.isNotEmpty(poolIds)) {
            sc.addAnd("id", SearchCriteria.Op.IN, poolIds.toArray());
        }
        if (poolId != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, poolId);
        }
        sc.addAnd("parent", SearchCriteria.Op.EQ, 0L);
        if (poolId != null) {
            pools.add(_storagePoolDao.findById(poolId));
        } else {
            pools = _storagePoolDao.search(sc, null);
        }

        CapacityVO capacity = new CapacityVO(poolId, zoneId, podId, clusterId, 0, 0, Capacity.CAPACITY_TYPE_STORAGE);
        for (StoragePoolVO primaryDataStoreVO : pools) {
            StorageStats stats = ApiDBUtils.getStoragePoolStatistics(primaryDataStoreVO.getId());
            if (stats == null) {
                continue;
            }
            capacity.setUsedCapacity(stats.getByteUsed() + capacity.getUsedCapacity());
            capacity.setTotalCapacity(stats.getCapacityBytes() + capacity.getTotalCapacity());
        }
        return capacity;

    }

    @Override
    public CapacityVO getStoragePoolUsedStats(Long poolId, Long clusterId, Long podId, Long zoneId) {
        return getStoragePoolUsedStatsInternal(zoneId, podId, clusterId, null, poolId);
    }

    @Override
    public CapacityVO getStoragePoolUsedStats(Long zoneId, Long podId, Long clusterId, List<Long> poolIds) {
        return getStoragePoolUsedStatsInternal(zoneId, podId, clusterId, poolIds, null);
    }

    @Override
    public PrimaryDataStoreInfo getStoragePool(long id) {
        return (PrimaryDataStoreInfo)_dataStoreMgr.getDataStore(id, DataStoreRole.Primary);
    }

    @Override
    @DB
    public List<VMInstanceVO> listByStoragePool(long storagePoolId) {
        SearchCriteria<VMInstanceVO> sc = StoragePoolSearch.create();
        sc.setJoinParameters("vmVolume", "volumeType", Volume.Type.ROOT);
        sc.setJoinParameters("vmVolume", "poolId", storagePoolId);
        sc.setJoinParameters("vmVolume", "state", Volume.State.Ready);
        return _vmInstanceDao.search(sc, null);
    }

    @Override
    @DB
    public StoragePoolVO findLocalStorageOnHost(long hostId) {
        SearchCriteria<StoragePoolVO> sc = LocalStorageSearch.create();
        sc.setParameters("type", StoragePoolType.Filesystem, StoragePoolType.LVM);
        sc.setJoinParameters("poolHost", "hostId", hostId);
        List<StoragePoolVO> storagePools = _storagePoolDao.search(sc, null);
        if (!storagePools.isEmpty()) {
            return storagePools.get(0);
        } else {
            return null;
        }
    }

    @Override
    public Host findUpAndEnabledHostWithAccessToStoragePools(List<Long> poolIds) {
        List<Long> hostIds = _storagePoolHostDao.findHostsConnectedToPools(poolIds);
        if (hostIds.isEmpty()) {
            return null;
        }
        Collections.shuffle(hostIds);

        for (Long hostId : hostIds) {
            Host host = _hostDao.findById(hostId);
            if (canHostAccessStoragePools(host, poolIds)) {
                return host;
            }
        }

        return null;
    }

    private boolean canHostAccessStoragePools(Host host, List<Long> poolIds) {
        if (poolIds == null || poolIds.isEmpty()) {
            return false;
        }

        for (Long poolId : poolIds) {
            StoragePool pool = _storagePoolDao.findById(poolId);
            if (!canHostAccessStoragePool(host, pool)) {
                return false;
            }
        }

        return true;
    }

    @Override
    @DB
    public List<StoragePoolHostVO> findStoragePoolsConnectedToHost(long hostId) {
        return _storagePoolHostDao.listByHostId(hostId);
    }

    @Override
    public boolean canHostAccessStoragePool(Host host, StoragePool pool) {
        if (host == null || pool == null) {
            return false;
        }

        if (!pool.isManaged()) {
            return true;
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

        return (storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canHostAccessStoragePool(host, pool));
    }

    @Override
    public boolean canHostPrepareStoragePoolAccess(Host host, StoragePool pool) {
        if (host == null || pool == null || !pool.isManaged()) {
            return false;
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canHostPrepareStoragePoolAccess(host, pool);
    }

    @Override
    public boolean canDisconnectHostFromStoragePool(Host host, StoragePool pool) {
        if (pool == null || !pool.isManaged()) {
            return true;
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canDisconnectHostFromStoragePool(host, pool);
    }

    @Override
    @DB
    public Host getHost(long hostId) {
        return _hostDao.findById(hostId);
    }

    @Override
    public Host updateSecondaryStorage(long secStorageId, String newUrl) {
        HostVO secHost = _hostDao.findById(secStorageId);
        if (secHost == null) {
            throw new InvalidParameterValueException("Can not find out the secondary storage id: " + secStorageId);
        }

        if (secHost.getType() != Host.Type.SecondaryStorage) {
            throw new InvalidParameterValueException(String.format("host: %s is not a secondary storage", secHost));
        }

        URI uri = null;
        try {
            uri = new URI(UriUtils.encodeURIComponent(newUrl));
            if (uri.getScheme() == null) {
                throw new InvalidParameterValueException("uri.scheme is null " + newUrl + ", add nfs:// (or cifs://) as a prefix");
            } else if (uri.getScheme().equalsIgnoreCase("nfs")) {
                if (uri.getHost() == null || uri.getHost().equalsIgnoreCase("") || uri.getPath() == null || uri.getPath().equalsIgnoreCase("")) {
                    throw new InvalidParameterValueException("Your host and/or path is wrong.  Make sure it's of the format nfs://hostname/path");
                }
            } else if (uri.getScheme().equalsIgnoreCase("cifs")) {
                // Don't validate against a URI encoded URI.
                URI cifsUri = new URI(newUrl);
                String warnMsg = UriUtils.getCifsUriParametersProblems(cifsUri);
                if (warnMsg != null) {
                    throw new InvalidParameterValueException(warnMsg);
                }
            }
        } catch (URISyntaxException e) {
            throw new InvalidParameterValueException(newUrl + " is not a valid uri");
        }

        String oldUrl = secHost.getStorageUrl();

        URI oldUri = null;
        try {
            oldUri = new URI(UriUtils.encodeURIComponent(oldUrl));
            if (!oldUri.getScheme().equalsIgnoreCase(uri.getScheme())) {
                throw new InvalidParameterValueException("can not change old scheme:" + oldUri.getScheme() + " to " + uri.getScheme());
            }
        } catch (URISyntaxException e) {
            logger.debug("Failed to get uri from " + oldUrl);
        }

        secHost.setStorageUrl(newUrl);
        secHost.setGuid(newUrl);
        secHost.setName(newUrl);
        _hostDao.update(secHost.getId(), secHost);
        return secHost;
    }

    @Override
    public HypervisorType getHypervisorTypeFromFormat(ImageFormat format) {

        if (format == null) {
            return HypervisorType.None;
        }

        if (format == ImageFormat.VHD) {
            return HypervisorType.XenServer;
        } else if (format == ImageFormat.OVA) {
            return HypervisorType.VMware;
        } else if (format == ImageFormat.QCOW2) {
            return HypervisorType.KVM;
        } else if (format == ImageFormat.RAW) {
            return HypervisorType.Ovm;
        } else if (format == ImageFormat.VHDX) {
            return HypervisorType.Hyperv;
        } else {
            return HypervisorType.None;
        }
    }

    private boolean checkUsagedSpace(StoragePool pool) {
        // Managed storage does not currently deal with accounting for physically used space (only provisioned space). Just return true if "pool" is managed.
        if (pool.isManaged() && !canPoolProvideStorageStats(pool)) {
            return true;
        }

        long totalSize = pool.getCapacityBytes();
        long usedSize = getUsedSize(pool);
        double usedPercentage = ((double)usedSize / (double)totalSize);
        double storageUsedThreshold = CapacityManager.StorageCapacityDisableThreshold.valueIn(pool.getId());
        if (logger.isDebugEnabled()) {
            logger.debug("Checking pool {} for storage, totalSize: {}, usedBytes: {}, usedPct: {}, disable threshold: {}", pool, pool.getCapacityBytes(), pool.getUsedBytes(), usedPercentage, storageUsedThreshold);
        }
        if (usedPercentage >= storageUsedThreshold) {
            if (logger.isDebugEnabled()) {
                logger.debug("Insufficient space on pool: {} since its usage percentage: {} has crossed the pool.storage.capacity.disablethreshold: {}", pool, usedPercentage, storageUsedThreshold);
            }
            return false;
        }
        return true;
    }

    private long getUsedSize(StoragePool pool) {
        if (pool.getStorageProviderName().equalsIgnoreCase(DataStoreProvider.DEFAULT_PRIMARY) || canPoolProvideStorageStats(pool)) {
            return (pool.getUsedBytes());
        }

        StatsCollector sc = StatsCollector.getInstance();
        if (sc != null) {
            StorageStats stats = sc.getStoragePoolStats(pool.getId());
            if (stats == null) {
                stats = sc.getStorageStats(pool.getId());
            }
            if (stats != null) {
                return (stats.getByteUsed());
            }
        }

        return 0;
    }

    protected boolean checkIfPoolIopsCapacityNull(StoragePool pool) {
        // Only IOPS-guaranteed primary storage like SolidFire is using/setting IOPS.
        // This check returns true for storage that does not specify IOPS.
        if (pool.getCapacityIops() == null) {
            logger.info("Storage pool {} does not supply IOPS capacity, assuming enough capacity", pool);

            return true;
        }
        return false;
    }

    protected boolean storagePoolHasEnoughIops(long requestedIops, List<Pair<Volume, DiskProfile>> requestedVolumes, StoragePool pool, boolean skipPoolNullIopsCheck) {
        if (!skipPoolNullIopsCheck && checkIfPoolIopsCapacityNull(pool)) {
            return true;
        }
        StoragePoolVO storagePoolVo = _storagePoolDao.findById(pool.getId());
        long currentIops = _capacityMgr.getUsedIops(storagePoolVo);
        long futureIops = currentIops + requestedIops;
        boolean hasEnoughIops = futureIops <= pool.getCapacityIops();
        String hasCapacity = hasEnoughIops ? "has" : "does not have";
        logger.debug(String.format("Pool [%s] %s enough IOPS to allocate volumes [%s].", pool, hasCapacity, requestedVolumes));
        return hasEnoughIops;
    }

    @Override
    public boolean storagePoolHasEnoughIops(List<Pair<Volume, DiskProfile>> requestedVolumes, StoragePool pool) {
        if (requestedVolumes == null || requestedVolumes.isEmpty() || pool == null) {
            logger.debug(String.format("Cannot check if storage [%s] has enough IOPS to allocate volumes [%s].", pool, requestedVolumes));
            return false;
        }
        if (checkIfPoolIopsCapacityNull(pool)) {
            return true;
        }
        long requestedIops = 0;
        for (Pair<Volume, DiskProfile> volumeDiskProfilePair : requestedVolumes) {
            Volume requestedVolume = volumeDiskProfilePair.first();
            DiskProfile diskProfile = volumeDiskProfilePair.second();
            Long minIops = requestedVolume.getMinIops();
            if (requestedVolume.getDiskOfferingId() != diskProfile.getDiskOfferingId()) {
                minIops = diskProfile.getMinIops();
            }

            if (minIops != null && minIops > 0) {
                requestedIops += minIops;
            }
        }
        return storagePoolHasEnoughIops(requestedIops, requestedVolumes, pool, true);
    }

    @Override
    public boolean storagePoolHasEnoughIops(Long requestedIops, StoragePool pool) {
        if (pool == null) {
            return false;
        }
        if (requestedIops == null || requestedIops == 0) {
            return true;
        }
        return storagePoolHasEnoughIops(requestedIops, new ArrayList<>(), pool, false);
    }

    @Override
    public boolean storagePoolHasEnoughSpace(Long size, StoragePool pool) {
        if (size == null || size == 0) {
            return true;
        }
        final StoragePoolVO poolVO = _storagePoolDao.findById(pool.getId());
        long allocatedSizeWithTemplate = _capacityMgr.getAllocatedPoolCapacity(poolVO, null);
        return checkPoolforSpace(pool, allocatedSizeWithTemplate, size);
    }

    @Override
    public boolean storagePoolHasEnoughSpace(List<Pair<Volume, DiskProfile>> volumeDiskProfilePairs, StoragePool pool) {
        return storagePoolHasEnoughSpace(volumeDiskProfilePairs, pool, null);
    }

    @Override
    public boolean storagePoolHasEnoughSpace(List<Pair<Volume, DiskProfile>> volumeDiskProfilesList, StoragePool pool, Long clusterId) {
        if (CollectionUtils.isEmpty(volumeDiskProfilesList)) {
            logger.debug(String.format("Cannot check if pool [%s] has enough space to allocate volumes because the volumes list is empty.", pool));
            return false;
        }

        if (!checkUsagedSpace(pool)) {
            logger.debug(String.format("Cannot allocate pool [%s] because there is not enough space in this pool.", pool));
            return false;
        }

        // allocated space includes templates
        if (logger.isDebugEnabled()) {
            logger.debug("Destination pool: {}", pool);
        }
        // allocated space includes templates
        final StoragePoolVO poolVO = _storagePoolDao.findById(pool.getId());
        long allocatedSizeWithTemplate = _capacityMgr.getAllocatedPoolCapacity(poolVO, null);
        long totalAskingSize = 0;

        for (Pair<Volume, DiskProfile> volumeDiskProfilePair : volumeDiskProfilesList) {
            // refreshing the volume from the DB to get latest hv_ss_reserve (hypervisor snapshot reserve) field
            // I could have just assigned this to "volume", but decided to make a new variable for it so that it
            // might be clearer that this "volume" in "volumeDiskProfilesList" still might have an old value for hv_ss_reverse.
            Volume volume = volumeDiskProfilePair.first();
            DiskProfile diskProfile = volumeDiskProfilePair.second();
            VolumeVO volumeVO = volumeDao.findById(volume.getId());

            if (volumeVO.getHypervisorSnapshotReserve() == null) {
                // update the volume's hv_ss_reserve (hypervisor snapshot reserve) from a disk offering (used for managed storage)
                volService.updateHypervisorSnapshotReserveForVolume(getDiskOfferingVO(volumeVO), volumeVO.getId(), getHypervisorType(volumeVO));

                // hv_ss_reserve field might have been updated; refresh from DB to make use of it in getDataObjectSizeIncludingHypervisorSnapshotReserve
                volumeVO = volumeDao.findById(volume.getId());
            }

            // this if statement should resolve to true at most once per execution of the for loop its contained within (for a root disk that is
            // to leverage a template)
            if (volume.getTemplateId() != null) {
                VMTemplateVO tmpl = _templateDao.findByIdIncludingRemoved(volume.getTemplateId());

                if (tmpl != null && !ImageFormat.ISO.equals(tmpl.getFormat())) {
                    allocatedSizeWithTemplate = _capacityMgr.getAllocatedPoolCapacity(poolVO, tmpl);
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Pool ID for the volume {} is {}", volumeVO, volumeVO.getPoolId());
            }

            // A ready-state volume is already allocated in a pool, so the asking size is zero for it.
            // In case the volume is moving across pools or is not ready yet, the asking size has to be computed.
            if ((volumeVO.getState() != Volume.State.Ready) || (volumeVO.getPoolId() != pool.getId())) {
                totalAskingSize += getDataObjectSizeIncludingHypervisorSnapshotReserve(volumeVO, diskProfile, poolVO);

                totalAskingSize += getAskingSizeForTemplateBasedOnClusterAndStoragePool(volumeVO.getTemplateId(), clusterId, poolVO);
            }
        }

        return checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize);
    }

    @Override
    public boolean storagePoolHasEnoughSpaceForResize(StoragePool pool, long currentSize, long newSize) {
        if (!checkUsagedSpace(pool)) {
            return false;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Destination pool: {}", pool);
        }
        long totalAskingSize = newSize - currentSize;

        if (totalAskingSize <= 0) {
            return true;
        } else {
            final StoragePoolVO poolVO = _storagePoolDao.findById(pool.getId());
            final long allocatedSizeWithTemplate = _capacityMgr.getAllocatedPoolCapacity(poolVO, null);
            return checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, true);
        }
    }

    protected Answer getCheckDatastorePolicyComplianceAnswer(String storagePolicyId, StoragePool pool) throws StorageUnavailableException {
        if (StringUtils.isEmpty(storagePolicyId)) {
            return null;
        }
        VsphereStoragePolicyVO storagePolicyVO = _vsphereStoragePolicyDao.findById(Long.parseLong(storagePolicyId));
        List<Long> hostIds = getUpHostsInPool(pool.getId());
        Collections.shuffle(hostIds);

        if (CollectionUtils.isEmpty(hostIds)) {
            throw new StorageUnavailableException("Unable to send command to the pool " + pool.getName() + " due to there is no enabled hosts up in this cluster", pool.getId());
        }
        try {
            StorageFilerTO storageFilerTO = new StorageFilerTO(pool);
            CheckDataStoreStoragePolicyComplainceCommand cmd = new CheckDataStoreStoragePolicyComplainceCommand(storagePolicyVO.getPolicyId(), storageFilerTO);
            long targetHostId = _hvGuruMgr.getGuruProcessedCommandTargetHost(hostIds.get(0), cmd);
            return _agentMgr.send(targetHostId, cmd);
        } catch (AgentUnavailableException e) {
            logger.debug("Unable to send storage pool command to " + pool + " via " + hostIds.get(0), e);
            throw new StorageUnavailableException("Unable to send command to the pool ", pool.getId());
        } catch (OperationTimedoutException e) {
            logger.debug("Failed to process storage pool command to " + pool + " via " + hostIds.get(0), e);
            throw new StorageUnavailableException("Failed to process storage command to the pool ", pool.getId());
        }
    }

    @Override
    public boolean isStoragePoolCompliantWithStoragePolicy(long diskOfferingId, StoragePool pool) throws StorageUnavailableException {
        String storagePolicyId = _diskOfferingDetailsDao.getDetail(diskOfferingId, ApiConstants.STORAGE_POLICY);
        Answer answer = getCheckDatastorePolicyComplianceAnswer(storagePolicyId, pool);
        return answer == null || answer.getResult();
    }

    @Override
    public boolean isStoragePoolCompliantWithStoragePolicy(List<Pair<Volume, DiskProfile>> volumes, StoragePool pool) throws StorageUnavailableException {
        if (CollectionUtils.isEmpty(volumes)) {
            return false;
        }
        List<Pair<Volume, Answer>> answers = new ArrayList<>();

        for (Pair<Volume, DiskProfile> volumeDiskProfilePair : volumes) {
            Volume volume = volumeDiskProfilePair.first();
            DiskProfile diskProfile = volumeDiskProfilePair.second();
            String storagePolicyId = _diskOfferingDetailsDao.getDetail(diskProfile.getDiskOfferingId(), ApiConstants.STORAGE_POLICY);
            Answer answer = getCheckDatastorePolicyComplianceAnswer(storagePolicyId, pool);
            if (answer != null) {
                answers.add(new Pair<>(volume, answer));
            }
        }
        // check cummilative result for all volumes
        for (Pair<Volume, Answer> answer : answers) {
            if (!answer.second().getResult()) {
                logger.debug("Storage pool {} is not compliance with storage policy for volume {}", pool, answer.first().getName());
                return false;
            }
        }
        return true;
    }

    protected boolean checkPoolforSpace(StoragePool pool, long allocatedSizeWithTemplate, long totalAskingSize) {
        return checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, false);
    }

    protected boolean checkPoolforSpace(StoragePool pool, long allocatedSizeWithTemplate, long totalAskingSize, boolean forVolumeResize) {
        // allocated space includes templates
        StoragePoolVO poolVO = _storagePoolDao.findById(pool.getId());

        long totalOverProvCapacity;

        if (pool.getPoolType().supportsOverProvisioning()) {
            BigDecimal overProvFactor = getStorageOverProvisioningFactor(pool.getId());

            totalOverProvCapacity = overProvFactor.multiply(new BigDecimal(pool.getCapacityBytes())).longValue();

            logger.debug("Found storage pool {} of type {} with overprovisioning factor {}", pool, pool.getPoolType(), overProvFactor);
            logger.debug("Total over provisioned capacity calculated is {} * {}", overProvFactor, toHumanReadableSize(pool.getCapacityBytes()));
        } else {
            totalOverProvCapacity = pool.getCapacityBytes();

            logger.debug("Found storage pool {} of type {}", poolVO, pool.getPoolType());
        }

        logger.debug("Total capacity of the pool {} is {}", poolVO, toHumanReadableSize(totalOverProvCapacity));

        double storageAllocatedThreshold = CapacityManager.StorageAllocatedCapacityDisableThreshold.valueIn(pool.getId());

        if (logger.isDebugEnabled()) {
            logger.debug("Checking pool: {} for storage allocation , maxSize : {}, " +
                    "totalAllocatedSize : {}, askingSize : {}, allocated disable threshold: {}",
                    pool, toHumanReadableSize(totalOverProvCapacity), toHumanReadableSize(allocatedSizeWithTemplate), toHumanReadableSize(totalAskingSize), storageAllocatedThreshold);
        }

        double usedPercentage = (allocatedSizeWithTemplate + totalAskingSize) / (double)(totalOverProvCapacity);

        if (usedPercentage > storageAllocatedThreshold) {
            if (logger.isDebugEnabled()) {
                logger.debug("Insufficient un-allocated capacity on: {} for storage " +
                        "allocation since its allocated percentage: {} has crossed the allocated" +
                        " pool.storage.allocated.capacity.disablethreshold: {}",
                        pool, usedPercentage, storageAllocatedThreshold);
            }
            if (!forVolumeResize) {
                return false;
            }
            if (!AllowVolumeReSizeBeyondAllocation.valueIn(pool.getId())) {
                logger.debug(String.format("Skipping the pool %s as %s is false", pool, AllowVolumeReSizeBeyondAllocation.key()));
                return false;
            }

            double storageAllocatedThresholdForResize = CapacityManager.StorageAllocatedCapacityDisableThresholdForVolumeSize.valueIn(pool.getId());
            if (usedPercentage > storageAllocatedThresholdForResize) {
                logger.debug(String.format("Skipping the pool %s since its allocated percentage: %s has crossed the allocated %s: %s",
                        pool, usedPercentage, CapacityManager.StorageAllocatedCapacityDisableThresholdForVolumeSize.key(), storageAllocatedThresholdForResize));
                return false;
            }
        }

        if (totalOverProvCapacity < (allocatedSizeWithTemplate + totalAskingSize)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Insufficient un-allocated capacity on: {} for storage " +
                        "allocation, not enough storage, maxSize : {}, totalAllocatedSize : {}, " +
                        "askingSize : {}", pool, toHumanReadableSize(totalOverProvCapacity),
                        toHumanReadableSize(allocatedSizeWithTemplate), toHumanReadableSize(totalAskingSize));
            }

            return false;
        }

        return true;
    }

    /**
     * Storage plug-ins for managed storage can be designed in such a way as to store a template on the primary storage once and
     * make use of it via storage-side cloning.
     *
     * This method determines how many more bytes it will need for the template (if the template is already stored on the primary storage,
     * then the answer is 0).
     */
    private long getAskingSizeForTemplateBasedOnClusterAndStoragePool(Long templateId, Long clusterId, StoragePoolVO storagePoolVO) {
        if (templateId == null || clusterId == null || storagePoolVO == null || !storagePoolVO.isManaged()) {
            return 0;
        }

        VMTemplateVO tmpl = _templateDao.findByIdIncludingRemoved(templateId);

        if (tmpl == null || ImageFormat.ISO.equals(tmpl.getFormat())) {
            return 0;
        }

        HypervisorType hypervisorType = tmpl.getHypervisorType();

        // The getSupportsResigning method is applicable for XenServer as a UUID-resigning patch may or may not be installed on those hypervisor hosts.
        if (_clusterDao.getSupportsResigning(clusterId) || HypervisorType.VMware.equals(hypervisorType) || HypervisorType.KVM.equals(hypervisorType)) {
            return getBytesRequiredForTemplate(tmpl, storagePoolVO);
        }

        return 0;
    }

    private long getDataObjectSizeIncludingHypervisorSnapshotReserve(Volume volume, DiskProfile diskProfile, StoragePool pool) {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

        if (storeDriver instanceof PrimaryDataStoreDriver) {
            PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver)storeDriver;

            VolumeInfo volumeInfo = volFactory.getVolume(volume.getId());
            if (volume.getDiskOfferingId() != diskProfile.getDiskOfferingId()) {
                return diskProfile.getSize();
            }
            return primaryStoreDriver.getDataObjectSizeIncludingHypervisorSnapshotReserve(volumeInfo, pool);
        }

        return volume.getSize();
    }

    private DiskOfferingVO getDiskOfferingVO(Volume volume) {
        Long diskOfferingId = volume.getDiskOfferingId();

        return _diskOfferingDao.findById(diskOfferingId);
    }

    private HypervisorType getHypervisorType(Volume volume) {
        Long instanceId = volume.getInstanceId();

        VMInstanceVO vmInstance = _vmInstanceDao.findById(instanceId);

        if (vmInstance != null) {
            return vmInstance.getHypervisorType();
        }

        return null;
    }

    private long getBytesRequiredForTemplate(VMTemplateVO tmpl, StoragePool pool) {
        if (tmplFactory.isTemplateMarkedForDirectDownload(tmpl.getId())) {
            return tmpl.getSize();
        }

        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

        if (storeDriver instanceof PrimaryDataStoreDriver) {
            PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver)storeDriver;

            TemplateInfo templateInfo = tmplFactory.getReadyTemplateOnImageStore(tmpl.getId(), pool.getDataCenterId());

            return primaryStoreDriver.getBytesRequiredForTemplate(templateInfo, pool);
        }

        return tmpl.getSize();
    }

    @Override
    public boolean storagePoolCompatibleWithVolumePool(StoragePool pool, Volume volume) {
        if (pool == null || volume == null) {
            logger.debug(String.format("Cannot check if storage pool [%s] is compatible with volume [%s].", pool, volume));
            return false;
        }

        if (volume.getPoolId() == null) {
            logger.debug(String.format("Volume [%s] is not allocated to any pool. Cannot check compatibility with pool [%s].", volume, pool));
            return true;
        }

        StoragePool volumePool = _storagePoolDao.findById(volume.getPoolId());
        if (volumePool == null) {
            logger.debug(String.format("Pool [%s] used by volume [%s] does not exist. Cannot check compatibility.", pool, volume));
            return true;
        }

        if (volume.getState() == Volume.State.Ready) {
            if (volumePool.getPoolType() == Storage.StoragePoolType.PowerFlex && pool.getPoolType() != Storage.StoragePoolType.PowerFlex) {
                logger.debug(String.format("Pool [%s] with type [%s] does not match volume [%s] pool type [%s].", pool, pool.getPoolType(), volume, volumePool.getPoolType()));
                return false;
            } else if (volumePool.getPoolType() != Storage.StoragePoolType.PowerFlex && pool.getPoolType() == Storage.StoragePoolType.PowerFlex) {
                logger.debug(String.format("Pool [%s] with type [%s] does not match volume [%s] pool type [%s].", pool, pool.getPoolType(), volume, volumePool.getPoolType()));
                return false;
            }
        } else {
            logger.debug(String.format("Cannot check compatibility of pool [%s] because volume [%s] is not in [%s] state.", pool, volume, Volume.State.Ready));
            return false;
        }
        logger.debug(String.format("Pool [%s] is compatible with volume [%s].", pool, volume));
        return true;
    }

    @Override
    public void createCapacityEntry(long poolId) {
        StoragePoolVO storage = _storagePoolDao.findById(poolId);
        createCapacityEntry(storage, Capacity.CAPACITY_TYPE_STORAGE_ALLOCATED, 0);
    }

    @Override
    public synchronized boolean registerHostListener(String providerName, HypervisorHostListener listener) {
        hostListeners.put(providerName, listener);
        return true;
    }

    @Override
    public Answer sendToPool(long poolId, Command cmd) throws StorageUnavailableException {
        return null;
    }

    @Override
    public Answer[] sendToPool(long poolId, Commands cmd) throws StorageUnavailableException {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    private String getValidTemplateName(Long zoneId, HypervisorType hType) {
        String templateName = null;
        if (hType.equals(HypervisorType.XenServer)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateXen.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.KVM)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateKvm.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.VMware)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateVmware.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.Hyperv)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateHyperV.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.LXC)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateLxc.valueIn(zoneId);
        }
        return templateName;
    }
    @Override
    public ImageStore discoverImageStore(String name, String url, String providerName, Long zoneId, Map details) throws IllegalArgumentException, DiscoveryException, InvalidParameterValueException {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            storeProvider = _dataStoreProviderMgr.getDefaultImageDataStoreProvider();
            if (storeProvider == null) {
                throw new InvalidParameterValueException("can't find image store provider: " + providerName);
            }
            providerName = storeProvider.getName(); // ignored passed provider name and use default image store provider name
        }

        ScopeType scopeType = ScopeType.ZONE;
        if (zoneId == null) {
            scopeType = ScopeType.REGION;
        }

        if (name == null) {
            name = url;
        }

        ImageStoreVO imageStore = _imageStoreDao.findByName(name);
        if (imageStore != null) {
            throw new InvalidParameterValueException("The image store with name " + name + " already exists, try creating with another name");
        }

        // check if scope is supported by store provider
        if (!((ImageStoreProvider)storeProvider).isScopeSupported(scopeType)) {
            throw new InvalidParameterValueException("Image store provider " + providerName + " does not support scope " + scopeType);
        }

        // check if we have already image stores from other different providers,
        // we currently are not supporting image stores from different
        // providers co-existing
        List<ImageStoreVO> imageStores = _imageStoreDao.listImageStores();
        for (ImageStoreVO store : imageStores) {
            if (!store.getProviderName().equalsIgnoreCase(providerName)) {
                throw new InvalidParameterValueException("You can only add new image stores from the same provider " + store.getProviderName() + " already added");
            }
        }

        if (zoneId != null) {
            // Check if the zone exists in the system
            DataCenterVO zone = _dcDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Can't find zone by id " + zoneId);
            }

            Account account = CallContext.current().getCallingAccount();
            if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(account.getId())) {
                PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, Zone with specified id is currently disabled");
                ex.addProxyObject(zone.getUuid(), "dcId");
                throw ex;
            }
        }

        Map<String, Object> params = new HashMap<>();
        params.put("zoneId", zoneId);
        params.put("url", url);
        params.put("name", name);
        params.put("details", details);
        params.put("scope", scopeType);
        params.put("providerName", storeProvider.getName());
        params.put("role", DataStoreRole.Image);

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();

        DataStore store;
        try {
            store = lifeCycle.initialize(params);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to add data store: " + e.getMessage(), e);
            }
            throw new CloudRuntimeException("Failed to add data store: " + e.getMessage(), e);
        }

        if (((ImageStoreProvider)storeProvider).needDownloadSysTemplate()) {
            // trigger system vm template download
            _imageSrv.downloadBootstrapSysTemplate(store);
        } else {
            // populate template_store_ref table
            _imageSrv.addSystemVMTemplatesToSecondary(store);
            _imageSrv.handleTemplateSync(store);
            registerSystemVmTemplateOnFirstNfsStore(zoneId, providerName, url, store);
        }

        // associate builtin template with zones associated with this image store
        associateCrosszoneTemplatesToZone(zoneId);

        // duplicate cache store records to region wide storage
        if (scopeType == ScopeType.REGION) {
            duplicateCacheStoreRecordsToRegionStore(store.getId());
        }

        return (ImageStore)_dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Image);
    }

    protected void registerSystemVmTemplateForHypervisorArch(final HypervisorType hypervisorType,
                 final CPU.CPUArch arch, final Long zoneId, final String url, final DataStore store,
                 final SystemVmTemplateRegistration systemVmTemplateRegistration, final String filePath,
                 final Pair<String, Long> storeUrlAndId, final String nfsVersion) {
        if (HypervisorType.Simulator.equals(hypervisorType)) {
            return;
        }
        String templateName = getValidTemplateName(zoneId, hypervisorType);
        VMTemplateVO registeredTemplate = systemVmTemplateRegistration.getRegisteredTemplate(templateName, arch);
        TemplateDataStoreVO templateDataStoreVO = null;
        if (registeredTemplate != null) {
            templateDataStoreVO = _templateStoreDao.findByStoreTemplate(store.getId(), registeredTemplate.getId());
            if (templateDataStoreVO != null) {
                try {
                    if (systemVmTemplateRegistration.validateIfSeeded(templateDataStoreVO, url,
                            templateDataStoreVO.getInstallPath(), nfsVersion)) {
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Failed to validated if template is seeded", e);
                }
            }
        }
        SystemVmTemplateRegistration.mountStore(storeUrlAndId.first(), filePath, nfsVersion);
        if (templateDataStoreVO != null) {
            systemVmTemplateRegistration.validateAndRegisterTemplate(hypervisorType, templateName,
                    storeUrlAndId.second(), registeredTemplate, templateDataStoreVO, filePath);
        } else {
            systemVmTemplateRegistration.validateAndRegisterTemplateForNonExistingEntries(hypervisorType, arch,
                    templateName, storeUrlAndId, filePath);
        }
    }

    private void registerSystemVmTemplateOnFirstNfsStore(Long zoneId, String providerName, String url, DataStore store) {
        if (DataStoreProvider.NFS_IMAGE.equals(providerName) && zoneId != null) {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    List<ImageStoreVO> stores = _imageStoreDao.listAllStoresInZoneExceptId(zoneId, providerName,
                            DataStoreRole.Image, store.getId());
                    if (CollectionUtils.isEmpty(stores)) {
                        List<Pair<HypervisorType, CPU.CPUArch>> hypervisorTypes =
                                _clusterDao.listDistinctHypervisorsArchAcrossClusters(zoneId);
                        TransactionLegacy txn = TransactionLegacy.open("AutomaticTemplateRegister");
                        SystemVmTemplateRegistration systemVmTemplateRegistration = new SystemVmTemplateRegistration();
                        String filePath = null;
                        try {
                            filePath = Files.createTempDirectory(SystemVmTemplateRegistration.TEMPORARY_SECONDARY_STORE).toString();
                            if (filePath == null) {
                                throw new CloudRuntimeException("Failed to create temporary file path to mount the store");
                            }
                            Pair<String, Long> storeUrlAndId = new Pair<>(url, store.getId());
                            String nfsVersion = imageStoreDetailsUtil.getNfsVersion(store.getId());
                            for (Pair<HypervisorType, CPU.CPUArch> hypervisorArchType : hypervisorTypes) {
                                try {
                                    registerSystemVmTemplateForHypervisorArch(hypervisorArchType.first(),
                                            hypervisorArchType.second(), zoneId, url, store,
                                            systemVmTemplateRegistration, filePath, storeUrlAndId, nfsVersion);
                                } catch (CloudRuntimeException e) {
                                    SystemVmTemplateRegistration.unmountStore(filePath);
                                    logger.error("Failed to register system VM template for hypervisor: {} {}",
                                            hypervisorArchType.first().name(), hypervisorArchType.second().name(), e);
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Failed to register systemVM template(s)");
                        } finally {
                            SystemVmTemplateRegistration.unmountStore(filePath);
                            txn.close();
                        }
                    }
                }
            });
        }
    }
    @Override
    public ImageStore migrateToObjectStore(String name, String url, String providerName, Map<String, String> details) throws DiscoveryException, InvalidParameterValueException {
        // check if current cloud is ready to migrate, we only support cloud with only NFS secondary storages
        List<ImageStoreVO> imgStores = _imageStoreDao.listImageStores();
        List<ImageStoreVO> nfsStores = new ArrayList<>();
        if (imgStores != null && imgStores.size() > 0) {
            for (ImageStoreVO store : imgStores) {
                if (!store.getProviderName().equals(DataStoreProvider.NFS_IMAGE)) {
                    throw new InvalidParameterValueException("We only support migrate NFS secondary storage to use object store!");
                } else {
                    nfsStores.add(store);
                }
            }
        }
        // convert all NFS secondary storage to staging store
        if (nfsStores != null && nfsStores.size() > 0) {
            for (ImageStoreVO store : nfsStores) {
                long storeId = store.getId();

                _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), store.getDataCenterId());

                DataStoreProvider provider = _dataStoreProviderMgr.getDataStoreProvider(store.getProviderName());
                DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
                DataStore secStore = _dataStoreMgr.getDataStore(storeId, DataStoreRole.Image);
                lifeCycle.migrateToObjectStore(secStore);
                // update store_role in template_store_ref and snapshot_store_ref to ImageCache
                _templateStoreDao.updateStoreRoleToCachce(storeId);
                _snapshotStoreDao.updateStoreRoleToCache(storeId);
            }
        }
        // add object store
        return discoverImageStore(name, url, providerName, null, details);
    }

    @Override
    public ImageStore updateImageStore(UpdateImageStoreCmd cmd) {
        return updateImageStoreStatus(cmd.getId(), cmd.getName(), cmd.getReadonly(), cmd.getCapacityBytes());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_UPDATE_IMAGE_STORE_ACCESS_STATE,
            eventDescription = "image store access updated")
    public ImageStore updateImageStoreStatus(Long id, String name, Boolean readonly, Long capacityBytes) {
        // Input validation
        ImageStoreVO imageStoreVO = _imageStoreDao.findById(id);
        if (imageStoreVO == null) {
            throw new IllegalArgumentException("Unable to find image store with ID: " + id);
        }
        if (com.cloud.utils.StringUtils.isNotBlank(name)) {
            imageStoreVO.setName(name);
        }
        if (capacityBytes != null) {
            imageStoreVO.setTotalSize(capacityBytes);
        }
        if (readonly != null) {
            imageStoreVO.setReadonly(readonly);
        }
        _imageStoreDao.update(id, imageStoreVO);
        return imageStoreVO;
    }

    @Override
    public ImageStore updateImageStoreStatus(Long id, Boolean readonly) {
        return updateImageStoreStatus(id, null, readonly, null);
    }

    /**
     * @param poolId - Storage pool id for pool to update.
     * @param failOnChecks - If true, throw an error if pool type and state checks fail.
     */
    @Override
    public void updateStorageCapabilities(Long poolId, boolean failOnChecks) {
        StoragePoolVO pool = _storagePoolDao.findById(poolId);

        if (pool == null) {
            throw new CloudRuntimeException("Primary storage not found for id: " + poolId);
        }

        // Only checking NFS for now - required for disk provisioning type support for vmware.
        if (pool.getPoolType() != StoragePoolType.NetworkFilesystem) {
            if (failOnChecks) {
                throw new CloudRuntimeException("Storage capabilities update only supported on NFS storage mounted.");
            }
            return;
        }

        if (pool.getStatus() != StoragePoolStatus.Initialized && pool.getStatus() != StoragePoolStatus.Up) {
            if (failOnChecks){
                throw new CloudRuntimeException("Primary storage is not in the right state to update capabilities");
            }
            return;
        }

        HypervisorType hypervisor = pool.getHypervisor();

        if (hypervisor == null){
            if (pool.getClusterId() != null) {
                ClusterVO cluster = _clusterDao.findById(pool.getClusterId());
                hypervisor = cluster.getHypervisorType();
            }
        }

        if (!HypervisorType.VMware.equals(hypervisor)) {
            if (failOnChecks) {
                throw new CloudRuntimeException("Storage capabilities update only supported on VMWare.");
            }
            return;
        }

        // find the host
        List<Long> poolIds = new ArrayList<>();
        poolIds.add(pool.getId());
        List<Long> hosts = _storagePoolHostDao.findHostsConnectedToPools(poolIds);
        if (hosts.size() > 0) {
            GetStoragePoolCapabilitiesCommand cmd = new GetStoragePoolCapabilitiesCommand();
            cmd.setPool(new StorageFilerTO(pool));
            GetStoragePoolCapabilitiesAnswer answer = (GetStoragePoolCapabilitiesAnswer) _agentMgr.easySend(hosts.get(0), cmd);
            if (answer.getPoolDetails() != null && answer.getPoolDetails().containsKey(Storage.Capability.HARDWARE_ACCELERATION.toString())) {
                StoragePoolDetailVO hardwareAccelerationSupported = _storagePoolDetailsDao.findDetail(pool.getId(), Storage.Capability.HARDWARE_ACCELERATION.toString());
                if (hardwareAccelerationSupported == null) {
                    StoragePoolDetailVO storagePoolDetailVO = new StoragePoolDetailVO(pool.getId(), Storage.Capability.HARDWARE_ACCELERATION.toString(), answer.getPoolDetails().get(Storage.Capability.HARDWARE_ACCELERATION.toString()), false);
                    _storagePoolDetailsDao.persist(storagePoolDetailVO);
                } else {
                    hardwareAccelerationSupported.setValue(answer.getPoolDetails().get(Storage.Capability.HARDWARE_ACCELERATION.toString()));
                    _storagePoolDetailsDao.update(hardwareAccelerationSupported.getId(), hardwareAccelerationSupported);
                }
            } else {
                if (answer != null && !answer.getResult()) {
                    logger.error("Failed to update storage pool capabilities: " + answer.getDetails());
                    if (failOnChecks) {
                        throw new CloudRuntimeException(answer.getDetails());
                    }
                }
            }
        }
    }

    private void duplicateCacheStoreRecordsToRegionStore(long storeId) {
        _templateStoreDao.duplicateCacheRecordsOnRegionStore(storeId);
        _snapshotStoreDao.duplicateCacheRecordsOnRegionStore(storeId);
        _volumeStoreDao.duplicateCacheRecordsOnRegionStore(storeId);
    }

    private void associateCrosszoneTemplatesToZone(Long zoneId) {
        VMTemplateZoneVO tmpltZone;

        List<VMTemplateVO> allTemplates = _vmTemplateDao.listAll();
        List<Long> dcIds = new ArrayList<>();
        if (zoneId != null) {
            dcIds.add(zoneId);
        } else {
            List<DataCenterVO> dcs = _dcDao.listAll();
            if (dcs != null) {
                for (DataCenterVO dc : dcs) {
                    dcIds.add(dc.getId());
                }
            }
        }

        for (VMTemplateVO vt : allTemplates) {
            if (vt.isCrossZones()) {
                for (Long dcId : dcIds) {
                    tmpltZone = _vmTemplateZoneDao.findByZoneTemplate(dcId, vt.getId());
                    if (tmpltZone == null) {
                        VMTemplateZoneVO vmTemplateZone = new VMTemplateZoneVO(dcId, vt.getId(), new Date());
                        _vmTemplateZoneDao.persist(vmTemplateZone);
                    }
                }
            }
        }
    }

    @Override
    public boolean deleteImageStore(DeleteImageStoreCmd cmd) {
        final long storeId = cmd.getId();
        // Verify that image store exists
        ImageStoreVO store = _imageStoreDao.findById(storeId);
        if (store == null) {
            throw new InvalidParameterValueException("Image store with id " + storeId + " doesn't exist");
        }
        _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), store.getDataCenterId());

        // Verify that there are no live snapshot, template, volume on the image
        // store to be deleted
        List<SnapshotDataStoreVO> snapshots = _snapshotStoreDao.listByStoreId(storeId, DataStoreRole.Image);
        if (snapshots != null && snapshots.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete image store with active snapshots backup!");
        }
        List<VolumeDataStoreVO> volumes = _volumeStoreDao.listByStoreId(storeId);
        if (volumes != null && volumes.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete image store with active volumes backup!");
        }

        // search if there are user templates stored on this image store, excluding system, builtin templates
        List<TemplateJoinVO> templates = _templateViewDao.listActiveTemplates(storeId);
        if (templates != null && templates.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete image store with active templates backup!");
        }

        // ready to delete
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // first delete from image_store_details table, we need to do that since
                // we are not actually deleting record from main
                // image_data_store table, so delete cascade will not work
                _imageStoreDetailsDao.deleteDetails(storeId);
                _snapshotStoreDao.deletePrimaryRecordsForStore(storeId, DataStoreRole.Image);
                _volumeStoreDao.deletePrimaryRecordsForStore(storeId);
                _templateStoreDao.deletePrimaryRecordsForStore(storeId);
                annotationDao.removeByEntityType(AnnotationService.EntityType.SECONDARY_STORAGE.name(), store.getUuid());
                _imageStoreDao.remove(storeId);
            }
        });

        return true;
    }

    @Override
    public ImageStore createSecondaryStagingStore(CreateSecondaryStagingStoreCmd cmd) {
        String providerName = cmd.getProviderName();
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            storeProvider = _dataStoreProviderMgr.getDefaultCacheDataStoreProvider();
            if (storeProvider == null) {
                throw new InvalidParameterValueException("can't find cache store provider: " + providerName);
            }
        }

        Long dcId = cmd.getZoneId();

        ScopeType scopeType = null;
        String scope = cmd.getScope();
        if (scope != null) {
            try {
                scopeType = Enum.valueOf(ScopeType.class, scope.toUpperCase());

            } catch (Exception e) {
                throw new InvalidParameterValueException("invalid scope for cache store " + scope);
            }

            if (scopeType != ScopeType.ZONE) {
                throw new InvalidParameterValueException("Only zone wide cache storage is supported");
            }
        }

        if (scopeType == ScopeType.ZONE && dcId == null) {
            throw new InvalidParameterValueException("zone id can't be null, if scope is zone");
        }

        // Check if the zone exists in the system
        DataCenterVO zone = _dcDao.findById(dcId);
        if (zone == null) {
            throw new InvalidParameterValueException("Can't find zone by id " + dcId);
        }

        Account account = CallContext.current().getCallingAccount();
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(account.getId())) {
            PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, Zone with specified id is currently disabled");
            ex.addProxyObject(zone.getUuid(), "dcId");
            throw ex;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("zoneId", dcId);
        params.put("url", cmd.getUrl());
        params.put("name", cmd.getUrl());
        params.put("details", cmd.getDetails());
        params.put("scope", scopeType);
        params.put("providerName", storeProvider.getName());
        params.put("role", DataStoreRole.ImageCache);

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();
        DataStore store = null;
        try {
            store = lifeCycle.initialize(params);
        } catch (Exception e) {
            logger.debug("Failed to add data store: " + e.getMessage(), e);
            throw new CloudRuntimeException("Failed to add data store: " + e.getMessage(), e);
        }

        return (ImageStore)_dataStoreMgr.getDataStore(store.getId(), DataStoreRole.ImageCache);
    }

    @Override
    public boolean deleteSecondaryStagingStore(DeleteSecondaryStagingStoreCmd cmd) {
        final long storeId = cmd.getId();
        // Verify that cache store exists
        ImageStoreVO store = _imageStoreDao.findById(storeId);
        if (store == null) {
            throw new InvalidParameterValueException("Cache store with id " + storeId + " doesn't exist");
        }
        _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), store.getDataCenterId());

        // Verify that there are no live snapshot, template, volume on the cache
        // store that is currently referenced
        List<SnapshotDataStoreVO> snapshots = _snapshotStoreDao.listActiveOnCache(storeId);
        if (snapshots != null && snapshots.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete cache store with staging snapshots currently in use!");
        }
        List<VolumeDataStoreVO> volumes = _volumeStoreDao.listActiveOnCache(storeId);
        if (volumes != null && volumes.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete cache store with staging volumes currently in use!");
        }

        List<TemplateDataStoreVO> templates = _templateStoreDao.listActiveOnCache(storeId);
        if (templates != null && templates.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete cache store with staging templates currently in use!");
        }

        // ready to delete
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // first delete from image_store_details table, we need to do that since
                // we are not actually deleting record from main
                // image_data_store table, so delete cascade will not work
                _imageStoreDetailsDao.deleteDetails(storeId);
                _snapshotStoreDao.deletePrimaryRecordsForStore(storeId, DataStoreRole.ImageCache);
                _volumeStoreDao.deletePrimaryRecordsForStore(storeId);
                _templateStoreDao.deletePrimaryRecordsForStore(storeId);
                _imageStoreDao.remove(storeId);
            }
        });

        return true;
    }

    protected class DownloadURLGarbageCollector implements Runnable {

        public DownloadURLGarbageCollector() {
        }

        @Override
        public void run() {
            try {
                logger.trace("Download URL Garbage Collection Thread is running.");

                cleanupDownloadUrls();

            } catch (Exception e) {
                logger.error("Caught the following Exception", e);
            }
        }
    }

    @Override
    public void cleanupDownloadUrls() {

        // Cleanup expired volume URLs
        List<VolumeDataStoreVO> volumesOnImageStoreList = _volumeStoreDao.listVolumeDownloadUrls();
        HashSet<Long> expiredVolumeIds = new HashSet<>();
        HashSet<Long> activeVolumeIds = new HashSet<>();
        for (VolumeDataStoreVO volumeOnImageStore : volumesOnImageStoreList) {

            long volumeId = volumeOnImageStore.getVolumeId();
            VolumeVO volume = volumeDao.findById(volumeId);
            try {
                long downloadUrlCurrentAgeInSecs = DateUtil.getTimeDifference(DateUtil.now(), volumeOnImageStore.getExtractUrlCreated());
                if (downloadUrlCurrentAgeInSecs < _downloadUrlExpirationInterval) {  // URL hasnt expired yet
                    activeVolumeIds.add(volumeId);
                    continue;
                }
                expiredVolumeIds.add(volumeId);
                logger.debug("Removing download url {} for volume {}", volumeOnImageStore.getExtractUrl(), volume);

                // Remove it from image store
                ImageStoreEntity secStore = (ImageStoreEntity)_dataStoreMgr.getDataStore(volumeOnImageStore.getDataStoreId(), DataStoreRole.Image);
                secStore.deleteExtractUrl(volumeOnImageStore.getInstallPath(), volumeOnImageStore.getExtractUrl(), Upload.Type.VOLUME);

                // Now expunge it from DB since this entry was created only for download purpose
                _volumeStoreDao.expunge(volumeOnImageStore.getId());
            } catch (Throwable th) {
                logger.warn("Caught exception while deleting download url {} for volume {}", volumeOnImageStore.getExtractUrl(), volume, th);
            }
        }
        for (Long volumeId : expiredVolumeIds) {
            if (activeVolumeIds.contains(volumeId)) {
                continue;
            }
            Volume volume = volumeDao.findById(volumeId);
            if (volume != null && volume.getState() == Volume.State.Expunged) {
                volumeDao.remove(volumeId);
            }
        }

        // Cleanup expired template URLs
        List<TemplateDataStoreVO> templatesOnImageStoreList = _templateStoreDao.listTemplateDownloadUrls();
        for (TemplateDataStoreVO templateOnImageStore : templatesOnImageStoreList) {
            VMTemplateVO template = _templateDao.findById(templateOnImageStore.getId());
            try {
                long downloadUrlCurrentAgeInSecs = DateUtil.getTimeDifference(DateUtil.now(), templateOnImageStore.getExtractUrlCreated());
                if (downloadUrlCurrentAgeInSecs < _downloadUrlExpirationInterval) {  // URL hasnt expired yet
                    continue;
                }

                logger.debug("Removing download url {} for template {}", templateOnImageStore.getExtractUrl(), template);

                // Remove it from image store
                ImageStoreEntity secStore = (ImageStoreEntity)_dataStoreMgr.getDataStore(templateOnImageStore.getDataStoreId(), DataStoreRole.Image);
                secStore.deleteExtractUrl(templateOnImageStore.getInstallPath(), templateOnImageStore.getExtractUrl(), Upload.Type.TEMPLATE);

                // Now remove download details from DB.
                templateOnImageStore.setExtractUrl(null);
                templateOnImageStore.setExtractUrlCreated(null);
                _templateStoreDao.update(templateOnImageStore.getId(), templateOnImageStore);
            } catch (Throwable th) {
                logger.warn("caught exception while deleting download url {} for template {}", templateOnImageStore.getExtractUrl(), template, th);
            }
        }

        Date date = DateUtils.addSeconds(new Date(), -1 * _downloadUrlExpirationInterval);
        List<ImageStoreObjectDownloadVO> imageStoreObjectDownloadList = _imageStoreObjectDownloadDao.listToExpire(date);
        for (ImageStoreObjectDownloadVO imageStoreObjectDownloadVO : imageStoreObjectDownloadList) {
            try {
                ImageStoreEntity secStore = (ImageStoreEntity)_dataStoreMgr.getDataStore(imageStoreObjectDownloadVO.getStoreId(), DataStoreRole.Image);
                secStore.deleteExtractUrl(imageStoreObjectDownloadVO.getPath(), imageStoreObjectDownloadVO.getDownloadUrl(), null);
                _imageStoreObjectDownloadDao.expunge(imageStoreObjectDownloadVO.getId());
            } catch (Throwable th) {
                logger.warn("caught exception while deleting download url {} for object {}", imageStoreObjectDownloadVO.getDownloadUrl(), imageStoreObjectDownloadVO, th);
            }
        }

        List <SnapshotDataStoreVO> snapshotDataStoreVos = _snapshotStoreDao.listExtractedSnapshotsBeforeDate(DateUtils.addSeconds(DateUtil.now(), -_downloadUrlExpirationInterval));

        for (SnapshotDataStoreVO snapshotDataStoreVo : snapshotDataStoreVos) {
            ImageStoreEntity secStore = (ImageStoreEntity)_dataStoreMgr.getDataStore(snapshotDataStoreVo.getDataStoreId(), DataStoreRole.Image);
            secStore.deleteExtractUrl(snapshotDataStoreVo.getInstallPath(), snapshotDataStoreVo.getExtractUrl(), Upload.Type.SNAPSHOT);

            snapshotDataStoreVo.setExtractUrl(null);
            snapshotDataStoreVo.setExtractUrlCreated(null);
            _snapshotStoreDao.update(snapshotDataStoreVo.getId(), snapshotDataStoreVo);
        }
    }

    // get bytesReadRate from service_offering, disk_offering and vm.disk.throttling.bytes_read_rate
    @Override
    public Long getDiskBytesReadRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getBytesReadRate() != null) && (diskOffering.getBytesReadRate() > 0)) {
            return diskOffering.getBytesReadRate();
        } else if ((diskOffering != null) && (diskOffering.getBytesReadRate() != null) && (diskOffering.getBytesReadRate() > 0)) {
            return diskOffering.getBytesReadRate();
        } else {
            Long bytesReadRate = Long.parseLong(_configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key()));
            if ((bytesReadRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return bytesReadRate;
            }
        }
        return 0L;
    }

    // get bytesWriteRate from service_offering, disk_offering and vm.disk.throttling.bytes_write_rate
    @Override
    public Long getDiskBytesWriteRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getBytesWriteRate() != null) && (diskOffering.getBytesWriteRate() > 0)) {
            return diskOffering.getBytesWriteRate();
        } else {
            Long bytesWriteRate = Long.parseLong(_configDao.getValue(Config.VmDiskThrottlingBytesWriteRate.key()));
            if ((bytesWriteRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return bytesWriteRate;
            }
        }
        return 0L;
    }

    // get iopsReadRate from service_offering, disk_offering and vm.disk.throttling.iops_read_rate
    @Override
    public Long getDiskIopsReadRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getIopsReadRate() != null) && (diskOffering.getIopsReadRate() > 0)) {
            return diskOffering.getIopsReadRate();
        } else {
            Long iopsReadRate = Long.parseLong(_configDao.getValue(Config.VmDiskThrottlingIopsReadRate.key()));
            if ((iopsReadRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return iopsReadRate;
            }
        }
        return 0L;
    }

    // get iopsWriteRate from service_offering, disk_offering and vm.disk.throttling.iops_write_rate
    @Override
    public Long getDiskIopsWriteRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getIopsWriteRate() != null) && (diskOffering.getIopsWriteRate() > 0)) {
            return diskOffering.getIopsWriteRate();
        } else {
            Long iopsWriteRate = Long.parseLong(_configDao.getValue(Config.VmDiskThrottlingIopsWriteRate.key()));
            if ((iopsWriteRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return iopsWriteRate;
            }
        }
        return 0L;
    }

    @Override
    public String getConfigComponentName() {
        return StorageManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                StorageCleanupInterval,
                StorageCleanupDelay,
                StorageCleanupEnabled,
                TemplateCleanupEnabled,
                KvmStorageOfflineMigrationWait,
                KvmStorageOnlineMigrationWait,
                KvmAutoConvergence,
                MaxNumberOfManagedClusteredFileSystems,
                STORAGE_POOL_DISK_WAIT,
                STORAGE_POOL_CLIENT_TIMEOUT,
                STORAGE_POOL_CLIENT_MAX_CONNECTIONS,
                STORAGE_POOL_CONNECTED_CLIENTS_LIMIT,
                STORAGE_POOL_IO_POLICY,
                PRIMARY_STORAGE_DOWNLOAD_WAIT,
                SecStorageMaxMigrateSessions,
                MaxDataMigrationWaitTime,
                DiskProvisioningStrictness,
                PreferredStoragePool,
                SecStorageVMAutoScaleDown,
                MountDisabledStoragePool,
                VmwareCreateCloneFull,
                VmwareAllowParallelExecution,
                DataStoreDownloadFollowRedirects,
                AllowVolumeReSizeBeyondAllocation,
                StoragePoolHostConnectWorkers,
                ObjectStorageCapacityThreshold
        };
    }

    @Override
    public void setDiskProfileThrottling(DiskProfile dskCh, final ServiceOffering offering, final DiskOffering diskOffering) {
        dskCh.setBytesReadRate(getDiskBytesReadRate(offering, diskOffering));
        dskCh.setBytesWriteRate(getDiskBytesWriteRate(offering, diskOffering));
        dskCh.setIopsReadRate(getDiskIopsReadRate(offering, diskOffering));
        dskCh.setIopsWriteRate(getDiskIopsWriteRate(offering, diskOffering));
    }

    @Override
    public DiskTO getDiskWithThrottling(final DataTO volTO, final Volume.Type volumeType, final long deviceId, final String path, final long offeringId, final long diskOfferingId) {
        DiskTO disk = null;
        if (volTO != null && volTO instanceof VolumeObjectTO) {
            VolumeObjectTO volumeTO = (VolumeObjectTO)volTO;
            ServiceOffering offering = _entityMgr.findById(ServiceOffering.class, offeringId);
            DiskOffering diskOffering = _entityMgr.findById(DiskOffering.class, diskOfferingId);
            if (volumeType == Volume.Type.ROOT) {
                setVolumeObjectTOThrottling(volumeTO, offering, diskOffering);
            } else {
                setVolumeObjectTOThrottling(volumeTO, null, diskOffering);
            }
            disk = new DiskTO(volumeTO, deviceId, path, volumeType);
        } else {
            disk = new DiskTO(volTO, deviceId, path, volumeType);
        }
        return disk;
    }

    @Override
    public boolean isStoragePoolDatastoreClusterParent(StoragePool pool) {
        List<StoragePoolVO> childStoragePools = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(pool.getId());
        if (childStoragePools != null && !childStoragePools.isEmpty()) {
            return true;
        }
        return false;
    }

    private void setVolumeObjectTOThrottling(VolumeObjectTO volumeTO, final ServiceOffering offering, final DiskOffering diskOffering) {
        volumeTO.setBytesReadRate(getDiskBytesReadRate(offering, diskOffering));
        volumeTO.setBytesWriteRate(getDiskBytesWriteRate(offering, diskOffering));
        volumeTO.setIopsReadRate(getDiskIopsReadRate(offering, diskOffering));
        volumeTO.setIopsWriteRate(getDiskIopsWriteRate(offering, diskOffering));
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_OBJECT_STORE_CREATE, eventDescription = "creating object storage")
    public ObjectStore discoverObjectStore(String name, String url, Long size, String providerName, Map details)
            throws IllegalArgumentException, InvalidParameterValueException {
        DataStoreProvider storeProvider = _dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            throw new InvalidParameterValueException("can't find object store provider: " + providerName);
        }

        // Check Unique object store name
        ObjectStoreVO objectStore = _objectStoreDao.findByName(name);
        if (objectStore != null) {
            throw new InvalidParameterValueException("The object store with name " + name + " already exists, try creating with another name");
        }

        try {
            UriUtils.validateUrl(url);
        } catch (InvalidParameterValueException e) {
            throw new InvalidParameterValueException(url + " is not a valid URL:" + e.getMessage());
        }

        // Check Unique object store url
        ObjectStoreVO objectStoreUrl = _objectStoreDao.findByUrl(url);
        if (objectStoreUrl != null) {
            throw new InvalidParameterValueException("The object store with url " + url + " already exists");
        }


        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        params.put("name", name);
        if (size == null) {
            params.put("size", 0L);
        } else {
            params.put("size", size);
        }
        params.put("providerName", storeProvider.getName());
        params.put("role", DataStoreRole.Object);
        params.put("details", details);

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();

        DataStore store;
        try {
            store = lifeCycle.initialize(params);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to add object store: " + e.getMessage(), e);
            }
            throw new CloudRuntimeException("Failed to add object store: " + e.getMessage(), e);
        }

        return (ObjectStore)_dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Object);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_OBJECT_STORE_DELETE, eventDescription = "deleting object storage")
    public boolean deleteObjectStore(DeleteObjectStoragePoolCmd cmd) {
        final long storeId = cmd.getId();
        // Verify that object store exists
        ObjectStoreVO store = _objectStoreDao.findById(storeId);
        if (store == null) {
            throw new InvalidParameterValueException("Object store with id " + storeId + " doesn't exist");
        }

        // Verify that there are no buckets in the store
        List<BucketVO> buckets = _bucketDao.listByObjectStoreId(storeId);
        if(buckets != null && buckets.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete object store with buckets");
        }

        // ready to delete
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                _objectStoreDetailsDao.deleteDetails(storeId);
                _objectStoreDao.remove(storeId);
            }
        });
        logger.debug("Successfully deleted object store: {}", store);
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_OBJECT_STORE_UPDATE, eventDescription = "update object storage")
    public ObjectStore updateObjectStore(Long id, UpdateObjectStoragePoolCmd cmd) {

        // Input validation
        ObjectStoreVO objectStoreVO = _objectStoreDao.findById(id);
        if (objectStoreVO == null) {
            throw new IllegalArgumentException("Unable to find object store with ID: " + id);
        }

        if(cmd.getUrl() != null ) {
            String url = cmd.getUrl();
            try {
                // Check URL
                UriUtils.validateUrl(url);
            } catch (final Exception e) {
                throw new InvalidParameterValueException(url + " is not a valid URL");
            }
            ObjectStoreEntity objectStore = (ObjectStoreEntity)_dataStoreMgr.getDataStore(objectStoreVO.getId(), DataStoreRole.Object);
            String oldUrl = objectStoreVO.getUrl();
            objectStoreVO.setUrl(url);
            _objectStoreDao.update(id, objectStoreVO);
            //Update URL and check access
            try {
                objectStore.listBuckets();
            } catch (Exception e) {
                //Revert to old URL on failure
                objectStoreVO.setUrl(oldUrl);
                _objectStoreDao.update(id, objectStoreVO);
                throw new IllegalArgumentException("Unable to access Object Storage with URL: " + cmd.getUrl());
            }
        }

        if(cmd.getName() != null ) {
            objectStoreVO.setName(cmd.getName());
        }
        if (cmd.getSize() != null) {
            objectStoreVO.setTotalSize(cmd.getSize() * ResourceType.bytesToGiB);
        }
        _objectStoreDao.update(id, objectStoreVO);
        logger.debug("Successfully updated object store: {}", objectStoreVO);
        return objectStoreVO;
    }

    @Override
    public CapacityVO getObjectStorageUsedStats(Long zoneId) {
        List<ObjectStoreVO> objectStores = _objectStoreDao.listObjectStores();
        Long allocated = 0L;
        Long total = 0L;
        for (ObjectStoreVO objectStore: objectStores) {
            if (objectStore.getAllocatedSize() != null) {
                allocated += objectStore.getAllocatedSize();
            }
            if (objectStore.getTotalSize() != null) {
                total += objectStore.getTotalSize();
            }
        }
        CapacityVO capacity = new CapacityVO(null, zoneId, null, null, allocated, total, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);
        return capacity;
    }
}
