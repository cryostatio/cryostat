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
import { cleanup, screen, within } from '@testing-library/react';
import renderer, { act } from 'react-test-renderer';
import '@testing-library/jest-dom';
import { of } from 'rxjs';
import { Archives } from '@app/Archives/Archives';
import { ServiceContext, defaultServices } from '@app/Shared/Services/Services';
import { renderWithServiceContext } from '../Common';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';

jest.mock('@app/Recordings/ArchivedRecordingsTable', () => {
  return {
    ArchivedRecordingsTable: jest.fn(() => {
      return <div>Uploads Table</div>;
    }),
  };
});

jest.mock('@app/Archives/AllArchivedRecordingsTable', () => {
  return {
    AllArchivedRecordingsTable: jest.fn(() => {
      return <div>All Archives Table</div>;
    }),
  };
});

jest.mock('@app/Archives/AllTargetsArchivedRecordingsTable', () => {
  return {
    AllTargetsArchivedRecordingsTable: jest.fn(() => {
      return <div>All Targets Table</div>;
    }),
  };
});

jest
  .spyOn(defaultServices.api, 'isArchiveEnabled')
  .mockReturnValueOnce(of(true))
  .mockReturnValueOnce(of(true))
  .mockReturnValueOnce(of(false)) // Test archives disabled case
  .mockReturnValue(of(true));

describe('<Archives />', () => {
  afterEach(cleanup);

  it('has the correct page title', async () => {
    renderWithServiceContext(<Archives />);

    expect(screen.getByText('Archives')).toBeInTheDocument();
  });

  it('handles the case where archiving is enabled', async () => {
    renderWithServiceContext(<Archives />);

    expect(screen.getByText('All Targets')).toBeInTheDocument();
    expect(screen.getByText('Uploads')).toBeInTheDocument();
  });

  it('handles the case where archiving is disabled', async () => {
    renderWithServiceContext(<Archives />);

    expect(screen.queryByText('All Targets')).not.toBeInTheDocument();
    expect(screen.queryByText('Uploads')).not.toBeInTheDocument();
    expect(screen.getByText('Archives Unavailable')).toBeInTheDocument();
  });

  it('handles changing tabs', async () => {
    const { user } = renderWithServiceContext(<Archives />);

    // Assert that the All Targets tab is currently selected (default behaviour)
    let tabsList = screen.getAllByRole('tab');

    let firstTab = tabsList[0];
    expect(firstTab).toHaveAttribute('aria-selected', 'true');
    expect(within(firstTab).getByText('All Targets')).toBeTruthy();

    let secondTab = tabsList[1];
    expect(secondTab).toHaveAttribute('aria-selected', 'false');
    expect(within(secondTab).getByText('All Archives')).toBeTruthy();

    let thirdTab = tabsList[2];
    expect(thirdTab).toHaveAttribute('aria-selected', 'false');
    expect(within(thirdTab).getByText('Uploads')).toBeTruthy();

    // Click the Uploads tab
    await user.click(screen.getByText('Uploads'));

    // Assert that the Uploads tab is now selected
    tabsList = screen.getAllByRole('tab');

    firstTab = tabsList[0];
    expect(firstTab).toHaveAttribute('aria-selected', 'false');
    expect(within(firstTab).getByText('All Targets')).toBeTruthy();

    secondTab = tabsList[1];
    expect(secondTab).toHaveAttribute('aria-selected', 'false');
    expect(within(secondTab).getByText('All Archives')).toBeTruthy();

    thirdTab = tabsList[2];
    expect(thirdTab).toHaveAttribute('aria-selected', 'true');
    expect(within(thirdTab).getByText('Uploads')).toBeTruthy();
  });

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <ServiceContext.Provider value={defaultServices}>
          <NotificationsContext.Provider value={NotificationsInstance}>
            <Archives />
          </NotificationsContext.Provider>
        </ServiceContext.Provider>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });
});
