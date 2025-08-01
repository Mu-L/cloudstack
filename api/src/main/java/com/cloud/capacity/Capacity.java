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
package com.cloud.capacity;

import java.util.List;

import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

public interface Capacity extends InternalIdentity, Identity {
    public static final short CAPACITY_TYPE_MEMORY = 0;
    public static final short CAPACITY_TYPE_CPU = 1;
    public static final short CAPACITY_TYPE_STORAGE = 2;
    public static final short CAPACITY_TYPE_STORAGE_ALLOCATED = 3;
    public static final short CAPACITY_TYPE_VIRTUAL_NETWORK_PUBLIC_IP = 4;
    public static final short CAPACITY_TYPE_PRIVATE_IP = 5;
    public static final short CAPACITY_TYPE_SECONDARY_STORAGE = 6;
    public static final short CAPACITY_TYPE_VLAN = 7;
    public static final short CAPACITY_TYPE_DIRECT_ATTACHED_PUBLIC_IP = 8;
    public static final short CAPACITY_TYPE_LOCAL_STORAGE = 9;
    public static final short CAPACITY_TYPE_VIRTUAL_NETWORK_IPV6_SUBNET = 10;
    public static final short CAPACITY_TYPE_GPU = 19;
    public static final short CAPACITY_TYPE_OBJECT_STORAGE = 20;
    public static final short CAPACITY_TYPE_BACKUP_STORAGE = 21;

    public static final short CAPACITY_TYPE_CPU_CORE = 90;

    public static final List<Short> STORAGE_CAPACITY_TYPES = List.of(CAPACITY_TYPE_STORAGE,
            CAPACITY_TYPE_STORAGE_ALLOCATED,
            CAPACITY_TYPE_SECONDARY_STORAGE,
            CAPACITY_TYPE_LOCAL_STORAGE,
            CAPACITY_TYPE_BACKUP_STORAGE,
            CAPACITY_TYPE_OBJECT_STORAGE);

    public Long getHostOrPoolId();

    public Long getDataCenterId();

    public Long getPodId();

    public Long getClusterId();

    public long getUsedCapacity();

    public long getTotalCapacity();

    public short getCapacityType();

    public long getReservedCapacity();

    public Float getUsedPercentage();

    public Long getAllocatedCapacity();

    public String getTag();
}
