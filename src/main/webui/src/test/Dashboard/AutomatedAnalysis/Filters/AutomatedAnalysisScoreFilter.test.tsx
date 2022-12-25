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

import { AutomatedAnalysisScoreFilter } from '@app/Dashboard/AutomatedAnalysis/Filters/AutomatedAnalysisScoreFilter';
import {
  emptyActiveRecordingFilters,
  emptyArchivedRecordingFilters,
  TargetRecordingFilters,
} from '@app/Shared/Redux/Filters/RecordingFilterSlice';
import { RootState } from '@app/Shared/Redux/ReduxStore';
import { cleanup, screen } from '@testing-library/react';
import React from 'react';
import { renderWithReduxStore } from '../../../Common';

const mockTargetConnectUrl = 'service:jmx:rmi://someUrl';

const onlyShowingText = (value) => `Only showing analysis results with severity scores ≥ ${value}:`;

describe('<AutomatedAnalysisScoreFilter />', () => {
  let preloadedState: RootState;

  beforeEach(() => {
    preloadedState = {
      dashboardConfigs: {
        list: [],
      },
      recordingFilters: {
        list: [
          {
            target: mockTargetConnectUrl,
            active: {
              selectedCategory: 'Labels',
              filters: emptyActiveRecordingFilters,
            },
            archived: {
              selectedCategory: 'Name',
              filters: emptyArchivedRecordingFilters,
            },
          } as TargetRecordingFilters,
        ],
      },
      automatedAnalysisFilters: {
        state: {
          targetFilters: [],
          globalFilters: {
            filters: {
              Score: 100,
            },
          },
        },
      },
    };
  });

  afterEach(cleanup);

  // it('renders correctly', async () => {
  //   let tree;
  //   await act(async () => {
  //     tree = renderer.create(
  //       <Provider store={setupStore(preloadedState)}>
  //         <AutomatedAnalysisScoreFilter targetConnectUrl={mockTargetConnectUrl} />
  //       </Provider>
  //     );
  //   });
  //   expect(tree.toJSON()).toMatchSnapshot();
  // });

  it('resets to 0 and 100 when clicking reset buttons', async () => {
    const { user } = renderWithReduxStore(<AutomatedAnalysisScoreFilter targetConnectUrl={mockTargetConnectUrl} />, {
      preloadState: preloadedState,
    });
    const resetTo0Button = screen.getByRole('button', {
      name: /reset score to 0/i,
    });
    const resetTo100Button = screen.getByRole('button', {
      name: /reset score to 100/i,
    });
    const sliderValue = screen.getByRole('spinbutton', {
      name: /slider value input/i,
    });

    expect(sliderValue).toHaveValue(100);

    await user.click(resetTo0Button);
    expect(sliderValue).toHaveValue(0);

    await user.click(resetTo100Button);
    expect(sliderValue).toHaveValue(100);
  });

  it('responds to score filter changes', async () => {
    const { user } = renderWithReduxStore(<AutomatedAnalysisScoreFilter targetConnectUrl={mockTargetConnectUrl} />, {
      preloadState: preloadedState,
    });
    const sliderValue = screen.getByRole('spinbutton', {
      name: /slider value input/i,
    });

    expect(sliderValue).toHaveValue(100);
    expect(
      document.getElementsByClassName('automated-analysis-score-filter-slider-critical').item(0)
    ).toBeInTheDocument();
    expect(screen.getByText(onlyShowingText(100))).toBeInTheDocument();

    await user.clear(sliderValue);
    await user.keyboard('{Enter}');
    expect(sliderValue).toHaveValue(0);
    expect(document.getElementsByClassName('automated-analysis-score-filter-slider-ok').item(0)).toBeInTheDocument();
    expect(screen.getByText(onlyShowingText(0))).toBeInTheDocument();

    await user.type(sliderValue, '50');
    await user.keyboard('{Enter}');
    expect(sliderValue).toHaveValue(50);
    expect(
      document.getElementsByClassName('automated-analysis-score-filter-slider-warning').item(0)
    ).toBeInTheDocument();
    expect(screen.getByText(onlyShowingText(50))).toBeInTheDocument();

    await user.type(sliderValue, '500');
    await user.keyboard('{Enter}');
    expect(sliderValue).toHaveValue(100);
    expect(
      document.getElementsByClassName('automated-analysis-score-filter-slider-critical').item(0)
    ).toBeInTheDocument();
    expect(screen.getByText(onlyShowingText(100))).toBeInTheDocument();
  });
});
