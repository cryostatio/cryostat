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

import { RecordingStateFilter } from '@app/Recordings/Filters/RecordingStateFilter';
import { ActiveRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { cleanup, screen, within } from '@testing-library/react';
import React from 'react';
import renderer, { act } from 'react-test-renderer';
import { renderDefault } from '../../Common';

const mockRecordingLabels = {
  someLabel: 'someValue',
};
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
  state: RecordingState.STOPPED,
} as ActiveRecording;

const onStateSelectToggle = jest.fn((state) => {
  /**Do nothing. Used for checking renders */
});

describe('<RecordingStateFilter />', () => {
  let filteredStates: RecordingState[];
  let emptyFilteredStates: RecordingState[];

  beforeEach(() => {
    emptyFilteredStates = [];
    filteredStates = [mockRecording.state];
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <RecordingStateFilter filteredStates={emptyFilteredStates} onSelectToggle={onStateSelectToggle} />
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('should display state selections when dropdown is clicked', async () => {
    const { user } = renderDefault(
      <RecordingStateFilter filteredStates={emptyFilteredStates} onSelectToggle={onStateSelectToggle} />
    );

    const stateDropDown = screen.getByRole('button', { name: 'Options menu' });
    expect(stateDropDown).toBeInTheDocument();
    expect(stateDropDown).toBeVisible();

    await user.click(stateDropDown);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by state' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    Object.values(RecordingState).forEach((rs) => {
      const selectOption = within(selectMenu).getByText(rs);
      expect(selectOption).toBeInTheDocument();
      expect(selectOption).toBeVisible();
    });
  });

  it('should close state selections when dropdown is toggled', async () => {
    const { user } = renderDefault(
      <RecordingStateFilter filteredStates={emptyFilteredStates} onSelectToggle={onStateSelectToggle} />
    );

    const stateDropDown = screen.getByRole('button', { name: 'Options menu' });
    expect(stateDropDown).toBeInTheDocument();
    expect(stateDropDown).toBeVisible();

    await user.click(stateDropDown);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by state' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    Object.values(RecordingState).forEach((rs) => {
      const selectOption = within(selectMenu).getByText(rs);
      expect(selectOption).toBeInTheDocument();
      expect(selectOption).toBeVisible();
    });

    await user.click(stateDropDown);
    expect(selectMenu).not.toBeInTheDocument();
    expect(selectMenu).not.toBeVisible();
  });

  it('should display filtered states as checked', async () => {
    const { user } = renderDefault(
      <RecordingStateFilter filteredStates={filteredStates} onSelectToggle={onStateSelectToggle} />
    );

    const stateDropDown = screen.getByRole('button', { name: 'Options menu' });
    expect(stateDropDown).toBeInTheDocument();
    expect(stateDropDown).toBeVisible();

    await user.click(stateDropDown);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by state' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    Object.values(RecordingState).forEach((rs) => {
      const selectOption = within(selectMenu).getByText(rs);
      expect(selectOption).toBeInTheDocument();
      expect(selectOption).toBeVisible();
    });

    const selectedOption = within(selectMenu).getByLabelText(`${mockRecording.state} State`);
    expect(selectedOption).toBeInTheDocument();
    expect(selectedOption).toBeVisible();

    const checkedBox = within(selectedOption).getByRole('checkbox');
    expect(checkedBox).toBeInTheDocument();
    expect(checkedBox).toBeVisible();
    expect(checkedBox).toHaveAttribute('checked');
  });

  it('should select a state when clicking unchecked state box', async () => {
    const onRecordingStateToggle = jest.fn((state) => {
      emptyFilteredStates.push(state);
    });

    const { user } = renderDefault(
      <RecordingStateFilter filteredStates={emptyFilteredStates} onSelectToggle={onRecordingStateToggle} />
    );

    const stateDropDown = screen.getByRole('button', { name: 'Options menu' });
    expect(stateDropDown).toBeInTheDocument();
    expect(stateDropDown).toBeVisible();

    await user.click(stateDropDown);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by state' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    Object.values(RecordingState).forEach((rs) => {
      const selectOption = within(selectMenu).getByText(rs);
      expect(selectOption).toBeInTheDocument();
      expect(selectOption).toBeVisible();
    });

    const selectedOption = within(selectMenu).getByLabelText(`${mockAnotherRecording.state} State`);
    expect(selectedOption).toBeInTheDocument();
    expect(selectedOption).toBeVisible();

    const uncheckedBox = within(selectedOption).getByRole('checkbox');
    expect(uncheckedBox).toBeInTheDocument();
    expect(uncheckedBox).toBeVisible();
    expect(uncheckedBox).not.toHaveAttribute('checked');

    await user.click(uncheckedBox);

    expect(onRecordingStateToggle).toHaveBeenCalledTimes(1);
    expect(onRecordingStateToggle).toHaveBeenCalledWith(mockAnotherRecording.state);
    expect(emptyFilteredStates).toStrictEqual([mockAnotherRecording.state]);
  });

  it('should unselect a state when clicking checked state box', async () => {
    const onRecordingStateToggle = jest.fn((state) => {
      filteredStates = filteredStates.filter((rs) => state !== rs);
    });

    const { user } = renderDefault(
      <RecordingStateFilter filteredStates={filteredStates} onSelectToggle={onRecordingStateToggle} />
    );

    const stateDropDown = screen.getByRole('button', { name: 'Options menu' });
    expect(stateDropDown).toBeInTheDocument();
    expect(stateDropDown).toBeVisible();

    await user.click(stateDropDown);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by state' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    Object.values(RecordingState).forEach((rs) => {
      const selectOption = within(selectMenu).getByText(rs);
      expect(selectOption).toBeInTheDocument();
      expect(selectOption).toBeVisible();
    });

    const selectedOption = within(selectMenu).getByLabelText(`${mockRecording.state} State`);
    expect(selectedOption).toBeInTheDocument();
    expect(selectedOption).toBeVisible();

    const uncheckedBox = within(selectedOption).getByRole('checkbox');
    expect(uncheckedBox).toBeInTheDocument();
    expect(uncheckedBox).toBeVisible();
    expect(uncheckedBox).toHaveAttribute('checked');

    await user.click(uncheckedBox);

    expect(onRecordingStateToggle).toHaveBeenCalledTimes(1);
    expect(onRecordingStateToggle).toHaveBeenCalledWith(mockRecording.state);
    expect(filteredStates).toStrictEqual([]);
  });
});
