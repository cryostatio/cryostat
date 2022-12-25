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

import { AutomatedAnalysisNameFilter } from '@app/Dashboard/AutomatedAnalysis/Filters/AutomatedAnalysisNameFilter';
import { CategorizedRuleEvaluations, RuleEvaluation } from '@app/Shared/Services/Report.service';
import { cleanup, screen, within } from '@testing-library/react';
import React from 'react';
import renderer, { act } from 'react-test-renderer';
import { renderDefault } from '../../../Common';

const mockRuleEvaluation1: RuleEvaluation = {
  name: 'rule1',
  description: 'rule1 description',
  score: 100,
  topic: 'myTopic',
};

const mockRuleEvaluation2: RuleEvaluation = {
  name: 'rule2',
  description: 'rule2 description',
  score: 0,
  topic: 'fakeTopic',
};

const mockRuleEvaluation3: RuleEvaluation = {
  name: 'rule3',
  description: 'rule3 description',
  score: 55,
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

const allMockEvaluations = mockEvaluations1.concat(mockEvaluations2);

const onNameInput = jest.fn((nameInput) => {
  /**Do nothing. Used for checking renders */
});

describe('<AutomatedAnalysisNameFilter />', () => {
  let emptyFilteredNames: string[];
  let filteredNames: string[];

  beforeEach(() => {
    emptyFilteredNames = [];
    filteredNames = [mockRuleEvaluation1.name];
  });

  afterEach(cleanup);

  it('renders correctly', async () => {
    let tree;
    await act(async () => {
      tree = renderer.create(
        <AutomatedAnalysisNameFilter
          evaluations={mockCategorizedEvaluations}
          onSubmit={onNameInput}
          filteredNames={emptyFilteredNames}
        />
      );
    });
    expect(tree.toJSON()).toMatchSnapshot();
  });

  it('display name selections when text input is clicked', async () => {
    const { user } = renderDefault(
      <AutomatedAnalysisNameFilter
        evaluations={mockCategorizedEvaluations}
        onSubmit={onNameInput}
        filteredNames={emptyFilteredNames}
      />
    );
    const nameInput = screen.getByLabelText('Filter by name...');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    await user.click(nameInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by name' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    allMockEvaluations.forEach((r) => {
      const option = within(selectMenu).getByText(r.name);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });
  });

  it('display name selections when dropdown arrow is clicked', async () => {
    const { user } = renderDefault(
      <AutomatedAnalysisNameFilter
        evaluations={mockCategorizedEvaluations}
        onSubmit={onNameInput}
        filteredNames={emptyFilteredNames}
      />
    );
    const dropDownArrow = screen.getByRole('button', { name: 'Options menu' });
    expect(dropDownArrow).toBeInTheDocument();
    expect(dropDownArrow).toBeVisible();

    await user.click(dropDownArrow);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by name' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    allMockEvaluations.forEach((r) => {
      const option = within(selectMenu).getByText(r.name);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });
  });

  it('should close selection menu when toggled with dropdown arrow', async () => {
    const { user } = renderDefault(
      <AutomatedAnalysisNameFilter
        evaluations={mockCategorizedEvaluations}
        onSubmit={onNameInput}
        filteredNames={emptyFilteredNames}
      />
    );

    const dropDownArrow = screen.getByRole('button', { name: 'Options menu' });
    expect(dropDownArrow).toBeInTheDocument();
    expect(dropDownArrow).toBeVisible();

    await user.click(dropDownArrow);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by name' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    allMockEvaluations.forEach((r) => {
      const option = within(selectMenu).getByText(r.name);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.click(dropDownArrow);
    expect(selectMenu).not.toBeInTheDocument();
    expect(selectMenu).not.toBeVisible();
  });

  it('should close selection menu when toggled with text input', async () => {
    const { user } = renderDefault(
      <AutomatedAnalysisNameFilter
        evaluations={mockCategorizedEvaluations}
        onSubmit={onNameInput}
        filteredNames={emptyFilteredNames}
      />
    );

    const nameInput = screen.getByLabelText('Filter by name...');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    await user.click(nameInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by name' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    allMockEvaluations.forEach((r) => {
      const option = within(selectMenu).getByText(r.name);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.click(nameInput);
    expect(selectMenu).not.toBeInTheDocument();
    expect(selectMenu).not.toBeVisible();
  });

  it('should not display selected names', async () => {
    const { user } = renderDefault(
      <AutomatedAnalysisNameFilter
        evaluations={mockCategorizedEvaluations}
        onSubmit={onNameInput}
        filteredNames={filteredNames}
      />
    );

    const nameInput = screen.getByLabelText('Filter by name...');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    await user.click(nameInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by name' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    const notToShowName = within(selectMenu).queryByText(mockRuleEvaluation1.name);
    expect(notToShowName).not.toBeInTheDocument();
  });

  it('should select a name when a name option is clicked', async () => {
    const submitNameInput = jest.fn((nameInput) => emptyFilteredNames.push(nameInput));

    const { user } = renderDefault(
      <AutomatedAnalysisNameFilter
        evaluations={mockCategorizedEvaluations}
        onSubmit={submitNameInput}
        filteredNames={emptyFilteredNames}
      />
    );

    const nameInput = screen.getByLabelText('Filter by name...');
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).toBeVisible();

    await user.click(nameInput);

    const selectMenu = await screen.findByRole('listbox', { name: 'Filter by name' });
    expect(selectMenu).toBeInTheDocument();
    expect(selectMenu).toBeVisible();

    allMockEvaluations.forEach((r) => {
      const option = within(selectMenu).getByText(r.name);
      expect(option).toBeInTheDocument();
      expect(option).toBeVisible();
    });

    await user.selectOptions(selectMenu, mockRuleEvaluation1.name);

    expect(submitNameInput).toBeCalledTimes(1);
    expect(submitNameInput).toBeCalledWith(mockRuleEvaluation1.name);
    expect(emptyFilteredNames).toStrictEqual([mockRuleEvaluation1.name]);
  });
});
