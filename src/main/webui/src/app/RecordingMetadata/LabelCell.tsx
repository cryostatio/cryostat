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

import { getLabelDisplay } from '@app/Recordings/Filters/LabelFilter';
import { UpdateFilterOptions } from '@app/Shared/Redux/Filters/Common';
import { Label, Text } from '@patternfly/react-core';
import React from 'react';
import { ClickableLabel } from './ClickableLabel';
import { RecordingLabel } from './RecordingLabel';

export interface LabelCellProps {
  target: string;
  labels: RecordingLabel[];
  clickableOptions?: {
    // If undefined, labels are not clickable (i.e. display only) and only displayed in grey.
    labelFilters: string[];
    updateFilters: (target: string, updateFilterOptions: UpdateFilterOptions) => void;
  };
}

export const LabelCell: React.FunctionComponent<LabelCellProps> = (props) => {
  const isLabelSelected = React.useCallback(
    (label: RecordingLabel) => {
      if (props.clickableOptions) {
        const labelFilterSet = new Set(props.clickableOptions.labelFilters);
        return labelFilterSet.has(getLabelDisplay(label));
      }
      return false;
    },
    [getLabelDisplay, props.clickableOptions]
  );

  const getLabelColor = React.useCallback(
    (label: RecordingLabel) => (isLabelSelected(label) ? 'blue' : 'grey'),
    [isLabelSelected]
  );
  const onLabelSelectToggle = React.useCallback(
    (clickedLabel: RecordingLabel) => {
      if (props.clickableOptions) {
        props.clickableOptions.updateFilters(props.target, {
          filterKey: 'Label',
          filterValue: getLabelDisplay(clickedLabel),
          deleted: isLabelSelected(clickedLabel),
        });
      }
    },
    [props.clickableOptions, props.target, getLabelDisplay]
  );

  return (
    <>
      {!!props.labels && props.labels.length ? (
        props.labels.map((label) =>
          props.clickableOptions ? (
            <ClickableLabel
              key={label.key}
              label={label}
              isSelected={isLabelSelected(label)}
              onLabelClick={onLabelSelectToggle}
            />
          ) : (
            <Label aria-label={`${label.key}: ${label.value}`} key={label.key} color={getLabelColor(label)}>
              {`${label.key}: ${label.value}`}
            </Label>
          )
        )
      ) : (
        <Text>-</Text>
      )}
    </>
  );
};
