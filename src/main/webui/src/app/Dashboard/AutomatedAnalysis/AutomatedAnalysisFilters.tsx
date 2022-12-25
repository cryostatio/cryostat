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
import { UpdateFilterOptions } from '@app/Shared/Redux/Filters/Common';
import { automatedAnalysisUpdateCategoryIntent, RootState, StateDispatch } from '@app/Shared/Redux/ReduxStore';
import {
  Dropdown,
  DropdownItem,
  DropdownPosition,
  DropdownToggle,
  ToolbarFilter,
  ToolbarGroup,
  ToolbarItem,
  ToolbarToggleGroup,
} from '@patternfly/react-core';
import { FilterIcon } from '@patternfly/react-icons';
import React from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { RuleEvaluation } from '@app/Shared/Services/Report.service';
import { AutomatedAnalysisNameFilter } from './Filters/AutomatedAnalysisNameFilter';
import { AutomatedAnalysisTopicFilter } from './Filters/AutomatedAnalysisTopicFilter';
import { allowedAutomatedAnalysisFilters } from '@app/Shared/Redux/Filters/AutomatedAnalysisFilterSlice';

export interface AutomatedAnalysisFiltersCategories {
  Name: string[];
  Topic: string[];
}

export interface AutomatedAnalysisGlobalFiltersCategories {
  Score: number;
}

export interface AutomatedAnalysisFiltersProps {
  target: string;
  evaluations: [string, RuleEvaluation[]][];
  filters: AutomatedAnalysisFiltersCategories;
  updateFilters: (target: string, updateFilterOptions: UpdateFilterOptions) => void;
}

export const AutomatedAnalysisFilters: React.FunctionComponent<AutomatedAnalysisFiltersProps> = (props) => {
  const dispatch = useDispatch<StateDispatch>();

  const currentCategory = useSelector((state: RootState) => {
    const targetAutomatedAnalysisFilters = state.automatedAnalysisFilters.state.targetFilters.filter(
      (targetFilter) => targetFilter.target === props.target
    );
    if (!targetAutomatedAnalysisFilters.length) return 'Name'; // Target is not yet loaded
    return targetAutomatedAnalysisFilters[0].selectedCategory;
  });

  const [isCategoryDropdownOpen, setIsCategoryDropdownOpen] = React.useState(false);

  const onCategoryToggle = React.useCallback(() => {
    setIsCategoryDropdownOpen((opened) => !opened);
  }, [setIsCategoryDropdownOpen]);

  const onCategorySelect = React.useCallback(
    (category) => {
      setIsCategoryDropdownOpen(false);
      dispatch(automatedAnalysisUpdateCategoryIntent(props.target, category));
    },
    [dispatch, automatedAnalysisUpdateCategoryIntent, setIsCategoryDropdownOpen, props.target]
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

  const onTopicInput = React.useCallback(
    (inputTopic) => {
      props.updateFilters(props.target, { filterKey: currentCategory!, filterValue: inputTopic });
    },
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
        dropdownItems={allowedAutomatedAnalysisFilters.map((cat) => (
          <DropdownItem aria-label={cat} key={cat} onClick={() => onCategorySelect(cat)}>
            {cat}
          </DropdownItem>
        ))}
      />
    );
  }, [allowedAutomatedAnalysisFilters, isCategoryDropdownOpen, currentCategory, onCategoryToggle, onCategorySelect]);

  const filterDropdownItems = React.useMemo(
    () => [
      <AutomatedAnalysisNameFilter
        evaluations={props.evaluations}
        filteredNames={props.filters.Name}
        onSubmit={onNameInput}
      />,
      <AutomatedAnalysisTopicFilter
        evaluations={props.evaluations}
        filteredTopics={props.filters.Topic}
        onSubmit={onTopicInput}
      ></AutomatedAnalysisTopicFilter>,
    ],
    [props.evaluations, props.filters.Name, props.filters.Topic, onNameInput, onTopicInput]
  );

  return (
    <ToolbarToggleGroup toggleIcon={<FilterIcon />} breakpoint="xl">
      <ToolbarGroup variant="filter-group">
        <ToolbarItem>
          {categoryDropdown}
          {Object.keys(props.filters)
            .filter((f) => f !== 'Score')
            .map((filterKey, i) => (
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
        </ToolbarItem>
      </ToolbarGroup>
    </ToolbarToggleGroup>
  );
};

export const filterAutomatedAnalysis = (
  topicEvalTuple: [string, RuleEvaluation[]][],
  filters: AutomatedAnalysisFiltersCategories,
  globalFilters: AutomatedAnalysisGlobalFiltersCategories,
  showNAScores: boolean
) => {
  if (!topicEvalTuple || !topicEvalTuple.length) {
    return topicEvalTuple;
  }

  let filtered = topicEvalTuple;

  if (!!filters.Name.length) {
    filtered = filtered.map(([topic, evaluations]) => {
      return [topic, evaluations.filter((evaluation) => filters.Name.includes(evaluation.name))] as [
        string,
        RuleEvaluation[]
      ];
    });
  }
  if (globalFilters.Score != null) {
    filtered = filtered.map(([topic, evaluations]) => {
      return [
        topic,
        evaluations.filter((evaluation) => {
          if (showNAScores) {
            return globalFilters.Score <= evaluation.score || evaluation.score == -1;
          }
          return globalFilters.Score <= evaluation.score;
        }),
      ] as [string, RuleEvaluation[]];
    });
  }
  if (filters.Topic != null && !!filters.Topic.length) {
    filtered = filtered.map(([topic, evaluations]) => {
      return [topic, evaluations.filter((_) => filters.Topic.includes(topic))] as [string, RuleEvaluation[]];
    });
  }

  return filtered;
};
