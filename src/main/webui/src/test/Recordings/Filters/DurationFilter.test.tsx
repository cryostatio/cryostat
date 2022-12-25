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

import { DurationFilter } from '@app/Recordings/Filters/DurationFilter';
import { ActiveRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { cleanup, screen } from '@testing-library/react';
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
  duration: 30,
  continuous: false,
  toDisk: false,
  maxSize: 0,
  maxAge: 0,
};

const onDurationInput = jest.fn((durationInput) => {});
const onContinuousSelect = jest.fn((continuous) => {});

describe('<DurationFilter />', () => {
  let emptyFilteredDuration: string[];
  let filteredDurationsWithCont: string[];
  let filteredDurationsWithoutCont: string[];

  beforeEach(() => {
    emptyFilteredDuration = [];
    filteredDurationsWithCont = [`${mockRecording.duration}`, 'continuous'];
    filteredDurationsWithoutCont = [`${mockRecording.duration}`];
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <DurationFilter
          durations={emptyFilteredDuration}
          onContinuousDurationSelect={onContinuousSelect}
          onDurationInput={onDurationInput}
        />
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('should check continous box if continous is in filter', async () => {
    renderDefault(
      <DurationFilter
        durations={filteredDurationsWithCont}
        onContinuousDurationSelect={onContinuousSelect}
        onDurationInput={onDurationInput}
      />
    );

    const checkBox = screen.getByRole('checkbox', { name: 'Continuous' });
    expect(checkBox).toBeInTheDocument();
    expect(checkBox).toBeVisible();
    expect(checkBox).toHaveAttribute('checked');
  });

  it('should not check continous box if continous is in filter', async () => {
    renderDefault(
      <DurationFilter
        durations={filteredDurationsWithoutCont}
        onContinuousDurationSelect={onContinuousSelect}
        onDurationInput={onDurationInput}
      />
    );

    const checkBox = screen.getByRole('checkbox', { name: 'Continuous' });
    expect(checkBox).toBeInTheDocument();
    expect(checkBox).toBeVisible();
    expect(checkBox).not.toHaveAttribute('checked');
  });

  it('should select continous when clicking unchecked continuous box', async () => {
    const submitContinous = jest.fn((continous) => {
      filteredDurationsWithoutCont.push('continuous');
    });

    const { user } = renderDefault(
      <DurationFilter
        durations={filteredDurationsWithoutCont}
        onContinuousDurationSelect={submitContinous}
        onDurationInput={onDurationInput}
      />
    );

    const checkBox = screen.getByRole('checkbox', { name: 'Continuous' });
    expect(checkBox).toBeInTheDocument();
    expect(checkBox).toBeVisible();
    expect(checkBox).not.toHaveAttribute('checked');

    await user.click(checkBox);

    expect(submitContinous).toHaveBeenCalledTimes(1);
    expect(submitContinous).toHaveBeenCalledWith(true);

    expect(filteredDurationsWithoutCont).toStrictEqual([`${mockRecording.duration}`, 'continuous']);
  });

  it('should unselect continous when clicking checked continuous box', async () => {
    const submitContinous = jest.fn((continous) => {
      filteredDurationsWithCont = filteredDurationsWithCont.filter((v) => v !== 'continuous');
    });

    const { user } = renderDefault(
      <DurationFilter
        durations={filteredDurationsWithCont}
        onContinuousDurationSelect={submitContinous}
        onDurationInput={onDurationInput}
      />
    );

    const checkBox = screen.getByRole('checkbox', { name: 'Continuous' });
    expect(checkBox).toBeInTheDocument();
    expect(checkBox).toBeVisible();
    expect(checkBox).toHaveAttribute('checked');

    await user.click(checkBox);

    expect(submitContinous).toHaveBeenCalledTimes(1);
    expect(submitContinous).toHaveBeenCalledWith(false);
    expect(filteredDurationsWithCont).toStrictEqual([`${mockRecording.duration}`]);
  });

  it('should select a duration when pressing Enter', async () => {
    const submitDuration = jest.fn((duration) => {
      emptyFilteredDuration.push(duration);
    });

    const { user } = renderDefault(
      <DurationFilter
        durations={emptyFilteredDuration}
        onContinuousDurationSelect={onContinuousSelect}
        onDurationInput={submitDuration}
      />
    );

    const durationInput = screen.getByLabelText('duration filter');
    expect(durationInput).toBeInTheDocument();
    expect(durationInput).toBeVisible();

    await user.clear(durationInput);
    await user.type(durationInput, '50');

    // Press enter
    await user.type(durationInput, '{enter}');

    expect(submitDuration).toHaveBeenCalledTimes(1);
    expect(submitDuration).toHaveBeenCalledWith(Number('50'));
    expect(emptyFilteredDuration).toStrictEqual([50]);
  });

  it('should not select a duration when pressing other keys', async () => {
    const submitDuration = jest.fn((duration) => {
      emptyFilteredDuration.push(duration);
    });

    const { user } = renderDefault(
      <DurationFilter
        durations={emptyFilteredDuration}
        onContinuousDurationSelect={onContinuousSelect}
        onDurationInput={submitDuration}
      />
    );

    const durationInput = screen.getByLabelText('duration filter');
    expect(durationInput).toBeInTheDocument();
    expect(durationInput).toBeVisible();

    await user.clear(durationInput);
    await user.type(durationInput, '50');

    // Press shift
    await user.type(durationInput, '{shift}');

    expect(submitDuration).toHaveBeenCalledTimes(0);
    expect(emptyFilteredDuration).toStrictEqual([]);
  });
});
