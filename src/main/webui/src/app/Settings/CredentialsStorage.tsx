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
import { Link } from 'react-router-dom';
import { Select, SelectOption, SelectVariant, Text } from '@patternfly/react-core';
import { UserSetting } from './Settings';
import { getFromLocalStorage, saveToLocalStorage } from '@app/utils/LocalStorage';

export interface Location {
  key: string;
  description: string;
}

export class Locations {
  static readonly BROWSER_SESSION: Location = {
    key: 'Session (Browser Memory)',
    description:
      'Keep credentials in browser memory for the current session only. When you close this browser tab the credentials will be forgotten.',
  };
  static readonly BACKEND: Location = {
    key: 'Backend',
    description:
      'Keep credentials in encrypted Cryostat backend storage. These credentials will be available to other users and will be used for Automated Rules.',
  };
}

const locations = [Locations.BROWSER_SESSION, Locations.BACKEND];

const getLocation = (key: string): Location => {
  for (let l of locations) {
    if (l.key === key) {
      return l;
    }
  }
  return Locations.BACKEND;
};

const Component = () => {
  const [isExpanded, setExpanded] = React.useState(false);
  const [selection, setSelection] = React.useState(Locations.BACKEND.key);

  const handleSelect = React.useCallback(
    (_, selection) => {
      let location = getLocation(selection);
      setSelection(location.key);
      setExpanded(false);
      saveToLocalStorage('JMX_CREDENTIAL_LOCATION', selection);
    },
    [getLocation, setSelection, setExpanded, saveToLocalStorage]
  );

  React.useEffect(() => {
    handleSelect(undefined, getFromLocalStorage('JMX_CREDENTIAL_LOCATION', Locations.BACKEND.key));
  }, [handleSelect, getFromLocalStorage]);

  return (
    <>
      <Select
        variant={SelectVariant.single}
        onToggle={setExpanded}
        onSelect={handleSelect}
        isOpen={isExpanded}
        selections={selection}
      >
        {locations.map((location) => (
          <SelectOption key={location.key} value={location.key} description={location.description} />
        ))}
      </Select>
    </>
  );
};

export const CredentialsStorage: UserSetting = {
  title: 'JMX Credentials Storage',
  description: (
    <Text>
      When you attempt to connect to a target application which requires authentication, you will see a prompt for
      credentials to present to the application and complete the connection. You can choose where to persist these
      credentials. Any credentials added through the <Link to="/security">Security</Link> panel will always be stored in
      Cryostat backend encrypted storage.
    </Text>
  ),
  content: Component,
};
