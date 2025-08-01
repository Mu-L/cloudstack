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
package com.cloud.network.nsx;

import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.network.IpAddress;
import com.cloud.network.vpc.Vpc;

public interface NsxService {

    ConfigKey<Integer> NSX_API_FAILURE_RETRIES = new ConfigKey<>("Advanced", Integer.class,
            "nsx.api.failure.retries", "30",
            "Number of retries for NSX API operations in case of failures",
            true, ConfigKey.Scope.Zone);
    ConfigKey<Integer> NSX_API_FAILURE_INTERVAL = new ConfigKey<>("Advanced", Integer.class,
            "nsx.api.failure.interval", "60",
            "Waiting time (in seconds) before retrying an NSX API operation in case of failure",
            true, ConfigKey.Scope.Zone);

    boolean createVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName, boolean sourceNatEnabled);
    boolean updateVpcSourceNatIp(Vpc vpc, IpAddress address);
    String getSegmentId(long domainId, long accountId, long zoneId, Long vpcId, long networkId);
}
