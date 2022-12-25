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
import { of, Subject } from 'rxjs';
import { Text } from '@patternfly/react-core';
import { screen, within, waitFor, cleanup } from '@testing-library/react';
import * as tlr from '@testing-library/react';
import '@testing-library/jest-dom';
import { ArchivedRecording, UPLOADS_SUBDIRECTORY } from '@app/Shared/Services/Api.service';
import { NotificationMessage } from '@app/Shared/Services/NotificationChannel.service';
import { ArchivedRecordingsTable } from '@app/Recordings/ArchivedRecordingsTable';
import { defaultServices } from '@app/Shared/Services/Services';
import { DeleteArchivedRecordings, DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import {
  emptyActiveRecordingFilters,
  emptyArchivedRecordingFilters,
  TargetRecordingFilters,
} from '@app/Shared/Redux/Filters/RecordingFilterSlice';
import { RootState } from '@app/Shared/Redux/ReduxStore';
import { renderWithServiceContextAndReduxStoreWithRouter } from '../Common';

const mockConnectUrl = 'service:jmx:rmi://someUrl';
const mockTarget = { connectUrl: mockConnectUrl, alias: 'fooTarget' };
const mockUploadsTarget = { connectUrl: UPLOADS_SUBDIRECTORY, alias: '' };
const mockRecordingLabels = {
  someLabel: 'someValue',
};
const mockUploadedRecordingLabels = {
  someUploaded: 'someUploadedValue',
};
const mockMetadataFileName = 'mock.metadata.json';
const mockMetadataFile = new File(
  [JSON.stringify({ labels: { ...mockUploadedRecordingLabels } })],
  mockMetadataFileName,
  { type: 'json' }
);
mockMetadataFile.text = jest.fn(() => Promise.resolve(JSON.stringify({ labels: { ...mockUploadedRecordingLabels } })));

const mockRecording: ArchivedRecording = {
  name: 'someRecording',
  downloadUrl: 'http://downloadUrl',
  reportUrl: 'http://reportUrl',
  metadata: { labels: mockRecordingLabels },
  size: 2048,
  archivedTime: 2048,
};

const mockArchivedRecordingsResponse = {
  data: {
    archivedRecordings: {
      data: [mockRecording] as ArchivedRecording[],
    },
  },
};

const mockAnotherRecording = { ...mockRecording, name: 'anotherRecording' };
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
const mockDeleteNotification = { message: { target: mockConnectUrl, recording: mockRecording } } as NotificationMessage;

const mockFileName = 'mock.jfr';
const mockFileUpload = new File([JSON.stringify(mockAnotherRecording)], mockFileName, { type: 'jfr' });

const history = createMemoryHistory({ initialEntries: ['/archives'] });

jest.mock('@app/RecordingMetadata/BulkEditLabels', () => {
  return {
    BulkEditLabels: (props: any) => <Text>Edit Recording Labels</Text>,
  };
});

jest.mock('@app/Recordings/RecordingFilters', () => {
  return {
    ...jest.requireActual('@app/Recordings/RecordingFilters'),
    RecordingFilters: jest.fn(() => {
      return <div>RecordingFilters</div>;
    }),
  };
});

jest.spyOn(defaultServices.api, 'deleteArchivedRecording').mockReturnValue(of(true));
jest.spyOn(defaultServices.api, 'downloadRecording').mockReturnValue();
jest.spyOn(defaultServices.api, 'downloadReport').mockReturnValue();
jest.spyOn(defaultServices.api, 'grafanaDatasourceUrl').mockReturnValue(of('/datasource'));
jest.spyOn(defaultServices.api, 'grafanaDashboardUrl').mockReturnValue(of('/grafanaUrl'));
jest.spyOn(defaultServices.api, 'graphql').mockReturnValue(of(mockArchivedRecordingsResponse));
jest.spyOn(defaultServices.api, 'uploadArchivedRecordingToGrafana').mockReturnValue(of(true));

jest
  .spyOn(defaultServices.settings, 'deletionDialogsEnabledFor')
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

  .mockReturnValueOnce(of(mockCreateNotification)) // adds a recording table after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // updates the recording labels after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockLabelsNotification))

  .mockReturnValueOnce(of()) // removes a recording after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockDeleteNotification))

  .mockReturnValue(of()); // all other tests

jest.spyOn(window, 'open').mockReturnValue(null);

describe('<ArchivedRecordingsTable />', () => {
  let preloadedState: RootState;
  beforeEach(() => {
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
    renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    ['Delete', 'Edit Labels'].map((text) => {
      const button = screen.getByText(text);
      expect(button).toBeInTheDocument();
      expect(button).toBeVisible();
    });

    ['Name', 'Size', 'Labels'].map((text) => {
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

    const size = screen.getByText('2 KB');
    expect(size).toBeInTheDocument();
    expect(size).toBeVisible();

    Object.keys(mockRecordingLabels).forEach((key) => {
      const label = screen.getByText(`${key}: ${mockRecordingLabels[key]}`);
      expect(label).toBeInTheDocument();
      expect(label).toBeVisible();
    });

    const actionIcon = screen.getByRole('button', { name: 'Actions' });
    expect(actionIcon).toBeInTheDocument();
    expect(actionIcon).toBeVisible();

    const totalSize = screen.getByText(`Total size: 2 KB`);
    expect(totalSize).toBeInTheDocument();
    expect(totalSize).toBeVisible();
  });

  it('adds a recording after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );
    expect(screen.getByText('someRecording')).toBeInTheDocument();
    expect(screen.getByText('anotherRecording')).toBeInTheDocument();
  });

  it('updates the recording labels after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );
    expect(screen.getByText('someLabel: someUpdatedValue')).toBeInTheDocument();
    expect(screen.queryByText('someLabel: someValue')).not.toBeInTheDocument();
  });

  it('removes a recording after receiving a notification', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );
    expect(screen.queryByText('someRecording')).not.toBeInTheDocument();
  });

  it('displays the toolbar buttons', async () => {
    renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    expect(screen.getByText('Delete')).toBeInTheDocument();
    expect(screen.getByText('Edit Labels')).toBeInTheDocument();
  });

  it('opens the labels drawer when Edit Labels is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Edit Labels'));
    expect(screen.getByText('Edit Recording Labels')).toBeInTheDocument();
  });

  it('shows a popup when Delete is clicked and then deletes the recording after clicking confirmation Delete', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Delete'));

    const deleteModal = await screen.findByLabelText(DeleteArchivedRecordings.ariaLabel);
    expect(deleteModal).toBeInTheDocument();
    expect(deleteModal).toBeVisible();

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteArchivedRecording');
    const dialogWarningSpy = jest.spyOn(defaultServices.settings, 'setDeletionDialogsEnabledFor');
    await user.click(screen.getByLabelText("Don't ask me again"));
    await user.click(within(screen.getByLabelText(DeleteArchivedRecordings.ariaLabel)).getByText('Delete'));

    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith(mockTarget.connectUrl, 'someRecording');
    expect(dialogWarningSpy).toBeCalledTimes(1);
    expect(dialogWarningSpy).toBeCalledWith(DeleteWarningType.DeleteArchivedRecordings, false);
  });

  it('deletes the recording when Delete is clicked w/o popup warning', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    const checkboxes = screen.getAllByRole('checkbox');
    const selectAllCheck = checkboxes[0];
    await user.click(selectAllCheck);
    await user.click(screen.getByText('Delete'));

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteArchivedRecording');

    expect(screen.queryByLabelText(DeleteArchivedRecordings.ariaLabel)).not.toBeInTheDocument();
    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith(mockTarget.connectUrl, 'someRecording');
  });

  it('downloads a recording when Download Recording is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await tlr.act(async () => {
      await user.click(screen.getByLabelText('Actions'));
    });
    await user.click(screen.getByText('Download Recording'));

    const downloadRequestSpy = jest.spyOn(defaultServices.api, 'downloadRecording');

    expect(downloadRequestSpy).toHaveBeenCalledTimes(1);
    expect(downloadRequestSpy).toBeCalledWith(mockRecording);
  });

  it('displays the automated analysis report when View Report is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await tlr.act(async () => {
      await user.click(screen.getByLabelText('Actions'));
    });
    await user.click(screen.getByText('View Report ...'));

    const reportRequestSpy = jest.spyOn(defaultServices.api, 'downloadReport');

    expect(reportRequestSpy).toHaveBeenCalledTimes(1);
  });

  it('uploads a recording to Grafana when View in Grafana is clicked', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await tlr.act(async () => {
      await user.click(screen.getByLabelText('Actions'));
    });
    await user.click(screen.getByText('View in Grafana ...'));

    const grafanaUploadSpy = jest.spyOn(defaultServices.api, 'uploadArchivedRecordingToGrafana');

    expect(grafanaUploadSpy).toHaveBeenCalledTimes(1);
  });

  it('renders correctly the Uploads table', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockUploadsTarget)} isUploadsTable={true} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    expect(screen.getByText('someRecording')).toBeInTheDocument();

    const uploadButton = screen.getByLabelText('upload-recording');
    expect(uploadButton).toHaveAttribute('type', 'button');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(uploadButton);

    const uploadModal = await screen.findByRole('dialog');
    expect(uploadModal).toBeInTheDocument();
    expect(uploadModal).toBeVisible();
  });

  it('uploads an archived recording without labels when Submit is clicked', async () => {
    const uploadSpy = jest.spyOn(defaultServices.api, 'uploadRecording').mockReturnValue(of(mockFileName));

    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockUploadsTarget)} isUploadsTable={true} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await user.click(screen.getByLabelText('upload-recording'));

    const modal = await screen.findByRole('dialog');

    const modalTitle = await within(modal).findByText('Re-Upload Archived Recording');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const fileLabel = await within(modal).findByText('JFR File');
    expect(fileLabel).toBeInTheDocument();
    expect(fileLabel).toBeInTheDocument();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();

    const uploadButton = within(modal).getByText('Upload');
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    const uploadInput = modal.querySelector("input[accept='.jfr'][type='file']") as HTMLInputElement;
    expect(uploadInput).toBeInTheDocument();
    expect(uploadInput).not.toBeVisible();

    await user.click(uploadButton);
    await user.upload(uploadInput, mockFileUpload);

    const fileUploadNameText = within(modal).getByText(mockFileUpload.name);
    expect(fileUploadNameText).toBeInTheDocument();
    expect(fileUploadNameText).toBeVisible();

    const submitButton = within(modal).getByText('Submit');
    expect(submitButton).toBeInTheDocument();
    expect(submitButton).toBeVisible();
    expect(submitButton).not.toBeDisabled();

    await user.click(submitButton);

    expect(uploadSpy).toHaveBeenCalled();
    expect(uploadSpy).toHaveBeenCalledWith(mockFileUpload, {}, expect.any(Function), expect.any(Subject));

    expect(within(modal).queryByText('Submit')).not.toBeInTheDocument();
    expect(within(modal).queryByText('Cancel')).not.toBeInTheDocument();

    const closeButton = within(modal).getByText('Close');
    expect(closeButton).toBeInTheDocument();
    expect(closeButton).toBeVisible();
  });

  it('uploads an archived recording with labels from editors when Submit is clicked', async () => {
    const uploadSpy = jest.spyOn(defaultServices.api, 'uploadRecording').mockReturnValue(of(mockFileName));

    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockUploadsTarget)} isUploadsTable={true} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await user.click(screen.getByLabelText('upload-recording'));

    const modal = await screen.findByRole('dialog');

    const modalTitle = await within(modal).findByText('Re-Upload Archived Recording');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const fileLabel = await within(modal).findByText('JFR File');
    expect(fileLabel).toBeInTheDocument();
    expect(fileLabel).toBeInTheDocument();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();

    const uploadButton = within(modal).getByText('Upload');
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    const uploadInput = modal.querySelector("input[accept='.jfr'][type='file']") as HTMLInputElement;
    expect(uploadInput).toBeInTheDocument();
    expect(uploadInput).not.toBeVisible();

    await user.click(uploadButton);
    await user.upload(uploadInput, mockFileUpload);

    const fileUploadNameText = within(modal).getByText(mockFileUpload.name);
    expect(fileUploadNameText).toBeInTheDocument();
    expect(fileUploadNameText).toBeVisible();

    const metadataEditorToggle = within(modal).getByText('Show metadata options');
    expect(metadataEditorToggle).toBeInTheDocument();
    expect(metadataEditorToggle).toBeVisible();

    await user.click(metadataEditorToggle);

    const addLabelButton = within(modal).getByRole('button', { name: 'Add Label' });
    expect(addLabelButton).toBeInTheDocument();
    expect(addLabelButton).toBeVisible();

    await user.click(addLabelButton);

    const keyInput = within(modal).getByLabelText('Label Key');
    expect(keyInput).toBeInTheDocument();
    expect(keyInput).toBeVisible();

    const valueInput = within(modal).getByLabelText('Label Value');
    expect(valueInput).toBeInTheDocument();
    expect(valueInput).toBeVisible();

    await user.type(keyInput, 'someLabel');
    await user.type(valueInput, 'someValue');

    const submitButton = within(modal).getByText('Submit');
    expect(submitButton).toBeInTheDocument();
    expect(submitButton).toBeVisible();
    expect(submitButton).not.toBeDisabled();

    await user.click(submitButton);

    expect(uploadSpy).toHaveBeenCalled();
    expect(uploadSpy).toHaveBeenCalledWith(
      mockFileUpload,
      { someLabel: 'someValue' },
      expect.any(Function),
      expect.any(Subject)
    );

    expect(within(modal).queryByText('Submit')).not.toBeInTheDocument();
    expect(within(modal).queryByText('Cancel')).not.toBeInTheDocument();

    const closeButton = within(modal).getByText('Close');
    expect(closeButton).toBeInTheDocument();
    expect(closeButton).toBeVisible();
  });

  it('uploads an archived recording with labels from uploads when Submit is clicked', async () => {
    const uploadSpy = jest.spyOn(defaultServices.api, 'uploadRecording').mockReturnValue(of(mockFileName));

    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockUploadsTarget)} isUploadsTable={true} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await user.click(screen.getByLabelText('upload-recording'));

    const modal = await screen.findByRole('dialog');

    const modalTitle = await within(modal).findByText('Re-Upload Archived Recording');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const fileLabel = await within(modal).findByText('JFR File');
    expect(fileLabel).toBeInTheDocument();
    expect(fileLabel).toBeInTheDocument();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();

    const uploadButton = within(modal).getByText('Upload');
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    const uploadInput = modal.querySelector("input[accept='.jfr'][type='file']") as HTMLInputElement;
    expect(uploadInput).toBeInTheDocument();
    expect(uploadInput).not.toBeVisible();

    await user.click(uploadButton);
    await user.upload(uploadInput, mockFileUpload);

    const fileUploadNameText = within(modal).getByText(mockFileUpload.name);
    expect(fileUploadNameText).toBeInTheDocument();
    expect(fileUploadNameText).toBeVisible();

    const metadataEditorToggle = within(modal).getByText('Show metadata options');
    expect(metadataEditorToggle).toBeInTheDocument();
    expect(metadataEditorToggle).toBeVisible();

    await user.click(metadataEditorToggle);

    const uploadeLabelButton = within(modal).getByRole('button', { name: 'Upload Labels' });
    expect(uploadeLabelButton).toBeInTheDocument();
    expect(uploadeLabelButton).toBeVisible();

    await user.click(uploadeLabelButton);

    const labelUploadInput = modal.querySelector("input[accept='.json'][type='file']") as HTMLInputElement;
    expect(labelUploadInput).toBeInTheDocument();

    await user.upload(labelUploadInput, mockMetadataFile);

    expect(labelUploadInput.files).not.toBe(null);
    expect(labelUploadInput.files![0]).toStrictEqual(mockMetadataFile);

    const submitButton = within(modal).getByText('Submit');
    expect(submitButton).toBeInTheDocument();
    expect(submitButton).toBeVisible();

    expect(submitButton).not.toBeDisabled();
    await user.click(submitButton);

    expect(uploadSpy).toHaveBeenCalled();
    expect(uploadSpy).toHaveBeenCalledWith(
      mockFileUpload,
      mockUploadedRecordingLabels,
      expect.any(Function),
      expect.any(Subject)
    );

    expect(within(modal).queryByText('Submit')).not.toBeInTheDocument();
    expect(within(modal).queryByText('Cancel')).not.toBeInTheDocument();

    const closeButton = within(modal).getByText('Close');
    expect(closeButton).toBeInTheDocument();
    expect(closeButton).toBeVisible();
  });

  it('should show warning popover if any metadata file upload has invalid contents', async () => {
    const { user } = renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockUploadsTarget)} isUploadsTable={true} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    await user.click(screen.getByLabelText('upload-recording'));

    const modal = await screen.findByRole('dialog');

    const modalTitle = await within(modal).findByText('Re-Upload Archived Recording');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const fileLabel = await within(modal).findByText('JFR File');
    expect(fileLabel).toBeInTheDocument();
    expect(fileLabel).toBeInTheDocument();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();

    const uploadButton = within(modal).getByText('Upload');
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    const uploadInput = modal.querySelector("input[accept='.jfr'][type='file']") as HTMLInputElement;
    expect(uploadInput).toBeInTheDocument();
    expect(uploadInput).not.toBeVisible();

    const metadataEditorToggle = within(modal).getByText('Show metadata options');
    expect(metadataEditorToggle).toBeInTheDocument();
    expect(metadataEditorToggle).toBeVisible();

    await user.click(metadataEditorToggle);

    const invalidMetadataFileName = 'invalid.metadata.json';
    const invalidMetadataFile = new File(['asdfg'], invalidMetadataFileName, { type: 'json' });
    invalidMetadataFile.text = jest.fn(() => Promise.resolve('asdfg'));

    const uploadeLabelButton = within(modal).getByRole('button', { name: 'Upload Labels' });
    expect(uploadeLabelButton).toBeInTheDocument();
    expect(uploadeLabelButton).toBeVisible();

    await user.click(uploadeLabelButton);

    const labelUploadInput = modal.querySelector("input[accept='.json'][type='file']") as HTMLInputElement;
    expect(labelUploadInput).toBeInTheDocument();

    await tlr.act(async () => {
      await user.upload(labelUploadInput, invalidMetadataFile);
    });

    expect(labelUploadInput.files).not.toBe(null);
    expect(labelUploadInput.files![0]).toStrictEqual(invalidMetadataFile);

    const warningTitle = screen.getByText('Invalid Selection');
    expect(warningTitle).toBeInTheDocument();
    expect(warningTitle).toBeVisible();

    const invalidFileText = screen.getByText(invalidMetadataFileName);
    expect(invalidFileText).toBeInTheDocument();
    expect(invalidFileText).toBeVisible();
  });

  it('should show error view if failing to retrieve recordings', async () => {
    jest.spyOn(defaultServices.api, 'graphql').mockImplementationOnce((query) => {
      throw new Error('Something wrong');
    });

    renderWithServiceContextAndReduxStoreWithRouter(
      <ArchivedRecordingsTable target={of(mockTarget)} isUploadsTable={false} isNestedTable={false} />,
      {
        preloadState: preloadedState,
        history: history,
      }
    );

    const failTitle = screen.getByText('Error retrieving recordings');
    expect(failTitle).toBeInTheDocument();
    expect(failTitle).toBeVisible();

    const authFailText = screen.getByText('Something wrong');
    expect(authFailText).toBeInTheDocument();
    expect(authFailText).toBeVisible();

    const toolbar = screen.queryByLabelText('archived-recording-toolbar');
    expect(toolbar).not.toBeInTheDocument();
  });
});
