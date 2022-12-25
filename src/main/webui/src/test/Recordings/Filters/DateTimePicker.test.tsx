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

// Mock system time
const mockCurrentDate = new Date('14 Sep 2022 00:00:00 UTC');
jest.useFakeTimers('modern').setSystemTime(mockCurrentDate);

import React from 'react';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import { renderDefault } from '../../Common';
import userEvent from '@testing-library/user-event';
import { DateTimePicker } from '@app/Recordings/Filters/DateTimePicker';

const onDateTimeSelect = jest.fn((date) => {});

describe('<DateTimePicker />', () => {
  afterEach(cleanup);

  afterAll(jest.useRealTimers);

  it('should open calendar when calendar icon is clicked', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const calendarIcon = screen.getByRole('button', { name: 'Toggle date picker' });
    expect(calendarIcon).toBeInTheDocument();
    expect(calendarIcon).toBeVisible();

    await user.click(calendarIcon);

    const calendar = await screen.findByRole('dialog');
    expect(calendar).toBeInTheDocument();
    expect(calendar).toBeVisible();
  });

  it('should close calendar when calendar icon is clicked', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const calendarIcon = screen.getByRole('button', { name: 'Toggle date picker' });
    expect(calendarIcon).toBeInTheDocument();
    expect(calendarIcon).toBeVisible();

    await user.click(calendarIcon);

    const calendar = await screen.findByRole('dialog');
    expect(calendar).toBeInTheDocument();
    expect(calendar).toBeVisible();

    await user.click(calendarIcon);

    await waitFor(() => {
      expect(calendar).not.toBeInTheDocument();
      expect(calendar).not.toBeVisible();
    });
  });

  it('should enable search icon when date is selected', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const calendarIcon = screen.getByRole('button', { name: 'Toggle date picker' });
    expect(calendarIcon).toBeInTheDocument();
    expect(calendarIcon).toBeVisible();

    await user.click(calendarIcon);

    const calendar = await screen.findByRole('dialog');
    expect(calendar).toBeInTheDocument();
    expect(calendar).toBeVisible();

    const dateOption = within(calendar).getByRole('button', { name: '14 September 2022' });
    expect(dateOption).toBeInTheDocument();
    expect(dateOption).toBeVisible();

    await user.click(dateOption);

    await waitFor(() => {
      expect(calendar).not.toBeInTheDocument();
      expect(calendar).not.toBeVisible();
    });

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).not.toBeDisabled();
  });

  it('should update date time when date is selected and search icon is clicked', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const calendarIcon = screen.getByRole('button', { name: 'Toggle date picker' });
    expect(calendarIcon).toBeInTheDocument();
    expect(calendarIcon).toBeVisible();

    await user.click(calendarIcon);

    const calendar = await screen.findByRole('dialog');
    expect(calendar).toBeInTheDocument();
    expect(calendar).toBeVisible();

    const dateOption = within(calendar).getByRole('button', { name: '14 September 2022' });
    expect(dateOption).toBeInTheDocument();
    expect(dateOption).toBeVisible();

    await user.click(dateOption);

    await waitFor(() => {
      expect(calendar).not.toBeInTheDocument();
      expect(calendar).not.toBeVisible();
    });

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).not.toBeDisabled();

    await user.click(searchIcon);

    expect(onDateTimeSelect).toHaveBeenCalledTimes(1);
    expect(onDateTimeSelect).toHaveBeenCalledWith(mockCurrentDate.toISOString());
  });

  it('should update date time when both date and time are selected and search icon is clicked', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    // Select a date
    const calendarIcon = screen.getByRole('button', { name: 'Toggle date picker' });
    expect(calendarIcon).toBeInTheDocument();
    expect(calendarIcon).toBeVisible();

    await user.click(calendarIcon);

    const calendar = await screen.findByRole('dialog');
    expect(calendar).toBeInTheDocument();
    expect(calendar).toBeVisible();

    const dateOption = within(calendar).getByRole('button', { name: '14 September 2022' });
    expect(dateOption).toBeInTheDocument();
    expect(dateOption).toBeVisible();

    await user.click(dateOption);

    await waitFor(() => {
      expect(calendar).not.toBeInTheDocument();
      expect(calendar).not.toBeVisible();
    });

    // Select a time
    const timeInput = screen.getByLabelText('Time Picker');
    expect(timeInput).toBeInTheDocument();
    expect(timeInput).toBeVisible();

    await user.click(timeInput);

    const timeMenu = await screen.findByRole('menu', { name: 'Time Picker' });
    expect(timeMenu).toBeInTheDocument();
    expect(timeMenu).toBeVisible();

    const noonOption = within(timeMenu).getByRole('menuitem', { name: '12:00' });
    expect(noonOption).toBeInTheDocument();
    expect(noonOption).toBeVisible();

    await user.click(noonOption);

    expect(timeMenu).not.toBeInTheDocument();
    expect(timeMenu).not.toBeVisible();

    // Submit
    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).not.toBeDisabled();

    await user.click(searchIcon);

    expect(onDateTimeSelect).toHaveBeenCalledTimes(1);
    const expectedDate = new Date(mockCurrentDate);
    expectedDate.setUTCHours(12, 0);
    expect(onDateTimeSelect).toHaveBeenCalledWith(expectedDate.toISOString());
  });

  it('should enable search icon when a valid date is entered', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const dateInput = screen.getByLabelText('Date Picker');
    expect(dateInput).toBeInTheDocument();
    expect(dateInput).toBeVisible();

    await user.type(dateInput, '2022-09-14');
    await user.type(dateInput, '{enter}');

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).not.toBeDisabled();
  });

  it('should show error when an invalid date is entered', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const dateInput = screen.getByLabelText('Date Picker');
    expect(dateInput).toBeInTheDocument();
    expect(dateInput).toBeVisible();

    await user.type(dateInput, 'invalid_date');
    await user.type(dateInput, '{enter}');

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).toBeDisabled();

    const errorMessage = await screen.findByText('Invalid date');
    expect(errorMessage).toBeInTheDocument();
    expect(errorMessage).toBeVisible();
  });

  it('should open time menu when time input is clicked', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const timeInput = screen.getByLabelText('Time Picker');
    expect(timeInput).toBeInTheDocument();
    expect(timeInput).toBeVisible();

    await user.click(timeInput);

    const timeMenu = await screen.findByRole('menu', { name: 'Time Picker' });
    expect(timeMenu).toBeInTheDocument();
    expect(timeMenu).toBeVisible();
  });

  it('should close time menu when time input is clicked and then user clicks elsewhere', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const timeInput = screen.getByLabelText('Time Picker');
    expect(timeInput).toBeInTheDocument();
    expect(timeInput).toBeVisible();

    await user.click(timeInput);

    const timeMenu = await screen.findByRole('menu', { name: 'Time Picker' });
    expect(timeMenu).toBeInTheDocument();
    expect(timeMenu).toBeVisible();

    await user.click(document.body); // Click elsewhere

    expect(timeMenu).not.toBeInTheDocument();
    expect(timeMenu).not.toBeVisible();
  });

  it('should disable search icon when no date is selected', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).toBeDisabled();
  });

  it('should still disable search icon when time is selected', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const timeInput = screen.getByLabelText('Time Picker');
    expect(timeInput).toBeInTheDocument();
    expect(timeInput).toBeVisible();

    await user.click(timeInput);

    const timeMenu = await screen.findByRole('menu', { name: 'Time Picker' });
    expect(timeMenu).toBeInTheDocument();
    expect(timeMenu).toBeVisible();

    const noonOption = within(timeMenu).getByRole('menuitem', { name: '12:00' });
    expect(noonOption).toBeInTheDocument();
    expect(noonOption).toBeVisible();

    await user.click(noonOption);

    expect(timeMenu).not.toBeInTheDocument();
    expect(timeMenu).not.toBeVisible();

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).toBeDisabled();
  });

  it('should show error when an invalid time is entered', async () => {
    const { user } = renderDefault(<DateTimePicker onSubmit={onDateTimeSelect} />, {
      user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
    });

    const timeInput = screen.getByLabelText('Time Picker');
    expect(timeInput).toBeInTheDocument();
    expect(timeInput).toBeVisible();

    await user.type(timeInput, 'invalid_time');
    await user.type(timeInput, '{enter}');

    const searchIcon = screen.getByRole('button', { name: 'Search For Date' });
    expect(searchIcon).toBeInTheDocument();
    expect(searchIcon).toBeVisible();
    expect(searchIcon).toBeDisabled();

    const errorMessage = await screen.findByText('Invalid time format');
    expect(errorMessage).toBeInTheDocument();
    expect(errorMessage).toBeVisible();
  });
});
