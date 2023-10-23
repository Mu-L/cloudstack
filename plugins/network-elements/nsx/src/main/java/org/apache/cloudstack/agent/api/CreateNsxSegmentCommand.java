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
package org.apache.cloudstack.agent.api;

import com.cloud.network.dao.NetworkVO;

import java.util.Objects;

public class CreateNsxSegmentCommand extends CreateNsxTier1GatewayCommand {
    private NetworkVO tierNetwork;
    public CreateNsxSegmentCommand(String zoneName, Long zoneId, String accountName, Long accountId, String vpcName, NetworkVO tierNetwork) {
        super(zoneName, zoneId, accountName, accountId, vpcName);
        this.tierNetwork = tierNetwork;
    }

    public NetworkVO getTierNetwork() {
        return tierNetwork;
    }

    public void setTierNetwork(NetworkVO tierNetwork) {
        this.tierNetwork = tierNetwork;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        CreateNsxSegmentCommand command = (CreateNsxSegmentCommand) o;
        return Objects.equals(tierNetwork, command.tierNetwork);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), tierNetwork);
    }
}