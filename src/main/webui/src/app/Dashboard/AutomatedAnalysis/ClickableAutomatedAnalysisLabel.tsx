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

import { AutomatedAnalysisScore, RuleEvaluation } from '@app/Shared/Services/Report.service';
import { Label, LabelProps, Popover } from '@patternfly/react-core';
import { InfoCircleIcon } from '@patternfly/react-icons';
import { css } from '@patternfly/react-styles';
import popoverStyles from '@patternfly/react-styles/css/components/Popover/popover';
import React from 'react';

export interface ClickableAutomatedAnalysisLabelProps {
  label: RuleEvaluation;
  isSelected: boolean;
}

export const clickableAutomatedAnalysisKey = 'clickable-automated-analysis-label';

export const ClickableAutomatedAnalysisLabel: React.FunctionComponent<ClickableAutomatedAnalysisLabelProps> = ({
  label,
  isSelected,
}) => {
  const [isHoveredOrFocused, setIsHoveredOrFocused] = React.useState(false);
  const [isDescriptionVisible, setIsDescriptionVisible] = React.useState(false);

  const handleHoveredOrFocused = React.useCallback(() => setIsHoveredOrFocused(true), [setIsHoveredOrFocused]);
  const handleNonHoveredOrFocused = React.useCallback(() => setIsHoveredOrFocused(false), [setIsHoveredOrFocused]);

  const alertStyle = {
    default: popoverStyles.modifiers.default,
    info: popoverStyles.modifiers.info,
    success: popoverStyles.modifiers.success,
    warning: popoverStyles.modifiers.warning,
    danger: popoverStyles.modifiers.danger,
  };

  const style = React.useMemo(() => {
    if (isHoveredOrFocused) {
      const defaultStyle = { cursor: 'pointer', '--pf-c-label__content--before--BorderWidth': '2.5px' };
      if (isSelected) {
        return { ...defaultStyle, '--pf-c-label__content--before--BorderColor': '#06c' };
      }
      return { ...defaultStyle, '--pf-c-label__content--before--BorderColor': '#8a8d90' };
    }
    return {};
  }, [isSelected, isHoveredOrFocused]);

  const colorScheme = React.useMemo((): LabelProps['color'] => {
    // TODO: use label color schemes based on settings for accessibility
    // context.settings.etc.
    return label.score == AutomatedAnalysisScore.NA_SCORE
      ? 'grey'
      : label.score < AutomatedAnalysisScore.ORANGE_SCORE_THRESHOLD
      ? 'green'
      : label.score < AutomatedAnalysisScore.RED_SCORE_THRESHOLD
      ? 'orange'
      : 'red';
  }, [label.score]);

  const alertPopoverVariant = React.useMemo(() => {
    return label.score == AutomatedAnalysisScore.NA_SCORE
      ? 'default'
      : label.score < AutomatedAnalysisScore.ORANGE_SCORE_THRESHOLD
      ? 'success'
      : label.score < AutomatedAnalysisScore.RED_SCORE_THRESHOLD
      ? 'warning'
      : 'danger';
  }, [label.score]);

  return (
    <Popover
      aria-label="automated-analysis-description-popover"
      isVisible={isDescriptionVisible}
      headerContent={<div className={`${clickableAutomatedAnalysisKey}-popover-header`}>{label.name}</div>}
      alertSeverityVariant={alertPopoverVariant}
      alertSeverityScreenReaderText={alertPopoverVariant}
      shouldOpen={() => setIsDescriptionVisible(true)}
      shouldClose={() => setIsDescriptionVisible(false)}
      key={`${clickableAutomatedAnalysisKey}-popover-${label.name}`}
      bodyContent={
        <div
          className={`${clickableAutomatedAnalysisKey}-popover-body`}
          key={`${clickableAutomatedAnalysisKey}-popover-body-${label.name}`}
        >
          <p className={css(alertStyle[alertPopoverVariant], `${clickableAutomatedAnalysisKey}-popover-body-score`)}>
            {label.score == AutomatedAnalysisScore.NA_SCORE ? 'N/A' : label.score.toFixed(1)}
          </p>
          <p>{label.description}</p>
        </div>
      }
    >
      <Label
        aria-label={`${label.name}`}
        icon={<InfoCircleIcon />}
        color={colorScheme}
        style={style}
        onMouseEnter={handleHoveredOrFocused}
        onMouseLeave={handleNonHoveredOrFocused}
        onFocus={handleHoveredOrFocused}
        key={`${clickableAutomatedAnalysisKey}-${label.name}`}
        isCompact
      >
        <span className={`${clickableAutomatedAnalysisKey}-name`}>{`${label.name}`}</span>
        {
          // don't use isTruncated here, it doesn't work with the popover because of helperText
        }
      </Label>
    </Popover>
  );
};
