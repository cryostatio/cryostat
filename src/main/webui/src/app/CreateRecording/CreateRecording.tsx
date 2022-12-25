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
import { TargetView } from '@app/TargetView/TargetView';
import { Card, CardBody, Tab, Tabs } from '@patternfly/react-core';
import { StaticContext } from 'react-router';
import { RouteComponentProps, withRouter } from 'react-router-dom';
import { CustomRecordingForm } from './CustomRecordingForm';
import { SnapshotRecordingForm } from './SnapshotRecordingForm';
import { TemplateType } from '@app/Shared/Services/Api.service';

export interface CreateRecordingProps {
  templateName?: string;
  templateType?: TemplateType;
}

export interface EventTemplate {
  name: string;
  description: string;
  provider: string;
  type: TemplateType;
}

const Comp: React.FunctionComponent<RouteComponentProps<{}, StaticContext, CreateRecordingProps>> = (props) => {
  const [activeTab, setActiveTab] = React.useState(0);

  const onTabSelect = React.useCallback((evt, idx) => setActiveTab(Number(idx)), [setActiveTab]);

  const prefilled = React.useMemo(
    () => ({
      templateName: props.location?.state?.templateName,
      templateType: props.location?.state?.templateType,
    }),
    [props.location]
  );

  return (
    <TargetView pageTitle="Create Recording" breadcrumbs={[{ title: 'Recordings', path: '/recordings' }]}>
      <Card>
        <CardBody>
          <Tabs activeKey={activeTab} onSelect={onTabSelect}>
            <Tab eventKey={0} title="Custom Flight Recording">
              <CustomRecordingForm prefilled={prefilled} />
            </Tab>
            <Tab eventKey={1} title="Snapshot Recording">
              <SnapshotRecordingForm />
            </Tab>
          </Tabs>
        </CardBody>
      </Card>
    </TargetView>
  );
};

export const CreateRecording = withRouter(Comp);
export default CreateRecording;
