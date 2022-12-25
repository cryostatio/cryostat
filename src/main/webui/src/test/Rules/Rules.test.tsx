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
import { Router } from 'react-router-dom';
import { createMemoryHistory } from 'history';
import { of, Subject } from 'rxjs';
import '@testing-library/jest-dom';
import renderer, { act } from 'react-test-renderer';
import { act as doAct, cleanup, screen, within } from '@testing-library/react';
import { Rules, Rule } from '@app/Rules/Rules';
import { ServiceContext, defaultServices, Services } from '@app/Shared/Services/Services';
import {
  NotificationCategory,
  NotificationChannel,
  NotificationMessage,
} from '@app/Shared/Services/NotificationChannel.service';
import { DeleteAutomatedRules, DeleteWarningType } from '@app/Modal/DeleteWarningUtils';
import { renderWithServiceContextAndRouter } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

const mockRule: Rule = {
  name: 'mockRule',
  description: 'A mock rule',
  matchExpression: "target.alias == 'io.cryostat.Cryostat' || target.annotations.cryostat['PORT'] == 9091",
  enabled: true,
  eventSpecifier: 'template=Profiling,type=TARGET',
  archivalPeriodSeconds: 0,
  initialDelaySeconds: 0,
  preservedArchives: 0,
  maxAgeSeconds: 0,
  maxSizeBytes: 0,
};
const mockRuleListResponse = { data: { result: [mockRule] as Rule[] } };
const mockRuleListEmptyResponse = { data: { result: [] as Rule[] } };

const mockFileUpload = new File([JSON.stringify(mockRule)], `${mockRule.name}.json`, { type: 'application/json' });
mockFileUpload.text = jest.fn(() => Promise.resolve(JSON.stringify(mockRule)));

const mockDeleteNotification = { message: { ...mockRule } } as NotificationMessage;

const mockUpdateNotification = { message: { ...mockRule, enabled: false } } as NotificationMessage;

const history = createMemoryHistory({ initialEntries: ['/rules'] });

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useRouteMatch: () => ({ url: history.location.pathname }),
  useHistory: () => history,
}));

const downloadSpy = jest.spyOn(defaultServices.api, 'downloadRule').mockReturnValue();
const uploadSpy = jest.spyOn(defaultServices.api, 'uploadRule').mockReturnValue(of(true));
const updateSpy = jest.spyOn(defaultServices.api, 'updateRule').mockReturnValue(of(true));
jest
  .spyOn(defaultServices.api, 'doGet')
  .mockReturnValueOnce(of(mockRuleListEmptyResponse)) // renders correctly empty
  .mockReturnValue(of(mockRuleListResponse));

jest.spyOn(defaultServices.settings, 'deletionDialogsEnabledFor').mockReturnValueOnce(true);

jest
  .spyOn(defaultServices.notificationChannel, 'messages')
  .mockReturnValueOnce(of()) // renders correctly
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // open view to create rules
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // opens upload modal
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // delete a rule when clicked with popup
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // delete a rule when clicked w/o popup
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // remove a rule when receiving notification
  .mockReturnValueOnce(of(mockDeleteNotification))
  .mockReturnValueOnce(of())

  .mockReturnValueOnce(of()) // update a rule when receiving notification
  .mockReturnValueOnce(of())
  .mockReturnValueOnce(of(mockUpdateNotification))

  .mockReturnValue(of()); // other tests

describe('<Rules />', () => {
  beforeEach(() => {
    history.go(-history.length);
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <Router location={history.location} history={history}>
              <Rules />
            </Router>
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('opens create rule view when Create is clicked', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    await user.click(screen.getByRole('button', { name: /Create/ }));

    expect(history.entries.map((entry) => entry.pathname)).toStrictEqual(['/rules', '/rules/create']);
  });

  it('opens upload modal when upload icon is clicked', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    await user.click(screen.getByRole('button', { name: 'Upload' }));

    const modal = await screen.findByRole('dialog');
    expect(modal).toBeInTheDocument();
    expect(modal).toBeVisible();

    const modalTitle = await within(modal).findByText('Upload Automated Rules');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();
  });

  it('shows a popup when Delete is clicked and then deletes the Rule after clicking confirmation Delete', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteRule').mockReturnValue(of(true));
    const dialogWarningSpy = jest.spyOn(defaultServices.settings, 'setDeletionDialogsEnabledFor');

    await user.click(screen.getByLabelText('Actions'));
    await user.click(await screen.findByText('Delete'));

    expect(screen.getByLabelText(DeleteAutomatedRules.ariaLabel));

    await user.click(screen.getByLabelText("Don't ask me again"));
    await user.click(within(screen.getByLabelText(DeleteAutomatedRules.ariaLabel)).getByText('Delete'));

    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith(mockRule.name, true);
    expect(dialogWarningSpy).toBeCalledTimes(1);
    expect(dialogWarningSpy).toBeCalledWith(DeleteWarningType.DeleteAutomatedRules, false);
  });

  it('deletes a rule when Delete is clicked w/o popup warning', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    const deleteRequestSpy = jest.spyOn(defaultServices.api, 'deleteRule').mockReturnValue(of(true));

    await user.click(screen.getByLabelText('Actions'));
    await user.click(await screen.findByText('Delete'));

    expect(screen.queryByLabelText(DeleteAutomatedRules.ariaLabel)).not.toBeInTheDocument();
    expect(deleteRequestSpy).toHaveBeenCalledTimes(1);
    expect(deleteRequestSpy).toBeCalledWith(mockRule.name, true);
  });

  it('remove a rule when receiving a notification', async () => {
    renderWithServiceContextAndRouter(<Rules />, { history: history });

    expect(screen.queryByText(mockRule.name)).not.toBeInTheDocument();
  });

  it('update a rule when receiving a notification', async () => {
    const subj = new Subject<NotificationMessage>();
    const mockNotifications = {
      messages: (category: string) => (category === NotificationCategory.RuleUpdated ? subj.asObservable() : of()),
    } as NotificationChannel;
    const services: Services = {
      ...defaultServices,
      notificationChannel: mockNotifications,
    };
    const { container } = renderWithServiceContextAndRouter(<Rules />, { history: history, services: services });

    expect(await screen.findByText(mockRule.name)).toBeInTheDocument();

    let labels = container.querySelectorAll('label');
    expect(labels).toHaveLength(1);
    let label = labels[0];
    expect(label).toHaveClass('switch-toggle-true');
    expect(label).not.toHaveClass('switch-toggle-false');

    doAct(() => subj.next(mockUpdateNotification));

    labels = container.querySelectorAll('label');
    expect(labels).toHaveLength(1);
    label = labels[0];
    expect(label).not.toHaveClass('switch-toggle-true');
    expect(label).toHaveClass('switch-toggle-false');
  });

  it('downloads a rule when Download is clicked', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    await user.click(screen.getByLabelText('Actions'));
    await user.click(await screen.findByText('Download'));

    expect(downloadSpy).toHaveBeenCalledTimes(1);
    expect(downloadSpy).toBeCalledWith(mockRule.name);
  });

  it('updates a rule when the switch is clicked', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    await user.click(screen.getByRole('checkbox'));

    expect(updateSpy).toHaveBeenCalledTimes(1);
    expect(updateSpy).toBeCalledWith({ ...mockRule, enabled: !mockRule.enabled });
  });

  it('upload a rule file when Submit is clicked', async () => {
    const { user } = renderWithServiceContextAndRouter(<Rules />, { history: history });

    await user.click(screen.getByRole('button', { name: 'Upload' }));

    const modal = await screen.findByRole('dialog');
    expect(modal).toBeInTheDocument();
    expect(modal).toBeVisible();

    const modalTitle = await within(modal).findByText('Upload Automated Rules');
    expect(modalTitle).toBeInTheDocument();
    expect(modalTitle).toBeVisible();

    const dropZoneText = within(modal).getByText('Drag and drop files here');
    expect(dropZoneText).toBeInTheDocument();
    expect(dropZoneText).toBeVisible();

    const uploadButton = within(modal).getByText('Upload');
    expect(uploadButton).toBeInTheDocument();
    expect(uploadButton).toBeVisible();

    const uploadInput = modal.querySelector("input[accept='application/json'][type='file']") as HTMLInputElement;
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
    expect(uploadSpy).toHaveBeenCalledWith(mockRule, expect.any(Function), expect.any(Subject));

    expect(within(modal).queryByText('Submit')).not.toBeInTheDocument();
    expect(within(modal).queryByText('Cancel')).not.toBeInTheDocument();

    const closeButton = within(modal).getByText('Close');
    expect(closeButton).toBeInTheDocument();
    expect(closeButton).toBeVisible();
  });
});
