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
package org.apache.cloudstack.api;

/**
 * Enum class for various API error code used in CloudStack
 *
 */
public enum ApiErrorCode {

    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    UNAUTHORIZED2FA(511),
    METHOD_NOT_ALLOWED(405),
    MALFORMED_PARAMETER_ERROR(430),
    PARAM_ERROR(431),
    UNSUPPORTED_ACTION_ERROR(432),
    API_LIMIT_EXCEED(429),

    SERVICE_UNAVAILABLE(503),
    INTERNAL_ERROR(530),
    ACCOUNT_ERROR(531),
    ACCOUNT_RESOURCE_LIMIT_ERROR(532),
    INSUFFICIENT_CAPACITY_ERROR(533),
    RESOURCE_UNAVAILABLE_ERROR(534),
    RESOURCE_ALLOCATION_ERROR(535),
    RESOURCE_IN_USE_ERROR(536),
    NETWORK_RULE_CONFLICT_ERROR(537);

    private int httpCode;

    private ApiErrorCode(int httpStatusCode) {
        httpCode = httpStatusCode;
    }

    public int getHttpCode() {
        return httpCode;
    }

    public void setHttpCode(int httpCode) {
        this.httpCode = httpCode;
    }

    @Override
    public String toString() {
        return String.valueOf(this.httpCode);
    }

}
