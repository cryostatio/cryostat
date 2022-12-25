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
import { Recordings } from '@app/Recordings/Recordings';
import { Target } from '@app/Shared/Services/Target.service';
import { renderWithServiceContext } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

jest.mock('@app/Recordings/ActiveRecordingsTable', () => {
  return {
    ActiveRecordingsTable: jest.fn((props) => {
      return <div>Active Recordings Table</div>;
    }),
  };
});

jest.mock('@app/Recordings/ArchivedRecordingsTable', () => {
  return {
    ArchivedRecordingsTable: jest.fn((props) => {
      return <div>Archived Recordings Table</div>;
    }),
  };
});

jest.mock('@app/TargetView/TargetView', () => {
  return {
    TargetView: jest.fn((props) => {
      return (
        <div>
          {props.pageTitle}
          {props.children}
        </div>
      );
    }),
  };
});

const mockFooTarget: Target = {
  connectUrl: 'service:jmx:rmi://someFooUrl',
  alias: 'fooTarget',
  annotations: {
    cryostat: {},
    platform: {},
  },
};

jest.spyOn(defaultServices.target, 'target').mockReturnValue(of(mockFooTarget));

jest
  .spyOn(defaultServices.api, 'isArchiveEnabled')
  .mockReturnValueOnce(of(true)) // has the correct title in the TargetView
  .mockReturnValueOnce(of(true)) // handles the case where archiving is enabled
  .mockReturnValueOnce(of(false)) // handles the case where archiving is disabled
  .mockReturnValue(of(true)); // others

describe('<Recordings />', () => {
  afterEach(cleanup);

  it('has the correct title in the TargetView', async () => {
    renderWithServiceContext(<Recordings />);

    expect(screen.getByText('Recordings')).toBeInTheDocument();
  });

  it('handles the case where archiving is enabled', async () => {
    renderWithServiceContext(<Recordings />);

    expect(screen.getByText('Active Recordings')).toBeInTheDocument();
    expect(screen.getByText('Archived Recordings')).toBeInTheDocument();
  });

  it('handles the case where archiving is disabled', async () => {
    renderWithServiceContext(<Recordings />);

    expect(screen.getByText('Active Recordings')).toBeInTheDocument();
    expect(screen.queryByText('Archived Recordings')).not.toBeInTheDocument();
  });

  it('handles updating the activeTab state', async () => {
    const { user } = renderWithServiceContext(<Recordings />);

    // Assert that the active recordings tab is currently selected (default behaviour)
    let tabsList = screen.getAllByRole('tab');

    let firstTab = tabsList[0];
    expect(firstTab).toHaveAttribute('aria-selected', 'true');
    expect(within(firstTab).getByText('Active Recordings')).toBeTruthy();

    let secondTab = tabsList[1];
    expect(secondTab).toHaveAttribute('aria-selected', 'false');
    expect(within(secondTab).getByText('Archived Recordings')).toBeTruthy();

    // Click the archived recordings tab
    await user.click(screen.getByText('Archived Recordings'));

    // Assert that the archived recordings tab is now selected
    tabsList = screen.getAllByRole('tab');

    firstTab = tabsList[0];
    expect(firstTab).toHaveAttribute('aria-selected', 'false');
    expect(within(firstTab).getByText('Active Recordings')).toBeTruthy();

    secondTab = tabsList[1];
    expect(secondTab).toHaveAttribute('aria-selected', 'true');
    expect(within(secondTab).getByText('Archived Recordings')).toBeTruthy();
  });

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <Recordings />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });
});
