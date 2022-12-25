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

import { AutomatedAnalysisConfigForm } from '@app/Dashboard/AutomatedAnalysis/AutomatedAnalysisConfigForm';
import {
  AutomatedAnalysisRecordingConfig,
  defaultAutomatedAnalysisRecordingConfig,
} from '@app/Shared/Services/Api.service';
import { ServiceContext } from '@app/Shared/Services/Services';
import { NO_TARGET, Target } from '@app/Shared/Services/Target.service';
import { TargetSelect } from '@app/TargetSelect/TargetSelect';
import { TargetView } from '@app/TargetView/TargetView';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import {
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  ExpandableSection,
  Stack,
  StackItem,
  Title,
} from '@patternfly/react-core';
import * as React from 'react';
import { UserSetting } from './Settings';

const Component = () => {
  const context = React.useContext(ServiceContext);

  const [config, setConfig] = React.useState<AutomatedAnalysisRecordingConfig>(
    context.settings.automatedAnalysisRecordingConfig()
  );
  const [expanded, setExpanded] = React.useState(false);

  const onSave = React.useCallback(() => {
    const newConfig = context.settings.automatedAnalysisRecordingConfig();
    setConfig(newConfig);
  }, [context.settings, context.settings.automatedAnalysisRecordingConfig, setConfig]);

  return (
    <Stack hasGutter>
      <StackItem>
        <TargetSelect simple />
      </StackItem>
      <StackItem>
        <Title headingLevel="h2" size="lg">
          Current configuration
        </Title>
      </StackItem>
      <StackItem>
        <DescriptionList columnModifier={{ lg: '3Col' }}>
          <DescriptionListGroup>
            <DescriptionListTerm>Template</DescriptionListTerm>
            <DescriptionListDescription>{config.template}</DescriptionListDescription>
          </DescriptionListGroup>
          <DescriptionListGroup>
            <DescriptionListTerm>Max Size (B)</DescriptionListTerm>
            <DescriptionListDescription>{config.maxSize}</DescriptionListDescription>
          </DescriptionListGroup>
          <DescriptionListGroup>
            <DescriptionListTerm>Max Age (s)</DescriptionListTerm>
            <DescriptionListDescription>{config.maxAge}</DescriptionListDescription>
          </DescriptionListGroup>
        </DescriptionList>
      </StackItem>
      <ExpandableSection
        toggleText={expanded ? 'Hide configuration edit' : 'Show configuration edit'}
        onToggle={setExpanded}
        isExpanded={expanded}
      >
        <AutomatedAnalysisConfigForm onSave={onSave} isSettingsForm={true} />
      </ExpandableSection>
    </Stack>
  );
};

export const AutomatedAnalysisConfig: UserSetting = {
  title: 'Automated Analysis Recording Configuration',
  description:
    'Set the recording configuration for automated analysis recordings. You may want smaller or larger values for max-age and max-size depending on how recent you want events to be recorded from the analysis.',
  content: Component,
};
