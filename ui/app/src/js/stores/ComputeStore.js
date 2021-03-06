/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

import * as actions from 'actions/Actions';
import services from 'core/services';
import utils from 'core/utils';
import CrudStoreMixin from 'stores/mixins/CrudStoreMixin';
import ContextPanelStoreMixin from 'stores/mixins/ContextPanelStoreMixin';

const OPERATION = {
  LIST: 'LIST',
  DETAILS: 'DETAILS'
};

let toViewModel = function(dto) {
  var customProperties = [];
  let hasCustomProperties = dto.customProperties && dto.customProperties !== null;
  if (hasCustomProperties) {
    for (var key in dto.customProperties) {
      if (dto.customProperties.hasOwnProperty(key)) {
        customProperties.push({
          name: key,
          value: dto.customProperties[key]
        });
      }
    }
  }

  return {
    documentSelfLink: dto.documentSelfLink,
    id: dto.id,
    name: dto.name,
    address: dto.address,
    powerState: dto.powerState,
    descriptionLink: dto.descriptionLink,
    resourcePoolLink: dto.resourcePoolLink,
    resourcePoolDocumentId: dto.resourcePoolLink && utils.getDocumentId(dto.resourcePoolLink),
    connectionType: hasCustomProperties ? dto.customProperties.__adapterDockerType : null,
    customProperties: customProperties,
    computeType: dto.customProperties.computeType
  };
};

let ComputeStore = Reflux.createStore({
  mixins: [ContextPanelStoreMixin, CrudStoreMixin],

  init: function() {
  },

  listenables: [actions.ComputeActions],

  onOpenCompute: function(queryOptions, forceReload) {
    var items = utils.getIn(this.data, ['listView', 'items']);
    if (!forceReload && items) {
      return;
    }

    this.setInData(['selectedItem'], null);
    this.setInData(['selectedItemDetails'], null);
    this.setInData(['listView', 'queryOptions'], queryOptions);

    var operation = this.requestCancellableOperation(OPERATION.LIST, queryOptions);

    if (operation) {
      this.cancelOperations(OPERATION.DETAILS);
      this.setInData(['listView', 'itemsLoading'], true);

      operation.forPromise(services.loadCompute(queryOptions)).then((result) => {
        // Transforming to the model of the view
        var documents = result.documents;
        var nextPageLink = result.nextPageLink;

        // TODO: temporary client side filter
        var compute = [];
        for (var key in documents) {
          if (documents.hasOwnProperty(key)) {
            var document = documents[key];
            compute.push(toViewModel(document));
          }
        }

        this.getResourcePools(compute).then((result) => {
          compute.forEach((compute) => {
            if (result[compute.resourcePoolLink]) {
              compute.resourcePoolName =
                  result[compute.resourcePoolLink].resourcePoolState.name;
            }
          });
          return this.getDescriptions(compute);
        }).then((result) => {

          compute.forEach((compute) => {
            if (result[compute.descriptionLink]) {
              compute.cpuCount = result[compute.descriptionLink].cpuCount;
              compute.cpuMhzPerCore = result[compute.descriptionLink].cpuMhzPerCore;
              compute.memory =
                  Math.floor(result[compute.descriptionLink].totalMemoryBytes / 1048576);
            }
          });

          this.setInData(['listView', 'items'], compute);
          this.setInData(['listView', 'itemsLoading'], false);
          this.setInData(['listView', 'itemsCount'], result.itemsCount);
          this.setInData(['listView', 'nextPageLink'], nextPageLink);
          this.emitChange();
        });
      });
    }

    this.emitChange();
  },

  onOpenComputeDetails: function(computeId) {
    // If switching between views, there will be a short period that we show old data,
    // until the new one is loaded.
    var currentItemDetailsCursor = this.selectFromData(['selectedItemDetails']);
    currentItemDetailsCursor.merge({
      documentSelfLink: '/resources/compute/' + computeId,
      logsLoading: true
    });

    var currentItemCursor = this.selectFromData(['selectedItem']);
    currentItemCursor.merge({
      documentSelfLink: '/resources/compute/' + computeId
    });
    this.emitChange();

    this.emitChange();

    this.cancelOperations(OPERATION.DETAILS);
    var operation = this.requestCancellableOperation(OPERATION.DETAILS);
    operation.forPromise(services.loadHost(computeId))
      .then((compute) => {
        compute = toViewModel(compute);

        currentItemCursor.merge(compute);
        currentItemDetailsCursor.setIn(['instance'], compute);

        this.emitChange();
      });
  },

  getResourcePools: function(compute) {
    let resourcePools = utils.getIn(this.data, ['listView', 'resourcePools']) || {};
    let resourcePoolLinks = compute.filter((compute) =>
        compute.resourcePoolLink).map((compute) => compute.resourcePoolLink);
    let links = [...new Set(resourcePoolLinks)].filter((link) =>
        !resourcePools.hasOwnProperty(link));
    if (links.length === 0) {
      return Promise.resolve(resourcePools);
    }
    return services.loadResourcePools(links).then((newResourcePools) => {
      this.setInData(['listView', 'resourcePools'], $.extend({}, resourcePools, newResourcePools));
      return utils.getIn(this.data, ['listView', 'resourcePools']);
    });
  },

  getDescriptions: function(compute) {
    let descriptions = utils.getIn(this.data, ['listView', 'descriptions']) || {};
    let descriptionLinks = compute.filter((compute) =>
        compute.descriptionLink).map((compute) => compute.descriptionLink);
    let links = [...new Set(descriptionLinks)].filter((link) =>
        !descriptions.hasOwnProperty(link));
    if (links.length === 0) {
      return Promise.resolve(descriptions);
    }
    return services.loadHostDescriptions(links).then((newDescriptions) => {
      this.setInData(['listView', 'descriptions'], $.extend({}, descriptions, newDescriptions));
      return utils.getIn(this.data, ['listView', 'descriptions']);
    });
  }
});

export default ComputeStore;
