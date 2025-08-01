/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.agent.api.storage;

import com.cloud.agent.api.Command;
import com.cloud.vm.VirtualMachine;

import java.util.List;

public class MergeDiskOnlyVmSnapshotCommand extends Command {

    private List<SnapshotMergeTreeTO> snapshotMergeTreeToList;
    private VirtualMachine.State vmState;
    private String vmName;

    public MergeDiskOnlyVmSnapshotCommand(List<SnapshotMergeTreeTO> snapshotMergeTreeToList, VirtualMachine.State vmState, String vmName) {
        this.snapshotMergeTreeToList = snapshotMergeTreeToList;
        this.vmState = vmState;
        this.vmName = vmName;
    }

    public List<SnapshotMergeTreeTO> getSnapshotMergeTreeToList() {
        return snapshotMergeTreeToList;
    }

    public VirtualMachine.State getVmState() {
        return vmState;
    }

    public String getVmName() {
        return vmName;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

}
