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
import {
  ActionGroup,
  Button,
  Card,
  CardBody,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  Grid,
  GridItem,
  Split,
  SplitItem,
  Switch,
  Text,
  TextInput,
  TextVariants,
  ValidatedOptions,
} from '@patternfly/react-core';
import { useHistory, withRouter } from 'react-router-dom';
import { iif } from 'rxjs';
import { filter, first, mergeMap, toArray } from 'rxjs/operators';
import { ServiceContext } from '@app/Shared/Services/Services';
import { NotificationsContext } from '@app/Notifications/Notifications';
import { BreadcrumbPage, BreadcrumbTrail } from '@app/BreadcrumbPage/BreadcrumbPage';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { EventTemplate } from '@app/CreateRecording/CreateRecording';
import { MatchExpressionEvaluator } from '@app/Shared/MatchExpressionEvaluator';
import { SelectTemplateSelectorForm } from '@app/TemplateSelector/SelectTemplateSelectorForm';
import { NO_TARGET } from '@app/Shared/Services/Target.service';
import { authFailMessage, ErrorView, isAuthFail } from '@app/ErrorView/ErrorView';
import { LoadingPropsType } from '@app/Shared/ProgressIndicator';
import { Rule } from './Rules';
import { TemplateType } from '@app/Shared/Services/Api.service';

// FIXME check if this is correct/matches backend name validation
export const RuleNamePattern = /^[\w_]+$/;

const Comp: React.FunctionComponent<{}> = () => {
  const context = React.useContext(ServiceContext);
  const notifications = React.useContext(NotificationsContext);
  const history = useHistory();
  const addSubscription = useSubscriptions();

  const [name, setName] = React.useState('');
  const [nameValid, setNameValid] = React.useState(ValidatedOptions.default);
  const [description, setDescription] = React.useState('');
  const [enabled, setEnabled] = React.useState(true);
  const [matchExpression, setMatchExpression] = React.useState('');
  const [matchExpressionValid, setMatchExpressionValid] = React.useState(ValidatedOptions.default);
  const [templates, setTemplates] = React.useState([] as EventTemplate[]);
  const [templateName, setTemplateName] = React.useState<string | undefined>(undefined);
  const [templateType, setTemplateType] = React.useState<TemplateType | undefined>(undefined);
  const [maxAge, setMaxAge] = React.useState(0);
  const [maxAgeUnits, setMaxAgeUnits] = React.useState(1);
  const [maxSize, setMaxSize] = React.useState(0);
  const [maxSizeUnits, setMaxSizeUnits] = React.useState(1);
  const [archivalPeriod, setArchivalPeriod] = React.useState(0);
  const [archivalPeriodUnits, setArchivalPeriodUnits] = React.useState(1);
  const [initialDelay, setInitialDelay] = React.useState(0);
  const [initialDelayUnits, setInitialDelayUnits] = React.useState(1);
  const [preservedArchives, setPreservedArchives] = React.useState(0);
  const [errorMessage, setErrorMessage] = React.useState('');
  const [loading, setLoading] = React.useState(false);

  const handleNameChange = React.useCallback(
    (name) => {
      setNameValid(RuleNamePattern.test(name) ? ValidatedOptions.success : ValidatedOptions.error);
      setName(name);
    },
    [setNameValid, setName]
  );

  const eventSpecifierString = React.useMemo(() => {
    var str = '';
    if (templateName) {
      str += `template=${templateName}`;
    }
    if (templateType) {
      str += `,type=${templateType}`;
    }
    return str;
  }, [templateName]);

  const handleTemplateChange = React.useCallback(
    (templateName?: string, templateType?: TemplateType) => {
      setTemplateName(templateName);
      setTemplateType(templateType);
    },
    [setTemplateName, setTemplateType]
  );

  const handleMaxAgeChange = React.useCallback((maxAge) => setMaxAge(Number(maxAge)), [setMaxAge]);

  const handleMaxAgeUnitChange = React.useCallback(
    (maxAgeUnit) => setMaxAgeUnits(Number(maxAgeUnit)),
    [setMaxAgeUnits]
  );

  const handleMaxSizeChange = React.useCallback((maxSize) => setMaxSize(Number(maxSize)), [setMaxSize]);

  const handleMaxSizeUnitChange = React.useCallback(
    (maxSizeUnit) => setMaxSizeUnits(Number(maxSizeUnit)),
    [setMaxSizeUnits]
  );

  const handleArchivalPeriodChange = React.useCallback(
    (archivalPeriod) => setArchivalPeriod(Number(archivalPeriod)),
    [setArchivalPeriod]
  );

  const handleArchivalPeriodUnitsChange = React.useCallback(
    (evt) => setArchivalPeriodUnits(Number(evt)),
    [setArchivalPeriodUnits]
  );

  const handleInitialDelayChange = React.useCallback(
    (initialDelay) => setInitialDelay(Number(initialDelay)),
    [setInitialDelay]
  );

  const handleInitialDelayUnitsChanged = React.useCallback(
    (initialDelayUnit) => setInitialDelayUnits(Number(initialDelayUnit)),
    [setInitialDelayUnits]
  );

  const handlePreservedArchivesChange = React.useCallback(
    (preservedArchives) => setPreservedArchives(Number(preservedArchives)),
    [setPreservedArchives]
  );

  const handleError = React.useCallback((error) => setErrorMessage(error.message), [setErrorMessage]);

  const handleSubmit = React.useCallback((): void => {
    setLoading(true);
    const notificationMessages: string[] = [];
    if (nameValid !== ValidatedOptions.success) {
      notificationMessages.push(`Rule name ${name} is invalid`);
    }
    if (notificationMessages.length > 0) {
      const message = notificationMessages.join('. ').trim() + '.';
      notifications.warning('Invalid form data', message);
      return;
    }
    const rule: Rule = {
      name,
      description,
      enabled,
      matchExpression,
      eventSpecifier: eventSpecifierString,
      archivalPeriodSeconds: archivalPeriod * archivalPeriodUnits,
      initialDelaySeconds: initialDelay * initialDelayUnits,
      preservedArchives,
      maxAgeSeconds: maxAge * maxAgeUnits,
      maxSizeBytes: maxSize * maxSizeUnits,
    };
    addSubscription(
      context.api.createRule(rule).subscribe((success) => {
        setLoading(false);
        if (success) {
          history.push('/rules');
        }
      })
    );
  }, [
    setLoading,
    addSubscription,
    context,
    context.api,
    history,
    name,
    nameValid,
    description,
    enabled,
    matchExpression,
    eventSpecifierString,
    archivalPeriod,
    archivalPeriodUnits,
    initialDelay,
    initialDelayUnits,
    preservedArchives,
    maxAge,
    maxAgeUnits,
    maxSize,
    maxSizeUnits,
  ]);

  const handleTemplateList = React.useCallback(
    (templates: EventTemplate[]) => {
      setTemplates(templates);
      setErrorMessage('');
    },
    [setTemplateName, setErrorMessage]
  );

  const refreshTemplateList = React.useCallback(() => {
    addSubscription(
      context.target
        .target()
        .pipe(
          mergeMap((target) =>
            iif(
              () => target !== NO_TARGET,
              context.api.doGet<EventTemplate[]>(`targets/${encodeURIComponent(target.connectUrl)}/templates`),
              context.api.doGet<EventTemplate[]>(`targets/localhost:0/templates`).pipe(
                mergeMap((x) => x),
                filter((template) => template.provider !== 'Cryostat' || template.name !== 'Cryostat'),
                toArray()
              )
            )
          ),
          first()
        )
        .subscribe({
          next: handleTemplateList,
          error: handleError,
        })
    );
  }, [addSubscription, context.api, context.target, setTemplates]);

  React.useEffect(() => {
    addSubscription(context.target.target().subscribe(refreshTemplateList));
  }, [addSubscription, context.target, refreshTemplateList]);

  React.useEffect(() => {
    addSubscription(
      context.target.authFailure().subscribe(() => {
        setErrorMessage(authFailMessage);
      })
    );
  }, [context.target, authFailMessage, setErrorMessage, addSubscription]);

  const breadcrumbs: BreadcrumbTrail[] = [
    {
      title: 'Automated Rules',
      path: '/rules',
    },
  ];

  const createButtonLoadingProps = React.useMemo(
    () =>
      ({
        spinnerAriaValueText: 'Creating',
        spinnerAriaLabel: 'creating-automated-rule',
        isLoading: loading,
      } as LoadingPropsType),
    [loading]
  );

  const selectedSpecifier = React.useMemo(() => {
    if (templateName && templateType) {
      return `${templateName},${templateType}`;
    }
    return '';
  }, [templateName, templateType]);

  const authRetry = React.useCallback(() => {
    context.target.setAuthRetry();
  }, [context.target, context.target.setAuthRetry]);

  return (
    <BreadcrumbPage pageTitle="Create" breadcrumbs={breadcrumbs}>
      <Grid hasGutter>
        <GridItem xl={7}>
          {errorMessage ? (
            <ErrorView
              title={'Error retrieving event templates'}
              message={errorMessage}
              retry={isAuthFail(errorMessage) ? authRetry : undefined}
            />
          ) : (
            <Card>
              <CardBody>
                <Form>
                  <Text component={TextVariants.small}>
                    Automated Rules are configurations that instruct Cryostat to create JDK Flight Recordings on
                    matching target JVM applications. Each Automated Rule specifies parameters for which Event Template
                    to use, how much data should be kept in the application recording buffer, and how frequently
                    Cryostat should copy the application recording buffer into Cryostat's own archived storage.
                  </Text>
                  <FormGroup
                    label="Name"
                    isRequired
                    fieldId="rule-name"
                    helperText="Enter a rule name."
                    helperTextInvalid="A rule name may only contain letters, numbers, and underscores."
                    validated={nameValid}
                  >
                    <TextInput
                      value={name}
                      isDisabled={loading}
                      isRequired
                      type="text"
                      id="rule-name"
                      aria-describedby="rule-name-helper"
                      onChange={handleNameChange}
                      validated={nameValid}
                    />
                  </FormGroup>
                  <FormGroup
                    label="Description"
                    fieldId="rule-description"
                    helperText="Enter a rule description. This is only used for display purposes to aid in identifying rules and their intentions."
                  >
                    <TextInput
                      value={description}
                      isDisabled={loading}
                      type="text"
                      id="rule-description"
                      aria-describedby="rule-description-helper"
                      onChange={setDescription}
                    />
                  </FormGroup>
                  <FormGroup
                    label="Match Expression"
                    isRequired
                    fieldId="rule-matchexpr"
                    helperText={`
                      Enter a match expression. This is a Java-like code snippet that is evaluated against each target
                      application to determine whether the rule should be applied. Select a target from the dropdown
                      on the right to view the context object available within the match expression context and test
                      if the expression matches.`}
                    helperTextInvalid="Invalid Match Expression."
                    validated={matchExpressionValid}
                  >
                    <TextInput
                      value={matchExpression}
                      isDisabled={loading}
                      isRequired
                      type="text"
                      id="rule-matchexpr"
                      aria-describedby="rule-matchexpr-helper"
                      onChange={setMatchExpression}
                      validated={matchExpressionValid}
                    />
                  </FormGroup>
                  <FormGroup
                    label="Enabled"
                    isRequired
                    fieldId="rule-enabled"
                    helperText={`Rules take effect when created if enabled and will be matched against all
                  discovered target applications immediately. When new target applications appear they are
                  checked against all enabled rules. Disabled rules have no effect but are available to be
                  enabled in the future.`}
                  >
                    <Switch
                      id="rule-enabled"
                      isDisabled={loading}
                      aria-label="Apply this rule to matching targets"
                      isChecked={enabled}
                      onChange={setEnabled}
                    />
                  </FormGroup>
                  <FormGroup
                    label="Template"
                    isRequired
                    fieldId="recording-template"
                    validated={!templateName ? ValidatedOptions.default : ValidatedOptions.success}
                    helperText="The Event Template to be applied by this Rule against matching target applications."
                    helperTextInvalid="A Template must be selected"
                  >
                    <SelectTemplateSelectorForm
                      selected={selectedSpecifier}
                      disabled={loading}
                      validated={!templateName ? ValidatedOptions.default : ValidatedOptions.success}
                      templates={templates}
                      onSelect={handleTemplateChange}
                    />
                  </FormGroup>
                  <FormGroup
                    label="Maximum Size"
                    fieldId="maxSize"
                    helperText="The maximum size of recording data retained in the target application's recording buffer."
                  >
                    <Split hasGutter={true}>
                      <SplitItem isFilled>
                        <TextInput
                          value={maxSize}
                          isDisabled={loading}
                          isRequired
                          type="number"
                          id="maxSize"
                          aria-label="max size value"
                          onChange={handleMaxSizeChange}
                          min="0"
                        />
                      </SplitItem>
                      <SplitItem>
                        <FormSelect
                          value={maxSizeUnits}
                          isDisabled={loading}
                          onChange={handleMaxSizeUnitChange}
                          aria-label="Max size units input"
                        >
                          <FormSelectOption key="1" value="1" label="B" />
                          <FormSelectOption key="2" value={1024} label="KiB" />
                          <FormSelectOption key="3" value={1024 * 1024} label="MiB" />
                        </FormSelect>
                      </SplitItem>
                    </Split>
                  </FormGroup>
                  <FormGroup
                    label="Maximum Age"
                    fieldId="maxAge"
                    helperText="The maximum age of recording data retained in the target application's recording buffer."
                  >
                    <Split hasGutter={true}>
                      <SplitItem isFilled>
                        <TextInput
                          value={maxAge}
                          isDisabled={loading}
                          isRequired
                          type="number"
                          id="maxAge"
                          aria-label="Max age duration"
                          onChange={handleMaxAgeChange}
                          min="0"
                        />
                      </SplitItem>
                      <SplitItem>
                        <FormSelect
                          value={maxAgeUnits}
                          isDisabled={loading}
                          onChange={handleMaxAgeUnitChange}
                          aria-label="Max Age units Input"
                        >
                          <FormSelectOption key="1" value="1" label="Seconds" />
                          <FormSelectOption key="2" value={60} label="Minutes" />
                          <FormSelectOption key="3" value={60 * 60} label="Hours" />
                        </FormSelect>
                      </SplitItem>
                    </Split>
                  </FormGroup>
                  <FormGroup
                    label="Archival Period"
                    fieldId="archivalPeriod"
                    helperText="Time between copies of active recording data being pulled into Cryostat archive storage."
                  >
                    <Split hasGutter={true}>
                      <SplitItem isFilled>
                        <TextInput
                          value={archivalPeriod}
                          isDisabled={loading}
                          isRequired
                          type="number"
                          id="archivalPeriod"
                          aria-label="archival period"
                          onChange={handleArchivalPeriodChange}
                          min="0"
                        />
                      </SplitItem>
                      <SplitItem>
                        <FormSelect
                          value={archivalPeriodUnits}
                          isDisabled={loading}
                          onChange={handleArchivalPeriodUnitsChange}
                          aria-label="archival period units input"
                        >
                          <FormSelectOption key="1" value="1" label="Seconds" />
                          <FormSelectOption key="2" value={60} label="Minutes" />
                          <FormSelectOption key="3" value={60 * 60} label="Hours" />
                        </FormSelect>
                      </SplitItem>
                    </Split>
                  </FormGroup>
                  <FormGroup
                    label="Initial Delay"
                    fieldId="initialDelay"
                    helperText="Initial delay before archiving starts. The first archived copy will be made this long after the recording is started. The second archived copy will occur one Archival Period later."
                  >
                    <Split hasGutter={true}>
                      <SplitItem isFilled>
                        <TextInput
                          value={initialDelay}
                          isDisabled={loading}
                          isRequired
                          type="number"
                          id="initialDelay"
                          aria-label="initial delay"
                          onChange={handleInitialDelayChange}
                          min="0"
                        />
                      </SplitItem>
                      <SplitItem>
                        <FormSelect
                          value={initialDelayUnits}
                          isDisabled={loading}
                          onChange={handleInitialDelayUnitsChanged}
                          aria-label="initial delay units input"
                        >
                          <FormSelectOption key="1" value="1" label="Seconds" />
                          <FormSelectOption key="2" value={60} label="Minutes" />
                          <FormSelectOption key="3" value={60 * 60} label="Hours" />
                        </FormSelect>
                      </SplitItem>
                    </Split>
                  </FormGroup>
                  <FormGroup
                    label="Preserved Archives"
                    fieldId="preservedArchives"
                    helperText="The number of archived recording copies to preserve in archives for each target application affected by this rule."
                  >
                    <TextInput
                      value={preservedArchives}
                      isDisabled={loading}
                      isRequired
                      type="number"
                      id="preservedArchives"
                      aria-label="preserved archives"
                      onChange={handlePreservedArchivesChange}
                      min="0"
                    />
                  </FormGroup>
                  <ActionGroup>
                    <Button
                      variant="primary"
                      onClick={handleSubmit}
                      isDisabled={
                        loading ||
                        nameValid !== ValidatedOptions.success ||
                        !templateName ||
                        !templateType ||
                        !matchExpression
                      }
                      {...createButtonLoadingProps}
                    >
                      {loading ? 'Creating' : 'Create'}
                    </Button>
                    <Button variant="secondary" onClick={history.goBack} isAriaDisabled={loading}>
                      Cancel
                    </Button>
                  </ActionGroup>
                </Form>
              </CardBody>
            </Card>
          )}
        </GridItem>
        <GridItem xl={5}>
          <Card>
            <CardBody>
              <MatchExpressionEvaluator
                inlineHint
                matchExpression={matchExpression}
                onChange={setMatchExpressionValid}
              />
            </CardBody>
          </Card>
        </GridItem>
      </Grid>
    </BreadcrumbPage>
  );
};

export const CreateRule = withRouter(Comp);

export default CreateRule;
