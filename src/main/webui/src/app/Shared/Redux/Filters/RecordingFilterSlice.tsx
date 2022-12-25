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

import { RecordingFiltersCategories } from '@app/Recordings/RecordingFilters';
import { getPersistedState } from '../utils';
import { createAction, createReducer } from '@reduxjs/toolkit';
import { WritableDraft } from 'immer/dist/internal';
import { UpdateFilterOptions } from './Common';

const _version = '1';

// Common action string format: "resource(s)/action"
export enum RecordingFilterAction {
  FILTER_ADD = 'recording-filter/add',
  FILTER_DELETE = 'recording-filter/delete',
  FILTER_DELETE_ALL = 'recording-filter/delete-all', // Delete all filters in all categories
  CATEGORY_FILTERS_DELETE = 'recording-filter/delete-category', // Delete all filters of the same category
  CATEGORY_UPDATE = 'recording-filter-category/update',
  TARGET_ADD = 'recording-filter-target/add',
  TARGET_DELETE = 'recording-filter-target/delete',
}

export const enumValues = new Set(Object.values(RecordingFilterAction));

export const emptyActiveRecordingFilters = {
  Name: [],
  Label: [],
  State: [],
  StartedBeforeDate: [],
  StartedAfterDate: [],
  DurationSeconds: [],
} as RecordingFiltersCategories;

export const allowedActiveRecordingFilters = Object.keys(emptyActiveRecordingFilters);

export const emptyArchivedRecordingFilters = {
  Name: [],
  Label: [],
} as RecordingFiltersCategories;

export const allowedArchivedRecordingFilters = Object.keys(emptyArchivedRecordingFilters);

export interface RecordingFilterActionPayload {
  target: string;
  category?: string;
  filter?: any;
  isArchived?: boolean;
}

export const recordingAddFilterIntent = createAction(
  RecordingFilterAction.FILTER_ADD,
  (target: string, category: string, filter: any, isArchived: boolean) => ({
    payload: {
      target: target,
      category: category,
      filter: filter,
      isArchived: isArchived,
    } as RecordingFilterActionPayload,
  })
);

export const recordingDeleteFilterIntent = createAction(
  RecordingFilterAction.FILTER_DELETE,
  (target: string, category: string, filter: any, isArchived: boolean) => ({
    payload: {
      target: target,
      category: category,
      filter: filter,
      isArchived: isArchived,
    } as RecordingFilterActionPayload,
  })
);

export const recordingDeleteCategoryFiltersIntent = createAction(
  RecordingFilterAction.CATEGORY_FILTERS_DELETE,
  (target: string, category: string, isArchived: boolean) => ({
    payload: {
      target: target,
      category: category,
      isArchived: isArchived,
    } as RecordingFilterActionPayload,
  })
);

export const recordingDeleteAllFiltersIntent = createAction(
  RecordingFilterAction.FILTER_DELETE_ALL,
  (target: string, isArchived: boolean) => ({
    payload: {
      target: target,
      isArchived: isArchived,
    } as RecordingFilterActionPayload,
  })
);

export const recordingUpdateCategoryIntent = createAction(
  RecordingFilterAction.CATEGORY_UPDATE,
  (target: string, category: string, isArchived: boolean) => ({
    payload: {
      target: target,
      category: category,
      isArchived: isArchived,
    } as RecordingFilterActionPayload,
  })
);

export const recordingAddTargetIntent = createAction(RecordingFilterAction.TARGET_ADD, (target: string) => ({
  payload: {
    target: target,
  } as RecordingFilterActionPayload,
}));

export const recordingDeleteTargetIntent = createAction(RecordingFilterAction.TARGET_DELETE, (target: string) => ({
  payload: {
    target: target,
  } as RecordingFilterActionPayload,
}));

export interface TargetRecordingFilters {
  target: string; // connectURL
  active: {
    // active recordings
    selectedCategory?: string;
    filters: RecordingFiltersCategories;
  };
  archived: {
    // archived recordings
    selectedCategory?: string;
    filters: RecordingFiltersCategories;
  };
}

export const createOrUpdateRecordingFilter = (
  old: RecordingFiltersCategories,
  { filterValue, filterKey, deleted = false, deleteOptions }: UpdateFilterOptions
): RecordingFiltersCategories => {
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

export const getTargetRecordingFilter = (
  state: WritableDraft<{ list: TargetRecordingFilters[] }>,
  target: string
): TargetRecordingFilters => {
  const targetFilter = state.list.filter((targetFilters) => targetFilters.target === target);
  return targetFilter.length > 0 ? targetFilter[0] : createEmptyTargetRecordingFilters(target);
};

export const createEmptyTargetRecordingFilters = (target: string) =>
  ({
    target: target,
    active: {
      selectedCategory: 'Name',
      filters: emptyActiveRecordingFilters,
    },
    archived: {
      selectedCategory: 'Name',
      filters: emptyArchivedRecordingFilters,
    },
  } as TargetRecordingFilters);

export const deleteAllTargetRecordingFilters = (targetRecordingFilter: TargetRecordingFilters, isArchived: boolean) => {
  if (isArchived) {
    return {
      ...targetRecordingFilter,
      archived: {
        selectedCategory: targetRecordingFilter.archived.selectedCategory,
        filters: emptyArchivedRecordingFilters,
      },
    };
  }
  return {
    ...targetRecordingFilter,
    active: {
      selectedCategory: targetRecordingFilter.active.selectedCategory,
      filters: emptyActiveRecordingFilters,
    },
  };
};

const INITIAL_STATE = getPersistedState('TARGET_RECORDING_FILTERS', _version, {
  list: [] as TargetRecordingFilters[],
});

export const recordingFilterReducer = createReducer(INITIAL_STATE, (builder) => {
  builder
    .addCase(recordingAddFilterIntent, (state, { payload }) => {
      const oldTargetRecordingFilter = getTargetRecordingFilter(state, payload.target);

      let newTargetRecordingFilter: TargetRecordingFilters;
      if (payload.isArchived) {
        newTargetRecordingFilter = {
          ...oldTargetRecordingFilter,
          archived: {
            selectedCategory: payload.category,
            filters: createOrUpdateRecordingFilter(oldTargetRecordingFilter.archived.filters, {
              filterKey: payload.category!,
              filterValue: payload.filter,
            }),
          },
        };
      } else {
        newTargetRecordingFilter = {
          ...oldTargetRecordingFilter,
          active: {
            selectedCategory: payload.category,
            filters: createOrUpdateRecordingFilter(oldTargetRecordingFilter.active.filters, {
              filterKey: payload.category!,
              filterValue: payload.filter,
            }),
          },
        };
      }

      state.list = state.list.filter((targetFilters) => targetFilters.target !== newTargetRecordingFilter.target);
      state.list.push(newTargetRecordingFilter);
    })
    .addCase(recordingDeleteFilterIntent, (state, { payload }) => {
      const oldTargetRecordingFilter = getTargetRecordingFilter(state, payload.target);

      let newTargetRecordingFilter: TargetRecordingFilters;
      if (payload.isArchived) {
        newTargetRecordingFilter = {
          ...oldTargetRecordingFilter,
          archived: {
            selectedCategory: payload.category,
            filters: createOrUpdateRecordingFilter(oldTargetRecordingFilter.archived.filters, {
              filterKey: payload.category!,
              filterValue: payload.filter,
              deleted: true,
            }),
          },
        };
      } else {
        newTargetRecordingFilter = {
          ...oldTargetRecordingFilter,
          active: {
            selectedCategory: payload.category,
            filters: createOrUpdateRecordingFilter(oldTargetRecordingFilter.active.filters, {
              filterKey: payload.category!,
              filterValue: payload.filter,
              deleted: true,
            }),
          },
        };
      }

      state.list = state.list.filter((targetFilters) => targetFilters.target !== newTargetRecordingFilter.target);
      state.list.push(newTargetRecordingFilter);
    })
    .addCase(recordingDeleteCategoryFiltersIntent, (state, { payload }) => {
      const oldTargetRecordingFilter = getTargetRecordingFilter(state, payload.target);

      let newTargetRecordingFilter: TargetRecordingFilters;
      if (payload.isArchived) {
        newTargetRecordingFilter = {
          ...oldTargetRecordingFilter,
          archived: {
            selectedCategory: payload.category,
            filters: createOrUpdateRecordingFilter(oldTargetRecordingFilter.archived.filters, {
              filterKey: payload.category!,
              deleted: true,
              deleteOptions: { all: true },
            }),
          },
        };
      } else {
        newTargetRecordingFilter = {
          ...oldTargetRecordingFilter,
          active: {
            selectedCategory: payload.category,
            filters: createOrUpdateRecordingFilter(oldTargetRecordingFilter.active.filters, {
              filterKey: payload.category!,
              deleted: true,
              deleteOptions: { all: true },
            }),
          },
        };
      }

      state.list = state.list.filter((targetFilters) => targetFilters.target !== newTargetRecordingFilter.target);
      state.list.push(newTargetRecordingFilter);
    })
    .addCase(recordingDeleteAllFiltersIntent, (state, { payload }) => {
      const oldTargetRecordingFilter = getTargetRecordingFilter(state, payload.target);
      const newTargetRecordingFilter = deleteAllTargetRecordingFilters(oldTargetRecordingFilter, payload.isArchived!);
      state.list = state.list.filter((targetFilters) => targetFilters.target !== newTargetRecordingFilter.target);
      state.list.push(newTargetRecordingFilter);
    })
    .addCase(recordingUpdateCategoryIntent, (state, { payload }) => {
      const oldTargetRecordingFilter = getTargetRecordingFilter(state, payload.target);
      const newTargetRecordingFilter = { ...oldTargetRecordingFilter };
      if (payload.isArchived) {
        newTargetRecordingFilter.archived.selectedCategory = payload.category;
      } else {
        newTargetRecordingFilter.active.selectedCategory = payload.category;
      }
      state.list = state.list.filter((targetFilters) => targetFilters.target !== newTargetRecordingFilter.target);
      state.list.push(newTargetRecordingFilter);
    })
    .addCase(recordingAddTargetIntent, (state, { payload }) => {
      const targetRecordingFilter = getTargetRecordingFilter(state, payload.target);
      state.list = state.list.filter((targetFilters) => targetFilters.target !== payload.target);
      state.list.push(targetRecordingFilter);
    })
    .addCase(recordingDeleteTargetIntent, (state, { payload }) => {
      state.list = state.list.filter((targetFilters) => targetFilters.target !== payload.target);
    });
});

export default recordingFilterReducer;
