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
import { BreadcrumbPage, BreadcrumbTrail } from '@app/BreadcrumbPage/BreadcrumbPage';
import { ServiceContext } from '@app/Shared/Services/Services';
import { TargetSelect } from '@app/TargetSelect/TargetSelect';
import { NoTargetSelected } from './NoTargetSelected';
import { Grid, GridItem } from '@patternfly/react-core';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { NO_TARGET } from '@app/Shared/Services/Target.service';

interface TargetViewProps {
  pageTitle: string;
  compactSelect?: boolean;
  breadcrumbs?: BreadcrumbTrail[];
  children: React.ReactNode;
}

export const TargetView: React.FunctionComponent<TargetViewProps> = (props) => {
  const context = React.useContext(ServiceContext);
  const [hasSelection, setHasSelection] = React.useState(false);
  const addSubscription = useSubscriptions();

  React.useEffect(() => {
    addSubscription(
      context.target.target().subscribe((target) => {
        setHasSelection(target !== NO_TARGET);
      })
    );
  }, [context.target, addSubscription, setHasSelection]);

  const compact = React.useMemo(
    () => (props.compactSelect == null ? true : props.compactSelect),
    [props.compactSelect]
  );

  return (
    <>
      <BreadcrumbPage pageTitle={props.pageTitle} breadcrumbs={props.breadcrumbs}>
        <Grid hasGutter>
          <GridItem span={compact ? 6 : 12}>
            <TargetSelect />
          </GridItem>
          <GridItem>{hasSelection ? props.children : <NoTargetSelected />}</GridItem>
        </Grid>
      </BreadcrumbPage>
    </>
  );
};
