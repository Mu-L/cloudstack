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

package org.apache.cloudstack.quota.activationrule.presetvariables;

import org.apache.cloudstack.quota.constant.QuotaTypes;

public class ComputeOffering extends GenericPresetVariable {
    @PresetVariableDefinition(description = "A boolean informing if the compute offering is customized or not.")
    private boolean customized;

    @PresetVariableDefinition(description = "A boolean informing if the compute offering offers HA or not.", supportedTypes = {QuotaTypes.RUNNING_VM})
    private boolean offerHa;

    public boolean isCustomized() {
        return customized;
    }

    public void setCustomized(boolean customized) {
        this.customized = customized;
        fieldNamesToIncludeInToString.add("customized");
    }

    public boolean offerHa() {
        return offerHa;
    }

    public void setOfferHa(boolean offerHa) {
        this.offerHa = offerHa;
        fieldNamesToIncludeInToString.add("offerHa");
    }

}
