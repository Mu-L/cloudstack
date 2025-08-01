//
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
//

package com.cloud.vm;

import org.apache.cloudstack.consoleproxy.ConsoleSession;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "console_session")
public class ConsoleSessionVO implements ConsoleSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "created")
    private Date created;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "user_id")
    private long userId;

    @Column(name = "instance_id")
    private long instanceId;

    @Column(name = "host_id")
    private long hostId;

    @Column(name = "acquired")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date acquired;

    @Column(name = "removed")
    private Date removed;

    @Column(name = "console_endpoint_creator_address")
    private String consoleEndpointCreatorAddress;

    @Column(name = "client_address")
    private String clientAddress;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    @Override
    public long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(long instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public long getHostId() {
        return hostId;
    }

    public void setHostId(long hostId) {
        this.hostId = hostId;
    }

    @Override
    public Date getRemoved() {
        return removed;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    @Override
    public Date getAcquired() {
        return acquired;
    }

    public void setAcquired(Date acquired) {
        this.acquired = acquired;
    }

    @Override
    public String getConsoleEndpointCreatorAddress() {
        return consoleEndpointCreatorAddress;
    }

    public void setConsoleEndpointCreatorAddress(String consoleEndpointCreatorAddress) {
        this.consoleEndpointCreatorAddress = consoleEndpointCreatorAddress;
    }

    @Override
    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(String clientAddress) {
        this.clientAddress = clientAddress;
    }
}
