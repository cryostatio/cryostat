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

import { LabelFilter } from '@app/Recordings/Filters/LabelFilter';
import { ActiveRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { cleanup, screen, within } from '@testing-library/react';
import React from 'react';
import renderer, { act } from 'react-test-renderer';
import { renderDefault } from '../../Common';

const mockRecordingLabels = {
  someLabel: 'someValue',
};
const mockAnotherRecordingLabels = {
  anotherLabel: 'anotherValue',
};
const mockRecordingLabelList = ['someLabel:someValue', 'anotherLabel:anotherValue'];

const mockRecording: ActiveRecording = {
  name: 'someRecording',
  downloadUrl: 'http://downloadUrl',
  reportUrl: 'http://reportUrl',
  metadata: { labels: mockRecordingLabels },
  startTime: 1234567890,
  id: 0,
  state: RecordingState.RUNNING,
  duration: 0,
  continuous: false,
  toDisk: false,
  maxSize: 0,
  maxAge: 0,
};
const mockAnotherRecording = {
  ...mockRecording,
  name: 'anotherRecording',
  metadata: { labels: mockAnotherRecordingLabels },
} as ActiveRecording;
const mockRecordingWithoutLabel = {
  ...mockRecording,
  name: 'noLabelRecording',
  metadata: { labels: {} },
} as ActiveRecording;
const mockRecordingList = [mockRecording, mockAnotherRecording, mockRecordingWithoutLabel];

const onLabelInput = jest.fn((label) => {
  /**Do nothing. Used for checking renders */
});

describe('<LabelFilter />', () => {
  let emptyFilteredLabels: string[];
  let filteredLabels: string[];

  beforeEach(() => {
    emptyFilteredLabels = [];
    filteredLabels = [`someLabel:someValue`];
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <LabelFilter recordings={mockRecordingList} filteredLabels={emptyFilteredLabels} onSubmit={onLabelInput} />
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('display label selections when text input is clicked', async () => {
    const { user } = renderDefault(
      <LabelFilter recordings={mockRecordingList} onSubmit={onLabelInput} filteredLabels={emptyFilteredLabels} />
    );
    const labelInput = screen.getByLabelText('Filter by label...');
    expect(labelInput).toBeInTheDocument();
    expect(labelInput).toBeVisible();

    await user.click(labelInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by label' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    mockRecordingLabelList.forEach((label) => {
      const option = within(selectMenu).getByText(label);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });
  });

  it('display label selections when dropdown arrow is clicked', async () => {
    const { user } = renderDefault(
      <LabelFilter recordings={mockRecordingList} onSubmit={onLabelInput} filteredLabels={emptyFilteredLabels} />
    );

    const dropDownArrow = screen.getByRole('button', { name: 'Options menu' });
    expect(dropDownArrow).toBeInTheDocument();
    expect(dropDownArrow).toBeVisible();

    await user.click(dropDownArrow);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by label' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    mockRecordingLabelList.forEach((label) => {
      const option = within(selectMenu).getByText(label);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });
  });

  it('should close selection menu when toggled with dropdown arrow', async () => {
    const { user } = renderDefault(
      <LabelFilter recordings={mockRecordingList} onSubmit={onLabelInput} filteredLabels={emptyFilteredLabels} />
    );

    const dropDownArrow = screen.getByRole('button', { name: 'Options menu' });
    expect(dropDownArrow).toBeInTheDocument();
    expect(dropDownArrow).toBeVisible();

    await user.click(dropDownArrow);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by label' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    mockRecordingLabelList.forEach((label) => {
      const option = within(selectMenu).getByText(label);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.click(dropDownArrow);
    expect(selectMenu).not.toBeInTheDocument();
    expect(selectMenu).not.toBeVisible();
  });

  it('should close selection menu when toggled with text input', async () => {
    const { user } = renderDefault(
      <LabelFilter recordings={mockRecordingList} onSubmit={onLabelInput} filteredLabels={emptyFilteredLabels} />
    );
    const labelInput = screen.getByLabelText('Filter by label...');
    expect(labelInput).toBeInTheDocument();
    expect(labelInput).toBeVisible();

    await user.click(labelInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by label' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    mockRecordingLabelList.forEach((label) => {
      const option = within(selectMenu).getByText(label);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.click(labelInput);
    expect(selectMenu).not.toBeInTheDocument();
    expect(selectMenu).not.toBeVisible();
  });

  it('should not display selected labels', async () => {
    const { user } = renderDefault(
      <LabelFilter recordings={mockRecordingList} onSubmit={onLabelInput} filteredLabels={filteredLabels} />
    );
    const labelInput = screen.getByLabelText('Filter by label...');
    expect(labelInput).toBeInTheDocument();
    expect(labelInput).toBeVisible();

    await user.click(labelInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by label' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    const notToShowLabel = within(selectMenu).queryByText('someLabel:someValue');
    expect(notToShowLabel).not.toBeInTheDocument();
  });

  it('should select a name when a name option is clicked', async () => {
    const submitLabelInput = jest.fn((labelInput) => emptyFilteredLabels.push(labelInput));

    const { user } = renderDefault(
      <LabelFilter recordings={mockRecordingList} onSubmit={submitLabelInput} filteredLabels={emptyFilteredLabels} />
    );
    const labelInput = screen.getByLabelText('Filter by label...');
    expect(labelInput).toBeInTheDocument();
    expect(labelInput).toBeVisible();

    await user.click(labelInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by label' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    mockRecordingLabelList.forEach((label) => {
      const option = within(selectMenu).getByText(label);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.click(screen.getByText('someLabel:someValue'));

    // NameFilter's parent rebuilds to close menu by default.

    expect(submitLabelInput).toBeCalledTimes(1);
    expect(submitLabelInput).toBeCalledWith('someLabel:someValue');
    expect(emptyFilteredLabels).toStrictEqual(['someLabel:someValue']);
  });
});
