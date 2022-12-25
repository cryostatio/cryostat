/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import {
  AutomatedAnalysisFiltersCategories,
  AutomatedAnalysisGlobalFiltersCategories,
} from '@app/Dashboard/AutomatedAnalysis/AutomatedAnalysisFilters';
import { getPersistedState } from '../utils';
import { createAction, createReducer } from '@reduxjs/toolkit';
import { WritableDraft } from 'immer/dist/internal';
import { UpdateFilterOptions } from './Common';

const _version = '1';

// Common action string format: "resource(s)/action"
export enum AutomatedAnalysisFilterAction {
  GLOBAL_FILTER_ADD = 'automated-analysis-global-filter/add',
  FILTER_ADD = 'automated-analysis-filter/add',
  FILTER_DELETE = 'automated-analysis-filter/delete',
  FILTER_DELETE_ALL = 'automated-analysis-filter/delete_all', // Delete all filters in all categories
  CATEGORY_FILTERS_DELETE = 'automated-analysis-filters/delete', // Delete all filters of the same category
  CATEGORY_UPDATE = 'automated-analysis-category/update',
  TARGET_ADD = 'automated-analysis-target/add',
  TARGET_DELETE = 'automated-analysis-target/delete',
}

export const enumValues = new Set(Object.values(AutomatedAnalysisFilterAction));

export const emptyAutomatedAnalysisFilters = {
  Name: [],
  Topic: [],
} as AutomatedAnalysisFiltersCategories;

export const allowedAutomatedAnalysisFilters = Object.keys(emptyAutomatedAnalysisFilters);

export interface AutomatedAnalysisFilterActionPayload {
  target: string;
  category?: string;
  filter?: any;
}

export const automatedAnalysisAddGlobalFilterIntent = createAction(
  AutomatedAnalysisFilterAction.GLOBAL_FILTER_ADD,
  (category: string, filter: any) => ({
    payload: {
      category: category,
      filter: filter,
    },
  })
);

export const automatedAnalysisAddFilterIntent = createAction(
  AutomatedAnalysisFilterAction.FILTER_ADD,
  (target: string, category: string, filter: any) => ({
    payload: {
      target: target,
      category: category,
      filter: filter,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export const automatedAnalysisDeleteFilterIntent = createAction(
  AutomatedAnalysisFilterAction.FILTER_DELETE,
  (target: string, category: string, filter: any) => ({
    payload: {
      target: target,
      category: category,
      filter: filter,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export const automatedAnalysisDeleteCategoryFiltersIntent = createAction(
  AutomatedAnalysisFilterAction.CATEGORY_FILTERS_DELETE,
  (target: string, category: string) => ({
    payload: {
      target: target,
      category: category,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export const automatedAnalysisDeleteAllFiltersIntent = createAction(
  AutomatedAnalysisFilterAction.FILTER_DELETE_ALL,
  (target: string) => ({
    payload: {
      target: target,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export const automatedAnalysisUpdateCategoryIntent = createAction(
  AutomatedAnalysisFilterAction.CATEGORY_UPDATE,
  (target: string, category: string) => ({
    payload: {
      target: target,
      category: category,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export const automatedAnalysisAddTargetIntent = createAction(
  AutomatedAnalysisFilterAction.TARGET_ADD,
  (target: string) => ({
    payload: {
      target: target,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export const automatedAnalysisDeleteTargetIntent = createAction(
  AutomatedAnalysisFilterAction.TARGET_DELETE,
  (target: string) => ({
    payload: {
      target: target,
    } as AutomatedAnalysisFilterActionPayload,
  })
);

export interface AutomatedAnalysisFilterState {
  targetFilters: TargetAutomatedAnalysisFilters[];
  globalFilters: TargetAutomatedAnalysisGlobalFilters;
}
export interface TargetAutomatedAnalysisGlobalFilters {
  filters: AutomatedAnalysisGlobalFiltersCategories;
}
export interface TargetAutomatedAnalysisFilters {
  target: string; // connectURL
  selectedCategory?: string;
  filters: AutomatedAnalysisFiltersCategories;
}

export const createOrUpdateAutomatedAnalysisGlobalFilter = (
  old: AutomatedAnalysisGlobalFiltersCategories,
  { filterValue, filterKey }
): AutomatedAnalysisGlobalFiltersCategories => {
  const newFilters = { ...old };
  newFilters[filterKey] = filterValue;
  return newFilters;
};

export const createOrUpdateAutomatedAnalysisFilter = (
  old: AutomatedAnalysisFiltersCategories,
  { filterValue, filterKey, deleted = false, deleteOptions }: UpdateFilterOptions
): AutomatedAnalysisFiltersCategories => {
  let newFilterValues: any[];

  if (!old[filterKey]) {
    newFilterValues = [filterValue];
  } else {
    const oldFilterValues = old[filterKey] as any[];
    if (deleted) {
      if (deleteOptions && deleteOptions.all) {
        newFilterValues = [];
      } else {
        newFilterValues = oldFilterValues.filter((val) => val !== filterValue);
      }
    } else {
      newFilterValues = Array.from(new Set([...oldFilterValues, filterValue]));
    }
  }

  const newFilters = { ...old };
  newFilters[filterKey] = newFilterValues;
  return newFilters;
};

export const getAutomatedAnalysisGlobalFilter = (
  state: WritableDraft<{ globalFilters: TargetAutomatedAnalysisGlobalFilters }>
) => {
  return state.globalFilters;
};

export const getAutomatedAnalysisFilter = (
  state: WritableDraft<{ targetFilters: TargetAutomatedAnalysisFilters[] }>,
  target: string
): TargetAutomatedAnalysisFilters => {
  const targetFilter = state.targetFilters.filter((targetFilters) => targetFilters.target === target);
  return targetFilter.length > 0 ? targetFilter[0] : createEmptyAutomatedAnalysisFilters(target);
};

export const createEmptyAutomatedAnalysisFilters = (target: string) =>
  ({
    target: target,
    selectedCategory: 'Name',
    filters: emptyAutomatedAnalysisFilters,
  } as TargetAutomatedAnalysisFilters);

export const deleteAllAutomatedAnalysisFilters = (automatedAnalysisFilter: TargetAutomatedAnalysisFilters) => {
  return {
    ...automatedAnalysisFilter,
    selectedCategory: automatedAnalysisFilter.selectedCategory,
    filters: emptyAutomatedAnalysisFilters,
  };
};

const INITIAL_STATE = getPersistedState('AUTOMATED_ANALYSIS_FILTERS', _version, {
  state: {
    targetFilters: [],
    globalFilters: { filters: { Score: 0 } },
  } as AutomatedAnalysisFilterState,
});

export const automatedAnalysisFilterReducer = createReducer(INITIAL_STATE, (builder) => {
  builder
    .addCase(automatedAnalysisAddGlobalFilterIntent, (state, { payload }) => {
      const oldAutomatedAnalysisGlobalFilter = getAutomatedAnalysisGlobalFilter(state.state);
      state.state.globalFilters = {
        filters: createOrUpdateAutomatedAnalysisGlobalFilter(oldAutomatedAnalysisGlobalFilter.filters, {
          filterKey: payload.category,
          filterValue: payload.filter,
        }),
      };
    })
    .addCase(automatedAnalysisAddFilterIntent, (state, { payload }) => {
      const oldAutomatedAnalysisFilter = getAutomatedAnalysisFilter(state.state, payload.target);
      let newAutomatedAnalysisFilter: TargetAutomatedAnalysisFilters = {
        ...oldAutomatedAnalysisFilter,
        selectedCategory: payload.category,
        filters: createOrUpdateAutomatedAnalysisFilter(oldAutomatedAnalysisFilter.filters, {
          filterKey: payload.category!,
          filterValue: payload.filter,
        }),
      };
      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== newAutomatedAnalysisFilter.target
      );
      state.state.targetFilters.push(newAutomatedAnalysisFilter);
    })
    .addCase(automatedAnalysisDeleteFilterIntent, (state, { payload }) => {
      const oldAutomatedAnalysisFilter = getAutomatedAnalysisFilter(state.state, payload.target);

      let newAutomatedAnalysisFilter: TargetAutomatedAnalysisFilters = {
        ...oldAutomatedAnalysisFilter,
        selectedCategory: payload.category,
        filters: createOrUpdateAutomatedAnalysisFilter(oldAutomatedAnalysisFilter.filters, {
          filterKey: payload.category!,
          filterValue: payload.filter,
          deleted: true,
        }),
      };

      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== newAutomatedAnalysisFilter.target
      );
      state.state.targetFilters.push(newAutomatedAnalysisFilter);
    })
    .addCase(automatedAnalysisDeleteCategoryFiltersIntent, (state, { payload }) => {
      const oldAutomatedAnalysisFilter = getAutomatedAnalysisFilter(state.state, payload.target);

      let newAutomatedAnalysisFilter: TargetAutomatedAnalysisFilters = {
        ...oldAutomatedAnalysisFilter,
        selectedCategory: payload.category,
        filters: createOrUpdateAutomatedAnalysisFilter(oldAutomatedAnalysisFilter.filters, {
          filterKey: payload.category!,
          deleted: true,
          deleteOptions: { all: true },
        }),
      };
      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== newAutomatedAnalysisFilter.target
      );
      state.state.targetFilters.push(newAutomatedAnalysisFilter);
    })
    .addCase(automatedAnalysisDeleteAllFiltersIntent, (state, { payload }) => {
      const oldAutomatedAnalysisFilter = getAutomatedAnalysisFilter(state.state, payload.target);
      const newAutomatedAnalysisFilter = deleteAllAutomatedAnalysisFilters(oldAutomatedAnalysisFilter);
      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== newAutomatedAnalysisFilter.target
      );
      state.state.targetFilters.push(newAutomatedAnalysisFilter);
    })
    .addCase(automatedAnalysisUpdateCategoryIntent, (state, { payload }) => {
      const oldAutomatedAnalysisFilter = getAutomatedAnalysisFilter(state.state, payload.target);
      const newAutomatedAnalysisFilter = { ...oldAutomatedAnalysisFilter };

      newAutomatedAnalysisFilter.selectedCategory = payload.category;

      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== newAutomatedAnalysisFilter.target
      );
      state.state.targetFilters.push(newAutomatedAnalysisFilter);
    })
    .addCase(automatedAnalysisAddTargetIntent, (state, { payload }) => {
      const AutomatedAnalysisFilter = getAutomatedAnalysisFilter(state.state, payload.target);
      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== payload.target
      );
      state.state.targetFilters.push(AutomatedAnalysisFilter);
    })
    .addCase(automatedAnalysisDeleteTargetIntent, (state, { payload }) => {
      state.state.targetFilters = state.state.targetFilters.filter(
        (targetFilters) => targetFilters.target !== payload.target
      );
    });
});

export default automatedAnalysisFilterReducer;
