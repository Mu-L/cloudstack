// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.usage.parser;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import com.cloud.usage.UsageManagerImpl;
import com.cloud.utils.DateUtil;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.usage.UsageTypes;

import com.cloud.usage.StorageTypes;
import com.cloud.usage.UsageStorageVO;
import com.cloud.usage.UsageVO;
import com.cloud.usage.dao.UsageStorageDao;
import com.cloud.user.AccountVO;
import com.cloud.utils.Pair;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

@Component
public class StorageUsageParser extends UsageParser {
    @Inject
    private UsageStorageDao usageStorageDao;

    @Override
    public String getParserName() {
        return "Storage";
    }

    @Override
    protected boolean parse(AccountVO account, Date startDate, Date endDate) {
        if ((endDate == null) || endDate.after(new Date())) {
            endDate = new Date();
        }

        // - query usage_volume table with the following criteria:
        //     - look for an entry for accountId with start date in the given range
        //     - look for an entry for accountId with end date in the given range
        //     - look for an entry for accountId with end date null (currently running vm or owned IP)
        //     - look for an entry for accountId with start date before given range *and* end date after given range
        List<UsageStorageVO> usageUsageStorages = usageStorageDao.getUsageRecords(account.getId(), account.getDomainId(), startDate, endDate, false, 0);

        if (usageUsageStorages.isEmpty()) {
            logger.debug("No Storage usage events for this period");
            return true;
        }

        // This map has both the running time *and* the usage amount.
        Map<String, Pair<Long, Long>> usageMap = new HashMap<String, Pair<Long, Long>>();

        Map<String, StorageInfo> storageMap = new HashMap<String, StorageInfo>();

        // loop through all the usage volumes, create a usage record for each
        for (UsageStorageVO usageStorage : usageUsageStorages) {
            long storageId = usageStorage.getEntityId();
            int storage_type = usageStorage.getStorageType();
            long size = usageStorage.getSize();
            Long virtualSize = usageStorage.getVirtualSize();
            long zoneId = usageStorage.getZoneId();
            Long sourceId = usageStorage.getSourceId();

            String key = "" + storageId + "Z" + zoneId + "T" + storage_type;

            // store the info in the storage map
            storageMap.put(key, new StorageInfo(zoneId, storageId, storage_type, sourceId, size, virtualSize));

            Date storageCreateDate = usageStorage.getCreated();
            Date storageDeleteDate = usageStorage.getDeleted();

            if ((storageDeleteDate == null) || storageDeleteDate.after(endDate)) {
                storageDeleteDate = endDate;
            }

            // clip the start date to the beginning of our aggregation range if the vm has been running for a while
            if (storageCreateDate.before(startDate)) {
                storageCreateDate = startDate;
            }

            if (storageCreateDate.after(endDate)) {
                //Ignore records created after endDate
                continue;
            }

            long currentDuration = (storageDeleteDate.getTime() - storageCreateDate.getTime()) + 1; // make sure this is an inclusive check for milliseconds (i.e. use n - m + 1 to find total number of millis to charge)

            updateStorageUsageData(usageMap, key, usageStorage.getEntityId(), currentDuration);
        }

        for (String storageIdKey : usageMap.keySet()) {
            Pair<Long, Long> storagetimeInfo = usageMap.get(storageIdKey);
            long useTime = storagetimeInfo.second().longValue();

            // Only create a usage record if we have a runningTime of bigger than zero.
            if (useTime > 0L) {
                StorageInfo info = storageMap.get(storageIdKey);
                createUsageRecord(info.getZoneId(), info.getStorageType(), useTime, startDate, endDate, account, info.getStorageId(), info.getSourceId(), info.getSize(),
                    info.getVirtualSize());
            }
        }

        return true;
    }

    private void updateStorageUsageData(Map<String, Pair<Long, Long>> usageDataMap, String key, long storageId, long duration) {
        Pair<Long, Long> volUsageInfo = usageDataMap.get(key);
        if (volUsageInfo == null) {
            volUsageInfo = new Pair<Long, Long>(new Long(storageId), new Long(duration));
        } else {
            Long runningTime = volUsageInfo.second();
            runningTime = new Long(runningTime.longValue() + duration);
            volUsageInfo = new Pair<Long, Long>(volUsageInfo.first(), runningTime);
        }
        usageDataMap.put(key, volUsageInfo);
    }

    private void createUsageRecord(long zoneId, int type, long runningTime, Date startDate, Date endDate, AccountVO account, long storageId, Long sourceId,
        long size, Long virtualSize) {
        // Our smallest increment is hourly for now
        logger.debug("Total running time {} ms", runningTime);

        float usage = runningTime / 1000f / 60f / 60f;

        DecimalFormat dFormat = new DecimalFormat("#.######");
        String usageDisplay = dFormat.format(usage);

        logger.debug("Creating Storage usage record for type [{}], with id [{}], usage [{}], startDate [{}], and endDate [{}], for account [{}].",
                type, storageId, usageDisplay, DateUtil.displayDateInTimezone(UsageManagerImpl.getUsageAggregationTimeZone(), startDate),
                DateUtil.displayDateInTimezone(UsageManagerImpl.getUsageAggregationTimeZone(), endDate), account.getId());

        String usageDesc = "";
        Long tmplSourceId = null;

        int usage_type = 0;
        switch (type) {
            case StorageTypes.TEMPLATE:
                usage_type = UsageTypes.TEMPLATE;
                usageDesc += "Template ";
                tmplSourceId = sourceId;
                break;
            case StorageTypes.ISO:
                usage_type = UsageTypes.ISO;
                usageDesc += "ISO ";
                virtualSize = size;
                break;
            case StorageTypes.SNAPSHOT:
                usage_type = UsageTypes.SNAPSHOT;
                usageDesc += "Snapshot ";
                break;
            case StorageTypes.VOLUME:
                usage_type = UsageTypes.VOLUME_SECONDARY;
                usageDesc += "Volume ";
                break;
        }
        //Create the usage record
        usageDesc += "Id:" + storageId + " Size:" + toHumanReadableSize(size);
        if (type != StorageTypes.SNAPSHOT) {
            usageDesc += " VirtualSize: " + toHumanReadableSize(virtualSize);
        }

        //ToDo: get zone id
        UsageVO usageRecord =
            new UsageVO(zoneId, account.getId(), account.getDomainId(), usageDesc, usageDisplay + " Hrs", usage_type, new Double(usage), null, null, null, tmplSourceId,
                storageId, size, virtualSize, startDate, endDate);
        usageDao.persist(usageRecord);
    }

    private static class StorageInfo {
        private long zoneId;
        private long storageId;
        private int storageType;
        private Long sourceId;
        private long size;
        private Long virtualSize;

        public StorageInfo(long zoneId, long storageId, int storageType, Long sourceId, long size, Long virtualSize) {
            this.zoneId = zoneId;
            this.storageId = storageId;
            this.storageType = storageType;
            this.sourceId = sourceId;
            this.size = size;
            this.virtualSize = virtualSize;
        }

        public Long getVirtualSize() {
            return virtualSize;
        }

        public long getZoneId() {
            return zoneId;
        }

        public long getStorageId() {
            return storageId;
        }

        public int getStorageType() {
            return storageType;
        }

        public long getSourceId() {
            return sourceId;
        }

        public long getSize() {
            return size;
        }
    }
}
