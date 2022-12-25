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
import { EventTemplate } from '@app/CreateRecording/CreateRecording';
import { CreateRule } from '@app/Rules/CreateRule';
import { Rule } from '@app/Rules/Rules';
import { NotificationMessage } from '@app/Shared/Services/NotificationChannel.service';
import { defaultServices, Services } from '@app/Shared/Services/Services';
import { Target, TargetService } from '@app/Shared/Services/Target.service';
import '@testing-library/jest-dom';
import { act as doAct, cleanup, screen } from '@testing-library/react';
import { createMemoryHistory } from 'history';
import * as React from 'react';
import { of, Subject } from 'rxjs';
import { renderWithServiceContextAndRouter } from '../Common';

const escapeKeyboardInput = (value: string) => {
  return value.replace(/[{[]/g, '$&$&');
};

const mockConnectUrl = 'service:jmx:rmi://someUrl';
const mockTarget: Target = {
  connectUrl: mockConnectUrl,
  alias: 'io.cryostat.Cryostat',
  annotations: {
    cryostat: new Map<string, string>([['PORT', '9091']]),
    platform: new Map<string, string>(),
  },
};
const mockEventTemplate: EventTemplate = {
  name: 'Profiling',
  type: 'TARGET',
  provider: 'some provider',
  description: 'some description',
};
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

const mockNewTarget: Target = {
  connectUrl: 'service:jmx:rmi://someUrl1',
  alias: 'someAlias',
};

const mockTargetFoundNotification = {
  message: {
    event: { kind: 'FOUND', serviceRef: mockNewTarget },
  },
} as NotificationMessage;

const history = createMemoryHistory({ initialEntries: ['/rules'] });

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useRouteMatch: () => ({ url: history.location.pathname }),
  useHistory: () => history,
}));

const createSpy = jest.spyOn(defaultServices.api, 'createRule').mockReturnValue(of(true));
jest.spyOn(defaultServices.notificationChannel, 'messages').mockReturnValue(of(mockTargetFoundNotification));
jest.spyOn(defaultServices.api, 'doGet').mockReturnValue(of([mockEventTemplate]));
jest.spyOn(defaultServices.target, 'target').mockReturnValue(of(mockTarget));
jest.spyOn(defaultServices.targets, 'targets').mockReturnValue(of([mockTarget]));
jest.spyOn(defaultServices.target, 'authFailure').mockReturnValue(of());

describe('<CreateRule />', () => {
  beforeEach(() => {
    history.go(-history.length);
  });

  afterEach(cleanup);

  it('should show error view if failing to retrieve templates', async () => {
    const subj = new Subject<void>();
    const mockTargetSvc = {
      target: () => of(mockTarget),
      setTarget: (target) => {},
      authFailure: () => subj.asObservable(),
    } as TargetService;
    const services: Services = {
      ...defaultServices,
      target: mockTargetSvc,
    };

    renderWithServiceContextAndRouter(<CreateRule />, { history: history, services: services });

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

  it('should submit form if form input is valid', async () => {
    const { user } = renderWithServiceContextAndRouter(<CreateRule />, { history });

    const nameInput = screen.getByLabelText('Name *');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    const descriptionInput = screen.getByLabelText('Description');
    expect(descriptionInput).toBeInTheDocument();
    expect(descriptionInput).toBeVisible();

    const matchExpressionInput = screen.getByLabelText('Match Expression *');
    expect(matchExpressionInput).toBeInTheDocument();
    expect(matchExpressionInput).toBeVisible();

    const templateSelect = screen.getByLabelText('Template *');
    expect(templateSelect).toBeInTheDocument();
    expect(templateSelect).toBeVisible();

    const maxSizeInput = screen.getByLabelText('Maximum Size');
    expect(maxSizeInput).toBeInTheDocument();
    expect(maxSizeInput).toBeVisible();

    const maxAgeInput = screen.getByLabelText('Maximum Age');
    expect(maxAgeInput).toBeInTheDocument();
    expect(maxAgeInput).toBeVisible();

    const archivalPeriodInput = screen.getByLabelText('Archival Period');
    expect(archivalPeriodInput).toBeInTheDocument();
    expect(archivalPeriodInput).toBeVisible();

    const preservedArchivesInput = screen.getByLabelText('Preserved Archives');
    expect(preservedArchivesInput).toBeInTheDocument();
    expect(preservedArchivesInput).toBeVisible();

    const initialDelayInput = screen.getByLabelText('Initial Delay');
    expect(initialDelayInput).toBeInTheDocument();
    expect(initialDelayInput).toBeVisible();

    await user.type(nameInput, mockRule.name);
    await user.type(descriptionInput, mockRule.description);
    await user.type(matchExpressionInput, escapeKeyboardInput(mockRule.matchExpression));
    await user.selectOptions(templateSelect, ['Profiling']);
    await user.type(maxSizeInput, `${mockRule.maxSizeBytes}`);
    await user.type(maxAgeInput, `${mockRule.maxAgeSeconds}`);
    await user.type(archivalPeriodInput, `${mockRule.archivalPeriodSeconds}`);
    await user.type(preservedArchivesInput, `${mockRule.preservedArchives}`);
    await user.type(initialDelayInput, `${mockRule.initialDelaySeconds}`);

    const createButton = screen.getByRole('button', { name: /^create$/i });
    expect(createButton).toBeInTheDocument();
    expect(createButton).toBeVisible();

    expect(createButton).not.toBeDisabled();
    await user.click(createButton);

    expect(createSpy).toHaveBeenCalledTimes(1);
    expect(createSpy).toHaveBeenCalledWith(mockRule);
  });

  it('should show error helper text if rule form inputs are invalid', async () => {
    const { user } = renderWithServiceContextAndRouter(<CreateRule />, { history });

    const nameInput = screen.getByLabelText('Name *');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    await user.type(nameInput, 'some name with spaces');

    const nameHelperText = screen.getByText('A rule name may only contain letters, numbers, and underscores.');
    expect(nameHelperText).toBeInTheDocument();
    expect(nameHelperText).toBeVisible();

    const matchExpressionInput = screen.getByLabelText('Match Expression *');
    expect(matchExpressionInput).toBeInTheDocument();
    expect(matchExpressionInput).toBeVisible();

    await user.type(matchExpressionInput, 'abc');

    const exphelperText = screen.getByText('Invalid Match Expression.');
    expect(exphelperText).toBeInTheDocument();
    expect(exphelperText).toBeVisible();
  });
});
