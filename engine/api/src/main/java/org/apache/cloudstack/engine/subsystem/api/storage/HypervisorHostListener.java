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
package org.apache.cloudstack.engine.subsystem.api.storage;

import com.cloud.exception.StorageConflictException;
import com.cloud.host.Host;
import com.cloud.storage.StoragePool;

public interface HypervisorHostListener {
    boolean hostAdded(long hostId);

    default boolean hostConnect(Host host, StoragePool pool) throws StorageConflictException {
        return hostConnect(host.getId(), pool.getId());
    }

    boolean hostConnect(long hostId, long poolId) throws StorageConflictException;

    default boolean hostDisconnected(Host host, StoragePool pool) throws StorageConflictException {
        return hostDisconnected(host.getId(), pool.getId());
    }

    boolean hostDisconnected(long hostId, long poolId);

    boolean hostAboutToBeRemoved(long hostId);

    boolean hostRemoved(long hostId, long clusterId);

    boolean hostEnabled(long hostId);
}
