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
import { cleanup, screen, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import { of } from 'rxjs';
import { ServiceContext, defaultServices } from '@app/Shared/Services/Services';
import { NotificationMessage } from '@app/Shared/Services/NotificationChannel.service';
import { AllArchivedRecordingsTable } from '@app/Archives/AllArchivedRecordingsTable';
import { ArchivedRecording, RecordingDirectory } from '@app/Shared/Services/Api.service';
import { renderWithServiceContext } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

const mockConnectUrl1 = 'service:jmx:rmi://someUrl1';
const mockJvmId1 = 'fooJvmId1';
const mockConnectUrl2 = 'service:jmx:rmi://someUrl2';
const mockJvmId2 = 'fooJvmId2';
const mockConnectUrl3 = 'service:jmx:rmi://someUrl3';
const mockJvmId3 = 'fooJvmId3';

const mockCount1 = 1;

const mockRecordingSavedNotification = {
  message: {
    target: mockConnectUrl3,
  },
} as NotificationMessage;

const mockRecordingDeletedNotification = {
  message: {
    target: mockConnectUrl1,
  },
} as NotificationMessage;

const mockRecordingLabels = {
  someLabel: 'someValue',
};

const mockRecording: ArchivedRecording = {
  name: 'someRecording',
  downloadUrl: 'http://downloadUrl',
  reportUrl: 'http://reportUrl',
  metadata: { labels: mockRecordingLabels },
  size: 2048,
  archivedTime: 2048,
};

const mockRecordingDirectory1: RecordingDirectory = {
  connectUrl: mockConnectUrl1,
  jvmId: mockJvmId1,
  recordings: [mockRecording],
};

const mockRecordingDirectory2: RecordingDirectory = {
  connectUrl: mockConnectUrl2,
  jvmId: mockJvmId2,
  recordings: [mockRecording],
};

const mockRecordingDirectory3: RecordingDirectory = {
  connectUrl: mockConnectUrl3,
  jvmId: mockJvmId3,
  recordings: [mockRecording, mockRecording, mockRecording],
};

const mockRecordingDirectory3Removed: RecordingDirectory = {
  ...mockRecordingDirectory3,
  recordings: [mockRecording, mockRecording],
};

const mockRecordingDirectory3Added: RecordingDirectory = {
  connectUrl: mockConnectUrl3,
  jvmId: mockJvmId3,
  recordings: [mockRecording, mockRecording, mockRecording, mockRecording],
};

jest.mock('@app/Recordings/ArchivedRecordingsTable', () => {
  return {
    ArchivedRecordingsTable: jest.fn((props) => {
      return <div>Archived Recordings Table</div>;
    }),
  };
});

jest.mock('@app/Shared/Services/Target.service', () => ({
  ...jest.requireActual('@app/Shared/Services/Target.service'), // Require actual implementation of utility functions for Target
}));

jest
  .spyOn(defaultServices.api, 'doGet')
  .mockReturnValueOnce(of([])) // renders correctly

  .mockReturnValueOnce(of([])) // shows no recordings when empty

  .mockReturnValueOnce(of([mockRecordingDirectory1])) // has the correct table elements

  .mockReturnValueOnce(of([mockRecordingDirectory1, mockRecordingDirectory2, mockRecordingDirectory3])) // search function

  .mockReturnValueOnce(of([mockRecordingDirectory1, mockRecordingDirectory2, mockRecordingDirectory3])) // expands targets to show their <ArchivedRecordingsTable />

  // notifications trigger doGet queries
  .mockReturnValueOnce(of([mockRecordingDirectory1, mockRecordingDirectory2, mockRecordingDirectory3])) // increments the count when an archived recording is saved
  .mockReturnValueOnce(of([mockRecordingDirectory1, mockRecordingDirectory2, mockRecordingDirectory3Added]))

  .mockReturnValueOnce(of([mockRecordingDirectory1, mockRecordingDirectory2, mockRecordingDirectory3])) // decrements the count when an archived recording is deleted
  .mockReturnValueOnce(of([mockRecordingDirectory1, mockRecordingDirectory2, mockRecordingDirectory3Removed]));

jest
  .spyOn(defaultServices.notificationChannel, 'messages')
  .mockReturnValueOnce(of()) // renders correctly  // NotificationCategory.RecordingMetadataUpdated
  .mockReturnValueOnce(of()) // NotificationCategory.ActiveRecordingSaved
  .mockReturnValueOnce(of()) // NotificationCategory.ArchivedRecordingCreated
  .mockReturnValueOnce(of()) // NotificationCategory.ArchivedRecordingDeleted

  .mockReturnValueOnce(of()) // shows no recordings when empty
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // has the correct table elements
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // correctly handles the search function
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // expands targets to show their <ArchivedRecordingsTable />
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockRecordingSavedNotification)) // increments the count when an archived recording is saved
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockRecordingDeletedNotification)) // decrements the count when an archived recording is deleted
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of());

describe('<AllArchivedRecordingsTable />', () => {
  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <AllArchivedRecordingsTable />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('shows no recordings when empty', async () => {
    renderWithServiceContext(<AllArchivedRecordingsTable />);

    expect(screen.getByText('No Archived Recordings')).toBeInTheDocument();
  });

  it('has the correct table elements', async () => {
    renderWithServiceContext(<AllArchivedRecordingsTable />);

    expect(screen.getByLabelText('all-archives-table')).toBeInTheDocument();
    expect(screen.getByText('Directory')).toBeInTheDocument();
    expect(screen.getByText('Count')).toBeInTheDocument();
    expect(screen.getByText(`${mockConnectUrl1}`)).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
  });

  it('correctly handles the search function', async () => {
    const { user } = renderWithServiceContext(<AllArchivedRecordingsTable />);

    const search = screen.getByLabelText('Search input');

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);

    await user.type(search, '1');
    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(1);
    const firstTarget = rows[0];
    expect(within(firstTarget).getByText(`${mockConnectUrl1}`)).toBeTruthy();
    expect(within(firstTarget).getByText(`${mockCount1}`)).toBeTruthy();

    await user.type(search, 'asdasdjhj');
    expect(screen.getByText('No Archived Recordings')).toBeInTheDocument();
    expect(screen.queryByLabelText('all-archives-table')).not.toBeInTheDocument();

    await user.clear(search);
    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);
  });

  it('expands targets to show their <ArchivedRecordingsTable />', async () => {
    const { user } = renderWithServiceContext(<AllArchivedRecordingsTable />);

    expect(screen.queryByText('Archived Recordings Table')).not.toBeInTheDocument();

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);

    let firstTarget = rows[0];
    const expand = within(firstTarget).getByLabelText('Details');
    await user.click(expand);

    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(4);

    let expandedTable = rows[1];
    expect(within(expandedTable).getByText('Archived Recordings Table')).toBeTruthy();

    await user.click(expand);
    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(screen.queryByText('Archived Recordings Table')).not.toBeInTheDocument();
  });

  it('increments the count when an archived recording is saved', async () => {
    renderWithServiceContext(<AllArchivedRecordingsTable />);

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);

    const thirdTarget = rows[2];
    expect(within(thirdTarget).getByText(`${mockConnectUrl3}`)).toBeTruthy();
    expect(within(thirdTarget).getByText(4)).toBeTruthy();
  });

  it('decrements the count when an archived recording is deleted', async () => {
    renderWithServiceContext(<AllArchivedRecordingsTable />);

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');

    const thirdTarget = rows[2];
    expect(within(thirdTarget).getByText(`${mockConnectUrl3}`)).toBeTruthy();
    expect(within(thirdTarget).getByText(2)).toBeTruthy();
  });
});
