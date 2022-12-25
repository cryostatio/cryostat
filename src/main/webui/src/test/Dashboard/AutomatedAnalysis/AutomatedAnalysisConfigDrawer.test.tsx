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
import { AutomatedAnalysisConfigDrawer } from '@app/Dashboard/AutomatedAnalysis/AutomatedAnalysisConfigDrawer';
import { defaultAutomatedAnalysisRecordingConfig, SimpleResponse } from '@app/Shared/Services/Api.service';
import { defaultServices } from '@app/Shared/Services/Services';
import '@testing-library/jest-dom';
import { cleanup, screen } from '@testing-library/react';
import * as React from 'react';
import { of } from 'rxjs';
import { renderWithServiceContext } from '../../Common';

const drawerContent = <div>Drawer Content</div>;

jest.mock('@app/Dashboard/AutomatedAnalysis/AutomatedAnalysisConfigForm', () => {
  return {
    ...jest.requireActual('@app/Dashboard/AutomatedAnalysis/AutomatedAnalysisConfigForm'),
    AutomatedAnalysisConfigForm: jest.fn(() => {
      return <div>AutomatedAnalysisConfigForm</div>;
    }),
  };
});

jest.spyOn(defaultServices.api, 'createRecording').mockReturnValue(of({ ok: true } as SimpleResponse));
jest
  .spyOn(defaultServices.settings, 'automatedAnalysisRecordingConfig')
  .mockReturnValue(defaultAutomatedAnalysisRecordingConfig);

describe('<AutomatedAnalysisConfigDrawer />', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders default view correctly', async () => {
    renderWithServiceContext(
      <AutomatedAnalysisConfigDrawer
        drawerContent={drawerContent}
        isContentAbove={false}
        onCreate={() => {}}
        onError={() => {}}
      />
    );

    expect(screen.getByText(/drawer content/i)).toBeInTheDocument();
    const createRecording = screen.queryByRole('button', {
      name: /recording config dropdown/i,
    });
    const recordingActions = screen.queryByRole('button', {
      name: /recording actions/i,
    });
    expect(createRecording).toBeInTheDocument();
    expect(recordingActions).toBeInTheDocument();
  });

  it('opens drawer when button clicked', async () => {
    const { user } = renderWithServiceContext(
      <AutomatedAnalysisConfigDrawer
        drawerContent={drawerContent}
        isContentAbove={false}
        onCreate={() => {}}
        onError={() => {}}
      />
    );

    expect(screen.getByText(/drawer content/i)).toBeInTheDocument();
    const recordingActions = screen.getByRole('button', {
      name: /recording actions/i,
    });

    await user.click(recordingActions);
    expect(screen.getByText(/automatedanalysisconfigform/i)).toBeInTheDocument();
  });

  it('opens a dropdown when Create Recording is clicked', async () => {
    const onCreateFunction = jest.fn();
    const requestSpy = jest.spyOn(defaultServices.api, 'createRecording');
    const { user } = renderWithServiceContext(
      <AutomatedAnalysisConfigDrawer
        drawerContent={drawerContent}
        isContentAbove={false}
        onCreate={onCreateFunction}
        onError={() => {}}
      />
    );

    const createRecording = screen.getByRole('button', {
      name: /recording config dropdown/i,
    });

    expect(screen.queryByText(/automatedanalysisconfigform/i)).not.toBeInTheDocument();

    // Click Custom
    await user.click(createRecording);
    const customMenuItem = screen.getByRole('menuitem', { name: /custom/i });
    await user.click(customMenuItem);
    expect(screen.getByText(/automatedanalysisconfigform/i)).toBeInTheDocument();

    // Click Default
    await user.click(createRecording);
    const defaultMenuItem = screen.getByRole('menuitem', { name: /default/i });
    await user.click(defaultMenuItem);
    expect(requestSpy).toHaveBeenCalledTimes(1);
    expect(onCreateFunction).toHaveBeenCalledTimes(1);
  });
});
