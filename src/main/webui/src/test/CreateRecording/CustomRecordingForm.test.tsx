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
import { screen, cleanup, act as doAct } from '@testing-library/react';
import renderer, { act } from 'react-test-renderer';
import { ServiceContext, Services } from '@app/Shared/Services/Services';
import { defaultServices } from '@app/Shared/Services/Services';
import { EventTemplate, RecordingAttributes, RecordingOptions } from '@app/Shared/Services/Api.service';
import { of, Subject } from 'rxjs';
import { renderWithServiceContext } from '../Common';

jest.mock('@patternfly/react-core', () => ({
  // Mock out tooltip for snapshot testing
  ...jest.requireActual('@patternfly/react-core'),
  Tooltip: ({ children }) => <>{children}</>,
}));

import { CustomRecordingForm } from '@app/CreateRecording/CustomRecordingForm';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';
import { TargetService } from '@app/Shared/Services/Target.service';

const mockConnectUrl = 'service:jmx:rmi://someUrl';
const mockTarget = { connectUrl: mockConnectUrl, alias: 'fooTarget' };

const mockCustomEventTemplate: EventTemplate = {
  name: 'someEventTemplate',
  description: 'Some Description',
  provider: 'Cryostat',
  type: 'CUSTOM',
};

const mockRecordingOptions: RecordingOptions = {
  toDisk: true,
  maxAge: undefined,
  maxSize: 0,
};

const mockResponse: Response = {
  ok: true,
} as Response;

jest.spyOn(defaultServices.target, 'target').mockReturnValue(of(mockTarget));
jest
  .spyOn(defaultServices.api, 'doGet')
  .mockReturnValueOnce(of([mockCustomEventTemplate])) // renders correctly
  .mockReturnValueOnce(of(mockRecordingOptions))

  .mockReturnValueOnce(of([mockCustomEventTemplate])) // should create recording when form is filled and create is clicked
  .mockReturnValueOnce(of(mockRecordingOptions))

  .mockReturnValueOnce(of([mockCustomEventTemplate])) // should show correct helper texts in metadata label editor
  .mockReturnValueOnce(of(mockRecordingOptions));

jest.spyOn(defaultServices.target, 'authFailure').mockReturnValue(of());

const history = createMemoryHistory({ initialEntries: ['/recordings/create'] });

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useRouteMatch: () => ({ url: history.location.pathname }),
  useHistory: () => history,
}));

describe('<CustomRecordingForm />', () => {
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
            <CustomRecordingForm />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('should create recording when form is filled and create is clicked', async () => {
    const onSubmitSpy = jest.spyOn(defaultServices.api, 'createRecording').mockReturnValue(of(mockResponse));
    const { user } = renderWithServiceContext(<CustomRecordingForm />);

    const nameInput = screen.getByLabelText('Name *');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    const templateSelect = screen.getByLabelText('Template *');
    expect(templateSelect).toBeInTheDocument();
    expect(templateSelect).toBeVisible();

    await user.type(nameInput, 'a_recording');
    await user.selectOptions(templateSelect, ['someEventTemplate']);

    const createButton = screen.getByRole('button', { name: /^create$/i });
    expect(createButton).toBeInTheDocument();
    expect(createButton).toBeVisible();

    expect(createButton).not.toBeDisabled();
    await user.click(createButton);

    expect(onSubmitSpy).toHaveBeenCalledTimes(1);
    expect(onSubmitSpy).toHaveBeenCalledWith({
      name: 'a_recording',
      events: 'template=someEventTemplate,type=CUSTOM',
      duration: 30,
      archiveOnStop: true,
      options: {
        toDisk: true,
        maxAge: undefined,
        maxSize: 0,
      },
      metadata: { labels: {} },
    } as RecordingAttributes);
  });

  it('should show correct helper texts in metadata label editor', async () => {
    const { user } = renderWithServiceContext(<CustomRecordingForm />);

    const metadataEditorToggle = screen.getByText('Show metadata options');
    expect(metadataEditorToggle).toBeInTheDocument();
    expect(metadataEditorToggle).toBeVisible();

    await user.click(metadataEditorToggle);

    const helperText = screen.getByText(/are set by Cryostat and will be overwritten if specifed\.$/);
    expect(helperText).toBeInTheDocument();
    expect(helperText).toBeVisible();
  });

  it('should show error view if failing to retrieve templates or recording options', async () => {
    const subj = new Subject<void>();
    const mockTargetSvc = {
      target: () => of(mockTarget),
      authFailure: () => subj.asObservable(),
    } as TargetService;
    const services: Services = {
      ...defaultServices,
      target: mockTargetSvc,
    };
    renderWithServiceContext(<CustomRecordingForm />, { services: services });

    await doAct(async () => subj.next());

    const failTitle = screen.getByText('Error displaying recording creation form');
    expect(failTitle).toBeInTheDocument();
    expect(failTitle).toBeVisible();

    const authFailText = screen.getByText('Auth failure');
    expect(authFailText).toBeInTheDocument();
    expect(authFailText).toBeVisible();

    const retryButton = screen.getByText('Retry');
    expect(retryButton).toBeInTheDocument();
    expect(retryButton).toBeVisible();
  });
});
