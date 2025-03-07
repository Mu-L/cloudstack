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
package org.apache.cloudstack.storage.resource;

import java.net.URI;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import org.apache.cloudstack.storage.template.DownloadManagerImpl;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

@Component
public class LocalNfsSecondaryStorageResource extends NfsSecondaryStorageResource {


    public LocalNfsSecondaryStorageResource() {
        this._dlMgr = new DownloadManagerImpl();
        ((DownloadManagerImpl)_dlMgr).setThreadPool(Executors.newFixedThreadPool(10));
        _storage = new JavaStorageLayer();
        this._inSystemVM = false;
    }

    @Override
    public Answer executeRequest(Command cmd) {
        return super.executeRequest(cmd);
    }

    @Override
    synchronized public String getRootDir(String secUrl, String nfsVersion) {
        try {
            URI uri = new URI(secUrl);
            String dir = mountUri(uri, nfsVersion);
            return _parent + "/" + dir;
        } catch (Exception e) {
            String msg = "GetRootDir for " + secUrl + " failed due to " + e;
            logger.error(msg, e);
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    protected void mount(String localRootPath, String remoteDevice, URI uri, String nfsVersion) {
        ensureLocalRootPathExists(localRootPath, uri);

        if (mountExists(localRootPath, uri)) {
            return;
        }

        attemptMount(localRootPath, remoteDevice, uri, nfsVersion);

        // Change permissions for the mountpoint - seems to bypass authentication
        Script script = new Script(true, "chmod", _timeout, logger);
        script.add("1777", localRootPath);
        String result = script.execute();
        if (result != null) {
            String errMsg = "Unable to set permissions for " + localRootPath + " due to " + result;
            logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }
        logger.debug("Successfully set 1777 permission for " + localRootPath);

        // XXX: Adding the check for creation of snapshots dir here. Might have
        // to move it somewhere more logical later.
        checkForSnapshotsDir(localRootPath);
        checkForVolumesDir(localRootPath);
    }

}
