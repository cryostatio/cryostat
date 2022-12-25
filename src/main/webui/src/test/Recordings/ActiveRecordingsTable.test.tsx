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
import { createMemoryHistory } from 'history';
import { act, cleanup, screen, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import { of, Subject } from 'rxjs';
import { ActiveRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { NotificationMessage } from '@app/Shared/Services/NotificationChannel.service';

const mockConnectUrl = 'service:jmx:rmi://someUrl';
const mockTarget = { connectUrl: mockConnectUrl, alias: 'fooTarget' };
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
  duration: 1000, // 1000ms
  continuous: false,
  toDisk: false,
  maxSize: 0,
  maxAge: 0,
};
const mockAnotherRecording = { ...mockRecording, name: 'anotherRecording', id: 1 };
const mockCreateNotification = {
  message: { target: mockConnectUrl, recording: mockAnotherRecording },
} as NotificationMessage;
const mockLabelsNotification = {
  message: {
    target: mockConnectUrl,
    recordingName: 'someRecording',
    metadata: { labels: { someLabel: 'someUpdatedValue' } },
  },
} as NotificationMessage;
const mockStopNotification = { message: { target: mockConnectUrl, recording: mockRecording } } as NotificationMessage;
const mockDeleteNotification = mockStopNotification;

const history = createMemoryHistory({ initialEntries: ['/recordings'] });

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useRouteMatch: () => ({ url: history.location.pathname }),
  useHistory: () => history,
}));

jest.mock('@app/Recordings/RecordingFilters', () => {
  // Already tested separately
  return {
    ...jest.requireActual('@app/Recordings/RecordingFilters'),
    RecordingFilters: jest.fn(() => {
      return <div>RecordingFilters</div>;
    }),
  };
});

import { ActiveRecordingsTable } from '@app/Recordings/ActiveRecordingsTable';
import { defaultServices, Services } from '@app/Shared/Services/Services';
import { DeleteActiveRecordings, DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import {
  emptyActiveRecordingFilters,
  emptyArchivedRecordingFilters,
  TargetRecordingFilters,
} from '@app/Shared/Redux/Filters/RecordingFilterSlice';
import { RootState } from '@app/Shared/Redux/ReduxStore';
import { renderWithServiceContextAndReduxStoreWithRouter } from '../Common';
import { TargetService } from '@app/Shared/Services/Target.service';

jest.spyOn(defaultServices.api, 'archiveRecording').mockReturnValue(of(true));
jest.spyOn(defaultServices.api, 'deleteRecording').mockReturnValue(of(true));
jest.spyOn(defaultServices.api, 'doGet').mockReturnValue(of([mockRecording]));
jest.spyOn(defaultServices.api, 'downloadRecording').mockReturnValue();
jest.spyOn(defaultServices.api, 'downloadReport').mockReturnValue();
jest.spyOn(defaultServices.api, 'grafanaDashboardUrl').mockReturnValue(of('/grafanaUrl'));
jest.spyOn(defaultServices.api, 'grafanaDatasourceUrl').mockReturnValue(of('/datasource'));
jest.spyOn(defaultServices.api, 'stopRecording').mockReturnValue(of(true));
jest.spyOn(defaultServices.api, 'uploadActiveRecordingToGrafana').mockReturnValue(of(true));
jest.spyOn(defaultServices.target, 'target').mockReturnValue(of(mockTarget));
jest.spyOn(defaultServices.target, 'authFailure').mockReturnValue(of());

jest.spyOn(defaultServices.reports, 'delete').mockReturnValue();

jest
  .spyOn(defaultServices.settings, 'deletionDialogsEnabledFor')
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true)
  .mockReturnValueOnce(true) // shows a popup when Delete is clicked and then deletes the recording after clicking confirmation Delete
  .mockReturnValueOnce(false) // deletes the recording when Delete is clicked w/o popup warning
  .mockReturnValue(true);

jest
  .spyOn(defaultServices.notificationChannel, 'messages')
  .mockReturnValueOnce(of()) // renders the recording table correctly
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockCreateNotification)) // adds a recording table after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // updates the recording labels after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockLabelsNotification))

  .mockReturnValueOnce(of()) // stops a recording after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockStopNotification))
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // removes a recording after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockDeleteNotification))
  .mockReturnValue(of()); // all other tests

jest.spyOn(window, 'open').mockReturnValue(null);

describe('<ActiveRecordingsTable />', () => {
  let preloadedState: RootState;

  beforeEach(() => {
    mockRecording.metadata.labels = mockRecordingLabels;
    mockRecording.state = RecordingState.RUNNING;
    history.go(-history.length);
    preloadedState = {
      dashboardConfigs: {
        list: [],
      },
      recordingFilters: {
        list: [
          {
            target: mockTarget.connectUrl,
            active: {
              selectedCategory: 'Labels',
              filters: emptyActiveRecordingFilters,
            },
            archived: {
              selectedCategory: 'Name',
              filters: emptyArchivedRecordingFilters,
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

  it('renders the recording table correctly', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    ['Create', 'Edit Labels', 'Stop', 'Delete'].map((text) => {
      const button = screen.getByText(text);
      expect(button).toBeInTheDocument();
      expect(button).toBeVisible();
    });

    ['Name', 'Start Time', 'Duration', 'State', 'Labels'].map((text) => {
      const header = screen.getByText(text);
      expect(header).toBeInTheDocument();
      expect(header).toBeVisible();
    });

    const checkboxes = screen.getAllByRole('checkbox');
    expect(checkboxes.length).toBe(2);
    checkboxes.forEach((checkbox) => {
      expect(checkbox).toBeInTheDocument();
      expect(checkbox).toBeVisible();
    });

    const name = screen.getByText(mockRecording.name);
    expect(name).toBeInTheDocument();
    expect(name).toBeVisible();

    const startTime = screen.getByText(new Date(mockRecording.startTime).toISOString());
    expect(startTime).toBeInTheDocument();
    expect(startTime).toBeVisible();

    const duration = screen.getByText(
      mockRecording.continuous || mockRecording.duration === 0 ? 'Continuous' : `${mockRecording.duration / 1000}s`
    );
    expect(duration).toBeInTheDocument();
    expect(duration).toBeVisible();

    const state = screen.getByText(mockRecording.state);
    expect(state).toBeInTheDocument();
    expect(state).toBeVisible();

    Object.keys(mockRecordingLabels).forEach((key) => {
      const label = screen.getByText(`${key}: ${mockRecordingLabels[key]}`);
      expect(label).toBeInTheDocument();
      expect(label).toBeVisible();
    });

    const actionIcon = screen.getByRole('button', { name: 'Actions' });
    expect(actionIcon).toBeInTheDocument();
    expect(actionIcon).toBeVisible();
  });

  it('adds a recording after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });
    expect(screen.getByText('someRecording')).toBeInTheDocument();
    expect(screen.getByText('anotherRecording')).toBeInTheDocument();
  });

  it('updates the recording labels after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });
    expect(screen.getByText('someLabel: someUpdatedValue')).toBeInTheDocument();
    expect(screen.queryByText('someLabel: someValue')).not.toBeInTheDocument();
  });

  it('stops a recording after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });
    expect(screen.getByText('STOPPED')).toBeInTheDocument();
    expect(screen.queryByText('RUNNING')).not.toBeInTheDocument();
  });

  it('removes a recording after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });
    expect(screen.queryByText('someRecording')).not.toBeInTheDocument();
  });

  it('displays the toolbar buttons', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    expect(screen.getByText('Create')).toBeInTheDocument();
    expect(screen.getByText('Archive')).toBeInTheDocument();
    expect(screen.getByText('Edit Labels')).toBeInTheDocument();
    expect(screen.getByText('Stop')).toBeInTheDocument();
    expect(screen.getByText('Delete')).toBeInTheDocument();
  });

  it('routes to the Create Flight Recording form when Create is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    await user.click(screen.getByText('Create'));

    expect(history.entries.map((entry) => entry.pathname)).toStrictEqual(['/recordings', '/recordings/create']);
  });

  it('archives the selected recording when Archive is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Archive'));

    const archiveRequestSpy = jest.spyOn(defaultServices.api, 'archiveRecording');

    expect(archiveRequestSpy).toHaveBeenCalledTimes(1);
    expect(archiveRequestSpy).toBeCalledWith('someRecording');
  });

  it('stops the selected recording when Stop is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Stop'));

    const stopRequestSpy = jest.spyOn(defaultServices.api, 'stopRecording');

    expect(stopRequestSpy).toHaveBeenCalledTimes(1);
    expect(stopRequestSpy).toBeCalledWith('someRecording');
  });

  it('opens the labels drawer when Edit Labels is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Edit Labels'));
    expect(screen.getByText('Edit Recording Labels')).toBeInTheDocument();
  });

  it('shows a popup when Delete is clicked and then deletes the recording after clicking confirmation Delete', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);

    await user.click(screen.getByText('Delete'));

    const deleteModal = await screen.findByLabelText(DeleteActiveRecordings.ariaLabel);
    expect(deleteModal).toBeInTheDocument();
    expect(deleteModal).toBeVisible();

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteRecording');
    const dialogWarningSpy = jest.spyOn(defaultServices.settings, 'setDeletionDialogsEnabledFor');
    await user.click(screen.getByLabelText("Don't ask me again"));
    await user.click(within(screen.getByLabelText(DeleteActiveRecordings.ariaLabel)).getByText('Delete'));

    expect(deleteRequestSpy).toBeCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith('someRecording');
    expect(dialogWarningSpy).toBeCalledTimes(1);
    expect(dialogWarningSpy).toBeCalledWith(DeleteWarningType.DeleteActiveRecordings, false);
  });

  it('deletes the recording when Delete is clicked w/o popup warning', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Delete'));

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteRecording');

    expect(screen.queryByLabelText(DeleteActiveRecordings.ariaLabel)).not.toBeInTheDocument();
    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith('someRecording');
  });

  it('downloads a recording when Download Recording is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    await act(async () => {
      await user.click(screen.getByLabelText('Actions'));
    });
    await user.click(screen.getByText('Download Recording'));

    const downloadRequestSpy = jest.spyOn(defaultServices.api, 'downloadRecording');

    expect(downloadRequestSpy).toHaveBeenCalledTimes(1);
    expect(downloadRequestSpy).toBeCalledWith(mockRecording);
  });

  it('displays the automated analysis report when View Report is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    await act(async () => {
      await user.click(screen.getByLabelText('Actions'));
    });
    await user.click(screen.getByText('View Report ...'));

    const reportRequestSpy = jest.spyOn(defaultServices.api, 'downloadReport');

    expect(reportRequestSpy).toHaveBeenCalledTimes(1);
  });

  it('uploads a recording to Grafana when View in Grafana is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
    });

    await act(async () => {
      await user.click(screen.getByLabelText('Actions'));
    });
    await user.click(screen.getByText('View in Grafana ...'));

    const grafanaUploadSpy = jest.spyOn(defaultServices.api, 'uploadActiveRecordingToGrafana');

    expect(grafanaUploadSpy).toHaveBeenCalledTimes(1);
    expect(grafanaUploadSpy).toBeCalledWith('someRecording');
  });

  it('should show error view if failing to retrieve recordings', async () => {
    const subj = new Subject<void>();
    const mockTargetSvc = {
      target: () => of(mockTarget),
      authFailure: () => subj.asObservable(),
    } as TargetService;
    const services: Services = {
      ...defaultServices,
      target: mockTargetSvc,
    };

    renderWithServiceContextAndReduxStoreWithRouter(<ActiveRecordingsTable archiveEnabled={true} />, {
      preloadState: preloadedState,
      history: history,
      services,
    });

    await act(async () => subj.next());

    const failTitle = screen.getByText('Error retrieving recordings');
    expect(failTitle).toBeInTheDocument();
    expect(failTitle).toBeVisible();

    const authFailText = screen.getByText('Auth failure');
    expect(authFailText).toBeInTheDocument();
    expect(authFailText).toBeVisible();

    const retryButton = screen.getByText('Retry');
    expect(retryButton).toBeInTheDocument();
    expect(retryButton).toBeVisible();

    const toolbar = screen.queryByLabelText('active-recording-toolbar');
    expect(toolbar).not.toBeInTheDocument();
  });
});
