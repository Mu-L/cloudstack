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
package com.cloud.kubernetes.cluster;

import org.apache.cloudstack.acl.ControlledEntity;

import java.util.Map;

import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.Adapter;

public interface KubernetesServiceHelper extends Adapter {

    enum KubernetesClusterNodeType {
        CONTROL, WORKER, ETCD, DEFAULT
    }

    ControlledEntity findByUuid(String uuid);
    ControlledEntity findByVmId(long vmId);
    void checkVmCanBeDestroyed(UserVm userVm);
    boolean isValidNodeType(String nodeType);
    Map<String, Long> getServiceOfferingNodeTypeMap(Map<String, Map<String, String>> serviceOfferingNodeTypeMap);
    Map<String, Long> getTemplateNodeTypeMap(Map<String, Map<String, String>> templateNodeTypeMap);
    void cleanupForAccount(Account account);
}
