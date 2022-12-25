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

// Mock system time for DatetimePicker
const mockCurrentDate = new Date('14 Sep 2022 00:00:00 UTC');
jest.useFakeTimers('modern').setSystemTime(mockCurrentDate);

import { RecordingFilters, RecordingFiltersCategories } from '@app/Recordings/RecordingFilters';
import { UpdateFilterOptions } from '@app/Shared/Redux/Filters/Common';
import {
  emptyActiveRecordingFilters,
  emptyArchivedRecordingFilters,
  TargetRecordingFilters,
} from '@app/Shared/Redux/Filters/RecordingFilterSlice';
import { RootState } from '@app/Shared/Redux/ReduxStore';
import { ActiveRecording, ArchivedRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { Target } from '@app/Shared/Services/Target.service';
import { Toolbar, ToolbarContent } from '@patternfly/react-core';
import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import React from 'react';
import { renderWithReduxStore } from '../Common';

const mockFooTarget: Target = {
  connectUrl: 'service:jmx:rmi://someFooUrl',
  alias: 'fooTarget',
  annotations: {
    cryostat: new Map(),
    platform: new Map(),
  },
};

const mockRecordingLabels = {
  someLabel: 'someValue',
};

const mockActiveRecording: ActiveRecording = {
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
const mockActiveRecordingList = [
  mockActiveRecording,
  { ...mockActiveRecording, name: 'anotherRecording' } as ActiveRecording,
];

const mockArchivedRecording: ArchivedRecording = {
  name: 'someArchivedRecording',
  downloadUrl: 'http://downloadUrl',
  reportUrl: 'http://reportUrl',
  metadata: { labels: mockRecordingLabels },
  size: 2048,
  archivedTime: 2048,
};
const mockArchivedRecordingList = [
  mockArchivedRecording,
  { ...mockArchivedRecording, name: 'anotherArchivedRecording' } as ArchivedRecording,
];

const activeCategoryOptions = Object.keys({} as RecordingFiltersCategories);
const archivedCategoryOptions = ['Name', 'Label'];

const updateFilters = jest.fn((target: string, options: UpdateFilterOptions) => {});

describe('<RecordingFilters />', () => {
  let preloadedState: RootState;
  let activeRecordingFilters: RecordingFiltersCategories;
  let archivedRecordingFilters: RecordingFiltersCategories;

  beforeEach(() => {
    activeRecordingFilters = {
      Name: [mockActiveRecording.name],
      Label: ['someLabel:someValue'],
      State: [],
      StartedBeforeDate: [],
      StartedAfterDate: [],
      DurationSeconds: [],
    } as RecordingFiltersCategories;

    archivedRecordingFilters = {
      Name: [mockArchivedRecording.name],
      Label: [],
    } as RecordingFiltersCategories;

    preloadedState = {
      dashboardConfigs: {
        list: [],
      },
      recordingFilters: {
        list: [
          {
            target: mockFooTarget.connectUrl,
            active: {
              selectedCategory: 'Label',
              filters: activeRecordingFilters,
            },
            archived: {
              selectedCategory: 'Name',
              filters: archivedRecordingFilters,
            },
          } as TargetRecordingFilters,
        ],
      },
      automatedAnalysisFilters: {
        state: {
          targetFilters: [],
          globalFilters: {
            filters: {
              Score: 100,
            },
          },
        },
      },
    };
  });

  afterEach(cleanup);

  afterAll(jest.useRealTimers);

  it('should display currently selected category for active recordings', async () => {
    renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={false}
            recordings={mockActiveRecordingList}
            filters={activeRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
      }
    );

    const categoryDropDown = screen.getByLabelText('Category Dropdown');
    expect(categoryDropDown).toBeInTheDocument();
    expect(categoryDropDown).toBeVisible();

    const selectedItem = screen.getByRole('button', { name: 'Label' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();
  });

  it('should display currently selected category for archived recordings', async () => {
    renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={true}
            recordings={mockArchivedRecordingList}
            filters={archivedRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    const categoryDropDown = screen.getByLabelText('Category Dropdown');
    expect(categoryDropDown).toBeInTheDocument();
    expect(categoryDropDown).toBeVisible();

    const selectedItem = screen.getByRole('button', { name: 'Name' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();
  });

  it('should display category menu for active recordings when clicked', async () => {
    const { user } = renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={false}
            recordings={mockActiveRecordingList}
            filters={activeRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    const categoryDropDown = screen.getByLabelText('Category Dropdown');
    expect(categoryDropDown).toBeInTheDocument();
    expect(categoryDropDown).toBeVisible();

    const selectedItem = screen.getByRole('button', { name: 'Label' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();

    await user.click(selectedItem);

    const categoryMenu = await screen.findByRole('menu');
    expect(categoryMenu).toBeInTheDocument();
    expect(categoryMenu).toBeVisible();

    activeCategoryOptions.forEach((category) => {
      const option = within(categoryMenu).getByRole('menuitem', { name: category });
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });
  });

  it('should display category menu for archived recordings when clicked', async () => {
    const { user } = renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={true}
            recordings={mockArchivedRecordingList}
            filters={archivedRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    const categoryDropDown = screen.getByLabelText('Category Dropdown');
    expect(categoryDropDown).toBeInTheDocument();
    expect(categoryDropDown).toBeVisible();

    const selectedItem = screen.getByRole('button', { name: 'Name' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();

    await user.click(selectedItem);

    const categoryMenu = await screen.findByRole('menu');
    expect(categoryMenu).toBeInTheDocument();
    expect(categoryMenu).toBeVisible();

    archivedCategoryOptions.forEach((category) => {
      const option = within(categoryMenu).getByRole('menuitem', { name: category });
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    // Check that only Name and Label should be showned
    activeCategoryOptions.forEach((category) => {
      if (!archivedCategoryOptions.includes(category)) {
        const option = within(categoryMenu).queryByRole('menuitem', { name: category });
        expect(option).toBeInTheDocument();
      }
    });
  });

  it('should close category menu when toggled', async () => {
    const { user } = renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={false}
            recordings={mockActiveRecordingList}
            filters={activeRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    const categoryDropDown = screen.getByLabelText('Category Dropdown');
    expect(categoryDropDown).toBeInTheDocument();
    expect(categoryDropDown).toBeVisible();

    const selectedItem = screen.getByRole('button', { name: 'Label' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();

    await user.click(selectedItem);

    let categoryMenu = await screen.findByRole('menu');
    expect(categoryMenu).toBeInTheDocument();
    expect(categoryMenu).toBeVisible();

    await user.click(selectedItem); // Click again

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('should switch filter input if a category is selected ', async () => {
    const { user } = renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={false}
            recordings={mockActiveRecordingList}
            filters={activeRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    const categoryDropDown = screen.getByLabelText('Category Dropdown');
    expect(categoryDropDown).toBeInTheDocument();
    expect(categoryDropDown).toBeVisible();

    let selectedItem = screen.getByRole('button', { name: 'Label' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();

    await user.click(selectedItem);

    const categoryMenu = await screen.findByRole('menu');
    expect(categoryMenu).toBeInTheDocument();
    expect(categoryMenu).toBeVisible();

    activeCategoryOptions.forEach((category) => {
      const option = within(categoryMenu).getByRole('menuitem', { name: category });
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.click(within(categoryMenu).getByRole('menuitem', { name: 'Name' }));

    selectedItem = screen.getByRole('button', { name: 'Name' });
    expect(selectedItem).toBeInTheDocument();
    expect(selectedItem).toBeVisible();

    const newFilterTool = screen.getByLabelText('Filter by name...');
    expect(newFilterTool).toBeInTheDocument();
    expect(newFilterTool).toBeVisible();

    const prevSelectedItem = screen.queryByRole('button', { name: 'Label' });
    expect(prevSelectedItem).not.toBeInTheDocument();
  });

  it('should approriate chips for filtered categories', async () => {
    renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={false}
            recordings={mockActiveRecordingList}
            filters={activeRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: preloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    // Label group
    let chipGroup = screen.getByRole('group', { name: 'Label' });
    expect(chipGroup).toBeInTheDocument();
    expect(chipGroup).toBeVisible();

    let chipGroupName = within(chipGroup).getByText('Label');
    expect(chipGroupName).toBeInTheDocument();
    expect(chipGroupName).toBeVisible();

    let chip = within(chipGroup).getByText('someLabel:someValue');
    expect(chip).toBeInTheDocument();
    expect(chip).toBeVisible();

    // Name group
    chipGroup = screen.getByRole('group', { name: 'Name' });
    expect(chipGroup).toBeInTheDocument();
    expect(chipGroup).toBeVisible();

    chipGroupName = within(chipGroup).getByText('Name');
    expect(chipGroupName).toBeInTheDocument();
    expect(chipGroupName).toBeVisible();

    chip = within(chipGroup).getByText(mockActiveRecording.name);
    expect(chip).toBeInTheDocument();
    expect(chip).toBeVisible();
  });

  it('should not display chips when no filters are selected', async () => {
    const emptyPreloadedState = {
      recordingFilters: {
        list: [
          {
            target: mockFooTarget.connectUrl,
            active: {
              selectedCategory: 'Label',
              filters: emptyActiveRecordingFilters,
            },
            archived: {
              selectedCategory: 'Name',
              filters: emptyArchivedRecordingFilters,
            },
          } as TargetRecordingFilters,
        ],
      },
    };
    renderWithReduxStore(
      <Toolbar>
        <ToolbarContent>
          <RecordingFilters
            target={mockFooTarget.connectUrl}
            isArchived={false}
            recordings={mockActiveRecordingList}
            filters={emptyActiveRecordingFilters}
            updateFilters={updateFilters}
          />
        </ToolbarContent>
      </Toolbar>,
      {
        preloadState: emptyPreloadedState,
        user: userEvent.setup({ advanceTimers: jest.advanceTimersByTime }),
      }
    );

    activeCategoryOptions.forEach((category) => {
      let chipGroup = screen.queryByRole('group', { name: category });
      expect(chipGroup).not.toBeInTheDocument();
    });
  });
});
