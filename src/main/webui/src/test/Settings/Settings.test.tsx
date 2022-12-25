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
import '@testing-library/jest-dom';
import renderer, { act } from 'react-test-renderer';
import { cleanup } from '@testing-library/react';
import { Text } from '@patternfly/react-core';
import { Settings } from '@app/Settings/Settings';

jest.mock('@app/Settings/NotificationControl', () => ({
  NotificationControl: {
    title: 'Notification Control Title',
    description: 'Notification Control Description',
    content: () => <Text>Notification Control Component</Text>,
  },
}));

jest.mock('@app/Settings/AutomatedAnalysisConfig', () => ({
  AutomatedAnalysisConfig: {
    title: 'Automated Analysis Config Title',
    description: 'Automated Analysis Config Description',
    content: () => <Text>Automated Analysis Config Component</Text>,
  },
}));

jest.mock('@app/Settings/CredentialsStorage', () => ({
  CredentialsStorage: {
    title: 'Credentials Storage Title',
    description: 'Credentials Storage Description',
    content: () => <Text>Credentials Storage Component</Text>,
  },
}));

jest.mock('@app/Settings/DeletionDialogControl', () => ({
  DeletionDialogControl: {
    title: 'Deletion Dialog Control Title',
    description: 'Deletion Dialog Control Description',
    content: () => <Text>Deletion Dialog Control Component</Text>,
  },
}));

jest.mock('@app/Settings/WebSocketDebounce', () => ({
  WebSocketDebounce: {
    title: 'WebSocket Debounce Title',
    description: 'WebSocket Debounce Description',
    content: () => <Text>WebSocket Debounce Component</Text>,
  },
}));

jest.mock('@app/Settings/AutoRefresh', () => ({
  AutoRefresh: {
    title: 'AutoRefresh Title',
    description: 'AutoRefresh Description',
    content: () => <Text>AutoRefresh Component</Text>,
  },
}));

jest.mock('@app/Settings/FeatureLevels', () => ({
  FeatureLevels: {
    title: 'Feature Levels Title',
    description: 'Feature Levels Description',
    content: () => <Text>Feature Levels Component</Text>,
  },
}));

describe('<Settings/>', () => {
  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(<Settings />);
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });
});
