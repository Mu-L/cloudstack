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
package com.cloud.service.dao;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.persistence.EntityExistsException;

import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import org.springframework.stereotype.Component;

import com.cloud.event.UsageEventVO;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
@DB()
public class ServiceOfferingDaoImpl extends GenericDaoBase<ServiceOfferingVO, Long> implements ServiceOfferingDao {

    @Inject
    protected ServiceOfferingDetailsDao detailsDao;
    @Inject
    protected VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;

    protected final SearchBuilder<ServiceOfferingVO> UniqueNameSearch;
    protected final SearchBuilder<ServiceOfferingVO> ServiceOfferingsByKeywordSearch;
    protected final SearchBuilder<ServiceOfferingVO> PublicCpuRamSearch;
    protected final SearchBuilder<ServiceOfferingVO> SearchComputeOfferingByComputeOnlyDiskOffering;

    public ServiceOfferingDaoImpl() {
        super();

        UniqueNameSearch = createSearchBuilder();
        UniqueNameSearch.and("name", UniqueNameSearch.entity().getUniqueName(), SearchCriteria.Op.EQ);
        UniqueNameSearch.and("system_use", UniqueNameSearch.entity().isSystemUse(), SearchCriteria.Op.EQ);
        UniqueNameSearch.done();

        ServiceOfferingsByKeywordSearch = createSearchBuilder();
        ServiceOfferingsByKeywordSearch.or("name", ServiceOfferingsByKeywordSearch.entity().getName(), SearchCriteria.Op.EQ);
        ServiceOfferingsByKeywordSearch.or("displayText", ServiceOfferingsByKeywordSearch.entity().getDisplayText(), SearchCriteria.Op.EQ);
        ServiceOfferingsByKeywordSearch.done();

        PublicCpuRamSearch = createSearchBuilder();
        PublicCpuRamSearch.and("cpu", PublicCpuRamSearch.entity().getCpu(), SearchCriteria.Op.EQ);
        PublicCpuRamSearch.and("ram", PublicCpuRamSearch.entity().getRamSize(), SearchCriteria.Op.EQ);
        PublicCpuRamSearch.and("system_use", PublicCpuRamSearch.entity().isSystemUse(), SearchCriteria.Op.EQ);
        PublicCpuRamSearch.done();

        SearchComputeOfferingByComputeOnlyDiskOffering = createSearchBuilder();
        SearchComputeOfferingByComputeOnlyDiskOffering.and("disk_offering_id", SearchComputeOfferingByComputeOnlyDiskOffering.entity().getDiskOfferingId(), SearchCriteria.Op.EQ);
        SearchComputeOfferingByComputeOnlyDiskOffering.done();
    }

    @Override
    public ServiceOfferingVO findByName(String name) {
        SearchCriteria<ServiceOfferingVO> sc = UniqueNameSearch.create();
        sc.setParameters("name", name);
        sc.setParameters("system_use", true);
        List<ServiceOfferingVO> vos = search(sc, null, null, false);
        if (vos.size() == 0) {
            return null;
        }

        return vos.get(0);
    }

    @Override
    @DB
    public ServiceOfferingVO persistSystemServiceOffering(ServiceOfferingVO offering) {
        assert offering.getUniqueName() != null : "how are you going to find this later if you don't set it?";
        ServiceOfferingVO vo = findByName(offering.getUniqueName());
        if (vo != null) {
            DiskOfferingVO diskOfferingVO = diskOfferingDao.findById(vo.getDiskOfferingId());
            // check invalid CPU speed in system service offering, set it to default value of 500 Mhz if 0 CPU speed is found
            if (vo.getSpeed() <= 0) {
                vo.setSpeed(500);
                update(vo.getId(), vo);
            }
            if (!vo.getUniqueName().endsWith("-Local")) {
                if (diskOfferingVO.isUseLocalStorage()) {
                    vo.setUniqueName(vo.getUniqueName() + "-Local");
                    vo.setName(vo.getName() + " - Local Storage");
                    update(vo.getId(), vo);
                }
            }
            return vo;
        }
        try {
            return persist(offering);
        } catch (EntityExistsException e) {
            // Assume it's conflict on unique name
            return findByName(offering.getUniqueName());
        }
    }

    @Override
    @DB
    public ServiceOfferingVO persistDeafultServiceOffering(ServiceOfferingVO offering) {
        assert offering.getUniqueName() != null : "unique name should be set for the service offering";
        ServiceOfferingVO vo = findByName(offering.getUniqueName());
        if (vo != null) {
            return vo;
        }
        try {
            return persist(offering);
        } catch (EntityExistsException e) {
            // Assume it's conflict on unique name
            return findByName(offering.getUniqueName());
        }
    }

    @Override
    public boolean remove(Long id) {
        ServiceOfferingVO offering = createForUpdate();
        offering.setRemoved(new Date());

        return update(id, offering);
    }

    @Override
    public void loadDetails(ServiceOfferingVO serviceOffering) {
        Map<String, String> details = detailsDao.listDetailsKeyPairs(serviceOffering.getId());
        serviceOffering.setDetails(details);
    }

    @Override
    public void saveDetails(ServiceOfferingVO serviceOffering) {
        Map<String, String> details = serviceOffering.getDetails();
        if (details == null) {
            return;
        }

        List<ServiceOfferingDetailsVO> resourceDetails = new ArrayList<ServiceOfferingDetailsVO>();
        for (String key : details.keySet()) {
            resourceDetails.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), key, details.get(key), true));
        }

        detailsDao.saveDetails(resourceDetails);
    }

    @Override
    public ServiceOfferingVO findById(Long vmId, long serviceOfferingId) {
        ServiceOfferingVO offering = super.findById(serviceOfferingId);
        if (offering.isDynamic()) {
            offering.setDynamicFlag(true);
            if (vmId == null) {
                throw new CloudRuntimeException("missing argument vmId");
            }
            Map<String, String> dynamicOffering = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
            return getComputeOffering(offering, dynamicOffering);
        }
        return offering;
    }

    @Override
    public ServiceOfferingVO findByIdIncludingRemoved(Long vmId, long serviceOfferingId) {
        ServiceOfferingVO offering = super.findByIdIncludingRemoved(serviceOfferingId);
        if (offering.isDynamic()) {
            offering.setDynamicFlag(true);
            if (vmId == null) {
                throw new CloudRuntimeException("missing argument vmId");
            }
            Map<String, String> dynamicOffering = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
            return getComputeOffering(offering, dynamicOffering);
        }
        return offering;
    }

    @Override
    public boolean isDynamic(long serviceOfferingId) {
        ServiceOfferingVO offering = super.findById(serviceOfferingId);
        return offering.getCpu() == null || offering.getSpeed() == null || offering.getRamSize() == null;
    }

    @Override
    public ServiceOfferingVO getComputeOffering(ServiceOfferingVO serviceOffering, Map<String, String> customParameters) {
        ServiceOfferingVO dummyoffering = new ServiceOfferingVO(serviceOffering);
        dummyoffering.setDynamicFlag(true);
        if (customParameters.containsKey(UsageEventVO.DynamicParameters.cpuNumber.name())) {
            dummyoffering.setCpu(Integer.parseInt(customParameters.get(UsageEventVO.DynamicParameters.cpuNumber.name())));
        }
        if (customParameters.containsKey(UsageEventVO.DynamicParameters.cpuSpeed.name())) {
            dummyoffering.setSpeed(Integer.parseInt(customParameters.get(UsageEventVO.DynamicParameters.cpuSpeed.name())));
        }
        if (customParameters.containsKey(UsageEventVO.DynamicParameters.memory.name())) {
            dummyoffering.setRamSize(Integer.parseInt(customParameters.get(UsageEventVO.DynamicParameters.memory.name())));
        }

        return dummyoffering;
    }

    @Override
    public List<ServiceOfferingVO> createSystemServiceOfferings(String name, String uniqueName, int cpuCount, int ramSize, int cpuSpeed,
            Integer rateMbps, Integer multicastRateMbps, boolean offerHA, String displayText, ProvisioningType provisioningType,
            boolean recreatable, String tags, boolean systemUse, VirtualMachine.Type vmType, boolean defaultUse) {
        DiskOfferingVO diskOfferingVO = new DiskOfferingVO(name, displayText, provisioningType, false, tags, recreatable, false, true);
        diskOfferingVO.setUniqueName(uniqueName);
        diskOfferingVO = diskOfferingDao.persistDefaultDiskOffering(diskOfferingVO);

        List<ServiceOfferingVO> list = new ArrayList<ServiceOfferingVO>();
        ServiceOfferingVO offering = new ServiceOfferingVO(name, cpuCount, ramSize, cpuSpeed, rateMbps, multicastRateMbps, offerHA, displayText,
                systemUse, vmType, defaultUse);
        offering.setUniqueName(uniqueName);
        offering.setDiskOfferingId(diskOfferingVO.getId());
        offering = persistSystemServiceOffering(offering);
        if (offering != null) {
            list.add(offering);
        }

        boolean useLocal = true;
        if (diskOfferingVO.isUseLocalStorage()) { // if 1st one is already local then 2nd needs to be shared
            useLocal = false;
        }

        diskOfferingVO = new DiskOfferingVO(name + (useLocal ? " - Local Storage" : ""), displayText, provisioningType, false, tags, recreatable, useLocal, true);
        diskOfferingVO.setUniqueName(uniqueName + (useLocal ? "-Local" : ""));
        diskOfferingVO = diskOfferingDao.persistDefaultDiskOffering(diskOfferingVO);

        offering = new ServiceOfferingVO(name + (useLocal ? " - Local Storage" : ""), cpuCount, ramSize, cpuSpeed, rateMbps, multicastRateMbps, offerHA, displayText,
                systemUse, vmType, defaultUse);
        offering.setUniqueName(uniqueName + (useLocal ? "-Local" : ""));
        offering.setDiskOfferingId(diskOfferingVO.getId());
        offering = persistSystemServiceOffering(offering);
        if (offering != null) {
            list.add(offering);
        }

        return list;
    }

    @Override
    public ServiceOfferingVO findDefaultSystemOffering(String offeringName, Boolean useLocalStorage) {
        String name = offeringName;
        if (useLocalStorage != null && useLocalStorage.booleanValue()) {
            name += "-Local";
        }
        ServiceOfferingVO serviceOffering = findByName(name);
        if (serviceOffering == null) {
            String message = "System service offering " + name + " not found";
            logger.error(message);
            throw new CloudRuntimeException(message);
        }
        return serviceOffering;
    }

    @Override
    public List<ServiceOfferingVO> listPublicByCpuAndMemory(Integer cpus, Integer memory) {
        SearchCriteria<ServiceOfferingVO> sc = PublicCpuRamSearch.create();
        sc.setParameters("cpu", cpus);
        sc.setParameters("ram", memory);
        sc.setParameters("system_use", false);
        return listBy(sc);
    }

    @Override
    public ServiceOfferingVO findServiceOfferingByComputeOnlyDiskOffering(long diskOfferingId, boolean includingRemoved) {
        SearchCriteria<ServiceOfferingVO> sc = SearchComputeOfferingByComputeOnlyDiskOffering.create();
        sc.setParameters("disk_offering_id", diskOfferingId);
        List<ServiceOfferingVO> vos = includingRemoved ? listIncludingRemovedBy(sc) : listBy(sc);
        if (vos.size() == 0) {
            return null;
        }
        return vos.get(0);
    }

    @Override
    public List<Long> listIdsByHostTag(String tag) {
        GenericSearchBuilder<ServiceOfferingVO, Long> sb = createSearchBuilder(Long.class);
        sb.selectFields(sb.entity().getId());
        sb.and("tagNotNull", sb.entity().getHostTag(), SearchCriteria.Op.NNULL);
        sb.and().op("tagEq", sb.entity().getHostTag(), SearchCriteria.Op.EQ);
        sb.or("tagStartLike", sb.entity().getHostTag(), SearchCriteria.Op.LIKE);
        sb.or("tagMidLike", sb.entity().getHostTag(), SearchCriteria.Op.LIKE);
        sb.or("tagEndLike", sb.entity().getHostTag(), SearchCriteria.Op.LIKE);
        sb.cp();
        sb.done();
        SearchCriteria<Long> sc = sb.create();

        sc.setParameters("tagEq", tag);
        sc.setParameters("tagStartLike", tag + ",%");
        sc.setParameters("tagMidLike", "%," + tag + ",%");
        sc.setParameters("tagEndLike",   "%," + tag);
        return customSearch(sc, null);
    }

    @Override
    public void addCheckForGpuEnabled(SearchBuilder<ServiceOfferingVO> serviceOfferingSearch, Boolean gpuEnabled) {
        if (gpuEnabled) {
            serviceOfferingSearch.and("gpuEnabled", serviceOfferingSearch.entity().getVgpuProfileId(), SearchCriteria.Op.NNULL);
        } else {
            serviceOfferingSearch.and("gpuDisabled", serviceOfferingSearch.entity().getVgpuProfileId(), SearchCriteria.Op.NULL);
        }
    }
}
