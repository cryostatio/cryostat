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
import { ServiceContext } from '@app/Shared/Services/Services';
import { TargetView } from '@app/TargetView/TargetView';
import { Card, CardBody, CardTitle, Tab, Tabs, TabTitleText } from '@patternfly/react-core';
import { ActiveRecordingsTable } from './ActiveRecordingsTable';
import { ArchivedRecordingsTable } from './ArchivedRecordingsTable';
import { useSubscriptions } from '@app/utils/useSubscriptions';

export interface RecordingsProps {}

export const Recordings: React.FunctionComponent<RecordingsProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const [activeTab, setActiveTab] = React.useState(0);
  const [archiveEnabled, setArchiveEnabled] = React.useState(false);
  const addSubscription = useSubscriptions();

  React.useEffect(() => {
    addSubscription(context.api.isArchiveEnabled().subscribe(setArchiveEnabled));
  }, [context.api, addSubscription, setArchiveEnabled]);

  const onTabSelect = React.useCallback((_, idx) => setActiveTab(Number(idx)), [setActiveTab]);

  const cardBody = React.useMemo(() => {
    return archiveEnabled ? (
      <Tabs id="recordings" activeKey={activeTab} onSelect={onTabSelect}>
        <Tab id="active-recordings" eventKey={0} title={<TabTitleText>Active Recordings</TabTitleText>}>
          <ActiveRecordingsTable archiveEnabled={true} />
        </Tab>
        <Tab id="archived-recordings" eventKey={1} title={<TabTitleText>Archived Recordings</TabTitleText>}>
          <ArchivedRecordingsTable target={context.target.target()} isUploadsTable={false} isNestedTable={false} />
        </Tab>
      </Tabs>
    ) : (
      <>
        <CardTitle>Active Recordings</CardTitle>
        <ActiveRecordingsTable archiveEnabled={false} />
      </>
    );
  }, [archiveEnabled, activeTab, onTabSelect, context.target]);

  return (
    <TargetView pageTitle="Recordings">
      <Card>
        <CardBody>{cardBody}</CardBody>
      </Card>
    </TargetView>
  );
};

export default Recordings;
