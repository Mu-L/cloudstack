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
package com.cloud.host.dao;

import java.util.List;
import java.util.Map;

import com.cloud.host.DetailVO;
import com.cloud.utils.db.GenericDao;

public interface HostDetailsDao extends GenericDao<DetailVO, Long> {
    Map<String, String> findDetails(long hostId);

    void persist(long hostId, Map<String, String> details);

    DetailVO findDetail(long hostId, String name);

    void deleteDetails(long hostId);

    List<DetailVO> findByName(String name);

    void replaceExternalDetails(long hostId, Map<String, String> details);

}
