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
import { AllTargetsArchivedRecordingsTable } from '@app/Archives/AllTargetsArchivedRecordingsTable';
import { Target } from '@app/Shared/Services/Target.service';
import { renderWithServiceContext } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

const mockConnectUrl1 = 'service:jmx:rmi://someUrl1';
const mockAlias1 = 'fooTarget1';
const mockTarget1: Target = {
  connectUrl: mockConnectUrl1,
  alias: mockAlias1,
};
const mockConnectUrl2 = 'service:jmx:rmi://someUrl2';
const mockAlias2 = 'fooTarget2';
const mockConnectUrl3 = 'service:jmx:rmi://someUrl3';
const mockAlias3 = 'fooTarget3';
const mockNewConnectUrl = 'service:jmx:rmi://someNewUrl';
const mockNewAlias = 'newTarget';
const mockNewTarget: Target = {
  connectUrl: mockNewConnectUrl,
  alias: mockNewAlias,
};
const mockCount1 = 1;
const mockCount2 = 3;
const mockCount3 = 0;
const mockNewCount = 12;

const mockTargetFoundNotification = {
  message: {
    event: { kind: 'FOUND', serviceRef: mockNewTarget },
  },
} as NotificationMessage;

const mockTargetLostNotification = {
  message: {
    event: { kind: 'LOST', serviceRef: mockTarget1 },
  },
} as NotificationMessage;

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

const mockTargetsAndCountsResponse = {
  data: {
    targetNodes: [
      {
        recordings: {
          archived: {
            aggregate: {
              count: mockCount1,
            },
          },
        },
        target: {
          alias: mockAlias1,
          serviceUri: mockConnectUrl1,
        },
      },
      {
        recordings: {
          archived: {
            aggregate: {
              count: mockCount2,
            },
          },
        },
        target: {
          alias: mockAlias2,
          serviceUri: mockConnectUrl2,
        },
      },
      {
        recordings: {
          archived: {
            aggregate: {
              count: mockCount3,
            },
          },
        },
        target: {
          alias: mockAlias3,
          serviceUri: mockConnectUrl3,
        },
      },
    ],
  },
};

const mockNewTargetCountResponse = {
  data: {
    targetNodes: [
      {
        recordings: {
          archived: {
            aggregate: {
              count: mockNewCount,
            },
          },
        },
      },
    ],
  },
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
  .spyOn(defaultServices.api, 'graphql')
  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // renders correctly

  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // has the correct table elements

  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // hides targets with zero recordings

  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // correctly handles the search function

  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // expands targets to show their <ArchivedRecordingsTable />

  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // does not expand targets with zero recordings

  .mockReturnValueOnce(of(mockTargetsAndCountsResponse)) // adds a target upon receiving a notification
  .mockReturnValueOnce(of(mockNewTargetCountResponse))

  .mockReturnValue(of(mockTargetsAndCountsResponse)); // remaining tests

jest
  .spyOn(defaultServices.notificationChannel, 'messages')
  .mockReturnValueOnce(of()) // renders correctly
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // has the correct table elements
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // hides targets with zero recordings
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // correctly handles the search function
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // expands targets to show their <ArchivedRecordingsTable />
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // does not expand targets with zero recordings
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockTargetFoundNotification)) // adds a target upon receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockTargetLostNotification)) // removes a target upon receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // increments the count when an archived recording is saved
  .mockReturnValueOnce(of(mockRecordingSavedNotification))
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // decrements the count when an archived recording is deleted
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockRecordingDeletedNotification));

describe('<AllTargetsArchivedRecordingsTable />', () => {
  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <AllTargetsArchivedRecordingsTable />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('has the correct table elements', async () => {
    renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    expect(screen.getByLabelText('all-targets-table')).toBeInTheDocument();
    expect(screen.getByText('Target')).toBeInTheDocument();
    expect(screen.getByText('Count')).toBeInTheDocument();
    expect(screen.getByText(`${mockAlias1} (${mockConnectUrl1})`)).toBeInTheDocument();
    expect(screen.getByText(`${mockCount1}`)).toBeInTheDocument();
    expect(screen.getByText(`${mockAlias2} (${mockConnectUrl2})`)).toBeInTheDocument();
    expect(screen.getByText(`${mockCount2}`)).toBeInTheDocument();
    expect(screen.getByText(`${mockAlias3} (${mockConnectUrl3})`)).toBeInTheDocument();
    expect(screen.getByText(`${mockCount3}`)).toBeInTheDocument();
  });

  it('hides targets with zero recordings', async () => {
    const { user } = renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    // By default targets with zero recordings are hidden so the only rows
    // in the table should be mockTarget1 (mockCount1 == 1) and mockTarget2 (mockCount2 == 3)
    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(2);
    const firstTarget = rows[0];
    expect(within(firstTarget).getByText(`${mockAlias1} (${mockConnectUrl1})`)).toBeTruthy();
    expect(within(firstTarget).getByText(`${mockCount1}`)).toBeTruthy();
    const secondTarget = rows[1];
    expect(within(secondTarget).getByText(`${mockAlias2} (${mockConnectUrl2})`)).toBeTruthy();
    expect(within(secondTarget).getByText(`${mockCount2}`)).toBeTruthy();

    const checkbox = screen.getByLabelText('all-targets-hide-check');
    await user.click(checkbox);

    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);
    const thirdTarget = rows[2];
    expect(within(thirdTarget).getByText(`${mockAlias3} (${mockConnectUrl3})`)).toBeTruthy();
    expect(within(thirdTarget).getByText(`${mockCount3}`)).toBeTruthy();
  });

  it('correctly handles the search function', async () => {
    const { user } = renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);
    const search = screen.getByLabelText('Search input');

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(2);

    await user.type(search, '1');
    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(1);
    const firstTarget = rows[0];
    expect(within(firstTarget).getByText(`${mockAlias1} (${mockConnectUrl1})`)).toBeTruthy();
    expect(within(firstTarget).getByText(`${mockCount1}`)).toBeTruthy();

    await user.type(search, 'asdasdjhj');
    expect(screen.getByText('No Targets')).toBeInTheDocument();
    expect(screen.queryByLabelText('all-targets-table')).not.toBeInTheDocument();

    await user.clear(search);
    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(2);
  });

  it('expands targets to show their <ArchivedRecordingsTable />', async () => {
    const { user } = renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    expect(screen.queryByText('Archived Recordings Table')).not.toBeInTheDocument();

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(2);

    let firstTarget = rows[0];
    const expand = within(firstTarget).getByLabelText('Details');
    await user.click(expand);

    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);

    let expandedTable = rows[1];
    expect(within(expandedTable).getByText('Archived Recordings Table')).toBeTruthy();

    await user.click(expand);
    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(2);
    expect(screen.queryByText('Archived Recordings Table')).not.toBeInTheDocument();
  });

  it('does not expand targets with zero recordings', async () => {
    const { user } = renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    const checkbox = screen.getByLabelText('all-targets-hide-check');
    await user.click(checkbox);

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);

    const thirdTarget = rows[2];
    expect(within(thirdTarget).getByText(`${mockAlias3} (${mockConnectUrl3})`)).toBeTruthy();
    expect(within(thirdTarget).getByText(`${mockCount3}`)).toBeTruthy();

    const expand = within(thirdTarget).getByLabelText('Details');
    await user.click(expand);

    tableBody = screen.getAllByRole('rowgroup')[1];
    rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(screen.queryByText('Archived Recordings Table')).not.toBeInTheDocument();
  });

  it('adds a target upon receiving a notification', async () => {
    renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    expect(screen.getByText(`${mockNewAlias} (${mockNewConnectUrl})`)).toBeInTheDocument();
    expect(screen.getByText(`${mockNewCount}`)).toBeInTheDocument();
  });

  it('removes a target upon receiving a notification', async () => {
    renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    expect(screen.queryByText(`${mockAlias1} (${mockConnectUrl1})`)).not.toBeInTheDocument();
    expect(screen.queryByText(`${mockCount1}`)).not.toBeInTheDocument();
  });

  it('increments the count when an archived recording is saved', async () => {
    renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');
    expect(rows).toHaveLength(3);

    const thirdTarget = rows[2];
    expect(within(thirdTarget).getByText(`${mockCount3 + 1}`)).toBeTruthy();
  });

  it('decrements the count when an archived recording is deleted', async () => {
    const { user } = renderWithServiceContext(<AllTargetsArchivedRecordingsTable />);

    const checkbox = screen.getByLabelText('all-targets-hide-check');
    await user.click(checkbox);

    let tableBody = screen.getAllByRole('rowgroup')[1];
    let rows = within(tableBody).getAllByRole('row');

    const firstTarget = rows[0];
    expect(within(firstTarget).getByText(`${mockAlias1} (${mockConnectUrl1})`)).toBeTruthy();
    expect(within(firstTarget).getByText(`${mockCount1 - 1}`)).toBeTruthy();
  });
});
