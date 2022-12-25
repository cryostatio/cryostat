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
import { act as doAct, cleanup, screen, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import { of, Subject } from 'rxjs';
import { EventTemplate } from '@app/Shared/Services/Api.service';
import { MessageMeta, MessageType, NotificationMessage } from '@app/Shared/Services/NotificationChannel.service';
import { ServiceContext, defaultServices, Services } from '@app/Shared/Services/Services';
import { EventTemplates } from '@app/Events/EventTemplates';
import { DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import { TargetService } from '@app/Shared/Services/Target.service';
import { renderWithServiceContextAndRouter } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

const mockConnectUrl = 'service:jmx:rmi://someUrl';
const mockTarget = { connectUrl: mockConnectUrl, alias: 'fooTarget' };

const mockMessageType = { type: 'application', subtype: 'json' } as MessageType;

const mockCustomEventTemplate: EventTemplate = {
  name: 'someEventTemplate',
  description: 'Some Description',
  provider: 'Cryostat',
  type: 'CUSTOM',
};

const mockAnotherTemplate = { ...mockCustomEventTemplate, name: 'anotherEventTemplate' };

const mockCreateTemplateNotification = {
  meta: {
    category: 'TemplateCreated',
    type: mockMessageType,
  } as MessageMeta,
  message: {
    template: mockAnotherTemplate,
  },
} as NotificationMessage;
const mockDeleteTemplateNotification = {
  ...mockCreateTemplateNotification,
  meta: {
    category: 'TemplateDeleted',
    type: mockMessageType,
  },
};

const mockEventTemplateContent = '<some><other><xml></xml></dummy></some>';
const mockFileUpload = new File([mockEventTemplateContent], 'mockEventTemplate.xml', { type: 'xml' });

const mockHistoryPush = jest.fn();

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useRouteMatch: () => ({ url: '/baseUrl' }),
  useHistory: () => ({
    push: mockHistoryPush,
  }),
}));

jest
  .spyOn(defaultServices.settings, 'deletionDialogsEnabledFor')
  .mockReturnValueOnce(true) // show deletion warning
  .mockReturnValue(false); // don't ask again

jest.spyOn(defaultServices.api, 'addCustomEventTemplate').mockReturnValue(of(true));
jest.spyOn(defaultServices.api, 'deleteCustomEventTemplate').mockReturnValue(of(true));
jest.spyOn(defaultServices.api, 'downloadTemplate').mockReturnValue(void 0);

jest.spyOn(defaultServices.api, 'doGet').mockReturnValue(of([mockCustomEventTemplate]));

jest.spyOn(defaultServices.target, 'target').mockReturnValue(of(mockTarget));
jest.spyOn(defaultServices.target, 'authFailure').mockReturnValue(of());

jest
  .spyOn(defaultServices.notificationChannel, 'messages')
  .mockReturnValueOnce(of()) // renders correctly
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockCreateTemplateNotification)) // adds a template after receiving a notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of(mockDeleteTemplateNotification)) // removes a template after receiving a notification
  .mockReturnValue(of()); // all other tests

describe('<EventTemplates />', () => {
  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <EventTemplates />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('adds a recording after receiving a notification', async () => {
    renderWithServiceContextAndRouter(<EventTemplates />);

    expect(screen.getByText('someEventTemplate')).toBeInTheDocument();
    expect(screen.getByText('anotherEventTemplate')).toBeInTheDocument();
  });

  it('removes a recording after receiving a notification', async () => {
    renderWithServiceContextAndRouter(<EventTemplates />);

    expect(screen.queryByText('anotherEventTemplate')).not.toBeInTheDocument();
  });

  it('displays the column header fields', async () => {
    renderWithServiceContextAndRouter(<EventTemplates />);

    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('Description')).toBeInTheDocument();
    expect(screen.getByText('Provider')).toBeInTheDocument();
    expect(screen.getByText('Type')).toBeInTheDocument();
  });

  it('shows a popup when uploading', async () => {
    const { user } = renderWithServiceContextAndRouter(<EventTemplates />);

    expect(screen.queryByLabelText('Create Custom Event Template')).not.toBeInTheDocument();

    const buttons = screen.getAllByRole('button');
    const uploadButton = buttons[0];
    await user.click(uploadButton);

    expect(screen.getByLabelText('Create Custom Event Template'));
  });

  it('downloads an event template when Download is clicked on template action bar', async () => {
    const { user } = renderWithServiceContextAndRouter(<EventTemplates />);

    await user.click(screen.getByLabelText('Actions'));
    await user.click(screen.getByText('Download'));

    const downloadRequestSpy = jest.spyOn(defaultServices.api, 'downloadTemplate');

    expect(downloadRequestSpy).toHaveBeenCalledTimes(1);
    expect(downloadRequestSpy).toBeCalledWith(mockCustomEventTemplate);
  });

  it('shows a popup when Delete is clicked and then deletes the template after clicking confirmation Delete', async () => {
    const { user } = renderWithServiceContextAndRouter(<EventTemplates />);

    await user.click(screen.getByLabelText('Actions'));

    expect(screen.getByText('Create Recording...'));
    expect(screen.getByText('Download'));
    expect(screen.getByText('Delete'));

    const deleteAction = screen.getByText('Delete');
    await user.click(deleteAction);

    expect(screen.getByLabelText('Event template delete warning'));

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteCustomEventTemplate');
    const dialogWarningSpy = jest.spyOn(defaultServices.settings, 'setDeletionDialogsEnabledFor');
    await user.click(screen.getByLabelText("Don't ask me again"));
    await user.click(within(screen.getByLabelText('Event template delete warning')).getByText('Delete'));

    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith('someEventTemplate');
    expect(dialogWarningSpy).toBeCalledTimes(1);
    expect(dialogWarningSpy).toBeCalledWith(DeleteWarningType.DeleteEventTemplates, false);
  });

  it('deletes the template when Delete is clicked w/o popup warning', async () => {
    const { user } = renderWithServiceContextAndRouter(<EventTemplates />);

    await user.click(screen.getByLabelText('Actions'));

    expect(screen.getByText('Create Recording...'));
    expect(screen.getByText('Download'));
    expect(screen.getByText('Delete'));

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteCustomEventTemplate');
    const deleteAction = screen.getByText('Delete');
    await user.click(deleteAction);

    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText('Event template delete warning')).not.toBeInTheDocument();
  });

  it('should show error view if failing to retrieve event templates', async () => {
    const subj = new Subject<void>();
    const mockTargetSvc = {
      target: () => of(mockTarget),
      authFailure: () => subj.asObservable(),
    } as TargetService;
    const services: Services = {
      ...defaultServices,
      target: mockTargetSvc,
    };

    renderWithServiceContextAndRouter(<EventTemplates />, { services: services });

    await doAct(async () => subj.next());

    const failTitle = screen.getByText('Error retrieving event templates');
    expect(failTitle).toBeInTheDocument();
    expect(failTitle).toBeVisible();

    const authFailText = screen.getByText('Auth failure');
    expect(authFailText).toBeInTheDocument();
    expect(authFailText).toBeVisible();

    const retryButton = screen.getByText('Retry');
    expect(retryButton).toBeInTheDocument();
    expect(retryButton).toBeVisible();
  });

  it('should shown empty state when table is empty', async () => {
    const { user } = renderWithServiceContextAndRouter(<EventTemplates />);

    const filterInput = screen.getByLabelText('Event template filter');
    expect(filterInput).toBeInTheDocument();
    expect(filterInput).toBeVisible();

    await user.type(filterInput, 'someveryoddname');

    expect(screen.queryByText('someEventTemplate')).not.toBeInTheDocument();

    const hintText = screen.getByText('No Event Templates');
    expect(hintText).toBeInTheDocument();
    expect(hintText).toBeVisible();
  });

  it('should upload event template when submit button is clicked', async () => {
    const createSpy = jest.spyOn(defaultServices.api, 'addCustomEventTemplate').mockReturnValueOnce(of(true));
    const { user } = renderWithServiceContextAndRouter(<EventTemplates />);

    const uploadButton = screen.getByRole('button', { name: 'Upload' });
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    await user.click(uploadButton);

    const modal = await screen.findByRole('dialog');
    expect(modal).toBeInTheDocument();
    expect(modal).toBeVisible();

    const modalTitle = await within(modal).findByText('Create Custom Event Template');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();

    const uploadButtonInModal = within(modal).getByText('Upload');
    expect(uploadButtonInModal).toBeInTheDocument();
    expect(uploadButtonInModal).toBeVisible();

    const uploadInput = modal.querySelector("input[accept='.xml,.jfc'][type='file']") as HTMLInputElement;
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

    expect(createSpy).toHaveBeenCalled();
    expect(createSpy).toHaveBeenCalledWith(mockFileUpload, expect.any(Function), expect.any(Subject));

    expect(within(modal).queryByText('Submit')).not.toBeInTheDocument();
    expect(within(modal).queryByText('Cancel')).not.toBeInTheDocument();

    const closeButton = within(modal).getByText('Close');
    expect(closeButton).toBeInTheDocument();
    expect(closeButton).toBeVisible();
  });
});
