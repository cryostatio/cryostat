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
import { of } from 'rxjs';
import '@testing-library/jest-dom';
import { BulkEditLabels } from '@app/RecordingMetadata/BulkEditLabels';
import { ServiceContext, defaultServices } from '@app/Shared/Services/Services';
import { ActiveRecording, ArchivedRecording, RecordingState } from '@app/Shared/Services/Api.service';
import { NotificationMessage } from '@app/Shared/Services/NotificationChannel.service';
import { renderWithServiceContext } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

jest.mock('@patternfly/react-core', () => ({
  ...jest.requireActual('@patternfly/react-core'),
  Tooltip: ({ t }) => <>{t}</>,
}));

const mockConnectUrl = 'service:jmx:rmi://someUrl';
const mockTarget = { connectUrl: mockConnectUrl, alias: 'fooTarget' };

const mockRecordingLabels = {
  someLabel: 'someValue',
};

const mockArchivedRecording: ArchivedRecording = {
  name: 'someArchivedRecording_some_random',
  downloadUrl: 'http://downloadUrl',
  reportUrl: 'http://reportUrl',
  metadata: { labels: mockRecordingLabels },
  size: 2048,
  archivedTime: 2048,
};

const mockActiveRecording: ActiveRecording = {
  name: 'someActiveRecording',
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

const mockActiveLabelsNotification = {
  message: {
    target: mockConnectUrl,
    recordingName: 'someActiveRecording',
    metadata: { labels: { someLabel: 'someValue', someNewLabel: 'someNewValue' } },
  },
} as NotificationMessage;

const mockActiveRecordingResponse = [mockActiveRecording];

const mockArchivedLabelsNotification = {
  message: {
    target: mockConnectUrl,
    recordingName: 'someArchivedRecording_some_random',
    metadata: { labels: { someLabel: 'someValue', someNewLabel: 'someNewValue' } },
  },
} as NotificationMessage;

const mockArchivedRecordingsResponse = {
  data: {
    targetNodes: [
      {
        recordings: {
          archived: {
            data: [mockArchivedRecording] as ArchivedRecording[],
          },
        },
      },
    ],
  },
};

jest.spyOn(defaultServices.target, 'target').mockReturnValue(of(mockTarget));
jest.spyOn(defaultServices.api, 'graphql').mockReturnValue(of(mockArchivedRecordingsResponse));
jest.spyOn(defaultServices.api, 'doGet').mockReturnValue(of(mockActiveRecordingResponse));
jest
  .spyOn(defaultServices.notificationChannel, 'messages')
  .mockReturnValueOnce(of()) // renders correctly
  .mockReturnValueOnce(of()) // should display read-only labels from selected recordings
  .mockReturnValueOnce(of()) // should display editable labels form when in edit mode
  .mockReturnValueOnce(of()) // should not display labels for unchecked recordings
  .mockReturnValueOnce(of(mockActiveLabelsNotification)) // should update the target recording labels after receiving a notification
  .mockReturnValueOnce(of(mockArchivedLabelsNotification)) // should update the archived recording labels after receiving a notification
  .mockReturnValue(of());

describe('<BulkEditLabels />', () => {
  let activeCheckedIndices: number[];
  let archivedCheckedIndices: number[];
  let emptycheckIndices: number[];

  beforeEach(() => {
    activeCheckedIndices = [mockActiveRecording.id];
    archivedCheckedIndices = [-553224758]; // Hash code of "someArchivedRecording_some_random"
    emptycheckIndices = [];
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <BulkEditLabels checkedIndices={activeCheckedIndices} isTargetRecording={true} />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('should display read-only labels from selected recordings', async () => {
    renderWithServiceContext(<BulkEditLabels checkedIndices={activeCheckedIndices} isTargetRecording={true} />);

    const label = screen.getByLabelText('someLabel: someValue');
    expect(label).toBeInTheDocument();
    expect(label).toBeVisible();
    expect(label.onclick).toBeNull();

    const addLabelButton = screen.queryByRole('button', { name: 'Add Label' });
    expect(addLabelButton).not.toBeInTheDocument();

    const editButton = screen.getByRole('button', { name: 'Edit Labels' });
    expect(editButton).toBeInTheDocument();
    expect(editButton).toBeVisible();
    expect(editButton).not.toBeDisabled();
  });

  it('should not display labels for unchecked recordings', async () => {
    renderWithServiceContext(<BulkEditLabels checkedIndices={emptycheckIndices} isTargetRecording={true} />);

    expect(screen.queryByText('someLabel')).not.toBeInTheDocument();
    expect(screen.queryByText('someValue')).not.toBeInTheDocument();

    const placeHolder = screen.getByText('-');
    expect(placeHolder).toBeInTheDocument();
    expect(placeHolder).toBeVisible();
  });

  it('should display editable labels form when in edit mode', async () => {
    const { user } = renderWithServiceContext(
      <BulkEditLabels checkedIndices={activeCheckedIndices} isTargetRecording={true} />
    );

    const editButton = screen.getByRole('button', { name: 'Edit Labels' });
    expect(editButton).toBeInTheDocument();
    expect(editButton).toBeVisible();

    await user.click(editButton);

    const addLabelButton = screen.getByRole('button', { name: 'Add Label' });
    expect(addLabelButton).toBeInTheDocument();
    expect(addLabelButton).toBeVisible();

    const labelKeyInput = screen.getAllByDisplayValue('someLabel')[0];
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toBeInTheDocument();
    expect(labelKeyInput).toBeVisible();

    const labelValueInput = screen.getAllByDisplayValue('someValue')[0];
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toBeInTheDocument();
    expect(labelValueInput).toBeVisible();

    const saveButton = screen.getByText('Save');
    expect(saveButton).toBeInTheDocument();
    expect(saveButton).toBeVisible();

    const cancelButton = screen.getByText('Cancel');
    expect(cancelButton).toBeInTheDocument();
    expect(cancelButton).toBeVisible();
  });

  it('should update the target recording labels after receiving a notification', async () => {
    renderWithServiceContext(<BulkEditLabels checkedIndices={activeCheckedIndices} isTargetRecording={true} />);

    const newLabel = screen.getByLabelText('someNewLabel: someNewValue');
    expect(newLabel).toBeInTheDocument();
    expect(newLabel).toBeVisible();

    const oldLabel = screen.getByLabelText('someLabel: someValue');
    expect(oldLabel).toBeInTheDocument();
    expect(oldLabel).toBeVisible();
  });

  it('should update the archived recording labels after receiving a notification', async () => {
    renderWithServiceContext(<BulkEditLabels checkedIndices={archivedCheckedIndices} isTargetRecording={false} />);

    const newLabel = screen.getByLabelText('someNewLabel: someNewValue');
    expect(newLabel).toBeInTheDocument();
    expect(newLabel).toBeVisible();

    const oldLabel = screen.getByLabelText('someLabel: someValue');
    expect(oldLabel).toBeInTheDocument();
    expect(oldLabel).toBeVisible();
  });

  it('should return to read-only view when edited labels are cancelled', async () => {
    const { user } = renderWithServiceContext(
      <BulkEditLabels checkedIndices={archivedCheckedIndices} isTargetRecording={false} />
    );

    let editButton = screen.getByRole('button', { name: 'Edit Labels' });
    expect(editButton).toBeInTheDocument();
    expect(editButton).toBeVisible();

    await user.click(editButton);

    let addLabelButton = screen.getByRole('button', { name: 'Add Label' });
    expect(addLabelButton).toBeInTheDocument();
    expect(addLabelButton).toBeVisible();

    const labelKeyInput = screen.getAllByDisplayValue('someLabel')[0];
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toBeInTheDocument();
    expect(labelKeyInput).toBeVisible();

    const labelValueInput = screen.getAllByDisplayValue('someValue')[0];
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toBeInTheDocument();
    expect(labelValueInput).toBeVisible();

    const saveButton = screen.getByText('Save');
    expect(saveButton).toBeInTheDocument();
    expect(saveButton).toBeVisible();

    const cancelButton = screen.getByText('Cancel');
    expect(cancelButton).toBeInTheDocument();
    expect(cancelButton).toBeVisible();

    await user.click(cancelButton);

    editButton = screen.getByRole('button', { name: 'Edit Labels' });
    expect(editButton).toBeInTheDocument();
    expect(editButton).toBeVisible();
    expect(addLabelButton).not.toBeInTheDocument();
  });

  it('should save target recording labels when Save is clicked', async () => {
    const saveRequestSpy = jest
      .spyOn(defaultServices.api, 'postTargetRecordingMetadata')
      .mockReturnValue(of([mockActiveRecording]));
    const { user } = renderWithServiceContext(
      <BulkEditLabels checkedIndices={activeCheckedIndices} isTargetRecording={true} />
    );

    let editButton = screen.getByRole('button', { name: 'Edit Labels' });
    expect(editButton).toBeInTheDocument();
    expect(editButton).toBeVisible();

    await user.click(editButton);

    let addLabelButton = screen.getByRole('button', { name: 'Add Label' });
    expect(addLabelButton).toBeInTheDocument();
    expect(addLabelButton).toBeVisible();

    const labelKeyInput = screen.getAllByDisplayValue('someLabel')[0];
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toBeInTheDocument();
    expect(labelKeyInput).toBeVisible();

    const labelValueInput = screen.getAllByDisplayValue('someValue')[0];
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toBeInTheDocument();
    expect(labelValueInput).toBeVisible();

    const saveButton = screen.getByText('Save');
    expect(saveButton).toBeInTheDocument();
    expect(saveButton).toBeVisible();

    const cancelButton = screen.getByText('Cancel');
    expect(cancelButton).toBeInTheDocument();
    expect(cancelButton).toBeVisible();

    await user.click(saveButton);

    expect(saveRequestSpy).toHaveBeenCalledTimes(1);
  });

  it('should save archived recording labels when Save is clicked', async () => {
    const saveRequestSpy = jest
      .spyOn(defaultServices.api, 'postRecordingMetadata')
      .mockReturnValue(of([mockArchivedRecording]));
    const { user } = renderWithServiceContext(
      <BulkEditLabels checkedIndices={archivedCheckedIndices} isTargetRecording={false} />
    );

    let editButton = screen.getByRole('button', { name: 'Edit Labels' });
    expect(editButton).toBeInTheDocument();
    expect(editButton).toBeVisible();

    await user.click(editButton);

    let addLabelButton = screen.getByRole('button', { name: 'Add Label' });
    expect(addLabelButton).toBeInTheDocument();
    expect(addLabelButton).toBeVisible();

    const labelKeyInput = screen.getAllByDisplayValue('someLabel')[0];
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toHaveClass('pf-c-form-control');
    expect(labelKeyInput).toBeInTheDocument();
    expect(labelKeyInput).toBeVisible();

    const labelValueInput = screen.getAllByDisplayValue('someValue')[0];
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toHaveClass('pf-c-form-control');
    expect(labelValueInput).toBeInTheDocument();
    expect(labelValueInput).toBeVisible();

    const saveButton = screen.getByText('Save');
    expect(saveButton).toBeInTheDocument();
    expect(saveButton).toBeVisible();

    const cancelButton = screen.getByText('Cancel');
    expect(cancelButton).toBeInTheDocument();
    expect(cancelButton).toBeVisible();

    await user.click(saveButton);

    expect(saveRequestSpy).toHaveBeenCalledTimes(1);
  });
});
