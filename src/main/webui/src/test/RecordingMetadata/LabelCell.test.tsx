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
import * as React from 'react';
import renderer, { act } from 'react-test-renderer';
import { cleanup, screen } from '@testing-library/react';

import '@testing-library/jest-dom';
import { LabelCell } from '@app/RecordingMetadata/LabelCell';
import { RecordingLabel } from '@app/RecordingMetadata/RecordingLabel';
import { Target } from '@app/Shared/Services/Target.service';
import userEvent from '@testing-library/user-event';
import { UpdateFilterOptions } from '@app/Shared/Redux/Filters/Common';
import { renderDefault } from '../Common';

const mockFooTarget: Target = {
  connectUrl: 'service:jmx:rmi://someFooUrl',
  alias: 'fooTarget',
  annotations: {
    cryostat: new Map(),
    platform: new Map(),
  },
};

const mockLabel = { key: 'someLabel', value: 'someValue' } as RecordingLabel;
const mockAnotherLabel = { key: 'anotherLabel', value: 'anotherValue' } as RecordingLabel;
const mockLabelList = [mockLabel, mockAnotherLabel];

// For display
const mockLabelStringDisplayList = [
  `${mockLabel.key}: ${mockLabel.value}`,
  `${mockAnotherLabel.key}: ${mockAnotherLabel.value}`,
];
// For filters and labeling elements
const mockLabelStringList = mockLabelStringDisplayList.map((s: string) => s.replace(' ', ''));

describe('<LabelCell />', () => {
  let onUpdateLabels: (target: string, updateFilterOptions: UpdateFilterOptions) => void;
  beforeEach(() => {
    onUpdateLabels = jest.fn((target: string, options: UpdateFilterOptions) => {});
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <LabelCell
          target={mockFooTarget.connectUrl}
          labels={mockLabelList}
          clickableOptions={{ labelFilters: [], updateFilters: onUpdateLabels }}
        />
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('should display read-only labels', async () => {
    renderDefault(<LabelCell target={mockFooTarget.connectUrl} labels={mockLabelList} />);

    for (const labelAsString of mockLabelStringDisplayList) {
      const displayedLabel = screen.getByLabelText(labelAsString);

      expect(displayedLabel).toBeInTheDocument();
      expect(displayedLabel).toBeVisible();

      expect(displayedLabel.onclick).toBeNull();

      await userEvent.click(displayedLabel);
      expect(onUpdateLabels).not.toHaveBeenCalled();
    }
  });

  it('should display clickable labels', async () => {
    renderDefault(
      <LabelCell
        target={mockFooTarget.connectUrl}
        labels={mockLabelList}
        clickableOptions={{ labelFilters: [], updateFilters: onUpdateLabels }}
      />
    );

    let count = 0;
    let index = 0;
    for (const labelAsString of mockLabelStringDisplayList) {
      const displayedLabel = screen.getByLabelText(labelAsString);

      expect(displayedLabel).toBeInTheDocument();
      expect(displayedLabel).toBeVisible();

      await userEvent.click(displayedLabel);

      expect(onUpdateLabels).toHaveBeenCalledTimes(++count);
      expect(onUpdateLabels).toHaveBeenCalledWith(mockFooTarget.connectUrl, {
        filterKey: 'Label',
        filterValue: mockLabelStringList[index],
        deleted: false,
      });
      index++;
    }
  });

  it('should display placeholder when there is no label', async () => {
    renderDefault(<LabelCell target={mockFooTarget.connectUrl} labels={[]} />);

    const placeHolder = screen.getByText('-');
    expect(placeHolder).toBeInTheDocument();
    expect(placeHolder).toBeVisible();
    expect(placeHolder.onclick).toBeNull();
  });
});
