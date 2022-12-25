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
import { Observable, of } from 'rxjs';
import { getFromLocalStorage } from '@app/utils/LocalStorage';
import { Locations } from '@app/Settings/CredentialsStorage';
import { ApiService } from './Api.service';

export interface Credential {
  username: string;
  password: string;
}

export class JmxCredentials {
  // TODO replace with Redux?
  private readonly store = new Map<string, Credential>();

  constructor(private readonly api: () => ApiService) {}

  setCredential(targetId: string, username: string, password: string): Observable<boolean> {
    let location = getFromLocalStorage('JMX_CREDENTIAL_LOCATION', Locations.BACKEND.key);
    switch (location) {
      case Locations.BACKEND.key:
        return this.api().postCredentials(`target.connectUrl == "${targetId}"`, username, password);
      case Locations.BROWSER_SESSION.key:
        this.store.set(targetId, { username, password });
        return of(true);
      default:
        console.warn('Unknown storage location', location);
        return of(false);
    }
  }

  getCredential(targetId: string): Observable<Credential | undefined> {
    let location = getFromLocalStorage('JMX_CREDENTIAL_LOCATION', Locations.BACKEND.key);
    switch (location) {
      case Locations.BACKEND.key:
        // if this is stored on the backend then Cryostat should be using those and not prompting us to request from the user
        return of(undefined);
      case Locations.BROWSER_SESSION.key:
        return of(this.store.get(targetId));
      default:
        console.warn('Unknown storage location', location);
        return of(undefined);
    }
  }
}
