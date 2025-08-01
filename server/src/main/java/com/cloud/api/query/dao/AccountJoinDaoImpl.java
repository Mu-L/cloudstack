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
package com.cloud.api.query.dao;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.ResourceLimitAndCountResponse;
import org.apache.cloudstack.api.response.UserResponse;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.configuration.Resource;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class AccountJoinDaoImpl extends GenericDaoBase<AccountJoinVO, Long> implements AccountJoinDao {

    @Inject
    private ConfigurationDao configDao;
    private final SearchBuilder<AccountJoinVO> acctIdSearch;
    private final SearchBuilder<AccountJoinVO> domainSearch;
    @Inject
    AccountManager _acctMgr;

    protected AccountJoinDaoImpl() {

        domainSearch = createSearchBuilder();
        domainSearch.and("idIN", domainSearch.entity().getId(), SearchCriteria.Op.IN);
        domainSearch.done();

        acctIdSearch = createSearchBuilder();
        acctIdSearch.and("id", acctIdSearch.entity().getId(), SearchCriteria.Op.EQ);
        acctIdSearch.done();

        _count = "select count(distinct id) from account_view WHERE ";
    }

    @Override
    public AccountResponse newAccountResponse(ResponseView view, EnumSet<DomainDetails> details, AccountJoinVO account) {
        AccountResponse accountResponse = new AccountResponse();
        accountResponse.setId(account.getUuid());
        accountResponse.setName(account.getAccountName());
        accountResponse.setAccountType(account.getType().ordinal());
        accountResponse.setDomainId(account.getDomainUuid());
        accountResponse.setDomainName(account.getDomainName());
        StringBuilder domainPath = new StringBuilder("ROOT");
        (domainPath.append(account.getDomainPath())).deleteCharAt(domainPath.length() - 1);
        accountResponse.setDomainPath(domainPath.toString());
        accountResponse.setState(account.getState().toString());
        accountResponse.setCreated(account.getCreated());
        accountResponse.setNetworkDomain(account.getNetworkDomain());
        accountResponse.setDefaultZone(account.getDataCenterUuid());
        accountResponse.setIsDefault(account.isDefault());
        if (view == ResponseView.Full) {
            accountResponse.setApiKeyAccess(account.getApiKeyAccess());
        }

        // get network stat
        accountResponse.setBytesReceived(account.getBytesReceived());
        accountResponse.setBytesSent(account.getBytesSent());

        if (details.contains(DomainDetails.all) || details.contains(DomainDetails.resource)) {
            boolean fullView = (view == ResponseView.Full && _acctMgr.isRootAdmin(account.getId()));
            setResourceLimits(account, fullView, accountResponse);

            //get resource limits for projects
            long projectLimit = ApiDBUtils.findCorrectResourceLimit(account.getProjectLimit(), account.getId(), ResourceType.project);
            String projectLimitDisplay = (fullView || projectLimit == -1) ? Resource.UNLIMITED : String.valueOf(projectLimit);
            long projectTotal = (account.getProjectTotal() == null) ? 0 : account.getProjectTotal();
            String projectAvail = (fullView || projectLimit == -1) ? Resource.UNLIMITED : String.valueOf(projectLimit - projectTotal);
            accountResponse.setProjectLimit(projectLimitDisplay);
            accountResponse.setProjectTotal(projectTotal);
            accountResponse.setProjectAvailable(projectAvail);
        }

        // set async job
        if (account.getJobId() != null) {
            accountResponse.setJobId(account.getJobUuid());
            accountResponse.setJobStatus(account.getJobStatus());
        }

        // adding all the users for an account as part of the response obj
        List<UserAccountJoinVO> usersForAccount = ApiDBUtils.findUserViewByAccountId(account.getId());
        List<UserResponse> userResponses = ViewResponseHelper.createUserResponse(usersForAccount.toArray(new UserAccountJoinVO[usersForAccount.size()]));
        accountResponse.setUsers(userResponses);

        // set details
        accountResponse.setDetails(ApiDBUtils.getAccountDetails(account.getId()));
        accountResponse.setObjectName("account");

        // add all the acl groups for an account
        accountResponse.setGroups(_acctMgr.listAclGroupsByAccount(account.getId()));

        return accountResponse;
    }

    @Override
    public void setResourceLimits(AccountJoinVO account, boolean fullView, ResourceLimitAndCountResponse response) {
        // Get resource limits and counts
        long vmLimit = ApiDBUtils.findCorrectResourceLimit(account.getVmLimit(), account.getId(), ResourceType.user_vm);
        String vmLimitDisplay = (fullView || vmLimit == -1) ? Resource.UNLIMITED : String.valueOf(vmLimit);
        long vmTotal = (account.getVmTotal() == null) ? 0 : account.getVmTotal();
        String vmAvail = (fullView || vmLimit == -1) ? Resource.UNLIMITED : String.valueOf(vmLimit - vmTotal);
        response.setVmLimit(vmLimitDisplay);
        response.setVmTotal(vmTotal);
        response.setVmAvailable(vmAvail);

        long ipLimit = ApiDBUtils.findCorrectResourceLimit(account.getIpLimit(), account.getId(), ResourceType.public_ip);
        String ipLimitDisplay = (fullView || ipLimit == -1) ? Resource.UNLIMITED : String.valueOf(ipLimit);
        long ipTotal = (account.getIpTotal() == null) ? 0 : account.getIpTotal();

        Long ips = ipLimit - ipTotal;
        // check how many free ips are left, and if it's less than max allowed number of ips from account - use this
        // value
        Long ipsLeft = account.getIpFree();
        boolean unlimited = true;
        if (ips.longValue() > ipsLeft.longValue()) {
            ips = ipsLeft;
            unlimited = false;
        }

        String ipAvail = ((fullView || ipLimit == -1) && unlimited) ? Resource.UNLIMITED : String.valueOf(ips);

        response.setIpLimit(ipLimitDisplay);
        response.setIpTotal(ipTotal);
        response.setIpAvailable(ipAvail);

        long volumeLimit = ApiDBUtils.findCorrectResourceLimit(account.getVolumeLimit(), account.getId(), ResourceType.volume);
        String volumeLimitDisplay = (fullView || volumeLimit == -1) ? Resource.UNLIMITED : String.valueOf(volumeLimit);
        long volumeTotal = (account.getVolumeTotal() == null) ? 0 : account.getVolumeTotal();
        String volumeAvail = (fullView || volumeLimit == -1) ? Resource.UNLIMITED : String.valueOf(volumeLimit - volumeTotal);
        response.setVolumeLimit(volumeLimitDisplay);
        response.setVolumeTotal(volumeTotal);
        response.setVolumeAvailable(volumeAvail);

        long snapshotLimit = ApiDBUtils.findCorrectResourceLimit(account.getSnapshotLimit(), account.getId(), ResourceType.snapshot);
        String snapshotLimitDisplay = (fullView || snapshotLimit == -1) ? Resource.UNLIMITED : String.valueOf(snapshotLimit);
        long snapshotTotal = (account.getSnapshotTotal() == null) ? 0 : account.getSnapshotTotal();
        String snapshotAvail = (fullView || snapshotLimit == -1) ? Resource.UNLIMITED : String.valueOf(snapshotLimit - snapshotTotal);
        response.setSnapshotLimit(snapshotLimitDisplay);
        response.setSnapshotTotal(snapshotTotal);
        response.setSnapshotAvailable(snapshotAvail);

        Long templateLimit = ApiDBUtils.findCorrectResourceLimit(account.getTemplateLimit(), account.getId(), ResourceType.template);
        String templateLimitDisplay = (fullView || templateLimit == -1) ? Resource.UNLIMITED : String.valueOf(templateLimit);
        Long templateTotal = (account.getTemplateTotal() == null) ? 0 : account.getTemplateTotal();
        String templateAvail = (fullView || templateLimit == -1) ? Resource.UNLIMITED : String.valueOf(templateLimit - templateTotal);
        response.setTemplateLimit(templateLimitDisplay);
        response.setTemplateTotal(templateTotal);
        response.setTemplateAvailable(templateAvail);

        // Get stopped and running VMs
        response.setVmStopped(account.getVmStopped()!=null ? account.getVmStopped() : 0);
        response.setVmRunning(account.getVmRunning()!=null ? account.getVmRunning() : 0);

        //get resource limits for networks
        long networkLimit = ApiDBUtils.findCorrectResourceLimit(account.getNetworkLimit(), account.getId(), ResourceType.network);
        String networkLimitDisplay = (fullView || networkLimit == -1) ? Resource.UNLIMITED : String.valueOf(networkLimit);
        long networkTotal = (account.getNetworkTotal() == null) ? 0 : account.getNetworkTotal();
        String networkAvail = (fullView || networkLimit == -1) ? Resource.UNLIMITED : String.valueOf(networkLimit - networkTotal);
        response.setNetworkLimit(networkLimitDisplay);
        response.setNetworkTotal(networkTotal);
        response.setNetworkAvailable(networkAvail);

        //get resource limits for vpcs
        long vpcLimit = ApiDBUtils.findCorrectResourceLimit(account.getVpcLimit(), account.getId(), ResourceType.vpc);
        String vpcLimitDisplay = (fullView || vpcLimit == -1) ? Resource.UNLIMITED : String.valueOf(vpcLimit);
        long vpcTotal = (account.getVpcTotal() == null) ? 0 : account.getVpcTotal();
        String vpcAvail = (fullView || vpcLimit == -1) ? Resource.UNLIMITED : String.valueOf(vpcLimit - vpcTotal);
        response.setVpcLimit(vpcLimitDisplay);
        response.setVpcTotal(vpcTotal);
        response.setVpcAvailable(vpcAvail);

        //get resource limits for cpu cores
        long cpuLimit = ApiDBUtils.findCorrectResourceLimit(account.getCpuLimit(), account.getId(), ResourceType.cpu);
        String cpuLimitDisplay = (fullView || cpuLimit == -1) ? Resource.UNLIMITED : String.valueOf(cpuLimit);
        long cpuTotal = (account.getCpuTotal() == null) ? 0 : account.getCpuTotal();
        String cpuAvail = (fullView || cpuLimit == -1) ? Resource.UNLIMITED : String.valueOf(cpuLimit - cpuTotal);
        response.setCpuLimit(cpuLimitDisplay);
        response.setCpuTotal(cpuTotal);
        response.setCpuAvailable(cpuAvail);

        //get resource limits for memory
        long memoryLimit = ApiDBUtils.findCorrectResourceLimit(account.getMemoryLimit(), account.getId(), ResourceType.memory);
        String memoryLimitDisplay = (fullView || memoryLimit == -1) ? Resource.UNLIMITED : String.valueOf(memoryLimit);
        long memoryTotal = (account.getMemoryTotal() == null) ? 0 : account.getMemoryTotal();
        String memoryAvail = (fullView || memoryLimit == -1) ? Resource.UNLIMITED : String.valueOf(memoryLimit - memoryTotal);
        response.setMemoryLimit(memoryLimitDisplay);
        response.setMemoryTotal(memoryTotal);
        response.setMemoryAvailable(memoryAvail);

        //get resource limits for gpus
        setGpuResourceLimits(account, fullView,response);

        //get resource limits for primary storage space and convert it from Bytes to GiB
        long primaryStorageLimit = ApiDBUtils.findCorrectResourceLimit(account.getPrimaryStorageLimit(), account.getId(), ResourceType.primary_storage);
        String primaryStorageLimitDisplay = (fullView || primaryStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf(primaryStorageLimit / ResourceType.bytesToGiB);
        long primaryStorageTotal = (account.getPrimaryStorageTotal() == null) ? 0 : (account.getPrimaryStorageTotal() / ResourceType.bytesToGiB);
        String primaryStorageAvail = (fullView || primaryStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf((primaryStorageLimit / ResourceType.bytesToGiB) - primaryStorageTotal);

        response.setPrimaryStorageLimit(primaryStorageLimitDisplay);
        response.setPrimaryStorageTotal(primaryStorageTotal);
        response.setPrimaryStorageAvailable(primaryStorageAvail);

        //get resource limits for secondary storage space and convert it from Bytes to GiB
        long secondaryStorageLimit = ApiDBUtils.findCorrectResourceLimit(account.getSecondaryStorageLimit(), account.getId(), ResourceType.secondary_storage);
        String secondaryStorageLimitDisplay = (fullView || secondaryStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf(secondaryStorageLimit / ResourceType.bytesToGiB);
        float secondaryStorageTotal = (account.getSecondaryStorageTotal() == null) ? 0 : (account.getSecondaryStorageTotal() / (ResourceType.bytesToGiB * 1f));
        String secondaryStorageAvail = (fullView || secondaryStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf(( (double)secondaryStorageLimit / ResourceType.bytesToGiB)
                - secondaryStorageTotal);

        response.setSecondaryStorageLimit(secondaryStorageLimitDisplay);
        response.setSecondaryStorageTotal(secondaryStorageTotal);
        response.setSecondaryStorageAvailable(secondaryStorageAvail);

        //get resource limits for backups
        long backupLimit = ApiDBUtils.findCorrectResourceLimit(account.getBackupLimit(), account.getId(), ResourceType.backup);
        String backupLimitDisplay = (fullView || backupLimit == -1) ? Resource.UNLIMITED : String.valueOf(backupLimit);
        long backupTotal = (account.getBackupTotal() == null) ? 0 : account.getBackupTotal();
        String backupAvail = (fullView || backupLimit == -1) ? Resource.UNLIMITED : String.valueOf(backupLimit - backupTotal);
        response.setBackupLimit(backupLimitDisplay);
        response.setBackupTotal(backupTotal);
        response.setBackupAvailable(backupAvail);

        //get resource limits for backup storage space and convert it from Bytes to GiB
        long backupStorageLimit = ApiDBUtils.findCorrectResourceLimit(account.getBackupStorageLimit(), account.getId(), ResourceType.backup_storage);
        String backupStorageLimitDisplay = (fullView || backupStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf(backupStorageLimit / ResourceType.bytesToGiB);
        long backupStorageTotal = (account.getBackupStorageTotal() == null) ? 0 : (account.getBackupStorageTotal() / ResourceType.bytesToGiB);
        String backupStorageAvail = (fullView || backupStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf((backupStorageLimit / ResourceType.bytesToGiB) - backupStorageTotal);
        response.setBackupStorageLimit(backupStorageLimitDisplay);
        response.setBackupStorageTotal(backupStorageTotal);
        response.setBackupStorageAvailable(backupStorageAvail);

        //get resource limits for buckets
        long bucketLimit = ApiDBUtils.findCorrectResourceLimit(account.getBucketLimit(), account.getId(), ResourceType.bucket);
        String bucketLimitDisplay = (fullView || bucketLimit == -1) ? Resource.UNLIMITED : String.valueOf(bucketLimit);
        long bucketTotal = (account.getBucketTotal() == null) ? 0 : account.getBucketTotal();
        String bucketAvail = (fullView || bucketLimit == -1) ? Resource.UNLIMITED : String.valueOf(bucketLimit - bucketTotal);
        response.setBucketLimit(bucketLimitDisplay);
        response.setBucketTotal(bucketTotal);
        response.setBucketAvailable(bucketAvail);

        //get resource limits for object storage space and convert it from Bytes to GiB
        long objectStorageLimit = ApiDBUtils.findCorrectResourceLimit(account.getObjectStorageLimit(), account.getId(), ResourceType.object_storage);
        String objectStorageLimitDisplay = (fullView || objectStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf(objectStorageLimit / ResourceType.bytesToGiB);
        long objectStorageTotal = (account.getObjectStorageTotal() == null) ? 0 : (account.getObjectStorageTotal() / ResourceType.bytesToGiB);
        String objectStorageAvail = (fullView || objectStorageLimit == -1) ? Resource.UNLIMITED : String.valueOf((objectStorageLimit / ResourceType.bytesToGiB) - objectStorageTotal);
        response.setObjectStorageLimit(objectStorageLimitDisplay);
        response.setObjectStorageTotal(objectStorageTotal);
        response.setObjectStorageAvailable(objectStorageAvail);
    }

    private void setGpuResourceLimits(AccountJoinVO account, boolean fullView, ResourceLimitAndCountResponse response) {
        long gpuLimit = ApiDBUtils.findCorrectResourceLimit(account.getGpuLimit(), account.getId(), ResourceType.gpu);
        String gpuLimitDisplay = (fullView || gpuLimit == -1) ? Resource.UNLIMITED : String.valueOf(gpuLimit);
        long gpuTotal = (account.getGpuTotal() == null) ? 0 : account.getGpuTotal();
        String gpuAvail = (fullView || gpuLimit == -1) ? Resource.UNLIMITED : String.valueOf(gpuLimit - gpuTotal);
        response.setGpuLimit(gpuLimitDisplay);
        response.setGpuTotal(gpuTotal);
        response.setGpuAvailable(gpuAvail);
    }

    @Override
    public List<AccountJoinVO> searchByIds(Long... accountIds) {
        // set detail batch query size
        int DETAILS_BATCH_SIZE = 2000;
        String batchCfg = configDao.getValue("detail.batch.query.size");
        if (batchCfg != null) {
            DETAILS_BATCH_SIZE = Integer.parseInt(batchCfg);
        }

        List<AccountJoinVO> uvList = new ArrayList<>();
        // query details by batches
        int curr_index = 0;
        if (accountIds.length > DETAILS_BATCH_SIZE) {
            while ((curr_index + DETAILS_BATCH_SIZE) <= accountIds.length) {
                Long[] ids = new Long[DETAILS_BATCH_SIZE];
                for (int k = 0, j = curr_index; j < curr_index + DETAILS_BATCH_SIZE; j++, k++) {
                    ids[k] = accountIds[j];
                }
                SearchCriteria<AccountJoinVO> sc = domainSearch.create();
                sc.setParameters("idIN", ids);
                List<AccountJoinVO> accounts = searchIncludingRemoved(sc, null, null, false);
                if (accounts != null) {
                    uvList.addAll(accounts);
                }
                curr_index += DETAILS_BATCH_SIZE;
            }
        }
        if (curr_index < accountIds.length) {
            int batch_size = (accountIds.length - curr_index);
            // set the ids value
            Long[] ids = new Long[batch_size];
            for (int k = 0, j = curr_index; j < curr_index + batch_size; j++, k++) {
                ids[k] = accountIds[j];
            }
            SearchCriteria<AccountJoinVO> sc = domainSearch.create();
            sc.setParameters("idIN", ids);
            List<AccountJoinVO> accounts = searchIncludingRemoved(sc, null, null, false);
            if (accounts != null) {
                uvList.addAll(accounts);
            }
        }
        return uvList;
    }

    @Override
    public AccountJoinVO newAccountView(Account acct) {
        SearchCriteria<AccountJoinVO> sc = acctIdSearch.create();
        sc.setParameters("id", acct.getId());
        List<AccountJoinVO> accounts = searchIncludingRemoved(sc, null, null, false);
        assert accounts != null && accounts.size() == 1 : "No account found for account id " + acct.getId();
        return accounts.get(0);

    }

}
