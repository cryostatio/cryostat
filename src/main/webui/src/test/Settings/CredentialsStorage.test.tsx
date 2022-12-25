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
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import { renderDefault } from '../Common';
import { CredentialsStorage } from '@app/Settings/CredentialsStorage';
import { getFromLocalStorage, saveToLocalStorage } from '@app/utils/LocalStorage';

jest.mock('@app/utils/LocalStorage', () => {
  const map = new Map<any, any>();
  return {
    getFromLocalStorage: jest.fn((key: any, defaultValue: any): any => {
      if (map.has(key)) {
        return map.get(key);
      }
      return defaultValue;
    }),

    saveToLocalStorage: jest.fn((key: any, value: any): any => {
      map.set(key, value);
    }),
  };
});

const storageKey = 'JMX_CREDENTIAL_LOCATION';
const sessionStorageValue = 'Session (Browser Memory)';
const backendStorageValue = 'Backend';

describe('<CredentialsStorage/>', () => {
  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(React.createElement(CredentialsStorage.content, null));
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('defaults to Backend storage', async () => {
    renderDefault(React.createElement(CredentialsStorage.content, null));

    expect(getFromLocalStorage).toHaveBeenCalledTimes(1);
    expect(saveToLocalStorage).toHaveBeenCalledTimes(1);
    expect(saveToLocalStorage).lastCalledWith(storageKey, backendStorageValue);

    expect(screen.getByText(backendStorageValue)).toBeVisible();
    expect(screen.queryByText(sessionStorageValue)).toBeFalsy();
  });

  it('sets value to local storage when dropdown is clicked', async () => {
    const { user } = renderDefault(React.createElement(CredentialsStorage.content, null));

    expect(getFromLocalStorage).toHaveBeenCalledTimes(1);
    expect(saveToLocalStorage).toHaveBeenCalledTimes(1);
    expect(saveToLocalStorage).lastCalledWith(storageKey, backendStorageValue);

    await user.click(screen.getByRole('button'));

    // the default is Backend storage. Click the dropdown and select Session (Browser Memory) to change selection
    const ul = await screen.findByRole('listbox');
    const browserSession = within(ul).getByText(sessionStorageValue);
    await user.click(browserSession);

    await waitFor(() => expect(ul).not.toBeVisible()); // expect selection menu to close after user clicks an option

    // expect the selection to be visible, the other not
    expect(screen.getByText(sessionStorageValue)).toBeVisible();
    expect(screen.queryByText(backendStorageValue)).toBeFalsy();

    expect(getFromLocalStorage).toHaveBeenCalledTimes(1);
    expect(saveToLocalStorage).toHaveBeenCalledTimes(2);
    expect(saveToLocalStorage).lastCalledWith(storageKey, sessionStorageValue);
  });
});
