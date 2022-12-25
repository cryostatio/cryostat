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
import { cleanup, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import { Button, Toolbar, ToolbarContent, ToolbarGroup, ToolbarItem } from '@patternfly/react-core';
import { Tbody, Tr, Td } from '@patternfly/react-table';
import { RecordingsTable } from '@app/Recordings/RecordingsTable';
import { renderDefault } from '../Common';

const FakeToolbar = () => {
  return (
    <Toolbar>
      <ToolbarContent>
        <ToolbarGroup>
          <ToolbarItem>
            <Button>Fake Button</Button>
          </ToolbarItem>
        </ToolbarGroup>
      </ToolbarContent>
    </Toolbar>
  );
};

const fakeTableTitle = 'Test Table';

const fakeTableColumns: string[] = ['Column 1', 'Column 2'];

const fakeTableRows = (
  <Tbody>
    <Tr key="fake-row-1">
      <Td></Td>
      <Td key="data-1">Row 1: Fake Column 1 Data</Td>
      <Td key="data-2">Row 1: Fake Column 2 Data</Td>
    </Tr>
    <Tr key="fake-row-2">
      <Td key="data-3">Row 2: Fake Column 1 Data</Td>
      <Td key="data-4">Row 2: Fake Column 2 Data</Td>
      <Td></Td>
    </Tr>
  </Tbody>
);

const mockHeaderCheckCallback = jest.fn((event, checked) => {
  /* do nothing */
});

describe('<RecordingsTable />', () => {
  afterEach(cleanup);

  it('correctly displays the toolbar prop', async () => {
    renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={false}
        isNestedTable={false}
        errorMessage=""
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    expect(screen.getByText('Fake Button')).toBeInTheDocument();
  });

  it('handles a non-nested table', async () => {
    renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={false}
        isNestedTable={false}
        errorMessage=""
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    expect(screen.getByLabelText('Test Table')).toBeInTheDocument();
    expect(screen.getByText(fakeTableColumns[0])).toBeInTheDocument();
    expect(screen.getByText(fakeTableColumns[1])).toBeInTheDocument();
    expect(screen.getByText('Row 1: Fake Column 1 Data')).toBeInTheDocument();
    expect(screen.getByText('Row 1: Fake Column 2 Data')).toBeInTheDocument();
    expect(screen.getByText('Row 2: Fake Column 1 Data')).toBeInTheDocument();
    expect(screen.getByText('Row 2: Fake Column 2 Data')).toBeInTheDocument();
  });

  it('handles a nested table with sticky header', async () => {
    const { container } = renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={false}
        isNestedTable={true}
        errorMessage=""
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    const table = screen.getByLabelText('Test Table');
    expect(table).toHaveClass('pf-m-sticky-header');
    expect(container.getElementsByClassName('pf-c-scroll-outer-wrapper').length).toBe(1);
    expect(container.getElementsByClassName('pf-c-scroll-inner-wrapper').length).toBe(1);
  });

  it('handles the case where an error occurs', async () => {
    renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={false}
        isNestedTable={false}
        errorMessage="some error"
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    expect(screen.getByText('some error')).toBeInTheDocument();
  });

  it('renders correctly when table data is still loading', async () => {
    renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={true}
        isNestedTable={false}
        errorMessage=""
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    const spinner = screen.getByRole('progressbar');
    expect(spinner).toHaveAttribute('aria-valuetext', 'Loading...');
  });

  it('handles an empty table', async () => {
    renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={true}
        isLoading={false}
        isNestedTable={false}
        errorMessage=""
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    expect(screen.getByText(`No ${fakeTableTitle}`)).toBeInTheDocument();
  });

  it('handles the header checkbox callback correctly', async () => {
    const { user } = renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={false}
        isNestedTable={false}
        errorMessage=""
        isHeaderChecked={false}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    let headerCheckAll = screen.getByLabelText('Select all rows');
    expect(headerCheckAll).not.toHaveAttribute('checked');
    await user.click(headerCheckAll);
    expect(mockHeaderCheckCallback).toHaveBeenCalledTimes(1);
  });

  it('renders the header checkbox as checked if props.isHeaderChecked == true', async () => {
    renderDefault(
      <RecordingsTable
        toolbar={<FakeToolbar />}
        tableColumns={fakeTableColumns}
        tableTitle={fakeTableTitle}
        isEmpty={false}
        isLoading={false}
        isNestedTable={false}
        errorMessage=""
        isHeaderChecked={true}
        onHeaderCheck={mockHeaderCheckCallback}
      >
        {fakeTableRows}
      </RecordingsTable>
    );

    let headerCheckAll = screen.getByLabelText('Select all rows');
    expect(headerCheckAll).toHaveAttribute('checked');
  });
});
