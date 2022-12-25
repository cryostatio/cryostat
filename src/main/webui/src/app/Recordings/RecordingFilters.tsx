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
  Dropdown,
  DropdownItem,
  DropdownPosition,
  DropdownToggle,
  InputGroup,
  ToolbarFilter,
  ToolbarGroup,
  ToolbarItem,
  ToolbarToggleGroup,
} from '@patternfly/react-core';
import { FilterIcon } from '@patternfly/react-icons';
import React from 'react';
import { DateTimePicker } from './Filters/DateTimePicker';
import { DurationFilter } from './Filters/DurationFilter';
import { LabelFilter } from './Filters/LabelFilter';
import { NameFilter } from './Filters/NameFilter';
import { RecordingStateFilter } from './Filters/RecordingStateFilter';
import { Recording, RecordingState } from '@app/Shared/Services/Api.service';
import { useDispatch, useSelector } from 'react-redux';
import { UpdateFilterOptions } from '@app/Shared/Redux/Filters/Common';
import { recordingUpdateCategoryIntent, StateDispatch, RootState } from '@app/Shared/Redux/ReduxStore';
import {
  allowedActiveRecordingFilters,
  allowedArchivedRecordingFilters,
} from '@app/Shared/Redux/Filters/RecordingFilterSlice';

export interface RecordingFiltersCategories {
  Name: string[];
  Label: string[];
  State?: RecordingState[];
  StartedBeforeDate?: string[];
  StartedAfterDate?: string[];
  DurationSeconds?: string[];
}

export interface RecordingFiltersProps {
  target: string;
  isArchived: boolean;
  recordings: Recording[];
  filters: RecordingFiltersCategories;
  updateFilters: (target: string, updateFilterOptions: UpdateFilterOptions) => void;
}

export const RecordingFilters: React.FunctionComponent<RecordingFiltersProps> = (props) => {
  const dispatch = useDispatch<StateDispatch>();

  const currentCategory = useSelector((state: RootState) => {
    const targetRecordingFilters = state.recordingFilters.list.filter(
      (targetFilter) => targetFilter.target === props.target
    );
    if (!targetRecordingFilters.length) return 'Name'; // Target is not yet loaded
    return (props.isArchived ? targetRecordingFilters[0].archived : targetRecordingFilters[0].active).selectedCategory;
  });

  const [isCategoryDropdownOpen, setIsCategoryDropdownOpen] = React.useState(false);

  const onCategoryToggle = React.useCallback(() => {
    setIsCategoryDropdownOpen((opened) => !opened);
  }, [setIsCategoryDropdownOpen]);

  const onCategorySelect = React.useCallback(
    (category) => {
      setIsCategoryDropdownOpen(false);
      dispatch(recordingUpdateCategoryIntent(props.target, category, props.isArchived));
    },
    [dispatch, recordingUpdateCategoryIntent, setIsCategoryDropdownOpen, props.target, props.isArchived]
  );

  const onDelete = React.useCallback(
    (category, value) => props.updateFilters(props.target, { filterKey: category, filterValue: value, deleted: true }),
    [props.updateFilters, props.target]
  );

  const onDeleteGroup = React.useCallback(
    (category) =>
      props.updateFilters(props.target, { filterKey: category, deleted: true, deleteOptions: { all: true } }),
    [props.updateFilters, props.target]
  );

  const onNameInput = React.useCallback(
    (inputName) => props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: inputName }),
    [props.updateFilters, currentCategory, props.target]
  );

  const onLabelInput = React.useCallback(
    (inputLabel) => props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: inputLabel }),
    [props.updateFilters, currentCategory, props.target]
  );

  const onStartedBeforeInput = React.useCallback(
    (searchDate) => props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: searchDate }),
    [props.updateFilters, currentCategory, props.target]
  );

  const onStartedAfterInput = React.useCallback(
    (searchDate) => props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: searchDate }),
    [props.updateFilters, currentCategory, props.target]
  );

  const onDurationInput = React.useCallback(
    (duration) =>
      props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: `${duration.toString()} s` }),
    [props.updateFilters, currentCategory, props.target]
  );

  const onRecordingStateSelectToggle = React.useCallback(
    (searchState) => {
      const deleted = props.filters.State && props.filters.State.includes(searchState);
      props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: searchState, deleted: deleted });
    },
    [props.updateFilters, currentCategory, props.target]
  );

  const onContinuousDurationSelect = React.useCallback(
    (cont) =>
      props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: 'continuous', deleted: !cont }),
    [props.updateFilters, currentCategory, props.target]
  );

  const categoryDropdown = React.useMemo(() => {
    return (
      <Dropdown
        aria-label={'Category Dropdown'}
        position={DropdownPosition.left}
        toggle={
          <DropdownToggle aria-label={currentCategory} onToggle={onCategoryToggle}>
            <FilterIcon /> {currentCategory}
          </DropdownToggle>
        }
        isOpen={isCategoryDropdownOpen}
        dropdownItems={(!props.isArchived ? allowedActiveRecordingFilters : allowedArchivedRecordingFilters).map(
          (cat) => (
            <DropdownItem aria-label={cat} key={cat} onClick={() => onCategorySelect(cat)}>
              {cat}
            </DropdownItem>
          )
        )}
      />
    );
  }, [
    props.isArchived,
    allowedActiveRecordingFilters,
    allowedArchivedRecordingFilters,
    isCategoryDropdownOpen,
    currentCategory,
    onCategoryToggle,
    onCategorySelect,
  ]);

  const filterDropdownItems = React.useMemo(
    () => [
      <NameFilter recordings={props.recordings} onSubmit={onNameInput} filteredNames={props.filters.Name} />,
      <LabelFilter recordings={props.recordings} onSubmit={onLabelInput} filteredLabels={props.filters.Label} />,
      ...(!props.isArchived
        ? [
            <RecordingStateFilter filteredStates={props.filters.State} onSelectToggle={onRecordingStateSelectToggle} />,
            <DateTimePicker onSubmit={onStartedBeforeInput} />,
            <DateTimePicker onSubmit={onStartedAfterInput} />,
            <DurationFilter
              durations={props.filters.DurationSeconds}
              onContinuousDurationSelect={onContinuousDurationSelect}
              onDurationInput={onDurationInput}
            ></DurationFilter>,
          ]
        : []),
    ],
    [
      props.isArchived,
      props.recordings,
      props.filters.Name,
      props.filters.Label,
      props.filters.State,
      props.filters.DurationSeconds,
      onNameInput,
      onLabelInput,
      onRecordingStateSelectToggle,
      onStartedAfterInput,
      onStartedBeforeInput,
      onContinuousDurationSelect,
      onDurationInput,
    ]
  );

  return (
    <ToolbarToggleGroup toggleIcon={<FilterIcon />} breakpoint="xl">
      <ToolbarGroup variant="filter-group">
        <ToolbarItem>
          <InputGroup>
            {categoryDropdown}
            {Object.keys(props.filters).map((filterKey, i) => (
              <ToolbarFilter
                key={filterKey}
                chips={props.filters[filterKey]}
                deleteChip={onDelete}
                deleteChipGroup={onDeleteGroup}
                categoryName={filterKey}
                showToolbarItem={filterKey === currentCategory}
              >
                {filterDropdownItems[i]}
              </ToolbarFilter>
            ))}
          </InputGroup>
        </ToolbarItem>
      </ToolbarGroup>
    </ToolbarToggleGroup>
  );
};

export const filterRecordings = (recordings: any[], filters: RecordingFiltersCategories) => {
  if (!recordings || !recordings.length) {
    return recordings;
  }

  let filtered = recordings;

  if (!!filters.Name.length) {
    filtered = filtered.filter((r) => filters.Name.includes(r.name));
  }
  if (!!filters.State && !!filters.State.length) {
    filtered = filtered.filter((r) => !!filters.State && filters.State.includes(r.state));
  }
  if (!!filters.DurationSeconds && !!filters.DurationSeconds.length) {
    filtered = filtered.filter((r) => {
      if (!filters.DurationSeconds) return true;
      return (
        filters.DurationSeconds.includes(`${r.duration / 1000} s`) ||
        (filters.DurationSeconds.includes('continuous') && r.continuous)
      );
    });
  }
  if (!!filters.StartedBeforeDate && !!filters.StartedBeforeDate.length) {
    filtered = filtered.filter((rec) => {
      if (!filters.StartedBeforeDate) return true;

      return filters.StartedBeforeDate.filter((startedBefore) => {
        const beforeDate = new Date(startedBefore);
        return rec.startTime < beforeDate.getTime();
      }).length;
    });
  }
  if (!!filters.StartedAfterDate && !!filters.StartedAfterDate.length) {
    filtered = filtered.filter((rec) => {
      if (!filters.StartedAfterDate) return true;
      return filters.StartedAfterDate.filter((startedAfter) => {
        const afterDate = new Date(startedAfter);

        return rec.startTime > afterDate.getTime();
      }).length;
    });
  }
  if (!!filters.Label.length) {
    filtered = filtered.filter(
      (r) => Object.entries(r.metadata.labels).filter(([k, v]) => filters.Label.includes(`${k}:${v}`)).length
    );
  }

  return filtered;
};
