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

import { ClickableAutomatedAnalysisLabel } from '@app/Dashboard/AutomatedAnalysis/ClickableAutomatedAnalysisLabel';
import { AutomatedAnalysisNameFilter } from '@app/Dashboard/AutomatedAnalysis/Filters/AutomatedAnalysisNameFilter';
import { CategorizedRuleEvaluations, RuleEvaluation } from '@app/Shared/Services/Report.service';
import { cleanup, screen, within } from '@testing-library/react';
import React from 'react';
import renderer, { act } from 'react-test-renderer';
import { renderDefault } from '../../Common';

const mockRuleEvaluation1: RuleEvaluation = {
  name: 'rule1',
  description: 'rule1 description',
  score: 100,
  topic: 'myTopic',
};

const mockRuleEvaluation2: RuleEvaluation = {
  name: 'rule2',
  description: 'rule2 description',
  score: 55,
  topic: 'fakeTopic',
};

const mockRuleEvaluation3: RuleEvaluation = {
  name: 'rule3',
  description: 'rule3 description',
  score: 0,
  topic: 'fakeTopic',
};

const mockNaRuleEvaluation: RuleEvaluation = {
  name: 'N/A rule',
  description: 'N/A description',
  score: -1,
  topic: 'fakeTopic',
};

const mockEvaluations1: RuleEvaluation[] = [mockRuleEvaluation1];

const mockEvaluations2: RuleEvaluation[] = [mockRuleEvaluation2, mockRuleEvaluation3, mockNaRuleEvaluation];

const mockCategorizedEvaluations: CategorizedRuleEvaluations[] = [
  [mockRuleEvaluation1.topic, mockEvaluations1],
  [mockRuleEvaluation2.topic, mockEvaluations2],
];

describe('<ClickableAutomatedAnalysisLabel />', () => {
  afterEach(cleanup);

  it('displays label', async () => {
    renderDefault(<ClickableAutomatedAnalysisLabel label={mockRuleEvaluation1} isSelected={false} />);

    expect(screen.getByText(mockRuleEvaluation1.name)).toBeInTheDocument();
  });

  it('displays popover when critical label is clicked', async () => {
    const { user } = renderDefault(<ClickableAutomatedAnalysisLabel label={mockRuleEvaluation1} isSelected={true} />);

    expect(screen.getByText(mockRuleEvaluation1.name)).toBeInTheDocument();

    await user.hover(screen.getByText(mockRuleEvaluation1.name));
    await user.click(screen.getByText(mockRuleEvaluation1.name));

    const closeButton = screen.getByRole('button', {
      name: /close/i,
    });

    expect(closeButton).toBeInTheDocument();

    expect(document.getElementsByClassName('pf-m-danger').item(0)).toBeInTheDocument();
    expect(screen.getByText(mockRuleEvaluation1.description)).toBeInTheDocument();
    expect(screen.getByText(String(mockRuleEvaluation1.score) + '.0')).toBeInTheDocument();
    const heading = screen.getByRole('heading', {
      name: /danger rule1/i,
    });
    expect(within(heading).getByText(mockRuleEvaluation1.name)).toBeInTheDocument();

    await user.click(screen.getAllByText(mockRuleEvaluation1.name)[0]);

    expect(screen.queryByText(mockRuleEvaluation1.description)).not.toBeInTheDocument();
    expect(screen.queryByText(String(mockRuleEvaluation1.score) + '.0')).not.toBeInTheDocument();
    expect(closeButton).not.toBeInTheDocument();
  });

  it('displays popover when warning label is clicked', async () => {
    const { user } = renderDefault(<ClickableAutomatedAnalysisLabel label={mockRuleEvaluation2} isSelected={true} />);

    expect(screen.getByText(mockRuleEvaluation2.name)).toBeInTheDocument();

    await user.hover(screen.getByText(mockRuleEvaluation2.name));
    await user.click(screen.getByText(mockRuleEvaluation2.name));

    const closeButton = screen.getByRole('button', {
      name: /close/i,
    });

    expect(closeButton).toBeInTheDocument();

    expect(document.getElementsByClassName('pf-m-warning').item(0)).toBeInTheDocument();
    expect(screen.getByText(mockRuleEvaluation2.description)).toBeInTheDocument();
    expect(screen.getByText(String(mockRuleEvaluation2.score) + '.0')).toBeInTheDocument();
    const heading = screen.getByRole('heading', {
      name: /warning rule2/i,
    });
    expect(within(heading).getByText(mockRuleEvaluation2.name)).toBeInTheDocument();

    await user.click(screen.getAllByText(mockRuleEvaluation2.name)[0]);

    expect(screen.queryByText(mockRuleEvaluation2.description)).not.toBeInTheDocument();
    expect(screen.queryByText(String(mockRuleEvaluation2.score) + '.0')).not.toBeInTheDocument();
    expect(closeButton).not.toBeInTheDocument();
  });

  it('displays popover when ok label is clicked', async () => {
    const { user } = renderDefault(<ClickableAutomatedAnalysisLabel label={mockRuleEvaluation3} isSelected={true} />);

    expect(screen.getByText(mockRuleEvaluation3.name)).toBeInTheDocument();

    await user.hover(screen.getByText(mockRuleEvaluation3.name));
    await user.click(screen.getByText(mockRuleEvaluation3.name));

    const closeButton = screen.getByRole('button', {
      name: /close/i,
    });

    expect(closeButton).toBeInTheDocument();

    expect(document.getElementsByClassName('pf-m-success').item(0)).toBeInTheDocument();
    expect(screen.getByText(mockRuleEvaluation3.description)).toBeInTheDocument();
    expect(screen.getByText(String(mockRuleEvaluation3.score) + '.0')).toBeInTheDocument();
    const heading = screen.getByRole('heading', {
      name: /success rule3/i,
    });
    expect(within(heading).getByText(mockRuleEvaluation3.name)).toBeInTheDocument();

    await user.click(screen.getAllByText(mockRuleEvaluation3.name)[0]);

    expect(screen.queryByText(mockRuleEvaluation3.description)).not.toBeInTheDocument();
    expect(screen.queryByText(String(mockRuleEvaluation3.score) + '.0')).not.toBeInTheDocument();
    expect(closeButton).not.toBeInTheDocument();
  });

  it('displays popover when N/A label is clicked', async () => {
    const { user } = renderDefault(<ClickableAutomatedAnalysisLabel label={mockNaRuleEvaluation} isSelected={true} />);

    expect(screen.getByText(mockNaRuleEvaluation.name)).toBeInTheDocument();

    await user.hover(screen.getByText(mockNaRuleEvaluation.name));
    await user.click(screen.getByText(mockNaRuleEvaluation.name));

    const closeButton = screen.getByRole('button', {
      name: /close/i,
    });

    expect(closeButton).toBeInTheDocument();

    expect(document.getElementsByClassName('pf-m-default').item(0)).toBeInTheDocument();
    expect(screen.getByText(mockNaRuleEvaluation.description)).toBeInTheDocument();
    expect(screen.getByText('N/A')).toBeInTheDocument();
    const heading = screen.getByRole('heading', {
      name: /default /i,
    });
    expect(within(heading).getByText(mockNaRuleEvaluation.name)).toBeInTheDocument();

    await user.click(screen.getAllByText(mockNaRuleEvaluation.name)[0]);

    expect(screen.queryByText(mockNaRuleEvaluation.description)).not.toBeInTheDocument();
    expect(screen.queryByText('N/A')).not.toBeInTheDocument();
    expect(closeButton).not.toBeInTheDocument();
  });
});
