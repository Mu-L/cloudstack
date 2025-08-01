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
<template>
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        layout="vertical"
       >
        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input
            v-model:value="form.name"
            :placeholder="apiParams.name.description"
            v-focus="true"/>
        </a-form-item>
        <a-form-item name="displaytext" ref="displaytext">
          <template #label>
            <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
          </template>
          <a-input
            v-model:value="form.displaytext"
            :placeholder="apiParams.displaytext.description"/>
        </a-form-item>
        <a-form-item name="zoneid" ref="zoneid">
          <template #label>
            <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
          </template>
          <a-select
            :loading="loadingZone"
            v-model:value="form.zoneid"
            @change="val => changeZone(val)"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="zone in zones" :key="zone.id" :label="zone.name">
              <span>
                <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
                <global-outlined v-else style="margin-right: 5px" />
                {{ zone.name }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <ownership-selection v-if="isAdminOrDomainAdmin()" @fetch-owner="fetchOwnerOptions"/>
        <a-form-item name="cidr" ref="cidr" v-if="selectedVpcOffering && (selectedVpcOffering.networkmode !== 'ROUTED' || isAdmin())">
          <template #label>
            <tooltip-label :title="$t('label.cidr')" :tooltip="apiParams.cidr.description"/>
          </template>
          <a-input
            v-model:value="form.cidr"
            :placeholder="apiParams.cidr.description"/>
        </a-form-item>
        <a-form-item
          v-if="selectedVpcOffering && selectedVpcOffering.networkmode === 'ROUTED'"
          ref="cidrsize"
          name="cidrsize">
          <template #label>
            <tooltip-label :title="$t('label.cidrsize')" :tooltip="apiParams.cidrsize.description"/>
          </template>
          <a-input
            v-model:value="form.cidrsize"
            :placeholder="apiParams.cidrsize.description"/>
        </a-form-item>
        <a-form-item name="networkdomain" ref="networkdomain">
          <template #label>
            <tooltip-label :title="$t('label.networkdomain')" :tooltip="apiParams.networkdomain.description"/>
          </template>
          <a-input
            v-model:value="form.networkdomain"
            :placeholder="apiParams.networkdomain.description"/>
        </a-form-item>
        <a-form-item name="vpcofferingid" ref="vpcofferingid">
          <template #label>
            <tooltip-label :title="$t('label.vpcofferingid')" :tooltip="apiParams.vpcofferingid.description"/>
          </template>
          <a-select
            :loading="loadingOffering"
            v-model:value="form.vpcofferingid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            @change="handleVpcOfferingChange" >
            <a-select-option :value="offering.id" v-for="offering in vpcOfferings" :key="offering.id" :label="offering.name">
              {{ offering.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item ref="asnumber" name="asnumber" v-if="isASNumberRequired()">
          <template #label>
            <tooltip-label :title="$t('label.asnumber')" :tooltip="apiParams.asnumber.description"/>
          </template>
          <a-select
            v-model:value="form.asnumber"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :loading="asNumberLoading"
            :placeholder="apiParams.asnumber.description"
            @change="val => { handleASNumberChange(val) }">
            <a-select-option v-for="(opt, optIndex) in asNumbersZone" :key="optIndex" :label="opt.asnumber">
              {{ opt.asnumber }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <div v-if="setMTU">
          <a-form-item
            ref="publicmtu"
            name="publicmtu">
            <template #label>
              <tooltip-label :title="$t('label.publicmtu')" :tooltip="apiParams.publicmtu.description"/>
            </template>
            <a-input-number
              style="width: 100%;"
              v-model:value="form.publicmtu"
              :placeholder="apiParams.publicmtu.description"
              @change="updateMtu()"/>
              <div style="color: red" v-if="errorPublicMtu" v-html="errorPublicMtu"></div>
          </a-form-item>
        </div>
        <div v-if="isNsxNetwork">
          <a-form-item name="userouteripresolver" ref="userouteripresolver">
            <template #label>
              <tooltip-label :title="$t('label.use.router.ip.resolver')" :tooltip="apiParams.userouteripresolver.description"/>
            </template>
            <a-switch v-model:checked="useRouterIpResolver" />
          </a-form-item>
        </div>
        <a-row :gutter="12" v-if="selectedVpcOfferingSupportsDns && !useRouterIpResolver">
          <a-col :md="12" :lg="12">
            <a-form-item v-if="'dns1' in apiParams" name="dns1" ref="dns1">
              <template #label>
                <tooltip-label :title="$t('label.dns1')" :tooltip="apiParams.dns1.description"/>
              </template>
              <a-input
                v-model:value="form.dns1"
                :placeholder="apiParams.dns1.description"/>
            </a-form-item>
          </a-col>
          <a-col :md="12" :lg="12">
            <a-form-item v-if="'dns2' in apiParams" name="dns2" ref="dns2">
              <template #label>
                <tooltip-label :title="$t('label.dns2')" :tooltip="apiParams.dns2.description"/>
              </template>
              <a-input
                v-model:value="form.dns2"
                :placeholder="apiParams.dns2.description"/>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="selectedVpcOfferingSupportsDns">
          <a-col :md="12" :lg="12">
            <a-form-item v-if="selectedVpcOffering && selectedVpcOffering.internetprotocol === 'DualStack' && 'ip6dns1' in apiParams" name="ip6dns1" ref="ip6dns1">
              <template #label>
                <tooltip-label :title="$t('label.ip6dns1')" :tooltip="apiParams.ip6dns1.description"/>
              </template>
              <a-input
                v-model:value="form.ip6dns1"
                :placeholder="apiParams.ip6dns1.description"/>
            </a-form-item>
          </a-col>
          <a-col :md="12" :lg="12">
            <a-form-item v-if="selectedVpcOffering && selectedVpcOffering.internetprotocol === 'DualStack' && 'ip6dns2' in apiParams" name="ip6dns2" ref="ip6dns2">
              <template #label>
                <tooltip-label :title="$t('label.ip6dns2')" :tooltip="apiParams.ip6dns2.description"/>
              </template>
              <a-input
                v-model:value="form.ip6dns2"
                :placeholder="apiParams.ip6dns2.description"/>
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item v-if="selectedNetworkOfferingSupportsSourceNat && !isNsxNetwork" name="sourcenatipaddress" ref="sourcenatipaddress">
          <template #label>
            <tooltip-label :title="$t('label.routerip')" :tooltip="apiParams.sourcenatipaddress?.description"/>
          </template>
          <a-input
            v-model:value="form.sourcenatipaddress"
            :placeholder="apiParams.sourcenatipaddress?.description"/>
        </a-form-item>
        <a-form-item name="start" ref="start">
          <template #label>
            <tooltip-label :title="$t('label.start')" :tooltip="apiParams.start.description"/>
          </template>
          <a-switch v-model:checked="form.start" />
        </a-form-item>
      </a-form>
      <div :span="24" class="action-button">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { isAdmin, isAdminOrDomainAdmin } from '@/role'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection.vue'

export default {
  name: 'CreateVpc',
  components: {
    OwnershipSelection,
    ResourceIcon,
    TooltipLabel
  },
  data () {
    return {
      loading: false,
      loadingZone: false,
      selectedZone: {},
      loadingOffering: false,
      setMTU: false,
      zoneid: '',
      zones: [],
      vpcOfferings: [],
      publicMtuMax: 1500,
      minMTU: 68,
      errorPublicMtu: '',
      selectedVpcOffering: {},
      isNsxNetwork: false,
      asNumberLoading: false,
      asNumbersZone: [],
      selectedAsNumber: 0,
      useRouterIpResolver: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createVPC')
  },
  created () {
    this.initForm()
    this.fetchData()
    console.log(this.setMTU)
  },
  computed: {
    selectedVpcOfferingSupportsDns () {
      if (this.selectedVpcOffering) {
        const services = this.selectedVpcOffering?.service || []
        const dnsServices = services.filter(service => service.name === 'Dns')
        return dnsServices && dnsServices.length === 1
      }
      return false
    },
    selectedNetworkOfferingSupportsSourceNat () {
      if (this.selectedVpcOffering) {
        const services = this.selectedVpcOffering?.service || []
        const sourcenatService = services.filter(service => service.name === 'SourceNat')
        return sourcenatService && sourcenatService.length === 1
      }
      return false
    }
  },
  methods: {
    isAdminOrDomainAdmin,
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        start: true
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        zoneid: [{ required: true, message: this.$t('label.required') }],
        vpcofferingid: [{ required: true, message: this.$t('label.required') }]
      })
    },
    isASNumberRequired () {
      return !this.isObjectEmpty(this.selectedVpcOffering) && this.selectedVpcOffering.specifyasnumber && this.selectedVpcOffering?.routingmode.toLowerCase() === 'dynamic'
    },
    isObjectEmpty (obj) {
      return !(obj !== null && obj !== undefined && Object.keys(obj).length > 0 && obj.constructor === Object)
    },
    async fetchData () {
      this.fetchZones()
    },
    isAdmin () {
      return isAdmin()
    },
    fetchPublicMtuForZone () {
      getAPI('listConfigurations', {
        name: 'vr.public.interface.mtu',
        zoneid: this.form.zoneid
      }).then(json => {
        this.publicMtuMax = json?.listconfigurationsresponse?.configuration[0]?.value || 1500
      })
    },
    fetchZones () {
      this.loadingZone = true
      getAPI('listZones', { showicon: true }).then((response) => {
        const listZones = response.listzonesresponse.zone || []
        this.zones = listZones.filter(zone => !zone.securitygroupsenabled)
        this.form.zoneid = ''
        if (this.zones.length > 0) {
          this.form.zoneid = this.zones[0].id
          this.changeZone(this.form.zoneid)
        }
      }).finally(() => {
        this.loadingZone = false
      })
    },
    changeZone (value) {
      this.form.zoneid = value
      if (this.form.zoneid === '') {
        this.form.vpcofferingid = ''
        return
      }
      for (var zone of this.zones) {
        if (zone.id === value) {
          this.setMTU = zone?.allowuserspecifyvrmtu || false
          this.publicMtuMax = zone?.routerpublicinterfacemaxmtu || 1500
          this.isNsxNetwork = zone?.isnsxenabled || false
          this.selectedZone = zone
        }
      }
      this.fetchOfferings()
      if (this.isASNumberRequired()) {
        this.fetchZoneASNumbers()
      }
    },
    fetchZoneASNumbers () {
      const params = {}
      this.asNumberLoading = true
      params.zoneid = this.selectedZone.id
      params.isallocated = false
      getAPI('listASNumbers', params).then(json => {
        this.asNumbersZone = json.listasnumbersresponse.asnumber
        this.asNumberLoading = false
      })
    },
    fetchOfferings () {
      this.loadingOffering = true
      getAPI('listVPCOfferings', { zoneid: this.form.zoneid, state: 'Enabled' }).then((response) => {
        this.vpcOfferings = response.listvpcofferingsresponse.vpcoffering
        this.vpcOfferings = this.vpcOfferings.filter(offering => offering.fornsx === this.selectedZone.isnsxenabled)
        if (!this.selectedZone.routedmodeenabled) {
          this.vpcOfferings = this.vpcOfferings.filter(offering => offering.networkmode !== 'ROUTED')
        }
        this.form.vpcofferingid = this.vpcOfferings[0].id || ''
        this.selectedVpcOffering = this.vpcOfferings[0] || {}
      }).finally(() => {
        this.loadingOffering = false
        if (this.vpcOfferings.length > 0) {
          this.form.vpcofferingid = 0
          this.handleVpcOfferingChange(this.vpcOfferings[0].id)
        }
      })
    },
    fetchOwnerOptions (OwnerOptions) {
      this.owner = {
        projectid: null,
        domainid: this.$store.getters.userInfo.domainid,
        account: this.$store.getters.userInfo.account
      }
      if (OwnerOptions.selectedAccountType === 'Account') {
        if (!OwnerOptions.selectedAccount) {
          return
        }
        this.owner.account = OwnerOptions.selectedAccount
        this.owner.domainid = OwnerOptions.selectedDomain
        this.owner.projectid = null
      } else if (OwnerOptions.selectedAccountType === 'Project') {
        if (!OwnerOptions.selectedProject) {
          return
        }
        this.owner.account = null
        this.owner.domainid = null
        this.owner.projectid = OwnerOptions.selectedProject
      }
    },
    handleVpcOfferingChange (value) {
      this.selectedVpcOffering = {}
      if (!value) {
        return
      }
      for (var offering of this.vpcOfferings) {
        if (offering.id === value) {
          this.selectedVpcOffering = offering
          this.form.vpcofferingid = offering.id
          if (this.isASNumberRequired()) {
            this.fetchZoneASNumbers()
          }
          return
        }
      }
    },
    handleASNumberChange (selectedIndex) {
      this.selectedAsNumber = this.asNumbersZone[selectedIndex].asnumber
      this.form.asnumber = this.selectedAsNumber
    },
    closeAction () {
      this.$emit('close-action')
    },
    updateMtu () {
      if (this.form.publicmtu > this.publicMtuMax) {
        this.errorPublicMtu = `${this.$t('message.error.mtu.public.max.exceed').replace('%x', this.publicMtuMax)}`
        this.form.publicmtu = this.publicMtuMax
      } else if (this.form.publicmtu < this.minMTU) {
        this.errorPublicMtu = `${this.$t('message.error.mtu.below.min').replace('%x', this.minMTU)}`
        this.form.publicmtu = this.minMTU
      } else {
        this.errorPublicMtu = ''
      }
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        var params = {}
        if (this.owner?.account) {
          params.account = this.owner.account
          params.domainid = this.owner.domainid
        } else if (this.owner?.projectid) {
          params.domainid = this.owner.domainid
          params.projectid = this.owner.projectid
        }
        for (const key in values) {
          const input = values[key]
          if (input === '' || input === null || input === undefined) {
            continue
          }
          params[key] = input
        }
        if (this.selectedVpcOffering.networkmode === 'ROUTED') {
          if ((values.cidr === undefined || values.cidr === '') && (values.cidrsize === undefined || values.cidrsize === '')) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.error.cidr.or.cidrsize')
            })
            return
          }
        } else {
          if (values.cidr === undefined || values.cidr === '') {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.error.cidr')
            })
            return
          }
        }
        if ('asnumber' in values && this.isASNumberRequired()) {
          params.asnumber = values.asnumber
        }
        if (this.useRouterIpResolver) {
          params.userouteripresolver = true
        }
        this.loading = true
        const title = this.$t('label.add.vpc')
        const description = this.$t('message.success.add.vpc')
        postAPI('createVPC', params).then(json => {
          const jobId = json.createvpcresponse.jobid
          if (jobId) {
            this.$pollJob({
              jobId,
              title,
              description,
              loadingMessage: `${title} ${this.$t('label.in.progress')}`,
              catchMessage: this.$t('error.fetching.async.job.result')
            })
          }
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    }
  }
}
</script>
<style lang="scss" scoped>
.form-layout {
  width: 80vw;
  @media (min-width: 700px) {
    width: 600px;
  }
}

.form {
  margin: 10px 0;
}
</style>
