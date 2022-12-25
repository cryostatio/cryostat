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

import { Text, TextContent, TextList, TextListItem, TextVariants } from '@patternfly/react-core';
import React from 'react';
import build from '@app/build.json';
import { ServiceContext } from '@app/Shared/Services/Services';
import { NotificationsContext } from '@app/Notifications/Notifications';

export const CRYOSTAT_TRADEMARK = 'Copyright The Cryostat Authors, The Universal Permissive License (UPL), Version 1.0';

export const AboutDescription = () => {
  const serviceContext = React.useContext(ServiceContext);
  const notificationsContext = React.useContext(NotificationsContext);
  const [cryostatVersion, setCryostatVersion] = React.useState(undefined as string | undefined);

  React.useEffect(() => {
    const sub = serviceContext.api.cryostatVersion().subscribe(setCryostatVersion);
    return () => sub.unsubscribe();
  }, [serviceContext]);

  const cryostatCommitHash = React.useMemo(() => {
    if (!cryostatVersion) {
      return;
    }
    const expr = /^(?<describe>[a-zA-Z0-9-_.]+-[0-9]+-[a-z0-9]+)(?:-dirty)?$/;
    const result = cryostatVersion.match(expr);
    if (!result) {
      notificationsContext.warning(
        'Cryostat Version Parse Failure',
        `Could not parse Cryostat version string '${cryostatVersion}'.`
      );
      return 'main';
    }
    return result.groups?.describe || 'main';
  }, [cryostatVersion, notificationsContext]);

  const versionComponent = React.useMemo(() => {
    if (build.commitHashUrl) {
      return (
        <Text
          component={TextVariants.a}
          target="_blank"
          href={build.commitHashUrl.replace('__REPLACE_HASH__', cryostatCommitHash || '')}
        >
          {cryostatVersion}
        </Text>
      );
    } else {
      return <Text component={TextVariants.p}>{cryostatVersion}</Text>;
    }
  }, [build.commitHashUrl, cryostatCommitHash]);

  return (
    <>
      <TextContent>
        <TextList component="dl">
          <TextListItem component="dt">Version</TextListItem>
          <TextListItem component="dd">{versionComponent}</TextListItem>
          <TextListItem component="dt">Homepage</TextListItem>
          <TextListItem component="dd">
            <Text component={TextVariants.a} target="_blank" href={build.homePageUrl}>
              cryostat.io
            </Text>
          </TextListItem>
          <TextListItem component="dt">Bugs</TextListItem>
          <TextListItem component="dd">
            <Text>
              <Text component={TextVariants.a} target="_blank" href={build.knownIssuesUrl}>
                Known Issues
              </Text>
              &nbsp;|&nbsp;
              <Text
                component={TextVariants.a}
                target="_blank"
                href={build.fileIssueUrl.replace('__REPLACE_VERSION__', cryostatVersion || 'unknown')}
              >
                File a Report
              </Text>
            </Text>
          </TextListItem>
          <TextListItem component="dt">Mailing List</TextListItem>
          <TextListItem component="dd">
            <Text component={TextVariants.a} target="_blank" href={build.mailingListUrl}>
              {build.mailingListName}
            </Text>
          </TextListItem>
          <TextListItem component="dt">Open Source License</TextListItem>
          <TextListItem component="dd">
            <Text component={TextVariants.a} target="_blank" href={build.licenseUrl}>
              License
            </Text>
          </TextListItem>
        </TextList>
      </TextContent>
    </>
  );
};
