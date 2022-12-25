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
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';

jest.mock('@app/RecordingMetadata/BulkEditLabels', () => {
  return {
    BulkEditLabels: jest.fn((props) => {
      return (
        <div>
          Bulk Edit Labels
          {props.children}
        </div>
      );
    }),
  };
});

import { RecordingLabelsPanel } from '@app/Recordings/RecordingLabelsPanel';
import { ArchivedRecording } from '@app/Shared/Services/Api.service';
import { Drawer, DrawerContent } from '@patternfly/react-core';
import { renderDefault } from '../Common';

const mockRecordingLabels = {
  someLabel: 'someValue',
};

const mockRecording: ArchivedRecording = {
  name: 'someRecording',
  downloadUrl: 'http://downloadUrl',
  reportUrl: 'http://reportUrl',
  metadata: { labels: mockRecordingLabels },
  size: 2048,
  archivedTime: 2048,
};

describe('<RecordingLabelsPanel />', () => {
  let isTargetRecording = true;
  let checkedIndices = [];
  let recordings = [mockRecording];

  const props = {
    setShowPanel: jest.fn(() => (showPanel: boolean) => {}),
    isTargetRecording: isTargetRecording,
    checkedIndices: checkedIndices,
    recordings: recordings,
  };

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <Drawer isExpanded={true} isInline>
          <DrawerContent panelContent={<RecordingLabelsPanel {...props} />} />
        </Drawer>
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('displays the bulk labels editor within the resizeable drawer panel', async () => {
    renderDefault(
      <Drawer isExpanded={true} isInline>
        <DrawerContent panelContent={<RecordingLabelsPanel {...props} />} />
      </Drawer>
    );
    expect(screen.getByText('Bulk Edit Labels')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Resize').length).toBe(1);
    expect(screen.getAllByLabelText('hide table actions panel').length).toBe(1);
  });
});
